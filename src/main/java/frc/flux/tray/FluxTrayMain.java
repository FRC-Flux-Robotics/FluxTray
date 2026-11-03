package frc.flux.tray;

import frc.flux.tray.BatteryAndRunManager.CompletedRunBundle;
import frc.lib.fluxfig.FluxFig;
import frc.lib.fluxfig.FluxSystemSnapshot;
import frc.lib.fluxfig.FluxSystemSnapshot.WatchDecision;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Entry point for {@code FluxTray.jar} — the ~35 MB Windows Driver Station System Tray Agent and
 * CLI tool for FRC Team 10413 (FLUX Robotics).
 */
public final class FluxTrayMain {

  private FluxTrayMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
      printUsage();
      return;
    }
    if (args.length == 0) {
      executePullAndPackageLogs(args);
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
        RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
        RobotTransport robot = parseTransportArgs(args, lockout);
        PackageDeployer deployer = new PackageDeployer(lockout, "ds-laptop");
        deployer.restoreStable(robot);
        System.out.println("Restored jars/prev_stable.jar -> frcUserProgram.jar on " + robot.controllerPlatform());
      }
      case "--rollback" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException("Usage: --rollback <1..5> [--host <ip>] [--roborio]");
        }
        int slot = Integer.parseInt(args[1]);
        RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
        RobotTransport robot = parseTransportArgs(args, lockout);
        PackageDeployer deployer = new PackageDeployer(lockout, "ds-laptop");
        deployer.rollbackToSlot(slot, robot);
        System.out.println("Rolled back to jars/prev_" + slot + ".jar on " + robot.controllerPlatform());
      }
      case "--deploy" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --deploy <package.fluxpkg.zip> [--host <ip>] [--roborio] [--force]");
        }
        Path pkg = Path.of(args[1]);
        boolean force = hasFlag(args, "--force");
        RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
        RobotTransport robot = parseTransportArgs(args, lockout);
        PackageDeployer deployer = new PackageDeployer(lockout, "ds-laptop");
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
                + ")");
      }
      case "--set-mode" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --set-mode <PRODUCTION|TUNING> [--profile <profile>] [--config-sha <sha>] [--host <ip>] [--roborio]");
        }
        FluxFig.Mode mode = FluxFig.Mode.valueOf(args[1].trim().toUpperCase(Locale.ROOT));
        String profile = getFlagValue(args, "--profile", null);
        String configSha = getFlagValue(args, "--config-sha", null);
        RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
        RobotTransport robot = parseTransportArgs(args, lockout);
        PackageDeployer deployer = new PackageDeployer(lockout, "ds-laptop");
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
              "Usage: --push-configs <config-dir> [--profile <profile>] [--mode <PRODUCTION|TUNING>] [--host <ip>] [--roborio]");
        }
        Path configDir = Path.of(args[1]);
        String profile = getFlagValue(args, "--profile", "COMP_2027");
        FluxFig.Mode mode =
            FluxFig.Mode.valueOf(
                getFlagValue(args, "--mode", "PRODUCTION").trim().toUpperCase(Locale.ROOT));
        RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
        RobotTransport robot = parseTransportArgs(args, lockout);
        PackageDeployer deployer = new PackageDeployer(lockout, "ds-laptop");
        String sha = deployer.pushFluxFigConfigs(configDir, profile, mode, robot);
        System.out.println(
            "Pushed FluxFig configs ("
                + sha
                + ") with profile="
                + profile
                + ", mode="
                + mode
                + " to "
                + robot.controllerPlatform());
      }
      case "--promote-tuning" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException(
              "Usage: --promote-tuning <config-dir> [--author <name>] [--notes <text>] [--host <ip>] [--roborio]");
        }
        Path configDir = Path.of(args[1]);
        String author = getFlagValue(args, "--author", "pit-crew");
        String notes = getFlagValue(args, "--notes", "");
        RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
        RobotTransport robot = parseTransportArgs(args, lockout);
        PackageDeployer deployer = new PackageDeployer(lockout, "ds-laptop");
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
          "--roborio" ->
          executePullAndPackageLogs(args);
      case "--sync-pending" -> {
        Path hubDir =
            (args.length >= 2 && !args[1].startsWith("--"))
                ? Path.of(args[1])
                : Path.of(getFlagValue(args, "--hub", BatteryAndRunManager.DEFAULT_HUB_DIR_NAME));
        RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
        RobotTransport robot = parseTransportArgs(args, lockout);
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
      case "--tray" -> launchSystemTray();
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

    // Optional positional argument after --pull-logs <dir>
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
    String dsLogDirStr =
        getFlagValue(args, "--ds-logs", "C:\\Users\\Public\\Documents\\FRC\\Log Files");
    Path dsLogDir = Path.of(dsLogDirStr);
    RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
    RobotTransport robot = parseTransportArgs(args, lockout);
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder();
    BatteryAndRunManager mgr = new BatteryAndRunManager(hubDir, lockout, audio);
    CompletedRunBundle bundle =
        mgr.pullPracticeRunLogs(
            runId,
            robotName,
            "unknown",
            "",
            robot,
            dsLogDir,
            null,
            RobotSafetyLockout.MIN_DISABLE_DURATION_MS_FOR_LOG_PULL + 1_000L,
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
        " Loop Period: %.1f ms (p50=%.2f ms, p99=%.2f ms, max=%.2f ms, 1stEnable=%.2f ms)%n",
        snap.loopPeriodMs(),
        snap.loopMedianMs(),
        snap.loopP99Ms(),
        snap.loopMaxMs(),
        snap.firstEnablePeakMs());
    System.out.printf(
        Locale.US,
        " CPU Load:    Total=%.1f%% (Java=%.1f%%, Vision C++=%.1f%%, softirq=%.1f%%)%n",
        snap.totalCpuPct(),
        snap.javaProcessCpuPct(),
        snap.visionDaemonCpuPct(),
        snap.softIrqPct());
    System.out.printf(
        Locale.US,
        " Thermal/Pwr: %.1f °C @ %.0f MHz (throttled=0x%x) | Free Storage: %.0f MB%n",
        snap.cpuTempC(),
        snap.cpuFreqMHz(),
        snap.throttledFlags(),
        snap.freeStorageMb());
    System.out.printf(
        Locale.US,
        " JVM & PMU:   Alloc=%.2f MB/s, GCs=%d (GC Overruns=%d) | IPC=%.2f, LLC Miss=%.1f%%%n",
        snap.heapAllocRateMbPerSec(),
        snap.gcCount(),
        snap.gcCorrelatedLoopSpikes(),
        snap.ipc(),
        snap.llcMissPct());
    System.out.println("----------------------------------------------------------------------");
    if (decisions.isEmpty()) {
      System.out.println(" VERDICT: NOMINAL (0 watch metric anomalies detected)");
    } else {
      System.out.println(" VERDICT: " + decisions.size() + " ACTIONABLE WATCH DECISION(S) TRIGGERED:");
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

  private static void runSelfTest() throws IOException {
    Path tmp = Files.createTempDirectory("fluxtray-selftest-");
    try {
      MockRobotServer mock = new MockRobotServer(tmp.resolve("robot"), "systemcore-2027");
      mock.seedSimulatedMatchLogs("2027-02-18_16-08-12");
      FluxSystemSnapshot baseline = mock.captureSystemSnapshot();
      printSnapshotAudit("SelfTest-Healthy-SystemCore", baseline);
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

  private static void launchSystemTray() throws AWTException {
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
      System.out.println(
          "FluxTray: Headless environment detected (SystemTray UI not supported on this display). Use CLI flags (--help).");
      return;
    }
    SystemTray tray = SystemTray.getSystemTray();
    Image iconImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    PopupMenu menu = new PopupMenu("FLUX 10413 Tray");

    Path hubDir = Path.of(System.getProperty("flux.hub.dir", "FLUX-10413-Hub"));
    RobotSafetyLockout lockout = new RobotSafetyLockout(System.currentTimeMillis());
    RobotTransport robot = new SshRobotTransport("10.104.13.2", true, lockout);
    PackageDeployer deployer = new PackageDeployer(lockout, "ds-laptop");
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder();
    BatteryAndRunManager runMgr = new BatteryAndRunManager(hubDir, lockout, audio);

    MenuItem restoreItem = new MenuItem("Restore STABLE (4s Rollback)");
    MenuItem modeTuningItem = new MenuItem("Switch Mode: TUNING (Live Dashboard)");
    MenuItem modeProdItem = new MenuItem("Switch Mode: PRODUCTION (Locked)");
    MenuItem promoteTuningItem = new MenuItem("Promote Floor Tuning -> vN+1 Release");
    MenuItem pullLogsItem = new MenuItem("Pull Logs & System Snapshot Now");
    MenuItem syncFmsItem = new MenuItem("Sync Pending FMS Match Logs");
    MenuItem scanScoutQrItem = new MenuItem("Scan Match Scouting QR Code (Webcam / Image)");
    MenuItem exportScoutPwaItem = new MenuItem("Export Offline /scout PWA Bundle");
    MenuItem exitItem = new MenuItem("Exit FluxTray");

    ExecutorService trayActionExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "fluxtray-action-worker");
              t.setDaemon(true);
              return t;
            });

    restoreItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    deployer.restoreStable(robot);
                  } catch (Exception ex) {
                    System.err.println("Restore STABLE failed: " + ex.getMessage());
                  }
                }));
    modeTuningItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    deployer.switchActiveMode(null, FluxFig.Mode.TUNING, null, robot);
                  } catch (Exception ex) {
                    System.err.println("Switch to TUNING failed: " + ex.getMessage());
                  }
                }));
    modeProdItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    deployer.switchActiveMode(null, FluxFig.Mode.PRODUCTION, null, robot);
                  } catch (Exception ex) {
                    System.err.println("Switch to PRODUCTION failed: " + ex.getMessage());
                  }
                }));
    promoteTuningItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    deployer.promoteRemoteTuningSnapshot(
                        hubDir.resolve("03_FluxFig_Configs"), "pit-crew", "Tray promotion", robot);
                  } catch (Exception ex) {
                    System.err.println("Promote tuning failed: " + ex.getMessage());
                  }
                }));
    pullLogsItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    runMgr.pullPracticeRunLogs(
                        "manual_" + System.currentTimeMillis(),
                        "COMP_2027",
                        "unknown",
                        "unknown",
                        robot,
                        Path.of("C:\\Users\\Public\\Documents\\FRC\\Log Files"),
                        null,
                        System.currentTimeMillis());
                  } catch (Exception ex) {
                    System.err.println("Pull logs failed: " + ex.getMessage());
                  }
                }));
    syncFmsItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    runMgr.syncPendingFmsRuns(robot);
                  } catch (Exception ex) {
                    System.err.println("Sync pending FMS runs failed: " + ex.getMessage());
                  }
                }));
    scanScoutQrItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    Path qrImg = hubDir.resolve("latest-scout-qr.png");
                    if (Files.isRegularFile(qrImg)) {
                      String payload = ScoutingQrManager.readVisualMatrixPng(qrImg);
                      ScoutingQrManager.ingestVerifiedPayload(
                          payload, hubDir, ScoutingQrManager.DEFAULT_TEAM_SECRET_HEX);
                    }
                  } catch (Exception ex) {
                    System.err.println("Scan scouting QR failed: " + ex.getMessage());
                  }
                }));
    exportScoutPwaItem.addActionListener(
        e ->
            trayActionExecutor.submit(
                () -> {
                  try {
                    ScoutingQrManager.exportOfflineScoutingPwa(
                        hubDir.resolve("05_Scouting_PWA"),
                        ScoutingQrManager.DEFAULT_TEAM_SECRET_HEX);
                  } catch (Exception ex) {
                    System.err.println("Export scouting PWA failed: " + ex.getMessage());
                  }
                }));
    exitItem.addActionListener(e -> System.exit(0));

    menu.add(restoreItem);
    menu.add(modeTuningItem);
    menu.add(modeProdItem);
    menu.add(promoteTuningItem);
    menu.add(pullLogsItem);
    menu.add(syncFmsItem);
    menu.add(scanScoutQrItem);
    menu.add(exportScoutPwaItem);
    menu.addSeparator();
    menu.add(exitItem);

    TrayIcon icon = new TrayIcon(iconImg, "FLUX 10413 Driver Station Agent", menu);
    icon.setImageAutoSize(true);
    tray.add(icon);
    System.out.println("FluxTray running in system tray.");
  }

  private static RobotTransport parseTransportArgs(String[] args, RobotSafetyLockout lockout) {
    String host = "10.104.13.2";
    boolean isSystemCore = !hasFlag(args, "--roborio");
    for (int i = 0; i < args.length - 1; i++) {
      if ("--host".equals(args[i])) {
        host = args[i + 1];
      }
    }
    return new SshRobotTransport(host, isSystemCore, lockout);
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
        FluxTray.jar — FRC Team 10413 (FLUX Robotics) Driver Station Agent & SystemCore Watch Auditor
        Default Behavior (no arguments or --pull-logs):
          Downloads robot logs (.wpilog, .hoot, FRC_UserProgram.log, cam-snapshots/*.jpg, system-snapshot.txt),
          pairs them with local Driver Station logs (.dslog/.dsevents) and driver-audio.ogg, and packages both:
            - Run directory: ./FLUX-10413-Hub/04_Practice_And_Match_Logs/<runId>/
            - ZIP archive:   ./FLUX-10413-Hub/04_Practice_And_Match_Logs/match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip

        Usage:
          java -jar dist/FluxTray.jar [--dir <output-dir>] [--file <filename.zip>] [--out <dir-or-zip-path>] [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --pull-logs [<dir>] [--dir <output-dir>] [--file <filename.zip>] [--out <dir-or-zip-path>] [--run-id <id>] [--robot <name>] [--ds-logs <dir>] [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --tray
          java -jar dist/FluxTray.jar --inspect-snapshot <system-snapshot.txt>
          java -jar dist/FluxTray.jar --deploy <package.fluxpkg.zip> [--host 10.104.13.2] [--roborio] [--force]
          java -jar dist/FluxTray.jar --push-configs <config-dir> [--profile COMP_2027] [--mode PRODUCTION|TUNING] [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --set-mode <PRODUCTION|TUNING> [--profile COMP_2027] [--config-sha <sha>] [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --promote-tuning <config-dir> [--author <name>] [--notes <text>] [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --restore-stable [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --rollback <1..5> [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --sync-pending [<FLUX-10413-Hub-dir>] [--host 10.104.13.2] [--roborio]
          java -jar dist/FluxTray.jar --export-scout-pwa <out-dir> [--secret <hex-key>]
          java -jar dist/FluxTray.jar --scan-scout-qr <qr-image.png | FLUXSCOUT1-payload> <FLUX-10413-Hub-dir> [--secret <hex-key>]
          java -jar dist/FluxTray.jar --print-ssh-capture-cmd
          java -jar dist/FluxTray.jar --self-test
        """);
  }
}
