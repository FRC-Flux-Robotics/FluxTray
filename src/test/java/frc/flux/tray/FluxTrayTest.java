package frc.flux.tray;

import frc.flux.tray.BatteryAndRunManager.CompletedRunBundle;
import frc.flux.tray.DriveWatcher.ActionType;
import frc.flux.tray.DriveWatcher.PollOutcome;
import frc.flux.tray.PackageDeployer.PackageManifest;
import frc.lib.fluxfig.FluxFig;
import frc.lib.fluxfig.FluxSystemSnapshot;
import frc.lib.fluxfig.FluxSystemSnapshot.CanInterfaceStats;
import frc.lib.fluxfig.FluxSystemSnapshot.WatchDecision;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Comprehensive verification suite for {@code FluxTray.jar} covering M1.6–M1.10 plus existing
 * rollback, tuning, FMS pit sync, scouting QR, and 14 watch metrics contracts.
 */
public final class FluxTrayTest {

  private static final List<Path> TEMP_DIRS = new ArrayList<>();

  private static Path createTempDir(String prefix) throws IOException {
    Path dir = Files.createTempDirectory(prefix);
    TEMP_DIRS.add(dir);
    return dir;
  }

  private static void cleanupTempDirs() {
    for (Path dir : TEMP_DIRS) {
      if (dir != null && Files.exists(dir)) {
        try (Stream<Path> walk = Files.walk(dir)) {
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
  }

  public static void main(String[] args) throws Exception {
    try {
      testSafetyLockoutAndAbort();
      testPackageDeployAndRollbackRing();
      testPracticeAndFmsCaptureSyncWorkflow();
      testHealthyAndAnomalousSystemSnapshots();
      testFluxFigTuningModeSwitchAndVersionPromotion();
      testScoutingPwaAndSignedQrScanner();
      testNt4WebSocketLiveLockoutAndOncePerDisableAutoPull();
      testRoboRioRobotCommandAtomicDeployRestartAndRobotStateLockout();
      testDriveWatcherIncrementalLedgerDsLogFilterAtomicZipAndRealWavAudio();
      System.out.println("FluxTrayTest: ALL 9 SUITES PASSED");
    } finally {
      cleanupTempDirs();
    }
  }

  private static void testSafetyLockoutAndAbort() {
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    assertEquals(-1L, lockout.lastDisabledTimestampMs(), "Initial lastDisabledTimestampMs is -1L");
    assertTrue(
        !lockout.isReadyForAutoLogPull(10_000L),
        "Idle robot without Enabled -> Disabled transition must NOT trigger auto-pull");

    // Observe Enabled -> Disabled transition at t=1_000ms.
    lockout.updateState(true, false, 500L);
    lockout.updateState(false, false, 1_000L);
    assertTrue(!lockout.isReadyForAutoLogPull(2_500L), "Must wait 3.0s after Disable before log pull");
    assertTrue(lockout.isReadyForAutoLogPull(4_100L), "Ready for log pull after >= 3.0s Disabled");
    assertTrue(lockout.claimAutoLogPull(4_100L), "First auto-pull claim succeeds once");
    assertTrue(!lockout.claimAutoLogPull(4_200L), "Second auto-pull claim blocked for same disable");
    assertTrue(!lockout.isReadyForAutoLogPull(5_000L), "Auto-pull not ready again until next Enable");

    // Start a transfer while Disabled, then transition to Enabled -> transfer must abort immediately.
    lockout.beginTransferOrThrow("deployPackage");
    assertEquals(1, lockout.activeTransferCount(), "1 active transfer");
    lockout.updateState(true, false, 5_000L);
    assertEquals(0, lockout.activeTransferCount(), "Active transfer cleared on Enable");
    assertEquals(1, lockout.abortedTransferCount(), "Aborted count incremented on Enable");

    boolean threwOnEnabled = false;
    try {
      lockout.beginTransferOrThrow("pushConfig");
    } catch (IllegalStateException expected) {
      threwOnEnabled = true;
    }
    assertTrue(threwOnEnabled, "Must block transfers while Enabled");

    lockout.updateState(false, true, 10_000L);
    boolean threwOnFms = false;
    try {
      lockout.beginTransferOrThrow("pullLogs");
    } catch (IllegalStateException expected) {
      threwOnFms = true;
    }
    assertTrue(threwOnFms, "Must block transfers while attached to FMS");
  }

  private static void testPackageDeployAndRollbackRing() throws IOException {
    Path temp = createTempDir("fluxtray-deploy-test-");
    MockRobotServer robot = new MockRobotServer(temp.resolve("robot"), "systemcore-2027");
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    PackageDeployer deployerA = new PackageDeployer(lockout, "pit-laptop-A");
    PackageDeployer deployerB = new PackageDeployer(lockout, "pit-laptop-B");

    Path validConfigs = createSampleFluxFigConfigs(temp.resolve("configs-valid"), "can_s0", 60.0);
    Path pathPlannerDir = validConfigs.resolve("pathplanner/paths");
    Files.createDirectories(pathPlannerDir);
    Files.writeString(
        pathPlannerDir.resolve("Shop_Center_Shuttle.path"),
        "{\"version\":\"2025.0\",\"waypoints\":[]}",
        StandardCharsets.UTF_8);
    Path jarV1 = temp.resolve("v1.jar");
    Path jarV2Exp = temp.resolve("v2-exp.jar");
    Files.writeString(jarV1, "STABLE_JAR_V1_BYTES", StandardCharsets.UTF_8);
    Files.writeString(jarV2Exp, "EXP_STUDENT_JAR_V2_BYTES", StandardCharsets.UTF_8);

    Path pkgStableV1 =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("STABLE_v1.fluxpkg.zip"),
            new PackageManifest(
                "STABLE_v1",
                "systemcore-2027",
                "STABLE",
                "1111111",
                false,
                "a1b2c3d4",
                "pit-laptop-A"),
            jarV1,
            validConfigs);

    deployerA.deployPackage(pkgStableV1, robot, false);
    assertEquals(
        "STABLE_JAR_V1_BYTES",
        robot.readRemoteText("frcUserProgram.jar"),
        "Active JAR is STABLE v1");
    assertEquals(
        "STABLE_JAR_V1_BYTES",
        robot.readRemoteText("jars/prev_stable.jar"),
        "prev_stable.jar saved");
    assertEquals(
        "{\"version\":\"2025.0\",\"waypoints\":[]}",
        robot.readRemoteText("deploy/pathplanner/paths/Shop_Center_Shuttle.path"),
        "deploy/pathplanner/** recursively deployed from .fluxpkg.zip");
    assertEquals(1, robot.restartCount(), "Robot restarted after deploy");

    Path pkgExpV2 =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("EXP_v2.fluxpkg.zip"),
            new PackageManifest(
                "EXP_v2",
                "systemcore-2027",
                "EXPERIMENTAL",
                "2222222",
                true,
                "a1b2c3d4",
                "pit-laptop-B"),
            jarV2Exp,
            validConfigs);

    boolean overwriteBlocked = false;
    try {
      deployerB.deployPackage(pkgExpV2, robot, false);
    } catch (IllegalStateException expected) {
      overwriteBlocked = true;
    }
    assertTrue(overwriteBlocked, "Pre-deploy overwrite guard must block second laptop by default");

    deployerB.deployPackage(pkgExpV2, robot, true);
    assertEquals(
        "EXP_STUDENT_JAR_V2_BYTES",
        robot.readRemoteText("frcUserProgram.jar"),
        "Active JAR updated to EXPERIMENTAL v2");
    assertEquals(
        "STABLE_JAR_V1_BYTES",
        robot.readRemoteText("jars/prev_1.jar"),
        "prev_1.jar holds previous STABLE v1");
    assertEquals(
        "STABLE_JAR_V1_BYTES",
        robot.readRemoteText("jars/prev_stable.jar"),
        "prev_stable.jar remains untouched by EXPERIMENTAL deploy");
    assertEquals(2, robot.restartCount(), "Robot restarted after second deploy");

    deployerB.restoreStable(robot);
    assertEquals(
        "STABLE_JAR_V1_BYTES",
        robot.readRemoteText("frcUserProgram.jar"),
        "Restore STABLE restored STABLE v1");
    assertEquals(3, robot.restartCount(), "Robot restarted after Restore STABLE");

    Path badConfigs = createSampleFluxFigConfigs(temp.resolve("configs-bad"), "can_s1", 120.0);
    boolean badConfigBlocked = false;
    try {
      deployerA.pushFluxFigConfigs(badConfigs, "COMP_2027", robot);
    } catch (IllegalStateException expected) {
      badConfigBlocked = true;
    }
    assertTrue(badConfigBlocked, "FluxFig safety gate must block odd CAN port can_s1 and 120A stator");
  }

  private static void testPracticeAndFmsCaptureSyncWorkflow() throws IOException {
    Path temp = createTempDir("fluxtray-run-test-");
    Path hubDir = temp.resolve("FLUX-10413-Hub");
    createSampleFluxFigConfigs(hubDir.resolve("03_FluxFig_Configs"), "can_s0", 60.0);
    Path dsLogDir = temp.resolve("ds-logs");
    Files.createDirectories(dsLogDir);
    Files.writeString(
        dsLogDir.resolve("2027_02_18_16_08_00.dslog"), "DSLOG_BYTES", StandardCharsets.UTF_8);

    MockRobotServer robot = new MockRobotServer(temp.resolve("robot"), "systemcore-2027");
    robot.seedSimulatedMatchLogs("2027-02-18_16-08-12");

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager mgr = new BatteryAndRunManager(hubDir, lockout, audio);

    assertTrue(mgr.checkRobotBootEpoch(robot.currentBootEpochId()), "New boot epoch detected");
    assertTrue(mgr.isBatteryPromptPending(), "Battery prompt pending after boot");

    lockout.updateState(true, false, 1_000L);
    audio.onRobotStateChanged(true, 1_000L);
    audio.recordVoiceCue("Coach", 4.2, "We forgot the tray menu, running battery 2026-B07 today");
    audio.recordVoiceCue("Driver", 42.5, "Slight voltage sag coming out of the corner");
    lockout.updateState(false, false, 150_000L);
    audio.onRobotStateChanged(false, 150_000L);

    Path usbMirror = temp.resolve("FLUXLOGS_USB");
    CompletedRunBundle practiceRun =
        mgr.pullPracticeRunLogs(
            "2027-02-18_16-08-12_COMP_2027",
            "COMP_2027",
            "88b120c",
            "a1b2c3d4",
            robot,
            dsLogDir,
            usbMirror,
            165_000L);

    assertEquals("2026-B07", practiceRun.batteryName(), "Spoken battery fallback resolved 2026-B07");
    assertEquals("COMPLETE", practiceRun.syncStatus(), "Practice run is immediately COMPLETE");
    String practiceMetaJson =
        Files.readString(practiceRun.runDir().resolve("run-meta.json"), StandardCharsets.UTF_8);
    assertTrue(
        practiceMetaJson.contains("\"robotId\": \"COMP_2027\"")
            && practiceMetaJson.contains("\"profile\": \"COMP_2027\"")
            && practiceMetaJson.contains("\"batteryName\": \"2026-B07\"")
            && practiceMetaJson.contains("\"configSha256\": \"a1b2c3d4\"")
            && practiceMetaJson.contains("\"loopPeriodMs\": 5")
            && practiceMetaJson.contains("\"bootTimestampIso\": \"2027-02-18T16:08:00Z\""),
        "Practice run-meta.json preserves unified schema and robot boot metadata");
    assertTrue(
        Files.isRegularFile(practiceRun.runDir().resolve("system-snapshot.txt")),
        "system-snapshot.txt saved in practice run folder");
    assertTrue(
        practiceRun.zipArchive() != null
            && Files.isRegularFile(practiceRun.zipArchive())
            && practiceRun.zipArchive().getFileName().toString().startsWith("match-logs_")
            && practiceRun.zipArchive().getFileName().toString().endsWith("_COMP_2027.zip")
            && Files.size(practiceRun.zipArchive()) > 200,
        "Default match-logs_<date>_<time>_COMP_2027.zip packaged by default");
    assertTrue(
        Files.isRegularFile(
                usbMirror
                    .resolve("2027-02-18_16-08-12_COMP_2027")
                    .resolve(MultiMicAudioRecorder.AUDIO_FILE_NAME))
            && Files.isRegularFile(
                usbMirror
                    .resolve("2027-02-18_16-08-12_COMP_2027")
                    .resolve(MultiMicAudioRecorder.AUDIO_SYNC_FILE_NAME))
            && Files.isRegularFile(usbMirror.resolve(practiceRun.zipArchive().getFileName())),
        "Mirrored run bundle with mentor-audio.wav, audio-sync.json, and default .zip archive");

    Path customOutDir = temp.resolve("custom-export-dir");
    CompletedRunBundle customOutRun =
        mgr.pullPracticeRunLogs(
            "2027-02-18_16-12-00_COMP_2027",
            "COMP_2027",
            "88b120c",
            "a1b2c3d4",
            robot,
            dsLogDir,
            null,
            165_000L,
            customOutDir,
            "my-custom-bundle.zip");
    assertEquals(
        customOutDir.resolve("my-custom-bundle.zip"),
        customOutRun.zipArchive(),
        "Custom output directory and zip filename honored");
    assertTrue(
        Files.isRegularFile(customOutRun.zipArchive())
            && Files.size(customOutRun.zipArchive()) > 200,
        "Custom zip archive created and non-empty");

    robot.setReachable(false);
    lockout.updateState(true, true, 200_000L);
    audio.onRobotStateChanged(true, 200_000L);
    mgr.selectBattery("2026-B03");
    audio.recordVoiceCue("Operator", 88.0, "[Operator] Elevator hit L4 cleanly");
    lockout.updateState(false, false, 350_000L);
    audio.onRobotStateChanged(false, 350_000L);

    CompletedRunBundle fieldCapture =
        mgr.captureFieldSideFmsRun(
            "2027-03-20_Qual-14_COMP_2027", "COMP_2027", "88b120c", "a1b2c3d4", dsLogDir);
    assertEquals(
        "PENDING_ROBOT_LOGS",
        fieldCapture.syncStatus(),
        "Field-side FMS capture marked PENDING_ROBOT_LOGS");
    assertEquals(
        0,
        audio.recordedCues().size(),
        "recordedCues cleared after run capture so stale cues never leak into next run");

    robot.setReachable(true);
    robot.seedSimulatedMatchLogs("Qual14");
    List<CompletedRunBundle> synced = mgr.syncPendingFmsRuns(robot);
    assertEquals(1, synced.size(), "Synced 1 pending FMS match run in pit");
    assertEquals("COMPLETE", synced.get(0).syncStatus(), "Pending FMS run upgraded to COMPLETE");
    assertEquals("2026-B03", synced.get(0).batteryName(), "Synced FMS run retains 2026-B03");
    String syncedMetaJson =
        Files.readString(synced.get(0).runDir().resolve("run-meta.json"), StandardCharsets.UTF_8);
    assertTrue(
        syncedMetaJson.contains("\"robotId\": \"COMP_2027\"")
            && syncedMetaJson.contains("\"batteryName\": \"2026-B03\"")
            && syncedMetaJson.contains("\"loopPeriodMs\": 5")
            && syncedMetaJson.contains("\"bootTimestampIso\": \"2027-02-18T16:08:00Z\"")
            && syncedMetaJson.contains("Elevator hit L4 cleanly"),
        "Pit-synced FMS run-meta.json merges field voiceNotes with robot boot metadata");
    assertTrue(
        Files.isRegularFile(synced.get(0).runDir().resolve("system-snapshot.txt")),
        "system-snapshot.txt added during pit sync");
    assertTrue(
        synced.get(0).zipArchive() != null
            && Files.isRegularFile(synced.get(0).zipArchive())
            && synced.get(0).zipArchive().getFileName().toString().startsWith("match-logs_")
            && synced.get(0).zipArchive().getFileName().toString().endsWith("_COMP_2027.zip")
            && Files.size(synced.get(0).zipArchive()) > 200,
        "Pit-synced FMS run also packaged into match-logs_<date>_<time>_COMP_2027.zip");
  }

  private static void testHealthyAndAnomalousSystemSnapshots() {
    FluxSystemSnapshot healthy = FluxSystemSnapshot.healthySystemCoreBaseline();
    String serialized = healthy.serialize();
    FluxSystemSnapshot reparsed = FluxSystemSnapshot.parse(serialized);
    List<WatchDecision> healthyDecisions = reparsed.evaluate();
    assertEquals(0, healthyDecisions.size(), "Healthy SystemCore snapshot must have 0 warnings");

    FluxSystemSnapshot stressed =
        new FluxSystemSnapshot(
            "SYSTEMCORE",
            "SystemCore-10413",
            "6.6.47-rt39-v8-16k+",
            612.0,
            5.0,
            2.10,
            7.85,
            16.40,
            18.50,
            2.15,
            0.90,
            3.40,
            0.40,
            88.5,
            14.0,
            68.0,
            29.5,
            2.5,
            6.8,
            95_000L,
            82_000L,
            83.5,
            1800.0,
            0x50005,
            3.85,
            9,
            42.0,
            3,
            280.0,
            31.0,
            0.68,
            6.5,
            19.4,
            740L,
            List.of(
                new CanInterfaceStats(
                    "can_s0", 380_000L, 90_000L, 14L, 0L, 6L, 0, 0, "ERROR-ACTIVE"),
                new CanInterfaceStats(
                    "can_s1", 1_200L, 400L, 0L, 0L, 0L, 0, 0, "ERROR-ACTIVE"),
                new CanInterfaceStats(
                    "can_s2", 140_000L, 45_000L, 0L, 0L, 0L, 19, 1, "ERROR-PASSIVE")));

    List<WatchDecision> decisions = FluxSystemSnapshot.parse(stressed.serialize()).evaluate();
    assertTrue(
        decisions.size() >= 14,
        "Expected all 14+ watch metric decisions to fire, got " + decisions.size());
  }

  private static void testFluxFigTuningModeSwitchAndVersionPromotion() throws IOException {
    Path temp = createTempDir("fluxtray-tuning-promote-");
    Path hubDir = temp.resolve("FLUX-10413-Hub");
    Path hubConfigs =
        createSampleFluxFigConfigs(hubDir.resolve("03_FluxFig_Configs"), "can_s0", 60.0);
    Path dsLogDir = temp.resolve("ds-logs");
    Files.createDirectories(dsLogDir);

    MockRobotServer robot = new MockRobotServer(temp.resolve("robot"), "systemcore-2027");
    robot.seedSimulatedMatchLogs("2027-02-19_10-00-00");
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    PackageDeployer deployer = new PackageDeployer(lockout, "pit-laptop-A");

    String initialSha =
        deployer.pushFluxFigConfigs(hubConfigs, "COMP_2027", FluxFig.Mode.PRODUCTION, robot);
    FluxFig.ActiveProfileDirective tuningDirective =
        deployer.switchActiveMode("COMP_2027", FluxFig.Mode.TUNING, initialSha, robot);
    assertEquals(FluxFig.Mode.TUNING, tuningDirective.mode(), "Switched robot to Mode.TUNING");
    assertEquals("COMP_2027", tuningDirective.profile(), "Retained active profile COMP_2027");

    FluxFig.InMemoryTuningBackend backend = new FluxFig.InMemoryTuningBackend();
    FluxFig robotFig = FluxFig.loadTuning(hubConfigs, "COMP_2027", backend);
    robotFig.registerTunableParameters("loopPeriodMs");
    robotFig.bindTable("mechanisms", "COMP_2027", FluxFig.MechanismSpec.class);
    backend.putFromDashboard("/FluxFig/Tuning/parameters/loopPeriodMs", "10.0");
    backend.putFromDashboard("/FluxFig/Tuning/mechanisms/Elevator/kP", "3.25");
    int changed = robotFig.pollTuning();
    assertEquals(2, changed, "2 dashboard tunables updated on robot");
    robot.writeRemoteText(
        FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_PATH.toString(), robotFig.getEffectiveSnapshot());

    lockout.updateState(true, false, 1_000L);
    lockout.updateState(false, false, 1_500L);
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager mgr = new BatteryAndRunManager(hubDir, lockout, audio);
    mgr.selectBattery("2026-B03");
    CompletedRunBundle run =
        mgr.pullPracticeRunLogs(
            "2027-02-19_10-00-00_COMP_2027",
            "COMP_2027",
            "88b120c",
            initialSha,
            robot,
            dsLogDir,
            null,
            5_000L);
    assertTrue(
        Files.isRegularFile(run.runDir().resolve("tuned-snapshot.txt")),
        "Post-run auto-pull safety net archived tuned-snapshot.txt");

    FluxFig.ReleaseEntry release =
        deployer.promoteRemoteTuningSnapshot(
            hubConfigs, "student-alex", "Dialed Elevator kP", robot);
    assertEquals("v1", release.version(), "First promoted release is v1");
    assertTrue(
        !release.configSha256().equals(initialSha),
        "Promoted release has updated ConfigSHA256");
    assertTrue(
        !robot.remoteFileExists(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_PATH.toString()),
        "Remote /tmp/fluxfig-tuned-snapshot.txt deleted after promotion");

    FluxFig reloadedHub = FluxFig.load(hubConfigs, "COMP_2027");
    assertEquals(10.0, reloadedHub.getDouble("loopPeriodMs"), "Promoted loopPeriodMs=10.0 in CSV");
    assertEquals(
        3.25,
        reloadedHub.getMechanismSpec("COMP_2027", "Elevator").kP(),
        "Promoted Elevator.kP=3.25 in mechanisms.csv");

    FluxFig.ActiveProfileDirective postPromoteDirective =
        FluxFig.parseActiveProfileDirective(
            robot.readRemoteText("deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE));
    assertEquals(
        FluxFig.Mode.PRODUCTION,
        postPromoteDirective.mode(),
        "Robot locked back to Mode.PRODUCTION after promotion");
    assertEquals(
        release.configSha256(),
        postPromoteDirective.expectedConfigSha256(),
        "Robot active-profile.txt points to promoted v1 ConfigSHA256");
  }

  private static void testScoutingPwaAndSignedQrScanner() throws IOException {
    Path temp = createTempDir("fluxtray-scouting-test-");
    Path hubDir = temp.resolve("FLUX-10413-Hub");
    Path pwaDir = temp.resolve("scout-pwa");
    String secret = ScoutingQrManager.DEFAULT_TEAM_SECRET_HEX;

    Path scoutHtml = ScoutingQrManager.exportOfflineScoutingPwa(pwaDir, secret);
    assertTrue(Files.isRegularFile(scoutHtml), "scout.html generated");
    assertTrue(Files.isRegularFile(pwaDir.resolve("sw.js")), "sw.js Service Worker generated");
    assertTrue(
        Files.isRegularFile(pwaDir.resolve("manifest.webmanifest")),
        "manifest.webmanifest generated");
    assertTrue(
        Files.isRegularFile(pwaDir.resolve("enroll-qr.png")),
        "One-time enrollment QR PNG generated");

    String decodedEnrollUri = ScoutingQrManager.readVisualMatrixPng(pwaDir.resolve("enroll-qr.png"));
    assertTrue(
        decodedEnrollUri.contains("#enroll?student=scouter-1&key=" + secret),
        "Enrollment 2D matrix PNG decoded to exact enrollment URI");

    String signedPayloadSeq1 =
        ScoutingQrManager.encodeSignedPayload(
            "2027NYTR",
            14,
            "Blue 3",
            10413,
            "scouter-maya",
            "CENTER",
            3,
            11,
            2,
            "DEEP_CLIMB",
            1L,
            1774022400L,
            "Fast center lane auto, needs right feeder clear",
            secret);
    Path matchQrPng = temp.resolve("match14-qr.png");
    ScoutingQrManager.writeVisualMatrixPng(signedPayloadSeq1, matchQrPng);
    String decodedFromPng = ScoutingQrManager.readVisualMatrixPng(matchQrPng);
    assertEquals(
        signedPayloadSeq1, decodedFromPng, "2D Finder-Matrix PNG round-trip matches signed payload");

    ScoutingQrManager.ScoutingMatchEntry ingested =
        ScoutingQrManager.ingestVerifiedPayload(decodedFromPng, hubDir, secret);
    assertEquals(10413, ingested.teamNumber(), "Ingested team 10413");
    assertEquals(11, ingested.teleopCycles(), "Ingested 11 teleop cycles");
    assertEquals(
        "Fast center lane auto, needs right feeder clear",
        ingested.queueNotes(),
        "Ingested queue notes");

    String tamperedPayload = signedPayloadSeq1.replace("|11|", "|99|");
    boolean tamperBlocked = false;
    try {
      ScoutingQrManager.verifyAndDecodePayload(tamperedPayload, secret);
    } catch (SecurityException expected) {
      tamperBlocked = true;
    }
    assertTrue(tamperBlocked, "HMAC-SHA256 verification must reject tampered cycle count");

    boolean replayBlocked = false;
    try {
      ScoutingQrManager.ingestVerifiedPayload(signedPayloadSeq1, hubDir, secret);
    } catch (SecurityException expected) {
      replayBlocked = true;
    }
    assertTrue(replayBlocked, "Anti-replay check must reject duplicate sequenceNumber=1");

    String signedPayloadSeq2 =
        ScoutingQrManager.encodeSignedPayload(
            "2027NYTR",
            14,
            "Blue 3",
            10413,
            "scouter-maya",
            "CENTER",
            3,
            12,
            2,
            "DEEP_CLIMB",
            2L,
            1774022460L,
            "Corrected: 12 teleop cycles",
            secret);
    ScoutingQrManager.ScoutingMatchEntry updated =
        ScoutingQrManager.ingestVerifiedPayload(signedPayloadSeq2, hubDir, secret);
    assertEquals(12, updated.teleopCycles(), "Updated to 12 teleop cycles with seq=2");
    List<String> csvLines =
        Files.readAllLines(
            hubDir.resolve("04_Practice_And_Match_Logs").resolve("scouting-matches.csv"),
            StandardCharsets.UTF_8);
    assertEquals(2, csvLines.size(), "scouting-matches.csv has header + 1 deduplicated match row");
  }

  private static void testNt4WebSocketLiveLockoutAndOncePerDisableAutoPull() throws Exception {
    AtomicLong fakeClockMs = new AtomicLong(10_000L);
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    AtomicInteger abortCallbacks = new AtomicInteger(0);
    lockout.addAbortListener(abortCallbacks::incrementAndGet);

    CountDownLatch enabledObserved = new CountDownLatch(1);
    CountDownLatch disabledObserved = new CountDownLatch(1);

    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      int port = server.getLocalPort();
      Thread mockServer =
          new Thread(
              () -> {
                try (Socket client = server.accept()) {
                  performWsServerHandshake(client);
                  OutputStream out = client.getOutputStream();
                  String announce =
                      "[{\"method\":\"announce\",\"params\":{\"name\":\"/FMSInfo/FMSControlData\",\"id\":7,\"type\":\"int\",\"properties\":{}}}]";
                  sendWsFrame(out, 0x01, announce.getBytes(StandardCharsets.UTF_8));
                  // Send Enabled + DS attached (0x21).
                  sendWsFrame(out, 0x02, encodeNt4IntFrame(7, 1_000_000L, 0x21));
                  enabledObserved.await(3, TimeUnit.SECONDS);
                  // Advance clock and transition to Disabled + DS attached (0x20).
                  fakeClockMs.set(20_000L);
                  sendWsFrame(out, 0x02, encodeNt4IntFrame(7, 2_000_000L, 0x20));
                  disabledObserved.await(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
              },
              "mock-nt4-ws-server");
      mockServer.setDaemon(true);
      mockServer.start();

      // Start an active transfer while idle -> must abort when NT4 reports Enabled.
      lockout.beginTransferOrThrow("in-flight-scp");
      assertEquals(1, lockout.activeTransferCount(), "Active transfer before NT4 Enable");

      try (Nt4RobotStateClient ntClient =
          new Nt4RobotStateClient(
              List.of("127.0.0.1"),
              port,
              lockout,
              (enabled, fms, word, host, ts) -> {
                if (enabled) {
                  enabledObserved.countDown();
                } else if (word == 0x20) {
                  disabledObserved.countDown();
                }
              },
              fakeClockMs::get)) {
        ntClient.start();
        assertTrue(enabledObserved.await(5, TimeUnit.SECONDS), "NT4 client received Enabled frame");
        assertTrue(lockout.isEnabled(), "Lockout entered Enabled from live NT4 frame");
        assertEquals(0, lockout.activeTransferCount(), "In-flight transfer aborted on NT4 Enable");
        assertTrue(abortCallbacks.get() >= 1, "Abort listener invoked on NT4 Enable");

        assertTrue(
            disabledObserved.await(5, TimeUnit.SECONDS), "NT4 client received Disabled frame");
        assertTrue(!lockout.isEnabled(), "Lockout returned to Disabled");
        assertEquals(20_000L, lockout.lastDisabledTimestampMs(), "Disabled timestamp recorded");

        assertTrue(
            !lockout.isReadyForAutoLogPull(22_000L), "Auto-pull blocked at +2.0s after Disable");
        assertTrue(
            lockout.isReadyForAutoLogPull(23_100L), "Auto-pull ready at +3.1s after Disable");
        assertTrue(
            lockout.claimAutoLogPull(23_100L), "Auto-pull claims exactly once after Disable");
        assertTrue(
            !lockout.claimAutoLogPull(24_000L), "Auto-pull does NOT fire a second time");
      }
    }
  }

  private static void testRoboRioRobotCommandAtomicDeployRestartAndRobotStateLockout()
      throws IOException {
    assertTrue(
        SshRobotTransport.ROBORIO_CANDIDATE_HOSTS.containsAll(
            List.of("10.104.13.2", "172.22.11.2", "roboRIO-10413-FRC.local")),
        "Multi-host roboRIO discovery list matches spec");
    assertEquals(15L, SshRobotTransport.COMMAND_TIMEOUT_SEC, "Command timeout is 15s");
    assertEquals(300L, SshRobotTransport.TRANSFER_TIMEOUT_SEC, "Transfer timeout is 300s");
    List<String> sshOpts = SshRobotTransport.buildSshOptions();
    assertTrue(
        sshOpts.stream().anyMatch(o -> o.startsWith("UserKnownHostsFile=")),
        "SSH options include OS-safe UserKnownHostsFile");
    assertTrue(!sshOpts.contains("-O"), "Never pass legacy -O option");

    String parsedJar =
        PackageDeployer.parseJarPathFromRobotCommand(
            "exec /usr/local/frc/JRE/bin/java -XX:+UseSerialGC -jar /home/lvuser/Flux-RoboRIO-2027.jar\n");
    assertEquals(
        "Flux-RoboRIO-2027.jar", parsedJar, "Parsed exact GradleRIO JAR from robotCommand");

    Path temp = createTempDir("fluxtray-roborio-deploy-");
    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    rio.setRobotStateFile(false, false, "FUEL");

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");

    Path cfgV1 = createSampleFluxFigConfigs(temp.resolve("cfg-v1"), "Drivetrain", 60.0);
    Files.writeString(cfgV1.resolve("marker.txt"), "DEPLOY_V1_MARKER", StandardCharsets.UTF_8);
    Path jarV1 = temp.resolve("fuel-v1.jar");
    Files.writeString(jarV1, "ROBORIO_STABLE_V1", StandardCharsets.UTF_8);
    Path pkgV1 =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("STABLE_v1_roborio-2026_fluxpkg.zip"),
            new PackageManifest(
                "STABLE_v1", "roborio-2026", "STABLE", "aaa1111", false, "cfg11111", "boris"),
            jarV1,
            cfgV1);

    deployer.deployPackage(pkgV1, rio, true);
    assertEquals(
        "ROBORIO_STABLE_V1",
        rio.readRemoteText("Flux-RoboRIO-2027.jar"),
        "Deployed onto exact robotCommand JAR target Flux-RoboRIO-2027.jar");
    assertTrue(
        !rio.remoteFileExists("frcUserProgram.jar"),
        "Did NOT write mismatched frcUserProgram.jar when robotCommand targets Flux-RoboRIO-2027.jar");
    assertEquals(
        "DEPLOY_V1_MARKER",
        rio.readRemoteText("jars/prev_stable_deploy/marker.txt"),
        "deploy/ tree saved inside jars/prev_stable_deploy/");
    assertEquals("Flux-RoboRIO-2027.jar", rio.lastRestartedJar(), "Restarted exact active JAR");
    assertEquals(1, rio.restartCount(), "Restart executed after deploy");

    // Dirty EXPERIMENTAL package must NOT overwrite prev_stable even if channel mistakenly says STABLE.
    Path cfgV2 = createSampleFluxFigConfigs(temp.resolve("cfg-v2"), "Drivetrain", 60.0);
    Files.writeString(cfgV2.resolve("marker.txt"), "DEPLOY_V2_EXP_MARKER", StandardCharsets.UTF_8);
    Path jarV2 = temp.resolve("fuel-v2-dirty.jar");
    Files.writeString(jarV2, "ROBORIO_DIRTY_V2", StandardCharsets.UTF_8);
    Path pkgDirty =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("EXP_v2_roborio-2026_fluxpkg.zip"),
            new PackageManifest(
                "EXP_v2", "roborio-2026", "STABLE", "bbb2222", true, "cfg22222", "boris"),
            jarV2,
            cfgV2);
    deployer.deployPackage(pkgDirty, rio, true);
    assertEquals("ROBORIO_DIRTY_V2", rio.readRemoteText("Flux-RoboRIO-2027.jar"), "Live JAR is v2");
    assertEquals(
        "ROBORIO_STABLE_V1",
        rio.readRemoteText("jars/prev_stable.jar"),
        "Dirty package did NOT overwrite jars/prev_stable.jar");
    assertEquals(
        "DEPLOY_V1_MARKER",
        rio.readRemoteText("jars/prev_stable_deploy/marker.txt"),
        "Dirty package did NOT overwrite jars/prev_stable_deploy/");

    // Restore STABLE must restore BOTH JAR and deploy/ and restart robot code.
    deployer.restoreStable(rio);
    assertEquals(
        "ROBORIO_STABLE_V1",
        rio.readRemoteText("Flux-RoboRIO-2027.jar"),
        "Restore STABLE restored exact robotCommand JAR");
    assertEquals(
        "DEPLOY_V1_MARKER",
        rio.readRemoteText("deploy/marker.txt"),
        "Restore STABLE restored matching deploy/ directory");
    assertEquals(3, rio.restartCount(), "Restart executed after Restore STABLE");

    // switchActiveMode with null profile must preserve detected robot identity FUEL (not COMP_2027).
    FluxFig.ActiveProfileDirective dir =
        deployer.switchActiveMode(null, FluxFig.Mode.TUNING, "cfg11111", rio);
    assertEquals("FUEL", dir.profile(), "switchActiveMode resolved FUEL rather than COMP_2027");

    // Robot-side /tmp/flux-robot-state ENABLED must block both deploy and log pull even if local lockout is idle.
    rio.setRobotStateFile(true, false, "FUEL");
    boolean blockedByStateFile = false;
    try {
      deployer.pushFluxFigConfigs(cfgV1, null, rio);
    } catch (IllegalStateException expected) {
      blockedByStateFile = expected.getMessage().contains("/tmp/flux-robot-state");
    }
    assertTrue(blockedByStateFile, "Robot-side /tmp/flux-robot-state ENABLED blocks mutating push");

    boolean pullBlockedByStateFile = false;
    try {
      rio.downloadAllLogs(temp.resolve("blocked-pull"));
    } catch (IllegalStateException expected) {
      pullBlockedByStateFile = expected.getMessage().contains("/tmp/flux-robot-state");
    }
    assertTrue(pullBlockedByStateFile, "Robot-side /tmp/flux-robot-state ENABLED blocks log pull");
  }

  private static void testDriveWatcherIncrementalLedgerDsLogFilterAtomicZipAndRealWavAudio()
      throws Exception {
    Path temp = createTempDir("fluxtray-drivewatcher-");
    Path codeDir = temp.resolve("drive/code");
    Path logsDir = temp.resolve("drive/logs");
    Path dsLogDir = temp.resolve("ds-logs");
    Path ledgerFile = temp.resolve("state/synced-remote-files.json");
    Path trayLogFile = temp.resolve("state/fluxtray.log");
    Files.createDirectories(codeDir);
    Files.createDirectories(logsDir);
    Files.createDirectories(dsLogDir);

    long nowMs = Instant.parse("2026-11-20T18:00:00Z").toEpochMilli();
    DateTimeFormatter dsFmt = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss", Locale.US);
    LocalDateTime nowLdt =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
    Path recentDs = dsLogDir.resolve(nowLdt.minusMinutes(1).format(dsFmt) + ".dslog");
    Path oldDs = dsLogDir.resolve(nowLdt.minusHours(3).format(dsFmt) + ".dslog");
    Files.writeString(recentDs, "RECENT_DSLOG", StandardCharsets.UTF_8);
    Files.writeString(oldDs, "OLD_SEASON_DSLOG", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(recentDs, FileTime.fromMillis(nowMs - 60_000L));
    Files.setLastModifiedTime(oldDs, FileTime.fromMillis(nowMs - 3L * 3600_000L));

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    rio.setRobotStateFile(false, false, "FUEL");
    rio.seedSimulatedMatchLogs("2026-11-20_17-59-30", "FUEL", "cafebabe");
    String rawRioSnap =
        "[PLATFORM]\ncontrollerType=ROBORIO\nhostname=roboRIO-10413-FRC\n[CPU_AND_IRQ]\ntotalCpuPct=27.5\n[STORAGE]\nfreeStorageMb=410.0\n";
    rio.setNextSnapshotRaw(rawRioSnap);

    // Real 16 kHz 16-bit mono PCM sine wave through PcmCapturePort.
    byte[] syntheticPcm = generateSinePcm16kHz(0.25, 440.0);
    MultiMicAudioRecorder audio =
        new MultiMicAudioRecorder(
            fmt -> {
              AtomicInteger offset = new AtomicInteger(0);
              return new MultiMicAudioRecorder.PcmCapturePort() {
                @Override
                public int read(byte[] buf, int off, int len) {
                  int cur = offset.get();
                  if (cur >= syntheticPcm.length) {
                    return -1;
                  }
                  int n = Math.min(len, syntheticPcm.length - cur);
                  System.arraycopy(syntheticPcm, cur, buf, off, n);
                  offset.addAndGet(n);
                  return n;
                }

                @Override
                public void close() {}
              };
            });
    audio.onRobotStateChanged(true, nowMs - 20_000L);
    Thread.sleep(60L);
    audio.onRobotStateChanged(false, nowMs - 5_000L);

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(temp.resolve("hub"), ledgerFile, lockout, audio);
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");
    List<String> toasts = new ArrayList<>();
    DriveWatcher watcher =
        new DriveWatcher(
            codeDir,
            logsDir,
            dsLogDir,
            rio,
            deployer,
            runMgr,
            lockout,
            (lvl, title, msg) -> toasts.add(lvl + ":" + title + ":" + msg));

    // Seed a valid STABLE roboRIO package into code/.
    Path cfgDir = createSampleFluxFigConfigs(temp.resolve("pkg-cfg"), "Drivetrain", 60.0);
    Path jarFile = temp.resolve("fuel.jar");
    Files.writeString(jarFile, "FUEL_CT_PACKAGE_BYTES", StandardCharsets.UTF_8);
    PackageDeployer.createFluxPkgZip(
        codeDir.resolve("STABLE_v2027.2.0_roborio-2026_fluxpkg.zip"),
        new PackageManifest(
            "STABLE_v2027.2.0", "roborio-2026", "STABLE", "abc1234", false, "cafebabe", "boris"),
        jarFile,
        cfgDir);

    // Poll 1: stabilizing only -> no deploy yet.
    PollOutcome poll1 = watcher.pollOnce(nowMs);
    assertEquals(ActionType.NONE, poll1.action(), "Poll 1 must wait for 2-poll stabilization");
    assertEquals(0, rio.restartCount(), "No deploy/restart on poll 1");

    // Poll 2: stabilized -> pulls pending logs FIRST, then deploys + restarts.
    PollOutcome poll2 = watcher.pollOnce(nowMs + 1_000L);
    assertEquals(ActionType.DEPLOYED_PACKAGE, poll2.action(), "Poll 2 deploys stabilized package");
    assertTrue(
        poll2.preDeployLogsBundle() != null && poll2.preDeployLogsBundle().zipArchive() != null,
        "Pre-deploy log pull produced atomic ZIP");

    // Verify pull happened strictly BEFORE uploadAtomic and restart in robot operation log.
    List<String> ops = rio.operationLog();
    int pullIdx = indexOfPrefix(ops, "downloadSelectedLogs:");
    int uploadIdx = indexOfPrefix(ops, "uploadAtomic:Flux-RoboRIO-2027.jar");
    int restartIdx = indexOfPrefix(ops, "restart:Flux-RoboRIO-2027.jar");
    assertTrue(
        pullIdx >= 0 && uploadIdx > pullIdx && restartIdx > uploadIdx,
        "Expected downloadSelectedLogs -> uploadAtomic -> restart order, got " + ops);

    // Verify logs/ contains ONLY the single atomic .zip (no raw run folder, no .part file).
    List<Path> logsDirChildren;
    try (Stream<Path> s = Files.list(logsDir)) {
      logsDirChildren = s.toList();
    }
    assertEquals(1, logsDirChildren.size(), "Drive logs/ must contain ONLY 1 atomic .zip");
    Path producedZip = logsDirChildren.get(0);
    assertTrue(
        producedZip.getFileName().toString().endsWith("_FUEL.zip")
            && !producedZip.getFileName().toString().endsWith(".part"),
        "Final atomic ZIP named for FUEL without .part suffix: " + producedZip.getFileName());

    // Inspect ZIP contents: mentor-audio.wav (real RIFF/WAVE), audio-sync.json, raw snapshot, windowed dslog.
    Set<String> entryNames = new TreeSet<>();
    try (ZipFile zf = new ZipFile(producedZip.toFile())) {
      var en = zf.entries();
      while (en.hasMoreElements()) {
        ZipEntry ze = en.nextElement();
        entryNames.add(ze.getName());
        if (ze.getName().endsWith("/" + MultiMicAudioRecorder.AUDIO_FILE_NAME)) {
          byte[] wavBytes = zf.getInputStream(ze).readAllBytes();
          assertTrue(wavBytes.length > 44, "mentor-audio.wav contains PCM payload beyond 44B header");
          assertEquals("RIFF", new String(wavBytes, 0, 4, StandardCharsets.US_ASCII), "RIFF magic");
          assertEquals("WAVE", new String(wavBytes, 8, 4, StandardCharsets.US_ASCII), "WAVE magic");
          int sampleRate = ByteBuffer.wrap(wavBytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
          assertEquals(16_000, sampleRate, "WAV sample rate is 16000 Hz");
        } else if (ze.getName().endsWith("/" + MultiMicAudioRecorder.AUDIO_SYNC_FILE_NAME)) {
          String syncText = new String(zf.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(
              syncText.contains("\"enableEpochMs\": " + (nowMs - 20_000L))
                  && syncText.contains("\"disableEpochMs\": " + (nowMs - 5_000L)),
              "audio-sync.json contains exact enable/disable epochs: " + syncText);
        } else if (ze.getName().endsWith("/system-snapshot.txt")) {
          String snapText = new String(zf.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8);
          assertEquals(
              rawRioSnap,
              snapText,
              "system-snapshot.txt stores raw capture text (not default-filled serialize())");
        }
      }
    }
    assertTrue(
        entryNames.stream().anyMatch(n -> n.endsWith("/" + recentDs.getFileName())),
        "±4 min matching .dslog included");
    assertTrue(
        entryNames.stream().noneMatch(n -> n.endsWith("/" + oldDs.getFileName())),
        "3-hour-old .dslog excluded by ±4 min window filter");
    assertTrue(
        entryNames.stream().noneMatch(n -> n.endsWith("driver-audio.ogg")),
        "Retired fake text driver-audio.ogg is not written");

    // Incremental ledger: calling findUnsyncedRemoteLogs again returns 0 files.
    assertTrue(Files.isRegularFile(ledgerFile), "synced-remote-files.json persisted");
    assertEquals(
        0, runMgr.findUnsyncedRemoteLogs(rio).size(), "Incremental ledger skips already-synced files");

    // Dropping invalid code/configs/*.csv must be rejected locally without touching the robot.
    Path badConfigsDir = codeDir.resolve("configs");
    createSampleFluxFigConfigs(badConfigsDir, "can_s1", 120.0);
    int restartsBeforeBadCfg = rio.restartCount();
    watcher.pollOnce(nowMs + 2_000L);
    PollOutcome badCfgOutcome = watcher.pollOnce(nowMs + 3_000L);
    assertEquals(
        ActionType.REJECTED_INVALID_CONFIG,
        badCfgOutcome.action(),
        "Invalid code/configs/*.csv blocked locally by FluxFig.validateStrict()");
    assertEquals(restartsBeforeBadCfg, rio.restartCount(), "Robot untouched on invalid config");

    // Emptying code/ (.fluxpkg.zip removed) triggers Restore STABLE after 2 stabilized polls.
    Files.delete(codeDir.resolve("STABLE_v2027.2.0_roborio-2026_fluxpkg.zip"));
    assertEquals(ActionType.NONE, watcher.pollOnce(nowMs + 4_000L).action(), "Empty poll 1 waits");
    PollOutcome restoreOutcome = watcher.pollOnce(nowMs + 5_000L);
    assertEquals(
        ActionType.RESTORED_STABLE,
        restoreOutcome.action(),
        "Emptying code/ restores prev_stable and restarts robot");
    assertEquals(restartsBeforeBadCfg + 1, rio.restartCount(), "Robot restarted on empty-code restore");

    // Tray icon colors & persistent fluxtray.log check.
    BufferedImage green = FluxTrayMain.createStatusIcon(FluxTrayMain.TrayStatus.READY);
    BufferedImage red = FluxTrayMain.createStatusIcon(FluxTrayMain.TrayStatus.ENABLED);
    assertTrue(green.getRGB(3, 8) != red.getRGB(3, 8), "READY and ENABLED tray icons differ in color");
    FluxTrayMain.appendTrayLog(trayLogFile, "INFO", "Smoke test log entry");
    assertTrue(
        Files.readString(trayLogFile, StandardCharsets.UTF_8).contains("Smoke test log entry"),
        "Persistent fluxtray.log written");
    assertTrue(!toasts.isEmpty(), "Toast notifications emitted");
  }

  private static byte[] generateSinePcm16kHz(double seconds, double freqHz) {
    int samples = (int) Math.round(seconds * MultiMicAudioRecorder.SAMPLE_RATE_HZ);
    ByteBuffer buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) {
      short s =
          (short)
              Math.round(
                  12_000.0 * Math.sin(2.0 * Math.PI * freqHz * i / MultiMicAudioRecorder.SAMPLE_RATE_HZ));
      buf.putShort(s);
    }
    return buf.array();
  }

  private static int indexOfPrefix(List<String> list, String prefix) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  private static void performWsServerHandshake(Socket client) throws Exception {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
    String key = "";
    String protocol = "v4.1.networktables.first.wpi.edu";
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")) {
        key = line.substring(line.indexOf(':') + 1).trim();
      } else if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-protocol:")) {
        protocol = line.substring(line.indexOf(':') + 1).split(",")[0].trim();
      }
    }
    String acceptSeed = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(acceptSeed.getBytes(StandardCharsets.US_ASCII));
    String accept = Base64.getEncoder().encodeToString(sha1);
    String response =
        "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + accept
            + "\r\n"
            + "Sec-WebSocket-Protocol: "
            + protocol
            + "\r\n\r\n";
    OutputStream out = client.getOutputStream();
    out.write(response.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static void sendWsFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    frame.write(0x80 | (opcode & 0x0F));
    if (payload.length < 126) {
      frame.write(payload.length);
    } else {
      frame.write(126);
      frame.write((payload.length >> 8) & 0xFF);
      frame.write(payload.length & 0xFF);
    }
    frame.write(payload);
    out.write(frame.toByteArray());
    out.flush();
  }

  private static byte[] encodeNt4IntFrame(int topicId, long timestampUs, int intVal) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x94); // 4-element fixarray: [topicId, timestampUs, typeId(1=int), value]
    out.write(topicId & 0x7F);
    out.write(0xCF); // uint64 timestamp
    for (int shift = 56; shift >= 0; shift -= 8) {
      out.write((int) ((timestampUs >>> shift) & 0xFF));
    }
    out.write(1); // NT4 int type ID
    out.write(intVal & 0x7F);
    return out.toByteArray();
  }

  private static Path createSampleFluxFigConfigs(
      Path dir, String swerveBus, double statorLimitAmps) throws IOException {
    Files.createDirectories(dir);
    boolean isRoboRio = !"can_s0".equals(swerveBus) && !"can_s1".equals(swerveBus);
    String ctrl = isRoboRio ? "ROBORIO_CANIVORE_2026" : "SYSTEMCORE_2027";
    String mechBus = isRoboRio ? "Mech" : "can_s2";
    String robotProfile = isRoboRio ? "FUEL" : "COMP_2027";
    Files.writeString(
        dir.resolve("parameters.csv"),
        "Key,DEFAULT,"
            + robotProfile
            + "\n"
            + "controllerProfile,"
            + ctrl
            + ","
            + ctrl
            + "\n"
            + "swerveCanBus,"
            + swerveBus
            + ","
            + swerveBus
            + "\n"
            + "mechCanBus,"
            + mechBus
            + ","
            + mechBus
            + "\n"
            + "loopPeriodMs,"
            + (isRoboRio ? "20.0,20.0" : "5.0,5.0")
            + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("mechanisms.csv"),
        "robot,mechanism,motorId,canBus,statorLimitAmps,supplyLimitAmps,slipCurrentAmps,gravityType,kP,kI,kD,kS,kV,kA,kG,minPosRot,maxPosRot,followerMotorId,invertFollower,motorInverted,gearRatio\n"
            + robotProfile
            + ",Elevator,10,"
            + mechBus
            + ","
            + statorLimitAmps
            + ",40.0,60.0,Elevator_Static,2.4,0.0,0.1,0.18,0.12,0.01,0.35,0.0,48.0,11,false,false,1.0\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("cameras.csv"),
        "robot,cameraName,portIndex,xMeters,yMeters,zMeters,rollDeg,pitchDeg,yawDeg,stdDevScalar\n"
            + robotProfile
            + ",limelightsc0,0,0.28,0.25,0.52,0.0,15.0,0.0,1.0\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("range_table.csv"),
        "distanceMeters,shooterRps,hoodRotations,timeOfFlightSec\n"
            + "2.0,35.0,0.15,0.32\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("batteries.csv"),
        "batteryName,qrCode,purchaseYear,nominalRintMilliOhms,maxAllowedRintMilliOhms\n"
            + "2026-B03,FLUX-2026-B03,2026,14.5,20.0\n"
            + "2026-B07,FLUX-2026-B07,2026,15.0,20.0\n",
        StandardCharsets.UTF_8);
    return dir;
  }

  private static void assertTrue(boolean cond, String msg) {
    if (!cond) {
      throw new AssertionError("Assertion failed: " + msg);
    }
  }

  private static void assertEquals(Object expected, Object actual, String msg) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(
          "Assertion failed (" + msg + "): expected='" + expected + "', actual='" + actual + "'");
    }
  }
}
