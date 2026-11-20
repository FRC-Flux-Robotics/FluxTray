package frc.flux.tray;

import frc.flux.tray.MultiMicAudioRecorder.VoiceCue;
import frc.flux.tray.RobotTransport.RemoteLogEntry;
import frc.lib.fluxfig.FluxFig;
import frc.lib.fluxfig.FluxFig.BatterySpec;
import frc.lib.fluxfig.FluxSystemSnapshot;
import frc.lib.fluxfig.FluxSystemSnapshot.WatchDecision;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Coordinates Named Battery tracking, incremental remote log syncing ({@code
 * synced-remote-files.json}), {@code ±4 min} Driver Station {@code .dslog}/{@code .dsevents}
 * window correlation, {@code %TEMP%} staging with atomic {@code .zip.part -> .zip} publishing into
 * Google Drive {@code logs/}, raw {@code system-snapshot.txt} preservation, and FMS pit sync.
 */
public final class BatteryAndRunManager {

  public static final String DEFAULT_HUB_DIR_NAME = "FLUX-10413-Hub";
  public static final String DEFAULT_LOGS_SUBDIR_NAME = "04_Practice_And_Match_Logs";
  public static final String SYNCED_LEDGER_FILE_NAME = "synced-remote-files.json";
  public static final long DSLOG_WINDOW_PAD_MS = 4L * 60L * 1_000L;

  private static final DateTimeFormatter ZIP_TS_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.US);
  private static final Pattern DSLOG_TS_PATTERN =
      Pattern.compile("(?i)(\\d{4})_(\\d{2})_(\\d{2})_(\\d{2})_(\\d{2})_(\\d{2})\\.(dslog|dsevents)$");
  private static final Pattern AKIT_TS_PATTERN =
      Pattern.compile("(?i)akit_(\\d{2})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})");
  private static final Pattern ISO_LOG_TS_PATTERN =
      Pattern.compile("(?i)(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})");
  private static final Pattern LEDGER_ENTRY_PATTERN =
      Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{\\s*\"size\"\\s*:\\s*(\\d+)\\s*,\\s*\"mtime\"\\s*:\\s*(-?\\d+)\\s*\\}");

  public record CompletedRunBundle(
      String runId,
      Path runDir,
      Path zipArchive,
      String batteryName,
      String syncStatus,
      FluxSystemSnapshot systemSnapshot,
      List<WatchDecision> watchDecisions) {}

  private record SyncedFileRecord(long sizeBytes, long mtimeEpochSec) {}

  private final Path hubRootDir;
  private final Path ledgerFile;
  private final RobotSafetyLockout lockout;
  private final MultiMicAudioRecorder audioRecorder;
  private volatile String selectedBatteryName = null;
  private volatile String lastSeenBootEpochId = null;
  private volatile boolean batteryPromptPending = false;

  public BatteryAndRunManager(
      Path hubRootDir, RobotSafetyLockout lockout, MultiMicAudioRecorder audioRecorder) {
    this(hubRootDir, defaultLedgerPath(hubRootDir), lockout, audioRecorder);
  }

  public BatteryAndRunManager(
      Path hubRootDir,
      Path ledgerFile,
      RobotSafetyLockout lockout,
      MultiMicAudioRecorder audioRecorder) {
    this.hubRootDir = hubRootDir;
    this.ledgerFile = ledgerFile != null ? ledgerFile : defaultLedgerPath(hubRootDir);
    this.lockout = lockout;
    this.audioRecorder = audioRecorder;
  }

  private static Path defaultLedgerPath(Path hubRootDir) {
    String stateProp = System.getProperty("flux.state.dir");
    if (stateProp != null && !stateProp.isBlank()) {
      return Path.of(stateProp.trim()).resolve(SYNCED_LEDGER_FILE_NAME);
    }
    if (hubRootDir != null) {
      return hubRootDir.resolve(".fluxtray").resolve(SYNCED_LEDGER_FILE_NAME);
    }
    return Path.of(System.getProperty("user.home"), ".fluxtray", SYNCED_LEDGER_FILE_NAME);
  }

  /** Loads all named team batteries from {@code 03_FluxFig_Configs/batteries.csv}. */
  public List<BatterySpec> loadNamedBatteries() {
    Path configDir = hubRootDir.resolve("03_FluxFig_Configs");
    if (Files.isRegularFile(configDir.resolve("parameters.csv"))
        && Files.isRegularFile(configDir.resolve("batteries.csv"))) {
      FluxFig fig = new FluxFig(configDir);
      return new ArrayList<>(fig.getBatteries().values());
    }
    return List.of();
  }

  public synchronized boolean checkRobotBootEpoch(String currentBootEpochId) {
    if (currentBootEpochId == null || "unreachable".equals(currentBootEpochId)) {
      return false;
    }
    if (lastSeenBootEpochId == null || !lastSeenBootEpochId.equals(currentBootEpochId)) {
      lastSeenBootEpochId = currentBootEpochId;
      selectedBatteryName = null;
      batteryPromptPending = true;
      return true;
    }
    return false;
  }

  public synchronized void selectBattery(String batteryName) {
    this.selectedBatteryName = batteryName;
    this.batteryPromptPending = false;
  }

  public synchronized boolean isBatteryPromptPending() {
    return batteryPromptPending;
  }

  /** Resolves the active battery name from menu selection or spoken audio fallback. */
  public String resolveEffectiveBatteryName() {
    if (selectedBatteryName != null && !selectedBatteryName.isBlank()) {
      return selectedBatteryName;
    }
    String spoken = audioRecorder.detectSpokenBatteryFallback("2026");
    if (spoken != null) {
      return spoken;
    }
    return "UNKNOWN_BATTERY";
  }

  /** Returns remote log entries not yet recorded (or modified) in {@code synced-remote-files.json}. */
  public List<RemoteLogEntry> findUnsyncedRemoteLogs(RobotTransport robot) throws IOException {
    List<RemoteLogEntry> remote = robot.listRemoteLogEntries();
    if (remote.isEmpty()) {
      return List.of();
    }
    Map<String, SyncedFileRecord> ledger = loadSyncedLedger();
    List<RemoteLogEntry> changed = new ArrayList<>();
    for (RemoteLogEntry entry : remote) {
      SyncedFileRecord prev = ledger.get(entry.remoteFullPath());
      if (prev == null
          || prev.sizeBytes() != entry.sizeBytes()
          || prev.mtimeEpochSec() != entry.mtimeEpochSec()) {
        changed.add(entry);
      }
    }
    if (changed.isEmpty()) {
      return List.of();
    }
    // Always pair run-meta.json alongside any newly added log files so robot metadata stays intact.
    for (RemoteLogEntry entry : remote) {
      if (entry.relativeKey().endsWith("run-meta.json") && !changed.contains(entry)) {
        changed.add(entry);
      }
    }
    return changed;
  }

  /**
   * Drive-safe incremental log pull: downloads only new/changed remote files into {@code %TEMP%},
   * correlates {@code ±4 min} {@code .dslog}/{@code .dsevents}, flushes {@code mentor-audio.wav} +
   * {@code audio-sync.json}, saves raw {@code system-snapshot.txt}, atomically renames {@code
   * .zip.part -> .zip} into {@code logsDir} (zero duplicate raw run folders), updates {@code
   * synced-remote-files.json}, and deletes the temp staging directory in {@code finally}.
   *
   * @return the completed bundle, or {@code null} when {@code skipIfNoNewFiles} is true and no new
   *     robot logs exist.
   */
  public CompletedRunBundle pullDriveSafeRunZip(
      String runId,
      String robotName,
      RobotTransport robot,
      Path localDsLogDir,
      Path logsOutputDir,
      long nowMs,
      boolean enforcePostDisableWindow,
      boolean incrementalOnly,
      boolean skipIfNoNewFiles)
      throws IOException {
    if (enforcePostDisableWindow && !lockout.isDisabledStabilizedForAutoPull(nowMs)) {
      throw new IllegalStateException(
          "Robot must be Disabled for >= 3.0 seconds after Enabled -> Disabled and off-FMS before auto log pull.");
    }
    lockout.beginTransferOrThrow("pullDriveSafeRunZip:" + runId);
    Path tempStageRoot = Files.createTempDirectory("fluxtray-run-stage-");
    Path partZip = null;
    try {
      robot.assertRobotDisabledForTransfer("pullDriveSafeRunZip:" + runId);
      List<RemoteLogEntry> toDownload =
          incrementalOnly ? findUnsyncedRemoteLogs(robot) : robot.listRemoteLogEntries();
      if (skipIfNoNewFiles && toDownload.isEmpty() && !audioRecorder.isRecording()) {
        return null;
      }

      Path runStageDir = tempStageRoot.resolve(runId);
      Files.createDirectories(runStageDir);

      long audioEnableMs = audioRecorder.enableStartEpochMs();
      long audioDisableMs = audioRecorder.disableStartEpochMs();
      audioRecorder.flushAudioFile(runStageDir);
      String batteryName = resolveEffectiveBatteryName();

      if (!toDownload.isEmpty()) {
        robot.downloadSelectedLogs(toDownload, runStageDir);
      }
      copyWindowedLocalDsLogs(localDsLogDir, runStageDir, nowMs, audioEnableMs, audioDisableMs);

      Path metaPath = runStageDir.resolve("run-meta.json");
      Map<String, String> robotMeta = readExistingRunMeta(metaPath);
      String effectiveRobotId =
          resolveEffectiveRobotId(robotName, robotMeta, robot);
      String profile = robotMeta.getOrDefault("profile", effectiveRobotId);
      String effectiveConfigSha = robotMeta.getOrDefault("configSha256", "");
      String effectiveGitSha = robotMeta.getOrDefault("gitSha", "unknown");
      if ("UNKNOWN_BATTERY".equals(batteryName)) {
        String robotBatt = robotMeta.getOrDefault("batteryName", "");
        if (!robotBatt.isBlank() && !"UNKNOWN_BATTERY".equals(robotBatt)) {
          batteryName = robotBatt;
        }
      }
      int defaultLoopMs = "systemcore-2027".equalsIgnoreCase(robot.controllerPlatform()) ? 5 : 20;
      int loopPeriodMs = parsePositiveInt(robotMeta.get("loopPeriodMs"), defaultLoopMs);
      String bootTimestampIso = robotMeta.getOrDefault("bootTimestampIso", "");

      archiveRemoteTunedSnapshotIfPresent(robot, runStageDir);

      String rawSnapshot = robot.captureSystemSnapshotRaw();
      Files.writeString(
          runStageDir.resolve("system-snapshot.txt"),
          rawSnapshot != null ? rawSnapshot : "",
          StandardCharsets.UTF_8);
      FluxSystemSnapshot snapshot = FluxSystemSnapshot.parse(rawSnapshot);
      List<WatchDecision> decisions = snapshot.evaluate();

      writeRunMetaJson(
          metaPath,
          runId,
          effectiveRobotId,
          profile,
          batteryName,
          effectiveGitSha,
          effectiveConfigSha,
          loopPeriodMs,
          bootTimestampIso,
          false,
          "COMPLETE",
          audioRecorder.recordedCues(),
          decisions);
      audioRecorder.clearRecordedCues();

      Path targetDir =
          logsOutputDir != null ? logsOutputDir : hubRootDir.resolve(DEFAULT_LOGS_SUBDIR_NAME);
      Files.createDirectories(targetDir);
      Path finalZip = targetDir.resolve(defaultZipFileName(effectiveRobotId, nowMs));
      partZip = targetDir.resolve(finalZip.getFileName().toString() + ".part");
      packageRunDirectoryToZipAtomic(runStageDir, partZip, finalZip);
      partZip = null;

      if (!toDownload.isEmpty()) {
        recordSyncedEntries(toDownload);
      }

      return new CompletedRunBundle(
          runId, null, finalZip, batteryName, "COMPLETE", snapshot, decisions);
    } finally {
      if (partZip != null) {
        try {
          Files.deleteIfExists(partZip);
        } catch (IOException ignored) {
        }
      }
      deleteRecursivelyQuietly(tempStageRoot);
      lockout.endTransfer();
    }
  }

  /** Manual CLI/button pull that enforces Enabled/FMS + robot-side lockout without requiring a prior transition. */
  public CompletedRunBundle pullManualRunLogs(
      String runId,
      String robotName,
      String gitSha,
      String configSha,
      RobotTransport robot,
      Path localDsLogDir,
      Path optionalUsbMirrorDir,
      long nowMs,
      Path customOutputDir,
      String customZipFileName)
      throws IOException {
    return executePracticeRunPull(
        runId,
        robotName,
        gitSha,
        configSha,
        robot,
        localDsLogDir,
        optionalUsbMirrorDir,
        nowMs,
        customOutputDir,
        customZipFileName,
        false);
  }

  public CompletedRunBundle pullPracticeRunLogs(
      String runId,
      String robotName,
      String gitSha,
      String configSha,
      RobotTransport robot,
      Path localDsLogDir,
      Path optionalUsbMirrorDir,
      long nowMs)
      throws IOException {
    return pullPracticeRunLogs(
        runId,
        robotName,
        gitSha,
        configSha,
        robot,
        localDsLogDir,
        optionalUsbMirrorDir,
        nowMs,
        null,
        null);
  }

  public CompletedRunBundle pullPracticeRunLogs(
      String runId,
      String robotName,
      String gitSha,
      String configSha,
      RobotTransport robot,
      Path localDsLogDir,
      Path optionalUsbMirrorDir,
      long nowMs,
      Path customOutputDir,
      String customZipFileName)
      throws IOException {
    return executePracticeRunPull(
        runId,
        robotName,
        gitSha,
        configSha,
        robot,
        localDsLogDir,
        optionalUsbMirrorDir,
        nowMs,
        customOutputDir,
        customZipFileName,
        true);
  }

  private CompletedRunBundle executePracticeRunPull(
      String runId,
      String robotName,
      String gitSha,
      String configSha,
      RobotTransport robot,
      Path localDsLogDir,
      Path optionalUsbMirrorDir,
      long nowMs,
      Path customOutputDir,
      String customZipFileName,
      boolean requirePostDisableWindow)
      throws IOException {
    if (requirePostDisableWindow && !lockout.isDisabledStabilizedForAutoPull(nowMs)) {
      throw new IllegalStateException(
          "Robot must be Disabled for >= 3.0 seconds and not on FMS before pulling practice logs.");
    }
    lockout.beginTransferOrThrow("pullPracticeRunLogs:" + runId);
    try {
      robot.assertRobotDisabledForTransfer("pullPracticeRunLogs:" + runId);
      Path baseLogsDir =
          (customOutputDir != null)
              ? customOutputDir
              : hubRootDir.resolve(DEFAULT_LOGS_SUBDIR_NAME);
      Path runDir = baseLogsDir.resolve(runId);
      Files.createDirectories(runDir);

      long audioEnableMs = audioRecorder.enableStartEpochMs();
      long audioDisableMs = audioRecorder.disableStartEpochMs();
      audioRecorder.flushAudioFile(runDir);
      String batteryName = resolveEffectiveBatteryName();

      robot.downloadAllLogs(runDir);
      copyWindowedLocalDsLogs(localDsLogDir, runDir, nowMs, audioEnableMs, audioDisableMs);

      Path metaPath = runDir.resolve("run-meta.json");
      Map<String, String> robotMeta = readExistingRunMeta(metaPath);
      String effectiveRobotId = resolveEffectiveRobotId(robotName, robotMeta, robot);
      String profile = robotMeta.getOrDefault("profile", effectiveRobotId);
      String effectiveConfigSha =
          (configSha != null && !configSha.isBlank() && !"unknown".equalsIgnoreCase(configSha))
              ? configSha
              : robotMeta.getOrDefault("configSha256", "");
      String effectiveGitSha =
          (gitSha != null && !gitSha.isBlank() && !"unknown".equalsIgnoreCase(gitSha))
              ? gitSha
              : robotMeta.getOrDefault("gitSha", "unknown");
      if ("UNKNOWN_BATTERY".equals(batteryName)) {
        String robotBatt = robotMeta.getOrDefault("batteryName", "");
        if (!robotBatt.isBlank() && !"UNKNOWN_BATTERY".equals(robotBatt)) {
          batteryName = robotBatt;
        }
      }
      int defaultLoopMs = "systemcore-2027".equalsIgnoreCase(robot.controllerPlatform()) ? 5 : 20;
      int loopPeriodMs = parsePositiveInt(robotMeta.get("loopPeriodMs"), defaultLoopMs);
      String bootTimestampIso = robotMeta.getOrDefault("bootTimestampIso", "");

      archiveRemoteTunedSnapshotIfPresent(robot, runDir);

      String rawSnapshot = robot.captureSystemSnapshotRaw();
      Files.writeString(
          runDir.resolve("system-snapshot.txt"),
          rawSnapshot != null ? rawSnapshot : "",
          StandardCharsets.UTF_8);
      FluxSystemSnapshot snapshot = FluxSystemSnapshot.parse(rawSnapshot);
      List<WatchDecision> decisions = snapshot.evaluate();

      writeRunMetaJson(
          metaPath,
          runId,
          effectiveRobotId,
          profile,
          batteryName,
          effectiveGitSha,
          effectiveConfigSha,
          loopPeriodMs,
          bootTimestampIso,
          false,
          "COMPLETE",
          audioRecorder.recordedCues(),
          decisions);
      audioRecorder.clearRecordedCues();

      String zipName =
          (customZipFileName != null && !customZipFileName.isBlank())
              ? (customZipFileName.toLowerCase(Locale.ROOT).endsWith(".zip")
                  ? customZipFileName
                  : customZipFileName + ".zip")
              : defaultZipFileName(effectiveRobotId, nowMs);
      Path zipPath = baseLogsDir.resolve(zipName);
      packageRunDirectoryToZip(runDir, zipPath);

      if (optionalUsbMirrorDir != null) {
        mirrorDirectory(runDir, optionalUsbMirrorDir.resolve(runId));
        Files.copy(
            zipPath,
            optionalUsbMirrorDir.resolve(zipPath.getFileName()),
            StandardCopyOption.REPLACE_EXISTING);
      }

      return new CompletedRunBundle(
          runId, runDir, zipPath, batteryName, "COMPLETE", snapshot, decisions);
    } finally {
      lockout.endTransfer();
    }
  }

  /**
   * Competition FMS Step 1 ("Capture on Field"): Saves {@code mentor-audio.wav}, windowed {@code
   * .dslog}/{@code .dsevents}, and {@code run-meta.json} ({@code PENDING_ROBOT_LOGS}).
   */
  public CompletedRunBundle captureFieldSideFmsRun(
      String runId,
      String robotName,
      String gitSha,
      String configSha,
      Path localDsLogDir)
      throws IOException {
    Path runDir = hubRootDir.resolve(DEFAULT_LOGS_SUBDIR_NAME).resolve(runId);
    Files.createDirectories(runDir);

    long nowMs = System.currentTimeMillis();
    long audioEnableMs = audioRecorder.enableStartEpochMs();
    long audioDisableMs = audioRecorder.disableStartEpochMs();
    audioRecorder.flushAudioFile(runDir);
    String batteryName = resolveEffectiveBatteryName();
    copyWindowedLocalDsLogs(localDsLogDir, runDir, nowMs, audioEnableMs, audioDisableMs);

    writeRunMetaJson(
        runDir.resolve("run-meta.json"),
        runId,
        robotName,
        robotName,
        batteryName,
        gitSha,
        configSha,
        20,
        "",
        true,
        "PENDING_ROBOT_LOGS",
        audioRecorder.recordedCues(),
        List.of());
    audioRecorder.clearRecordedCues();

    return new CompletedRunBundle(
        runId, runDir, null, batteryName, "PENDING_ROBOT_LOGS", null, List.of());
  }

  /** Competition FMS Step 2 ("Sync in Pit"): Merges pending field runs with robot logs in pit. */
  public List<CompletedRunBundle> syncPendingFmsRuns(RobotTransport robot) throws IOException {
    lockout.beginTransferOrThrow("syncPendingFmsRuns");
    try {
      robot.assertRobotDisabledForTransfer("syncPendingFmsRuns");
      Path logsRoot = hubRootDir.resolve(DEFAULT_LOGS_SUBDIR_NAME);
      if (!Files.isDirectory(logsRoot)) {
        return List.of();
      }
      List<CompletedRunBundle> synced = new ArrayList<>();
      try (Stream<Path> dirs = Files.list(logsRoot)) {
        for (Path runDir : dirs.filter(Files::isDirectory).sorted().toList()) {
          Path metaPath = runDir.resolve("run-meta.json");
          if (!Files.isRegularFile(metaPath)) {
            continue;
          }
          String fieldMetaText = Files.readString(metaPath, StandardCharsets.UTF_8);
          if (!fieldMetaText.contains("\"PENDING_ROBOT_LOGS\"")) {
            continue;
          }
          Map<String, String> fieldMeta = PackageDeployer.parseFlatJson(fieldMetaText);
          String voiceNotesBlock = extractVoiceNotesBlock(fieldMetaText);

          robot.downloadAllLogs(runDir);
          Map<String, String> robotMeta = readExistingRunMeta(metaPath);

          archiveRemoteTunedSnapshotIfPresent(robot, runDir);
          String rawSnapshot = robot.captureSystemSnapshotRaw();
          Files.writeString(
              runDir.resolve("system-snapshot.txt"),
              rawSnapshot != null ? rawSnapshot : "",
              StandardCharsets.UTF_8);
          FluxSystemSnapshot snapshot = FluxSystemSnapshot.parse(rawSnapshot);
          List<WatchDecision> decisions = snapshot.evaluate();

          String runId = fieldMeta.getOrDefault("runId", runDir.getFileName().toString());
          String robotId =
              fieldMeta.getOrDefault(
                  "robotId",
                  fieldMeta.getOrDefault(
                      "robotName",
                      resolveEffectiveRobotId(null, robotMeta, robot)));
          String profile =
              robotMeta.getOrDefault("profile", fieldMeta.getOrDefault("profile", robotId));
          String batteryName =
              fieldMeta.getOrDefault(
                  "batteryName", fieldMeta.getOrDefault("namedBattery", "UNKNOWN_BATTERY"));
          if ("UNKNOWN_BATTERY".equals(batteryName)) {
            String robotBatt = robotMeta.getOrDefault("batteryName", "");
            if (!robotBatt.isBlank() && !"UNKNOWN_BATTERY".equals(robotBatt)) {
              batteryName = robotBatt;
            }
          }
          String gitSha = fieldMeta.getOrDefault("gitSha", "");
          if (gitSha.isBlank() || "unknown".equalsIgnoreCase(gitSha)) {
            gitSha = robotMeta.getOrDefault("gitSha", "unknown");
          }
          String configSha256 =
              fieldMeta.getOrDefault(
                  "configSha256",
                  fieldMeta.getOrDefault("configSha", robotMeta.getOrDefault("configSha256", "")));
          if ("unknown".equalsIgnoreCase(configSha256)) {
            configSha256 = robotMeta.getOrDefault("configSha256", "");
          }
          int defaultLoopMs =
              "systemcore-2027".equalsIgnoreCase(robot.controllerPlatform()) ? 5 : 20;
          int loopPeriodMs =
              parsePositiveInt(
                  robotMeta.getOrDefault("loopPeriodMs", fieldMeta.get("loopPeriodMs")),
                  defaultLoopMs);
          String bootTimestampIso =
              robotMeta.getOrDefault(
                  "bootTimestampIso", fieldMeta.getOrDefault("bootTimestampIso", ""));

          writeMergedRunMetaJson(
              metaPath,
              runId,
              robotId,
              profile,
              batteryName,
              gitSha,
              configSha256,
              loopPeriodMs,
              bootTimestampIso,
              true,
              "COMPLETE",
              decisions.size(),
              voiceNotesBlock);

          Path zipPath =
              logsRoot.resolve(defaultZipFileName(robotId, System.currentTimeMillis()));
          packageRunDirectoryToZip(runDir, zipPath);

          synced.add(
              new CompletedRunBundle(
                  runDir.getFileName().toString(),
                  runDir,
                  zipPath,
                  batteryName,
                  "COMPLETE",
                  snapshot,
                  decisions));
        }
      }
      return Collections.unmodifiableList(synced);
    } finally {
      lockout.endTransfer();
    }
  }

  /** Returns {@code match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip}. */
  public static String defaultZipFileName(String robotId, long timestampMs) {
    LocalDateTime ldt =
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestampMs > 0 ? timestampMs : System.currentTimeMillis()),
            ZoneId.systemDefault());
    String cleanRobot =
        (robotId == null || robotId.isBlank())
            ? "ROBOT"
            : robotId.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    return "match-logs_" + ldt.format(ZIP_TS_FMT) + "_" + cleanRobot + ".zip";
  }

  /** Atomically writes a run directory into {@code targetZipFile} via {@code .part -> .zip}. */
  public static Path packageRunDirectoryToZip(Path runDir, Path targetZipFile) throws IOException {
    if (targetZipFile.getParent() != null) {
      Files.createDirectories(targetZipFile.getParent());
    }
    Path partFile = targetZipFile.resolveSibling(targetZipFile.getFileName().toString() + ".part");
    try {
      return packageRunDirectoryToZipAtomic(runDir, partFile, targetZipFile);
    } finally {
      Files.deleteIfExists(partFile);
    }
  }

  private static Path packageRunDirectoryToZipAtomic(
      Path runDir, Path partZipFile, Path targetZipFile) throws IOException {
    if (targetZipFile.getParent() != null) {
      Files.createDirectories(targetZipFile.getParent());
    }
    String rootPrefix = runDir.getFileName().toString() + "/";
    try (OutputStream fos = Files.newOutputStream(partZipFile);
        ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8);
        Stream<Path> walk = Files.walk(runDir)) {
      for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
        String rel = runDir.relativize(p).toString().replace('\\', '/');
        ZipEntry entry = new ZipEntry(rootPrefix + rel);
        entry.setLastModifiedTime(Files.getLastModifiedTime(p));
        zos.putNextEntry(entry);
        Files.copy(p, zos);
        zos.closeEntry();
      }
      zos.finish();
    }
    try {
      Files.move(
          partZipFile,
          targetZipFile,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(partZipFile, targetZipFile, StandardCopyOption.REPLACE_EXISTING);
    }
    return targetZipFile;
  }

  /**
   * Copies only {@code .dslog}/{@code .dsevents} files falling within {@code ±4 minutes} of the
   * run's log/audio timestamps (ported from {@code New_Robot/scripts/pull-logs.bat}).
   */
  static int copyWindowedLocalDsLogs(
      Path localDsLogDir,
      Path runDir,
      long fallbackNowMs,
      long audioEnableMs,
      long audioDisableMs)
      throws IOException {
    if (localDsLogDir == null || !Files.isDirectory(localDsLogDir)) {
      return 0;
    }
    long[] window = resolveRunTimeWindowMs(runDir, fallbackNowMs, audioEnableMs, audioDisableMs);
    long winStart = window[0] - DSLOG_WINDOW_PAD_MS;
    long winEnd = window[1] + DSLOG_WINDOW_PAD_MS;
    int copied = 0;

    try (Stream<Path> s = Files.walk(localDsLogDir, 2)) {
      for (Path f :
          s.filter(Files::isRegularFile)
              .filter(
                  p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".dslog") || n.endsWith(".dsevents");
                  })
              .sorted()
              .toList()) {
        long mtimeMs = Files.getLastModifiedTime(f).toMillis();
        long nameTsMs = parseDsLogNameEpochMs(f.getFileName().toString());
        boolean mtimeMatches = mtimeMs >= winStart && mtimeMs <= winEnd;
        boolean nameMatches = nameTsMs > 0L && nameTsMs >= winStart && nameTsMs <= winEnd;
        if (mtimeMatches || nameMatches) {
          Files.copy(f, runDir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
          copied++;
        }
      }
    }
    return copied;
  }

  private static long[] resolveRunTimeWindowMs(
      Path runDir, long fallbackNowMs, long audioEnableMs, long audioDisableMs) throws IOException {
    long minTs = Long.MAX_VALUE;
    long maxTs = Long.MIN_VALUE;
    if (audioEnableMs > 1_700_000_000_000L) {
      minTs = Math.min(minTs, audioEnableMs);
      maxTs = Math.max(maxTs, audioEnableMs);
    }
    if (audioDisableMs > 1_700_000_000_000L) {
      minTs = Math.min(minTs, audioDisableMs);
      maxTs = Math.max(maxTs, audioDisableMs);
    }
    if (Files.isDirectory(runDir)) {
      try (Stream<Path> walk = Files.walk(runDir, 3)) {
        for (Path p : walk.filter(Files::isRegularFile).toList()) {
          String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
          if (!n.endsWith(".wpilog") && !n.endsWith(".hoot")) {
            continue;
          }
          long mtimeMs = Files.getLastModifiedTime(p).toMillis();
          long parsedNameMs = parseRobotLogNameEpochMs(p.getFileName().toString());
          // Ignore un-synced roboRIO 2024 RTC filename timestamps when mtime is >= 2025.
          long refMs =
              (parsedNameMs > 0L && isYearAtLeast2025(parsedNameMs)) ? parsedNameMs : mtimeMs;
          if (refMs > 0L) {
            minTs = Math.min(minTs, refMs);
            maxTs = Math.max(maxTs, Math.max(refMs, mtimeMs));
          }
        }
      }
    }
    if (minTs == Long.MAX_VALUE) {
      long ref = fallbackNowMs > 1_700_000_000_000L ? fallbackNowMs : System.currentTimeMillis();
      return new long[] {ref, ref};
    }
    return new long[] {minTs, maxTs};
  }

  private static boolean isYearAtLeast2025(long epochMs) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).getYear()
        >= 2025;
  }

  static long parseDsLogNameEpochMs(String fileName) {
    Matcher m = DSLOG_TS_PATTERN.matcher(fileName);
    if (!m.find()) {
      return -1L;
    }
    return toEpochMs(
        Integer.parseInt(m.group(1)),
        Integer.parseInt(m.group(2)),
        Integer.parseInt(m.group(3)),
        Integer.parseInt(m.group(4)),
        Integer.parseInt(m.group(5)),
        Integer.parseInt(m.group(6)));
  }

  private static long parseRobotLogNameEpochMs(String fileName) {
    Matcher akit = AKIT_TS_PATTERN.matcher(fileName);
    if (akit.find()) {
      return toEpochMs(
          2000 + Integer.parseInt(akit.group(1)),
          Integer.parseInt(akit.group(2)),
          Integer.parseInt(akit.group(3)),
          Integer.parseInt(akit.group(4)),
          Integer.parseInt(akit.group(5)),
          Integer.parseInt(akit.group(6)));
    }
    Matcher iso = ISO_LOG_TS_PATTERN.matcher(fileName);
    if (iso.find()) {
      return toEpochMs(
          Integer.parseInt(iso.group(1)),
          Integer.parseInt(iso.group(2)),
          Integer.parseInt(iso.group(3)),
          Integer.parseInt(iso.group(4)),
          Integer.parseInt(iso.group(5)),
          Integer.parseInt(iso.group(6)));
    }
    return -1L;
  }

  private static long toEpochMs(int y, int mo, int d, int h, int mi, int s) {
    try {
      return LocalDateTime.of(y, mo, d, h, mi, s)
          .atZone(ZoneId.systemDefault())
          .toInstant()
          .toEpochMilli();
    } catch (Exception e) {
      return -1L;
    }
  }

  private synchronized Map<String, SyncedFileRecord> loadSyncedLedger() {
    Map<String, SyncedFileRecord> map = new LinkedHashMap<>();
    if (ledgerFile == null || !Files.isRegularFile(ledgerFile)) {
      return map;
    }
    try {
      String raw = Files.readString(ledgerFile, StandardCharsets.UTF_8);
      Matcher m = LEDGER_ENTRY_PATTERN.matcher(raw);
      while (m.find()) {
        String key = unescapeJson(m.group(1));
        long size = Long.parseLong(m.group(2));
        long mtime = Long.parseLong(m.group(3));
        map.put(key, new SyncedFileRecord(size, mtime));
      }
    } catch (Exception ignored) {
    }
    return map;
  }

  private synchronized void recordSyncedEntries(List<RemoteLogEntry> entries) throws IOException {
    Map<String, SyncedFileRecord> ledger = loadSyncedLedger();
    for (RemoteLogEntry entry : entries) {
      ledger.put(entry.remoteFullPath(), new SyncedFileRecord(entry.sizeBytes(), entry.mtimeEpochSec()));
    }
    if (ledgerFile.getParent() != null) {
      Files.createDirectories(ledgerFile.getParent());
    }
    StringBuilder sb = new StringBuilder(512);
    sb.append("{\n");
    int idx = 0;
    for (Map.Entry<String, SyncedFileRecord> e : ledger.entrySet()) {
      idx++;
      sb.append("  \"")
          .append(esc(e.getKey()))
          .append("\": {\"size\": ")
          .append(e.getValue().sizeBytes())
          .append(", \"mtime\": ")
          .append(e.getValue().mtimeEpochSec())
          .append("}")
          .append(idx < ledger.size() ? "," : "")
          .append('\n');
    }
    sb.append("}\n");
    Path tmp = ledgerFile.resolveSibling(ledgerFile.getFileName().toString() + ".tmp");
    Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
    try {
      Files.move(
          tmp, ledgerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, ledgerFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String resolveEffectiveRobotId(
      String explicitRobotName, Map<String, String> robotMeta, RobotTransport robot) {
    if (explicitRobotName != null
        && !explicitRobotName.isBlank()
        && !"unknown".equalsIgnoreCase(explicitRobotName)) {
      return explicitRobotName.trim();
    }
    String metaId = robotMeta.getOrDefault("robotId", "");
    if (!metaId.isBlank() && !"unknown".equalsIgnoreCase(metaId)) {
      return metaId.trim();
    }
    try {
      return robot.detectRobotIdentity();
    } catch (IOException ignored) {
      return "roborio-2026".equalsIgnoreCase(robot.controllerPlatform()) ? "FUEL" : "COMP_2027";
    }
  }

  private static void archiveRemoteTunedSnapshotIfPresent(RobotTransport robot, Path runDir)
      throws IOException {
    String tunedSnapshot =
        robot.readRemoteText(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_PATH.toString());
    if (tunedSnapshot != null && !tunedSnapshot.isBlank()) {
      Files.writeString(
          runDir.resolve("tuned-snapshot.txt"), tunedSnapshot, StandardCharsets.UTF_8);
    }
  }

  private static void mirrorDirectory(Path srcDir, Path dstDir) throws IOException {
    Files.createDirectories(dstDir);
    try (Stream<Path> s = Files.walk(srcDir)) {
      for (Path p : s.filter(Files::isRegularFile).toList()) {
        Path rel = srcDir.relativize(p);
        Path target = dstDir.resolve(rel);
        Files.createDirectories(target.getParent());
        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static Map<String, String> readExistingRunMeta(Path metaFile) throws IOException {
    if (!Files.isRegularFile(metaFile)) {
      return Map.of();
    }
    return PackageDeployer.parseFlatJson(Files.readString(metaFile, StandardCharsets.UTF_8));
  }

  private static int parsePositiveInt(String val, int fallback) {
    if (val == null || val.isBlank()) {
      return fallback;
    }
    try {
      int parsed = (int) Math.round(Double.parseDouble(val.trim()));
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String extractVoiceNotesBlock(String json) {
    if (json == null) {
      return "  \"voiceNotes\": [\n  ]";
    }
    int idx = json.indexOf("\"voiceNotes\"");
    if (idx < 0) {
      return "  \"voiceNotes\": [\n  ]";
    }
    int openBracket = json.indexOf('[', idx);
    if (openBracket < 0) {
      return "  \"voiceNotes\": [\n  ]";
    }
    boolean inQuotes = false;
    boolean escaped = false;
    int closeBracket = -1;
    for (int i = openBracket + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\' && inQuotes) {
        escaped = true;
        continue;
      }
      if (c == '"') {
        inQuotes = !inQuotes;
        continue;
      }
      if (!inQuotes && c == ']') {
        closeBracket = i;
        break;
      }
    }
    if (closeBracket < 0) {
      return "  \"voiceNotes\": [\n  ]";
    }
    return "  " + json.substring(idx, closeBracket + 1).trim();
  }

  private static void writeRunMetaJson(
      Path metaFile,
      String runId,
      String robotId,
      String profile,
      String batteryName,
      String gitSha,
      String configSha256,
      int loopPeriodMs,
      String bootTimestampIso,
      boolean isFmsMatch,
      String syncStatus,
      List<VoiceCue> voiceCues,
      List<WatchDecision> watchDecisions)
      throws IOException {
    StringBuilder voiceBlock = new StringBuilder(512);
    voiceBlock.append("  \"voiceNotes\": [\n");
    for (int i = 0; i < voiceCues.size(); i++) {
      VoiceCue c = voiceCues.get(i);
      voiceBlock.append(
          String.format(
              Locale.US,
              "    {\"role\": \"%s\", \"timeSec\": %.2f, \"text\": \"%s\"}%s\n",
              esc(c.role()),
              c.timeSec(),
              esc(c.transcript()),
              (i + 1 < voiceCues.size()) ? "," : ""));
    }
    voiceBlock.append("  ]");

    writeMergedRunMetaJson(
        metaFile,
        runId,
        robotId,
        profile,
        batteryName,
        gitSha,
        configSha256,
        loopPeriodMs,
        bootTimestampIso,
        isFmsMatch,
        syncStatus,
        watchDecisions.size(),
        voiceBlock.toString());
  }

  private static void writeMergedRunMetaJson(
      Path metaFile,
      String runId,
      String robotId,
      String profile,
      String batteryName,
      String gitSha,
      String configSha256,
      int loopPeriodMs,
      String bootTimestampIso,
      boolean isFmsMatch,
      String syncStatus,
      int watchAlertCount,
      String voiceNotesBlock)
      throws IOException {
    StringBuilder sb = new StringBuilder(1024);
    sb.append("{\n");
    sb.append("  \"runId\": \"").append(esc(runId)).append("\",\n");
    sb.append("  \"robotId\": \"").append(esc(robotId)).append("\",\n");
    sb.append("  \"profile\": \"").append(esc(profile)).append("\",\n");
    sb.append("  \"batteryName\": \"").append(esc(batteryName)).append("\",\n");
    sb.append("  \"gitSha\": \"").append(esc(gitSha)).append("\",\n");
    sb.append("  \"configSha256\": \"").append(esc(configSha256)).append("\",\n");
    sb.append("  \"loopPeriodMs\": ").append(loopPeriodMs).append(",\n");
    sb.append("  \"bootTimestampIso\": \"").append(esc(bootTimestampIso)).append("\",\n");
    sb.append("  \"isFmsMatch\": ").append(isFmsMatch).append(",\n");
    sb.append("  \"syncStatus\": \"").append(esc(syncStatus)).append("\",\n");
    sb.append("  \"watchAlertCount\": ").append(watchAlertCount).append(",\n");
    sb.append(voiceNotesBlock).append("\n");
    sb.append("}\n");
    Files.writeString(metaFile, sb.toString(), StandardCharsets.UTF_8);
  }

  private static void deleteRecursivelyQuietly(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
      }
    } catch (IOException ignored) {
    }
  }

  private static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String unescapeJson(String s) {
    return s == null ? "" : s.replace("\\\"", "\"").replace("\\\\", "\\");
  }
}
