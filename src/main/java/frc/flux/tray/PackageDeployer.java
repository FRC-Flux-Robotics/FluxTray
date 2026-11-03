package frc.flux.tray;

import frc.lib.fluxfig.FluxFig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 4-Second SFTP Package / {@code FluxFig} Config Switcher &amp; 5-Slot Rollback Ring ({@code
 * prev_1.jar} .. {@code prev_5.jar} + {@code prev_stable.jar}).
 *
 * <ul>
 *   <li>Validates {@code .fluxpkg.zip} target platform ({@code systemcore-2027} vs. {@code
 *       roborio-2026}) and runs {@link FluxFig#validateStrict()} on bundled CSV configs before
 *       touching the robot.
 *   <li>Enforces the Pre-Deploy Overwrite Guard via {@code deploy/deploy-stamp.json} so two pit
 *       laptops cannot silently overwrite each other's deployed package.
 *   <li>Rotates the active {@code frcUserProgram.jar} into a 5-slot backup ring ({@code
 *       jars/prev_1..5.jar}) and preserves {@code jars/prev_stable.jar} for 1-click 4-second
 *       "Restore STABLE" recovery.
 * </ul>
 */
public final class PackageDeployer {

  public static final int ROLLBACK_RING_SLOTS = 5;

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
          m.getOrDefault("channel", "STABLE"),
          m.getOrDefault("gitSha", ""),
          m.getOrDefault("configSha", ""),
          m.getOrDefault("deployedByLaptop", ""),
          ts);
    }
  }

  private final RobotSafetyLockout lockout;
  private final String laptopId;

  public PackageDeployer(RobotSafetyLockout lockout, String laptopId) {
    this.lockout = lockout;
    this.laptopId = laptopId;
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
   * Deploys a {@code .fluxpkg.zip} bundle to the robot while enforcing:
   *
   * <ul>
   *   <li>Strict Enabled/FMS lockout
   *   <li>Target platform match ({@code systemcore-2027} vs. {@code roborio-2026})
   *   <li>{@link FluxFig#validateStrict()} on bundled CSV configs
   *   <li>Pre-deploy overwrite guard ({@code deploy/deploy-stamp.json})
   *   <li>5-slot JAR rollback rotation ({@code prev_1..5.jar} + {@code prev_stable.jar})
   * </ul>
   */
  public DeployStamp deployPackage(
      Path fluxPkgZip, RobotTransport robot, boolean forceOverwriteOtherLaptop)
      throws IOException {
    lockout.beginTransferOrThrow("deployPackage:" + fluxPkgZip.getFileName());
    Path tempExtract = Files.createTempDirectory("fluxpkg-extract-");
    try {
      extractZip(fluxPkgZip, tempExtract);
      Path manifestPath = tempExtract.resolve("manifest.json");
      if (!Files.isRegularFile(manifestPath)) {
        throw new IOException("Missing manifest.json inside " + fluxPkgZip);
      }
      Map<String, String> mf = parseFlatJson(Files.readString(manifestPath, StandardCharsets.UTF_8));
      PackageManifest manifest =
          new PackageManifest(
              mf.getOrDefault("packageName", fluxPkgZip.getFileName().toString()),
              mf.getOrDefault("targetPlatform", "systemcore-2027"),
              mf.getOrDefault("channel", "STABLE"),
              mf.getOrDefault("gitSha", "0000000"),
              Boolean.parseBoolean(mf.getOrDefault("gitDirty", "false")),
              mf.getOrDefault("configSha", "00000000"),
              mf.getOrDefault("authorLaptop", laptopId));

      if (!manifest.targetPlatform().equalsIgnoreCase(robot.controllerPlatform())) {
        throw new IllegalArgumentException(
            "Platform mismatch: package targets '"
                + manifest.targetPlatform()
                + "' but robot is '"
                + robot.controllerPlatform()
                + "'");
      }

      Path configDir = tempExtract.resolve("deploy/configs");
      if (Files.isDirectory(configDir) && Files.isRegularFile(configDir.resolve("parameters.csv"))) {
        FluxFig fig = new FluxFig(configDir);
        List<String> errors = fig.validateStrict();
        if (!errors.isEmpty()) {
          throw new IllegalStateException("FluxFig strict validation failed: " + errors);
        }
      }

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

      rotateRollbackRing(robot);

      Path jarPath = tempExtract.resolve("frcUserProgram.jar");
      if (!Files.isRegularFile(jarPath)) {
        throw new IOException("Missing frcUserProgram.jar inside " + fluxPkgZip);
      }
      robot.uploadFile(jarPath, "frcUserProgram.jar");
      if ("STABLE".equalsIgnoreCase(manifest.channel())) {
        robot.uploadFile(jarPath, "jars/prev_stable.jar");
      }

      Path deployDir = tempExtract.resolve("deploy");
      if (Files.isDirectory(deployDir)) {
        try (Stream<Path> walk = Files.walk(deployDir)) {
          for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
            String rel = deployDir.relativize(file).toString().replace('\\', '/');
            robot.uploadFile(file, "deploy/" + rel);
          }
        }
      }

      DeployStamp newStamp =
          new DeployStamp(
              manifest.packageName(),
              manifest.channel(),
              manifest.gitSha(),
              manifest.configSha(),
              laptopId,
              System.currentTimeMillis());
      robot.writeRemoteText("deploy/deploy-stamp.json", newStamp.toJson());
      return newStamp;
    } finally {
      deleteRecursively(tempExtract);
      lockout.endTransfer();
    }
  }

  /**
   * Hot-reloads only {@code FluxFig} CSV configuration files ({@code < 50 ms}) while Disabled in
   * locked {@link FluxFig.Mode#PRODUCTION} mode after running {@link FluxFig#validateStrict()}.
   */
  public String pushFluxFigConfigs(
      Path configDir, String activeProfileOverlay, RobotTransport robot) throws IOException {
    return pushFluxFigConfigs(configDir, activeProfileOverlay, FluxFig.Mode.PRODUCTION, robot);
  }

  /**
   * Hot-reloads {@code FluxFig} CSV configuration files and sets the operating {@link FluxFig.Mode}
   * ({@link FluxFig.Mode#PRODUCTION} vs. {@link FluxFig.Mode#TUNING}) in {@code
   * deploy/configs/active-profile.txt} ({@code < 50 ms}) while Disabled after running {@link
   * FluxFig#validateStrict()}.
   */
  public String pushFluxFigConfigs(
      Path configDir, String activeProfileOverlay, FluxFig.Mode mode, RobotTransport robot)
      throws IOException {
    lockout.beginTransferOrThrow("pushFluxFigConfigs:" + activeProfileOverlay + ":" + mode);
    try {
      FluxFig fig = new FluxFig(configDir);
      fig.setActiveProfile(activeProfileOverlay);
      List<String> violations = fig.validateStrict();
      if (!violations.isEmpty()) {
        throw new IllegalStateException("FluxFig safety gate blocked config push: " + violations);
      }
      try (Stream<Path> s = Files.list(configDir)) {
        for (Path csv : s.filter(p -> p.toString().endsWith(".csv")).sorted().toList()) {
          robot.uploadFile(csv, "deploy/configs/" + csv.getFileName().toString());
        }
      }
      robot.writeRemoteText(
          "deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE,
          FluxFig.formatActiveProfileDirective(activeProfileOverlay, mode, fig.getConfigSha256()));
      return fig.getConfigSha256();
    } finally {
      lockout.endTransfer();
    }
  }

  /**
   * Sub-50 ms Mode &amp; Profile Switch: writes {@code deploy/configs/active-profile.txt} on the
   * robot so {@link FluxFig#reloadIfChanged()} switches between locked {@link
   * FluxFig.Mode#PRODUCTION} and live dashboard {@link FluxFig.Mode#TUNING} without restarting
   * robot code.
   */
  public FluxFig.ActiveProfileDirective switchActiveMode(
      String activeProfileOverlay,
      FluxFig.Mode mode,
      String expectedConfigSha256,
      RobotTransport robot)
      throws IOException {
    lockout.beginTransferOrThrow("switchActiveMode:" + activeProfileOverlay + ":" + mode);
    try {
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
      String effectiveProfile =
          (profile == null || profile.isBlank()) ? "COMP_2027" : profile.trim();
      String directive = FluxFig.formatActiveProfileDirective(effectiveProfile, mode, sha);
      robot.writeRemoteText("deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE, directive);
      return FluxFig.parseActiveProfileDirective(directive);
    } finally {
      lockout.endTransfer();
    }
  }

  /**
   * End-of-Session Tuning Harvest &amp; Version Promotion ({@code vN+1}):
   *
   * <ol>
   *   <li>Pulls {@code /tmp/fluxfig-tuned-snapshot.txt} from the robot over SFTP ({@code
   *       RobotTransport.readRemoteText}).
   *   <li>Calls {@link FluxFig#promoteSnapshotToDirectory(String, Path, String, String)} to write
   *       modified parameters and {@code @FluxFig.Tunable} relational table cells back into {@code
   *       hubConfigsDir/*.csv}, validate with {@link FluxFig#validateStrict()}, and append {@code
   *       vN+1} (with new {@code ConfigSHA256} and auto-diff summary) to {@code releases.csv}.
   *   <li>Deletes {@code /tmp/fluxfig-tuned-snapshot.txt} on the robot.
   *   <li>Re-uploads the locked {@link FluxFig.Mode#PRODUCTION} CSV bundle to the robot so it
   *       immediately runs {@code vN+1}.
   * </ol>
   */
  public FluxFig.ReleaseEntry promoteRemoteTuningSnapshot(
      Path hubConfigsDir, String author, String notes, RobotTransport robot) throws IOException {
    String remoteTmpPath = FluxFig.DEFAULT_TMP_TUNED_SNAPSHOT_PATH.toString();
    String snapshotText;
    lockout.beginTransferOrThrow("promoteRemoteTuningSnapshot");
    try {
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
      try (Stream<Path> s = Files.list(hubConfigsDir)) {
        for (Path csv : s.filter(p -> p.toString().endsWith(".csv")).sorted().toList()) {
          robot.uploadFile(csv, "deploy/configs/" + csv.getFileName().toString());
        }
      }
      robot.writeRemoteText(
          "deploy/configs/" + FluxFig.ACTIVE_PROFILE_FILE,
          FluxFig.formatActiveProfileDirective(
              activeProfile, FluxFig.Mode.PRODUCTION, release.configSha256()));
      robot.deleteRemoteFile(remoteTmpPath);
    } finally {
      lockout.endTransfer();
    }
    return release;
  }

  /**
   * 1-Click "Restore STABLE": restores {@code jars/prev_stable.jar} to {@code frcUserProgram.jar}
   * in 4 seconds after student Mode A live sandbox testing.
   */
  public void restoreStable(RobotTransport robot) throws IOException {
    lockout.beginTransferOrThrow("restoreStable");
    try {
      if (!robot.remoteFileExists("jars/prev_stable.jar")) {
        throw new IOException("No jars/prev_stable.jar found on robot to restore.");
      }
      robot.copyRemoteFile("jars/prev_stable.jar", "frcUserProgram.jar");
    } finally {
      lockout.endTransfer();
    }
  }

  /** Rolls back {@code frcUserProgram.jar} to slot {@code 1..5} from the 5-slot backup ring. */
  public void rollbackToSlot(int slot, RobotTransport robot) throws IOException {
    if (slot < 1 || slot > ROLLBACK_RING_SLOTS) {
      throw new IllegalArgumentException("Rollback slot must be between 1 and " + ROLLBACK_RING_SLOTS);
    }
    lockout.beginTransferOrThrow("rollbackToSlot:" + slot);
    try {
      String slotPath = "jars/prev_" + slot + ".jar";
      if (!robot.remoteFileExists(slotPath)) {
        throw new IOException("Rollback slot " + slotPath + " does not exist on robot.");
      }
      robot.copyRemoteFile(slotPath, "frcUserProgram.jar");
    } finally {
      lockout.endTransfer();
    }
  }

  private static void rotateRollbackRing(RobotTransport robot) throws IOException {
    for (int i = ROLLBACK_RING_SLOTS - 1; i >= 1; i--) {
      String src = "jars/prev_" + i + ".jar";
      String dst = "jars/prev_" + (i + 1) + ".jar";
      if (robot.remoteFileExists(src)) {
        robot.copyRemoteFile(src, dst);
      }
    }
    if (robot.remoteFileExists("frcUserProgram.jar")) {
      robot.copyRemoteFile("frcUserProgram.jar", "jars/prev_1.jar");
    }
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

  static Map<String, String> parseFlatJson(String json) {
    Map<String, String> map = new LinkedHashMap<>();
    if (json == null) {
      return map;
    }
    for (String rawLine : json.split("\\R")) {
      String line = rawLine.trim();
      if (line.startsWith("{") || line.startsWith("}") || line.isEmpty()) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String k = stripQuotes(line.substring(0, colon).trim());
      String v = line.substring(colon + 1).trim();
      if (v.endsWith(",")) {
        v = v.substring(0, v.length() - 1).trim();
      }
      map.put(k, stripQuotes(v));
    }
    return map;
  }

  private static String stripQuotes(String s) {
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      return s.substring(1, s.length() - 1);
    }
    return s;
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
