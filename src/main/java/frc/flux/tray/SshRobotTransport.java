package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Live SSH/SCP transport supporting multi-host roboRIO 2.0 ({@code lvuser@}, {@code /home/lvuser})
 * and SystemCore ({@code systemcore@}, {@code /home/systemcore}) with split command ({@code 15 s})
 * vs. transfer ({@code 300 s}) timeouts, platform-safe {@code UserKnownHostsFile}, SFTP-mode {@code
 * scp}, robot-side {@code /tmp/flux-robot-state} lockout enforcement, atomic staging, and JVM
 * restart.
 */
public final class SshRobotTransport implements RobotTransport {

  public static final List<String> ROBORIO_CANDIDATE_HOSTS =
      List.of("10.104.13.2", "172.22.11.2", "roboRIO-10413-FRC.local");

  public static final List<String> SYSTEMCORE_CANDIDATE_HOSTS =
      List.of("10.104.13.2", "172.26.0.1", "172.27.0.1", "robot.local");

  public static final long COMMAND_TIMEOUT_SEC = 15L;
  public static final long TRANSFER_TIMEOUT_SEC = 300L;
  private static final int TCP_PROBE_TIMEOUT_MS = 1_200;

  private final List<String> candidateHosts;
  private volatile String activeHost;
  private final String user;
  private final String remoteHomeDir;
  private final String platform;
  private final boolean isSystemCore;
  private final RobotSafetyLockout lockout;
  private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
  private final Set<String> createdRemoteDirs = ConcurrentHashMap.newKeySet();

  public SshRobotTransport(String host, boolean isSystemCore) {
    this(host, isSystemCore, null);
  }

  public SshRobotTransport(String host, boolean isSystemCore, RobotSafetyLockout lockout) {
    this(
        (host != null && !host.isBlank())
            ? List.of(host.trim())
            : (isSystemCore ? SYSTEMCORE_CANDIDATE_HOSTS : ROBORIO_CANDIDATE_HOSTS),
        isSystemCore,
        lockout);
  }

  public SshRobotTransport(
      List<String> candidateHosts, boolean isSystemCore, RobotSafetyLockout lockout) {
    List<String> resolved =
        (candidateHosts == null || candidateHosts.isEmpty())
            ? (isSystemCore ? SYSTEMCORE_CANDIDATE_HOSTS : ROBORIO_CANDIDATE_HOSTS)
            : List.copyOf(candidateHosts);
    this.candidateHosts = resolved;
    this.activeHost = resolved.get(0);
    this.isSystemCore = isSystemCore;
    this.user = isSystemCore ? "systemcore" : "lvuser";
    this.remoteHomeDir = isSystemCore ? "/home/systemcore" : "/home/lvuser";
    this.platform = isSystemCore ? "systemcore-2027" : "roborio-2026";
    this.lockout = lockout;
    if (lockout != null) {
      lockout.addAbortListener(this::abortActiveProcesses);
    }
  }

  public static SshRobotTransport forRoboRioDiscovery(RobotSafetyLockout lockout) {
    return new SshRobotTransport(ROBORIO_CANDIDATE_HOSTS, false, lockout);
  }

  public List<String> candidateHosts() {
    return candidateHosts;
  }

  public String activeHost() {
    return activeHost;
  }

  /** Returns OS-appropriate null known-hosts sink ({@code NUL} on Windows, {@code /dev/null} on POSIX). */
  public static String nullKnownHostsPath() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win") ? "NUL" : "/dev/null";
  }

  /** Builds standard OpenSSH arguments without legacy {@code -O} (preserves SFTP-mode {@code scp}). */
  public static List<String> buildSshOptions() {
    return List.of(
        "-o",
        "ConnectTimeout=3",
        "-o",
        "StrictHostKeyChecking=no",
        "-o",
        "UserKnownHostsFile=" + nullKnownHostsPath(),
        "-o",
        "LogLevel=ERROR",
        "-o",
        "BatchMode=yes");
  }

  /** Forcibly terminates any in-flight {@code ssh}/{@code scp} OS subprocesses on Enabled/FMS. */
  public void abortActiveProcesses() {
    for (Process p : activeProcesses) {
      p.destroyForcibly();
    }
    activeProcesses.clear();
  }

  /** Probes candidate hosts on TCP port 22 and caches the first reachable controller address. */
  public String discoverReachableHost() {
    if (candidateHosts.size() == 1) {
      activeHost = candidateHosts.get(0);
      return activeHost;
    }
    String current = activeHost;
    if (current != null && isTcp22Reachable(current)) {
      return current;
    }
    for (String candidate : candidateHosts) {
      if (isTcp22Reachable(candidate)) {
        if (!candidate.equals(activeHost)) {
          createdRemoteDirs.clear();
        }
        activeHost = candidate;
        return candidate;
      }
    }
    return activeHost;
  }

  private static boolean isTcp22Reachable(String host) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(host, 22), TCP_PROBE_TIMEOUT_MS);
      return true;
    } catch (IOException ignored) {
      return false;
    }
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
    discoverReachableHost();
    try {
      String out = runSshCommand("echo OK");
      return out.contains("OK");
    } catch (IOException e) {
      for (String candidate : candidateHosts) {
        if (candidate.equals(activeHost)) {
          continue;
        }
        try {
          String out = runSshCommandOnHost(candidate, "echo OK");
          if (out.contains("OK")) {
            createdRemoteDirs.clear();
            activeHost = candidate;
            return true;
          }
        } catch (IOException ignored) {
        }
      }
      return false;
    }
  }

  @Override
  public void assertRobotDisabledForTransfer(String operationName) throws IOException {
    if (lockout != null) {
      lockout.assertSafeForTransfer(operationName);
    }
    RobotStateHeartbeat hb = readRobotStateHeartbeat();
    if (hb.present() && (hb.enabled() || hb.fmsAttached())) {
      if (lockout != null) {
        lockout.updateState(hb.enabled(), hb.fmsAttached(), System.currentTimeMillis());
      }
      String reason = hb.fmsAttached() ? "FMS-attached" : "ENABLED";
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Robot is " + reason + "; blocked " + operationName);
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
    assertRobotDisabledForTransfer("writeRemoteText:" + remoteRelativePath);
    Path tmp = Files.createTempFile("fluxtray-upload-", ".tmp");
    try {
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      uploadFileAtomic(tmp, remoteRelativePath);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Override
  public void uploadFile(Path localFile, String remoteRelativePath) throws IOException {
    assertRobotDisabledForTransfer("uploadFile:" + remoteRelativePath);
    String full = resolveRemote(remoteRelativePath);
    ensureRemoteParentDir(full);
    runScpUpload(localFile, full);
  }

  @Override
  public void uploadFileAtomic(Path localFile, String remoteRelativePath) throws IOException {
    assertRobotDisabledForTransfer("uploadFileAtomic:" + remoteRelativePath);
    String full = resolveRemote(remoteRelativePath);
    String staged = full + ".fluxtray.tmp";
    ensureRemoteParentDir(full);
    runScpUpload(localFile, staged);
    assertRobotDisabledForTransfer("commitAtomic:" + remoteRelativePath);
    runSshCommand("mv -f " + shQuote(staged) + " " + shQuote(full));
  }

  @Override
  public void replaceRemoteDirectoryAtomic(Path localDir, String remoteRelativeDir)
      throws IOException {
    assertRobotDisabledForTransfer("replaceRemoteDirectoryAtomic:" + remoteRelativeDir);
    String target = resolveRemote(remoteRelativeDir);
    String staged = target + ".fluxtray.tmp";
    String backupOld = target + ".fluxtray.old";
    runSshCommand("rm -rf " + shQuote(staged) + " && mkdir -p " + shQuote(staged));
    if (Files.isDirectory(localDir)) {
      try (Stream<Path> walk = Files.walk(localDir)) {
        for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
          String rel = localDir.relativize(file).toString().replace('\\', '/');
          String remoteFile = staged + "/" + rel;
          ensureRemoteParentDir(remoteFile);
          assertRobotDisabledForTransfer("stageDeployFile:" + rel);
          runScpUpload(file, remoteFile);
        }
      }
    }
    assertRobotDisabledForTransfer("swapRemoteDirectory:" + remoteRelativeDir);
    runSshCommand(
        "rm -rf "
            + shQuote(backupOld)
            + " && (if [ -d "
            + shQuote(target)
            + " ]; then mv "
            + shQuote(target)
            + " "
            + shQuote(backupOld)
            + "; fi) && mv "
            + shQuote(staged)
            + " "
            + shQuote(target)
            + " && rm -rf "
            + shQuote(backupOld));
    createdRemoteDirs.clear();
  }

  @Override
  public boolean copyRemoteFile(String fromRelativePath, String toRelativePath) throws IOException {
    assertRobotDisabledForTransfer("copyRemoteFile:" + toRelativePath);
    String src = resolveRemote(fromRelativePath);
    String dst = resolveRemote(toRelativePath);
    String dstTmp = dst + ".fluxtray.tmp";
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
                + shQuote(dstTmp)
                + " && mv -f "
                + shQuote(dstTmp)
                + " "
                + shQuote(dst)
                + " && echo COPIED; fi");
    return out.contains("COPIED");
  }

  @Override
  public boolean copyRemoteDirectory(String fromRelativeDir, String toRelativeDir)
      throws IOException {
    assertRobotDisabledForTransfer("copyRemoteDirectory:" + toRelativeDir);
    String src = resolveRemote(fromRelativeDir);
    String dst = resolveRemote(toRelativeDir);
    String dstTmp = dst + ".fluxtray.tmp";
    String dstOld = dst + ".fluxtray.old";
    int slash = dst.lastIndexOf('/');
    String mkdir = slash > 0 ? "mkdir -p " + shQuote(dst.substring(0, slash)) + " && " : "";
    String out =
        runSshCommand(
            "if [ -d "
                + shQuote(src)
                + " ]; then "
                + mkdir
                + "rm -rf "
                + shQuote(dstTmp)
                + " "
                + shQuote(dstOld)
                + " && cp -r "
                + shQuote(src)
                + " "
                + shQuote(dstTmp)
                + " && (if [ -d "
                + shQuote(dst)
                + " ]; then mv "
                + shQuote(dst)
                + " "
                + shQuote(dstOld)
                + "; fi) && mv "
                + shQuote(dstTmp)
                + " "
                + shQuote(dst)
                + " && rm -rf "
                + shQuote(dstOld)
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
    assertRobotDisabledForTransfer("deleteRemoteFile:" + remoteRelativePath);
    String full = resolveRemote(remoteRelativePath);
    String out =
        runSshCommand(
            "if [ -f " + shQuote(full) + " ]; then rm -f " + shQuote(full) + " && echo DELETED; fi");
    return out.contains("DELETED");
  }

  @Override
  public void restartRobotCode() throws IOException {
    assertRobotDisabledForTransfer("restartRobotCode");
    if (isSystemCore) {
      runSshCommand(
          "if command -v systemctl >/dev/null 2>&1; then sudo -n systemctl restart frc-robot 2>/dev/null || systemctl restart frc-robot 2>/dev/null || true; "
              + "elif [ -x /usr/local/frc/bin/frcKillRobot.sh ]; then /usr/local/frc/bin/frcKillRobot.sh -t -r; fi");
    } else {
      runSshCommand("/usr/local/frc/bin/frcKillRobot.sh -t -r");
    }
  }

  @Override
  public List<String> listRemoteLogFiles() throws IOException {
    return listRemoteLogEntries().stream().map(RemoteLogEntry::remoteFullPath).toList();
  }

  @Override
  public List<RemoteLogEntry> listRemoteLogEntries() throws IOException {
    String probeScript =
        "for root in "
            + shQuote(remoteHomeDir + "/logs")
            + " /U/logs /media/sda1; do "
            + "  [ -d \"$root\" ] || continue; "
            + "  find -L \"$root\" -maxdepth 3 -type f "
            + "    \\( -name '*.wpilog' -o -name '*.hoot' -o -name 'run-meta.json' -o -name 'FRC_UserProgram*' -o -name '*.jpg' -o -name '*.png' \\) 2>/dev/null "
            + "  | while IFS= read -r f; do "
            + "      meta=$(stat -c '%s|%Y' \"$f\" 2>/dev/null || ls -ln \"$f\" 2>/dev/null | awk '{print $5\"|0\"}'); "
            + "      printf '%s|%s|%s\\n' \"$root\" \"$f\" \"$meta\"; "
            + "    done; "
            + "done; "
            + "if [ -f "
            + shQuote(remoteHomeDir + "/FRC_UserProgram.log")
            + " ]; then "
            + "  f="
            + shQuote(remoteHomeDir + "/FRC_UserProgram.log")
            + "; "
            + "  meta=$(stat -c '%s|%Y' \"$f\" 2>/dev/null || ls -ln \"$f\" 2>/dev/null | awk '{print $5\"|0\"}'); "
            + "  printf '%s|%s|%s\\n' "
            + shQuote(remoteHomeDir)
            + " \"$f\" \"$meta\"; "
            + "fi";
    String out = runSshCommand(probeScript);
    Map<String, RemoteLogEntry> dedupByRelKey = new LinkedHashMap<>();
    for (String line : out.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] parts = trimmed.split("\\|", -1);
      if (parts.length < 4) {
        continue;
      }
      String root = parts[0];
      String fullPath = parts[1];
      long size = parseLongSafe(parts[2], 0L);
      long mtime = parseLongSafe(parts[3], 0L);
      String rel = fullPath.startsWith(root + "/") ? fullPath.substring(root.length() + 1) : Path.of(fullPath).getFileName().toString();
      if (root.startsWith("/media/sda1")) {
        rel = "usb_sda1/" + rel;
      } else if (root.startsWith("/U/logs")) {
        rel = "usb_U/" + rel;
      }
      dedupByRelKey.putIfAbsent(rel, new RemoteLogEntry(fullPath, rel, size, mtime));
    }
    return new ArrayList<>(dedupByRelKey.values());
  }

  @Override
  public Map<String, Path> downloadAllLogs(Path localDestinationDir) throws IOException {
    return downloadSelectedLogs(listRemoteLogEntries(), localDestinationDir);
  }

  @Override
  public Map<String, Path> downloadSelectedLogs(
      List<RemoteLogEntry> entries, Path localDestinationDir) throws IOException {
    Files.createDirectories(localDestinationDir);
    Map<String, Path> downloaded = new LinkedHashMap<>();
    if (entries == null || entries.isEmpty()) {
      return downloaded;
    }
    String host = discoverReachableHost();
    for (RemoteLogEntry entry : entries) {
      assertRobotDisabledForTransfer("scpDownload:" + entry.relativeKey());
      Path target = localDestinationDir.resolve(entry.relativeKey()).normalize();
      if (!target.startsWith(localDestinationDir.normalize())) {
        throw new IOException("Blocked unsafe remote log path: " + entry.relativeKey());
      }
      Files.createDirectories(target.getParent());
      List<String> cmd = new ArrayList<>();
      cmd.add("scp");
      cmd.add("-p");
      cmd.addAll(buildSshOptions());
      cmd.add(user + "@" + host + ":" + entry.remoteFullPath());
      cmd.add(target.toAbsolutePath().toString());
      runProcess(new ProcessBuilder(cmd), TRANSFER_TIMEOUT_SEC);
      downloaded.put(entry.relativeKey(), target);
    }
    return downloaded;
  }

  @Override
  public String captureSystemSnapshotRaw() throws IOException {
    return runSshCommand(FluxSystemSnapshot.REMOTE_CAPTURE_SH_COMMAND);
  }

  private void ensureRemoteParentDir(String fullRemotePath) throws IOException {
    int slash = fullRemotePath.lastIndexOf('/');
    if (slash > 0) {
      String remoteParentDir = fullRemotePath.substring(0, slash);
      if (createdRemoteDirs.add(remoteParentDir)) {
        runSshCommand("mkdir -p " + shQuote(remoteParentDir));
      }
    }
  }

  private void runScpUpload(Path localFile, String fullRemotePath) throws IOException {
    String host = discoverReachableHost();
    List<String> cmd = new ArrayList<>();
    cmd.add("scp");
    cmd.addAll(buildSshOptions());
    cmd.add(localFile.toAbsolutePath().toString());
    cmd.add(user + "@" + host + ":" + fullRemotePath);
    runProcess(new ProcessBuilder(cmd), TRANSFER_TIMEOUT_SEC);
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
    return runSshCommandOnHost(discoverReachableHost(), remoteCmd);
  }

  private String runSshCommandOnHost(String host, String remoteCmd) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add("ssh");
    cmd.addAll(buildSshOptions());
    cmd.add(user + "@" + host);
    cmd.add(remoteCmd);
    return runProcess(new ProcessBuilder(cmd), COMMAND_TIMEOUT_SEC);
  }

  private String runProcess(ProcessBuilder pb, long timeoutSeconds) throws IOException {
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

      boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        p.destroyForcibly();
        throw new IOException("SSH/SCP command timed out after " + timeoutSeconds + "s");
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

  private static long parseLongSafe(String s, long fallback) {
    if (s == null || s.isBlank()) {
      return fallback;
    }
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
