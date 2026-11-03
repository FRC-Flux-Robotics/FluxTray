package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Abstraction over robot communication (SSH/SFTP to physical SystemCore or roboRIO vs. local {@link
 * MockRobotServer} for Tier 6 testing).
 */
public interface RobotTransport {

  /** Target controller profile (`SYSTEMCORE_2027` vs `ROBORIO_2026`). */
  String controllerPlatform();

  /** Unique robot boot epoch ID (changes whenever the robot power-cycles). */
  String currentBootEpochId();

  boolean isReachable();

  String readRemoteText(String remoteRelativePath) throws IOException;

  void writeRemoteText(String remoteRelativePath, String content) throws IOException;

  void uploadFile(Path localFile, String remoteRelativePath) throws IOException;

  boolean copyRemoteFile(String fromRelativePath, String toRelativePath) throws IOException;

  boolean remoteFileExists(String remoteRelativePath) throws IOException;

  boolean deleteRemoteFile(String remoteRelativePath) throws IOException;

  List<String> listRemoteLogFiles() throws IOException;

  Map<String, Path> downloadAllLogs(Path localDestinationDir) throws IOException;

  /**
   * Executes the non-intrusive post-run {@link FluxSystemSnapshot#REMOTE_CAPTURE_SH_COMMAND} over SSH
   * and returns the parsed {@link FluxSystemSnapshot}.
   */
  FluxSystemSnapshot captureSystemSnapshot() throws IOException;
}
