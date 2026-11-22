package frc.flux.tray;

import frc.lib.fluxfig.FluxFig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Atomic {@code FluxFig} config pusher, {@code STABLE}/{@code EXPERIMENTAL} behavioral preset
 * switcher, live-tuning toggle, and tuning snapshot promoter for {@code FluxTray.jar}.
 */
public final class ConfigSwitcher {

  /**
   * Backup of {@code deploy/configs} captured before the first behavioral preset overwrite on a
   * controller that does not yet have {@code jars/prev_stable_deploy/configs/parameters.csv} (e.g.,
   * a controller loaded via {@code ./gradlew deploy} or an {@code EXPERIMENTAL} package).
   */
  public static final String PRE_PRESET_BASE_DIR = "jars/prev_preset_base_configs";

  private static final String PREV_STABLE_CONFIGS_DIR = "jars/prev_stable_deploy/configs";

  /** Behavioral delta preset CSV discovered under {@code code/configs/presets/*.csv}. */
  public record ConfigPresetInfo(String name, String displayName, Path filePath) {}

  /** Outcome of switching the connected robot's active config channel or behavioral preset. */
  public record ConfigSwitchResult(
      String robotId,
      String channel,
      String preset,
      FluxFig.Mode mode,
      String configSha256) {}

  private final RobotSafetyLockout lockout;

  public ConfigSwitcher(RobotSafetyLockout lockout) {
    this.lockout = lockout;
  }

  /**
   * Scans {@code <codeDir>/configs/presets/*.csv} for behavioral delta presets shown in the {@code
   * Switch Config} menu.
   */
  public static List<ConfigPresetInfo> discoverBehavioralPresets(Path codeDir) {
    if (codeDir == null) {
      return List.of();
    }
    Path presetsDir = codeDir.resolve("configs").resolve("presets");
    if (!Files.isDirectory(presetsDir)) {
      return List.of();
    }
    List<ConfigPresetInfo> presets = new ArrayList<>();
    try (Stream<Path> s = Files.list(presetsDir)) {
      for (Path p :
          s.filter(Files::isRegularFile)
              .sorted(Comparator.comparing(f -> f.getFileName().toString()))
              .toList()) {
        String fileName = p.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".") || !lower.endsWith(".csv")) {
          continue;
        }
        String stem = fileName.substring(0, fileName.length() - 4);
        presets.add(new ConfigPresetInfo(stem, stem + " (EXPERIMENTAL)", p));
      }
    } catch (IOException ignored) {
    }
    return presets;
  }

  /**
   * Atomically pushes {@code FluxFig} CSV configs in {@link FluxFig.Mode#PRODUCTION} mode after
   * local {@link FluxFig#validateStrict()} and restarts robot code so boot-time gains reload.
   */
  public String pushFluxFigConfigs(
      Path configDir, String activeProfileOverlay, RobotTransport robot) throws IOException {
    return pushFluxFigConfigs(configDir, activeProfileOverlay, FluxFig.Mode.PRODUCTION, robot);
  }

  /**
   * Atomically pushes {@code FluxFig} CSV configs + {@code active-profile.txt} as a single staged
   * directory swap, clears any stale tuned snapshot, and restarts robot code.
   */
  public String pushFluxFigConfigs(
      Path configDir, String activeProfileOverlay, FluxFig.Mode mode, RobotTransport robot)
      throws IOException {
    RobotSafetyLockout.TransferLease lease =
        lockout.beginTransferLeaseOrThrow(
            "pushFluxFigConfigs:" + activeProfileOverlay + ":" + mode);
    try (lease) {
      robot.assertRobotDisabledForTransfer("pushFluxFigConfigs");
      String effectiveProfile = resolveIdentifiedProfileOrThrow(activeProfileOverlay, robot);
      FluxFig fig = new FluxFig(configDir);
      fig.setActiveProfile(effectiveProfile);
      List<String> violations = fig.validateStrict();
      if (!violations.isEmpty()) {
        throw new IllegalStateException("FluxFig safety gate blocked config push: " + violations);
      }
      String sha = fig.getConfigSha256();
      String channel = (mode == FluxFig.Mode.TUNING) ? "EXPERIMENTAL" : "STABLE";
      String preset = (mode == FluxFig.Mode.TUNING) ? "live-tuning" : "STABLE";
      String directive =
          FluxFig.formatActiveProfileDirective(effectiveProfile, mode, sha, channel, preset);
      replaceRemoteConfigsFromLocalDirAtomic(configDir, directive, robot);
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      robot.restartRobotCode();
      return sha;
    }
  }

  /**
   * Restores the robot's locked {@code STABLE} config base from {@code
   * jars/prev_stable_deploy/configs} (or {@link #PRE_PRESET_BASE_DIR} when the controller was
   * loaded without a {@code STABLE} package), locks {@code Mode.PRODUCTION}, clears any temporary
   * tuned snapshot, and restarts robot code. Fails closed if neither locked base exists.
   */
  public ConfigSwitchResult switchToStableConfig(RobotTransport robot) throws IOException {
    RobotSafetyLockout.TransferLease lease =
        lockout.beginTransferLeaseOrThrow("switchToStableConfig");
    try (lease) {
      robot.assertRobotDisabledForTransfer("switchToStableConfig");
      String robotId = resolveIdentifiedProfileOrThrow(null, robot);
      String baseDir;
      if (robot.remoteFileExists(PREV_STABLE_CONFIGS_DIR + "/parameters.csv")) {
        baseDir = PREV_STABLE_CONFIGS_DIR;
      } else if (robot.remoteFileExists(PRE_PRESET_BASE_DIR + "/parameters.csv")) {
        baseDir = PRE_PRESET_BASE_DIR;
      } else {
        throw new IOException(
            "Cannot restore STABLE config: neither "
                + PREV_STABLE_CONFIGS_DIR
                + "/parameters.csv nor "
                + PRE_PRESET_BASE_DIR
                + "/parameters.csv exists on robot. Deploy a clean STABLE .fluxpkg.zip first.");
      }

      Map<String, String> baseCsvs = loadRemoteCsvDirectory(robot, baseDir);
      String sha = validateAndHashRemoteCsvs(baseCsvs, robotId);
      String directive =
          FluxFig.formatActiveProfileDirective(
              robotId, FluxFig.Mode.PRODUCTION, sha, "STABLE", "STABLE");
      replaceRemoteConfigsAtomic(baseCsvs, directive, robot);
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      robot.restartRobotCode();
      return new ConfigSwitchResult(robotId, "STABLE", "STABLE", FluxFig.Mode.PRODUCTION, sha);
    }
  }

  /**
   * Merges a behavioral delta preset CSV onto the connected robot's locked hardware base ({@code
   * jars/prev_stable_deploy/configs}, or {@link #PRE_PRESET_BASE_DIR} captured prior to the first
   * preset switch), validates the complete config set strictly locally, atomically replaces {@code
   * deploy/configs} with all base CSVs + merged {@code parameters.csv} + {@code
   * active-profile.txt} ({@code channel=EXPERIMENTAL}), clears stale tuned snapshots, and restarts
   * robot code.
   */
  public ConfigSwitchResult switchToBehavioralPreset(
      Path presetCsv, FluxFig.Mode mode, RobotTransport robot) throws IOException {
    if (presetCsv == null || !Files.isRegularFile(presetCsv)) {
      throw new IOException("Behavioral preset CSV not found: " + presetCsv);
    }
    FluxFig.Mode effectiveMode = (mode != null) ? mode : FluxFig.Mode.PRODUCTION;
    String fileName = presetCsv.getFileName().toString();
    String presetName =
        fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;
    String presetText = Files.readString(presetCsv, StandardCharsets.UTF_8);

    RobotSafetyLockout.TransferLease lease =
        lockout.beginTransferLeaseOrThrow("switchToBehavioralPreset:" + presetName);
    try (lease) {
      robot.assertRobotDisabledForTransfer("switchToBehavioralPreset:" + presetName);
      String robotId = resolveIdentifiedProfileOrThrow(null, robot);
      String baseDir = resolveOrSnapshotPresetBaseDir(robot);
      Map<String, String> baseCsvs = loadRemoteCsvDirectory(robot, baseDir);
      String baseParams = baseCsvs.get("parameters.csv");
      if (baseParams == null || baseParams.isBlank()) {
        throw new IOException("Robot has no base parameters.csv under " + baseDir);
      }

      String mergedParams =
          FluxFig.mergeBehavioralPresetParametersCsv(baseParams, presetText, robotId);
      Map<String, String> candidateCsvs = new LinkedHashMap<>(baseCsvs);
      candidateCsvs.put("parameters.csv", mergedParams);
      String mergedSha = validateAndHashRemoteCsvs(candidateCsvs, robotId);

      String directive =
          FluxFig.formatActiveProfileDirective(
              robotId, effectiveMode, mergedSha, "EXPERIMENTAL", presetName);
      replaceRemoteConfigsAtomic(candidateCsvs, directive, robot);
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      robot.restartRobotCode();
      return new ConfigSwitchResult(
          robotId, "EXPERIMENTAL", presetName, effectiveMode, mergedSha);
    }
  }

  /**
   * Switches the connected robot into {@code Mode.TUNING} ({@code channel=EXPERIMENTAL}, {@code
   * preset=live-tuning}) without modifying any CSVs or {@code jars/prev_stable_deploy/}, wipes any
   * stale {@code /tmp/fluxfig-tuned-snapshot.txt}, and restarts robot code so NT4 tuning activates.
   */
  public ConfigSwitchResult switchToLiveTuning(RobotTransport robot) throws IOException {
    RobotSafetyLockout.TransferLease lease =
        lockout.beginTransferLeaseOrThrow("switchToLiveTuning");
    try (lease) {
      robot.assertRobotDisabledForTransfer("switchToLiveTuning");
      String robotId = resolveIdentifiedProfileOrThrow(null, robot);
      Map<String, String> remoteCsvs = loadRemoteCsvDirectory(robot, "deploy/configs");
      String sha =
          remoteCsvs.containsKey("parameters.csv")
              ? validateAndHashRemoteCsvs(remoteCsvs, robotId)
              : "";
      robot.writeRemoteText(
          "deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE,
          FluxFig.formatActiveProfileDirective(
              robotId, FluxFig.Mode.TUNING, sha, "EXPERIMENTAL", "live-tuning"));
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      robot.restartRobotCode();
      return new ConfigSwitchResult(
          robotId, "EXPERIMENTAL", "live-tuning", FluxFig.Mode.TUNING, sha);
    }
  }

  /**
   * Switches {@code deploy/configs/active-profile.txt} between {@link FluxFig.Mode#PRODUCTION} and
   * {@link FluxFig.Mode#TUNING}, preserving the robot's actual identity and any active {@code
   * EXPERIMENTAL} behavioral preset, and restarts robot code so boot-time gains reload.
   */
  public FluxFig.ActiveProfileDirective switchActiveMode(
      String activeProfileOverlay,
      FluxFig.Mode mode,
      String expectedConfigSha256,
      RobotTransport robot)
      throws IOException {
    RobotSafetyLockout.TransferLease lease =
        lockout.beginTransferLeaseOrThrow(
            "switchActiveMode:" + activeProfileOverlay + ":" + mode);
    try (lease) {
      robot.assertRobotDisabledForTransfer("switchActiveMode");
      String profile = activeProfileOverlay;
      String sha = expectedConfigSha256;
      FluxFig.ActiveProfileDirective existing = null;
      String existingRaw = robot.readRemoteText("deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE);
      if (existingRaw != null && !existingRaw.isBlank()) {
        existing = FluxFig.parseActiveProfileDirective(existingRaw);
        if (profile == null || profile.isBlank()) {
          profile = existing.profile();
        }
        if (sha == null || sha.isBlank()) {
          sha = existing.expectedConfigSha256();
        }
      }
      String effectiveProfile = resolveIdentifiedProfileOrThrow(profile, robot);
      boolean hasActiveBehavioralPreset =
          existing != null
              && existing.preset() != null
              && !existing.preset().isBlank()
              && !"STABLE".equalsIgnoreCase(existing.preset())
              && !"live-tuning".equalsIgnoreCase(existing.preset());
      String channel;
      String preset;
      if (hasActiveBehavioralPreset) {
        channel = "EXPERIMENTAL";
        preset = existing.preset();
      } else {
        channel = (mode == FluxFig.Mode.TUNING) ? "EXPERIMENTAL" : "STABLE";
        preset = (mode == FluxFig.Mode.TUNING) ? "live-tuning" : "STABLE";
      }
      String directive =
          FluxFig.formatActiveProfileDirective(effectiveProfile, mode, sha, channel, preset);
      robot.writeRemoteText("deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE, directive);
      robot.restartRobotCode();
      return FluxFig.parseActiveProfileDirective(directive);
    }
  }

  /** Harvests {@code /tmp/fluxfig-tuned-snapshot.txt}, promotes {@code vN+1}, atomically pushes CSVs, and restarts. */
  public FluxFig.ReleaseEntry promoteRemoteTuningSnapshot(
      Path hubConfigsDir, String author, String notes, RobotTransport robot) throws IOException {
    String remoteTmpPath = FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH;
    String snapshotText;
    RobotSafetyLockout.TransferLease readLease =
        lockout.beginTransferLeaseOrThrow("promoteRemoteTuningSnapshot");
    try (readLease) {
      robot.assertRobotDisabledForTransfer("promoteRemoteTuningSnapshot");
      snapshotText = robot.readRemoteText(remoteTmpPath);
      if (snapshotText == null || snapshotText.isBlank()) {
        throw new IOException("No tuned snapshot found on robot at " + remoteTmpPath);
      }
    }

    FluxFig parsedSnapshot = FluxFig.fromEffectiveSnapshot(snapshotText);
    String activeProfile = parsedSnapshot.getActiveProfile();
    FluxFig.ReleaseEntry release =
        FluxFig.promoteSnapshotToDirectory(snapshotText, hubConfigsDir, author, notes);

    RobotSafetyLockout.TransferLease pushLease =
        lockout.beginTransferLeaseOrThrow("finalizePromotedRelease:" + release.version());
    try (pushLease) {
      robot.assertRobotDisabledForTransfer("finalizePromotedRelease");
      String directive =
          FluxFig.formatActiveProfileDirective(
              activeProfile, FluxFig.Mode.PRODUCTION, release.configSha256(), "STABLE", "STABLE");
      replaceRemoteConfigsFromLocalDirAtomic(hubConfigsDir, directive, robot);
      robot.deleteRemoteFile(remoteTmpPath);
      robot.restartRobotCode();
    }
    return release;
  }

  private static String resolveOrSnapshotPresetBaseDir(RobotTransport robot) throws IOException {
    if (robot.remoteFileExists(PREV_STABLE_CONFIGS_DIR + "/parameters.csv")) {
      return PREV_STABLE_CONFIGS_DIR;
    }
    if (robot.remoteFileExists(PRE_PRESET_BASE_DIR + "/parameters.csv")) {
      return PRE_PRESET_BASE_DIR;
    }
    if (!robot.remoteFileExists("deploy/configs/parameters.csv")) {
      throw new IOException("Robot has no base parameters.csv under deploy/configs");
    }
    if (!robot.copyRemoteDirectory("deploy/configs", PRE_PRESET_BASE_DIR)) {
      throw new IOException(
          "Failed to snapshot deploy/configs into "
              + PRE_PRESET_BASE_DIR
              + " before applying behavioral preset.");
    }
    return PRE_PRESET_BASE_DIR;
  }

  private static String resolveIdentifiedProfileOrThrow(
      String explicitProfile, RobotTransport robot) throws IOException {
    if (explicitProfile != null && !explicitProfile.isBlank()) {
      return explicitProfile.trim();
    }
    String detected = robot.detectRobotIdentity();
    if (RobotTransport.isUnknownRobotIdentity(detected)) {
      if (robot.isBootstrapAllowed()) {
        return "DEFAULT";
      }
      throw new IllegalStateException(
          "Robot identity is unknown — write the robot name (e.g. CHASSIS_3, FUEL, CORAL) to /home/lvuser/robot-identity.txt first.");
    }
    return detected.trim();
  }

  private static Map<String, String> loadRemoteCsvDirectory(
      RobotTransport robot, String remoteConfigsDir) throws IOException {
    Map<String, String> csvs = new LinkedHashMap<>();
    List<String> names = new ArrayList<>(robot.listRemoteCsvFiles(remoteConfigsDir));
    if (!names.contains("parameters.csv")) {
      names.add("parameters.csv");
    }
    for (String fallbackName :
        List.of(
            "mechanisms.csv",
            "swerve_modules.csv",
            "cameras.csv",
            "batteries.csv",
            "range_table.csv")) {
      if (!names.contains(fallbackName)) {
        names.add(fallbackName);
      }
    }
    names.sort(String::compareTo);
    for (String name : names) {
      String content = robot.readRemoteText(remoteConfigsDir + "/" + name);
      if (content != null && !content.isBlank()) {
        csvs.put(name, content);
      }
    }
    return csvs;
  }

  private static String validateAndHashRemoteCsvs(
      Map<String, String> csvFiles, String activeProfile) throws IOException {
    if (!csvFiles.containsKey("parameters.csv")) {
      throw new IOException("Robot config directory is missing required parameters.csv");
    }
    Path tempDir = Files.createTempDirectory("fluxtray-cfg-validate-");
    try {
      for (Map.Entry<String, String> e : csvFiles.entrySet()) {
        Files.writeString(tempDir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
      }
      FluxFig fig = new FluxFig(tempDir);
      fig.setActiveProfile(activeProfile);
      List<String> violations = fig.validateStrict();
      if (!violations.isEmpty()) {
        throw new IllegalStateException("FluxFig safety gate blocked config switch: " + violations);
      }
      return fig.getConfigSha256();
    } finally {
      PackageDeployer.deleteRecursively(tempDir);
    }
  }

  private static void replaceRemoteConfigsAtomic(
      Map<String, String> csvFiles, String activeProfileDirective, RobotTransport robot)
      throws IOException {
    Path stagedDir = Files.createTempDirectory("fluxtray-cfg-stage-");
    try {
      for (Map.Entry<String, String> e : csvFiles.entrySet()) {
        Files.writeString(stagedDir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
      }
      Files.writeString(
          stagedDir.resolve(FluxFig.ACTIVE_PROFILE_FILE),
          activeProfileDirective,
          StandardCharsets.UTF_8);
      robot.replaceRemoteDirectoryAtomic(stagedDir, "deploy/configs");
    } finally {
      PackageDeployer.deleteRecursively(stagedDir);
    }
  }

  private static void replaceRemoteConfigsFromLocalDirAtomic(
      Path configDir, String activeProfileDirective, RobotTransport robot) throws IOException {
    Path stagedDir = Files.createTempDirectory("fluxtray-cfg-push-");
    try {
      try (Stream<Path> s = Files.list(configDir)) {
        for (Path csv :
            s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                .sorted()
                .toList()) {
          Files.copy(csv, stagedDir.resolve(csv.getFileName()));
        }
      }
      Files.writeString(
          stagedDir.resolve(FluxFig.ACTIVE_PROFILE_FILE),
          activeProfileDirective,
          StandardCharsets.UTF_8);
      robot.replaceRemoteDirectoryAtomic(stagedDir, "deploy/configs");
    } finally {
      PackageDeployer.deleteRecursively(stagedDir);
    }
  }
}
