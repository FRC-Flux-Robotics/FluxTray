package frc.flux.tray;

import frc.flux.tray.BatteryAndRunManager.CompletedRunBundle;
import frc.flux.tray.DriveWatcher.PollOutcome;
import frc.lib.fluxfig.FluxFig;
import frc.lib.fluxfig.FluxSystemSnapshot;
import frc.lib.fluxfig.FluxSystemSnapshot.WatchDecision;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * Entry point for {@code FluxTray.jar} — the Driver Station System Tray Agent, live NT4 safety
 * lockout &amp; auto-pull daemon, two-folder Google Drive watcher ({@code code/} + {@code logs/}),
 * real {@code 16 kHz} PCM {@code .wav} audio recorder, and CLI tool for FRC Team 10413.
 */
public final class FluxTrayMain {

  public static final String DEFAULT_DS_LOGS_DIR = "C:\\Users\\Public\\Documents\\FRC\\Log Files";

  public enum TrayStatus {
    READY(new Color(0x22, 0xC5, 0x5E), "READY (Disabled)"),
    ENABLED(new Color(0xEF, 0x44, 0x44), "LOCKOUT (Robot Enabled / FMS)"),
    SYNCING(new Color(0xF5, 0x9E, 0x0B), "SYNCING (Logs / Deploy)"),
    DISCONNECTED(new Color(0x6B, 0x72, 0x80), "DISCONNECTED");

    private final Color color;
    private final String label;

    TrayStatus(Color color, String label) {
      this.color = color;
      this.label = label;
    }

    public Color color() {
      return color;
    }

    public String label() {
      return label;
    }
  }

  private FluxTrayMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
      printUsage();
      return;
    }
    if (args.length == 0) {
      if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) {
        launchSystemTray(args);
      } else {
        executePullAndPackageLogs(args);
      }
      return;
    }

    String cmd = args[0];
    switch (cmd) {
      case "--print-ssh-capture-cmd" -> System.out.println(FluxSystemSnapshot.REMOTE_CAPTURE_SH_COMMAND);
      case "--inspect-snapshot" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException("Usage: --inspect-snapshot <system-snapshot.txt>");
        }
        Path snapPath = Path.of(args[1]);
        String raw = Files.readString(snapPath, StandardCharsets.UTF_8);
        FluxSystemSnapshot snap = FluxSystemSnapshot.parse(raw);
        printSnapshotAudit(snapPath.toString(), snap);
      }
      case "--restore-stable" -> {
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        RobotTransport robot = parseTransportArgs(args, lockout, false);
        PackageDeployer deployer = new PackageDeployer(lockout, resolveLaptopId());
        deployer.restoreStable(robot);
        System.out.println(
            "Restored jars/prev_stable.jar + deploy/ and restarted robot on "
                + robot.controllerPlatform());
      }
      case "--rollback" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --rollback <1..5> [--host <ip>] [--roborio|--systemcore]");
        }
        int slot = Integer.parseInt(args[1]);
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        RobotTransport robot = parseTransportArgs(args, lockout, false);
        PackageDeployer deployer = new PackageDeployer(lockout, resolveLaptopId());
        deployer.rollbackToSlot(slot, robot);
        System.out.println(
            "Rolled back to jars/prev_"
                + slot
                + ".jar + deploy/ and restarted robot on "
                + robot.controllerPlatform());
      }
      case "--deploy" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --deploy <package.fluxpkg.zip> [--host <ip>] [--roborio|--systemcore] [--force]");
        }
        Path pkg = Path.of(args[1]);
        boolean force = hasFlag(args, "--force");
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        RobotTransport robot = parseTransportArgs(args, lockout, false);
        PackageDeployer deployer = new PackageDeployer(lockout, resolveLaptopId());
        var stamp = deployer.deployPackage(pkg, robot, force);
        System.out.println(
            "Deployed "
                + stamp.packageName()
                + " ["
                + stamp.channel()
                + "] (git="
                + stamp.gitSha()
                + ", config="
                + stamp.configSha()
                + ") and restarted robot");
      }
      case "--set-mode" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --set-mode <PRODUCTION|TUNING> [--profile <profile>] [--config-sha <sha>] [--host <ip>] [--roborio|--systemcore]");
        }
        FluxFig.Mode mode = FluxFig.Mode.valueOf(args[1].trim().toUpperCase(Locale.ROOT));
        String profile = getFlagValue(args, "--profile", null);
        String configSha = getFlagValue(args, "--config-sha", null);
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        RobotTransport robot = parseTransportArgs(args, lockout, false);
        PackageDeployer deployer = new PackageDeployer(lockout, resolveLaptopId());
        FluxFig.ActiveProfileDirective directive =
            deployer.switchActiveMode(profile, mode, configSha, robot);
        System.out.println(
            "Switched robot to mode="
                + directive.mode()
                + ", profile="
                + directive.profile()
                + ", config="
                + directive.expectedConfigSha256());
      }
      case "--push-configs" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --push-configs <config-dir> [--profile <profile>] [--mode <PRODUCTION|TUNING>] [--host <ip>] [--roborio|--systemcore]");
        }
        Path configDir = Path.of(args[1]);
        String profile = getFlagValue(args, "--profile", null);
        FluxFig.Mode mode =
            FluxFig.Mode.valueOf(
                getFlagValue(args, "--mode", "PRODUCTION").trim().toUpperCase(Locale.ROOT));
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        RobotTransport robot = parseTransportArgs(args, lockout, false);
        PackageDeployer deployer = new PackageDeployer(lockout, resolveLaptopId());
        String sha = deployer.pushFluxFigConfigs(configDir, profile, mode, robot);
        System.out.println(
            "Pushed FluxFig configs ("
                + sha
                + ") with mode="
                + mode
                + " and restarted robot on "
                + robot.controllerPlatform());
      }
      case "--promote-tuning" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --promote-tuning <config-dir> [--author <name>] [--notes <text>] [--host <ip>] [--roborio|--systemcore]");
        }
        Path configDir = Path.of(args[1]);
        String author = getFlagValue(args, "--author", "pit-crew");
        String notes = getFlagValue(args, "--notes", "");
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        RobotTransport robot = parseTransportArgs(args, lockout, false);
        PackageDeployer deployer = new PackageDeployer(lockout, resolveLaptopId());
        FluxFig.ReleaseEntry release =
            deployer.promoteRemoteTuningSnapshot(configDir, author, notes, robot);
        System.out.println(
            "Promoted remote tuning snapshot -> "
                + release.version()
                + " (config="
                + release.configSha256()
                + ", author="
                + release.author()
                + ", notes="
                + release.notes()
                + ")");
      }
      case "--pull-logs",
          "--dir",
          "--file",
          "--zip",
          "--out",
          "--output",
          "--run-id",
          "--robot",
          "--ds-logs",
          "--host",
          "--roborio",
          "--systemcore" ->
          executePullAndPackageLogs(args);
      case "--sync-pending" -> {
        Path hubDir =
            (args.length >= 2 && !args[1].startsWith("--"))
                ? Path.of(args[1])
                : Path.of(getFlagValue(args, "--hub", BatteryAndRunManager.DEFAULT_HUB_DIR_NAME));
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        RobotTransport robot = parseTransportArgs(args, lockout, false);
        MultiMicAudioRecorder audio = new MultiMicAudioRecorder();
        BatteryAndRunManager mgr = new BatteryAndRunManager(hubDir, lockout, audio);
        List<CompletedRunBundle> synced = mgr.syncPendingFmsRuns(robot);
        System.out.println("Synced " + synced.size() + " pending FMS run bundle(s).");
        for (CompletedRunBundle b : synced) {
          System.out.println("Synced logs    -> " + b.runDir().toAbsolutePath());
          System.out.println("Packaged ZIP   -> " + b.zipArchive().toAbsolutePath());
          printSnapshotAudit(b.runId(), b.systemSnapshot());
        }
      }
      case "--self-test" -> runSelfTest();
      case "--robot-status" -> printRobotStatus(args);
      case "--version" -> System.out.println("FluxTray " + GitHubReleaseUpdater.detectCurrentVersion());
      case "--check-update" -> {
        String repo = getFlagValue(args, "--update-repo", GitHubReleaseUpdater.resolveDefaultRepo());
        GitHubReleaseUpdater updater =
            new GitHubReleaseUpdater(repo, GitHubReleaseUpdater.detectCurrentVersion());
        var outcome = updater.checkForUpdate();
        System.out.println(outcome.message());
      }
      case "--self-update" -> {
        String repo = getFlagValue(args, "--update-repo", GitHubReleaseUpdater.resolveDefaultRepo());
        GitHubReleaseUpdater updater =
            new GitHubReleaseUpdater(repo, GitHubReleaseUpdater.detectCurrentVersion());
        Path runningJar =
            GitHubReleaseUpdater.resolveRunningJarPath()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Cannot --self-update when not running from a packaged FluxTray.jar"));
        var outcome = updater.checkAndStageUpdate(runningJar, List.of("--version"), false, () -> true);
        System.out.println(outcome.message());
        if (outcome.stagedForRestart()) {
          System.exit(0);
        }
      }
      case "--tray",
          "--code-dir",
          "--logs-dir",
          "--auto-update",
          "--no-auto-update",
          "--update-repo" ->
          launchSystemTray(args);
      default -> {
        System.err.println("Unknown option: " + cmd);
        printUsage();
        System.exit(1);
      }
    }
  }

  private static void printRobotStatus(String[] args) throws IOException {
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    RobotTransport robot = parseTransportArgs(args, lockout, false);
    PackageDeployer.RobotCodeStatus status = PackageDeployer.inspectRobotCode(robot);
    System.out.println("Robot Code Status : " + status.summary());
    System.out.println("Generation        : " + status.generation());
    System.out.println("Platform          : " + status.platform());
    if (status.generation() != PackageDeployer.CodeGeneration.UNREACHABLE) {
      System.out.println("Active JAR        : " + status.activeJar());
      System.out.println(
          "Heartbeat (/tmp/flux-robot-state): "
              + (status.heartbeat() != null && status.heartbeat().present()
                  ? status.heartbeat().state() + " (robotId=" + status.heartbeat().robotId() + ")"
                  : "ABSENT"));
      System.out.println(
          "Deploy Stamp      : "
              + (status.deployStamp() != null
                  ? status.deployStamp().packageName()
                      + " ["
                      + status.deployStamp().channel()
                      + "] git="
                      + status.deployStamp().gitSha()
                  : "ABSENT"));
      System.out.println("prev_stable.jar   : " + (status.hasPrevStable() ? "PRESENT" : "ABSENT"));
      RobotTransport.RobotDiagnostics d = status.diagnostics();
      if (d != null) {
        System.out.println(
            "Internal Storage  : "
                + (d.internalFreeMb() >= 0L
                    ? d.internalFreeMb() + " MB free / " + d.internalTotalMb() + " MB total"
                    : "unknown"));
        System.out.println(
            "USB Stick         : "
                + (d.usbMounted()
                    ? d.usbFreeMb() + " MB free / " + d.usbTotalMb() + " MB total"
                    : "not mounted"));
        System.out.println(
            String.format(
                Locale.US,
                "Logs on Robot     : %d .wpilog (%.1f MB), %d .hoot (%.1f MB)",
                d.wpilogCount(),
                d.wpilogBytes() / (1024.0 * 1024.0),
                d.hootCount(),
                d.hootBytes() / (1024.0 * 1024.0)));
        System.out.println(
            "Robot Java PID    : " + (d.javaPid() > 0L ? d.javaPid() : "not running"));
        if (d.uptimeLine() != null && !d.uptimeLine().isBlank()) {
          System.out.println("Uptime            : " + d.uptimeLine());
        }
      }
    }
    if (status.generation() == PackageDeployer.CodeGeneration.UNREACHABLE) {
      System.exit(2);
    }
  }

  private static void executePullAndPackageLogs(String[] args) throws IOException {
    Path hubDir = Path.of(getFlagValue(args, "--hub", BatteryAndRunManager.DEFAULT_HUB_DIR_NAME));
    Path customOutputDir = null;
    String customZipFileName = null;

    if (args.length >= 2 && "--pull-logs".equals(args[0]) && !args[1].startsWith("--")) {
      Path posPath = Path.of(args[1]);
      if (posPath.getFileName() != null
          && (BatteryAndRunManager.DEFAULT_HUB_DIR_NAME.equalsIgnoreCase(
                  posPath.getFileName().toString())
              || Files.isDirectory(posPath.resolve("03_FluxFig_Configs"))
              || Files.isDirectory(
                  posPath.resolve(BatteryAndRunManager.DEFAULT_LOGS_SUBDIR_NAME)))) {
        hubDir = posPath;
      } else {
        customOutputDir = posPath;
      }
    }

    String dirFlag = getFlagValue(args, "--dir", null);
    if (dirFlag != null && !dirFlag.isBlank()) {
      customOutputDir = Path.of(dirFlag);
    }

    String fileFlag = getFlagValue(args, "--file", getFlagValue(args, "--zip", null));
    if (fileFlag != null && !fileFlag.isBlank()) {
      Path fp = Path.of(fileFlag);
      if (fp.getParent() != null) {
        customOutputDir = fp.getParent();
        customZipFileName = fp.getFileName().toString();
      } else {
        customZipFileName = fileFlag;
      }
    }

    String outFlag = getFlagValue(args, "--out", getFlagValue(args, "--output", null));
    if (outFlag != null && !outFlag.isBlank()) {
      Path op = Path.of(outFlag);
      if (outFlag.toLowerCase(Locale.ROOT).endsWith(".zip")) {
        if (op.getParent() != null) {
          customOutputDir = op.getParent();
        }
        customZipFileName = op.getFileName().toString();
      } else {
        customOutputDir = op;
      }
    }

    long nowMs = System.currentTimeMillis();
    String runId = getFlagValue(args, "--run-id", "run_" + nowMs);
    String robotName = getFlagValue(args, "--robot", null);
    Path dsLogDir = Path.of(getFlagValue(args, "--ds-logs", DEFAULT_DS_LOGS_DIR));
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    RobotTransport robot = parseTransportArgs(args, lockout, false);
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder();
    BatteryAndRunManager mgr = new BatteryAndRunManager(hubDir, lockout, audio);
    CompletedRunBundle bundle =
        mgr.pullManualRunLogs(
            runId,
            robotName,
            "",
            "",
            robot,
            dsLogDir,
            null,
            nowMs,
            customOutputDir,
            customZipFileName);
    System.out.println("Pulled logs    -> " + bundle.runDir().toAbsolutePath());
    System.out.println("Packaged ZIP   -> " + bundle.zipArchive().toAbsolutePath());
    printSnapshotAudit(bundle.runId(), bundle.systemSnapshot());
  }

  public static void printSnapshotAudit(String label, FluxSystemSnapshot snap) {
    List<WatchDecision> decisions = snap.evaluate();
    System.out.println("======================================================================");
    System.out.printf(
        Locale.US,
        " FLUX 10413 Offline System Watch Audit — %s (%s)%n",
        label,
        snap.controllerType());
    System.out.println("======================================================================");
    System.out.printf(
        Locale.US,
        " Loop Period: %s (p50=%s, p99=%s, max=%s, 1stEnable=%s)%n",
        FluxSystemSnapshot.formatMetric(snap.loopPeriodMs(), "%.1f", " ms"),
        FluxSystemSnapshot.formatMetric(snap.loopMedianMs(), "%.2f", " ms"),
        FluxSystemSnapshot.formatMetric(snap.loopP99Ms(), "%.2f", " ms"),
        FluxSystemSnapshot.formatMetric(snap.loopMaxMs(), "%.2f", " ms"),
        FluxSystemSnapshot.formatMetric(snap.firstEnablePeakMs(), "%.2f", " ms"));
    System.out.printf(
        Locale.US,
        " CPU Load:    Total=%s (Java=%s, Vision C++=%s, softirq=%s)%n",
        FluxSystemSnapshot.formatMetric(snap.totalCpuPct(), "%.1f", "%"),
        FluxSystemSnapshot.formatMetric(snap.javaProcessCpuPct(), "%.1f", "%"),
        FluxSystemSnapshot.formatMetric(snap.visionDaemonCpuPct(), "%.1f", "%"),
        FluxSystemSnapshot.formatMetric(snap.softIrqPct(), "%.1f", "%"));
    System.out.printf(
        Locale.US,
        " Thermal/Pwr: %s @ %s (throttled=%s) | Free Storage: %s%n",
        FluxSystemSnapshot.formatMetric(snap.cpuTempC(), "%.1f", " °C"),
        FluxSystemSnapshot.formatMetric(snap.cpuFreqMHz(), "%.0f", " MHz"),
        FluxSystemSnapshot.formatHexFlags(snap.throttledFlags()),
        FluxSystemSnapshot.formatMetric(snap.freeStorageMb(), "%.0f", " MB"));
    System.out.printf(
        Locale.US,
        " JVM & PMU:   Alloc=%s, GCs=%s (GC Overruns=%s) | IPC=%s, LLC Miss=%s%n",
        FluxSystemSnapshot.formatMetric(snap.heapAllocRateMbPerSec(), "%.2f", " MB/s"),
        FluxSystemSnapshot.formatCounter(snap.gcCount(), ""),
        FluxSystemSnapshot.formatCounter(snap.gcCorrelatedLoopSpikes(), ""),
        FluxSystemSnapshot.formatMetric(snap.ipc(), "%.2f", ""),
        FluxSystemSnapshot.formatMetric(snap.llcMissPct(), "%.1f", "%"));
    System.out.println("----------------------------------------------------------------------");
    if (decisions.isEmpty()) {
      System.out.println(" VERDICT: NOMINAL (0 watch metric anomalies detected)");
    } else {
      System.out.println(
          " VERDICT: " + decisions.size() + " ACTIONABLE WATCH DECISION(S) TRIGGERED:");
      for (int i = 0; i < decisions.size(); i++) {
        WatchDecision d = decisions.get(i);
        System.out.printf(
            Locale.US,
            "  %d. [%s] %s (%s)%n     Value: %s (Threshold: %s)%n     Why:   %s%n     -> %s%n",
            i + 1,
            d.severity(),
            d.metricName(),
            d.category(),
            d.measuredValue(),
            d.threshold(),
            d.anomalySummary(),
            d.actionableDecision());
      }
    }
    System.out.println("======================================================================");
  }

  /** Renders a 16x16 color-coded status icon (Green Ready / Red Enabled / Yellow Syncing / Gray Disconnected). */
  public static BufferedImage createStatusIcon(TrayStatus status) {
    BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(status.color());
      g.fillOval(1, 1, 14, 14);
      g.setColor(new Color(0x21, 0x20, 0x20));
      g.drawOval(1, 1, 13, 13);
      g.setColor(Color.WHITE);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
      g.drawString("F", 5, 12);
    } finally {
      g.dispose();
    }
    return img;
  }

  /** Appends a timestamped diagnostic entry to persistent {@code fluxtray.log}. */
  public static synchronized void appendTrayLog(Path logFile, String level, String message) {
    if (logFile == null) {
      return;
    }
    try {
      if (logFile.getParent() != null) {
        Files.createDirectories(logFile.getParent());
      }
      String line = Instant.now() + " [" + level + "] " + message + System.lineSeparator();
      Files.writeString(
          logFile,
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
    }
  }

  /** Auto-update is opt-in ({@code --auto-update} or {@code -Dflux.autoupdate=true}); OFF by default. */
  static boolean isAutoUpdateEnabled(String[] args) {
    if (args != null && hasFlag(args, "--no-auto-update")) {
      return false;
    }
    if (args != null && hasFlag(args, "--auto-update")) {
      return true;
    }
    return "true".equalsIgnoreCase(System.getProperty("flux.autoupdate", "false"));
  }

  /** Exclusive OS-level file lock preventing duplicate competing FluxTray tray daemons. */
  static final class SingleInstanceLock implements Closeable {
    public static final String LOCK_FILE_NAME = "fluxtray.lock";

    private final Path lockFile;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private SingleInstanceLock(Path lockFile, FileChannel channel, FileLock lock) {
      this.lockFile = lockFile;
      this.channel = channel;
      this.lock = lock;
    }

    static Optional<SingleInstanceLock> tryAcquire(Path lockFile) throws IOException {
      if (lockFile.getParent() != null) {
        Files.createDirectories(lockFile.getParent());
      }
      FileChannel channel =
          FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException alreadyHeldInJvm) {
        channel.close();
        return Optional.empty();
      } catch (IOException | RuntimeException ex) {
        try {
          channel.close();
        } catch (IOException suppressed) {
          ex.addSuppressed(suppressed);
        }
        throw ex;
      }
      if (lock == null) {
        channel.close();
        return Optional.empty();
      }
      try {
        channel.truncate(0L);
        byte[] pidBytes =
            (ProcessHandle.current().pid() + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        channel.write(ByteBuffer.wrap(pidBytes));
        channel.force(false);
      } catch (IOException ignored) {
      }
      return Optional.of(new SingleInstanceLock(lockFile, channel, lock));
    }

    Path lockFile() {
      return lockFile;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      try {
        if (lock.isValid()) {
          lock.release();
        }
      } catch (IOException ignored) {
      }
      try {
        channel.close();
      } catch (IOException ignored) {
      }
    }
  }

  static void runSelfTest() throws IOException {
    Path tmp = Files.createTempDirectory("fluxtray-selftest-");
    try {
      MockRobotServer mock = new MockRobotServer(tmp.resolve("robot"), "roborio-2026");
      mock.seedSimulatedMatchLogs("2026-11-20_16-08-12", "FUEL", "a1b2c3d4");
      mock.setNextSnapshotRaw(
          "[PLATFORM]\ncontrollerType=ROBORIO\nhostname=roboRIO-10413-FRC\n[CPU_AND_IRQ]\ntotalCpuPct=24.5\n[STORAGE]\nfreeStorageMb=480.0\n");
      FluxSystemSnapshot baseline = mock.captureSystemSnapshot();
      if (!baseline.evaluate().isEmpty()) {
        throw new IllegalStateException("Self-test healthy baseline reported unexpected alerts");
      }

      FluxSystemSnapshot anomalous =
          FluxSystemSnapshot.parse(
              "[PLATFORM]\ncontrollerType=ROBORIO\nhostname=roboRIO-10413-FRC\n"
                  + "[CPU_AND_IRQ]\ntotalCpuPct=96.0\n[THERMAL]\ncpuTempC=92.0\n[STORAGE]\nfreeStorageMb=12.0\n");
      if (anomalous.evaluate().isEmpty()) {
        throw new IllegalStateException("Self-test anomalous snapshot failed to flag watch alerts");
      }

      Path validCfg = tmp.resolve("cfg-valid");
      Files.createDirectories(validCfg);
      Files.writeString(
          validCfg.resolve("parameters.csv"),
          "Key,DEFAULT,FUEL\ncontrollerProfile,ROBORIO_CANIVORE_2026,ROBORIO_CANIVORE_2026\n"
              + "swerveCanBus,Drivetrain,Drivetrain\nmechCanBus,Mech,Mech\nloopPeriodMs,20.0,20.0\n",
          StandardCharsets.UTF_8);
      Files.writeString(
          validCfg.resolve("mechanisms.csv"),
          "robot,mechanism,motorId,canBus,statorLimitAmps,supplyLimitAmps,slipCurrentAmps,gravityType,kP,kI,kD,kS,kV,kA,kG,minPosRot,maxPosRot,followerMotorId,invertFollower,motorInverted,gearRatio\n"
              + "FUEL,Elevator,10,Mech,60.0,40.0,60.0,Elevator_Static,2.4,0.0,0.1,0.18,0.12,0.01,0.35,0.0,48.0,11,false,false,1.0\n",
          StandardCharsets.UTF_8);
      List<String> validViolations = new FluxFig(validCfg).validateStrict();
      if (!validViolations.isEmpty()) {
        throw new IllegalStateException("Self-test valid FluxFig failed: " + validViolations);
      }

      Path fakeJar = tmp.resolve("Flux-RoboRIO-2027.jar");
      Files.writeString(fakeJar, "SELFTEST_JAR", StandardCharsets.UTF_8);
      Path pkgZip =
          PackageDeployer.createFluxPkgZip(
              tmp.resolve("STABLE_selftest_roborio-2026_fluxpkg.zip"),
              new PackageDeployer.PackageManifest(
                  "STABLE_selftest", "roborio-2026", "STABLE", "abc1234", false, "cfg1234", "self-test"),
              fakeJar,
              validCfg);
      PackageDeployer.PackageManifest parsedManifest =
          PackageDeployer.validatePackageArchiveLocally(pkgZip, "roborio-2026");
      if (!"STABLE_selftest".equals(parsedManifest.packageName())) {
        throw new IllegalStateException("Self-test package manifest mismatch");
      }
      if (!"Flux-RoboRIO-2027.jar"
          .equals(
              PackageDeployer.parseJarPathFromRobotCommand(
                  "java -jar /home/lvuser/Flux-RoboRIO-2027.jar"))) {
        throw new IllegalStateException("Self-test robotCommand parser mismatch");
      }

      RobotSafetyLockout lockout = new RobotSafetyLockout();
      lockout.updateState(true, false, System.currentTimeMillis());
      boolean blockedEnabled = false;
      try {
        lockout.assertSafeForTransfer("self-test");
      } catch (IllegalStateException expected) {
        blockedEnabled = true;
      }
      if (!blockedEnabled) {
        throw new IllegalStateException("Self-test RobotSafetyLockout failed to block Enabled");
      }
      RobotTransport.RobotStateHeartbeat hb =
          RobotTransport.RobotStateHeartbeat.parse(
              "STATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
                  + System.currentTimeMillis()
                  + "\nROBOT_ID=FUEL\nPID=1234\n");
      if (!hb.valid() || hb.enabled()) {
        throw new IllegalStateException("Self-test heartbeat parser failed");
      }

      if (!GitHubReleaseUpdater.isNewerVersion("2027.2.0-ct-handoff", "v2027.2.0-ct-handoff-2")
          || GitHubReleaseUpdater.isNewerVersion("2027.2.0", "v2027.2.0+build.1")) {
        throw new IllegalStateException("Self-test SemVer updater check failed");
      }
      var parsedRelease =
          GitHubReleaseUpdater.parseLatestReleaseJson(
              "{\"tag_name\":\"v2027.2.0\",\"body\":\"See \\\"assets\\\" []\",\"draft\":false,\"prerelease\":false,"
                  + "\"assets\":[{\"name\":\"FluxTray.jar\",\"browser_download_url\":\"https://example.invalid/FluxTray.jar\"}]}");
      if (parsedRelease.isEmpty() || parsedRelease.get().jarDownloadUrl() == null) {
        throw new IllegalStateException("Self-test release JSON parser failed");
      }

      printSnapshotAudit("SelfTest-RoboRIO-FUEL", baseline);
    } finally {
      try (Stream<Path> walk = Files.walk(tmp)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                  }
                });
      } catch (IOException ignored) {
      }
    }
  }

  /** Computes the displayed tray icon color without leaving a disconnected robot stuck Red. */
  static TrayStatus computeTrayStatus(
      boolean ntConnected, boolean displayedEnabled, boolean displayedFms, boolean busySyncing) {
    if (!ntConnected) {
      return busySyncing ? TrayStatus.SYNCING : TrayStatus.DISCONNECTED;
    }
    if (displayedEnabled || displayedFms) {
      return TrayStatus.ENABLED;
    }
    if (busySyncing) {
      return TrayStatus.SYNCING;
    }
    return TrayStatus.READY;
  }

  static void runOnEdt(Runnable action) {
    if (GraphicsEnvironment.isHeadless() || EventQueue.isDispatchThread()) {
      action.run();
    } else {
      EventQueue.invokeLater(action);
    }
  }

  static void removeTrayIconSynchronously(SystemTray tray, TrayIcon icon) {
    if (tray == null || icon == null) {
      return;
    }
    if (GraphicsEnvironment.isHeadless() || EventQueue.isDispatchThread()) {
      tray.remove(icon);
      return;
    }
    try {
      EventQueue.invokeAndWait(() -> tray.remove(icon));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (InvocationTargetException ignored) {
    }
  }

  /**
   * Serializes all tray actions (auto-pulls, DriveWatcher polls, manual log pulls, and manual
   * Restore STABLE) onto one single-thread worker so Drive polls never race in-flight transfers,
   * while signaling {@code busySyncing} only during real robot transfers.
   */
  static final class TrayActionCoordinator implements Closeable {
    private final RobotTransport robot;
    private final PackageDeployer deployer;
    private final BatteryAndRunManager runMgr;
    private final MultiMicAudioRecorder audio;
    private final RobotSafetyLockout lockout;
    private final Path dsLogDir;
    private final Path logsDir;
    private final Path logFile;
    private final DriveWatcher.NotificationSink notify;
    private final Consumer<Boolean> busyListener;
    private final AtomicBoolean busySyncing = new AtomicBoolean(false);
    private final AtomicBoolean drivePollQueued = new AtomicBoolean(false);
    private final AtomicBoolean updateCheckQueued = new AtomicBoolean(false);
    private final ExecutorService actionExecutor;

    TrayActionCoordinator(
        RobotTransport robot,
        PackageDeployer deployer,
        BatteryAndRunManager runMgr,
        MultiMicAudioRecorder audio,
        RobotSafetyLockout lockout,
        Path dsLogDir,
        Path logsDir,
        Path logFile,
        DriveWatcher.NotificationSink notify,
        Consumer<Boolean> busyListener) {
      this.robot = robot;
      this.deployer = deployer;
      this.runMgr = runMgr;
      this.audio = audio;
      this.lockout = lockout;
      this.dsLogDir = dsLogDir;
      this.logsDir = logsDir;
      this.logFile = logFile;
      this.notify = notify != null ? notify : (lvl, title, msg) -> {};
      this.busyListener = busyListener != null ? busyListener : ignored -> {};
      this.actionExecutor =
          Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "fluxtray-action-worker");
                t.setDaemon(true);
                return t;
              });
    }

    boolean isBusySyncing() {
      return busySyncing.get();
    }

    void setBusySyncing(boolean busy) {
      if (busySyncing.getAndSet(busy) != busy) {
        busyListener.accept(busy);
      }
    }

    Future<?> submitAutoPull() {
      return actionExecutor.submit(
          () -> {
            try {
              while (audio.isRecording()
                  && audio.isMicAvailable()
                  && audio.remainingPostDisableMs(System.currentTimeMillis()) > 0L) {
                if (lockout.isEnabled() || lockout.isFmsAttached()) {
                  appendTrayLog(
                      logFile, "WARN", "Aborted post-disable debrief wait: robot re-enabled");
                  return;
                }
                Thread.sleep(200L);
              }
              if (lockout.isEnabled() || lockout.isFmsAttached()) {
                appendTrayLog(logFile, "WARN", "Aborted auto-pull: robot re-enabled");
                return;
              }
              setBusySyncing(true);
              try {
                long pullNow = System.currentTimeMillis();
                CompletedRunBundle bundle =
                    runMgr.pullDriveSafeRunZip(
                        "auto_" + pullNow,
                        null,
                        robot,
                        dsLogDir,
                        logsDir,
                        pullNow,
                        true,
                        true,
                        false);
                if (bundle != null && bundle.zipArchive() != null) {
                  notify.notify(
                      "INFO",
                      "FluxTray Auto-Pull Complete",
                      "Saved " + bundle.zipArchive().getFileName() + " to " + logsDir);
                }
              } finally {
                setBusySyncing(false);
              }
            } catch (Exception ex) {
              notify.notify("ERROR", "FluxTray Auto-Pull Failed", ex.getMessage());
            }
          });
    }

    Future<?> submitDrivePollIfIdle(DriveWatcher driveWatcher, LongSupplier clockMs) {
      if (driveWatcher == null || lockout.isEnabled() || lockout.isFmsAttached()) {
        return null;
      }
      if (!drivePollQueued.compareAndSet(false, true)) {
        return null;
      }
      return actionExecutor.submit(
          () -> {
            try {
              if (lockout.isEnabled() || lockout.isFmsAttached()) {
                return;
              }
              PollOutcome outcome = driveWatcher.pollOnce(clockMs.getAsLong());
              if (outcome.action() != DriveWatcher.ActionType.NONE) {
                appendTrayLog(
                    logFile, "INFO", "DriveWatcher " + outcome.action() + ": " + outcome.summary());
              }
            } catch (Exception ex) {
              notify.notify("ERROR", "FluxTray Drive Sync Error", ex.getMessage());
            } finally {
              drivePollQueued.set(false);
            }
          });
    }

    Future<?> submitManualPull() {
      return actionExecutor.submit(
          () -> {
            setBusySyncing(true);
            try {
              long nowMs = System.currentTimeMillis();
              CompletedRunBundle bundle =
                  runMgr.pullDriveSafeRunZip(
                      "manual_" + nowMs,
                      null,
                      robot,
                      dsLogDir,
                      logsDir,
                      nowMs,
                      false,
                      false,
                      false);
              if (bundle != null && bundle.zipArchive() != null) {
                notify.notify(
                    "INFO",
                    "FluxTray Pull Complete",
                    "Saved " + bundle.zipArchive().getFileName());
              }
            } catch (Exception ex) {
              notify.notify("ERROR", "Pull Logs Failed", ex.getMessage());
            } finally {
              setBusySyncing(false);
            }
          });
    }

    /** Checks whether the connected controller runs 2027 FLUX code or legacy {@code New_Robot} code. */
    Future<?> submitRobotStatusCheck() {
      return actionExecutor.submit(
          () -> {
            try {
              PackageDeployer.RobotCodeStatus status = PackageDeployer.inspectRobotCode(robot);
              String level =
                  (status.generation() == PackageDeployer.CodeGeneration.LEGACY_OR_NON_FLUX
                          || status.generation() == PackageDeployer.CodeGeneration.UNREACHABLE)
                      ? "WARN"
                      : "INFO";
              notify.notify("INFO".equals(level) ? "INFO" : "WARN", "Robot Code Status", status.summary());
            } catch (Exception ex) {
              notify.notify("ERROR", "Robot Code Check Failed", ex.getMessage());
            }
          });
    }

    /** Pulls pending robot logs first before restoring {@code jars/prev_stable.jar} + {@code deploy/}. */
    Future<?> submitManualRestoreStable() {
      return actionExecutor.submit(
          () -> {
            setBusySyncing(true);
            try {
              long nowMs = System.currentTimeMillis();
              CompletedRunBundle preLogs = null;
              try {
                preLogs =
                    runMgr.pullDriveSafeRunZip(
                        "prerestore_" + nowMs,
                        null,
                        robot,
                        dsLogDir,
                        logsDir,
                        nowMs,
                        false,
                        true,
                        true);
              } catch (IllegalStateException lockoutEx) {
                String msg = lockoutEx.getMessage();
                boolean isLockout =
                    lockout.isEnabled()
                        || lockout.isFmsAttached()
                        || (msg != null && msg.toUpperCase(Locale.ROOT).contains("LOCKOUT"));
                if (isLockout) {
                  throw lockoutEx;
                }
                notify.notify(
                    "WARN",
                    "Pre-Restore Log Pull Skipped",
                    "Continuing Restore STABLE after log pull error: " + msg);
              } catch (Exception pullEx) {
                notify.notify(
                    "WARN",
                    "Pre-Restore Log Pull Skipped",
                    "Continuing Restore STABLE after log pull error: " + pullEx.getMessage());
              }
              deployer.restoreStable(robot);
              String suffix =
                  (preLogs != null && preLogs.zipArchive() != null)
                      ? " (archived pending logs to " + preLogs.zipArchive().getFileName() + ")"
                      : "";
              notify.notify(
                  "INFO",
                  "FluxTray Restore STABLE",
                  "Restored prev_stable.jar + deploy/ and restarted robot" + suffix + ".");
            } catch (Exception ex) {
              notify.notify("ERROR", "Restore STABLE Failed", ex.getMessage());
            } finally {
              setBusySyncing(false);
            }
          });
    }

    /**
     * Checks GitHub Releases when completely idle (Disabled, off-FMS, not recording audio, not
     * transferring) and triggers a verified swap-and-relaunch if a newer release is published.
     */
    Future<?> submitUpdateCheckIfIdle(
        GitHubReleaseUpdater updater,
        Path runningJar,
        List<String> relaunchArgs,
        boolean preferJavaw,
        Runnable onRestartScheduled) {
      if (updater == null
          || runningJar == null
          || lockout.isEnabled()
          || lockout.isFmsAttached()
          || audio.isRecording()
          || isBusySyncing()) {
        return null;
      }
      if (!updateCheckQueued.compareAndSet(false, true)) {
        return null;
      }
      return actionExecutor.submit(
          () -> {
            try {
              if (lockout.isEnabled() || lockout.isFmsAttached() || audio.isRecording()) {
                return;
              }
              var outcome =
                  updater.checkAndStageUpdate(
                      runningJar,
                      relaunchArgs,
                      preferJavaw,
                      () -> !lockout.isEnabled() && !lockout.isFmsAttached() && !audio.isRecording());
              if (outcome.stagedForRestart()) {
                notify.notify(
                    "INFO",
                    "FluxTray Updating to " + outcome.latestVersion(),
                    "Verified GitHub Release "
                        + outcome.latestVersion()
                        + "; restarting tray agent.");
                if (onRestartScheduled != null) {
                  onRestartScheduled.run();
                }
              }
            } catch (Exception ex) {
              // Offline pits and transient GitHub outages stay quiet in the tray UI (log only).
              appendTrayLog(logFile, "INFO", "Auto-update check skipped: " + ex.getMessage());
            } finally {
              updateCheckQueued.set(false);
            }
          });
    }

    @Override
    public void close() {
      actionExecutor.shutdownNow();
    }
  }

  private static void launchSystemTray(String[] args) throws AWTException {
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
      System.out.println(
          "FluxTray: Headless environment detected (SystemTray UI not supported on this display). Use CLI flags (--help).");
      return;
    }

    Path hubDir =
        Path.of(
            getFlagValue(
                args,
                "--hub",
                System.getProperty("flux.hub.dir", BatteryAndRunManager.DEFAULT_HUB_DIR_NAME)));
    Path codeDir =
        Path.of(
            getFlagValue(
                args,
                "--code-dir",
                System.getProperty("flux.code.dir", hubDir.resolve("code").toString())));
    Path logsDir =
        Path.of(
            getFlagValue(
                args,
                "--logs-dir",
                System.getProperty("flux.logs.dir", hubDir.resolve("logs").toString())));
    Path dsLogDir = Path.of(getFlagValue(args, "--ds-logs", DEFAULT_DS_LOGS_DIR));
    String stateProp = System.getProperty("flux.state.dir");
    Path stateDir =
        (stateProp != null && !stateProp.isBlank())
            ? Path.of(stateProp.trim())
            : Path.of(System.getProperty("user.home"), ".fluxtray");
    Path logFile =
        Path.of(
            getFlagValue(
                args, "--log-file", stateDir.resolve("fluxtray.log").toString()));
    Path ledgerFile = stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME);
    Path lockFile = stateDir.resolve(SingleInstanceLock.LOCK_FILE_NAME);

    SingleInstanceLock instanceLock;
    try {
      Optional<SingleInstanceLock> acquired = SingleInstanceLock.tryAcquire(lockFile);
      if (acquired.isEmpty()) {
        String msg =
            "Another FluxTray instance is already running (lock held: " + lockFile + "); exiting.";
        appendTrayLog(logFile, "WARN", msg);
        System.err.println("FluxTray: " + msg);
        return;
      }
      instanceLock = acquired.get();
    } catch (IOException lockEx) {
      String msg = "Failed to acquire single-instance lock " + lockFile + ": " + lockEx.getMessage();
      appendTrayLog(logFile, "ERROR", msg);
      System.err.println("FluxTray: " + msg);
      return;
    }

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    // Tray mode defaults to roboRIO multi-host discovery (lvuser@) unless --systemcore is given.
    SshRobotTransport robot = parseTransportArgs(args, lockout, false);
    PackageDeployer deployer = new PackageDeployer(lockout, resolveLaptopId());
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder();
    BatteryAndRunManager runMgr = new BatteryAndRunManager(hubDir, ledgerFile, lockout, audio);

    SystemTray tray = SystemTray.getSystemTray();
    Image iconDisconnected = createStatusIcon(TrayStatus.DISCONNECTED);
    Image iconReady = createStatusIcon(TrayStatus.READY);
    Image iconEnabled = createStatusIcon(TrayStatus.ENABLED);
    Image iconSyncing = createStatusIcon(TrayStatus.SYNCING);
    PopupMenu menu = new PopupMenu("FLUX 10413 Tray");
    TrayIcon icon =
        new TrayIcon(iconDisconnected, "FLUX 10413 — " + TrayStatus.DISCONNECTED.label(), menu);
    icon.setImageAutoSize(true);

    AtomicBoolean ntConnected = new AtomicBoolean(false);
    AtomicBoolean displayedEnabled = new AtomicBoolean(false);
    AtomicBoolean displayedFms = new AtomicBoolean(false);
    AtomicBoolean busySnapshot = new AtomicBoolean(false);

    Runnable refreshIcon =
        () -> {
          TrayStatus status =
              computeTrayStatus(
                  ntConnected.get(),
                  displayedEnabled.get(),
                  displayedFms.get(),
                  busySnapshot.get());
          Image target =
              switch (status) {
                case READY -> iconReady;
                case ENABLED -> iconEnabled;
                case SYNCING -> iconSyncing;
                case DISCONNECTED -> iconDisconnected;
              };
          String tooltip = "FLUX 10413 — " + status.label() + " (" + robot.activeHost() + ")";
          runOnEdt(
              () -> {
                icon.setImage(target);
                icon.setToolTip(tooltip);
              });
        };

    DriveWatcher.NotificationSink notify =
        (level, title, message) -> {
          appendTrayLog(logFile, level, title + ": " + message);
          TrayIcon.MessageType type =
              "ERROR".equalsIgnoreCase(level)
                  ? TrayIcon.MessageType.ERROR
                  : ("WARN".equalsIgnoreCase(level)
                      ? TrayIcon.MessageType.WARNING
                      : TrayIcon.MessageType.INFO);
          runOnEdt(() -> icon.displayMessage(title, message, type));
        };

    audio.setErrorListener(msg -> notify.notify("WARN", "FluxTray Audio Warning", msg));

    TrayActionCoordinator coordinator =
        new TrayActionCoordinator(
            robot,
            deployer,
            runMgr,
            audio,
            lockout,
            dsLogDir,
            logsDir,
            logFile,
            notify,
            busy -> {
              busySnapshot.set(busy);
              refreshIcon.run();
            });

    DriveWatcher driveWatcher =
        new DriveWatcher(
            codeDir,
            logsDir,
            dsLogDir,
            robot,
            deployer,
            runMgr,
            lockout,
            notify,
            stateDir,
            DriveWatcher.DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS,
            coordinator::setBusySyncing);

    Nt4RobotStateClient ntClient =
        new Nt4RobotStateClient(
            robot.candidateHosts(),
            Nt4RobotStateClient.DEFAULT_NT4_PORT,
            lockout,
            (enabled, fms, controlWord, host, nowMs) -> {
              audio.onRobotStateChanged(enabled, nowMs);
              ntConnected.set(true);
              displayedEnabled.set(enabled);
              displayedFms.set(fms);
              refreshIcon.run();
            });
    ntClient.setConnectionChangeListener(
        () -> {
          boolean conn = ntClient.isConnected();
          ntConnected.set(conn);
          if (conn) {
            driveWatcher.onRobotConnected();
          } else {
            driveWatcher.onRobotDisconnected();
            // Return icon to Gray on disconnect while robot-side /tmp/flux-robot-state stays fail-closed,
            // and trigger the 15 s debrief auto-stop/spool window if the robot breaker dropped mid-recording.
            displayedEnabled.set(false);
            displayedFms.set(false);
            if (audio.isRecording()) {
              audio.onRobotStateChanged(false, System.currentTimeMillis());
            }
          }
          refreshIcon.run();
        });
    ntClient.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  ntClient.close();
                  audio.close();
                  coordinator.close();
                  instanceLock.close();
                },
                "fluxtray-shutdown-cleanup"));

    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "fluxtray-watch-scheduler");
              t.setDaemon(true);
              return t;
            });

    scheduler.scheduleWithFixedDelay(
        () -> {
          long nowMs = System.currentTimeMillis();
          if (lockout.claimAutoLogPull(nowMs)) {
            coordinator.submitAutoPull();
          }
          coordinator.submitDrivePollIfIdle(driveWatcher, System::currentTimeMillis);
        },
        1L,
        1L,
        TimeUnit.SECONDS);

    boolean autoUpdateEnabled = isAutoUpdateEnabled(args);
    Path runningJarPath = GitHubReleaseUpdater.resolveRunningJarPath().orElse(null);
    if (autoUpdateEnabled && runningJarPath != null) {
      String updateRepo =
          getFlagValue(args, "--update-repo", GitHubReleaseUpdater.resolveDefaultRepo());
      GitHubReleaseUpdater releaseUpdater =
          new GitHubReleaseUpdater(updateRepo, GitHubReleaseUpdater.detectCurrentVersion());
      List<String> relaunchArgs = List.of(args);
      scheduler.scheduleWithFixedDelay(
          () ->
              coordinator.submitUpdateCheckIfIdle(
                  releaseUpdater,
                  runningJarPath,
                  relaunchArgs,
                  true,
                  () -> {
                    ntClient.close();
                    scheduler.shutdownNow();
                    audio.close();
                    removeTrayIconSynchronously(tray, icon);
                    instanceLock.close();
                    System.exit(0);
                  }),
          GitHubReleaseUpdater.DEFAULT_INITIAL_DELAY_SECONDS,
          TimeUnit.MINUTES.toSeconds(GitHubReleaseUpdater.DEFAULT_CHECK_INTERVAL_MINUTES),
          TimeUnit.SECONDS);
    }

    MenuItem pullLogsItem = new MenuItem("Pull Logs Now");
    MenuItem checkCodeItem = new MenuItem("Check Robot Code");
    MenuItem restoreItem = new MenuItem("Restore STABLE");
    MenuItem exitItem = new MenuItem("Exit FluxTray");

    pullLogsItem.addActionListener(e -> coordinator.submitManualPull());
    checkCodeItem.addActionListener(e -> coordinator.submitRobotStatusCheck());
    restoreItem.addActionListener(e -> coordinator.submitManualRestoreStable());

    exitItem.addActionListener(
        e -> {
          appendTrayLog(logFile, "INFO", "FluxTray exiting by user request.");
          ntClient.close();
          scheduler.shutdownNow();
          coordinator.close();
          audio.close();
          removeTrayIconSynchronously(tray, icon);
          instanceLock.close();
          System.exit(0);
        });

    menu.add(pullLogsItem);
    menu.add(checkCodeItem);
    menu.add(restoreItem);
    menu.addSeparator();
    menu.add(exitItem);

    tray.add(icon);
    appendTrayLog(
        logFile,
        "INFO",
        "FluxTray started (platform="
            + robot.controllerPlatform()
            + ", hosts="
            + robot.candidateHosts()
            + ", codeDir="
            + codeDir
            + ", logsDir="
            + logsDir
            + ")");
    System.out.println("FluxTray running in system tray (log: " + logFile + ").");
  }

  private static SshRobotTransport parseTransportArgs(
      String[] args, RobotSafetyLockout lockout, boolean defaultSystemCore) {
    String host = getFlagValue(args, "--host", null);
    boolean isSystemCore =
        hasFlag(args, "--systemcore") || (!hasFlag(args, "--roborio") && defaultSystemCore);
    boolean allowMissingHeartbeat = hasFlag(args, "--allow-missing-heartbeat");
    List<String> hosts =
        (host != null && !host.isBlank())
            ? List.of(host.trim())
            : (isSystemCore
                ? SshRobotTransport.SYSTEMCORE_CANDIDATE_HOSTS
                : SshRobotTransport.ROBORIO_CANDIDATE_HOSTS);
    return new SshRobotTransport(hosts, isSystemCore, lockout, allowMissingHeartbeat);
  }

  private static String resolveLaptopId() {
    try {
      String h = java.net.InetAddress.getLocalHost().getHostName();
      if (h != null && !h.isBlank()) {
        return h;
      }
    } catch (Exception ignored) {
    }
    return "ds-laptop";
  }

  private static boolean hasFlag(String[] args, String flag) {
    for (String a : args) {
      if (flag.equalsIgnoreCase(a)) {
        return true;
      }
    }
    return false;
  }

  private static String getFlagValue(String[] args, String flag, String defaultValue) {
    for (int i = 0; i < args.length - 1; i++) {
      if (flag.equalsIgnoreCase(args[i])) {
        return args[i + 1];
      }
    }
    return defaultValue;
  }

  private static void printUsage() {
    System.out.println(
        """
        FluxTray.jar — FRC Team 10413 (FLUX Robotics) Driver Station Tray Agent & Remote Ops Daemon
        Default Double-Click Launch (desktop GUI, no arguments):
          Starts the color-coded system tray agent (Green=Ready, Red=Enabled, Yellow=Syncing, Gray=Disconnected)
          watching Google Drive code/ + logs/ with live NT4 lockout, automatic post-disable log pulls,
          16 kHz PCM mentor-audio.wav recording, and atomic roboRIO deploy + frcKillRobot.sh restart.

        Usage:
          java -jar dist/FluxTray.jar --tray [--code-dir <drive/code>] [--logs-dir <drive/logs>] [--ds-logs <dir>] [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat] [--auto-update|--no-auto-update] [--update-repo <owner/repo>] [--log-file <fluxtray.log>]
          java -jar dist/FluxTray.jar --pull-logs [<dir>] [--dir <output-dir>] [--file <filename.zip>] [--out <dir-or-zip-path>] [--run-id <id>] [--robot <name>] [--ds-logs <dir>] [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat]
          java -jar dist/FluxTray.jar --inspect-snapshot <system-snapshot.txt>
          java -jar dist/FluxTray.jar --deploy <package.fluxpkg.zip> [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat] [--force]
          java -jar dist/FluxTray.jar --push-configs <config-dir> [--profile <id>] [--mode PRODUCTION|TUNING] [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat]
          java -jar dist/FluxTray.jar --set-mode <PRODUCTION|TUNING> [--profile <id>] [--config-sha <sha>] [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat]
          java -jar dist/FluxTray.jar --promote-tuning <config-dir> [--author <name>] [--notes <text>] [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat]
          java -jar dist/FluxTray.jar --restore-stable [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat]
          java -jar dist/FluxTray.jar --rollback <1..5> [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat]
          java -jar dist/FluxTray.jar --sync-pending [<FLUX-10413-Hub-dir>] [--host <ip>] [--roborio|--systemcore] [--allow-missing-heartbeat]
          java -jar dist/FluxTray.jar --check-update [--update-repo <owner/repo>]
          java -jar dist/FluxTray.jar --self-update [--update-repo <owner/repo>]
          java -jar dist/FluxTray.jar --robot-status [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --version
          java -jar dist/FluxTray.jar --print-ssh-capture-cmd
          java -jar dist/FluxTray.jar --self-test
        """);
  }
}
