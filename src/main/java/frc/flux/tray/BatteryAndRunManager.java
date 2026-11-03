package frc.flux.tray;

import frc.flux.tray.MultiMicAudioRecorder.VoiceCue;
import frc.lib.fluxfig.FluxFig;
import frc.lib.fluxfig.FluxFig.BatterySpec;
import frc.lib.fluxfig.FluxSystemSnapshot;
import frc.lib.fluxfig.FluxSystemSnapshot.WatchDecision;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Coordinates Named Battery tracking ({@code 2026-B01..B12}) and the two post-run telemetry
 * collection workflows:
 *
 * <ol>
 *   <li><b>Practice Mode (Direct Robot Wi-Fi):</b> Once {@code Disabled} for {@code >= 3.0 s},
 *       automatically pulls {@code .wpilog}, {@code .hoot}, {@code FRC_UserProgram.log}, {@code
 *       cam-snapshots/*.jpg}, and {@code system-snapshot.txt} over SFTP/SSH, pairs them with local
 *       {@code .dslog}/{@code .dsevents} and {@code driver-audio.ogg}, evaluates all 14 SystemCore
 *       watch metrics, and writes the complete run bundle to {@code
 *       FLUX-10413-Hub/04_Practice_And_Match_Logs/}.
 *   <li><b>Competition FMS Mode ("Capture on Field, Sync in Pit"):</b> At the field (when FMS
 *       blocks Port 22 and the laptop must unplug immediately), saves {@code driver-audio.ogg} +
 *       local {@code .dslog}/{@code .dsevents} + {@code run-meta.json} ({@code syncStatus =
 *       "PENDING_ROBOT_LOGS"}). As soon as the robot reconnects in the pit, {@link
 *       #syncPendingFmsRuns(RobotTransport)} pulls the robot logs + {@code system-snapshot.txt} and
 *       completes the bundle.
 * </ol>
 */
public final class BatteryAndRunManager {

  public static final String DEFAULT_HUB_DIR_NAME = "FLUX-10413-Hub";
  public static final String DEFAULT_LOGS_SUBDIR_NAME = "04_Practice_And_Match_Logs";
  private static final DateTimeFormatter ZIP_TS_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.US);

  public record CompletedRunBundle(
      String runId,
      Path runDir,
      Path zipArchive,
      String batteryName,
      String syncStatus,
      FluxSystemSnapshot systemSnapshot,
      List<WatchDecision> watchDecisions) {}

  private final Path hubRootDir;
  private final RobotSafetyLockout lockout;
  private final MultiMicAudioRecorder audioRecorder;
  private volatile String selectedBatteryName = null;
  private volatile String lastSeenBootEpochId = null;
  private volatile boolean batteryPromptPending = false;

  public BatteryAndRunManager(
      Path hubRootDir, RobotSafetyLockout lockout, MultiMicAudioRecorder audioRecorder) {
    this.hubRootDir = hubRootDir;
    this.lockout = lockout;
    this.audioRecorder = audioRecorder;
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

  /**
   * Checks the robot's boot epoch ID and flags {@code batteryPromptPending = true} whenever the
   * robot has power-cycled (indicating a battery swap).
   */
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

  /**
   * Executes Practice Mode post-run log pull once the robot has been {@code Disabled} for at least
   * {@code 3.0 seconds}, writing the run bundle to {@code
   * <hubRootDir>/04_Practice_And_Match_Logs/<runId>/} and packaging {@code
   * match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip} by default.
   */
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

  /**
   * Executes Practice Mode post-run log pull and packages the resulting run directory into a
   * single {@code .zip} archive (using {@code customOutputDir} and {@code customZipFileName} when
   * provided, or defaulting to {@code <hubRootDir>/04_Practice_And_Match_Logs/} and {@code
   * match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip}).
   */
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
    if (!lockout.isReadyForAutoLogPull(nowMs)) {
      throw new IllegalStateException(
          "Robot must be Disabled for >= 3.0 seconds and not on FMS before pulling practice logs.");
    }
    lockout.beginTransferOrThrow("pullPracticeRunLogs:" + runId);
    try {
      Path baseLogsDir =
          (customOutputDir != null)
              ? customOutputDir
              : hubRootDir.resolve(DEFAULT_LOGS_SUBDIR_NAME);
      Path runDir = baseLogsDir.resolve(runId);
      Files.createDirectories(runDir);

      audioRecorder.flushAudioFile(runDir);
      String batteryName = resolveEffectiveBatteryName();

      copyLocalDsLogs(localDsLogDir, runDir);

      robot.downloadAllLogs(runDir);
      Path metaPath = runDir.resolve("run-meta.json");
      Map<String, String> robotMeta = readExistingRunMeta(metaPath);
      String effectiveRobotId =
          (robotName != null && !robotName.isBlank())
              ? robotName
              : robotMeta.getOrDefault("robotId", "COMP_2027");
      String profile = robotMeta.getOrDefault("profile", effectiveRobotId);
      String effectiveConfigSha =
          (configSha != null && !configSha.isBlank())
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

      // Post-Run Auto-Pull Safety Net: if /tmp/fluxfig-tuned-snapshot.txt exists on the robot,
      // archive it into runDir/tuned-snapshot.txt so floor tuning edits are never lost.
      archiveRemoteTunedSnapshotIfPresent(robot, runDir);

      FluxSystemSnapshot snapshot = robot.captureSystemSnapshot();
      Files.writeString(
          runDir.resolve("system-snapshot.txt"), snapshot.serialize(), StandardCharsets.UTF_8);
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
   * Competition FMS Step 1 ("Capture on Field"): Saves 3-headset {@code driver-audio.ogg}, local
   * {@code .dslog}/{@code .dsevents}, and {@code run-meta.json} ({@code PENDING_ROBOT_LOGS}) at the
   * field when FMS blocks Port 22 and the drive crew unplugs immediately.
   */
  public CompletedRunBundle captureFieldSideFmsRun(
      String runId,
      String robotName,
      String gitSha,
      String configSha,
      Path localDsLogDir)
      throws IOException {
    Path runDir = hubRootDir.resolve("04_Practice_And_Match_Logs").resolve(runId);
    Files.createDirectories(runDir);

    audioRecorder.flushAudioFile(runDir);
    String batteryName = resolveEffectiveBatteryName();
    copyLocalDsLogs(localDsLogDir, runDir);

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

  /**
   * Competition FMS Step 2 ("Sync in Pit"): Automatically finds all {@code PENDING_ROBOT_LOGS}
   * folders in {@code 04_Practice_And_Match_Logs/} when the laptop reconnects to the robot back in
   * the pit, pulls the matching {@code .wpilog}, {@code .hoot}, {@code cam-snapshots/*.jpg}, and
   * {@code system-snapshot.txt}, updates {@code syncStatus} to {@code COMPLETE}, and packages each
   * completed bundle into {@code match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip}.
   */
  public List<CompletedRunBundle> syncPendingFmsRuns(RobotTransport robot) throws IOException {
    lockout.beginTransferOrThrow("syncPendingFmsRuns");
    try {
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
          FluxSystemSnapshot snapshot = robot.captureSystemSnapshot();
          Files.writeString(
              runDir.resolve("system-snapshot.txt"),
              snapshot.serialize(),
              StandardCharsets.UTF_8);
          List<WatchDecision> decisions = snapshot.evaluate();

          String runId = fieldMeta.getOrDefault("runId", runDir.getFileName().toString());
          String robotId =
              fieldMeta.getOrDefault(
                  "robotId",
                  fieldMeta.getOrDefault(
                      "robotName", robotMeta.getOrDefault("robotId", "COMP_2027")));
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

  /**
   * Returns the canonical default archive filename {@code
   * match-logs_<yyyy-MM-dd>_<HHmmss>_<ROBOT>.zip}.
   */
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

  /**
   * Packages all files inside {@code runDir} into {@code targetZipFile} under the top-level folder
   * {@code <runDir.getFileName()>/} so the archive can be passed directly to {@code
   * FluxProcessor} ({@code java -jar dist/flux-processor.jar <zip>}).
   */
  public static Path packageRunDirectoryToZip(Path runDir, Path targetZipFile) throws IOException {
    if (targetZipFile.getParent() != null) {
      Files.createDirectories(targetZipFile.getParent());
    }
    String rootPrefix = runDir.getFileName().toString() + "/";
    try (OutputStream fos = Files.newOutputStream(targetZipFile);
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
    }
    return targetZipFile;
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

  private static void copyLocalDsLogs(Path localDsLogDir, Path runDir) throws IOException {
    if (localDsLogDir == null || !Files.isDirectory(localDsLogDir)) {
      return;
    }
    try (Stream<Path> s = Files.list(localDsLogDir)) {
      for (Path f :
          s.filter(
                  p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".dslog") || n.endsWith(".dsevents");
                  })
              .toList()) {
        Files.copy(f, runDir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      }
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

  private static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
