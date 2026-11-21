package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Live SSH transport supporting multi-host roboRIO 2.0 ({@code lvuser@}, {@code /home/lvuser}) and
 * SystemCore ({@code systemcore@}, {@code /home/systemcore}) with split command ({@code 15 s}) vs.
 * transfer ({@code 300 s}) timeouts, platform-safe {@code UserKnownHostsFile}, binary stdin/stdout
 * streaming over {@code ssh} (avoiding OpenSSH 8.x vs. 9.x {@code scp} quoting divergence),
 * pure-Java USTAR tar-over-SSH {@code deploy/} streaming, POSIX-quoted remote paths, canonicalized
 * USB log root discovery, fail-closed {@code /tmp/flux-robot-state} staleness verification, and
 * atomic inlined BusyBox-safe SSH lockout guards right before every transfer, swap, and JVM
 * restart.
 */
public final class SshRobotTransport implements RobotTransport {

  public static final List<String> ROBORIO_CANDIDATE_HOSTS =
      List.of("10.104.13.2", "172.22.11.2", "roboRIO-10413-FRC.local");

  public static final List<String> SYSTEMCORE_CANDIDATE_HOSTS =
      List.of("10.104.13.2", "172.26.0.1", "172.27.0.1", "robot.local");

  public static final long COMMAND_TIMEOUT_SEC = 15L;
  public static final long TRANSFER_TIMEOUT_SEC = 300L;
  public static final long HEARTBEAT_MAX_AGE_MS = 5_000L;
  public static final long HEARTBEAT_MAX_AGE_SEC = 5L;
  public static final int LOCKOUT_EXIT_CODE = 42;
  static final String DEFAULT_HEARTBEAT_FILE = "/tmp/flux-robot-state";
  private static final int TCP_PROBE_TIMEOUT_MS = 1_200;
  private static final int TAR_BLOCK_SIZE = 512;

  /** Captured result of one local {@code ssh} subprocess invocation (text + lossless raw bytes). */
  public record ProcessResult(int exitCode, String stdout, String stderr, byte[] stdoutBytes) {
    public ProcessResult(int exitCode, String stdout, String stderr) {
      this(
          exitCode,
          stdout != null ? stdout : "",
          stderr != null ? stderr : "",
          stdout != null ? stdout.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }
  }

  /** Port executing {@code ssh} commands (backed by {@link ProcessBuilder} by default). */
  @FunctionalInterface
  public interface ProcessRunner {
    ProcessResult run(List<String> argv, byte[] stdin, Duration timeout) throws IOException;
  }

  private record HeartbeatSample(boolean present, long remoteEpochSec, RobotStateHeartbeat heartbeat) {}

  private final List<String> candidateHosts;
  private volatile String activeHost;
  private final String user;
  private final String remoteHomeDir;
  private final String platform;
  private final boolean isSystemCore;
  private final RobotSafetyLockout lockout;
  private final boolean allowMissingHeartbeat;
  private final String heartbeatFilePath;
  private final ProcessRunner processRunner;
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
        lockout,
        false,
        null);
  }

  public SshRobotTransport(
      List<String> candidateHosts, boolean isSystemCore, RobotSafetyLockout lockout) {
    this(candidateHosts, isSystemCore, lockout, false, null);
  }

  public SshRobotTransport(
      List<String> candidateHosts,
      boolean isSystemCore,
      RobotSafetyLockout lockout,
      boolean allowMissingHeartbeat) {
    this(candidateHosts, isSystemCore, lockout, allowMissingHeartbeat, null);
  }

  public SshRobotTransport(
      List<String> candidateHosts,
      boolean isSystemCore,
      RobotSafetyLockout lockout,
      boolean allowMissingHeartbeat,
      ProcessRunner processRunner) {
    this(
        candidateHosts,
        isSystemCore,
        lockout,
        allowMissingHeartbeat,
        DEFAULT_HEARTBEAT_FILE,
        processRunner);
  }

  SshRobotTransport(
      List<String> candidateHosts,
      boolean isSystemCore,
      RobotSafetyLockout lockout,
      boolean allowMissingHeartbeat,
      String heartbeatFilePath,
      ProcessRunner processRunner) {
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
    this.allowMissingHeartbeat = allowMissingHeartbeat;
    this.heartbeatFilePath =
        (heartbeatFilePath != null && !heartbeatFilePath.isBlank())
            ? heartbeatFilePath
            : DEFAULT_HEARTBEAT_FILE;
    this.processRunner = processRunner != null ? processRunner : this::runLocalProcess;
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

  public boolean isAllowMissingHeartbeat() {
    return allowMissingHeartbeat;
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
          String out = runSshCommandOnHost(candidate, "echo OK", null, COMMAND_TIMEOUT_SEC);
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
  public RobotStateHeartbeat readRobotStateHeartbeat() throws IOException {
    return readRobotStateHeartbeatWithClock().heartbeat();
  }

  private HeartbeatSample readRobotStateHeartbeatWithClock() throws IOException {
    String quotedHb = shQuote(heartbeatFilePath);
    String cmd =
        "printf 'REMOTE_EPOCH_SEC=%s\\n' \"$(date +%s)\" && "
            + "if [ -f "
            + quotedHb
            + " ]; then printf 'HEARTBEAT_PRESENT=1\\n'; cat "
            + quotedHb
            + "; "
            + "else printf 'HEARTBEAT_PRESENT=0\\n'; fi";
    String out = runSshCommand(cmd);
    long remoteEpochSec = -1L;
    boolean present = false;
    StringBuilder body = new StringBuilder();
    for (String line : out.split("\\R")) {
      if (line.startsWith("REMOTE_EPOCH_SEC=")) {
        remoteEpochSec = parseLongSafe(line.substring("REMOTE_EPOCH_SEC=".length()), -1L);
      } else if (line.startsWith("HEARTBEAT_PRESENT=")) {
        present = "1".equals(line.substring("HEARTBEAT_PRESENT=".length()).trim());
      } else {
        body.append(line).append('\n');
      }
    }
    if (!present) {
      return new HeartbeatSample(false, remoteEpochSec, RobotStateHeartbeat.absent());
    }
    RobotStateHeartbeat hb = RobotStateHeartbeat.parse(body.toString());
    if (!hb.present()) {
      // File existed on disk but was empty or whitespace-only; treat as present-but-corrupt.
      hb = new RobotStateHeartbeat(true, false, "CORRUPT", false, false, 0L, "", 0L);
    }
    return new HeartbeatSample(true, remoteEpochSec, hb);
  }

  @Override
  public void assertRobotDisabledForTransfer(String operationName) throws IOException {
    if (lockout != null) {
      lockout.assertSafeForTransfer(operationName);
    }
    HeartbeatSample sample = readRobotStateHeartbeatWithClock();
    if (!sample.present()) {
      if (allowMissingHeartbeat) {
        return;
      }
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat file is ABSENT (fail-closed; pass --allow-missing-heartbeat only for initial bootstrap); blocked "
              + operationName);
    }
    RobotStateHeartbeat hb = sample.heartbeat();
    if (!hb.valid()) {
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat file is CORRUPT; blocked " + operationName);
    }
    if (hb.enabled() || hb.fmsAttached()) {
      if (lockout != null) {
        lockout.updateState(hb.enabled(), hb.fmsAttached(), System.currentTimeMillis());
      }
      String reason = hb.fmsAttached() ? "FMS-attached" : "ENABLED";
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Robot is " + reason + "; blocked " + operationName);
    }
    if (sample.remoteEpochSec() <= 0L) {
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Unable to read remote clock for staleness check; blocked "
              + operationName);
    }
    long remoteNowMs = sample.remoteEpochSec() * 1_000L;
    if (hb.isStale(remoteNowMs, HEARTBEAT_MAX_AGE_MS)) {
      long ageMs = remoteNowMs - hb.epochMs();
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat is STALE (age="
              + ageMs
              + " ms, max="
              + HEARTBEAT_MAX_AGE_MS
              + " ms); blocked "
              + operationName);
    }
  }

  /**
   * Builds a pure POSIX/BusyBox shell guard checking {@code /tmp/flux-robot-state} atomically
   * inside the same SSH command immediately before a transfer, swap, or robot JVM restart. Uses
   * POSIX parameter expansion ({@code ${_ep%???}}) rather than 41-bit division so 32-bit BusyBox
   * {@code ash} on ARMv7 roboRIO 2.0 never overflows.
   */
  String buildInlineHeartbeatGuard() {
    String quotedHb = shQuote(heartbeatFilePath);
    String verifyBody =
        "_st=''; _en=''; _fm=''; _ep=''; "
            + "while IFS='=' read -r _k _v || [ -n \"$_k\" ]; do "
            + "case \"$_k\" in "
            + "STATE) _st=\"$_v\";; "
            + "ENABLED) _en=\"$_v\";; "
            + "FMS) _fm=\"$_v\";; "
            + "EPOCH_MS) _ep=\"$_v\";; "
            + "esac; done < "
            + quotedHb
            + "; "
            + "[ \"$_st\" = 'DISABLED' ] && [ \"$_en\" = 'false' ] && [ \"$_fm\" = 'false' ] "
            + "|| { echo \"LOCKOUT: state=$_st enabled=$_en fms=$_fm\" >&2; exit "
            + LOCKOUT_EXIT_CODE
            + "; }; "
            + "case \"$_ep\" in ''|0*|*[!0-9]*) echo 'LOCKOUT: corrupt EPOCH_MS' >&2; exit "
            + LOCKOUT_EXIT_CODE
            + ";; esac; "
            + "_hb=${_ep%???}; [ -n \"$_hb\" ] || _hb=0; _now=$(date +%s); _age=$((_now - _hb)); "
            + "[ \"$_age\" -le "
            + HEARTBEAT_MAX_AGE_SEC
            + " ] && [ \"$_age\" -ge -"
            + HEARTBEAT_MAX_AGE_SEC
            + " ] || { echo \"LOCKOUT: stale heartbeat age=${_age}s\" >&2; exit "
            + LOCKOUT_EXIT_CODE
            + "; }";
    if (allowMissingHeartbeat) {
      return "( if [ -f " + quotedHb + " ]; then " + verifyBody + "; fi )";
    }
    return "( [ -f "
        + quotedHb
        + " ] || { echo 'LOCKOUT: /tmp/flux-robot-state absent' >&2; exit "
        + LOCKOUT_EXIT_CODE
        + "; }; "
        + verifyBody
        + " )";
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
    runSshFileUpload(localFile, full);
  }

  @Override
  public void uploadFileAtomic(Path localFile, String remoteRelativePath) throws IOException {
    assertRobotDisabledForTransfer("uploadFileAtomic:" + remoteRelativePath);
    String full = resolveRemote(remoteRelativePath);
    String staged = full + ".fluxtray.tmp";
    ensureRemoteParentDir(full);
    runSshFileUpload(localFile, staged);
    assertRobotDisabledForTransfer("commitAtomic:" + remoteRelativePath);
    runSshCommand(buildInlineHeartbeatGuard() + " && mv -f " + shQuote(staged) + " " + shQuote(full));
  }

  @Override
  public void replaceRemoteDirectoryAtomic(Path localDir, String remoteRelativeDir)
      throws IOException {
    assertRobotDisabledForTransfer("replaceRemoteDirectoryAtomic:" + remoteRelativeDir);
    String target = resolveRemote(remoteRelativeDir);
    String staged = target + ".fluxtray.tmp";
    String backupOld = target + ".fluxtray.old";

    // Stream the entire directory as one POSIX/USTAR tar over SSH stdin so filenames with spaces
    // never touch Windows OpenSSH scp argument splitting.
    byte[] tarBytes = buildUstarTarArchive(localDir);
    String guard = buildInlineHeartbeatGuard();
    if (tarBytes.length > 0) {
      runSshTransferCommand(
          guard
              + " && rm -rf "
              + shQuote(staged)
              + " && mkdir -p "
              + shQuote(staged)
              + " && tar -xf - -C "
              + shQuote(staged),
          tarBytes);
    } else {
      runSshCommand(guard + " && rm -rf " + shQuote(staged) + " && mkdir -p " + shQuote(staged));
    }

    assertRobotDisabledForTransfer("swapRemoteDirectory:" + remoteRelativeDir);
    runSshCommand(
        buildInlineHeartbeatGuard()
            + " && rm -rf "
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
            buildInlineHeartbeatGuard()
                + " && if [ -f "
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
            buildInlineHeartbeatGuard()
                + " && if [ -d "
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
            buildInlineHeartbeatGuard()
                + " && if [ -f "
                + shQuote(full)
                + " ]; then rm -f "
                + shQuote(full)
                + " && echo DELETED; fi");
    return out.contains("DELETED");
  }

  @Override
  public void restartRobotCode() throws IOException {
    assertRobotDisabledForTransfer("restartRobotCode");
    String guard = buildInlineHeartbeatGuard();
    if (isSystemCore) {
      runSshCommand(
          guard
              + " && (if command -v systemctl >/dev/null 2>&1; then sudo -n systemctl restart frc-robot </dev/null >/dev/null 2>&1 || systemctl restart frc-robot </dev/null >/dev/null 2>&1 || true; "
              + "elif [ -x /usr/local/frc/bin/frcKillRobot.sh ]; then /usr/local/frc/bin/frcKillRobot.sh -t -r </dev/null >/dev/null 2>&1; fi)");
    } else {
      runSshCommand(guard + " && /usr/local/frc/bin/frcKillRobot.sh -t -r </dev/null >/dev/null 2>&1");
    }
  }

  @Override
  public List<String> listRemoteLogFiles() throws IOException {
    return listRemoteLogEntries().stream().map(RemoteLogEntry::remoteFullPath).toList();
  }

  @Override
  public List<RemoteLogEntry> listRemoteLogEntries() throws IOException {
    assertRobotDisabledForTransfer("listRemoteLogEntries");
    String probeScript =
        "seen_roots='|'; "
            + "for root in "
            + shQuote(remoteHomeDir + "/logs")
            + " /U/logs /media/sda1; do "
            + "  [ -d \"$root\" ] || continue; "
            + "  canon=$(readlink -f \"$root\" 2>/dev/null || printf '%s' \"$root\"); "
            + "  case \"$seen_roots\" in *\"|$canon|\"*) continue ;; esac; "
            + "  seen_roots=\"${seen_roots}${canon}|\"; "
            + "  find -L \"$canon\" -maxdepth 3 -type f "
            + "    \\( -name '*.wpilog' -o -name '*.hoot' -o -name 'run-meta.json' -o -name 'FRC_UserProgram*' -o -name '*.jpg' -o -name '*.png' \\) 2>/dev/null "
            + "  | while IFS= read -r f; do "
            + "      cf=$(readlink -f \"$f\" 2>/dev/null || printf '%s' \"$f\"); "
            + "      meta=$(stat -c '%s|%Y' \"$cf\" 2>/dev/null || ls -ln \"$cf\" 2>/dev/null | awk '{print $5\"|0\"}'); "
            + "      printf '%s|%s|%s|%s\\n' \"$root\" \"$canon\" \"$cf\" \"$meta\"; "
            + "    done; "
            + "done; "
            + "if [ -f "
            + shQuote(remoteHomeDir + "/FRC_UserProgram.log")
            + " ]; then "
            + "  f="
            + shQuote(remoteHomeDir + "/FRC_UserProgram.log")
            + "; "
            + "  cf=$(readlink -f \"$f\" 2>/dev/null || printf '%s' \"$f\"); "
            + "  meta=$(stat -c '%s|%Y' \"$cf\" 2>/dev/null || ls -ln \"$cf\" 2>/dev/null | awk '{print $5\"|0\"}'); "
            + "  printf '%s|%s|%s|%s\\n' "
            + shQuote(remoteHomeDir)
            + " "
            + shQuote(remoteHomeDir)
            + " \"$cf\" \"$meta\"; "
            + "fi";
    String out = runSshCommand(probeScript);
    Map<String, RemoteLogEntry> dedupByCanonPath = new LinkedHashMap<>();
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
      String root;
      String canonRoot;
      String fullPath;
      long size;
      long mtime;
      if (parts.length >= 5) {
        root = parts[0];
        canonRoot = parts[1];
        fullPath = parts[2];
        size = parseLongSafe(parts[3], 0L);
        mtime = parseLongSafe(parts[4], 0L);
      } else {
        root = parts[0];
        canonRoot = parts[0];
        fullPath = parts[1];
        size = parseLongSafe(parts[2], 0L);
        mtime = parseLongSafe(parts[3], 0L);
      }
      if (dedupByCanonPath.containsKey(fullPath)) {
        continue;
      }
      String rel;
      if (fullPath.startsWith(canonRoot + "/")) {
        rel = fullPath.substring(canonRoot.length() + 1);
      } else if (fullPath.startsWith(root + "/")) {
        rel = fullPath.substring(root.length() + 1);
      } else {
        rel = Path.of(fullPath).getFileName().toString();
      }
      if (root.startsWith("/U/logs")) {
        rel = "usb_U/" + rel;
      } else if (root.startsWith("/media/sda1")) {
        rel = "usb_sda1/" + rel;
      }
      RemoteLogEntry entry = new RemoteLogEntry(fullPath, rel, size, mtime);
      dedupByCanonPath.put(fullPath, entry);
      dedupByRelKey.putIfAbsent(rel, entry);
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
    for (RemoteLogEntry entry : entries) {
      assertRobotDisabledForTransfer("sshDownload:" + entry.relativeKey());
      Path target = localDestinationDir.resolve(entry.relativeKey()).normalize();
      if (!target.startsWith(localDestinationDir.normalize())) {
        throw new IOException("Blocked unsafe remote log path: " + entry.relativeKey());
      }
      Files.createDirectories(target.getParent());
      byte[] payload =
          runSshTransferDownload(
              buildInlineHeartbeatGuard() + " && cat " + shQuote(entry.remoteFullPath()));
      Files.write(target, payload);
      if (entry.mtimeEpochSec() > 0L) {
        Files.setLastModifiedTime(
            target, FileTime.from(Instant.ofEpochSecond(entry.mtimeEpochSec())));
      }
      downloaded.put(entry.relativeKey(), target);
    }
    return downloaded;
  }

  @Override
  public String captureSystemSnapshotRaw() throws IOException {
    assertRobotDisabledForTransfer("captureSystemSnapshotRaw");
    return runSshCommand(FluxSystemSnapshot.REMOTE_CAPTURE_SH_COMMAND);
  }

  @Override
  public RobotDiagnostics readDiagnostics() throws IOException {
    String homeLogs = shQuote(remoteHomeDir + "/logs");
    String script =
        "hdev=$(df -Pm "
            + shQuote(remoteHomeDir)
            + " 2>/dev/null | awk 'NR==2{print $1}'); "
            + "df -Pm "
            + shQuote(remoteHomeDir)
            + " 2>/dev/null | awk 'NR==2{printf \"INT=%s|%s\\n\", $4, $2}'; "
            + "usb='0|-1|-1'; "
            + "for u in /U /media/sda1; do "
            + "  [ -d \"$u\" ] || continue; "
            + "  udev=$(df -Pm \"$u\" 2>/dev/null | awk 'NR==2{print $1}'); "
            + "  if [ -n \"$udev\" ] && [ \"$udev\" != \"$hdev\" ]; then "
            + "    usb=$(df -Pm \"$u\" 2>/dev/null | awk 'NR==2{printf \"1|%s|%s\", $4, $2}'); "
            + "    break; "
            + "  fi; "
            + "done; "
            + "printf 'USB=%s\\n' \"$usb\"; "
            + "w=$(find -L "
            + homeLogs
            + " /U/logs /media/sda1 -maxdepth 3 -type f -name '*.wpilog' -exec ls -ln {} + 2>/dev/null "
            + "| awk '{c++; s+=$5} END{printf \"%d|%d\", c+0, s+0}'); "
            + "printf 'WPI=%s\\n' \"${w:-0|0}\"; "
            + "h=$(find -L "
            + homeLogs
            + " /U/logs /media/sda1 -maxdepth 3 -type f -name '*.hoot' -exec ls -ln {} + 2>/dev/null "
            + "| awk '{c++; s+=$5} END{printf \"%d|%d\", c+0, s+0}'); "
            + "printf 'HOOT=%s\\n' \"${h:-0|0}\"; "
            + "jpid=$(ps 2>/dev/null | awk '/[j]ava/{print $1; exit}'); "
            + "printf 'PID=%s\\n' \"${jpid:--1}\"; "
            + "printf 'UP=%s\\n' \"$(uptime 2>/dev/null || true)\"";
    String out = runSshCommand(script);
    long intFree = -1L;
    long intTotal = -1L;
    boolean usbMounted = false;
    long usbFree = -1L;
    long usbTotal = -1L;
    int wpiCount = 0;
    long wpiBytes = 0L;
    int hootCount = 0;
    long hootBytes = 0L;
    long javaPid = -1L;
    String uptime = "";
    for (String line : out.split("\\R")) {
      if (line.startsWith("INT=")) {
        String[] p = line.substring(4).split("\\|");
        if (p.length >= 2) {
          intFree = parseLongSafe(p[0], -1L);
          intTotal = parseLongSafe(p[1], -1L);
        }
      } else if (line.startsWith("USB=")) {
        String[] p = line.substring(4).split("\\|");
        if (p.length >= 3) {
          usbMounted = "1".equals(p[0]);
          usbFree = parseLongSafe(p[1], -1L);
          usbTotal = parseLongSafe(p[2], -1L);
        }
      } else if (line.startsWith("WPI=")) {
        String[] p = line.substring(4).split("\\|");
        if (p.length >= 2) {
          wpiCount = (int) parseLongSafe(p[0], 0L);
          wpiBytes = parseLongSafe(p[1], 0L);
        }
      } else if (line.startsWith("HOOT=")) {
        String[] p = line.substring(5).split("\\|");
        if (p.length >= 2) {
          hootCount = (int) parseLongSafe(p[0], 0L);
          hootBytes = parseLongSafe(p[1], 0L);
        }
      } else if (line.startsWith("PID=")) {
        javaPid = parseLongSafe(line.substring(4), -1L);
      } else if (line.startsWith("UP=")) {
        uptime = line.substring(3).trim();
      }
    }
    return new RobotDiagnostics(
        intFree, intTotal, usbMounted, usbFree, usbTotal, wpiCount, wpiBytes, hootCount, hootBytes,
        javaPid, uptime);
  }

  /** Builds a pure-Java POSIX/USTAR tar archive for all directories and files under {@code localDir}. */
  static byte[] buildUstarTarArchive(Path localDir) throws IOException {
    if (localDir == null || !Files.isDirectory(localDir)) {
      return new byte[0];
    }
    List<Path> dirs;
    List<Path> files;
    try (Stream<Path> walk = Files.walk(localDir)) {
      List<Path> all = walk.filter(p -> !p.equals(localDir)).sorted().toList();
      dirs = all.stream().filter(Files::isDirectory).toList();
      files = all.stream().filter(Files::isRegularFile).toList();
    }
    if (dirs.isEmpty() && files.isEmpty()) {
      return new byte[0];
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    for (Path dir : dirs) {
      String rel = localDir.relativize(dir).toString().replace('\\', '/');
      if (!rel.endsWith("/")) {
        rel = rel + "/";
      }
      long mtimeSec = Files.getLastModifiedTime(dir).toMillis() / 1_000L;
      writeUstarHeader(out, rel, 0L, mtimeSec, true);
    }
    for (Path file : files) {
      String rel = localDir.relativize(file).toString().replace('\\', '/');
      byte[] payload = Files.readAllBytes(file);
      long mtimeSec = Files.getLastModifiedTime(file).toMillis() / 1_000L;
      writeUstarHeader(out, rel, payload.length, mtimeSec, false);
      out.write(payload);
      int rem = payload.length % TAR_BLOCK_SIZE;
      if (rem != 0) {
        out.write(new byte[TAR_BLOCK_SIZE - rem]);
      }
    }
    // POSIX tar terminates with two 512-byte zero blocks.
    out.write(new byte[TAR_BLOCK_SIZE * 2]);
    return out.toByteArray();
  }

  private static void writeUstarHeader(
      ByteArrayOutputStream out, String relPath, long sizeBytes, long mtimeSec, boolean isDirectory)
      throws IOException {
    byte[] header = new byte[TAR_BLOCK_SIZE];
    String name = relPath;
    String prefix = "";
    byte[] rawFull = relPath.getBytes(StandardCharsets.UTF_8);
    if (rawFull.length > 100) {
      int split = relPath.lastIndexOf('/');
      while (split > 0) {
        String candPrefix = relPath.substring(0, split);
        String candName = relPath.substring(split + 1);
        if (!candName.isEmpty()
            && candPrefix.getBytes(StandardCharsets.UTF_8).length <= 155
            && candName.getBytes(StandardCharsets.UTF_8).length <= 100) {
          prefix = candPrefix;
          name = candName;
          break;
        }
        split = relPath.lastIndexOf('/', split - 1);
      }
      if (name.getBytes(StandardCharsets.UTF_8).length > 100
          || prefix.getBytes(StandardCharsets.UTF_8).length > 155) {
        throw new IOException("Path too long for POSIX USTAR header: " + relPath);
      }
    }

    putBytes(header, 0, name.getBytes(StandardCharsets.UTF_8), 100);
    putAscii(header, 100, isDirectory ? "0000755\0" : "0000644\0", 8);
    putAscii(header, 108, "0000000\0", 8);
    putAscii(header, 116, "0000000\0", 8);
    putAscii(header, 124, String.format(Locale.US, "%011o\0", isDirectory ? 0L : sizeBytes), 12);
    putAscii(header, 136, String.format(Locale.US, "%011o\0", Math.max(0L, mtimeSec)), 12);
    Arrays.fill(header, 148, 156, (byte) ' ');
    header[156] = (byte) (isDirectory ? '5' : '0');
    putAscii(header, 257, "ustar\0", 6);
    putAscii(header, 263, "00", 2);
    putAscii(header, 329, "0000000\0", 8);
    putAscii(header, 337, "0000000\0", 8);
    if (!prefix.isEmpty()) {
      putBytes(header, 345, prefix.getBytes(StandardCharsets.UTF_8), 155);
    }

    long checksum = 0L;
    for (byte b : header) {
      checksum += (b & 0xFF);
    }
    putAscii(header, 148, String.format(Locale.US, "%06o\0 ", checksum), 8);
    out.write(header);
  }

  private static void putAscii(byte[] dest, int offset, String value, int maxLen) {
    putBytes(dest, offset, value.getBytes(StandardCharsets.US_ASCII), maxLen);
  }

  private static void putBytes(byte[] dest, int offset, byte[] src, int maxLen) {
    System.arraycopy(src, 0, dest, offset, Math.min(src.length, maxLen));
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

  private void runSshFileUpload(Path localFile, String fullRemotePath) throws IOException {
    byte[] payload = Files.readAllBytes(localFile);
    runSshTransferCommand(
        buildInlineHeartbeatGuard() + " && cat > " + shQuote(fullRemotePath), payload);
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

  static String shQuote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  private String runSshCommand(String remoteCmd) throws IOException {
    return runSshCommandOnHost(discoverReachableHost(), remoteCmd, null, COMMAND_TIMEOUT_SEC);
  }

  private String runSshTransferCommand(String remoteCmd, byte[] stdin) throws IOException {
    return runSshCommandOnHost(discoverReachableHost(), remoteCmd, stdin, TRANSFER_TIMEOUT_SEC);
  }

  private byte[] runSshTransferDownload(String remoteCmd) throws IOException {
    List<String> cmd = buildSshInvocation(discoverReachableHost(), remoteCmd);
    return runCheckedProcessRaw(cmd, null, TRANSFER_TIMEOUT_SEC);
  }

  private String runSshCommandOnHost(
      String host, String remoteCmd, byte[] stdin, long timeoutSeconds) throws IOException {
    List<String> cmd = buildSshInvocation(host, remoteCmd);
    return runCheckedProcess(cmd, stdin, timeoutSeconds);
  }

  private List<String> buildSshInvocation(String host, String remoteCmd) {
    List<String> cmd = new ArrayList<>();
    cmd.add("ssh");
    cmd.addAll(buildSshOptions());
    cmd.add(user + "@" + host);
    cmd.add(remoteCmd);
    return cmd;
  }

  private String runCheckedProcess(List<String> argv, byte[] stdin, long timeoutSeconds)
      throws IOException {
    ProcessResult result = runCheckedResult(argv, stdin, timeoutSeconds);
    return result.stdout() != null ? result.stdout() : "";
  }

  private byte[] runCheckedProcessRaw(List<String> argv, byte[] stdin, long timeoutSeconds)
      throws IOException {
    ProcessResult result = runCheckedResult(argv, stdin, timeoutSeconds);
    return result.stdoutBytes() != null ? result.stdoutBytes() : new byte[0];
  }

  private ProcessResult runCheckedResult(List<String> argv, byte[] stdin, long timeoutSeconds)
      throws IOException {
    ProcessResult result =
        processRunner.run(List.copyOf(argv), stdin, Duration.ofSeconds(timeoutSeconds));
    if (result.exitCode() != 0) {
      String stderr = result.stderr() != null ? result.stderr() : "";
      if (result.exitCode() == LOCKOUT_EXIT_CODE || stderr.contains("LOCKOUT:")) {
        throw new IllegalStateException("LOCKOUT [/tmp/flux-robot-state inline]: " + stderr.trim());
      }
      throw new IOException("SSH exited with " + result.exitCode() + ": " + stderr);
    }
    return result;
  }

  private ProcessResult runLocalProcess(List<String> argv, byte[] stdin, Duration timeout)
      throws IOException {
    Process p = new ProcessBuilder(argv).start();
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

      try (OutputStream procIn = p.getOutputStream()) {
        if (stdin != null && stdin.length > 0) {
          procIn.write(stdin);
          procIn.flush();
        }
      }

      long timeoutMs = Math.max(1L, timeout.toMillis());
      boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!finished) {
        p.destroyForcibly();
        throw new IOException("SSH/SCP command timed out after " + timeout.toSeconds() + "s");
      }
      outReader.join(2_000L);
      errReader.join(2_000L);
      byte[] rawStdout = stdoutHolder[0] != null ? stdoutHolder[0] : new byte[0];
      String stdout = new String(rawStdout, StandardCharsets.UTF_8);
      String stderr =
          stderrHolder[0] != null ? new String(stderrHolder[0], StandardCharsets.UTF_8) : "";
      return new ProcessResult(p.exitValue(), stdout, stderr, rawStdout);
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
