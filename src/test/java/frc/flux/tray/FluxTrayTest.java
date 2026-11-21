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
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

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
      testSshTransportBlocksOnEnabledHeartbeatAndQuotesRemotePaths();
      testNt4IdleTimeoutReconnectsAndDisconnectClearsDisplayedStatus();
      testUtcRobotLogNamesAndLocalDsLogWindowWithMtimeGuard();
      testTrayActionCoordinatorSerializesDrivePollsAndOnlySetsBusyOnTransfer();
      testAudioRecorderReEnableInsideDebriefClosesRun1AndStartsRun2();
      testUsbLogRootDeduplicationAndUsbRunMetaHydration();
      testDriveWatcherStatePersistenceRejectedLatchPartialConfigsAndDebounce();
      testFailedPullKeepsMentorAudio();
      testManualRestoreStablePullsLogsFirst();
      testZipBuiltInTempBeforeMoveAndAudioFailureNotifies();
      testMalformedManifestFailsClosedAndRollbackChecksDeployCopy();
      testTruncatedMsgPackFrameNeverMarksDisabled();
      testSshBinaryStreamingAndBusyBoxHeartbeatGuardViaRealShell();
      testGitHubReleaseUpdaterIntegritySafetyLockoutAndRealPosixSwap();
      testInspectRobotCodeDistinguishesLegacyFromFlux2027();
      testHb08SshAsyncStdinLockoutExit42AndTimeout();
      testHb09AudioSpoolBeforeSshFailureAndAutoStopCaps();
      testHb10AudioZeroByteReadSpinExitsQuicklyAndNotifies();
      testHb11SshStreamingBinaryDownloadDirectToDisk();
      testHb12UpdaterOptInTimeoutSemVerTransientRetryAndWindowsQuoting();
      testHb13SingleInstanceLockContentionAndRelease();
      testHb14LockedDriverStationLogSkippedWithoutAbortingPull();
      testHb15DriveWatcherCachesValidationAndBacksOffWhenUnreachable();
      testCrashBootRecoveryAllowsDeployAndRestoreWhenNoRobotJvmRunning();
      testPreDeployAndPreRestoreLogPullFailureIsNonFatalExceptLockout();
      testReadDiagnosticsUsesBusyBoxSafeDfKilobytes();
      System.out.println("FluxTrayTest: ALL 35 SUITES PASSED");
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

    // Emptying code/ (.fluxpkg.zip removed) waits >= 25 s debounce before Restore STABLE.
    Files.delete(codeDir.resolve("STABLE_v2027.2.0_roborio-2026_fluxpkg.zip"));
    assertEquals(ActionType.NONE, watcher.pollOnce(nowMs + 4_000L).action(), "Empty poll 1 waits");
    assertEquals(
        ActionType.NONE,
        watcher.pollOnce(nowMs + 10_000L).action(),
        "Empty poll at +6s still waits inside 25s debounce");
    PollOutcome restoreOutcome = watcher.pollOnce(nowMs + 30_000L);
    assertEquals(
        ActionType.RESTORED_STABLE,
        restoreOutcome.action(),
        "Emptying code/ restores prev_stable after >= 25s debounce and restarts robot");
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

  private static void testSshTransportBlocksOnEnabledHeartbeatAndQuotesRemotePaths()
      throws Exception {
    Path temp = createTempDir("fluxtray-ssh-transport-");
    Path deployDir = temp.resolve("deploy");
    Path pathsDir = deployDir.resolve("pathplanner/paths");
    Files.createDirectories(pathsDir);
    Files.writeString(
        pathsDir.resolve("Hub to Depot.path"), "{\"name\":\"Hub to Depot\"}", StandardCharsets.UTF_8);
    Files.writeString(
        pathsDir.resolve("Left to Depot.path"),
        "{\"name\":\"Left to Depot\"}",
        StandardCharsets.UTF_8);
    Files.writeString(
        pathsDir.resolve("Right to Depot.path"),
        "{\"name\":\"Right to Depot\"}",
        StandardCharsets.UTF_8);

    long remoteNowSec = 1_763_650_000L;
    AtomicReference<String> heartbeatOutput =
        new AtomicReference<>(
            "REMOTE_EPOCH_SEC="
                + remoteNowSec
                + "\nHEARTBEAT_PRESENT=1\nSTATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
                + (remoteNowSec * 1_000L - 500L)
                + "\nROBOT_ID=FUEL\nPID=4321\n");

    record RecordedCall(List<String> argv, byte[] stdin) {}
    List<RecordedCall> recorded = new ArrayList<>();
    SshRobotTransport.ProcessRunner runner =
        (argv, stdin, timeout) -> {
          recorded.add(new RecordedCall(argv, stdin != null ? stdin.clone() : null));
          String lastArg = argv.get(argv.size() - 1);
          if ("ssh".equals(argv.get(0)) && lastArg.contains("HEARTBEAT_PRESENT=")) {
            return new SshRobotTransport.ProcessResult(0, heartbeatOutput.get(), "");
          }
          return new SshRobotTransport.ProcessResult(0, "OK\n", "");
        };

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    SshRobotTransport ssh =
        new SshRobotTransport(List.of("10.104.13.2"), false, lockout, false, runner);

    // Space-named PathPlanner files stream as one USTAR tar over ssh stdin (zero scp calls).
    ssh.replaceRemoteDirectoryAtomic(deployDir, "deploy");
    assertTrue(
        recorded.stream().noneMatch(c -> "scp".equals(c.argv().get(0))),
        "replaceRemoteDirectoryAtomic must stream tar over ssh instead of per-file scp");
    RecordedCall tarCall =
        recorded.stream()
            .filter(c -> c.stdin() != null && c.stdin().length > 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected tar stdin payload over ssh"));
    assertTrue(
        tarCall.argv().get(tarCall.argv().size() - 1).contains("tar -xf - -C"),
        "Expected tar -xf - -C staging command");
    Map<String, String> extractedTar = parseUstarTextEntries(tarCall.stdin());
    assertEquals(
        "{\"name\":\"Hub to Depot\"}",
        extractedTar.get("pathplanner/paths/Hub to Depot.path"),
        "USTAR tar contains Hub to Depot.path intact");
    assertEquals(
        "{\"name\":\"Left to Depot\"}",
        extractedTar.get("pathplanner/paths/Left to Depot.path"),
        "USTAR tar contains Left to Depot.path intact");
    assertEquals(
        "{\"name\":\"Right to Depot\"}",
        extractedTar.get("pathplanner/paths/Right to Depot.path"),
        "USTAR tar contains Right to Depot.path intact");
    RecordedCall swapCall = recorded.get(recorded.size() - 1);
    String swapCmd = swapCall.argv().get(swapCall.argv().size() - 1);
    assertTrue(
        swapCmd.contains("/tmp/flux-robot-state") && swapCmd.contains("mv "),
        "Atomic directory swap must inline /tmp/flux-robot-state guard inside the same ssh call");

    // downloadSelectedLogs must stream over ssh (zero scp calls) with POSIX-quoted space paths.
    recorded.clear();
    List<RobotTransport.RemoteLogEntry> spaceLogs =
        List.of(
            new RobotTransport.RemoteLogEntry(
                "/U/logs/Hub to Depot.wpilog", "usb_U/Hub to Depot.wpilog", 512L, remoteNowSec));
    ssh.downloadSelectedLogs(spaceLogs, temp.resolve("dl"));
    assertTrue(
        recorded.stream().noneMatch(c -> "scp".equals(c.argv().get(0))),
        "downloadSelectedLogs must stream over ssh instead of OpenSSH scp: " + recorded);
    RecordedCall catCall =
        recorded.stream()
            .filter(
                c ->
                    "ssh".equals(c.argv().get(0))
                        && c.argv().get(c.argv().size() - 1).contains("cat '/U/logs/Hub to Depot.wpilog'"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected ssh cat download call"));
    String catCmd = catCall.argv().get(catCall.argv().size() - 1);
    assertTrue(
        catCmd.contains("/tmp/flux-robot-state") && catCmd.contains("${_ep%???}"),
        "ssh download must inline BusyBox-safe /tmp/flux-robot-state guard: " + catCmd);

    // restartRobotCode must inline /tmp/flux-robot-state guard right before frcKillRobot.sh.
    recorded.clear();
    ssh.restartRobotCode();
    String restartCmd = recorded.get(recorded.size() - 1).argv().get(recorded.get(recorded.size() - 1).argv().size() - 1);
    assertTrue(
        restartCmd.contains("/tmp/flux-robot-state")
            && restartCmd.contains("/usr/local/frc/bin/frcKillRobot.sh -t -r"),
        "restartRobotCode must inline /tmp/flux-robot-state guard before frcKillRobot.sh");

    // Fail-closed heartbeat checks: ENABLED, FMS, STALE, CORRUPT, and ABSENT.
    heartbeatOutput.set(
        "REMOTE_EPOCH_SEC="
            + remoteNowSec
            + "\nHEARTBEAT_PRESENT=1\nSTATE=ENABLED\nENABLED=true\nFMS=false\nEPOCH_MS="
            + (remoteNowSec * 1_000L)
            + "\nROBOT_ID=FUEL\nPID=4321\n");
    assertThrowsIllegalState(
        () -> ssh.listRemoteLogEntries(), "ENABLED heartbeat must block listRemoteLogEntries");
    // Reset local lockout back to disabled before testing remaining robot-side heartbeat states.
    lockout.updateState(false, false, System.currentTimeMillis());

    heartbeatOutput.set(
        "REMOTE_EPOCH_SEC="
            + remoteNowSec
            + "\nHEARTBEAT_PRESENT=1\nSTATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
            + (remoteNowSec * 1_000L - 10_000L)
            + "\nROBOT_ID=FUEL\nPID=4321\n");
    assertThrowsIllegalState(
        () -> ssh.captureSystemSnapshotRaw(), "Stale (>5s) heartbeat must block captureSystemSnapshotRaw");

    heartbeatOutput.set(
        "REMOTE_EPOCH_SEC=" + remoteNowSec + "\nHEARTBEAT_PRESENT=1\nCORRUPT_GARBAGE_LINE\n");
    assertThrowsIllegalState(
        () -> ssh.assertRobotDisabledForTransfer("corruptTest"),
        "Corrupt heartbeat must fail closed");

    heartbeatOutput.set("REMOTE_EPOCH_SEC=" + remoteNowSec + "\nHEARTBEAT_PRESENT=0\n");
    assertThrowsIllegalState(
        () -> ssh.assertRobotDisabledForTransfer("absentTest"),
        "Absent heartbeat must fail closed by default");

    // Explicit bootstrap opt-in allows ONLY absent heartbeat, never corrupt heartbeat.
    SshRobotTransport bootstrapSsh =
        new SshRobotTransport(List.of("10.104.13.2"), false, lockout, true, runner);
    bootstrapSsh.assertRobotDisabledForTransfer("bootstrapAbsentAllowed");
    heartbeatOutput.set(
        "REMOTE_EPOCH_SEC=" + remoteNowSec + "\nHEARTBEAT_PRESENT=1\nENABLED=notabool\n");
    assertThrowsIllegalState(
        () -> bootstrapSsh.assertRobotDisabledForTransfer("bootstrapCorruptBlocked"),
        "Corrupt heartbeat must stay fail-closed even with --allow-missing-heartbeat");

    // PackageDeployer must roll back the live JAR from prev_1.jar if replaceRemoteDirectoryAtomic fails.
    MockRobotServer mockRio = new MockRobotServer(temp.resolve("rollback-rio"), "roborio-2026");
    mockRio.setRobotIdentityFile("FUEL");
    Path cfgValid = createSampleFluxFigConfigs(temp.resolve("rb-cfg"), "Drivetrain", 60.0);
    Path jarV1 = temp.resolve("v1.jar");
    Path jarV2 = temp.resolve("v2.jar");
    Files.writeString(jarV1, "LIVE_STABLE_JAR_V1", StandardCharsets.UTF_8);
    Files.writeString(jarV2, "NEW_BROKEN_DEPLOY_JAR_V2", StandardCharsets.UTF_8);
    Path pkgV1 =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("v1_roborio-2026_fluxpkg.zip"),
            new PackageManifest(
                "STABLE_v1", "roborio-2026", "STABLE", "1111111", false, "cfg11111", "boris"),
            jarV1,
            cfgValid);
    Path pkgV2 =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("v2_roborio-2026_fluxpkg.zip"),
            new PackageManifest(
                "EXP_v2", "roborio-2026", "EXPERIMENTAL", "2222222", true, "cfg22222", "boris"),
            jarV2,
            cfgValid);
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");
    deployer.deployPackage(pkgV1, mockRio, true);

    RobotTransport failingDeployTransport =
        new DelegatingRobotTransport(mockRio) {
          @Override
          public void replaceRemoteDirectoryAtomic(Path localDir, String remoteRelativeDir)
              throws IOException {
            throw new IOException("Simulated deploy/ upload failure");
          }
        };
    boolean deployFailed = false;
    try {
      deployer.deployPackage(pkgV2, failingDeployTransport, true);
    } catch (IOException expected) {
      deployFailed = true;
    }
    assertTrue(deployFailed, "deployPackage must propagate deploy/ failure");
    assertEquals(
        "LIVE_STABLE_JAR_V1",
        mockRio.readRemoteText("Flux-RoboRIO-2027.jar"),
        "Live JAR must roll back to prev_1.jar when replaceRemoteDirectoryAtomic fails");
  }

  private static void testSshBinaryStreamingAndBusyBoxHeartbeatGuardViaRealShell()
      throws Exception {
    Path temp = createTempDir("fluxtray-real-sh-stream-");
    Path remoteRoot = temp.resolve("remote-root");
    Files.createDirectories(remoteRoot);
    Path hbFile = temp.resolve("flux-robot-state");

    long nowMs = System.currentTimeMillis();
    Files.writeString(
        hbFile,
        "STATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
            + nowMs
            + "\nROBOT_ID=FUEL\nPID=1234\n",
        StandardCharsets.UTF_8);

    List<List<String>> executedArgvs = new ArrayList<>();
    SshRobotTransport.ProcessRunner realShellRunner =
        (argv, stdin, timeout) -> {
          executedArgvs.add(List.copyOf(argv));
          assertEquals("ssh", argv.get(0), "SshRobotTransport must never invoke scp");
          String remoteCmd = argv.get(argv.size() - 1);
          Process p = new ProcessBuilder("/bin/sh", "-c", remoteCmd).start();
          ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
          ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
          Thread outReader =
              new Thread(
                  () -> {
                    try {
                      p.getInputStream().transferTo(outBuf);
                    } catch (IOException ignored) {
                    }
                  });
          Thread errReader =
              new Thread(
                  () -> {
                    try {
                      p.getErrorStream().transferTo(errBuf);
                    } catch (IOException ignored) {
                    }
                  });
          outReader.start();
          errReader.start();
          try (OutputStream procIn = p.getOutputStream()) {
            if (stdin != null && stdin.length > 0) {
              procIn.write(stdin);
              procIn.flush();
            }
          }
          try {
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "Local /bin/sh command must complete");
            outReader.join(2_000L);
            errReader.join(2_000L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for /bin/sh", e);
          }
          byte[] rawOut = outBuf.toByteArray();
          return new SshRobotTransport.ProcessResult(
              p.exitValue(),
              new String(rawOut, StandardCharsets.UTF_8),
              errBuf.toString(StandardCharsets.UTF_8),
              rawOut);
        };

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    SshRobotTransport ssh =
        new SshRobotTransport(
            List.of("127.0.0.1"), false, lockout, false, hbFile.toString(), realShellRunner);

    String guard = ssh.buildInlineHeartbeatGuard();
    assertTrue(!guard.contains("_ep / 1000"), "Inline guard must not use 41-bit division");
    assertTrue(guard.contains("${_ep%???}"), "Inline guard must strip ms with POSIX expansion");

    // Binary payload with all 256 byte values + invalid UTF-8 sequences to prove lossless streaming.
    byte[] binaryPayload = new byte[1024];
    for (int i = 0; i < binaryPayload.length; i++) {
      binaryPayload[i] = (byte) (i & 0xFF);
    }
    Path localJar = temp.resolve("Flux-RoboRIO-2027.jar");
    Files.write(localJar, binaryPayload);

    // Atomic single-file upload to a path containing spaces and a single quote.
    Path remoteJar = remoteRoot.resolve("Hub to Depot's/Flux-RoboRIO-2027.jar");
    ssh.uploadFileAtomic(localJar, remoteJar.toAbsolutePath().toString());
    assertTrue(Files.isRegularFile(remoteJar), "Uploaded binary file exists at quoted remote path");
    assertTrue(
        java.util.Arrays.equals(binaryPayload, Files.readAllBytes(remoteJar)),
        "Uploaded binary bytes match input byte-for-byte");

    // Binary single-file download from a path containing spaces and a single quote + mtime.
    Path remoteLog = remoteRoot.resolve("U logs/Hub to Depot's 2026.wpilog");
    Files.createDirectories(remoteLog.getParent());
    Files.write(remoteLog, binaryPayload);
    long expectedMtimeSec = 1_763_650_123L;
    Path dlDir = temp.resolve("downloaded");
    Map<String, Path> downloaded =
        ssh.downloadSelectedLogs(
            List.of(
                new RobotTransport.RemoteLogEntry(
                    remoteLog.toAbsolutePath().toString(),
                    "usb_U/Hub to Depot's 2026.wpilog",
                    binaryPayload.length,
                    expectedMtimeSec)),
            dlDir);
    Path dlFile = downloaded.get("usb_U/Hub to Depot's 2026.wpilog");
    assertTrue(dlFile != null && Files.isRegularFile(dlFile), "Downloaded log exists locally");
    assertTrue(
        java.util.Arrays.equals(binaryPayload, Files.readAllBytes(dlFile)),
        "Downloaded binary .wpilog bytes match source byte-for-byte without UTF-8 corruption");
    assertEquals(
        expectedMtimeSec,
        Files.getLastModifiedTime(dlFile).toInstant().getEpochSecond(),
        "Downloaded log preserves remote mtime");
    assertTrue(
        executedArgvs.stream().noneMatch(a -> "scp".equals(a.get(0))),
        "Zero scp invocations during upload and download");

    // Real /bin/sh execution of buildInlineHeartbeatGuard: ENABLED, STALE, and CORRUPT fail closed.
    Files.writeString(
        hbFile,
        "STATE=ENABLED\nENABLED=true\nFMS=false\nEPOCH_MS=" + System.currentTimeMillis() + "\n",
        StandardCharsets.UTF_8);
    SshRobotTransport.ProcessResult enabledRes =
        realShellRunner.run(List.of("ssh", "lvuser@127.0.0.1", guard), null, java.time.Duration.ofSeconds(5));
    assertEquals(
        SshRobotTransport.LOCKOUT_EXIT_CODE,
        enabledRes.exitCode(),
        "Inline guard must exit 42 on ENABLED");

    Files.writeString(
        hbFile,
        "STATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
            + (System.currentTimeMillis() - 12_000L)
            + "\n",
        StandardCharsets.UTF_8);
    SshRobotTransport.ProcessResult staleRes =
        realShellRunner.run(List.of("ssh", "lvuser@127.0.0.1", guard), null, java.time.Duration.ofSeconds(5));
    assertEquals(
        SshRobotTransport.LOCKOUT_EXIT_CODE,
        staleRes.exitCode(),
        "Inline guard must exit 42 on stale (>5s) heartbeat");

    Files.writeString(
        hbFile,
        "STATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS=01795000000123\n",
        StandardCharsets.UTF_8);
    SshRobotTransport.ProcessResult leadingZeroRes =
        realShellRunner.run(List.of("ssh", "lvuser@127.0.0.1", guard), null, java.time.Duration.ofSeconds(5));
    assertEquals(
        SshRobotTransport.LOCKOUT_EXIT_CODE,
        leadingZeroRes.exitCode(),
        "Inline guard must exit 42 on leading-zero corrupt EPOCH_MS");

    // Fresh 41-bit EPOCH_MS passes real /bin/sh guard with zero 41-bit math.
    Files.writeString(
        hbFile,
        "STATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS=" + System.currentTimeMillis() + "\n",
        StandardCharsets.UTF_8);
    SshRobotTransport.ProcessResult okRes =
        realShellRunner.run(List.of("ssh", "lvuser@127.0.0.1", guard), null, java.time.Duration.ofSeconds(5));
    assertEquals(0, okRes.exitCode(), "Fresh 41-bit EPOCH_MS must pass real /bin/sh guard");

    // Verify restartRobotCode redirects stdio and replaceRemoteDirectoryAtomic inlines the heartbeat guard.
    List<String> capturedCmds = new ArrayList<>();
    SshRobotTransport.ProcessRunner captureRunner =
        (argv, stdin, timeout) -> {
          String cmd = argv.get(argv.size() - 1);
          capturedCmds.add(cmd);
          if (cmd.contains("HEARTBEAT_PRESENT")) {
            long sec = System.currentTimeMillis() / 1000L;
            String out =
                "REMOTE_EPOCH_SEC="
                    + sec
                    + "\nHEARTBEAT_PRESENT=1\nSTATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
                    + (sec * 1000L)
                    + "\nROBOT_ID=FUEL\nPID=123\n";
            return new SshRobotTransport.ProcessResult(0, out, "");
          }
          return new SshRobotTransport.ProcessResult(0, "", "");
        };
    SshRobotTransport sshVerify =
        new SshRobotTransport(List.of("127.0.0.1"), false, lockout, false, hbFile.toString(), captureRunner);
    sshVerify.restartRobotCode();
    assertTrue(
        capturedCmds.stream()
            .anyMatch(c -> c.contains("frcKillRobot.sh -t -r </dev/null >/dev/null 2>&1")),
        "restartRobotCode must redirect stdio so SSH never hangs on child FDs");

    Path localDeployDir = temp.resolve("deploy-src");
    Files.createDirectories(localDeployDir);
    Files.writeString(localDeployDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
    capturedCmds.clear();
    sshVerify.replaceRemoteDirectoryAtomic(localDeployDir, "deploy");
    assertTrue(
        capturedCmds.stream()
            .anyMatch(c -> c.contains("${_ep%???}") && c.contains("tar -xf - -C")),
        "replaceRemoteDirectoryAtomic must inline BusyBox heartbeat guard before tar staging");
  }

  private static void testGitHubReleaseUpdaterIntegritySafetyLockoutAndRealPosixSwap()
      throws Exception {
    assertTrue(
        GitHubReleaseUpdater.isNewerVersion("2027.1.0", "v2027.2.0-ct-handoff"),
        "Higher numeric version with tag suffix must be detected as newer");
    assertTrue(
        !GitHubReleaseUpdater.isNewerVersion("2027.2.0-ct-handoff", "v2027.2.0-ct-handoff"),
        "Identical suffixed version must not trigger update loop");
    assertTrue(
        GitHubReleaseUpdater.isNewerVersion("2027.2.0-ct-handoff", "v2027.2.0"),
        "Clean release without suffix must be newer than same-core suffixed build");
    assertTrue(
        !GitHubReleaseUpdater.isNewerVersion("2027.2.1", "v2027.2.0"),
        "Older release must never downgrade current version");

    Path temp = createTempDir("fluxtray-updater-");
    Path oldJar = temp.resolve("FluxTray.jar");
    Files.writeString(oldJar, "OLD_JAR_BYTES", StandardCharsets.UTF_8);

    // Build a valid minimal JAR containing META-INF/MANIFEST.MF and FluxTrayMain.class.
    Path validNewJar = temp.resolve("release-FluxTray.jar");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(validNewJar))) {
      zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      zos.write("Manifest-Version: 1.0\nImplementation-Version: 2027.2.0\n".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.putNextEntry(new ZipEntry("frc/flux/tray/FluxTrayMain.class"));
      zos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
      zos.closeEntry();
    }
    byte[] validJarBytes = Files.readAllBytes(validNewJar);
    String validSha256 = GitHubReleaseUpdater.sha256Hex(validJarBytes);

    // Start a real loopback HTTP server mimicking GitHub Releases API + asset downloads.
    AtomicReference<String> releaseJsonRef = new AtomicReference<>("");
    AtomicReference<String> shaBodyRef = new AtomicReference<>(validSha256 + "  FluxTray.jar\n");
    AtomicReference<byte[]> jarBodyRef = new AtomicReference<>(validJarBytes);
    try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
      int port = server.getLocalPort();
      String baseUrl = "http://127.0.0.1:" + port;
      AtomicBoolean serverRunning = new AtomicBoolean(true);
      Thread httpThread =
          new Thread(
              () -> {
                while (serverRunning.get()) {
                  try (Socket client = server.accept()) {
                    BufferedReader br =
                        new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String reqLine = br.readLine();
                    if (reqLine == null) {
                      continue;
                    }
                    String line;
                    while ((line = br.readLine()) != null && !line.isEmpty()) {
                      // drain headers
                    }
                    byte[] body;
                    int status = 200;
                    if (reqLine.contains("/releases/latest")) {
                      body = releaseJsonRef.get().getBytes(StandardCharsets.UTF_8);
                    } else if (reqLine.contains("/assets/FluxTray.jar.sha256")) {
                      body = shaBodyRef.get().getBytes(StandardCharsets.UTF_8);
                    } else if (reqLine.contains("/assets/FluxTray.jar")) {
                      body = jarBodyRef.get();
                    } else {
                      status = 404;
                      body = "not found".getBytes(StandardCharsets.UTF_8);
                    }
                    OutputStream out = client.getOutputStream();
                    String hdr =
                        "HTTP/1.1 "
                            + status
                            + " OK\r\nContent-Length: "
                            + body.length
                            + "\r\nConnection: close\r\n\r\n";
                    out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                  } catch (IOException ignored) {
                  }
                }
              },
              "fluxtray-test-gh-http");
      httpThread.setDaemon(true);
      httpThread.start();

      try {
        // 1) Refuse missing FluxTray.jar.sha256 asset (fail closed).
        releaseJsonRef.set(
            "{\"tag_name\":\"v2027.2.0\",\"draft\":false,\"prerelease\":false,\"assets\":["
                + "{\"name\":\"FluxTray.jar\",\"browser_download_url\":\""
                + baseUrl
                + "/assets/FluxTray.jar\"}]}");
        GitHubReleaseUpdater missingShaUpdater =
            new GitHubReleaseUpdater(
                "FRC-Flux-Robotics/FluxTray",
                baseUrl,
                "2027.1.0",
                null,
                null,
                (javaExe, staged) -> true,
                (pid, tgt, stg, bak, javaExe, args) -> {});
        boolean missingShaFailed = false;
        try {
          missingShaUpdater.checkAndStageUpdate(oldJar, List.of("--tray"), false, () -> true);
        } catch (IOException expected) {
          missingShaFailed = expected.getMessage().contains("missing");
        }
        assertTrue(missingShaFailed, "Updater must refuse release missing FluxTray.jar.sha256");

        // 2) Refuse SHA-256 mismatch and clean up staged .new file.
        releaseJsonRef.set(
            "{\"tag_name\":\"v2027.2.0\",\"draft\":false,\"prerelease\":false,\"assets\":["
                + "{\"name\":\"FluxTray.jar\",\"browser_download_url\":\""
                + baseUrl
                + "/assets/FluxTray.jar\"},"
                + "{\"name\":\"FluxTray.jar.sha256\",\"browser_download_url\":\""
                + baseUrl
                + "/assets/FluxTray.jar.sha256\"}]}");
        shaBodyRef.set("0".repeat(64) + "  FluxTray.jar\n");
        GitHubReleaseUpdater badShaUpdater =
            new GitHubReleaseUpdater(
                "FRC-Flux-Robotics/FluxTray",
                baseUrl,
                "2027.1.0",
                null,
                null,
                (javaExe, staged) -> true,
                (pid, tgt, stg, bak, javaExe, args) -> {});
        boolean shaMismatchFailed = false;
        try {
          badShaUpdater.checkAndStageUpdate(oldJar, List.of("--tray"), false, () -> true);
        } catch (IOException expected) {
          shaMismatchFailed = expected.getMessage().contains("SHA-256 mismatch");
        }
        assertTrue(shaMismatchFailed, "Updater must fail closed on SHA-256 mismatch");
        assertTrue(
            !Files.exists(temp.resolve("FluxTray.jar.new")),
            "Staged .new JAR must be deleted on verification failure");
        assertEquals(
            "v2027.2.0",
            badShaUpdater.lastFailedTag(),
            "Failed tag must be latched to avoid repeated downloads");

        // 3) Refuse update when --self-test fails on the staged JAR.
        shaBodyRef.set(validSha256 + "  FluxTray.jar\n");
        GitHubReleaseUpdater badSelfTestUpdater =
            new GitHubReleaseUpdater(
                "FRC-Flux-Robotics/FluxTray",
                baseUrl,
                "2027.1.0",
                null,
                null,
                (javaExe, staged) -> false,
                (pid, tgt, stg, bak, javaExe, args) -> {});
        boolean selfTestFailed = false;
        try {
          badSelfTestUpdater.checkAndStageUpdate(oldJar, List.of("--tray"), false, () -> true);
        } catch (IOException expected) {
          selfTestFailed = expected.getMessage().contains("--self-test");
        }
        assertTrue(selfTestFailed, "Updater must abort if staged JAR fails --self-test");

        // 4) TrayActionCoordinator blocks update checks while robot is Enabled or on FMS.
        RobotSafetyLockout lockout = new RobotSafetyLockout();
        lockout.updateState(true, false, System.currentTimeMillis());
        MockRobotServer mockRio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
        MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
        BatteryAndRunManager runMgr =
            new BatteryAndRunManager(temp.resolve("hub"), lockout, audio);
        AtomicBoolean restarted = new AtomicBoolean(false);
        try (FluxTrayMain.TrayActionCoordinator coord =
            new FluxTrayMain.TrayActionCoordinator(
                mockRio,
                new PackageDeployer(lockout, "test"),
                runMgr,
                audio,
                lockout,
                temp.resolve("ds"),
                temp.resolve("logs"),
                temp.resolve("tray.log"),
                (lvl, title, msg) -> {},
                busy -> {})) {
          GitHubReleaseUpdater liveUpdater =
              new GitHubReleaseUpdater(
                  "FRC-Flux-Robotics/FluxTray",
                  baseUrl,
                  "2027.1.0",
                  null,
                  null,
                  (javaExe, staged) -> true,
                  (pid, tgt, stg, bak, javaExe, args) -> {});
          Future<?> blockedFuture =
              coord.submitUpdateCheckIfIdle(
                  liveUpdater, oldJar, List.of("--tray"), false, () -> restarted.set(true));
          assertTrue(blockedFuture == null, "Update check must be blocked while robot is Enabled");
          assertTrue(!restarted.get(), "Restart callback must never fire while Enabled");

          // 5) Happy path: Disabled + off-FMS downloads, verifies SHA-256, backs up, and runs real POSIX swap script.
          lockout.updateState(false, false, System.currentTimeMillis());
          Path relaunchMarker = temp.resolve("relaunched.txt");
          Path fakeJava = temp.resolve("fake-java.sh");
          Files.writeString(
              fakeJava,
              "#!/bin/sh\nprintf '%s\\n' \"$@\" > "
                  + SshRobotTransport.shQuote(relaunchMarker.toString())
                  + "\n",
              StandardCharsets.UTF_8);
          fakeJava.toFile().setExecutable(true);

          GitHubReleaseUpdater swapUpdater =
              new GitHubReleaseUpdater(
                  "FRC-Flux-Robotics/FluxTray",
                  baseUrl,
                  "2027.1.0",
                  null,
                  null,
                  (javaExe, staged) -> true,
                  (pid, tgt, stg, bak, javaExe, args) -> {
                    // Use non-existent PID 99999999 so the real /bin/sh swap script executes immediately.
                    String posixScript =
                        GitHubReleaseUpdater.buildPosixSwapScript(
                            99_999_999L, tgt, stg, bak, fakeJava, args);
                    Path sh = temp.resolve("swap-test.sh");
                    Files.writeString(sh, posixScript, StandardCharsets.UTF_8);
                    Process p = new ProcessBuilder("/bin/sh", sh.toString()).start();
                    try {
                      assertTrue(p.waitFor(10, TimeUnit.SECONDS), "POSIX swap script must exit cleanly");
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IOException(e);
                    }
                  });
          Future<?> okFuture =
              coord.submitUpdateCheckIfIdle(
                  swapUpdater,
                  oldJar,
                  List.of("--tray", "--code-dir", "G:\\My Drive\\FLUX-10413-Hub\\code"),
                  false,
                  () -> restarted.set(true));
          assertTrue(okFuture != null, "Idle Disabled robot must allow update check");
          okFuture.get(15, TimeUnit.SECONDS);
          assertTrue(restarted.get(), "Verified update must invoke restart callback");
          assertTrue(
              java.util.Arrays.equals(validJarBytes, Files.readAllBytes(oldJar)),
              "Real POSIX swap script must replace FluxTray.jar with verified release bytes");
          assertEquals(
              "OLD_JAR_BYTES",
              Files.readString(temp.resolve("FluxTray.jar.bak"), StandardCharsets.UTF_8),
              "Previous FluxTray.jar must be preserved in FluxTray.jar.bak");

          // Wait briefly for detached background fake-java.sh to flush relaunched args.
          for (int i = 0; i < 40 && !Files.isRegularFile(relaunchMarker); i++) {
            Thread.sleep(50L);
          }
          assertTrue(Files.isRegularFile(relaunchMarker), "Swap script must relaunch FluxTray");
          String relaunchArgsText = Files.readString(relaunchMarker, StandardCharsets.UTF_8);
          assertTrue(
              relaunchArgsText.contains("--tray")
                  && relaunchArgsText.contains("G:\\My Drive\\FLUX-10413-Hub\\code"),
              "Relaunched FluxTray must preserve original CLI arguments");
        }

        // 6) Verify Windows .cmd swap script uses PS/non-interactive-safe ping wait, move /Y, and backup rollback.
        String winCmd =
            GitHubReleaseUpdater.buildWindowsSwapScript(
                4321L,
                Path.of("C:\\Flux10413\\FluxTray.jar"),
                Path.of("C:\\Flux10413\\FluxTray.jar.new"),
                Path.of("C:\\Flux10413\\FluxTray.jar.bak"),
                Path.of("C:\\Program Files\\Eclipse Adoptium\\jdk-17\\bin\\javaw.exe"),
                List.of("--tray", "--code-dir", "G:\\My Drive\\FLUX-10413-Hub\\code"));
        assertTrue(winCmd.contains("ping -n 2 127.0.0.1"), "Windows script must use ping wait (not timeout)");
        assertTrue(winCmd.contains("move /Y \"%STAGED%\" \"%TARGET%\""), "Windows script must move staged JAR");
        assertTrue(winCmd.contains("copy /Y \"%BACKUP%\" \"%TARGET%\""), "Windows script must roll back from .bak on failure");
        assertTrue(winCmd.contains("javaw.exe"), "Windows script must relaunch javaw.exe");
      } finally {
        serverRunning.set(false);
      }
    }
  }

  private static void testNt4IdleTimeoutReconnectsAndDisconnectClearsDisplayedStatus()
      throws Exception {
    assertEquals(
        FluxTrayMain.TrayStatus.DISCONNECTED,
        FluxTrayMain.computeTrayStatus(false, true, false, false),
        "Disconnected NT4 must show Gray DISCONNECTED (never stuck Red ENABLED)");
    assertEquals(
        FluxTrayMain.TrayStatus.DISCONNECTED,
        FluxTrayMain.computeTrayStatus(false, false, true, false),
        "Disconnected NT4 must show Gray DISCONNECTED (never stuck Red FMS)");
    assertEquals(
        FluxTrayMain.TrayStatus.ENABLED,
        FluxTrayMain.computeTrayStatus(true, true, false, false),
        "Connected Enabled robot shows Red ENABLED");
    assertEquals(
        FluxTrayMain.TrayStatus.READY,
        FluxTrayMain.computeTrayStatus(true, false, false, false),
        "Connected Disabled idle robot shows Green READY");

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    AtomicInteger acceptedConnections = new AtomicInteger(0);
    CountDownLatch firstConnected = new CountDownLatch(1);
    CountDownLatch disconnectedAfterSilence = new CountDownLatch(1);
    CountDownLatch secondDisabledReceived = new CountDownLatch(1);

    try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
      int port = server.getLocalPort();
      AtomicBoolean serverStop = new AtomicBoolean(false);
      Thread serverThread =
          new Thread(
              () -> {
                while (!serverStop.get()) {
                  try {
                    Socket client = server.accept();
                    int connNum = acceptedConnections.incrementAndGet();
                    Thread handler =
                        new Thread(
                            () -> {
                              try (Socket s = client) {
                                performWsServerHandshake(s);
                                OutputStream out = s.getOutputStream();
                                String announce =
                                    "[{\"method\":\"announce\",\"params\":{\"name\":\"/FMSInfo/FMSControlData\",\"id\":7,\"type\":\"int\",\"properties\":{}}}]";
                                sendWsFrame(out, 0x01, announce.getBytes(StandardCharsets.UTF_8));
                                if (connNum == 1) {
                                  sendWsFrame(out, 0x02, encodeNt4IntFrame(7, 1_000_000L, 0x21));
                                  // Go completely silent: ignore pings and never send pong.
                                  Thread.sleep(3_000L);
                                } else {
                                  sendWsFrame(out, 0x02, encodeNt4IntFrame(7, 2_000_000L, 0x20));
                                  secondDisabledReceived.await(3, TimeUnit.SECONDS);
                                }
                              } catch (Exception ignored) {
                              }
                            },
                            "silent-nt4-conn-" + connNum);
                    handler.setDaemon(true);
                    handler.start();
                  } catch (IOException ignored) {
                    break;
                  }
                }
              },
              "silent-nt4-server");
      serverThread.setDaemon(true);
      serverThread.start();

      try (Nt4RobotStateClient ntClient =
          new Nt4RobotStateClient(
              List.of("127.0.0.1"),
              port,
              lockout,
              (enabled, fms, word, host, ts) -> {
                if (enabled && word == 0x21) {
                  firstConnected.countDown();
                } else if (!enabled && word == 0x20 && acceptedConnections.get() >= 2) {
                  secondDisabledReceived.countDown();
                }
              },
              System::currentTimeMillis,
              100L,
              300L,
              50L)) {
        AtomicBoolean sawConn = new AtomicBoolean(false);
        ntClient.setConnectionChangeListener(
            () -> {
              if (ntClient.isConnected()) {
                sawConn.set(true);
              } else if (sawConn.get()) {
                disconnectedAfterSilence.countDown();
              }
            });
        ntClient.start();

        assertTrue(firstConnected.await(4, TimeUnit.SECONDS), "Initial NT4 connection received Enabled");
        assertTrue(
            disconnectedAfterSilence.await(4, TimeUnit.SECONDS),
            "Silent half-open NT4 socket must abort after idle timeout");
        assertTrue(
            secondDisabledReceived.await(4, TimeUnit.SECONDS),
            "NT4 client must reconnect cleanly after idle timeout and receive Disabled");
        assertTrue(
            acceptedConnections.get() >= 2,
            "Expected >= 2 connections after silent socket abort, got " + acceptedConnections.get());
      } finally {
        serverStop.set(true);
      }
    }
  }

  private static void testUtcRobotLogNamesAndLocalDsLogWindowWithMtimeGuard() throws IOException {
    Path temp = createTempDir("fluxtray-tz-window-");
    // 18:00:00 UTC on 2026-11-20 equals 13:00:00 EST in America/New_York (UTC-5).
    ZoneId nyZone = ZoneId.of("America/New_York");
    long utcTargetMs = Instant.parse("2026-11-20T18:00:00Z").toEpochMilli();
    assertEquals(
        utcTargetMs,
        BatteryAndRunManager.parseRobotLogNameEpochMs("akit_26-11-20_18-00-00.wpilog"),
        "AdvantageKit robot log filename must parse in UTC regardless of laptop timezone");
    assertEquals(
        utcTargetMs,
        BatteryAndRunManager.parseDsLogNameEpochMs("2026_11_20_13_00_00.dslog", nyZone),
        "NI Driver Station .dslog filename must parse in local timezone");

    // Case A: Test with JVM default timezone (fails under America/New_York if robot names parsed locally).
    Path runDirDefaultTz = temp.resolve("run-default-tz");
    Path dsDirDefaultTz = temp.resolve("ds-default-tz");
    Files.createDirectories(runDirDefaultTz);
    Files.createDirectories(dsDirDefaultTz);
    Path robotWpilog = runDirDefaultTz.resolve("akit_26-11-20_18-00-00.wpilog");
    Files.writeString(robotWpilog, "WPILOG", StandardCharsets.UTF_8);
    // Give files 2024 mtime so matching depends strictly on parsed filename timestamps.
    FileTime stale2024 = FileTime.from(Instant.parse("2024-12-18T14:08:00Z"));
    Files.setLastModifiedTime(robotWpilog, stale2024);

    DateTimeFormatter dsFmt = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss", Locale.US);
    LocalDateTime expectedLocal =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(utcTargetMs), ZoneId.systemDefault());
    Path matchingLocalDs = dsDirDefaultTz.resolve(expectedLocal.format(dsFmt) + ".dslog");
    Path shiftedFiveHoursDs =
        dsDirDefaultTz.resolve(expectedLocal.plusHours(5).format(dsFmt) + ".dslog");
    Files.writeString(matchingLocalDs, "MATCH_LOCAL", StandardCharsets.UTF_8);
    Files.writeString(shiftedFiveHoursDs, "SHIFTED_5H", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(matchingLocalDs, stale2024);
    Files.setLastModifiedTime(shiftedFiveHoursDs, stale2024);

    int copiedDefault =
        BatteryAndRunManager.copyWindowedLocalDsLogs(
            dsDirDefaultTz, runDirDefaultTz, 0L, 0L, 0L);
    assertEquals(1, copiedDefault, "Exactly 1 local .dslog matches UTC robot log under default TZ");
    assertTrue(
        Files.isRegularFile(runDirDefaultTz.resolve(matchingLocalDs.getFileName())),
        "Matching local-time .dslog copied");
    assertTrue(
        !Files.isRegularFile(runDirDefaultTz.resolve(shiftedFiveHoursDs.getFileName())),
        "5-hour-shifted .dslog excluded");

    // Explicit America/New_York zone check even when JVM runs in UTC.
    Path runDirNy = temp.resolve("run-ny");
    Path dsDirNy = temp.resolve("ds-ny");
    Files.createDirectories(runDirNy);
    Files.createDirectories(dsDirNy);
    Path nyRobotLog = runDirNy.resolve("akit_26-11-20_18-00-00.wpilog");
    Files.writeString(nyRobotLog, "WPILOG", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(nyRobotLog, stale2024);
    Path ny1300 = dsDirNy.resolve("2026_11_20_13_00_00.dslog");
    Path ny1800 = dsDirNy.resolve("2026_11_20_18_00_00.dslog");
    Files.writeString(ny1300, "EST_1300", StandardCharsets.UTF_8);
    Files.writeString(ny1800, "EST_1800_WRONG", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(ny1300, stale2024);
    Files.setLastModifiedTime(ny1800, stale2024);
    int copiedNy =
        BatteryAndRunManager.copyWindowedLocalDsLogs(dsDirNy, runDirNy, 0L, 0L, 0L, nyZone);
    assertEquals(1, copiedNy, "America/New_York matches 13:00 EST .dslog for 18:00 UTC .wpilog");
    assertTrue(Files.isRegularFile(runDirNy.resolve("2026_11_20_13_00_00.dslog")), "13:00 EST kept");
    assertTrue(!Files.isRegularFile(runDirNy.resolve("2026_11_20_18_00_00.dslog")), "18:00 EST excluded");

    // Case B: Unsynced 2024 roboRIO log & 2024 mtime must NOT sweep years of old .dslog files.
    Path runDir2024 = temp.resolve("run-unsynced-2024");
    Path dsDir2024 = temp.resolve("ds-unsynced-2024");
    Files.createDirectories(runDir2024);
    Files.createDirectories(dsDir2024);
    Path unsyncedLog = runDir2024.resolve("akit_24-12-18_14-08-00.wpilog");
    Files.writeString(unsyncedLog, "UNSYNCED_2024", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(unsyncedLog, stale2024);
    Path old2024Ds = dsDir2024.resolve("2024_12_18_09_08_00.dslog");
    Path old2025Ds = dsDir2024.resolve("2025_06_01_12_00_00.dslog");
    Path current2026Ds = dsDir2024.resolve("2026_11_20_13_00_00.dslog");
    Files.writeString(old2024Ds, "OLD2024", StandardCharsets.UTF_8);
    Files.writeString(old2025Ds, "OLD2025", StandardCharsets.UTF_8);
    Files.writeString(current2026Ds, "CUR2026", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(old2024Ds, stale2024);
    Files.setLastModifiedTime(old2025Ds, FileTime.from(Instant.parse("2025-06-01T16:00:00Z")));
    Files.setLastModifiedTime(current2026Ds, FileTime.fromMillis(utcTargetMs));

    int copiedFallback =
        BatteryAndRunManager.copyWindowedLocalDsLogs(
            dsDir2024, runDir2024, utcTargetMs, 0L, 0L, nyZone);
    assertEquals(1, copiedFallback, "Guarded mtime falls back to 2026 nowMs without sweeping 2024/2025");
    assertTrue(Files.isRegularFile(runDir2024.resolve("2026_11_20_13_00_00.dslog")), "Only 2026 .dslog copied");
    assertTrue(!Files.isRegularFile(runDir2024.resolve("2024_12_18_09_08_00.dslog")), "2024 .dslog excluded");
    assertTrue(!Files.isRegularFile(runDir2024.resolve("2025_06_01_12_00_00.dslog")), "2025 .dslog excluded");

    // Case C: Local audio timestamps take precedence over skewed robot log timestamps.
    long audioEnable = Instant.parse("2026-11-20T18:00:00Z").toEpochMilli();
    long audioDisable = audioEnable + 15_000L;
    long[] audioWindow =
        BatteryAndRunManager.resolveRunTimeWindowMs(
            runDir2024, Instant.parse("2026-11-21T10:00:00Z").toEpochMilli(), audioEnable, audioDisable);
    assertEquals(audioEnable, audioWindow[0], "Local audio enable timestamp preferred");
    assertEquals(audioDisable, audioWindow[1], "Local audio disable timestamp preferred");
  }

  private static void testTrayActionCoordinatorSerializesDrivePollsAndOnlySetsBusyOnTransfer()
      throws Exception {
    Path temp = createTempDir("fluxtray-coordinator-");
    Path codeDir = temp.resolve("drive/code");
    Path logsDir = temp.resolve("drive/logs");
    Path dsDir = temp.resolve("ds");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(codeDir);
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.seedSimulatedMatchLogs("2026-11-20_18-00-00", "FUEL", "11223344");

    CountDownLatch pullEnteredDownload = new CountDownLatch(1);
    CountDownLatch releasePullDownload = new CountDownLatch(1);
    List<String> timeline = new CopyOnWriteArrayList<>();

    RobotTransport blockingRobot =
        new DelegatingRobotTransport(rio) {
          private final AtomicBoolean blockedOnce = new AtomicBoolean(false);

          @Override
          public Map<String, Path> downloadSelectedLogs(
              List<RemoteLogEntry> entries, Path localDestinationDir) throws IOException {
            if (blockedOnce.compareAndSet(false, true)) {
              timeline.add("PULL_START");
              pullEnteredDownload.countDown();
              try {
                releasePullDownload.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              Map<String, Path> res = super.downloadSelectedLogs(entries, localDestinationDir);
              timeline.add("PULL_END");
              return res;
            }
            return super.downloadSelectedLogs(entries, localDestinationDir);
          }
        };

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            audio);
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");
    List<Boolean> busyTransitions = new CopyOnWriteArrayList<>();

    try (FluxTrayMain.TrayActionCoordinator coordinator =
        new FluxTrayMain.TrayActionCoordinator(
            blockingRobot,
            deployer,
            runMgr,
            audio,
            lockout,
            dsDir,
            logsDir,
            stateDir.resolve("fluxtray.log"),
            (lvl, title, msg) -> {},
            busyTransitions::add)) {

      DriveWatcher watcher =
          new DriveWatcher(
              codeDir,
              logsDir,
              dsDir,
              blockingRobot,
              deployer,
              runMgr,
              lockout,
              (lvl, title, msg) -> {},
              stateDir,
              DriveWatcher.DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS,
              coordinator::setBusySyncing);

      // Idle Drive poll must NEVER flip busySyncing to true (no Yellow strobe).
      Future<?> idlePoll =
          coordinator.submitDrivePollIfIdle(
              watcher,
              () -> {
                timeline.add("IDLE_POLL");
                return 1_763_650_000_000L;
              });
      idlePoll.get(5, TimeUnit.SECONDS);
      assertTrue(
          busyTransitions.isEmpty(),
          "Idle Drive poll must never set busySyncing/Yellow, got " + busyTransitions);

      // Submit manual pull; while it is blocked in downloadSelectedLogs, fire Drive polls.
      Future<?> pullFuture = coordinator.submitManualPull();
      assertTrue(
          pullEnteredDownload.await(5, TimeUnit.SECONDS), "Manual pull entered download phase");
      Future<?> queuedPoll =
          coordinator.submitDrivePollIfIdle(
              watcher,
              () -> {
                timeline.add("DRIVE_POLL");
                return 1_763_650_001_000L;
              });
      // A second poll while one is already queued must coalesce cleanly (returns null).
      Future<?> coalescedPoll =
          coordinator.submitDrivePollIfIdle(watcher, () -> 1_763_650_002_000L);
      assertEquals(null, coalescedPoll, "Duplicate queued Drive poll coalesced");

      // Wait briefly to prove DRIVE_POLL does NOT sneak in while PULL is in progress.
      Thread.sleep(150L);
      assertTrue(
          !timeline.contains("DRIVE_POLL"),
          "Drive poll must NOT execute concurrently between PULL_START and PULL_END: " + timeline);

      releasePullDownload.countDown();
      pullFuture.get(5, TimeUnit.SECONDS);
      queuedPoll.get(5, TimeUnit.SECONDS);

      int startIdx = timeline.indexOf("PULL_START");
      int endIdx = timeline.indexOf("PULL_END");
      int pollIdx = timeline.indexOf("DRIVE_POLL");
      assertTrue(
          startIdx >= 0 && endIdx > startIdx && pollIdx > endIdx,
          "Expected PULL_START -> PULL_END -> DRIVE_POLL strictly serialized, got " + timeline);

      // Actual Drive package deploy MUST turn busySyncing true during transfer and false after.
      busyTransitions.clear();
      Path cfg = createSampleFluxFigConfigs(temp.resolve("coord-cfg"), "Drivetrain", 60.0);
      Path jar = temp.resolve("coord.jar");
      Files.writeString(jar, "COORD_DEPLOY_JAR", StandardCharsets.UTF_8);
      PackageDeployer.createFluxPkgZip(
          codeDir.resolve("STABLE_coord_roborio-2026_fluxpkg.zip"),
          new PackageManifest(
              "STABLE_coord", "roborio-2026", "STABLE", "abc1234", false, "11223344", "boris"),
          jar,
          cfg);
      coordinator.submitDrivePollIfIdle(watcher, () -> 1_763_650_010_000L).get(5, TimeUnit.SECONDS);
      assertTrue(
          busyTransitions.isEmpty(), "Stabilizing poll 1 (no transfer) must not set busySyncing");
      coordinator.submitDrivePollIfIdle(watcher, () -> 1_763_650_011_000L).get(5, TimeUnit.SECONDS);
      assertEquals(
          List.of(Boolean.TRUE, Boolean.FALSE),
          busyTransitions,
          "Actual Drive deploy must set busySyncing=true during transfer and false afterward");
    }
  }

  private static void testAudioRecorderReEnableInsideDebriefClosesRun1AndStartsRun2()
      throws Exception {
    Path temp = createTempDir("fluxtray-reenable-debrief-");
    Path spoolRoot = temp.resolve("pending-audio");
    byte[] run1Pcm = generateSinePcm16kHz(0.20, 440.0);
    byte[] run2Pcm = generateSinePcm16kHz(0.25, 880.0);
    AtomicInteger openCount = new AtomicInteger(0);

    MultiMicAudioRecorder recorder =
        new MultiMicAudioRecorder(
            fmt -> {
              byte[] source = openCount.incrementAndGet() == 1 ? run1Pcm : run2Pcm;
              AtomicInteger off = new AtomicInteger(0);
              return new MultiMicAudioRecorder.PcmCapturePort() {
                @Override
                public int read(byte[] buf, int offset, int length) {
                  int cur = off.get();
                  if (cur >= source.length) {
                    return -1;
                  }
                  int n = Math.min(length, source.length - cur);
                  System.arraycopy(source, cur, buf, offset, n);
                  off.addAndGet(n);
                  return n;
                }

                @Override
                public void close() {}
              };
            });
    recorder.setSpoolRootDir(spoolRoot);

    long t1Enable = 1_763_650_000_000L;
    long t2Disable = t1Enable + 10_000L;
    long t3ReEnable = t2Disable + 4_000L; // Inside the 15 s debrief window!
    long t4Disable = t3ReEnable + 8_000L;

    recorder.onRobotStateChanged(true, t1Enable);
    Thread.sleep(60L);
    recorder.onRobotStateChanged(false, t2Disable);
    assertEquals(11_000L, recorder.remainingPostDisableMs(t3ReEnable), "Run 1 still inside debrief");

    // Re-enable inside 15 s debrief: Run 1 must flush to spool and Run 2 must start fresh.
    recorder.onRobotStateChanged(true, t3ReEnable);
    Thread.sleep(60L);

    Path run1Spool = spoolRoot.resolve(Long.toString(t1Enable));
    assertTrue(
        Files.isRegularFile(run1Spool.resolve(MultiMicAudioRecorder.AUDIO_FILE_NAME))
            && Files.size(run1Spool.resolve(MultiMicAudioRecorder.AUDIO_FILE_NAME)) > 44L,
        "Run 1 audio flushed safely to durable spool on re-enable");
    String run1Sync =
        Files.readString(
            run1Spool.resolve(MultiMicAudioRecorder.AUDIO_SYNC_FILE_NAME), StandardCharsets.UTF_8);
    assertTrue(
        run1Sync.contains("\"enableEpochMs\": " + t1Enable)
            && run1Sync.contains("\"disableEpochMs\": " + t2Disable),
        "Run 1 audio-sync.json preserves Run 1 enable/disable timestamps: " + run1Sync);

    assertTrue(recorder.isRecording(), "Run 2 actively recording");
    assertEquals(t3ReEnable, recorder.enableStartEpochMs(), "Run 2 has fresh enableEpochMs");
    assertEquals(0L, recorder.disableStartEpochMs(), "Run 2 has reset disableStartEpochMs=0");

    recorder.onRobotStateChanged(false, t4Disable);
    assertEquals(
        MultiMicAudioRecorder.POST_DISABLE_DEBRIEF_WINDOW_MS,
        recorder.remainingPostDisableMs(t4Disable),
        "Run 2 gets full 15 s debrief window");
    assertTrue(
        !recorder.isPostDisableWindowComplete(t4Disable + 5_000L),
        "Run 2 debrief not complete at +5s");
    assertTrue(
        recorder.isPostDisableWindowComplete(t4Disable + 15_000L),
        "Run 2 debrief completes at +15s");

    Path run2Out = temp.resolve("run2");
    Path run2Wav = recorder.flushAudioFile(run2Out);
    assertTrue(Files.size(run2Wav) > 44L, "Run 2 WAV has real PCM payload");
    String run2Sync =
        Files.readString(
            run2Out.resolve(MultiMicAudioRecorder.AUDIO_SYNC_FILE_NAME), StandardCharsets.UTF_8);
    assertTrue(
        run2Sync.contains("\"enableEpochMs\": " + t3ReEnable)
            && run2Sync.contains("\"disableEpochMs\": " + t4Disable),
        "Run 2 audio-sync.json contains exact Run 2 timestamps: " + run2Sync);
  }

  private static void testUsbLogRootDeduplicationAndUsbRunMetaHydration() throws Exception {
    Path temp = createTempDir("fluxtray-usb-dedup-");
    long nowSec = 1_763_650_000L;
    AtomicReference<String> probeCommandSeen = new AtomicReference<>("");
    SshRobotTransport.ProcessRunner runner =
        (argv, stdin, timeout) -> {
          String cmd = argv.get(argv.size() - 1);
          if ("ssh".equals(argv.get(0)) && cmd.contains("HEARTBEAT_PRESENT=")) {
            return new SshRobotTransport.ProcessResult(
                0,
                "REMOTE_EPOCH_SEC="
                    + nowSec
                    + "\nHEARTBEAT_PRESENT=1\nSTATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
                    + (nowSec * 1_000L)
                    + "\nROBOT_ID=CORAL\nPID=99\n",
                "");
          }
          if ("ssh".equals(argv.get(0)) && cmd.contains("readlink -f")) {
            probeCommandSeen.set(cmd);
            // Simulate /U/logs and /media/sda1 resolving to the same canonical USB files.
            String stdout =
                "/U/logs|/media/sda1/logs|/media/sda1/logs/akit_26-11-20_18-00-00.wpilog|2048|"
                    + nowSec
                    + "\n"
                    + "/U/logs|/media/sda1/logs|/media/sda1/logs/run-meta.json|220|"
                    + nowSec
                    + "\n"
                    + "/media/sda1|/media/sda1|/media/sda1/logs/akit_26-11-20_18-00-00.wpilog|2048|"
                    + nowSec
                    + "\n"
                    + "/media/sda1|/media/sda1|/media/sda1/logs/run-meta.json|220|"
                    + nowSec
                    + "\n";
            return new SshRobotTransport.ProcessResult(0, stdout, "");
          }
          return new SshRobotTransport.ProcessResult(0, "", "");
        };

    SshRobotTransport ssh =
        new SshRobotTransport(List.of("10.104.13.2"), false, new RobotSafetyLockout(), false, runner);
    List<RobotTransport.RemoteLogEntry> entries = ssh.listRemoteLogEntries();
    assertTrue(
        probeCommandSeen.get().contains("readlink -f"),
        "listRemoteLogEntries must canonicalize roots/files with readlink -f");
    assertEquals(2, entries.size(), "Duplicate /U/logs and /media/sda1 entries deduplicated to 2");
    assertEquals("usb_U/akit_26-11-20_18-00-00.wpilog", entries.get(0).relativeKey(), "1x USB wpilog");
    assertEquals("usb_U/run-meta.json", entries.get(1).relativeKey(), "1x USB run-meta.json");

    // Verify BatteryAndRunManager hydrates root run-meta.json from usb_U/run-meta.json.
    MockRobotServer baseRio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    RobotTransport usbOnlyTransport =
        new DelegatingRobotTransport(baseRio) {
          @Override
          public List<RemoteLogEntry> listRemoteLogEntries() {
            return entries;
          }

          @Override
          public Map<String, Path> downloadSelectedLogs(
              List<RemoteLogEntry> selected, Path localDestinationDir) throws IOException {
            Map<String, Path> out = new LinkedHashMap<>();
            Path usbDir = localDestinationDir.resolve("usb_U");
            Files.createDirectories(usbDir);
            Path wpilog = usbDir.resolve("akit_26-11-20_18-00-00.wpilog");
            Files.writeString(wpilog, "USB_WPILOG_BYTES", StandardCharsets.UTF_8);
            Path usbMeta = usbDir.resolve("run-meta.json");
            Files.writeString(
                usbMeta,
                "{\"robotId\":\"CORAL\",\"profile\":\"CORAL\",\"gitSha\":\"7777777\",\"configSha256\":\"deadbeef\",\"batteryName\":\"2026-B09\",\"loopPeriodMs\":20,\"bootTimestampIso\":\"2026-11-20T18:00:00Z\"}\n",
                StandardCharsets.UTF_8);
            out.put("usb_U/akit_26-11-20_18-00-00.wpilog", wpilog);
            out.put("usb_U/run-meta.json", usbMeta);
            return out;
          }
        };

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"), temp.resolve("state/synced.json"), lockout, audio);
    Path logsOut = temp.resolve("drive-logs");
    CompletedRunBundle bundle =
        runMgr.pullDriveSafeRunZip(
            "usb_run",
            null,
            usbOnlyTransport,
            temp.resolve("empty-ds"),
            logsOut,
            nowSec * 1_000L,
            false,
            true,
            false);
    assertTrue(
        bundle.zipArchive().getFileName().toString().endsWith("_CORAL.zip"),
        "ZIP filename hydrated robotId=CORAL from usb_U/run-meta.json: "
            + bundle.zipArchive().getFileName());
    String rootMeta = readZipEntryText(bundle.zipArchive(), "usb_run/run-meta.json");
    assertTrue(
        rootMeta.contains("\"robotId\": \"CORAL\"")
            && rootMeta.contains("\"gitSha\": \"7777777\"")
            && rootMeta.contains("\"configSha256\": \"deadbeef\"")
            && rootMeta.contains("\"batteryName\": \"2026-B09\""),
        "Root run-meta.json hydrated from usb_U/run-meta.json: " + rootMeta);
  }

  private static void testDriveWatcherStatePersistenceRejectedLatchPartialConfigsAndDebounce()
      throws Exception {
    Path temp = createTempDir("fluxtray-dw-persist-");
    Path codeDir = temp.resolve("drive/code");
    Path logsDir = temp.resolve("drive/logs");
    Path dsDir = temp.resolve("ds");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(codeDir);
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            audio);
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");

    Path cfg = createSampleFluxFigConfigs(temp.resolve("cfg-ok"), "Drivetrain", 60.0);
    Path jar = temp.resolve("ok.jar");
    Files.writeString(jar, "PERSIST_STABLE_JAR", StandardCharsets.UTF_8);
    Path validPkg =
        PackageDeployer.createFluxPkgZip(
            codeDir.resolve("STABLE_persist_roborio-2026_fluxpkg.zip"),
            new PackageManifest(
                "STABLE_persist", "roborio-2026", "STABLE", "aaa1111", false, "cfg11111", "boris"),
            jar,
            cfg);

    List<String> toasts1 = new ArrayList<>();
    DriveWatcher watcher1 =
        new DriveWatcher(
            codeDir,
            logsDir,
            dsDir,
            rio,
            deployer,
            runMgr,
            lockout,
            (lvl, title, msg) -> toasts1.add(lvl + ":" + title + ":" + msg),
            stateDir,
            DriveWatcher.DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS);

    long t = 1_763_650_000_000L;
    assertEquals(ActionType.NONE, watcher1.pollOnce(t).action(), "Poll 1 stabilizes");
    assertEquals(
        ActionType.DEPLOYED_PACKAGE, watcher1.pollOnce(t + 1_000L).action(), "Poll 2 deploys");
    assertEquals(1, rio.restartCount(), "1 restart after initial deploy");
    assertTrue(Files.isRegularFile(watcher1.stateFile()), "drive-watcher-state.json persisted");

    // Simulate laptop reboot: new DriveWatcher on same code/ and stateDir must NOT redeploy.
    List<String> toasts2 = new ArrayList<>();
    DriveWatcher watcher2 =
        new DriveWatcher(
            codeDir,
            logsDir,
            dsDir,
            rio,
            deployer,
            runMgr,
            lockout,
            (lvl, title, msg) -> toasts2.add(lvl + ":" + title + ":" + msg),
            stateDir,
            DriveWatcher.DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS);
    for (int i = 0; i < 4; i++) {
      assertEquals(
          ActionType.NONE,
          watcher2.pollOnce(t + 10_000L + i * 1_000L).action(),
          "New DriveWatcher after reboot must not redeploy same package");
    }
    assertEquals(1, rio.restartCount(), "Still 1 restart after reboot polls");

    // Corrupt ZIP fires ONE error toast and stays latched across polls and reboots.
    Path corruptPkg = codeDir.resolve("CORRUPT_roborio-2026_fluxpkg.zip");
    Files.writeString(corruptPkg, "NOT_A_VALID_ZIP_ARCHIVE", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(corruptPkg, FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
    assertEquals(ActionType.NONE, watcher2.pollOnce(t + 20_000L).action(), "Corrupt poll 1 stabilizes");
    assertEquals(
        ActionType.REJECTED_INVALID_PACKAGE,
        watcher2.pollOnce(t + 21_000L).action(),
        "Corrupt ZIP rejected once");
    assertEquals(1, toasts2.size(), "Corrupt ZIP emitted exactly 1 error toast");
    assertEquals(ActionType.NONE, watcher2.pollOnce(t + 22_000L).action(), "Corrupt ZIP latched poll 3");
    assertEquals(ActionType.NONE, watcher2.pollOnce(t + 23_000L).action(), "Corrupt ZIP latched poll 4");
    assertEquals(1, toasts2.size(), "No toast spam while corrupt ZIP remains in code/");

    List<String> toasts3 = new ArrayList<>();
    DriveWatcher watcher3 =
        new DriveWatcher(
            codeDir,
            logsDir,
            dsDir,
            rio,
            deployer,
            runMgr,
            lockout,
            (lvl, title, msg) -> toasts3.add(lvl + ":" + title + ":" + msg),
            stateDir,
            DriveWatcher.DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS);
    watcher3.pollOnce(t + 30_000L);
    watcher3.pollOnce(t + 31_000L);
    assertTrue(toasts3.isEmpty(), "Rejected corrupt ZIP hash remains latched across reboot");

    // Partial code/configs/ without parameters.csv emits ONE loud error toast and latches.
    Path partialConfigs = codeDir.resolve("configs");
    Files.createDirectories(partialConfigs);
    Files.writeString(
        partialConfigs.resolve("range_table.csv"),
        "distanceMeters,shooterRps,hoodRotations,timeOfFlightSec\n2.0,35.0,0.15,0.32\n",
        StandardCharsets.UTF_8);
    assertEquals(ActionType.NONE, watcher3.pollOnce(t + 40_000L).action(), "Partial cfg poll 1 stabilizes");
    PollOutcome partialOutcome = watcher3.pollOnce(t + 41_000L);
    assertEquals(
        ActionType.REJECTED_INVALID_CONFIG,
        partialOutcome.action(),
        "Partial code/configs/ without parameters.csv rejected loudly");
    assertEquals(
        DriveWatcher.PARTIAL_CONFIGS_ERROR_MESSAGE,
        partialOutcome.summary(),
        "Exact partial code/configs/ toast message");
    assertEquals(1, toasts3.size(), "Partial config emitted exactly 1 error toast");
    assertEquals(ActionType.NONE, watcher3.pollOnce(t + 42_000L).action(), "Partial config latched");
    assertEquals(1, toasts3.size(), "No toast spam on latched partial config");

    // Emptying code/ waits full >= 25 s debounce before Restore STABLE.
    Files.deleteIfExists(corruptPkg);
    Files.deleteIfExists(validPkg);
    assertEquals(ActionType.NONE, watcher3.pollOnce(t + 50_000L).action(), "Empty poll at +0s waits");
    assertEquals(ActionType.NONE, watcher3.pollOnce(t + 52_000L).action(), "Empty poll at +2s waits");
    assertEquals(ActionType.NONE, watcher3.pollOnce(t + 70_000L).action(), "Empty poll at +20s waits");
    assertEquals(1, rio.restartCount(), "No accidental Restore STABLE before 25s debounce");
    PollOutcome restored = watcher3.pollOnce(t + 75_000L);
    assertEquals(
        ActionType.RESTORED_STABLE,
        restored.action(),
        "Empty code/ triggers Restore STABLE after >= 25s debounce");
    assertEquals(2, rio.restartCount(), "Robot restarted after 25s empty-code Restore STABLE");
  }

  private static void testFailedPullKeepsMentorAudio() throws Exception {
    Path temp = createTempDir("fluxtray-pt1-audio-spool-");
    Path logsDir = temp.resolve("drive-logs");
    Path stateDir = temp.resolve("state");
    Path dsDir = temp.resolve("ds");
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    rio.seedSimulatedMatchLogs("2026-11-20_18-00-00", "FUEL", "cafebabe");

    byte[] realPcm = generateSinePcm16kHz(0.30, 520.0);
    MultiMicAudioRecorder audio =
        new MultiMicAudioRecorder(
            fmt -> {
              AtomicInteger off = new AtomicInteger(0);
              return new MultiMicAudioRecorder.PcmCapturePort() {
                @Override
                public int read(byte[] buf, int offset, int length) {
                  int cur = off.get();
                  if (cur >= realPcm.length) {
                    return -1;
                  }
                  int n = Math.min(length, realPcm.length - cur);
                  System.arraycopy(realPcm, cur, buf, offset, n);
                  off.addAndGet(n);
                  return n;
                }

                @Override
                public void close() {}
              };
            });

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            audio);

    long nowMs = Instant.parse("2026-11-20T18:01:00Z").toEpochMilli();
    audio.onRobotStateChanged(true, nowMs - 20_000L);
    Thread.sleep(60L);
    audio.onRobotStateChanged(false, nowMs - 5_000L);

    AtomicInteger downloadAttempts = new AtomicInteger(0);
    RobotTransport flakyRobot =
        new DelegatingRobotTransport(rio) {
          @Override
          public Map<String, Path> downloadSelectedLogs(
              List<RemoteLogEntry> entries, Path localDestinationDir) throws IOException {
            if (downloadAttempts.incrementAndGet() == 1) {
              throw new IOException("Simulated Wi-Fi drop mid-scp");
            }
            return super.downloadSelectedLogs(entries, localDestinationDir);
          }

          @Override
          public String captureSystemSnapshotRaw() throws IOException {
            throw new IOException("Simulated non-fatal snapshot SSH timeout");
          }
        };

    boolean attempt1Failed = false;
    try {
      runMgr.pullDriveSafeRunZip(
          "attempt1", null, flakyRobot, dsDir, logsDir, nowMs, false, true, false);
    } catch (IOException expected) {
      attempt1Failed = true;
    }
    assertTrue(attempt1Failed, "Attempt 1 failed inside downloadSelectedLogs");

    // Durable spool MUST retain mentor-audio.wav (> 44 B) after failed pull!
    List<Path> spooledDirs;
    try (Stream<Path> s = Files.list(runMgr.pendingAudioSpoolDir())) {
      spooledDirs = s.filter(Files::isDirectory).toList();
    }
    assertEquals(1, spooledDirs.size(), "Durable pending-audio spool kept Run 1 audio after failure");
    Path spooledWav = spooledDirs.get(0).resolve(MultiMicAudioRecorder.AUDIO_FILE_NAME);
    assertTrue(
        Files.isRegularFile(spooledWav) && Files.size(spooledWav) > 44L,
        "Spooled mentor-audio.wav exceeds 44-byte WAV header");

    // Attempt 2 succeeds (even with non-fatal captureSystemSnapshotRaw throwing!) and packages audio.
    CompletedRunBundle retryBundle =
        runMgr.pullDriveSafeRunZip(
            "attempt2", null, flakyRobot, dsDir, logsDir, nowMs + 2_000L, false, true, false);
    assertTrue(
        retryBundle != null && Files.isRegularFile(retryBundle.zipArchive()),
        "Retry pull succeeded even when captureSystemSnapshotRaw threw");
    byte[] wavInZip =
        readZipEntryBytes(
            retryBundle.zipArchive(), "attempt2/" + MultiMicAudioRecorder.AUDIO_FILE_NAME);
    assertTrue(
        wavInZip.length > 44,
        "Retry ZIP contains full mentor-audio.wav > 44 bytes (got " + wavInZip.length + " bytes)");
    String snapInZip = readZipEntryText(retryBundle.zipArchive(), "attempt2/system-snapshot.txt");
    assertTrue(
        snapInZip.contains("non-fatal"),
        "Non-fatal snapshot failure logged cleanly in system-snapshot.txt");

    List<Path> remainingSpool;
    try (Stream<Path> s = Files.list(runMgr.pendingAudioSpoolDir())) {
      remainingSpool = s.filter(Files::isDirectory).toList();
    }
    assertEquals(0, remainingSpool.size(), "Spool deleted only after finished ZIP moved to logs/");
  }

  private static void testManualRestoreStablePullsLogsFirst() throws Exception {
    Path temp = createTempDir("fluxtray-manual-restore-");
    Path logsDir = temp.resolve("drive-logs");
    Path dsDir = temp.resolve("ds");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");

    Path cfg = createSampleFluxFigConfigs(temp.resolve("cfg"), "Drivetrain", 60.0);
    Path jar = temp.resolve("stable.jar");
    Files.writeString(jar, "STABLE_BASELINE_JAR", StandardCharsets.UTF_8);
    Path stablePkg =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("STABLE_roborio-2026_fluxpkg.zip"),
            new PackageManifest(
                "STABLE_base", "roborio-2026", "STABLE", "1111111", false, "cfg11111", "boris"),
            jar,
            cfg);
    deployer.deployPackage(stablePkg, rio, true);

    // Seed pending robot match logs that must be pulled BEFORE manual Restore STABLE mutates robot.
    rio.seedSimulatedMatchLogs("2026-11-20_18-05-00", "FUEL", "cfg11111");
    int opsBeforeRestore = rio.operationLog().size();

    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            audio);

    try (FluxTrayMain.TrayActionCoordinator coordinator =
        new FluxTrayMain.TrayActionCoordinator(
            rio,
            deployer,
            runMgr,
            audio,
            lockout,
            dsDir,
            logsDir,
            stateDir.resolve("fluxtray.log"),
            (lvl, title, msg) -> {},
            busy -> {})) {
      coordinator.submitManualRestoreStable().get(5, TimeUnit.SECONDS);
    }

    List<String> restoreOps = rio.operationLog().subList(opsBeforeRestore, rio.operationLog().size());
    int pullIdx = indexOfPrefix(restoreOps, "downloadSelectedLogs:");
    int copyStableIdx = indexOfPrefix(restoreOps, "copyFile:jars/prev_stable.jar->");
    int restartIdx = indexOfPrefix(restoreOps, "restart:");
    assertTrue(
        pullIdx >= 0 && copyStableIdx > pullIdx && restartIdx > copyStableIdx,
        "Manual Restore STABLE must pull logs before restoring prev_stable.jar and restarting: "
            + restoreOps);
    List<Path> zips;
    try (Stream<Path> s = Files.list(logsDir)) {
      zips = s.filter(p -> p.getFileName().toString().endsWith(".zip")).toList();
    }
    assertEquals(1, zips.size(), "Manual Restore STABLE saved pending logs ZIP before restoring");
  }

  private static void testZipBuiltInTempBeforeMoveAndAudioFailureNotifies() throws Exception {
    Path temp = createTempDir("fluxtray-tray10-");
    Path logsDir = temp.resolve("drive-logs");
    Path dsDir = temp.resolve("ds");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    rio.seedSimulatedMatchLogs("2026-11-20_18-10-00", "FUEL", "12345678");

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    MultiMicAudioRecorder silentAudio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            silentAudio);

    long sameSecondMs = Instant.parse("2026-11-20T18:10:05Z").toEpochMilli();
    CompletedRunBundle first =
        runMgr.pullDriveSafeRunZip(
            "runA", null, rio, dsDir, logsDir, sameSecondMs, false, false, false);
    CompletedRunBundle second =
        runMgr.pullDriveSafeRunZip(
            "runB", null, rio, dsDir, logsDir, sameSecondMs, false, false, false);

    assertTrue(
        !first.zipArchive().equals(second.zipArchive()),
        "Same-second pulls must receive collision-safe suffix instead of overwriting");
    assertTrue(
        Files.isRegularFile(first.zipArchive()) && Files.isRegularFile(second.zipArchive()),
        "Both same-second ZIP archives preserved");
    assertTrue(
        second.zipArchive().getFileName().toString().endsWith("_FUEL_1.zip"),
        "Second same-second ZIP gets _1 suffix: " + second.zipArchive().getFileName());
    List<Path> children;
    try (Stream<Path> s = Files.list(logsDir)) {
      children = s.toList();
    }
    assertTrue(
        children.stream().noneMatch(p -> p.getFileName().toString().endsWith(".part")),
        "No .part files left inside Drive logs/");

    // LineUnavailableException must notify loudly via errorListener (never silently swallowed).
    List<String> audioErrors = new CopyOnWriteArrayList<>();
    MultiMicAudioRecorder failingAudio =
        new MultiMicAudioRecorder(
            fmt -> {
              throw new LineUnavailableException("USB headset mic disconnected");
            });
    failingAudio.setErrorListener(audioErrors::add);
    failingAudio.onRobotStateChanged(true, sameSecondMs);
    assertTrue(!failingAudio.isMicAvailable(), "micAvailable is false after LineUnavailableException");
    assertTrue(
        audioErrors.size() == 1 && audioErrors.get(0).contains("USB headset mic disconnected"),
        "Audio failure listener notified loudly: " + audioErrors);

    // Temp .pcm file must be cleaned up when close() is called mid-recording.
    CountDownLatch startedRead = new CountDownLatch(1);
    MultiMicAudioRecorder activeAudio =
        new MultiMicAudioRecorder(
            fmt ->
                new MultiMicAudioRecorder.PcmCapturePort() {
                  @Override
                  public int read(byte[] buf, int off, int len) {
                    startedRead.countDown();
                    try {
                      Thread.sleep(50L);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return -1;
                    }
                    return 0;
                  }

                  @Override
                  public void close() {}
                });
    activeAudio.onRobotStateChanged(true, sameSecondMs);
    assertTrue(startedRead.await(3, TimeUnit.SECONDS), "Capture thread started");
    Path pcmPath = activeAudio.currentTempPcmFile();
    assertTrue(pcmPath != null && Files.isRegularFile(pcmPath), "Temp .pcm file exists while recording");
    activeAudio.close();
    assertTrue(!Files.exists(pcmPath), "Temp .pcm file deleted on close()/shutdown");
  }

  private static void testMalformedManifestFailsClosedAndRollbackChecksDeployCopy()
      throws Exception {
    Path temp = createTempDir("fluxtray-tray09-");
    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");

    Path validCfg = createSampleFluxFigConfigs(temp.resolve("valid-cfg"), "Drivetrain", 60.0);
    Path jarV1 = temp.resolve("stable.jar");
    Path jarExp = temp.resolve("exp.jar");
    Files.writeString(jarV1, "STABLE_JAR_BASE", StandardCharsets.UTF_8);
    Files.writeString(jarExp, "EXP_SINGLE_LINE_JAR", StandardCharsets.UTF_8);

    Path stableZip =
        PackageDeployer.createFluxPkgZip(
            temp.resolve("stable_roborio-2026_fluxpkg.zip"),
            new PackageManifest(
                "STABLE_base", "roborio-2026", "STABLE", "1111111", false, "cfg11111", "boris"),
            jarV1,
            validCfg);
    deployer.deployPackage(stableZip, rio, true);

    // Single-line JSON manifest parses EXPERIMENTAL + gitDirty=true and must NOT overwrite prev_stable.
    String singleLineExpManifest =
        "{\"packageName\":\"EXP_one_line\",\"targetPlatform\":\"roborio-2026\",\"channel\":\"EXPERIMENTAL\",\"gitSha\":\"9999999\",\"gitDirty\":true,\"configSha\":\"cfg99999\",\"authorLaptop\":\"boris\"}";
    Path singleLineZip =
        createCustomFluxPkgZip(
            temp.resolve("single_line_exp_fluxpkg.zip"), singleLineExpManifest, jarExp, validCfg);
    PackageDeployer.DeployStamp expStamp = deployer.deployPackage(singleLineZip, rio, true);
    assertEquals("EXPERIMENTAL", expStamp.channel(), "Single-line manifest parsed EXPERIMENTAL");
    assertEquals(
        "STABLE_JAR_BASE",
        rio.readRemoteText("jars/prev_stable.jar"),
        "Single-line dirty EXPERIMENTAL package must NOT overwrite prev_stable.jar");

    // Malformed manifest missing channel/gitDirty/targetPlatform must fail closed (never default to STABLE).
    Path malformedManifestZip =
        createCustomFluxPkgZip(
            temp.resolve("malformed_manifest_fluxpkg.zip"),
            "{\"packageName\":\"BAD_MANIFEST\",\"gitSha\":\"0000000\"}",
            jarExp,
            validCfg);
    boolean malformedBlocked = false;
    try {
      deployer.deployPackage(malformedManifestZip, rio, true);
    } catch (IOException expected) {
      malformedBlocked = true;
    }
    assertTrue(malformedBlocked, "Malformed manifest missing required fields must fail closed");

    // Package ZIP missing deploy/configs/parameters.csv must fail closed.
    Path noParamsCfg = temp.resolve("no-params-cfg");
    Files.createDirectories(noParamsCfg);
    Files.writeString(
        noParamsCfg.resolve("range_table.csv"),
        "distanceMeters,shooterRps,hoodRotations,timeOfFlightSec\n2.0,35.0,0.15,0.32\n",
        StandardCharsets.UTF_8);
    Path noParamsZip =
        createCustomFluxPkgZip(
            temp.resolve("no_params_fluxpkg.zip"), singleLineExpManifest, jarExp, noParamsCfg);
    boolean missingParamsBlocked = false;
    try {
      deployer.deployPackage(noParamsZip, rio, true);
    } catch (IOException expected) {
      missingParamsBlocked = expected.getMessage().contains("parameters.csv");
    }
    assertTrue(missingParamsBlocked, "Package without deploy/configs/parameters.csv must fail closed");

    // restoreStable and rollbackToSlot must fail loudly when copyRemoteDirectory returns false.
    int restartsBefore = rio.restartCount();
    RobotTransport failDeployCopyRobot =
        new DelegatingRobotTransport(rio) {
          @Override
          public boolean copyRemoteDirectory(String fromRelativeDir, String toRelativeDir) {
            return false;
          }
        };
    boolean restoreFailed = false;
    try {
      deployer.restoreStable(failDeployCopyRobot);
    } catch (IOException expected) {
      restoreFailed = true;
    }
    assertTrue(restoreFailed, "restoreStable must fail loudly when copyRemoteDirectory returns false");
    boolean slotFailed = false;
    try {
      deployer.rollbackToSlot(1, failDeployCopyRobot);
    } catch (IOException expected) {
      slotFailed = true;
    }
    assertTrue(slotFailed, "rollbackToSlot must fail loudly when copyRemoteDirectory returns false");
    assertEquals(
        restartsBefore,
        rio.restartCount(),
        "Robot must NOT restart when rollback deploy/ copy fails");
  }

  private static void testTruncatedMsgPackFrameNeverMarksDisabled() {
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    Nt4RobotStateClient ntClient =
        new Nt4RobotStateClient(List.of("127.0.0.1"), 5810, lockout, null, () -> 50_000L);
    ntClient.handleJsonControlMessage(
        "[{\"method\":\"announce\",\"params\":{\"name\":\"/FMSInfo/FMSControlData\",\"id\":7,\"type\":\"int\",\"properties\":{}}}]");

    // Put robot into ENABLED state (0x21).
    ntClient.handleBinaryMessage(encodeNt4IntFrame(7, 1_000_000L, 0x21), "127.0.0.1");
    assertTrue(lockout.isEnabled(), "Robot is ENABLED after valid 0x21 frame");
    assertEquals(0x21, ntClient.lastControlWord(), "Control word is 0x21");

    // Truncated uint8 value: [7, uint64_ts, 1, 0xCC] missing the trailing uint8 byte.
    byte[] truncatedUint8Value = new byte[] {(byte) 0x94, 7, 1, 1, (byte) 0xCC};
    boolean threwUint8 = false;
    try {
      Nt4RobotStateClient.decodeMsgPackFrames(truncatedUint8Value);
    } catch (IllegalArgumentException expected) {
      threwUint8 = true;
    }
    assertTrue(threwUint8, "decodeMsgPackFrames must throw on unexpected EOF in readU8()");

    // Truncated uint64 timestamp inside 4-element array.
    byte[] truncatedTs = new byte[] {(byte) 0x94, 7, (byte) 0xCF, 0, 0, 1};
    boolean threwTs = false;
    try {
      Nt4RobotStateClient.decodeMsgPackFrames(truncatedTs);
    } catch (IllegalArgumentException expected) {
      threwTs = true;
    }
    assertTrue(threwTs, "decodeMsgPackFrames must throw on truncated uint64 timestamp");

    // Truncated 4-element array with only 3 elements present.
    byte[] truncatedArray = new byte[] {(byte) 0x94, 7, 1, 1};
    boolean threwArr = false;
    try {
      Nt4RobotStateClient.decodeMsgPackFrames(truncatedArray);
    } catch (IllegalArgumentException expected) {
      threwArr = true;
    }
    assertTrue(threwArr, "decodeMsgPackFrames must throw on truncated array");

    // Feeding any truncated frame to handleBinaryMessage must discard it without clearing ENABLED.
    ntClient.handleBinaryMessage(truncatedUint8Value, "127.0.0.1");
    ntClient.handleBinaryMessage(truncatedTs, "127.0.0.1");
    ntClient.handleBinaryMessage(truncatedArray, "127.0.0.1");
    assertTrue(
        lockout.isEnabled(), "Truncated MsgPack frames must NEVER flip ENABLED robot to DISABLED");
    assertEquals(0x21, ntClient.lastControlWord(), "Last control word remains 0x21");
  }

  private static Path createCustomFluxPkgZip(
      Path outputZip, String manifestJson, Path jarFile, Path configDir) throws IOException {
    Files.createDirectories(outputZip.getParent());
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputZip))) {
      zos.putNextEntry(new ZipEntry("manifest.json"));
      zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.putNextEntry(new ZipEntry("frcUserProgram.jar"));
      Files.copy(jarFile, zos);
      zos.closeEntry();
      if (configDir != null && Files.isDirectory(configDir)) {
        try (Stream<Path> walk = Files.walk(configDir)) {
          for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
            String rel = configDir.relativize(file).toString().replace('\\', '/');
            String entryName =
                (!rel.contains("/") && rel.endsWith(".csv")) ? "deploy/configs/" + rel : "deploy/" + rel;
            zos.putNextEntry(new ZipEntry(entryName));
            Files.copy(file, zos);
            zos.closeEntry();
          }
        }
      }
    }
    return outputZip;
  }

  private static Map<String, String> parseUstarTextEntries(byte[] tarBytes) {
    Map<String, String> entries = new LinkedHashMap<>();
    int pos = 0;
    while (pos + 512 <= tarBytes.length) {
      boolean allZero = true;
      for (int i = 0; i < 512; i++) {
        if (tarBytes[pos + i] != 0) {
          allZero = false;
          break;
        }
      }
      if (allZero) {
        break;
      }
      String name = readNullTerminated(tarBytes, pos, 100);
      String prefix = readNullTerminated(tarBytes, pos + 345, 155);
      String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
      String sizeOctal = readNullTerminated(tarBytes, pos + 124, 12).trim();
      int size = sizeOctal.isEmpty() ? 0 : Integer.parseInt(sizeOctal, 8);
      byte typeFlag = tarBytes[pos + 156];
      long storedSum = Long.parseLong(readNullTerminated(tarBytes, pos + 148, 8).trim(), 8);
      long computedSum = 0L;
      for (int i = 0; i < 512; i++) {
        computedSum += (i >= 148 && i < 156) ? ' ' : (tarBytes[pos + i] & 0xFF);
      }
      assertEquals(computedSum, storedSum, "Valid USTAR header checksum for " + fullName);
      pos += 512;
      if (typeFlag != (byte) '5') {
        String content = new String(tarBytes, pos, size, StandardCharsets.UTF_8);
        entries.put(fullName, content);
      }
      int blocks = (size + 511) / 512;
      pos += blocks * 512;
    }
    return entries;
  }

  private static String readNullTerminated(byte[] buf, int off, int maxLen) {
    int end = off;
    while (end < off + maxLen && buf[end] != 0) {
      end++;
    }
    return new String(buf, off, end - off, StandardCharsets.UTF_8);
  }

  private static byte[] readZipEntryBytes(Path zipPath, String entryName) throws IOException {
    try (ZipFile zf = new ZipFile(zipPath.toFile())) {
      ZipEntry ze = zf.getEntry(entryName);
      assertTrue(ze != null, "Expected ZIP entry " + entryName + " in " + zipPath);
      return zf.getInputStream(ze).readAllBytes();
    }
  }

  private static String readZipEntryText(Path zipPath, String entryName) throws IOException {
    return new String(readZipEntryBytes(zipPath, entryName), StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void assertThrowsIllegalState(ThrowingRunnable action, String msg) {
    try {
      action.run();
    } catch (IllegalStateException expected) {
      return;
    } catch (Exception other) {
      throw new AssertionError(
          "Assertion failed (" + msg + "): expected IllegalStateException, got " + other, other);
    }
    throw new AssertionError("Assertion failed (" + msg + "): did not throw IllegalStateException");
  }

  private static class DelegatingRobotTransport implements RobotTransport {
    private final RobotTransport delegate;

    private DelegatingRobotTransport(RobotTransport delegate) {
      this.delegate = delegate;
    }

    @Override
    public String controllerPlatform() {
      return delegate.controllerPlatform();
    }

    @Override
    public String currentBootEpochId() {
      return delegate.currentBootEpochId();
    }

    @Override
    public boolean isReachable() {
      return delegate.isReachable();
    }

    @Override
    public RobotStateHeartbeat readRobotStateHeartbeat() throws IOException {
      return delegate.readRobotStateHeartbeat();
    }

    @Override
    public void assertRobotDisabledForTransfer(String operationName) throws IOException {
      delegate.assertRobotDisabledForTransfer(operationName);
    }

    @Override
    public String detectRobotIdentity() throws IOException {
      return delegate.detectRobotIdentity();
    }

    @Override
    public String resolveActiveJarRemotePath() throws IOException {
      return delegate.resolveActiveJarRemotePath();
    }

    @Override
    public String readRemoteText(String remoteRelativePath) throws IOException {
      return delegate.readRemoteText(remoteRelativePath);
    }

    @Override
    public void writeRemoteText(String remoteRelativePath, String content) throws IOException {
      delegate.writeRemoteText(remoteRelativePath, content);
    }

    @Override
    public void uploadFile(Path localFile, String remoteRelativePath) throws IOException {
      delegate.uploadFile(localFile, remoteRelativePath);
    }

    @Override
    public void uploadFileAtomic(Path localFile, String remoteRelativePath) throws IOException {
      delegate.uploadFileAtomic(localFile, remoteRelativePath);
    }

    @Override
    public void replaceRemoteDirectoryAtomic(Path localDir, String remoteRelativeDir)
        throws IOException {
      delegate.replaceRemoteDirectoryAtomic(localDir, remoteRelativeDir);
    }

    @Override
    public boolean copyRemoteFile(String fromRelativePath, String toRelativePath)
        throws IOException {
      return delegate.copyRemoteFile(fromRelativePath, toRelativePath);
    }

    @Override
    public boolean copyRemoteDirectory(String fromRelativeDir, String toRelativeDir)
        throws IOException {
      return delegate.copyRemoteDirectory(fromRelativeDir, toRelativeDir);
    }

    @Override
    public boolean remoteFileExists(String remoteRelativePath) throws IOException {
      return delegate.remoteFileExists(remoteRelativePath);
    }

    @Override
    public boolean deleteRemoteFile(String remoteRelativePath) throws IOException {
      return delegate.deleteRemoteFile(remoteRelativePath);
    }

    @Override
    public void restartRobotCode() throws IOException {
      delegate.restartRobotCode();
    }

    @Override
    public List<String> listRemoteLogFiles() throws IOException {
      return delegate.listRemoteLogFiles();
    }

    @Override
    public List<RemoteLogEntry> listRemoteLogEntries() throws IOException {
      return delegate.listRemoteLogEntries();
    }

    @Override
    public Map<String, Path> downloadAllLogs(Path localDestinationDir) throws IOException {
      return delegate.downloadAllLogs(localDestinationDir);
    }

    @Override
    public Map<String, Path> downloadSelectedLogs(
        List<RemoteLogEntry> entries, Path localDestinationDir) throws IOException {
      return delegate.downloadSelectedLogs(entries, localDestinationDir);
    }

    @Override
    public String captureSystemSnapshotRaw() throws IOException {
      return delegate.captureSystemSnapshotRaw();
    }
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

  private static void testInspectRobotCodeDistinguishesLegacyFromFlux2027() throws Exception {
    Path workDir = createTempDir("fluxtray-inspect-code-");
    MockRobotServer robot = new MockRobotServer(workDir.resolve("mock-robot"), "roborio-2026");

    // Unreachable robot.
    robot.setReachable(false);
    PackageDeployer.RobotCodeStatus unreachable = PackageDeployer.inspectRobotCode(robot);
    assertEquals(
        PackageDeployer.CodeGeneration.UNREACHABLE,
        unreachable.generation(),
        "Unreachable robot must report UNREACHABLE");

    // Legacy / New_Robot: reachable, no /tmp/flux-robot-state, no deploy-stamp.json.
    robot.setReachable(true);
    robot.clearRobotStateFile();
    robot.setRobotCommand("/usr/local/frc/JRE/bin/java -jar /home/lvuser/frcUserProgram.jar\n");
    Path mockLogs = workDir.resolve("mock-robot").resolve("logs");
    Files.createDirectories(mockLogs);
    Files.write(mockLogs.resolve("akit_legacy.wpilog"), new byte[2048]);
    Files.write(mockLogs.resolve("Drivetrain_legacy.hoot"), new byte[1024]);
    PackageDeployer.RobotCodeStatus legacy = PackageDeployer.inspectRobotCode(robot);
    assertEquals(
        PackageDeployer.CodeGeneration.LEGACY_OR_NON_FLUX,
        legacy.generation(),
        "Missing heartbeat and deploy-stamp must report LEGACY_OR_NON_FLUX");
    assertTrue(legacy.isLegacyOrNonFlux(), "isLegacyOrNonFlux() must be true");
    assertEquals("frcUserProgram.jar", legacy.activeJar(), "Active JAR parsed from robotCommand");
    assertTrue(
        legacy.diagnostics().internalFreeMb() >= 0L,
        "Legacy robot diagnostics must report internal free space");
    assertEquals(1, legacy.diagnostics().wpilogCount(), "Legacy .wpilog count");
    assertEquals(1, legacy.diagnostics().hootCount(), "Legacy .hoot count");

    // Unstamped 2027 Gradle deploy: heartbeat present, no deploy-stamp.json.
    robot.setRobotStateFile(false, false, "FUEL");
    PackageDeployer.RobotCodeStatus unstamped = PackageDeployer.inspectRobotCode(robot);
    assertEquals(
        PackageDeployer.CodeGeneration.FLUX_2027_UNSTAMPED,
        unstamped.generation(),
        "Heartbeat present without deploy-stamp must report FLUX_2027_UNSTAMPED");
    assertTrue(!unstamped.isLegacyOrNonFlux(), "Unstamped 2027 code is not legacy");

    // Full .fluxpkg.zip deploy: deploy-stamp.json present.
    Path pkgDir = createTempDir("fluxtray-inspect-pkg-");
    Path cfgDir = createSampleFluxFigConfigs(pkgDir.resolve("configs"), "FUEL", 50.0);
    Path fakeJar = pkgDir.resolve("Flux-RoboRIO-2027.jar");
    Files.writeString(fakeJar, "FAKE_JAR", StandardCharsets.UTF_8);
    Path pkgZip =
        PackageDeployer.createFluxPkgZip(
            pkgDir.resolve("STABLE_v2027.1.0_roborio-2026_fluxpkg.zip"),
            new PackageDeployer.PackageManifest(
                "STABLE_v2027.1.0_roborio-2026",
                "roborio-2026",
                "STABLE",
                "abc1234",
                false,
                "cfg999",
                "test-laptop"),
            fakeJar,
            cfgDir);
    PackageDeployer deployer = new PackageDeployer(new RobotSafetyLockout(), "test-laptop");
    deployer.deployPackage(pkgZip, robot, true);
    PackageDeployer.RobotCodeStatus packaged = PackageDeployer.inspectRobotCode(robot);
    assertEquals(
        PackageDeployer.CodeGeneration.FLUX_2027_PACKAGE,
        packaged.generation(),
        "Deployed package must report FLUX_2027_PACKAGE");
    assertTrue(packaged.hasPrevStable(), "STABLE deploy must record prev_stable.jar");
    assertEquals("abc1234", packaged.deployStamp().gitSha(), "gitSha read from deploy-stamp.json");
  }

  private static void testHb08SshAsyncStdinLockoutExit42AndTimeout() throws Exception {
    SshRobotTransport ssh =
        new SshRobotTransport(List.of("127.0.0.1"), false, new RobotSafetyLockout(), false, null);
    byte[] largePayload = new byte[3 * 1024 * 1024];
    for (int i = 0; i < largePayload.length; i++) {
      largePayload[i] = (byte) (i & 0xFF);
    }

    // Large (>2 MB) stdin payload with child exiting 42 early must not hang or mask lockout as Broken pipe.
    long startMs = System.currentTimeMillis();
    SshRobotTransport.ProcessResult res =
        ssh.runLocalProcess(
            List.of("/bin/sh", "-c", "echo 'LOCKOUT: state=ENABLED enabled=true fms=false' >&2; exit 42"),
            largePayload,
            Duration.ofSeconds(5));
    long elapsedMs = System.currentTimeMillis() - startMs;
    assertTrue(elapsedMs < 4_000L, "Early exit-42 child with 3 MB stdin must finish quickly");
    assertEquals(
        SshRobotTransport.LOCKOUT_EXIT_CODE,
        res.exitCode(),
        "Exact exit code 42 preserved despite large unread stdin");
    assertTrue(
        res.stderr().contains("LOCKOUT:"),
        "Stderr lockout reason preserved: " + res.stderr());

    boolean threwLockout = false;
    try {
      ssh.runCheckedResult(
          List.of("/bin/sh", "-c", "echo 'LOCKOUT: state=ENABLED' >&2; exit 42"),
          largePayload,
          5L);
    } catch (IllegalStateException expected) {
      threwLockout = expected.getMessage().contains("LOCKOUT");
    }
    assertTrue(
        threwLockout,
        "runCheckedResult must throw IllegalStateException LOCKOUT on exit 42 (never Broken pipe)");

    // Stalled child that ignores 3 MB stdin must abort promptly at timeout and terminate.
    long timeoutStartMs = System.currentTimeMillis();
    boolean timedOut = false;
    try {
      ssh.runLocalProcess(
          List.of("/bin/sh", "-c", "sleep 30"), largePayload, Duration.ofMillis(400));
    } catch (IOException expected) {
      timedOut = expected.getMessage().toLowerCase(Locale.ROOT).contains("timed out");
    }
    long timeoutElapsedMs = System.currentTimeMillis() - timeoutStartMs;
    assertTrue(timedOut, "Stalled child must throw timeout IOException");
    assertTrue(
        timeoutElapsedMs < 3_500L,
        "Stalled child timeout must abort promptly (elapsed=" + timeoutElapsedMs + " ms)");
  }

  private static void testHb09AudioSpoolBeforeSshFailureAndAutoStopCaps() throws Exception {
    Path temp = createTempDir("fluxtray-hb09-");
    Path logsDir = temp.resolve("logs");
    Path dsDir = temp.resolve("ds");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    byte[] realPcm = generateSinePcm16kHz(0.25, 440.0);
    MultiMicAudioRecorder audio =
        new MultiMicAudioRecorder(
            fmt -> {
              AtomicInteger off = new AtomicInteger(0);
              return new MultiMicAudioRecorder.PcmCapturePort() {
                @Override
                public int read(byte[] buf, int offset, int length) {
                  int cur = off.get();
                  if (cur >= realPcm.length) {
                    return -1;
                  }
                  int n = Math.min(length, realPcm.length - cur);
                  System.arraycopy(realPcm, cur, buf, offset, n);
                  off.addAndGet(n);
                  return n;
                }

                @Override
                public void close() {}
              };
            });

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            audio);

    long nowMs = Instant.parse("2026-11-20T18:30:00Z").toEpochMilli();
    audio.onRobotStateChanged(true, nowMs - 20_000L);
    Thread.sleep(60L);
    audio.onRobotStateChanged(false, nowMs - 5_000L);
    assertTrue(audio.isRecording(), "Audio recording active before pull");

    MockRobotServer baseRio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    RobotTransport breakerOffRobot =
        new DelegatingRobotTransport(baseRio) {
          @Override
          public void assertRobotDisabledForTransfer(String operationName) throws IOException {
            throw new IOException("Robot breaker switched off: connection refused");
          }
        };

    boolean threwSsh = false;
    try {
      runMgr.pullDriveSafeRunZip(
          "breaker_off", null, breakerOffRobot, dsDir, logsDir, nowMs, false, true, false);
    } catch (IOException expected) {
      threwSsh = true;
    }
    assertTrue(threwSsh, "pullDriveSafeRunZip must propagate SSH breaker-off failure");
    assertTrue(!audio.isRecording(), "Capture must stop cleanly even when assertRobotDisabledForTransfer throws");
    List<Path> spooledDirs;
    try (Stream<Path> s = Files.list(runMgr.pendingAudioSpoolDir())) {
      spooledDirs = s.filter(Files::isDirectory).toList();
    }
    assertEquals(1, spooledDirs.size(), "Durable spool must preserve mentor narration before SSH failure");
    Path spooledWav = spooledDirs.get(0).resolve(MultiMicAudioRecorder.AUDIO_FILE_NAME);
    assertTrue(
        Files.isRegularFile(spooledWav) && Files.size(spooledWav) > 44L,
        "Spooled mentor-audio.wav must exceed 44-byte WAV header");
    audio.close();

    // Post-disable auto-stop timer must stop recording and spool automatically without any pull call.
    Path autoSpoolDir = temp.resolve("auto-spool");
    MultiMicAudioRecorder timedAudio =
        new MultiMicAudioRecorder(
            fmt -> {
              AtomicInteger off = new AtomicInteger(0);
              return new MultiMicAudioRecorder.PcmCapturePort() {
                @Override
                public int read(byte[] buf, int offset, int length) {
                  int cur = off.get();
                  if (cur >= realPcm.length) {
                    try {
                      Thread.sleep(20L);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return -1;
                    }
                    return 0;
                  }
                  int n = Math.min(length, realPcm.length - cur);
                  System.arraycopy(realPcm, cur, buf, offset, n);
                  off.addAndGet(n);
                  return n;
                }

                @Override
                public void close() {}
              };
            },
            150L,
            MultiMicAudioRecorder.MAX_RECORDING_BYTES,
            MultiMicAudioRecorder.MAX_RECORDING_DURATION_MS);
    timedAudio.setSpoolRootDir(autoSpoolDir);
    try {
      timedAudio.onRobotStateChanged(true, nowMs);
      Thread.sleep(40L);
      timedAudio.onRobotStateChanged(false, nowMs + 5_000L);
      for (int i = 0; i < 40 && timedAudio.isRecording(); i++) {
        Thread.sleep(25L);
      }
      assertTrue(!timedAudio.isRecording(), "Post-disable timer must auto-stop recording");
      List<Path> autoSpooled;
      try (Stream<Path> s = Files.list(autoSpoolDir)) {
        autoSpooled = s.filter(Files::isDirectory).toList();
      }
      assertEquals(1, autoSpooled.size(), "Post-disable auto-stop must write durable spool");
      assertTrue(
          Files.size(autoSpooled.get(0).resolve(MultiMicAudioRecorder.AUDIO_FILE_NAME)) > 44L,
          "Auto-spooled mentor-audio.wav must contain PCM payload");
    } finally {
      timedAudio.close();
    }

    // Max recording byte cap must halt unbounded capture and spool safely.
    Path capSpoolDir = temp.resolve("cap-spool");
    List<String> capErrors = new CopyOnWriteArrayList<>();
    MultiMicAudioRecorder cappedAudio =
        new MultiMicAudioRecorder(
            fmt ->
                new MultiMicAudioRecorder.PcmCapturePort() {
                  @Override
                  public int read(byte[] buf, int offset, int length) {
                    int n = Math.min(length, 2048);
                    Arrays.fill(buf, offset, offset + n, (byte) 0x2A);
                    return n;
                  }

                  @Override
                  public void close() {}
                },
            15_000L,
            8192L,
            MultiMicAudioRecorder.MAX_RECORDING_DURATION_MS);
    cappedAudio.setSpoolRootDir(capSpoolDir);
    cappedAudio.setErrorListener(capErrors::add);
    try {
      cappedAudio.onRobotStateChanged(true, nowMs + 10_000L);
      for (int i = 0; i < 40 && cappedAudio.isRecording(); i++) {
        Thread.sleep(25L);
      }
      assertTrue(!cappedAudio.isRecording(), "Recording cap must auto-stop capture");
      assertTrue(!capErrors.isEmpty(), "Recording cap must notify errorListener");
      List<Path> capSpooled;
      try (Stream<Path> s = Files.list(capSpoolDir)) {
        capSpooled = s.filter(Files::isDirectory).toList();
      }
      assertEquals(1, capSpooled.size(), "Capped recording must spool cleanly");
      long cappedWavSize = Files.size(capSpooled.get(0).resolve(MultiMicAudioRecorder.AUDIO_FILE_NAME));
      assertTrue(
          cappedWavSize == 44L + 8192L,
          "Capped WAV size must match header + cap (got " + cappedWavSize + ")");
    } finally {
      cappedAudio.close();
    }
  }

  private static void testHb10AudioZeroByteReadSpinExitsQuicklyAndNotifies() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);
    TargetDataLine zeroReturningLine =
        (TargetDataLine)
            Proxy.newProxyInstance(
                TargetDataLine.class.getClassLoader(),
                new Class<?>[] {TargetDataLine.class},
                (proxy, method, args) -> {
                  return switch (method.getName()) {
                    case "read" -> 0;
                    case "isOpen", "isActive" -> !closed.get();
                    case "close" -> {
                      closed.set(true);
                      yield null;
                    }
                    case "stop", "start", "flush", "drain", "open" -> null;
                    case "available", "getBufferSize", "getFramePosition" -> 0;
                    case "getLongFramePosition", "getMicrosecondPosition" -> 0L;
                    case "getLevel" -> 0.0f;
                    case "isRunning" -> !closed.get();
                    default -> null;
                  };
                });

    List<String> errors = new CopyOnWriteArrayList<>();
    MultiMicAudioRecorder recorder =
        new MultiMicAudioRecorder(fmt -> MultiMicAudioRecorder.wrapTargetDataLine(zeroReturningLine));
    recorder.setErrorListener(errors::add);
    try {
      long startMs = System.currentTimeMillis();
      recorder.onRobotStateChanged(true, startMs);
      while (recorder.isCaptureThreadAlive() && (System.currentTimeMillis() - startMs) < 2_000L) {
        Thread.sleep(15L);
      }
      long elapsedMs = System.currentTimeMillis() - startMs;
      assertTrue(!recorder.isCaptureThreadAlive(), "0-byte read spin capture thread must exit cleanly");
      assertTrue(elapsedMs < 1_000L, "0-byte read spin must abort in < 1 s (got " + elapsedMs + " ms)");
      assertTrue(
          !errors.isEmpty() && errors.get(0).toLowerCase(Locale.ROOT).contains("0-byte"),
          "Error listener must be notified on 0-byte stall: " + errors);
      assertTrue(closed.get(), "Stalled TargetDataLine must be closed on abort");
    } finally {
      recorder.close();
    }
  }

  private static void testHb11SshStreamingBinaryDownloadDirectToDisk() throws Exception {
    Path temp = createTempDir("fluxtray-hb11-");
    Path srcFile = temp.resolve("sample_2560kb.wpilog");
    byte[] binaryData = new byte[2 * 1024 * 1024 + 513];
    for (int i = 0; i < binaryData.length; i++) {
      // Mix all 256 byte values including invalid UTF-8 lead/continuation bytes (0xFF, 0xFE, 0x80).
      binaryData[i] = (byte) ((i * 31 + 0x80) & 0xFF);
    }
    Files.write(srcFile, binaryData);

    SshRobotTransport ssh =
        new SshRobotTransport(List.of("127.0.0.1"), false, new RobotSafetyLockout(), false, null);
    Path dstFile = temp.resolve("streamed.wpilog");
    SshRobotTransport.ProcessResult res =
        ssh.runLocalProcessToFile(
            List.of("/bin/cat", srcFile.toAbsolutePath().toString()),
            null,
            dstFile,
            Duration.ofSeconds(10));
    assertEquals(0, res.exitCode(), "Streaming binary download must exit 0");
    assertEquals("", res.stdout(), "Streaming download must not decode stdout to UTF-8 String");
    assertEquals(0, res.stdoutBytes().length, "Streaming download must not buffer stdout in byte[]");
    assertTrue(
        Arrays.equals(binaryData, Files.readAllBytes(dstFile)),
        "Streamed binary .wpilog must match source byte-for-byte");

    // Non-zero exit (e.g., exit 42 lockout) must delete partial target file.
    Path failedDst = temp.resolve("failed.wpilog");
    SshRobotTransport.ProcessResult failRes =
        ssh.runLocalProcessToFile(
            List.of("/bin/sh", "-c", "printf 'PARTIAL'; echo 'LOCKOUT: enabled' >&2; exit 42"),
            null,
            failedDst,
            Duration.ofSeconds(5));
    assertEquals(
        SshRobotTransport.LOCKOUT_EXIT_CODE,
        failRes.exitCode(),
        "Streaming download must preserve exit 42");
    assertTrue(
        !Files.exists(failedDst),
        "Partial target file must be deleted on non-zero exit");
  }

  private static void testHb12UpdaterOptInTimeoutSemVerTransientRetryAndWindowsQuoting()
      throws Exception {
    // Auto-update must be OFF by default and strictly opt-in via --auto-update.
    String prevAutoProp = System.getProperty("flux.autoupdate");
    try {
      System.clearProperty("flux.autoupdate");
      assertTrue(
          !FluxTrayMain.isAutoUpdateEnabled(new String[] {"--tray"}),
          "Auto-update must be OFF by default for M1");
      assertTrue(
          FluxTrayMain.isAutoUpdateEnabled(new String[] {"--tray", "--auto-update"}),
          "--auto-update must opt in");
      assertTrue(
          !FluxTrayMain.isAutoUpdateEnabled(
              new String[] {"--tray", "--auto-update", "--no-auto-update"}),
          "--no-auto-update must keep disabling auto-update");
    } finally {
      if (prevAutoProp != null) {
        System.setProperty("flux.autoupdate", prevAutoProp);
      } else {
        System.clearProperty("flux.autoupdate");
      }
    }

    // SemVer suffixed-to-suffixed and +build metadata comparisons.
    assertTrue(
        GitHubReleaseUpdater.isNewerVersion("2027.2.0-ct-handoff", "v2027.2.0-ct-handoff-2"),
        "Suffixed upgrade ct-handoff -> ct-handoff-2 must be detected as newer");
    assertTrue(
        !GitHubReleaseUpdater.isNewerVersion("2027.2.0-ct-handoff-2", "v2027.2.0-ct-handoff"),
        "Reverse suffixed downgrade must return false");
    assertTrue(
        GitHubReleaseUpdater.isNewerVersion("2027.2.0-ct-handoff-2", "v2027.2.0-ct-handoff-10"),
        "Numeric prerelease token comparison must treat 10 > 2");
    assertTrue(
        !GitHubReleaseUpdater.isNewerVersion("2027.2.0+build.1", "v2027.2.0+build.2"),
        "+build metadata must be ignored for SemVer precedence");
    assertTrue(
        !GitHubReleaseUpdater.isNewerVersion("2027.2.0", "v2027.2.0+build.99"),
        "+build metadata must never trigger false upgrade on same core");
    assertTrue(
        GitHubReleaseUpdater.isNewerVersion("2027.2.0+build.1", "v2027.2.1"),
        "Higher patch after stripping +build must be newer");

    // extractArrayObjects must ignore "assets" inside JSON string values like release "body".
    String trickyJson =
        "{\"tag_name\":\"v2027.3.0\",\"body\":\"Notes: \\\"assets\\\": [{\\\"name\\\":\\\"FluxTray.jar\\\",\\\"browser_download_url\\\":\\\"https://evil.invalid/bad.jar\\\"}]\",\"draft\":false,\"prerelease\":false,"
            + "\"assets\":[{\"name\":\"FluxTray.jar\",\"browser_download_url\":\"https://good.invalid/FluxTray.jar\"},"
            + "{\"name\":\"FluxTray.jar.sha256\",\"browser_download_url\":\"https://good.invalid/FluxTray.jar.sha256\"}]}";
    var parsed = GitHubReleaseUpdater.parseLatestReleaseJson(trickyJson);
    assertTrue(parsed.isPresent(), "Tricky release JSON must parse");
    assertEquals(
        "https://good.invalid/FluxTray.jar",
        parsed.get().jarDownloadUrl(),
        "Real top-level assets array must win over \"assets\" inside body string");

    // Cross-origin redirect must NOT forward Authorization header.
    assertTrue(
        !GitHubReleaseUpdater.isSameOrigin(
            URI.create("https://api.github.com/repos/a/b"),
            URI.create("https://objects.githubusercontent.com/file")),
        "Different host must not be same origin");

    // Real-process runJarSelfTest must enforce timeout without deadlocking on stdout read.
    Path temp = createTempDir("fluxtray-hb12-");
    Path hangingJava = temp.resolve("hanging-java.sh");
    Files.writeString(
        hangingJava,
        "#!/bin/sh\necho 'starting hung self-test'\nsleep 30\n",
        StandardCharsets.UTF_8);
    hangingJava.toFile().setExecutable(true);
    Path dummyJar = temp.resolve("dummy.jar");
    Files.writeString(dummyJar, "DUMMY", StandardCharsets.UTF_8);
    long stStart = System.currentTimeMillis();
    boolean stPassed =
        GitHubReleaseUpdater.runJarSelfTest(hangingJava, dummyJar, Duration.ofMillis(400));
    long stElapsed = System.currentTimeMillis() - stStart;
    assertTrue(!stPassed, "Hanging --self-test process must fail closed");
    assertTrue(
        stElapsed < 3_500L,
        "Hanging --self-test must time out promptly (elapsed=" + stElapsed + " ms)");

    // Transient download IOException must NOT permanently latch lastFailedTag.
    Path oldJar = temp.resolve("FluxTray.jar");
    Files.writeString(oldJar, "OLD_JAR", StandardCharsets.UTF_8);
    Path validNewJar = temp.resolve("valid.jar");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(validNewJar))) {
      zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      zos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.putNextEntry(new ZipEntry("frc/flux/tray/FluxTrayMain.class"));
      zos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
      zos.closeEntry();
    }
    byte[] jarBytes = Files.readAllBytes(validNewJar);
    String sha = GitHubReleaseUpdater.sha256Hex(jarBytes);
    AtomicInteger jarDownloadAttempts = new AtomicInteger(0);
    GitHubReleaseUpdater.HttpGetPort flakyHttp =
        (url, accept, token, timeout) -> {
          if (url.endsWith("/releases/latest")) {
            return new GitHubReleaseUpdater.HttpResult(
                200,
                ("{\"tag_name\":\"v2027.2.0-ct-handoff-2\",\"draft\":false,\"prerelease\":false,\"assets\":["
                        + "{\"name\":\"FluxTray.jar\",\"browser_download_url\":\"https://api.example/jar\"},"
                        + "{\"name\":\"FluxTray.jar.sha256\",\"browser_download_url\":\"https://api.example/sha\"}]}")
                    .getBytes(StandardCharsets.UTF_8));
          }
          if (url.endsWith("/sha")) {
            return new GitHubReleaseUpdater.HttpResult(
                200, (sha + "  FluxTray.jar\n").getBytes(StandardCharsets.UTF_8));
          }
          if (jarDownloadAttempts.incrementAndGet() == 1) {
            throw new IOException("Simulated transient socket reset");
          }
          return new GitHubReleaseUpdater.HttpResult(200, jarBytes);
        };
    GitHubReleaseUpdater transientUpdater =
        new GitHubReleaseUpdater(
            "FRC-Flux-Robotics/FluxTray",
            "https://api.example",
            "2027.2.0-ct-handoff",
            "secret-token",
            flakyHttp,
            (javaExe, staged) -> true,
            (pid, tgt, stg, bak, javaExe, args) -> {});
    boolean threwTransient = false;
    try {
      transientUpdater.checkAndStageUpdate(oldJar, List.of("--tray"), false, () -> true);
    } catch (IOException expected) {
      threwTransient = expected.getMessage().contains("transient socket reset");
    }
    assertTrue(threwTransient, "Transient download failure must throw IOException");
    assertEquals(
        null,
        transientUpdater.lastFailedTag(),
        "Transient download IOException must NOT permanently latch lastFailedTag");
    var retryOutcome =
        transientUpdater.checkAndStageUpdate(oldJar, List.of("--tray"), false, () -> true);
    assertTrue(
        retryOutcome.stagedForRestart(),
        "Subsequent update attempt after transient error must retry and succeed");

    // Windows .cmd quoting with spaces, %, trailing backslash, -Dflux.* preservation, and self-clean.
    String winCmd =
        GitHubReleaseUpdater.buildWindowsSwapScript(
            4321L,
            Path.of("C:\\Users\\Jane 100% Doe\\FluxTray.jar"),
            Path.of("C:\\Users\\Jane 100% Doe\\FluxTray.jar.new"),
            Path.of("C:\\Users\\Jane 100% Doe\\FluxTray.jar.bak"),
            Path.of("C:\\Program Files\\Eclipse Adoptium\\jdk-17\\bin\\javaw.exe"),
            List.of("--tray", "--code-dir", "G:\\My Drive\\100% Team\\code\\"),
            Map.of("flux.state.dir", "C:\\Users\\Jane 100% Doe\\.fluxtray\\"));
    assertTrue(
        winCmd.contains("setlocal DisableDelayedExpansion"),
        "Windows swap script must disable delayed expansion");
    assertTrue(
        winCmd.contains("Jane 100%% Doe"),
        "Windows swap script must double % to %% in paths");
    assertTrue(
        winCmd.contains("\"G:\\My Drive\\100%% Team\\code\\\\\""),
        "Windows arg quoting must escape % and trailing backslash safely: " + winCmd);
    assertTrue(
        winCmd.contains("\"-Dflux.state.dir=C:\\Users\\Jane 100%% Doe\\.fluxtray\\\\\""),
        "Windows swap script must preserve -Dflux.* JVM properties: " + winCmd);
    assertTrue(
        winCmd.contains("del /F /Q \"%~f0\""),
        "Windows swap script must self-clean temp .cmd file");
    List<String> winLaunchCmd =
        GitHubReleaseUpdater.buildWindowsHelperLaunchCommand(
            Path.of("C:\\Users\\Jane Doe\\AppData\\Local\\Temp\\fluxtray-apply.cmd"));
    assertTrue(
        winLaunchCmd.stream().noneMatch(a -> "start".equalsIgnoreCase(a)),
        "Windows helper launcher must not use cmd /c start /B");

    // Upgraded offline --self-test must pass cleanly.
    FluxTrayMain.runSelfTest();
  }

  private static void testHb13SingleInstanceLockContentionAndRelease() throws Exception {
    Path temp = createTempDir("fluxtray-hb13-");
    Path lockFile = temp.resolve(FluxTrayMain.SingleInstanceLock.LOCK_FILE_NAME);

    var firstOpt = FluxTrayMain.SingleInstanceLock.tryAcquire(lockFile);
    assertTrue(firstOpt.isPresent(), "First single-instance lock acquisition must succeed");
    try (FluxTrayMain.SingleInstanceLock first = firstOpt.get()) {
      assertEquals(lockFile, first.lockFile(), "Lock file path matches requested stateDir lock");
      var secondOpt = FluxTrayMain.SingleInstanceLock.tryAcquire(lockFile);
      assertTrue(
          secondOpt.isEmpty(),
          "Second single-instance lock acquisition on same lock file must fail cleanly");
    }

    var thirdOpt = FluxTrayMain.SingleInstanceLock.tryAcquire(lockFile);
    assertTrue(thirdOpt.isPresent(), "Lock must be re-acquirable after clean release");
    thirdOpt.get().close();
  }

  private static void testHb14LockedDriverStationLogSkippedWithoutAbortingPull() throws Exception {
    Path temp = createTempDir("fluxtray-hb14-");
    Path logsDir = temp.resolve("drive-logs");
    Path dsDir = temp.resolve("ds-logs");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    rio.seedSimulatedMatchLogs("2026-11-20_19-00-00", "FUEL", "feedface");

    byte[] realPcm = generateSinePcm16kHz(0.20, 440.0);
    MultiMicAudioRecorder audio =
        new MultiMicAudioRecorder(
            fmt -> {
              AtomicInteger off = new AtomicInteger(0);
              return new MultiMicAudioRecorder.PcmCapturePort() {
                @Override
                public int read(byte[] buf, int offset, int length) {
                  int cur = off.get();
                  if (cur >= realPcm.length) {
                    return -1;
                  }
                  int n = Math.min(length, realPcm.length - cur);
                  System.arraycopy(realPcm, cur, buf, offset, n);
                  off.addAndGet(n);
                  return n;
                }

                @Override
                public void close() {}
              };
            });

    long nowMs = Instant.parse("2026-11-20T19:00:30Z").toEpochMilli();
    audio.onRobotStateChanged(true, nowMs - 20_000L);
    Thread.sleep(60L);
    audio.onRobotStateChanged(false, nowMs - 5_000L);

    Path unlockedDs = dsDir.resolve("2026_11_20_19_00_00.dslog");
    Path lockedDs = dsDir.resolve("2026_11_20_19_00_15.dslog");
    Files.writeString(unlockedDs, "UNLOCKED_DSLOG", StandardCharsets.UTF_8);
    Files.writeString(lockedDs, "LOCKED_DSLOG", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(unlockedDs, FileTime.fromMillis(nowMs));
    Files.setLastModifiedTime(lockedDs, FileTime.fromMillis(nowMs));

    try {
      Files.setPosixFilePermissions(lockedDs, Set.of());
      assertTrue(!Files.isReadable(lockedDs), "Locked .dslog must be unreadable in test");

      RobotSafetyLockout lockout = new RobotSafetyLockout();
      BatteryAndRunManager runMgr =
          new BatteryAndRunManager(
              temp.resolve("hub"),
              stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
              lockout,
              audio);
      CompletedRunBundle bundle =
          runMgr.pullDriveSafeRunZip(
              "hb14_run", null, rio, dsDir, logsDir, nowMs, false, true, false);
      assertTrue(
          bundle != null && Files.isRegularFile(bundle.zipArchive()),
          "Pull must succeed despite locked active .dslog");
      byte[] wavBytes =
          readZipEntryBytes(bundle.zipArchive(), "hb14_run/" + MultiMicAudioRecorder.AUDIO_FILE_NAME);
      assertTrue(wavBytes.length > 44, "Audio preserved when locked .dslog is skipped");
      String wpilog =
          readZipEntryText(bundle.zipArchive(), "hb14_run/akit_2026-11-20_19-00-00.wpilog");
      assertTrue(wpilog.contains("WPILOG_SIMULATED"), ".wpilog intact when locked .dslog is skipped");
      String unlockedContent =
          readZipEntryText(bundle.zipArchive(), "hb14_run/2026_11_20_19_00_00.dslog");
      assertEquals("UNLOCKED_DSLOG", unlockedContent, "Unlocked .dslog still bundled");
      try (ZipFile zf = new ZipFile(bundle.zipArchive().toFile())) {
        assertEquals(
            null,
            zf.getEntry("hb14_run/2026_11_20_19_00_15.dslog"),
            "Locked unreadable .dslog must be cleanly skipped");
      }
    } finally {
      Files.setPosixFilePermissions(
          lockedDs,
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      audio.close();
    }
  }

  private static void testHb15DriveWatcherCachesValidationAndBacksOffWhenUnreachable()
      throws Exception {
    Path temp = createTempDir("fluxtray-hb15-");
    Path codeDir = temp.resolve("drive/code");
    Path logsDir = temp.resolve("drive/logs");
    Path dsDir = temp.resolve("ds");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(codeDir);
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    rio.setReachable(false);

    AtomicInteger reachableProbes = new AtomicInteger(0);
    RobotTransport countingRobot =
        new DelegatingRobotTransport(rio) {
          @Override
          public boolean isReachable() {
            reachableProbes.incrementAndGet();
            return super.isReachable();
          }
        };

    Path cfg = createSampleFluxFigConfigs(temp.resolve("cfg"), "Drivetrain", 60.0);
    Path jar = temp.resolve("hb15.jar");
    Files.writeString(jar, "HB15_PACKAGE_JAR", StandardCharsets.UTF_8);
    PackageDeployer.createFluxPkgZip(
        codeDir.resolve("STABLE_hb15_roborio-2026_fluxpkg.zip"),
        new PackageManifest(
            "STABLE_hb15", "roborio-2026", "STABLE", "abc1234", false, "cfg1234", "boris"),
        jar,
        cfg);

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            audio);
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");
    DriveWatcher watcher =
        new DriveWatcher(
            codeDir,
            logsDir,
            dsDir,
            countingRobot,
            deployer,
            runMgr,
            lockout,
            (lvl, title, msg) -> {},
            stateDir,
            DriveWatcher.DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS);

    long baseMs = 1_763_660_000_000L;
    for (int i = 0; i < 20; i++) {
      PollOutcome out = watcher.pollOnce(baseMs + i * 1_000L);
      assertEquals(ActionType.NONE, out.action(), "Unreachable poll " + i + " must stay NONE");
    }
    assertEquals(
        1,
        watcher.packageValidationExtractCount(),
        "20 unreachable polls must extract pending ZIP at most once");
    assertTrue(
        reachableProbes.get() <= 5,
        "Exponential backoff must bound unreachable probes over 20s (got " + reachableProbes.get() + ")");

    // Once robot reconnects and backoff resets, next poll deploys without re-extracting for validation.
    rio.setReachable(true);
    watcher.resetUnreachableBackoff();
    PollOutcome deployed = watcher.pollOnce(baseMs + 21_000L);
    assertEquals(ActionType.DEPLOYED_PACKAGE, deployed.action(), "Package deploys once robot returns");
    assertEquals(
        1,
        watcher.packageValidationExtractCount(),
        "Cached validation avoids redundant ZIP re-extraction on reconnect");
    audio.close();
  }

  private static void testCrashBootRecoveryAllowsDeployAndRestoreWhenNoRobotJvmRunning()
      throws Exception {
    long remoteNowSec = 1_763_660_000L;
    AtomicReference<String> probeResponse = new AtomicReference<>("");
    SshRobotTransport.ProcessRunner stubRunner =
        (argv, stdin, timeout) -> {
          String cmd = argv.get(argv.size() - 1);
          if (cmd.contains("HEARTBEAT_PRESENT=")) {
            return new SshRobotTransport.ProcessResult(0, probeResponse.get(), "");
          }
          return new SshRobotTransport.ProcessResult(0, "OK\n", "");
        };
    RobotSafetyLockout lockout = new RobotSafetyLockout();
    SshRobotTransport transport =
        new SshRobotTransport(
            List.of("10.104.13.2"), false, lockout, false, "/tmp/flux-robot-state", stubRunner);

    // Absent heartbeat + ROBOT_JVM_RUNNING=0 (crash-booted robot): recovery transfer MUST proceed.
    probeResponse.set("REMOTE_EPOCH_SEC=" + remoteNowSec + "\nROBOT_JVM_RUNNING=0\nHEARTBEAT_PRESENT=0\n");
    transport.assertRobotDisabledForTransfer("crashRecoveryAbsent");

    // Stale heartbeat + ROBOT_JVM_RUNNING=0: recovery transfer MUST proceed.
    probeResponse.set(
        "REMOTE_EPOCH_SEC="
            + remoteNowSec
            + "\nROBOT_JVM_RUNNING=0\nHEARTBEAT_PRESENT=1\nSTATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
            + ((remoteNowSec - 30L) * 1_000L)
            + "\n");
    transport.assertRobotDisabledForTransfer("crashRecoveryStale");

    // Absent heartbeat + ROBOT_JVM_RUNNING=1 (live non-Flux JVM): MUST still fail closed.
    probeResponse.set("REMOTE_EPOCH_SEC=" + remoteNowSec + "\nROBOT_JVM_RUNNING=1\nHEARTBEAT_PRESENT=0\n");
    boolean blockedLiveAbsent = false;
    try {
      transport.assertRobotDisabledForTransfer("liveJvmAbsent");
    } catch (IllegalStateException expected) {
      blockedLiveAbsent = expected.getMessage().contains("LOCKOUT");
    }
    assertTrue(blockedLiveAbsent, "Absent heartbeat with live robot JVM must still fail closed");

    // Stale heartbeat + ROBOT_JVM_RUNNING=1: MUST still fail closed.
    probeResponse.set(
        "REMOTE_EPOCH_SEC="
            + remoteNowSec
            + "\nROBOT_JVM_RUNNING=1\nHEARTBEAT_PRESENT=1\nSTATE=DISABLED\nENABLED=false\nFMS=false\nEPOCH_MS="
            + ((remoteNowSec - 30L) * 1_000L)
            + "\n");
    boolean blockedLiveStale = false;
    try {
      transport.assertRobotDisabledForTransfer("liveJvmStale");
    } catch (IllegalStateException expected) {
      blockedLiveStale = expected.getMessage().contains("LOCKOUT");
    }
    assertTrue(blockedLiveStale, "Stale heartbeat with live robot JVM must still fail closed");

    // Fresh ENABLED heartbeat: ALWAYS blocked even if ROBOT_JVM_RUNNING=0 were somehow spoofed.
    probeResponse.set(
        "REMOTE_EPOCH_SEC="
            + remoteNowSec
            + "\nROBOT_JVM_RUNNING=0\nHEARTBEAT_PRESENT=1\nSTATE=ENABLED\nENABLED=true\nFMS=false\nEPOCH_MS="
            + (remoteNowSec * 1_000L)
            + "\n");
    boolean blockedFreshEnabled = false;
    try {
      transport.assertRobotDisabledForTransfer("freshEnabled");
    } catch (IllegalStateException expected) {
      blockedFreshEnabled = expected.getMessage().contains("LOCKOUT");
    }
    assertTrue(blockedFreshEnabled, "Fresh ENABLED heartbeat must always block transfers");

    // Inline guard script must contain the /proc/[0-9]*/comm crash-recovery check and stay valid POSIX sh.
    String inlineGuard = transport.buildInlineHeartbeatGuard();
    assertTrue(
        inlineGuard.contains("/proc/[0-9]*/comm") && inlineGuard.contains("_jvm"),
        "Inline guard must check /proc/[0-9]*/comm for running Java processes");
    SshRobotTransport.ProcessResult syntaxCheck =
        transport.runLocalProcess(
            List.of("/bin/sh", "-n", "-c", inlineGuard + " && echo OK"),
            null,
            Duration.ofSeconds(5));
    assertEquals(0, syntaxCheck.exitCode(), "Inline heartbeat guard must pass POSIX sh -n syntax check");
  }

  private static void testPreDeployAndPreRestoreLogPullFailureIsNonFatalExceptLockout()
      throws Exception {
    Path temp = createTempDir("fluxtray-nonfatal-prepull-");
    Path codeDir = temp.resolve("drive/code");
    Path logsDir = temp.resolve("drive/logs");
    Path dsDir = temp.resolve("ds");
    Path stateDir = temp.resolve("state");
    Files.createDirectories(codeDir);
    Files.createDirectories(logsDir);
    Files.createDirectories(dsDir);

    MockRobotServer rio = new MockRobotServer(temp.resolve("rio"), "roborio-2026");
    rio.setRobotIdentityFile("FUEL");
    rio.seedSimulatedMatchLogs("2026-11-21_12-00-00", "FUEL", "cafebabe");

    AtomicBoolean failDownloadIo = new AtomicBoolean(true);
    AtomicBoolean failListLockout = new AtomicBoolean(false);
    RobotTransport flakyLogRobot =
        new DelegatingRobotTransport(rio) {
          @Override
          public List<RobotTransport.RemoteLogEntry> listRemoteLogEntries() throws IOException {
            if (failListLockout.get()) {
              throw new IllegalStateException("LOCKOUT [/tmp/flux-robot-state]: Robot is ENABLED");
            }
            return super.listRemoteLogEntries();
          }

          @Override
          public Map<String, Path> downloadSelectedLogs(
              List<RobotTransport.RemoteLogEntry> entries, Path localDestinationDir)
              throws IOException {
            if (failDownloadIo.get()) {
              throw new IOException("Simulated Drive/SSH log download failure");
            }
            return super.downloadSelectedLogs(entries, localDestinationDir);
          }
        };

    Path cfg = createSampleFluxFigConfigs(temp.resolve("cfg"), "Drivetrain", 60.0);
    Path jar = temp.resolve("fix.jar");
    Files.writeString(jar, "FIXED_PACKAGE_JAR", StandardCharsets.UTF_8);
    PackageDeployer.createFluxPkgZip(
        codeDir.resolve("STABLE_fix_roborio-2026_fluxpkg.zip"),
        new PackageManifest(
            "STABLE_fix", "roborio-2026", "STABLE", "def5678", false, "cfg5678", "boris"),
        jar,
        cfg);

    RobotSafetyLockout lockout = new RobotSafetyLockout();
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder(fmt -> null);
    BatteryAndRunManager runMgr =
        new BatteryAndRunManager(
            temp.resolve("hub"),
            stateDir.resolve(BatteryAndRunManager.SYNCED_LEDGER_FILE_NAME),
            lockout,
            audio);
    PackageDeployer deployer = new PackageDeployer(lockout, "ct-laptop");
    List<String> notifications = new CopyOnWriteArrayList<>();
    DriveWatcher watcher =
        new DriveWatcher(
            codeDir,
            logsDir,
            dsDir,
            flakyLogRobot,
            deployer,
            runMgr,
            lockout,
            (lvl, title, msg) -> notifications.add(lvl + ":" + title + ":" + msg),
            stateDir,
            DriveWatcher.DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS);

    long baseMs = 1_763_660_100_000L;
    watcher.pollOnce(baseMs);
    PollOutcome deployed = watcher.pollOnce(baseMs + 1_000L);
    assertEquals(
        ActionType.DEPLOYED_PACKAGE,
        deployed.action(),
        "Package deploy must still succeed when pre-deploy log pull hits IOException");
    assertTrue(
        notifications.stream().anyMatch(n -> n.contains("Pre-Deploy Log Pull Skipped")),
        "Warning toast must be emitted when pre-deploy log pull is skipped");

    // Manual Restore STABLE must also succeed when pre-restore log pull throws IOException.
    int restartsBeforeRestore = rio.restartCount();
    try (FluxTrayMain.TrayActionCoordinator coord =
        new FluxTrayMain.TrayActionCoordinator(
            flakyLogRobot,
            deployer,
            runMgr,
            audio,
            lockout,
            dsDir,
            logsDir,
            stateDir.resolve("fluxtray.log"),
            (lvl, title, msg) -> notifications.add(lvl + ":" + title + ":" + msg),
            busy -> {})) {
      coord.submitManualRestoreStable().get(5, TimeUnit.SECONDS);
    }
    assertEquals(
        restartsBeforeRestore + 1,
        rio.restartCount(),
        "Manual Restore STABLE must still restore prev_stable and restart despite log pull IOException");
    assertTrue(
        notifications.stream().anyMatch(n -> n.contains("Pre-Restore Log Pull Skipped")),
        "Warning toast must be emitted when pre-restore log pull is skipped");

    // Safety LOCKOUT during pre-deploy log pull MUST still abort the deploy.
    failDownloadIo.set(false);
    failListLockout.set(true);
    Files.writeString(jar, "LOCKOUT_TEST_JAR", StandardCharsets.UTF_8);
    PackageDeployer.createFluxPkgZip(
        codeDir.resolve("STABLE_fix2_roborio-2026_fluxpkg.zip"),
        new PackageManifest(
            "STABLE_fix2", "roborio-2026", "STABLE", "abc9999", false, "cfg9999", "boris"),
        jar,
        cfg);
    watcher.pollOnce(baseMs + 3_000L);
    boolean lockoutAborted = false;
    try {
      watcher.pollOnce(baseMs + 4_000L);
    } catch (IllegalStateException expected) {
      lockoutAborted = expected.getMessage().contains("LOCKOUT");
    }
    assertTrue(lockoutAborted, "Live LOCKOUT during pre-deploy log pull must abort deploy");
    audio.close();
  }

  private static void testReadDiagnosticsUsesBusyBoxSafeDfKilobytes() throws Exception {
    AtomicReference<String> capturedScript = new AtomicReference<>("");
    SshRobotTransport.ProcessRunner runner =
        (argv, stdin, timeout) -> {
          String script = argv.get(argv.size() - 1);
          capturedScript.set(script);
          return new SshRobotTransport.ProcessResult(
              0, "INT=285|450\nUSB=1|14800|15200\nWPI=3|4096\nHOOT=0|0\nPID=4321\nUP=up 5 min\n", "");
        };
    SshRobotTransport transport =
        new SshRobotTransport(List.of("10.104.13.2"), false, null, false, runner);
    RobotTransport.RobotDiagnostics diag = transport.readDiagnostics();
    assertTrue(
        !capturedScript.get().contains("df -Pm"),
        "readDiagnostics must not use GNU-only df -Pm");
    assertTrue(
        capturedScript.get().contains("df -k "),
        "readDiagnostics must use BusyBox-safe df -k");
    assertEquals(285L, diag.internalFreeMb(), "Parsed internal free MB");
    assertEquals(450L, diag.internalTotalMb(), "Parsed internal total MB");
    assertTrue(diag.usbMounted(), "Parsed USB mounted flag");
    assertEquals(14800L, diag.usbFreeMb(), "Parsed USB free MB");

    // Verify wrapped 2-line BusyBox df -k output executes cleanly under real /bin/sh + awk.
    String awkTest =
        "printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\\n"
            + "/dev/ubi0_0\\n"
            + "  512000 102400 409600 20%% /\\n' | "
            + "awk 'NR==1{next} NF==1{dev=$1; next} "
            + "{if(dev==\"\"){dev=$1}; avail=int($(NF-2)/1024); tot=int($(NF-4)/1024); "
            + "printf \"%s|%d|%d\\n\", dev, avail, tot; exit}'";
    SshRobotTransport.ProcessResult awkRes =
        transport.runLocalProcess(List.of("/bin/sh", "-c", awkTest), null, Duration.ofSeconds(5));
    assertEquals(0, awkRes.exitCode(), "Wrapped BusyBox df -k awk parser exits 0");
    assertEquals("/dev/ubi0_0|400|500", awkRes.stdout().trim(), "Wrapped BusyBox df -k parses MB");
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
