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

  /** Canonical sentinel returned when neither {@code robot-identity.txt} nor heartbeat identifies the robot. */
  String UNKNOWN_ROBOT_IDENTITY = "unknown";

  static boolean isUnknownRobotIdentity(String robotId) {
    return robotId == null
        || robotId.isBlank()
        || UNKNOWN_ROBOT_IDENTITY.equalsIgnoreCase(robotId.trim());
  }

  /** Parsed view of {@code /tmp/flux-robot-state} written atomically by robot code. */
  record RobotStateHeartbeat(
      boolean present,
      boolean valid,
      String state,
      boolean enabled,
      boolean fmsAttached,
      long epochMs,
      String robotId,
      long pid) {
    public static RobotStateHeartbeat absent() {
      return new RobotStateHeartbeat(false, false, "ABSENT", false, false, 0L, "", 0L);
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
      boolean sawState = false;
      boolean sawEnabled = false;
      boolean sawFms = false;
      boolean sawEpochMs = false;
      boolean parseError = false;
      for (String rawLine : raw.split("\\R")) {
        String line = rawLine.trim();
        if (line.isEmpty()) {
          continue;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
          parseError = true;
          continue;
        }
        String k = line.substring(0, eq).trim();
        String v = line.substring(eq + 1).trim();
        switch (k) {
          case "STATE" -> {
            state = v;
            sawState = true;
            if (!"DISABLED".equalsIgnoreCase(v)
                && !"ENABLED".equalsIgnoreCase(v)
                && !"FMS".equalsIgnoreCase(v)) {
              parseError = true;
            }
          }
          case "ENABLED" -> {
            if ("true".equalsIgnoreCase(v)) {
              enabled = true;
              sawEnabled = true;
            } else if ("false".equalsIgnoreCase(v)) {
              enabled = false;
              sawEnabled = true;
            } else {
              parseError = true;
            }
          }
          case "FMS" -> {
            if ("true".equalsIgnoreCase(v)) {
              fms = true;
              sawFms = true;
            } else if ("false".equalsIgnoreCase(v)) {
              fms = false;
              sawFms = true;
            } else {
              parseError = true;
            }
          }
          case "EPOCH_MS" -> {
            try {
              epochMs = Long.parseLong(v);
              if (epochMs > 0L) {
                sawEpochMs = true;
              } else {
                parseError = true;
              }
            } catch (NumberFormatException ignored) {
              parseError = true;
            }
          }
          case "ROBOT_ID" -> robotId = v;
          case "PID" -> {
            try {
              pid = Long.parseLong(v);
              if (pid <= 0L) {
                parseError = true;
              }
            } catch (NumberFormatException ignored) {
              parseError = true;
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
      boolean valid = sawState && sawEnabled && sawFms && sawEpochMs && !parseError;
      return new RobotStateHeartbeat(true, valid, state, enabled, fms, epochMs, robotId, pid);
    }

    /** Returns {@code true} if the heartbeat is invalid or outside {@code +/- maxAgeMs}. */
    public boolean isStale(long referenceNowEpochMs, long maxAgeMs) {
      if (!present || !valid || epochMs <= 0L || referenceNowEpochMs <= 0L) {
        return true;
      }
      long ageMs = referenceNowEpochMs - epochMs;
      return ageMs > maxAgeMs || ageMs < -maxAgeMs;
    }
  }

  /** Metadata for one remote log file discovered on the robot controller. */
  record RemoteLogEntry(
      String remoteFullPath, String relativeKey, long sizeBytes, long mtimeEpochSec) {}

  /**
   * Read-only controller storage, log count/size, and Java process summary (works on legacy {@code
   * New_Robot} and 2027 controllers without {@code /tmp/flux-robot-state}).
   */
  record RobotDiagnostics(
      long internalFreeMb,
      long internalTotalMb,
      boolean usbMounted,
      long usbFreeMb,
      long usbTotalMb,
      int wpilogCount,
      long wpilogBytes,
      int hootCount,
      long hootBytes,
      long javaPid,
      String uptimeLine) {
    public static RobotDiagnostics empty() {
      return new RobotDiagnostics(-1L, -1L, false, -1L, -1L, 0, 0L, 0, 0L, -1L, "");
    }
  }

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
   * Resolves the connected robot's identity ({@code FUEL}, {@code CORAL}, {@code CHASSIS_3}, {@code
   * COMP_2027}, etc.) from {@code robot-identity.txt} or {@code /tmp/flux-robot-state}. Returns
   * {@link #UNKNOWN_ROBOT_IDENTITY} rather than guessing when neither source identifies the robot.
   */
  default String detectRobotIdentity() throws IOException {
    String idFile = readRemoteText("robot-identity.txt");
    if (idFile != null && !idFile.isBlank()) {
      String firstLine = idFile.trim().split("\\R")[0].trim();
      if (!isUnknownRobotIdentity(firstLine)) {
        return firstLine;
      }
    }
    RobotStateHeartbeat hb = readRobotStateHeartbeat();
    if (hb.present() && !isUnknownRobotIdentity(hb.robotId())) {
      return hb.robotId().trim();
    }
    return UNKNOWN_ROBOT_IDENTITY;
  }

  /** Returns {@code true} only when explicit initial-bootstrap opt-in is enabled. */
  default boolean isBootstrapAllowed() {
    return false;
  }

  /** Lists sorted regular {@code *.csv} filenames directly under {@code remoteRelativeDir}. */
  default List<String> listRemoteCsvFiles(String remoteRelativeDir) throws IOException {
    return List.of();
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

  /** Reads controller free space, log counts/sizes, and running robot Java PID without mutating state. */
  default RobotDiagnostics readDiagnostics() throws IOException {
    return RobotDiagnostics.empty();
  }
}
