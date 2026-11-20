package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Abstraction over robot communication (SSH/SFTP to physical roboRIO or SystemCore vs. local {@link
 * MockRobotServer} for Tier 6 testing).
 */
public interface RobotTransport {

  /** Parsed view of {@code /tmp/flux-robot-state} written atomically by robot code. */
  record RobotStateHeartbeat(
      boolean present,
      String state,
      boolean enabled,
      boolean fmsAttached,
      long epochMs,
      String robotId,
      long pid) {
    public static RobotStateHeartbeat absent() {
      return new RobotStateHeartbeat(false, "ABSENT", false, false, 0L, "", 0L);
    }

    public static RobotStateHeartbeat parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return absent();
      }
      String state = "UNKNOWN";
      boolean enabled = false;
      boolean fms = false;
      long epochMs = 0L;
      String robotId = "";
      long pid = 0L;
      for (String line : raw.split("\\R")) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String k = line.substring(0, eq).trim();
        String v = line.substring(eq + 1).trim();
        switch (k) {
          case "STATE" -> state = v;
          case "ENABLED" -> enabled = Boolean.parseBoolean(v);
          case "FMS" -> fms = Boolean.parseBoolean(v);
          case "EPOCH_MS" -> {
            try {
              epochMs = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
          }
          case "ROBOT_ID" -> robotId = v;
          case "PID" -> {
            try {
              pid = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
          }
          default -> {}
        }
      }
      if ("ENABLED".equalsIgnoreCase(state)) {
        enabled = true;
      } else if ("FMS".equalsIgnoreCase(state)) {
        fms = true;
      }
      return new RobotStateHeartbeat(true, state, enabled, fms, epochMs, robotId, pid);
    }
  }

  /** Metadata for one remote log file discovered on the robot controller. */
  record RemoteLogEntry(
      String remoteFullPath, String relativeKey, long sizeBytes, long mtimeEpochSec) {}

  /** Target controller profile (`systemcore-2027` vs `roborio-2026`). */
  String controllerPlatform();

  /** Unique robot boot epoch ID (changes whenever the robot power-cycles). */
  String currentBootEpochId();

  boolean isReachable();

  /** Reads the robot-side {@code /tmp/flux-robot-state} lockout heartbeat file if present. */
  default RobotStateHeartbeat readRobotStateHeartbeat() throws IOException {
    return RobotStateHeartbeat.parse(readRemoteText("/tmp/flux-robot-state"));
  }

  /**
   * Verifies via {@code /tmp/flux-robot-state} that the robot is neither Enabled nor attached to
   * FMS before allowing any SSH mutation or SCP transfer.
   */
  void assertRobotDisabledForTransfer(String operationName) throws IOException;

  /**
   * Resolves the connected robot's identity ({@code FUEL}, {@code CORAL}, {@code COMP_2027}, etc.)
   * from {@code robot-identity.txt}, {@code /tmp/flux-robot-state}, or {@code logs/run-meta.json}.
   */
  default String detectRobotIdentity() throws IOException {
    String idFile = readRemoteText("robot-identity.txt");
    if (idFile != null && !idFile.isBlank()) {
      return idFile.trim().split("\\R")[0].trim();
    }
    RobotStateHeartbeat hb = readRobotStateHeartbeat();
    if (hb.present() && hb.robotId() != null && !hb.robotId().isBlank() && !"UNKNOWN".equals(hb.robotId())) {
      return hb.robotId();
    }
    return "roborio-2026".equalsIgnoreCase(controllerPlatform()) ? "FUEL" : "COMP_2027";
  }

  /**
   * Resolves the active robot JAR path executed by {@code /home/lvuser/robotCommand} (e.g., {@code
   * Flux-RoboRIO-2027.jar}) or {@code frcUserProgram.jar} when no explicit {@code -jar} exists.
   */
  default String resolveActiveJarRemotePath() throws IOException {
    String robotCmd = readRemoteText("robotCommand");
    return PackageDeployer.parseJarPathFromRobotCommand(robotCmd);
  }

  String readRemoteText(String remoteRelativePath) throws IOException;

  void writeRemoteText(String remoteRelativePath, String content) throws IOException;

  void uploadFile(Path localFile, String remoteRelativePath) throws IOException;

  /** Stages to {@code .fluxtray.tmp} and atomically renames onto {@code remoteRelativePath}. */
  default void uploadFileAtomic(Path localFile, String remoteRelativePath) throws IOException {
    uploadFile(localFile, remoteRelativePath);
  }

  /** Stages a local directory tree to {@code .fluxtray.tmp} and atomically swaps it into place. */
  void replaceRemoteDirectoryAtomic(Path localDir, String remoteRelativeDir) throws IOException;

  boolean copyRemoteFile(String fromRelativePath, String toRelativePath) throws IOException;

  /** Recursively copies a remote directory (used for full {@code deploy/} rollback slots). */
  boolean copyRemoteDirectory(String fromRelativeDir, String toRelativeDir) throws IOException;

  boolean remoteFileExists(String remoteRelativePath) throws IOException;

  boolean deleteRemoteFile(String remoteRelativePath) throws IOException;

  /** Restarts the live robot JVM ({@code frcKillRobot.sh -t -r} on roboRIO). */
  void restartRobotCode() throws IOException;

  List<String> listRemoteLogFiles() throws IOException;

  /** Lists remote log files with size &amp; mtime metadata for incremental syncing. */
  List<RemoteLogEntry> listRemoteLogEntries() throws IOException;

  Map<String, Path> downloadAllLogs(Path localDestinationDir) throws IOException;

  /** Downloads only the selected remote log entries into {@code localDestinationDir}. */
  Map<String, Path> downloadSelectedLogs(List<RemoteLogEntry> entries, Path localDestinationDir)
      throws IOException;

  /**
   * Executes the non-intrusive post-run {@link FluxSystemSnapshot#REMOTE_CAPTURE_SH_COMMAND} over
   * SSH and returns the raw stdout text without default-filling absent metrics.
   */
  String captureSystemSnapshotRaw() throws IOException;

  /** Executes {@link #captureSystemSnapshotRaw()} and returns the parsed snapshot. */
  default FluxSystemSnapshot captureSystemSnapshot() throws IOException {
    return FluxSystemSnapshot.parse(captureSystemSnapshotRaw());
  }
}
