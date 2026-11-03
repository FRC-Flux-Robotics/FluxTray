package frc.flux.tray;

import frc.flux.tray.BatteryAndRunManager.CompletedRunBundle;
import frc.flux.tray.PackageDeployer.PackageManifest;
import frc.lib.fluxfig.FluxFig;
import frc.lib.fluxfig.FluxSystemSnapshot;
import frc.lib.fluxfig.FluxSystemSnapshot.CanInterfaceStats;
import frc.lib.fluxfig.FluxSystemSnapshot.WatchDecision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Comprehensive verification suite for {@code FluxTray.jar}:
 *
 * <ol>
 *   <li>Strict Enabled / FMS transfer lockout &amp; 3-second post-Disable guard
 *   <li>{@code .fluxpkg.zip} deployment, platform check, {@code FluxFig.validateStrict()} safety
 *       gate, multi-laptop overwrite guard, 5-slot rollback ring, and 1-click "Restore STABLE"
 *   <li>Named battery tracking ({@code 2026-B01..B12}) + 3-headset spoken battery fallback
 *   <li>Practice Mode auto-pull vs. Competition FMS "Capture on Field, Sync in Pit" workflow
 *   <li>All 14 offline SystemCore &amp; Controller watch metrics and actionable decisions
 * </ol>
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
      System.out.println("FluxTrayTest: ALL 6 SUITES (34 ASSERTIONS & 14 WATCH METRICS) PASSED");
    } finally {
      cleanupTempDirs();
    }
  }

  private static void testSafetyLockoutAndAbort() {
    RobotSafetyLockout lockout = new RobotSafetyLockout(1_000L);
    // Initially disabled at t=1000ms -> at t=2500ms (< 3000ms), not ready for auto log pull
    assertTrue(!lockout.isReadyForAutoLogPull(2_500L), "Must wait 3.0s after Disable before log pull");
    assertTrue(lockout.isReadyForAutoLogPull(4_100L), "Ready for log pull after >= 3.0s Disabled");

    // Start a transfer while Disabled, then transition to Enabled -> transfer must abort immediately
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

    // Disable at t=10_000ms, but attach FMS -> must still block transfers
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
    RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
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

    deployerB.restoreStable(robot);
    assertEquals(
        "STABLE_JAR_V1_BYTES",
        robot.readRemoteText("frcUserProgram.jar"),
        "Restore STABLE restored STABLE v1");

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

    RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
    MultiMicAudioRecorder audio = new MultiMicAudioRecorder();
    BatteryAndRunManager mgr = new BatteryAndRunManager(hubDir, lockout, audio);

    // Detect initial boot epoch -> battery prompt pending
    assertTrue(mgr.checkRobotBootEpoch(robot.currentBootEpochId()), "New boot epoch detected");
    assertTrue(mgr.isBatteryPromptPending(), "Battery prompt pending after boot");

    // Simulate match enable -> spoken battery fallback in audio -> disable
    lockout.updateState(true, false, 1_000L);
    audio.onRobotStateChanged(true, 1_000L);
    audio.recordVoiceCue("Coach", 4.2, "We forgot the tray menu, running battery 2026-B07 today");
    audio.recordVoiceCue("Driver", 42.5, "Slight voltage sag coming out of the corner");
    lockout.updateState(false, false, 150_000L);
    audio.onRobotStateChanged(false, 150_000L);

    // Practice Mode: pull after >= 3.0s Disabled (and >= 10s for full debrief)
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
            161_000L);

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
            usbMirror.resolve("2027-02-18_16-08-12_COMP_2027").resolve("driver-audio.ogg"))
            && Files.isRegularFile(usbMirror.resolve(practiceRun.zipArchive().getFileName())),
        "Mirrored run bundle and default .zip archive to FLUXLOGS USB stick");

    // Verify custom output directory and custom .zip filename override (--dir / --file / --out)
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

    // Competition FMS Mode ("Capture on Field, Sync in Pit")
    robot.setReachable(false); // Blocked on FMS field VLAN
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

    // Robot returns to pit and powers on -> syncPendingFmsRuns pulls robot logs + system-snapshot.txt
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
            5.0, // 200 Hz target
            2.10,
            7.85, // #1: Loop p99 > 6.5 ms
            16.40,
            18.50, // #3: First-enable cold JIT spike > 12 ms
            2.15, // #4: 250 Hz odometry jitter > 1.5 ms
            0.90,
            3.40, // #2: VisionMs trace spike > 2.5 ms
            0.40,
            88.5, // #8: Total CPU > 80% while Java is only 14%
            14.0,
            68.0,
            29.5, // #7: Kernel softirq > 25%
            2.5,
            6.8, // #13: Disk iowait > 5% & low storage
            95_000L,
            82_000L,
            83.5, // #9: CPU temp >= 80 C & thermal throttle
            1800.0,
            0x50005, // #9 & #10: Thermal throttle (0x40004) + 5V undervoltage (0x10001)
            3.85, // #11: Heap alloc > 2.0 MB/s
            9,
            42.0,
            3, // #12: 3 GC-correlated loop overruns
            280.0, // #13: Free storage < 400 MB
            31.0,
            0.68, // #14: ARMv8 PMU IPC < 0.80 & LLC miss > 15%
            6.5,
            19.4,
            740L,
            List.of(
                new CanInterfaceStats(
                    "can_s0", 380_000L, 90_000L, 14L, 0L, 6L, 0, 0, "ERROR-ACTIVE"), // #5: drops
                new CanInterfaceStats(
                    "can_s1", 1_200L, 400L, 0L, 0L, 0L, 0, 0, "ERROR-ACTIVE"), // #5b: odd port
                new CanInterfaceStats(
                    "can_s2", 140_000L, 45_000L, 0L, 0L, 0L, 19, 1, "ERROR-PASSIVE") // #6: bus err
                ));

    List<WatchDecision> decisions =
        FluxSystemSnapshot.parse(stressed.serialize()).evaluate();
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
    RobotSafetyLockout lockout = new RobotSafetyLockout(0L);
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

    MultiMicAudioRecorder audio = new MultiMicAudioRecorder();
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

    // lock robot back to Mode.PRODUCTION
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

  private static Path createSampleFluxFigConfigs(
      Path dir, String swerveBus, double statorLimitAmps) throws IOException {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("parameters.csv"),
        "Key,DEFAULT,COMP_2027\n"
            + "controllerProfile,SYSTEMCORE_2027,SYSTEMCORE_2027\n"
            + "swerveCanBus,"
            + swerveBus
            + ","
            + swerveBus
            + "\n"
            + "mechCanBus,can_s2,can_s2\n"
            + "loopPeriodMs,5.0,5.0\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("mechanisms.csv"),
        "robot,mechanism,motorId,canBus,statorLimitAmps,supplyLimitAmps,slipCurrentAmps,gravityType,kP,kI,kD,kS,kV,kA,kG,minPosRot,maxPosRot,followerMotorId,invertFollower,motorInverted,gearRatio\n"
            + "COMP_2027,Elevator,10,can_s2,"
            + statorLimitAmps
            + ",40.0,60.0,Elevator_Static,2.4,0.0,0.1,0.18,0.12,0.01,0.35,0.0,48.0,11,false,false,1.0\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("cameras.csv"),
        "robot,cameraName,portIndex,xMeters,yMeters,zMeters,rollDeg,pitchDeg,yawDeg,stdDevScalar\n"
            + "COMP_2027,limelightsc0,0,0.28,0.25,0.52,0.0,15.0,0.0,1.0\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("range_table.csv"),
        "distanceMeters,shooterRps,hoodRotations,timeOfFlightSec\n"
            + "2.0,45.0,0.15,0.32\n",
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
