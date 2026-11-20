package frc.flux.tray;

import frc.flux.tray.BatteryAndRunManager.CompletedRunBundle;
import frc.flux.tray.DriveWatcher.PollOutcome;
import frc.lib.fluxfig.FluxFig;
import frc.lib.fluxfig.FluxSystemSnapshot;
import frc.lib.fluxfig.FluxSystemSnapshot.WatchDecision;
import java.awt.AWTException;
import java.awt.Color;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
      case "--export-scout-pwa" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --export-scout-pwa <out-dir> [--secret <hex-key>]");
        }
        Path outDir = Path.of(args[1]);
        String secret = getFlagValue(args, "--secret", ScoutingQrManager.DEFAULT_TEAM_SECRET_HEX);
        Path scoutHtml = ScoutingQrManager.exportOfflineScoutingPwa(outDir, secret);
        System.out.println(
            "Exported offline /scout PWA (scout.html, sw.js, manifest.webmanifest, enroll-qr.png) -> "
                + scoutHtml.toAbsolutePath());
      }
      case "--scan-scout-qr" -> {
        if (args.length < 3) {
          throw new IllegalArgumentException(
              "Usage: --scan-scout-qr <qr-image.png | FLUXSCOUT1-payload> <FLUX-10413-Hub-dir> [--secret <hex-key>]");
        }
        String input = args[1];
        Path hubDir = Path.of(args[2]);
        String secret = getFlagValue(args, "--secret", ScoutingQrManager.DEFAULT_TEAM_SECRET_HEX);
        String payload =
            input.startsWith(ScoutingQrManager.PAYLOAD_PREFIX + "|")
                ? input
                : ScoutingQrManager.readVisualMatrixPng(Path.of(input));
        ScoutingQrManager.ScoutingMatchEntry entry =
            ScoutingQrManager.ingestVerifiedPayload(payload, hubDir, secret);
        System.out.println(
            "Verified & ingested scouting QR -> "
                + entry.eventCode()
                + " Match "
                + entry.matchNumber()
                + " ("
                + entry.allianceStation()
                + ", Team "
                + entry.teamNumber()
                + ", scouter="
                + entry.scouterId()
                + ", seq="
                + entry.sequenceNumber()
                + ")");
      }
      case "--self-test" -> runSelfTest();
      case "--tray", "--code-dir", "--logs-dir" -> launchSystemTray(args);
      default -> {
        System.err.println("Unknown option: " + cmd);
        printUsage();
        System.exit(1);
      }
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

  private static void runSelfTest() throws IOException {
    Path tmp = Files.createTempDirectory("fluxtray-selftest-");
    try {
      MockRobotServer mock = new MockRobotServer(tmp.resolve("robot"), "roborio-2026");
      mock.seedSimulatedMatchLogs("2026-11-20_16-08-12", "FUEL", "a1b2c3d4");
      mock.setNextSnapshotRaw(
          "[PLATFORM]\ncontrollerType=ROBORIO\nhostname=roboRIO-10413-FRC\n[CPU_AND_IRQ]\ntotalCpuPct=24.5\n[STORAGE]\nfreeStorageMb=480.0\n");
      FluxSystemSnapshot baseline = mock.captureSystemSnapshot();
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
    Path logFile =
        Path.of(
            getFlagValue(
                args,
                "--log-file",
                Path.of(System.getProperty("user.home"), ".fluxtray", "fluxtray.log").toString()));
    Path ledgerFile =
        Path.of(
            System.getProperty("user.home"),
            ".fluxtray",
            BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME);

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
    TrayIcon icon = new TrayIcon(iconDisconnected, "FLUX 10413 — " + TrayStatus.DISCONNECTED.label(), menu);
    icon.setImageAutoSize(true);

    AtomicBoolean busySyncing = new AtomicBoolean(false);
    AtomicBoolean ntConnected = new AtomicBoolean(false);

    Runnable refreshIcon =
        () -> {
          TrayStatus status;
          if (lockout.isEnabled() || lockout.isFmsAttached()) {
            status = TrayStatus.ENABLED;
          } else if (busySyncing.get()) {
            status = TrayStatus.SYNCING;
          } else if (ntConnected.get()) {
            status = TrayStatus.READY;
          } else {
            status = TrayStatus.DISCONNECTED;
          }
          Image target =
              switch (status) {
                case READY -> iconReady;
                case ENABLED -> iconEnabled;
                case SYNCING -> iconSyncing;
                case DISCONNECTED -> iconDisconnected;
              };
          icon.setImage(target);
          icon.setToolTip("FLUX 10413 — " + status.label() + " (" + robot.activeHost() + ")");
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
          icon.displayMessage(title, message, type);
        };

    DriveWatcher driveWatcher =
        new DriveWatcher(codeDir, logsDir, dsLogDir, robot, deployer, runMgr, lockout, notify);

    Nt4RobotStateClient ntClient =
        new Nt4RobotStateClient(
            robot.candidateHosts(),
            Nt4RobotStateClient.DEFAULT_NT4_PORT,
            lockout,
            (enabled, fms, controlWord, host, nowMs) -> {
              audio.onRobotStateChanged(enabled, nowMs);
              ntConnected.set(true);
              refreshIcon.run();
            });
    ntClient.setConnectionChangeListener(
        () -> {
          ntConnected.set(ntClient.isConnected());
          refreshIcon.run();
        });
    ntClient.start();

    ExecutorService trayActionExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "fluxtray-action-worker");
              t.setDaemon(true);
              return t;
            });

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
            trayActionExecutor.submit(
                () -> {
                  busySyncing.set(true);
                  refreshIcon.run();
                  try {
                    while (audio.isRecording()
                        && audio.isMicAvailable()
                        && audio.remainingPostDisableMs(System.currentTimeMillis()) > 0L) {
                      if (lockout.isEnabled() || lockout.isFmsAttached()) {
                        appendTrayLog(logFile, "WARN", "Aborted post-disable debrief wait: robot re-enabled");
                        return;
                      }
                      Thread.sleep(200L);
                    }
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
                  } catch (Exception ex) {
                    notify.notify("ERROR", "FluxTray Auto-Pull Failed", ex.getMessage());
                  } finally {
                    busySyncing.set(false);
                    refreshIcon.run();
                  }
                });
          }

          if (!busySyncing.get() && !lockout.isEnabled() && !lockout.isFmsAttached()) {
            try {
              busySyncing.set(true);
              refreshIcon.run();
              PollOutcome outcome = driveWatcher.pollOnce(System.currentTimeMillis());
              if (outcome.action() != DriveWatcher.ActionType.NONE) {
                appendTrayLog(logFile, "INFO", "DriveWatcher " + outcome.action() + ": " + outcome.summary());
              }
            } catch (Exception ex) {
              notify.notify("ERROR", "FluxTray Drive Sync Error", ex.getMessage());
            } finally {
              busySyncing.set(false);
              refreshIcon.run();
            }
          }
        },
        1L,
        1L,
        TimeUnit.SECONDS);

    MenuItem pullLogsItem = new MenuItem("Pull Logs Now");
    MenuItem restoreItem = new MenuItem("Restore STABLE");
    MenuItem modeTuningItem = new MenuItem("Switch Mode: TUNING");
    MenuItem modeProdItem = new MenuItem("Switch Mode: PRODUCTION");
    MenuItem promoteTuningItem = new MenuItem("Promote Floor Tuning -> vN+1");
    MenuItem syncFmsItem = new MenuItem("Sync Pending FMS Match Logs");
    MenuItem exitItem = new MenuItem("Exit FluxTray");

    pullLogsItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  busySyncing.set(true);
                  refreshIcon.run();
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
                    busySyncing.set(false);
                    refreshIcon.run();
                  }
                }));

    restoreItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  busySyncing.set(true);
                  refreshIcon.run();
                  try {
                    deployer.restoreStable(robot);
                    notify.notify(
                        "INFO",
                        "FluxTray Restore STABLE",
                        "Restored prev_stable.jar + deploy/ and restarted robot.");
                  } catch (Exception ex) {
                    notify.notify("ERROR", "Restore STABLE Failed", ex.getMessage());
                  } finally {
                    busySyncing.set(false);
                    refreshIcon.run();
                  }
                }));

    modeTuningItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    FluxFig.ActiveProfileDirective dir =
                        deployer.switchActiveMode(null, FluxFig.Mode.TUNING, null, robot);
                    notify.notify("INFO", "FluxTray Mode", "Switched " + dir.profile() + " -> TUNING");
                  } catch (Exception ex) {
                    notify.notify("ERROR", "Switch to TUNING Failed", ex.getMessage());
                  }
                }));

    modeProdItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    FluxFig.ActiveProfileDirective dir =
                        deployer.switchActiveMode(null, FluxFig.Mode.PRODUCTION, null, robot);
                    notify.notify(
                        "INFO", "FluxTray Mode", "Switched " + dir.profile() + " -> PRODUCTION");
                  } catch (Exception ex) {
                    notify.notify("ERROR", "Switch to PRODUCTION Failed", ex.getMessage());
                  }
                }));

    promoteTuningItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    FluxFig.ReleaseEntry rel =
                        deployer.promoteRemoteTuningSnapshot(
                            hubDir.resolve("03_FluxFig_Configs"),
                            "pit-crew",
                            "Tray promotion",
                            robot);
                    notify.notify(
                        "INFO",
                        "FluxTray Promoted " + rel.version(),
                        "Config SHA " + rel.configSha256());
                  } catch (Exception ex) {
                    notify.notify("ERROR", "Promote Tuning Failed", ex.getMessage());
                  }
                }));

    syncFmsItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  busySyncing.set(true);
                  refreshIcon.run();
                  try {
                    List<CompletedRunBundle> synced = runMgr.syncPendingFmsRuns(robot);
                    notify.notify(
                        "INFO", "FluxTray FMS Sync", "Synced " + synced.size() + " pending run(s).");
                  } catch (Exception ex) {
                    notify.notify("ERROR", "Sync Pending FMS Failed", ex.getMessage());
                  } finally {
                    busySyncing.set(false);
                    refreshIcon.run();
                  }
                }));

    exitItem.addActionListener(
        e -> {
          appendTrayLog(logFile, "INFO", "FluxTray exiting by user request.");
          ntClient.close();
          scheduler.shutdownNow();
          trayActionExecutor.shutdownNow();
          tray.remove(icon);
          System.exit(0);
        });

    menu.add(pullLogsItem);
    menu.add(restoreItem);
    menu.addSeparator();
    menu.add(modeTuningItem);
    menu.add(modeProdItem);
    menu.add(promoteTuningItem);
    menu.add(syncFmsItem);
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
    if (host != null && !host.isBlank()) {
      return new SshRobotTransport(host, isSystemCore, lockout);
    }
    return new SshRobotTransport(
        isSystemCore
            ? SshRobotTransport.SYSTEMCORE_CANDIDATE_HOSTS
            : SshRobotTransport.ROBORIO_CANDIDATE_HOSTS,
        isSystemCore,
        lockout);
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
          java -jar dist/FluxTray.jar --tray [--code-dir <drive/code>] [--logs-dir <drive/logs>] [--ds-logs <dir>] [--host <ip>] [--roborio|--systemcore] [--log-file <fluxtray.log>]
          java -jar dist/FluxTray.jar --pull-logs [<dir>] [--dir <output-dir>] [--file <filename.zip>] [--out <dir-or-zip-path>] [--run-id <id>] [--robot <name>] [--ds-logs <dir>] [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --inspect-snapshot <system-snapshot.txt>
          java -jar dist/FluxTray.jar --deploy <package.fluxpkg.zip> [--host <ip>] [--roborio|--systemcore] [--force]
          java -jar dist/FluxTray.jar --push-configs <config-dir> [--profile <id>] [--mode PRODUCTION|TUNING] [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --set-mode <PRODUCTION|TUNING> [--profile <id>] [--config-sha <sha>] [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --promote-tuning <config-dir> [--author <name>] [--notes <text>] [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --restore-stable [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --rollback <1..5> [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --sync-pending [<FLUX-10413-Hub-dir>] [--host <ip>] [--roborio|--systemcore]
          java -jar dist/FluxTray.jar --export-scout-pwa <out-dir> [--secret <hex-key>]
          java -jar dist/FluxTray.jar --scan-scout-qr <qr-image.png | FLUXSCOUT1-payload> <FLUX-10413-Hub-dir> [--secret <hex-key>]
          java -jar dist/FluxTray.jar --print-ssh-capture-cmd
          java -jar dist/FluxTray.jar --self-test
        """);
  }
}
