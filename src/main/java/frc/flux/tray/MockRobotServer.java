package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Tier 6 Localhost / In-Process Robot Simulator ({@code MockRobotServer}) that emulates a physical
 * roboRIO 2.0 ({@code roborio-2026}) or SystemCore ({@code systemcore-2027}) filesystem, {@code
 * /tmp/flux-robot-state} lockout heartbeat, {@code robotCommand}, boot epochs, 5-slot JAR+{@code
 * deploy/} rollback ring, JVM restarts, match log generation, and raw {@link FluxSystemSnapshot}
 * captures.
 */
public final class MockRobotServer implements RobotTransport {

  private final Path rootDir;
  private final String controllerPlatform;
  private volatile String bootEpochId;
  private volatile boolean reachable = true;
  private volatile FluxSystemSnapshot nextSnapshot = FluxSystemSnapshot.healthySystemCoreBaseline();
  private volatile String nextSnapshotRaw = null;
  private final AtomicInteger restartCount = new AtomicInteger(0);
  private volatile String lastRestartedJar = null;
  private final List<String> operationLog = new ArrayList<>();
  private volatile boolean autoRefreshHeartbeat = true;

  public MockRobotServer(Path rootDir, String controllerPlatform) throws IOException {
    this.rootDir = rootDir;
    this.controllerPlatform = controllerPlatform;
    this.bootEpochId = "boot-epoch-1";
    Files.createDirectories(rootDir.resolve("jars"));
    Files.createDirectories(rootDir.resolve("deploy/configs"));
    Files.createDirectories(rootDir.resolve("logs/cam-snapshots"));
    if ("roborio-2026".equalsIgnoreCase(controllerPlatform)) {
      setRobotCommand(
          "/usr/local/frc/JRE/bin/java -XX:+UseSerialGC -jar /home/lvuser/Flux-RoboRIO-2027.jar\n");
    }
    setRobotStateFile(
        false, false, "roborio-2026".equalsIgnoreCase(controllerPlatform) ? "FUEL" : "COMP_2027");
  }

  public void simulatePowerCycle(String newBootEpochId) {
    this.bootEpochId = newBootEpochId;
  }

  public void setReachable(boolean reachable) {
    this.reachable = reachable;
  }

  public void setNextSnapshot(FluxSystemSnapshot snapshot) {
    this.nextSnapshot = snapshot;
    this.nextSnapshotRaw = null;
  }

  public void setNextSnapshotRaw(String rawSnapshotText) {
    this.nextSnapshotRaw = rawSnapshotText;
  }

  public void setRobotCommand(String commandText) throws IOException {
    Files.writeString(rootDir.resolve("robotCommand"), commandText, StandardCharsets.UTF_8);
  }

  public void setRobotIdentityFile(String robotId) throws IOException {
    Files.writeString(rootDir.resolve("robot-identity.txt"), robotId + "\n", StandardCharsets.UTF_8);
  }

  public void setRobotStateFile(boolean enabled, boolean fmsAttached, String robotId)
      throws IOException {
    this.autoRefreshHeartbeat = true;
    writeRobotStatePayload(enabled, fmsAttached, robotId, System.currentTimeMillis());
  }

  public void setRobotStateFile(
      boolean enabled, boolean fmsAttached, String robotId, long explicitEpochMs)
      throws IOException {
    this.autoRefreshHeartbeat = false;
    writeRobotStatePayload(enabled, fmsAttached, robotId, explicitEpochMs);
  }

  public void setRobotStateRaw(String rawPayload) throws IOException {
    this.autoRefreshHeartbeat = false;
    Path stateFile = resolvePath("/tmp/flux-robot-state");
    Files.createDirectories(stateFile.getParent());
    Files.writeString(stateFile, rawPayload, StandardCharsets.UTF_8);
  }

  private void writeRobotStatePayload(
      boolean enabled, boolean fmsAttached, String robotId, long epochMs) throws IOException {
    String state = fmsAttached ? "FMS" : (enabled ? "ENABLED" : "DISABLED");
    String payload =
        "STATE="
            + state
            + "\nENABLED="
            + enabled
            + "\nFMS="
            + fmsAttached
            + "\nEPOCH_MS="
            + epochMs
            + "\nROBOT_ID="
            + (robotId != null ? robotId : "FUEL")
            + "\nPID=1234\n";
    Path stateFile = resolvePath("/tmp/flux-robot-state");
    Files.createDirectories(stateFile.getParent());
    Files.writeString(stateFile, payload, StandardCharsets.UTF_8);
  }

  public void clearRobotStateFile() throws IOException {
    this.autoRefreshHeartbeat = false;
    Files.deleteIfExists(resolvePath("/tmp/flux-robot-state"));
  }

  public int restartCount() {
    return restartCount.get();
  }

  public String lastRestartedJar() {
    return lastRestartedJar;
  }

  public synchronized List<String> operationLog() {
    return List.copyOf(operationLog);
  }

  private synchronized void recordOp(String op) {
    operationLog.add(op);
  }

  public Path rootDir() {
    return rootDir;
  }

  /**
   * Populates simulated robot logs ({@code .wpilog}, {@code .hoot}, {@code FRC_UserProgram.log},
   * and {@code cam-snapshots/*.jpg}) inside the mock robot's {@code logs/} folder.
   */
  public void seedSimulatedMatchLogs(String matchPrefix) throws IOException {
    seedSimulatedMatchLogs(
        matchPrefix,
        "roborio-2026".equalsIgnoreCase(controllerPlatform) ? "FUEL" : "COMP_2027",
        "a1b2c3d4");
  }

  public void seedSimulatedMatchLogs(String matchPrefix, String robotId, String configSha)
      throws IOException {
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
            + "  \"robotId\": \""
            + robotId
            + "\",\n"
            + "  \"profile\": \""
            + robotId
            + "\",\n"
            + "  \"configSha256\": \""
            + configSha
            + "\",\n"
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
  public void assertRobotDisabledForTransfer(String operationName) throws IOException {
    ensureReachable();
    RobotStateHeartbeat hb = readRobotStateHeartbeat();
    if (!hb.present()) {
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat file is ABSENT; blocked " + operationName);
    }
    if (!hb.valid()) {
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat file is CORRUPT; blocked " + operationName);
    }
    if (hb.enabled()) {
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Robot is ENABLED; blocked " + operationName);
    }
    if (hb.fmsAttached()) {
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Robot is FMS-attached; blocked " + operationName);
    }
    if (!autoRefreshHeartbeat
        && hb.isStale(System.currentTimeMillis(), SshRobotTransport.HEARTBEAT_MAX_AGE_MS)) {
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat is STALE; blocked " + operationName);
    }
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
    assertRobotDisabledForTransfer("writeRemoteText:" + remoteRelativePath);
    Path target = resolvePath(remoteRelativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }

  @Override
  public void uploadFile(Path localFile, String remoteRelativePath) throws IOException {
    assertRobotDisabledForTransfer("uploadFile:" + remoteRelativePath);
    Path target = resolvePath(remoteRelativePath);
    Files.createDirectories(target.getParent());
    Files.copy(localFile, target, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void uploadFileAtomic(Path localFile, String remoteRelativePath) throws IOException {
    assertRobotDisabledForTransfer("uploadFileAtomic:" + remoteRelativePath);
    Path target = resolvePath(remoteRelativePath);
    Files.createDirectories(target.getParent());
    Path tmp = target.resolveSibling(target.getFileName() + ".fluxtray.tmp");
    Files.copy(localFile, tmp, StandardCopyOption.REPLACE_EXISTING);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    recordOp("uploadAtomic:" + remoteRelativePath);
  }

  @Override
  public void replaceRemoteDirectoryAtomic(Path localDir, String remoteRelativeDir)
      throws IOException {
    assertRobotDisabledForTransfer("replaceRemoteDirectoryAtomic:" + remoteRelativeDir);
    Path targetDir = resolvePath(remoteRelativeDir);
    Path tmpDir = targetDir.resolveSibling(targetDir.getFileName() + ".fluxtray.tmp");
    deleteRecursively(tmpDir);
    Files.createDirectories(tmpDir);
    if (Files.isDirectory(localDir)) {
      try (Stream<Path> walk = Files.walk(localDir)) {
        for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
          Path dst = tmpDir.resolve(localDir.relativize(p));
          Files.createDirectories(dst.getParent());
          Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    deleteRecursively(targetDir);
    Files.move(tmpDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
    recordOp("replaceDirAtomic:" + remoteRelativeDir);
  }

  @Override
  public boolean copyRemoteFile(String fromRelativePath, String toRelativePath) throws IOException {
    assertRobotDisabledForTransfer("copyRemoteFile:" + toRelativePath);
    Path src = resolvePath(fromRelativePath);
    if (!Files.isRegularFile(src)) {
      return false;
    }
    Path dst = resolvePath(toRelativePath);
    Files.createDirectories(dst.getParent());
    Path tmp = dst.resolveSibling(dst.getFileName() + ".fluxtray.tmp");
    Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
    recordOp("copyFile:" + fromRelativePath + "->" + toRelativePath);
    return true;
  }

  @Override
  public boolean copyRemoteDirectory(String fromRelativeDir, String toRelativeDir)
      throws IOException {
    assertRobotDisabledForTransfer("copyRemoteDirectory:" + toRelativeDir);
    Path src = resolvePath(fromRelativeDir);
    if (!Files.isDirectory(src)) {
      return false;
    }
    Path dst = resolvePath(toRelativeDir);
    Path tmp = dst.resolveSibling(dst.getFileName() + ".fluxtray.tmp");
    deleteRecursively(tmp);
    Files.createDirectories(tmp);
    try (Stream<Path> walk = Files.walk(src)) {
      for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
        Path out = tmp.resolve(src.relativize(p));
        Files.createDirectories(out.getParent());
        Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    deleteRecursively(dst);
    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
    return true;
  }

  @Override
  public boolean remoteFileExists(String remoteRelativePath) throws IOException {
    ensureReachable();
    return Files.isRegularFile(resolvePath(remoteRelativePath));
  }

  @Override
  public boolean deleteRemoteFile(String remoteRelativePath) throws IOException {
    assertRobotDisabledForTransfer("deleteRemoteFile:" + remoteRelativePath);
    return Files.deleteIfExists(resolvePath(remoteRelativePath));
  }

  @Override
  public void restartRobotCode() throws IOException {
    assertRobotDisabledForTransfer("restartRobotCode");
    lastRestartedJar = resolveActiveJarRemotePath();
    restartCount.incrementAndGet();
    recordOp("restart:" + lastRestartedJar);
  }

  @Override
  public List<String> listRemoteLogFiles() throws IOException {
    return listRemoteLogEntries().stream().map(RemoteLogEntry::relativeKey).toList();
  }

  @Override
  public List<RemoteLogEntry> listRemoteLogEntries() throws IOException {
    assertRobotDisabledForTransfer("listRemoteLogEntries");
    Path logsDir = rootDir.resolve("logs");
    if (!Files.isDirectory(logsDir)) {
      return List.of();
    }
    List<RemoteLogEntry> out = new ArrayList<>();
    try (Stream<Path> s = Files.walk(logsDir)) {
      for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
        String rel = logsDir.relativize(p).toString().replace('\\', '/');
        out.add(
            new RemoteLogEntry(
                "/home/lvuser/logs/" + rel,
                rel,
                Files.size(p),
                Files.getLastModifiedTime(p).toMillis() / 1_000L));
      }
    }
    return out;
  }

  @Override
  public Map<String, Path> downloadAllLogs(Path localDestinationDir) throws IOException {
    assertRobotDisabledForTransfer("downloadAllLogs");
    recordOp("downloadAllLogs");
    return downloadSelectedLogs(listRemoteLogEntries(), localDestinationDir);
  }

  @Override
  public Map<String, Path> downloadSelectedLogs(
      List<RemoteLogEntry> entries, Path localDestinationDir) throws IOException {
    assertRobotDisabledForTransfer("downloadSelectedLogs");
    recordOp("downloadSelectedLogs:" + entries.size());
    Path logsDir = rootDir.resolve("logs");
    Files.createDirectories(localDestinationDir);
    Map<String, Path> result = new LinkedHashMap<>();
    if (!Files.isDirectory(logsDir)) {
      return result;
    }
    for (RemoteLogEntry entry : entries) {
      Path src = logsDir.resolve(entry.relativeKey());
      if (!Files.isRegularFile(src)) {
        continue;
      }
      Path dst = localDestinationDir.resolve(entry.relativeKey());
      Files.createDirectories(dst.getParent());
      Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
      result.put(entry.relativeKey(), dst);
    }
    return result;
  }

  @Override
  public String captureSystemSnapshotRaw() throws IOException {
    assertRobotDisabledForTransfer("captureSystemSnapshotRaw");
    if (nextSnapshotRaw != null) {
      return nextSnapshotRaw;
    }
    return nextSnapshot != null ? nextSnapshot.serialize() : "";
  }

  private Path resolvePath(String rel) {
    String clean = rel;
    if (clean.startsWith("/home/lvuser/")) {
      clean = clean.substring("/home/lvuser/".length());
    } else if (clean.startsWith("/home/systemcore/")) {
      clean = clean.substring("/home/systemcore/".length());
    } else if (clean.startsWith("/")) {
      clean = clean.substring(1);
    }
    return rootDir.resolve(clean);
  }

  private void ensureReachable() throws IOException {
    if (!reachable) {
      throw new IOException("Robot is currently unreachable (FMS field VLAN or powered off).");
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
