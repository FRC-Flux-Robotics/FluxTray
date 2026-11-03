package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tier 6 Localhost / In-Process Robot Simulator ({@code MockRobotServer}) that emulates a physical
 * SystemCore ({@code systemcore-2027}) or roboRIO 2.0 ({@code roborio-2026}) filesystem, boot
 * epochs, 5-slot JAR rollback ring, match log generation, and {@link FluxSystemSnapshot} watch metrics.
 */
public final class MockRobotServer implements RobotTransport {

  private final Path rootDir;
  private volatile String controllerPlatform;
  private volatile String bootEpochId;
  private volatile boolean reachable = true;
  private volatile FluxSystemSnapshot nextSnapshot = FluxSystemSnapshot.healthySystemCoreBaseline();

  public MockRobotServer(Path rootDir, String controllerPlatform) throws IOException {
    this.rootDir = rootDir;
    this.controllerPlatform = controllerPlatform;
    this.bootEpochId = "boot-epoch-1";
    Files.createDirectories(rootDir.resolve("jars"));
    Files.createDirectories(rootDir.resolve("deploy/configs"));
    Files.createDirectories(rootDir.resolve("logs/cam-snapshots"));
  }

  public void simulatePowerCycle(String newBootEpochId) {
    this.bootEpochId = newBootEpochId;
  }

  public void setReachable(boolean reachable) {
    this.reachable = reachable;
  }

  public void setNextSnapshot(FluxSystemSnapshot snapshot) {
    this.nextSnapshot = snapshot;
  }

  public Path rootDir() {
    return rootDir;
  }

  /**
   * Populates simulated robot logs ({@code .wpilog}, {@code .hoot}, {@code FRC_UserProgram.log},
   * and {@code cam-snapshots/*.jpg}) inside the mock robot's {@code logs/} folder.
   */
  public void seedSimulatedMatchLogs(String matchPrefix) throws IOException {
    Path logs = rootDir.resolve("logs");
    Files.createDirectories(logs.resolve("cam-snapshots"));
    Files.writeString(
        logs.resolve("akit_" + matchPrefix + ".wpilog"),
        "WPILOG_SIMULATED_" + matchPrefix,
        StandardCharsets.UTF_8);
    Files.writeString(
        logs.resolve("Drivetrain_" + matchPrefix + ".hoot"),
        "HOOT_SIMULATED_" + matchPrefix,
        StandardCharsets.UTF_8);
    Files.writeString(
        logs.resolve("FRC_UserProgram.log"),
        "[INFO] Robot match run completed: " + matchPrefix + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        logs.resolve("cam-snapshots/prematch_lock_cam0.jpg"),
        "JPEG_SNAPSHOT_" + matchPrefix,
        StandardCharsets.UTF_8);
    int loopMs = "systemcore-2027".equalsIgnoreCase(controllerPlatform) ? 5 : 20;
    Files.writeString(
        logs.resolve("run-meta.json"),
        "{\n"
            + "  \"robotId\": \"COMP_2027\",\n"
            + "  \"profile\": \"COMP_2027\",\n"
            + "  \"configSha256\": \"a1b2c3d4\",\n"
            + "  \"batteryName\": \"UNKNOWN_BATTERY\",\n"
            + "  \"loopPeriodMs\": "
            + loopMs
            + ",\n"
            + "  \"bootTimestampIso\": \"2027-02-18T16:08:00Z\"\n"
            + "}\n",
        StandardCharsets.UTF_8);
  }

  @Override
  public String controllerPlatform() {
    return controllerPlatform;
  }

  @Override
  public String currentBootEpochId() {
    return reachable ? bootEpochId : "unreachable";
  }

  @Override
  public boolean isReachable() {
    return reachable;
  }

  @Override
  public String readRemoteText(String remoteRelativePath) throws IOException {
    ensureReachable();
    Path target = resolvePath(remoteRelativePath);
    if (!Files.isRegularFile(target)) {
      return null;
    }
    return Files.readString(target, StandardCharsets.UTF_8);
  }

  @Override
  public void writeRemoteText(String remoteRelativePath, String content) throws IOException {
    ensureReachable();
    Path target = resolvePath(remoteRelativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }

  @Override
  public void uploadFile(Path localFile, String remoteRelativePath) throws IOException {
    ensureReachable();
    Path target = resolvePath(remoteRelativePath);
    Files.createDirectories(target.getParent());
    Files.copy(localFile, target, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public boolean copyRemoteFile(String fromRelativePath, String toRelativePath) throws IOException {
    ensureReachable();
    Path src = resolvePath(fromRelativePath);
    if (!Files.isRegularFile(src)) {
      return false;
    }
    Path dst = resolvePath(toRelativePath);
    Files.createDirectories(dst.getParent());
    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    return true;
  }

  @Override
  public boolean remoteFileExists(String remoteRelativePath) throws IOException {
    ensureReachable();
    return Files.isRegularFile(resolvePath(remoteRelativePath));
  }

  @Override
  public boolean deleteRemoteFile(String remoteRelativePath) throws IOException {
    ensureReachable();
    return Files.deleteIfExists(resolvePath(remoteRelativePath));
  }

  @Override
  public List<String> listRemoteLogFiles() throws IOException {
    ensureReachable();
    Path logsDir = rootDir.resolve("logs");
    if (!Files.isDirectory(logsDir)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    try (Stream<Path> s = Files.walk(logsDir)) {
      for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
        out.add(logsDir.relativize(p).toString().replace('\\', '/'));
      }
    }
    return out;
  }

  @Override
  public Map<String, Path> downloadAllLogs(Path localDestinationDir) throws IOException {
    ensureReachable();
    Path logsDir = rootDir.resolve("logs");
    Files.createDirectories(localDestinationDir);
    Map<String, Path> result = new LinkedHashMap<>();
    if (!Files.isDirectory(logsDir)) {
      return result;
    }
    try (Stream<Path> s = Files.walk(logsDir)) {
      for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
        Path rel = logsDir.relativize(p);
        Path dst = localDestinationDir.resolve(rel);
        Files.createDirectories(dst.getParent());
        Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
        result.put(rel.toString().replace('\\', '/'), dst);
      }
    }
    return result;
  }

  @Override
  public FluxSystemSnapshot captureSystemSnapshot() throws IOException {
    ensureReachable();
    return nextSnapshot;
  }

  private Path resolvePath(String rel) {
    String clean = rel.startsWith("/") ? rel.substring(1) : rel;
    return rootDir.resolve(clean);
  }

  private void ensureReachable() throws IOException {
    if (!reachable) {
      throw new IOException("Robot is currently unreachable (FMS field VLAN or powered off).");
    }
  }
}
