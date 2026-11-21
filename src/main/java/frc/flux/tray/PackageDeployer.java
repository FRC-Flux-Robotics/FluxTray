package frc.flux.tray;

import frc.lib.fluxfig.FluxFig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Atomic SFTP Package / {@code FluxFig} Config Switcher, 5-Slot Full-State Rollback Ring ({@code
 * prev_1..5.jar} + {@code prev_1..5_deploy/} + {@code prev_stable}), and Live JVM Restart Manager.
 */
public final class PackageDeployer {

  public static final int ROLLBACK_RING_SLOTS = 5;
  public static final String DEFAULT_JAR_NAME = "frcUserProgram.jar";
  private static final Pattern ROBOT_COMMAND_JAR_PATTERN =
      Pattern.compile("-jar\\s+(?:\"([^\"]+)\"|'([^']+)'|(\\S+))");

  public record PackageManifest(
      String packageName,
      String targetPlatform,
      String channel,
      String gitSha,
      boolean gitDirty,
      String configSha,
      String authorLaptop) {}

  public record DeployStamp(
      String packageName,
      String channel,
      String gitSha,
      String configSha,
      String deployedByLaptop,
      long timestampEpochMs) {

    public String toJson() {
      return "{\n"
          + "  \"packageName\": \""
          + escape(packageName)
          + "\",\n"
          + "  \"channel\": \""
          + escape(channel)
          + "\",\n"
          + "  \"gitSha\": \""
          + escape(gitSha)
          + "\",\n"
          + "  \"configSha\": \""
          + escape(configSha)
          + "\",\n"
          + "  \"deployedByLaptop\": \""
          + escape(deployedByLaptop)
          + "\",\n"
          + "  \"timestampEpochMs\": "
          + timestampEpochMs
          + "\n}\n";
    }

    public static DeployStamp fromJson(String json) {
      if (json == null || json.isBlank()) {
        return null;
      }
      Map<String, String> m = parseFlatJson(json);
      long ts = 0L;
      try {
        ts = Long.parseLong(m.getOrDefault("timestampEpochMs", "0"));
      } catch (NumberFormatException ignored) {
      }
      return new DeployStamp(
          m.getOrDefault("packageName", ""),
          m.getOrDefault("channel", ""),
          m.getOrDefault("gitSha", ""),
          m.getOrDefault("configSha", ""),
          m.getOrDefault("deployedByLaptop", ""),
          ts);
    }
  }

  /** Classification of what robot code is currently installed on the connected controller. */
  public enum CodeGeneration {
    FLUX_2027_PACKAGE,
    FLUX_2027_UNSTAMPED,
    LEGACY_OR_NON_FLUX,
    UNREACHABLE
  }

  /** Read-only snapshot of the robot controller's installed code, active JAR, and heartbeat. */
  public record RobotCodeStatus(
      CodeGeneration generation,
      String platform,
      String activeJar,
      RobotTransport.RobotStateHeartbeat heartbeat,
      DeployStamp deployStamp,
      boolean hasPrevStable,
      RobotTransport.RobotDiagnostics diagnostics) {

    public boolean isLegacyOrNonFlux() {
      return generation == CodeGeneration.LEGACY_OR_NON_FLUX;
    }

    public String summary() {
      String freePart =
          (diagnostics != null && diagnostics.internalFreeMb() >= 0L)
              ? " free=" + diagnostics.internalFreeMb() + "MB"
              : "";
      return switch (generation) {
        case UNREACHABLE -> "UNREACHABLE (" + platform + ")";
        case FLUX_2027_PACKAGE ->
            "FLUX 2027 package '"
                + deployStamp.packageName()
                + "' ["
                + deployStamp.channel()
                + "] git="
                + deployStamp.gitSha()
                + " jar="
                + activeJar
                + (heartbeat != null && heartbeat.present()
                    ? " heartbeat=" + heartbeat.state()
                    : " heartbeat=ABSENT")
                + freePart;
        case FLUX_2027_UNSTAMPED ->
            "FLUX 2027 (unstamped/Gradle deploy, no deploy-stamp.json) jar="
                + activeJar
                + " heartbeat="
                + heartbeat.state()
                + freePart;
        case LEGACY_OR_NON_FLUX ->
            "LEGACY / NON-FLUX (e.g. New_Robot 2026: no /tmp/flux-robot-state, no deploy-stamp.json) jar="
                + activeJar
                + freePart;
      };
    }
  }

  /**
   * Read-only inspection of the robot controller to distinguish 2027 FLUX code from legacy {@code
   * New_Robot} code without requiring {@code /tmp/flux-robot-state} or mutating any files.
   */
  public static RobotCodeStatus inspectRobotCode(RobotTransport robot) throws IOException {
    String platform = robot.controllerPlatform();
    if (!robot.isReachable()) {
      return new RobotCodeStatus(
          CodeGeneration.UNREACHABLE,
          platform,
          "",
          RobotTransport.RobotStateHeartbeat.absent(),
          null,
          false,
          RobotTransport.RobotDiagnostics.empty());
    }
    String activeJar = robot.resolveActiveJarRemotePath();
    RobotTransport.RobotStateHeartbeat hb = robot.readRobotStateHeartbeat();
    DeployStamp stamp = DeployStamp.fromJson(robot.readRemoteText("deploy/deploy-stamp.json"));
    boolean hasPrevStable = robot.remoteFileExists("jars/prev_stable.jar");
    RobotTransport.RobotDiagnostics diag = robot.readDiagnostics();
    CodeGeneration gen;
    if (stamp != null) {
      gen = CodeGeneration.FLUX_2027_PACKAGE;
    } else if (hb != null && hb.present()) {
      gen = CodeGeneration.FLUX_2027_UNSTAMPED;
    } else {
      gen = CodeGeneration.LEGACY_OR_NON_FLUX;
    }
    return new RobotCodeStatus(gen, platform, activeJar, hb, stamp, hasPrevStable, diag);
  }

  /** Behavioral delta preset CSV discovered under {@code code/configs/presets/*.csv}. */
  public record ConfigPresetInfo(String name, String displayName, Path filePath) {}

  /** Outcome of switching the connected robot's active config channel or behavioral preset. */
  public record ConfigSwitchResult(
      String robotId,
      String channel,
      String preset,
      FluxFig.Mode mode,
      String configSha256) {}

  private static final class InProgressDeploy {
    final String packageSha256;
    final boolean hadPreviousJar;
    boolean ringRotated;
    boolean jarUploaded;
    boolean deploySwapped;
    boolean stableSnapshotSaved;
    DeployStamp stamp;

    InProgressDeploy(String packageSha256, boolean hadPreviousJar) {
      this.packageSha256 = packageSha256;
      this.hadPreviousJar = hadPreviousJar;
    }
  }

  private final RobotSafetyLockout lockout;
  private final String laptopId;
  private final Map<String, InProgressDeploy> inProgressByTarget = new LinkedHashMap<>();

  public PackageDeployer(RobotSafetyLockout lockout, String laptopId) {
    this.lockout = lockout;
    this.laptopId = laptopId;
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
   * Extracts the exact robot JAR filename executed by {@code /home/lvuser/robotCommand} (for
   * example {@code /home/lvuser/Flux-RoboRIO-2027.jar} &rarr; {@code Flux-RoboRIO-2027.jar}),
   * falling back to {@code frcUserProgram.jar} when absent.
   */
  public static String parseJarPathFromRobotCommand(String robotCommandContent) {
    if (robotCommandContent == null || robotCommandContent.isBlank()) {
      return DEFAULT_JAR_NAME;
    }
    Matcher m = ROBOT_COMMAND_JAR_PATTERN.matcher(robotCommandContent);
    if (!m.find()) {
      return DEFAULT_JAR_NAME;
    }
    String raw =
        m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
    if (raw == null || raw.isBlank()) {
      return DEFAULT_JAR_NAME;
    }
    String cleaned = raw.trim();
    if (cleaned.startsWith("/home/lvuser/")) {
      return cleaned.substring("/home/lvuser/".length());
    }
    if (cleaned.startsWith("/home/systemcore/")) {
      return cleaned.substring("/home/systemcore/".length());
    }
    return cleaned;
  }

  /** Builds a self-contained {@code .fluxpkg.zip} package archive for testing or release. */
  public static Path createFluxPkgZip(
      Path outputZip, PackageManifest manifest, Path jarFile, Path configDir) throws IOException {
    Files.createDirectories(outputZip.getParent());
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputZip))) {
      String manifestJson =
          "{\n"
              + "  \"packageName\": \""
              + escape(manifest.packageName())
              + "\",\n"
              + "  \"targetPlatform\": \""
              + escape(manifest.targetPlatform())
              + "\",\n"
              + "  \"channel\": \""
              + escape(manifest.channel())
              + "\",\n"
              + "  \"gitSha\": \""
              + escape(manifest.gitSha())
              + "\",\n"
              + "  \"gitDirty\": "
              + manifest.gitDirty()
              + ",\n"
              + "  \"configSha\": \""
              + escape(manifest.configSha())
              + "\",\n"
              + "  \"authorLaptop\": \""
              + escape(manifest.authorLaptop())
              + "\"\n}\n";
      putTextEntry(zos, "manifest.json", manifestJson);

      zos.putNextEntry(new ZipEntry("frcUserProgram.jar"));
      Files.copy(jarFile, zos);
      zos.closeEntry();

      if (configDir != null && Files.isDirectory(configDir)) {
        boolean hasConfigsSubdir = Files.isDirectory(configDir.resolve("configs"));
        try (Stream<Path> walk = Files.walk(configDir)) {
          for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
            String rel = configDir.relativize(file).toString().replace('\\', '/');
            String entryName;
            if (!hasConfigsSubdir && !rel.contains("/") && rel.endsWith(".csv")) {
              entryName = "deploy/configs/" + rel;
            } else if (rel.startsWith("deploy/")) {
              entryName = rel;
            } else {
              entryName = "deploy/" + rel;
            }
            zos.putNextEntry(new ZipEntry(entryName));
            Files.copy(file, zos);
            zos.closeEntry();
          }
        }
      }
    }
    return outputZip;
  }

  /**
   * Locally validates a {@code .fluxpkg.zip} archive before touching the robot: verifies ZIP
   * integrity, strict fail-closed {@code manifest.json}, target platform match, {@code
   * frcUserProgram.jar} presence, required {@code deploy/configs/parameters.csv}, and {@link
   * FluxFig#validateStrict()}.
   */
  public static PackageManifest validatePackageArchiveLocally(
      Path fluxPkgZip, String expectedControllerPlatform) throws IOException {
    Path tempExtract = Files.createTempDirectory("fluxpkg-validate-");
    try {
      extractZip(fluxPkgZip, tempExtract);
      return validateExtractedPackage(
          tempExtract, fluxPkgZip.getFileName().toString(), expectedControllerPlatform);
    } finally {
      deleteRecursively(tempExtract);
    }
  }

  private static PackageManifest validateExtractedPackage(
      Path extractedRoot, String sourceName, String expectedControllerPlatform) throws IOException {
    Path manifestPath = extractedRoot.resolve("manifest.json");
    if (!Files.isRegularFile(manifestPath)) {
      throw new IOException("Missing manifest.json inside " + sourceName);
    }
    PackageManifest manifest =
        parseStrictManifest(Files.readString(manifestPath, StandardCharsets.UTF_8), sourceName);

    if (expectedControllerPlatform != null
        && !manifest.targetPlatform().equalsIgnoreCase(expectedControllerPlatform)) {
      throw new IllegalArgumentException(
          "Platform mismatch: package targets '"
              + manifest.targetPlatform()
              + "' but robot is '"
              + expectedControllerPlatform
              + "'");
    }

    Path jarPath = extractedRoot.resolve("frcUserProgram.jar");
    if (!Files.isRegularFile(jarPath)) {
      throw new IOException("Missing frcUserProgram.jar inside " + sourceName);
    }

    Path configDir = extractedRoot.resolve("deploy/configs");
    if (!Files.isDirectory(configDir) || !Files.isRegularFile(configDir.resolve("parameters.csv"))) {
      throw new IOException(
          "Invalid package " + sourceName + ": missing required deploy/configs/parameters.csv");
    }
    FluxFig fig = new FluxFig(configDir);
    List<String> errors = fig.validateStrict();
    if (!errors.isEmpty()) {
      throw new IllegalStateException("FluxFig strict validation failed: " + errors);
    }
    return manifest;
  }

  static PackageManifest parseStrictManifest(String rawJson, String sourceName) throws IOException {
    Map<String, String> mf = parseFlatJson(rawJson);
    if (mf.isEmpty()) {
      throw new IOException("Malformed manifest.json inside " + sourceName);
    }
    String packageName = requireNonBlankField(mf, "packageName", sourceName);
    String targetPlatform = requireNonBlankField(mf, "targetPlatform", sourceName);
    String channel = requireNonBlankField(mf, "channel", sourceName);
    if (!"STABLE".equalsIgnoreCase(channel) && !"EXPERIMENTAL".equalsIgnoreCase(channel)) {
      throw new IOException(
          "Malformed manifest.json inside "
              + sourceName
              + ": invalid channel '"
              + channel
              + "' (expected STABLE or EXPERIMENTAL)");
    }
    String gitSha = requireNonBlankField(mf, "gitSha", sourceName);
    String gitDirtyRaw = requireNonBlankField(mf, "gitDirty", sourceName);
    boolean gitDirty;
    if ("true".equalsIgnoreCase(gitDirtyRaw)) {
      gitDirty = true;
    } else if ("false".equalsIgnoreCase(gitDirtyRaw)) {
      gitDirty = false;
    } else {
      throw new IOException(
          "Malformed manifest.json inside "
              + sourceName
              + ": invalid gitDirty '"
              + gitDirtyRaw
              + "'");
    }
    String configSha = requireNonBlankField(mf, "configSha", sourceName);
    String authorLaptop = mf.getOrDefault("authorLaptop", "");
    return new PackageManifest(
        packageName, targetPlatform, channel, gitSha, gitDirty, configSha, authorLaptop);
  }

  private static String requireNonBlankField(
      Map<String, String> map, String key, String sourceName) throws IOException {
    String val = map.get(key);
    if (val == null || val.isBlank()) {
      throw new IOException(
          "Malformed manifest.json inside " + sourceName + ": missing required field '" + key + "'");
    }
    return val.trim();
  }

  /**
   * Atomically deploys a {@code .fluxpkg.zip} bundle onto the exact JAR executed by {@code
   * robotCommand}, stages and atomically swaps {@code deploy/}, rotates both JAR and {@code
   * deploy/} into the 5-slot rollback ring at most once per package hash (resuming retries at the
   * failed step), rolls back the JAR if {@code deploy/} fails, protects {@code prev_stable} from
   * dirty {@code EXPERIMENTAL} packages, and restarts robot code.
   */
  public synchronized DeployStamp deployPackage(
      Path fluxPkgZip, RobotTransport robot, boolean forceOverwriteOtherLaptop)
      throws IOException {
    lockout.beginTransferOrThrow("deployPackage:" + fluxPkgZip.getFileName());
    Path tempExtract = Files.createTempDirectory("fluxpkg-extract-");
    try {
      robot.assertRobotDisabledForTransfer("deployPackage:" + fluxPkgZip.getFileName());
      String pkgSha256 = DriveWatcher.sha256File(fluxPkgZip);
      extractZip(fluxPkgZip, tempExtract);
      PackageManifest manifest =
          validateExtractedPackage(
              tempExtract, fluxPkgZip.getFileName().toString(), robot.controllerPlatform());

      String activeJarTarget = robot.resolveActiveJarRemotePath();
      String resumeKey = buildDeployResumeKey(robot, activeJarTarget);
      InProgressDeploy progress = inProgressByTarget.get(resumeKey);
      boolean resumingSamePackage = progress != null && pkgSha256.equals(progress.packageSha256);

      if (!resumingSamePackage || (!progress.jarUploaded && !progress.deploySwapped)) {
        String existingStampJson = robot.readRemoteText("deploy/deploy-stamp.json");
        DeployStamp existing = DeployStamp.fromJson(existingStampJson);
        if (existing != null
            && !existing.deployedByLaptop().isEmpty()
            && !existing.deployedByLaptop().equalsIgnoreCase(laptopId)
            && !forceOverwriteOtherLaptop) {
          throw new IllegalStateException(
              "OVERWRITE GUARD: Robot currently holds package '"
                  + existing.packageName()
                  + "' (git="
                  + existing.gitSha()
                  + ") deployed by '"
                  + existing.deployedByLaptop()
                  + "'. Pass forceOverwrite=true to override.");
        }
      }

      if (!resumingSamePackage) {
        progress = new InProgressDeploy(pkgSha256, robot.remoteFileExists(activeJarTarget));
        inProgressByTarget.put(resumeKey, progress);
      }

      if (!progress.ringRotated) {
        rotateRollbackRing(robot, activeJarTarget);
        progress.ringRotated = true;
      }

      Path jarPath = tempExtract.resolve("frcUserProgram.jar");
      if (!progress.jarUploaded) {
        robot.uploadFileAtomic(jarPath, activeJarTarget);
        progress.jarUploaded = true;
      }

      DeployStamp newStamp =
          progress.stamp != null
              ? progress.stamp
              : new DeployStamp(
                  manifest.packageName(),
                  manifest.channel(),
                  manifest.gitSha(),
                  manifest.configSha(),
                  laptopId,
                  System.currentTimeMillis());
      progress.stamp = newStamp;

      if (!progress.deploySwapped) {
        Path deployDir = tempExtract.resolve("deploy");
        Files.createDirectories(deployDir);
        Files.writeString(
            deployDir.resolve("deploy-stamp.json"), newStamp.toJson(), StandardCharsets.UTF_8);
        try {
          robot.replaceRemoteDirectoryAtomic(deployDir, "deploy");
          progress.deploySwapped = true;
        } catch (IOException | RuntimeException deployEx) {
          // Never leave the robot split-brained with the new JAR and previous deploy/ directory.
          try {
            if (progress.hadPreviousJar && robot.remoteFileExists("jars/prev_1.jar")) {
              if (robot.copyRemoteFile("jars/prev_1.jar", activeJarTarget)) {
                progress.jarUploaded = false;
              }
            } else if (!progress.hadPreviousJar) {
              if (robot.deleteRemoteFile(activeJarTarget)) {
                progress.jarUploaded = false;
              }
            }
          } catch (Exception rollbackEx) {
            deployEx.addSuppressed(rollbackEx);
          }
          throw deployEx;
        }
      }

      boolean cleanStable = "STABLE".equalsIgnoreCase(manifest.channel()) && !manifest.gitDirty();
      if (cleanStable && !progress.stableSnapshotSaved) {
        robot.uploadFileAtomic(jarPath, "jars/prev_stable.jar");
        if (!robot.copyRemoteDirectory("deploy", "jars/prev_stable_deploy")) {
          throw new IOException("Failed to snapshot deploy/ into jars/prev_stable_deploy.");
        }
        progress.stableSnapshotSaved = true;
      }

      robot.restartRobotCode();
      inProgressByTarget.remove(resumeKey);
      return newStamp;
    } finally {
      deleteRecursively(tempExtract);
      lockout.endTransfer();
    }
  }

  private static String buildDeployResumeKey(RobotTransport robot, String activeJarTarget) {
    String identity = RobotTransport.UNKNOWN_ROBOT_IDENTITY;
    try {
      String detected = robot.detectRobotIdentity();
      if (detected != null && !detected.isBlank()) {
        identity = detected.trim();
      }
    } catch (IOException ignored) {
    }
    return identity + "|" + robot.controllerPlatform() + "|" + activeJarTarget;
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

  /**
   * Atomically pushes {@code FluxFig} CSV configs in {@link FluxFig.Mode#PRODUCTION} mode after
   * local {@link FluxFig#validateStrict()} and restarts robot code so boot-time gains reload.
   */
  public String pushFluxFigConfigs(
      Path configDir, String activeProfileOverlay, RobotTransport robot) throws IOException {
    return pushFluxFigConfigs(configDir, activeProfileOverlay, FluxFig.Mode.PRODUCTION, robot);
  }

  /**
   * Atomically pushes {@code FluxFig} CSV configs, updates {@code deploy/configs/active-profile.txt},
   * and restarts robot code so all boot-time motor/mechanism configs reload cleanly.
   */
  public String pushFluxFigConfigs(
      Path configDir, String activeProfileOverlay, FluxFig.Mode mode, RobotTransport robot)
      throws IOException {
    lockout.beginTransferOrThrow("pushFluxFigConfigs:" + activeProfileOverlay + ":" + mode);
    try {
      robot.assertRobotDisabledForTransfer("pushFluxFigConfigs");
      String effectiveProfile = resolveIdentifiedProfileOrThrow(activeProfileOverlay, robot);
      FluxFig fig = new FluxFig(configDir);
      fig.setActiveProfile(effectiveProfile);
      List<String> violations = fig.validateStrict();
      if (!violations.isEmpty()) {
        throw new IllegalStateException("FluxFig safety gate blocked config push: " + violations);
      }
      try (Stream<Path> s = Files.list(configDir)) {
        for (Path csv : s.filter(p -> p.toString().endsWith(".csv")).sorted().toList()) {
          robot.uploadFileAtomic(csv, "deploy/configs/" + csv.getFileName().toString());
        }
      }
      String channel = (mode == FluxFig.Mode.TUNING) ? "EXPERIMENTAL" : "STABLE";
      String preset = (mode == FluxFig.Mode.TUNING) ? "live-tuning" : "STABLE";
      robot.writeRemoteText(
          "deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE,
          FluxFig.formatActiveProfileDirective(
              effectiveProfile, mode, fig.getConfigSha256(), channel, preset));
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      robot.restartRobotCode();
      return fig.getConfigSha256();
    } finally {
      lockout.endTransfer();
    }
  }

  /**
   * Restores the robot's locked {@code STABLE} config base from {@code jars/prev_stable_deploy/configs}
   * (without touching {@code jars/prev_stable_deploy/} or the JAR), locks {@code Mode.PRODUCTION},
   * clears any temporary tuned snapshot, and restarts robot code.
   */
  public ConfigSwitchResult switchToStableConfig(RobotTransport robot) throws IOException {
    lockout.beginTransferOrThrow("switchToStableConfig");
    try {
      robot.assertRobotDisabledForTransfer("switchToStableConfig");
      String robotId = resolveIdentifiedProfileOrThrow(null, robot);
      if (robot.remoteFileExists("jars/prev_stable_deploy/configs/parameters.csv")) {
        if (!robot.copyRemoteDirectory("jars/prev_stable_deploy/configs", "deploy/configs")) {
          throw new IOException(
              "Failed to restore jars/prev_stable_deploy/configs onto deploy/configs.");
        }
      }
      Map<String, String> remoteCsvs = loadRemoteCsvDirectory(robot, "deploy/configs");
      String sha = validateAndHashRemoteCsvs(remoteCsvs, robotId);
      robot.writeRemoteText(
          "deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE,
          FluxFig.formatActiveProfileDirective(
              robotId, FluxFig.Mode.PRODUCTION, sha, "STABLE", "STABLE"));
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      robot.restartRobotCode();
      return new ConfigSwitchResult(robotId, "STABLE", "STABLE", FluxFig.Mode.PRODUCTION, sha);
    } finally {
      lockout.endTransfer();
    }
  }

  /**
   * Merges a behavioral delta preset CSV onto the connected robot's locked hardware base ({@code
   * jars/prev_stable_deploy/configs} if present, else {@code deploy/configs}), validates the full
   * robot config strictly locally, uploads ONLY merged {@code parameters.csv} + {@code
   * active-profile.txt} ({@code channel=EXPERIMENTAL}), preserves {@code swerve_modules.csv} and
   * {@code jars/prev_stable_deploy/}, clears stale tuned snapshots, and restarts robot code.
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

    lockout.beginTransferOrThrow("switchToBehavioralPreset:" + presetName);
    Path tempMergedParams = null;
    try {
      robot.assertRobotDisabledForTransfer("switchToBehavioralPreset:" + presetName);
      String robotId = resolveIdentifiedProfileOrThrow(null, robot);
      String baseDir =
          robot.remoteFileExists("jars/prev_stable_deploy/configs/parameters.csv")
              ? "jars/prev_stable_deploy/configs"
              : "deploy/configs";
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

      tempMergedParams = Files.createTempFile("fluxtray-preset-params-", ".csv");
      Files.writeString(tempMergedParams, mergedParams, StandardCharsets.UTF_8);
      robot.uploadFileAtomic(tempMergedParams, "deploy/configs/parameters.csv");
      robot.writeRemoteText(
          "deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE,
          FluxFig.formatActiveProfileDirective(
              robotId, effectiveMode, mergedSha, "EXPERIMENTAL", presetName));
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      robot.restartRobotCode();
      return new ConfigSwitchResult(
          robotId, "EXPERIMENTAL", presetName, effectiveMode, mergedSha);
    } finally {
      if (tempMergedParams != null) {
        Files.deleteIfExists(tempMergedParams);
      }
      lockout.endTransfer();
    }
  }

  /**
   * Switches the connected robot into {@code Mode.TUNING} ({@code channel=EXPERIMENTAL}, {@code
   * preset=live-tuning}) without modifying any CSVs or {@code jars/prev_stable_deploy/}, wipes any
   * stale {@code /tmp/fluxfig-tuned-snapshot.txt}, and restarts robot code so NT4 tuning activates.
   */
  public ConfigSwitchResult switchToLiveTuning(RobotTransport robot) throws IOException {
    lockout.beginTransferOrThrow("switchToLiveTuning");
    try {
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
    } finally {
      lockout.endTransfer();
    }
  }

  private static Map<String, String> loadRemoteCsvDirectory(
      RobotTransport robot, String remoteConfigsDir) throws IOException {
    Map<String, String> csvs = new LinkedHashMap<>();
    List<String> names = new ArrayList<>(robot.listRemoteCsvFiles(remoteConfigsDir));
    if (!names.contains("parameters.csv")) {
      names.add("parameters.csv");
    }
    for (String fallbackName :
        List.of("mechanisms.csv", "swerve_modules.csv", "cameras.csv", "batteries.csv", "range_table.csv")) {
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
      deleteRecursively(tempDir);
    }
  }

  /**
   * Switches {@code deploy/configs/active-profile.txt} between {@link FluxFig.Mode#PRODUCTION} and
   * {@link FluxFig.Mode#TUNING}, preserving the robot's actual identity rather than overwriting it
   * with {@code COMP_2027}.
   */
  public FluxFig.ActiveProfileDirective switchActiveMode(
      String activeProfileOverlay,
      FluxFig.Mode mode,
      String expectedConfigSha256,
      RobotTransport robot)
      throws IOException {
    lockout.beginTransferOrThrow("switchActiveMode:" + activeProfileOverlay + ":" + mode);
    try {
      robot.assertRobotDisabledForTransfer("switchActiveMode");
      String profile = activeProfileOverlay;
      String sha = expectedConfigSha256;
      if ((profile == null || profile.isBlank()) || (sha == null || sha.isBlank())) {
        String existingRaw = robot.readRemoteText("deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE);
        if (existingRaw != null && !existingRaw.isBlank()) {
          FluxFig.ActiveProfileDirective existing =
              FluxFig.parseActiveProfileDirective(existingRaw);
          if (profile == null || profile.isBlank()) {
            profile = existing.profile();
          }
          if (sha == null || sha.isBlank()) {
            sha = existing.expectedConfigSha256();
          }
        }
      }
      String effectiveProfile = resolveIdentifiedProfileOrThrow(profile, robot);
      String channel = (mode == FluxFig.Mode.TUNING) ? "EXPERIMENTAL" : "STABLE";
      String preset = (mode == FluxFig.Mode.TUNING) ? "live-tuning" : "STABLE";
      String directive =
          FluxFig.formatActiveProfileDirective(effectiveProfile, mode, sha, channel, preset);
      robot.writeRemoteText("deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE, directive);
      return FluxFig.parseActiveProfileDirective(directive);
    } finally {
      lockout.endTransfer();
    }
  }

  /** Harvests {@code /tmp/fluxfig-tuned-snapshot.txt}, promotes {@code vN+1}, pushes CSVs, and restarts. */
  public FluxFig.ReleaseEntry promoteRemoteTuningSnapshot(
      Path hubConfigsDir, String author, String notes, RobotTransport robot) throws IOException {
    String remoteTmpPath = FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH;
    String snapshotText;
    lockout.beginTransferOrThrow("promoteRemoteTuningSnapshot");
    try {
      robot.assertRobotDisabledForTransfer("promoteRemoteTuningSnapshot");
      snapshotText = robot.readRemoteText(remoteTmpPath);
      if (snapshotText == null || snapshotText.isBlank()) {
        throw new IOException("No tuned snapshot found on robot at " + remoteTmpPath);
      }
    } finally {
      lockout.endTransfer();
    }

    FluxFig parsedSnapshot = FluxFig.fromEffectiveSnapshot(snapshotText);
    String activeProfile = parsedSnapshot.getActiveProfile();
    FluxFig.ReleaseEntry release =
        FluxFig.promoteSnapshotToDirectory(snapshotText, hubConfigsDir, author, notes);

    lockout.beginTransferOrThrow("finalizePromotedRelease:" + release.version());
    try {
      robot.assertRobotDisabledForTransfer("finalizePromotedRelease");
      try (Stream<Path> s = Files.list(hubConfigsDir)) {
        for (Path csv : s.filter(p -> p.toString().endsWith(".csv")).sorted().toList()) {
          robot.uploadFileAtomic(csv, "deploy/configs/" + csv.getFileName().toString());
        }
      }
      robot.writeRemoteText(
          "deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE,
          FluxFig.formatActiveProfileDirective(
              activeProfile, FluxFig.Mode.PRODUCTION, release.configSha256(), "STABLE", "STABLE"));
      robot.deleteRemoteFile(remoteTmpPath);
      robot.restartRobotCode();
    } finally {
      lockout.endTransfer();
    }
    return release;
  }

  /**
   * 1-Click "Restore STABLE": atomically restores {@code jars/prev_stable.jar} onto the active
   * {@code robotCommand} JAR target, restores {@code jars/prev_stable_deploy/}, clears any
   * temporary {@code /tmp/fluxfig-tuned-snapshot.txt}, and restarts robot code.
   */
  public synchronized void restoreStable(RobotTransport robot) throws IOException {
    lockout.beginTransferOrThrow("restoreStable");
    try {
      robot.assertRobotDisabledForTransfer("restoreStable");
      if (!robot.remoteFileExists("jars/prev_stable.jar")) {
        throw new IOException("No jars/prev_stable.jar found on robot to restore.");
      }
      String activeJarTarget = robot.resolveActiveJarRemotePath();
      if (!robot.copyRemoteFile("jars/prev_stable.jar", activeJarTarget)) {
        throw new IOException("Failed to restore jars/prev_stable.jar onto " + activeJarTarget);
      }
      if (!robot.copyRemoteDirectory("jars/prev_stable_deploy", "deploy")) {
        throw new IOException("Failed to restore jars/prev_stable_deploy onto deploy/");
      }
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      inProgressByTarget.clear();
      robot.restartRobotCode();
    } finally {
      lockout.endTransfer();
    }
  }

  /** Rolls back active JAR and {@code deploy/} to slot {@code 1..5} and restarts robot code. */
  public synchronized void rollbackToSlot(int slot, RobotTransport robot) throws IOException {
    if (slot < 1 || slot > ROLLBACK_RING_SLOTS) {
      throw new IllegalArgumentException(
          "Rollback slot must be between 1 and " + ROLLBACK_RING_SLOTS);
    }
    lockout.beginTransferOrThrow("rollbackToSlot:" + slot);
    try {
      robot.assertRobotDisabledForTransfer("rollbackToSlot:" + slot);
      String slotPath = "jars/prev_" + slot + ".jar";
      if (!robot.remoteFileExists(slotPath)) {
        throw new IOException("Rollback slot " + slotPath + " does not exist on robot.");
      }
      String activeJarTarget = robot.resolveActiveJarRemotePath();
      if (!robot.copyRemoteFile(slotPath, activeJarTarget)) {
        throw new IOException("Failed to restore " + slotPath + " onto " + activeJarTarget);
      }
      String deploySlot = "jars/prev_" + slot + "_deploy";
      if (!robot.copyRemoteDirectory(deploySlot, "deploy")) {
        throw new IOException("Failed to restore " + deploySlot + " onto deploy/");
      }
      robot.deleteRemoteFile(FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_REMOTE_PATH);
      inProgressByTarget.clear();
      robot.restartRobotCode();
    } finally {
      lockout.endTransfer();
    }
  }

  private static void rotateRollbackRing(RobotTransport robot, String activeJarTarget)
      throws IOException {
    for (int i = ROLLBACK_RING_SLOTS - 1; i >= 1; i--) {
      String srcJar = "jars/prev_" + i + ".jar";
      String dstJar = "jars/prev_" + (i + 1) + ".jar";
      if (robot.remoteFileExists(srcJar)) {
        robot.copyRemoteFile(srcJar, dstJar);
      }
      robot.copyRemoteDirectory("jars/prev_" + i + "_deploy", "jars/prev_" + (i + 1) + "_deploy");
    }
    if (robot.remoteFileExists(activeJarTarget)) {
      robot.copyRemoteFile(activeJarTarget, "jars/prev_1.jar");
    }
    robot.copyRemoteDirectory("deploy", "jars/prev_1_deploy");
  }

  private static void extractZip(Path zipPath, Path destDir) throws IOException {
    try (ZipFile zf = new ZipFile(zipPath.toFile())) {
      var entries = zf.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        Path out = destDir.resolve(e.getName()).normalize();
        if (!out.startsWith(destDir)) {
          throw new IOException("Blocked zip-slip entry: " + e.getName());
        }
        if (e.isDirectory()) {
          Files.createDirectories(out);
        } else {
          Files.createDirectories(out.getParent());
          try (InputStream in = zf.getInputStream(e)) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
  }

  private static void putTextEntry(ZipOutputStream zos, String name, String text)
      throws IOException {
    zos.putNextEntry(new ZipEntry(name));
    zos.write(text.getBytes(StandardCharsets.UTF_8));
    zos.closeEntry();
  }

  /**
   * Parses a flat JSON object ({@code { ... }}) on either single or multiple lines into string
   * key/value pairs, skipping nested arrays/objects and returning an empty map on malformed syntax.
   */
  static Map<String, String> parseFlatJson(String json) {
    Map<String, String> map = new LinkedHashMap<>();
    if (json == null) {
      return map;
    }
    String s = json.trim();
    if (s.length() < 2 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
      return map;
    }
    int len = s.length();
    int i = 1;
    while (i < len) {
      i = skipWhitespace(s, i);
      if (i >= len) {
        return Map.of();
      }
      char c = s.charAt(i);
      if (c == '}') {
        i = skipWhitespace(s, i + 1);
        return i == len ? map : Map.of();
      }
      if (c != '"') {
        return Map.of();
      }
      StringBuilder keyBuf = new StringBuilder();
      int afterKey = readJsonString(s, i, keyBuf);
      if (afterKey < 0) {
        return Map.of();
      }
      i = skipWhitespace(s, afterKey);
      if (i >= len || s.charAt(i) != ':') {
        return Map.of();
      }
      i = skipWhitespace(s, i + 1);
      if (i >= len) {
        return Map.of();
      }
      char vc = s.charAt(i);
      if (vc == '"') {
        StringBuilder valBuf = new StringBuilder();
        int afterVal = readJsonString(s, i, valBuf);
        if (afterVal < 0) {
          return Map.of();
        }
        map.put(keyBuf.toString(), valBuf.toString());
        i = afterVal;
      } else if (vc == '{' || vc == '[') {
        int afterNested = skipNestedJson(s, i);
        if (afterNested < 0) {
          return Map.of();
        }
        i = afterNested;
      } else {
        int start = i;
        while (i < len) {
          char pc = s.charAt(i);
          if (pc == ',' || pc == '}' || Character.isWhitespace(pc)) {
            break;
          }
          i++;
        }
        String primitive = s.substring(start, i);
        if (primitive.isEmpty()) {
          return Map.of();
        }
        if (!"null".equals(primitive)) {
          map.put(keyBuf.toString(), primitive);
        }
      }
      i = skipWhitespace(s, i);
      if (i >= len) {
        return Map.of();
      }
      if (s.charAt(i) == ',') {
        i++;
      } else if (s.charAt(i) == '}') {
        i = skipWhitespace(s, i + 1);
        return i == len ? map : Map.of();
      } else {
        return Map.of();
      }
    }
    return Map.of();
  }

  private static int skipWhitespace(String s, int idx) {
    while (idx < s.length() && Character.isWhitespace(s.charAt(idx))) {
      idx++;
    }
    return idx;
  }

  private static int readJsonString(String s, int openQuoteIdx, StringBuilder out) {
    int i = openQuoteIdx + 1;
    while (i < s.length()) {
      char c = s.charAt(i++);
      if (c == '\\') {
        if (i >= s.length()) {
          return -1;
        }
        char esc = s.charAt(i++);
        switch (esc) {
          case '"', '\\', '/' -> out.append(esc);
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'u' -> {
            if (i + 4 > s.length()) {
              return -1;
            }
            try {
              out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            } catch (NumberFormatException e) {
              return -1;
            }
            i += 4;
          }
          default -> out.append(esc);
        }
      } else if (c == '"') {
        return i;
      } else {
        out.append(c);
      }
    }
    return -1;
  }

  private static int skipNestedJson(String s, int openIdx) {
    int depth = 0;
    boolean inQuotes = false;
    boolean escaped = false;
    for (int i = openIdx; i < s.length(); i++) {
      char c = s.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (inQuotes) {
        if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inQuotes = false;
        }
        continue;
      }
      if (c == '"') {
        inQuotes = true;
      } else if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
    }
    return -1;
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted((a, b) -> b.compareTo(a)).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
