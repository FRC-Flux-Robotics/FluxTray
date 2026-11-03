package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Live SSH/SCP transport supporting both SystemCore ({@code systemcore@10.104.13.2}, {@code
 * /home/systemcore}) and roboRIO 2.0 ({@code lvuser@10.104.13.2}, {@code /home/lvuser}).
 */
public final class SshRobotTransport implements RobotTransport {

  private final String host;
  private final String user;
  private final String remoteHomeDir;
  private final String platform;
  private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
  private final Set<String> createdRemoteDirs = ConcurrentHashMap.newKeySet();

  public SshRobotTransport(String host, boolean isSystemCore) {
    this(host, isSystemCore, null);
  }

  public SshRobotTransport(String host, boolean isSystemCore, RobotSafetyLockout lockout) {
    this.host = host;
    this.user = isSystemCore ? "systemcore" : "lvuser";
    this.remoteHomeDir = isSystemCore ? "/home/systemcore" : "/home/lvuser";
    this.platform = isSystemCore ? "systemcore-2027" : "roborio-2026";
    if (lockout != null) {
      lockout.addAbortListener(this::abortActiveProcesses);
    }
  }

  /** Forcibly terminates any in-flight {@code ssh}/{@code scp} OS subprocesses on Enabled/FMS. */
  public void abortActiveProcesses() {
    for (Process p : activeProcesses) {
      p.destroyForcibly();
    }
    activeProcesses.clear();
  }

  @Override
  public String controllerPlatform() {
    return platform;
  }

  @Override
  public String currentBootEpochId() {
    try {
      String out = runSshCommand("cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown");
      return out.trim();
    } catch (IOException e) {
      return "unreachable";
    }
  }

  @Override
  public boolean isReachable() {
    try {
      String out = runSshCommand("echo OK");
      return out.contains("OK");
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public String readRemoteText(String remoteRelativePath) throws IOException {
    String full = resolveRemote(remoteRelativePath);
    String out = runSshCommand("if [ -f " + shQuote(full) + " ]; then cat " + shQuote(full) + "; fi");
    return out.isEmpty() ? null : out;
  }

  @Override
  public void writeRemoteText(String remoteRelativePath, String content) throws IOException {
    Path tmp = Files.createTempFile("fluxtray-upload-", ".tmp");
    try {
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      uploadFile(tmp, remoteRelativePath);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Override
  public void uploadFile(Path localFile, String remoteRelativePath) throws IOException {
    String full = resolveRemote(remoteRelativePath);
    int slash = full.lastIndexOf('/');
    if (slash > 0) {
      String remoteParentDir = full.substring(0, slash);
      if (createdRemoteDirs.add(remoteParentDir)) {
        runSshCommand("mkdir -p " + shQuote(remoteParentDir));
      }
    }
    ProcessBuilder pb =
        new ProcessBuilder(
            "scp",
            "-o",
            "ConnectTimeout=3",
            "-o",
            "StrictHostKeyChecking=no",
            "-o",
            "BatchMode=yes",
            localFile.toAbsolutePath().toString(),
            user + "@" + host + ":" + full);
    runProcess(pb);
  }

  @Override
  public boolean copyRemoteFile(String fromRelativePath, String toRelativePath) throws IOException {
    String src = resolveRemote(fromRelativePath);
    String dst = resolveRemote(toRelativePath);
    int slash = dst.lastIndexOf('/');
    String mkdir = slash > 0 ? "mkdir -p " + shQuote(dst.substring(0, slash)) + " && " : "";
    String out =
        runSshCommand(
            "if [ -f "
                + shQuote(src)
                + " ]; then "
                + mkdir
                + "cp -f "
                + shQuote(src)
                + " "
                + shQuote(dst)
                + " && echo COPIED; fi");
    return out.contains("COPIED");
  }

  @Override
  public boolean remoteFileExists(String remoteRelativePath) throws IOException {
    String full = resolveRemote(remoteRelativePath);
    String out = runSshCommand("if [ -f " + shQuote(full) + " ]; then echo EXISTS; fi");
    return out.contains("EXISTS");
  }

  @Override
  public boolean deleteRemoteFile(String remoteRelativePath) throws IOException {
    String full = resolveRemote(remoteRelativePath);
    String out =
        runSshCommand("if [ -f " + shQuote(full) + " ]; then rm -f " + shQuote(full) + " && echo DELETED; fi");
    return out.contains("DELETED");
  }

  @Override
  public List<String> listRemoteLogFiles() throws IOException {
    String out =
        runSshCommand(
            "find -L "
                + shQuote(remoteHomeDir + "/logs")
                + " /U/logs -maxdepth 3 -type f 2>/dev/null || true");
    List<String> files = new ArrayList<>();
    for (String line : out.split("\\R")) {
      if (!line.isBlank()) {
        files.add(line.trim());
      }
    }
    return files;
  }

  @Override
  public Map<String, Path> downloadAllLogs(Path localDestinationDir) throws IOException {
    Files.createDirectories(localDestinationDir);
    ProcessBuilder pb =
        new ProcessBuilder(
            "scp",
            "-p",
            "-r",
            "-o",
            "ConnectTimeout=3",
            "-o",
            "StrictHostKeyChecking=no",
            "-o",
            "BatchMode=yes",
            user + "@" + host + ":" + remoteHomeDir + "/logs/.",
            localDestinationDir.toAbsolutePath().toString());
    runProcess(pb);
    // Also download /U/logs if present on USB / SystemCore mount
    String hasULogs = runSshCommand("if [ -d /U/logs ]; then echo YES; else echo NO; fi").trim();
    if ("YES".equals(hasULogs)) {
      ProcessBuilder pbU =
          new ProcessBuilder(
              "scp",
              "-p",
              "-r",
              "-o",
              "ConnectTimeout=3",
              "-o",
              "StrictHostKeyChecking=no",
              "-o",
              "BatchMode=yes",
              user + "@" + host + ":/U/logs/.",
              localDestinationDir.toAbsolutePath().toString());
      runProcess(pbU);
    }
    Map<String, Path> downloaded = new LinkedHashMap<>();
    try (Stream<Path> s = Files.walk(localDestinationDir)) {
      for (Path p : s.filter(Files::isRegularFile).toList()) {
        downloaded.put(localDestinationDir.relativize(p).toString(), p);
      }
    }
    return downloaded;
  }

  @Override
  public FluxSystemSnapshot captureSystemSnapshot() throws IOException {
    String raw = runSshCommand(FluxSystemSnapshot.REMOTE_CAPTURE_SH_COMMAND);
    return FluxSystemSnapshot.parse(raw);
  }

  private String resolveRemote(String rel) {
    if (rel == null || rel.contains("..")) {
      throw new IllegalArgumentException("Invalid remote path: " + rel);
    }
    if (rel.startsWith("/")) {
      return rel;
    }
    return remoteHomeDir + "/" + rel;
  }

  private static String shQuote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  private String runSshCommand(String remoteCmd) throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            "ssh",
            "-o",
            "ConnectTimeout=3",
            "-o",
            "StrictHostKeyChecking=no",
            "-o",
            "BatchMode=yes",
            user + "@" + host,
            remoteCmd);
    return runProcess(pb);
  }

  private String runProcess(ProcessBuilder pb) throws IOException {
    Process p = pb.start();
    activeProcesses.add(p);
    try {
      final byte[][] stdoutHolder = new byte[1][];
      final byte[][] stderrHolder = new byte[1][];
      Thread outReader =
          new Thread(
              () -> {
                try {
                  stdoutHolder[0] = p.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                }
              },
              "fluxtray-ssh-stdout");
      Thread errReader =
          new Thread(
              () -> {
                try {
                  stderrHolder[0] = p.getErrorStream().readAllBytes();
                } catch (IOException ignored) {
                }
              },
              "fluxtray-ssh-stderr");
      outReader.setDaemon(true);
      errReader.setDaemon(true);
      outReader.start();
      errReader.start();

      boolean finished = p.waitFor(10, TimeUnit.SECONDS);
      if (!finished) {
        p.destroyForcibly();
        throw new IOException("SSH/SCP command timed out");
      }
      outReader.join(2_000L);
      errReader.join(2_000L);
      String stdout =
          stdoutHolder[0] != null ? new String(stdoutHolder[0], StandardCharsets.UTF_8) : "";
      if (p.exitValue() != 0) {
        String stderr =
            stderrHolder[0] != null ? new String(stderrHolder[0], StandardCharsets.UTF_8) : "";
        throw new IOException("SSH/SCP exited with " + p.exitValue() + ": " + stderr);
      }
      return stdout;
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted waiting for SSH/SCP", e);
    } finally {
      activeProcesses.remove(p);
    }
  }
}
