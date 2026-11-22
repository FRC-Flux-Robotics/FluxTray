package frc.flux.tray;

import frc.lib.fluxfig.FluxSystemSnapshot;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.function.Predicate;
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

    /** Streams process stdout directly to {@code stdoutTargetFile} without heap buffering. */
    default ProcessResult runToFile(
        List<String> argv, byte[] stdin, Path stdoutTargetFile, Duration timeout)
        throws IOException {
      ProcessResult res = run(argv, stdin, timeout);
      if (res.exitCode() == 0 && stdoutTargetFile != null) {
        byte[] bytes = res.stdoutBytes() != null ? res.stdoutBytes() : new byte[0];
        Files.write(stdoutTargetFile, bytes);
      }
      return res;
    }
  }

  private record HeartbeatSample(
      boolean present, long remoteEpochSec, RobotStateHeartbeat heartbeat, boolean robotJvmRunning) {}

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
  private final Predicate<String> tcpProbe;
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
    this(
        candidateHosts,
        isSystemCore,
        lockout,
        allowMissingHeartbeat,
        heartbeatFilePath,
        processRunner,
        null);
  }

  SshRobotTransport(
      List<String> candidateHosts,
      boolean isSystemCore,
      RobotSafetyLockout lockout,
      boolean allowMissingHeartbeat,
      String heartbeatFilePath,
      ProcessRunner processRunner,
      Predicate<String> tcpProbe) {
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
    this.processRunner =
        processRunner != null
            ? processRunner
            : new ProcessRunner() {
              @Override
              public ProcessResult run(List<String> argv, byte[] stdin, Duration timeout)
                  throws IOException {
                return runLocalProcess(argv, stdin, timeout);
              }

              @Override
              public ProcessResult runToFile(
                  List<String> argv, byte[] stdin, Path stdoutTargetFile, Duration timeout)
                  throws IOException {
                return runLocalProcessToFile(argv, stdin, stdoutTargetFile, timeout);
              }
            };
    // Live production transports probe TCP:22 before spawning ssh; stub ProcessRunner tests bypass
    // socket probing unless an explicit tcpProbe is injected.
    this.tcpProbe =
        tcpProbe != null
            ? tcpProbe
            : (processRunner == null ? SshRobotTransport::isTcp22Reachable : ignoredHost -> true);
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

  @Override
  public boolean isBootstrapAllowed() {
    return allowMissingHeartbeat;
  }

  @Override
  public List<String> listRemoteCsvFiles(String remoteRelativeDir) throws IOException {
    String full = resolveRemote(remoteRelativeDir);
    String out =
        runSshCommand(
            "if [ -d "
                + shQuote(full)
                + " ]; then for f in "
                + shQuote(full)
                + "/*.csv; do [ -f \"$f\" ] && basename \"$f\"; done; fi");
    List<String> files = new ArrayList<>();
    for (String line : out.split("\\R")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty() && trimmed.toLowerCase(Locale.ROOT).endsWith(".csv")) {
        files.add(trimmed);
      }
    }
    files.sort(String::compareTo);
    return files;
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

  /** Hints the active host from a live NT4 connection so SSH avoids probing dead addresses. */
  public void preferHost(String host) {
    if (host == null || host.isBlank()) {
      return;
    }
    String trimmed = host.trim();
    if (!candidateHosts.contains(trimmed)) {
      return;
    }
    if (!trimmed.equals(activeHost)) {
      createdRemoteDirs.clear();
      activeHost = trimmed;
    }
  }

  /** Probes candidate hosts on TCP port 22 and caches the first reachable controller address. */
  public String discoverReachableHost() {
    String found = findReachableHostOrNull();
    return found != null ? found : activeHost;
  }

  private String findReachableHostOrNull() {
    String current = activeHost;
    if (current != null && tcpProbe.test(current)) {
      return current;
    }
    for (String candidate : candidateHosts) {
      if (candidate.equals(current)) {
        continue;
      }
      if (tcpProbe.test(candidate)) {
        if (!candidate.equals(activeHost)) {
          createdRemoteDirs.clear();
        }
        activeHost = candidate;
        return candidate;
      }
    }
    return null;
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
    String host = findReachableHostOrNull();
    if (host == null) {
      return false;
    }
    try {
      String out = runSshCommandOnHost(host, "echo OK", null, COMMAND_TIMEOUT_SEC);
      return out.contains("OK");
    } catch (IOException e) {
      for (String candidate : candidateHosts) {
        if (candidate.equals(host) || !tcpProbe.test(candidate)) {
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

  private static final String PROC_JAVA_RUNNING_SNIPPET =
      "_jvm=0; for _c in /proc/[0-9]*/comm; do [ -r \"$_c\" ] && "
          + "case \"$(cat \"$_c\" 2>/dev/null)\" in java*) _jvm=1; break;; esac; done";

  private HeartbeatSample readRobotStateHeartbeatWithClock() throws IOException {
    String quotedHb = shQuote(heartbeatFilePath);
    String cmd =
        "printf 'REMOTE_EPOCH_SEC=%s\\n' \"$(date +%s)\" && "
            + PROC_JAVA_RUNNING_SNIPPET
            + "; printf 'ROBOT_JVM_RUNNING=%s\\n' \"$_jvm\" && "
            + "if [ -f "
            + quotedHb
            + " ]; then printf 'HEARTBEAT_PRESENT=1\\n'; cat "
            + quotedHb
            + "; "
            + "else printf 'HEARTBEAT_PRESENT=0\\n'; fi";
    String out = runSshCommand(cmd);
    long remoteEpochSec = -1L;
    boolean present = false;
    // Fail closed if the remote output omitted ROBOT_JVM_RUNNING (assume a robot JVM may be alive).
    boolean robotJvmRunning = true;
    StringBuilder body = new StringBuilder();
    for (String line : out.split("\\R")) {
      if (line.startsWith("REMOTE_EPOCH_SEC=")) {
        remoteEpochSec = parseLongSafe(line.substring("REMOTE_EPOCH_SEC=".length()), -1L);
      } else if (line.startsWith("ROBOT_JVM_RUNNING=")) {
        robotJvmRunning = !"0".equals(line.substring("ROBOT_JVM_RUNNING=".length()).trim());
      } else if (line.startsWith("HEARTBEAT_PRESENT=")) {
        present = "1".equals(line.substring("HEARTBEAT_PRESENT=".length()).trim());
      } else {
        body.append(line).append('\n');
      }
    }
    if (!present) {
      return new HeartbeatSample(
          false, remoteEpochSec, RobotStateHeartbeat.absent(), robotJvmRunning);
    }
    RobotStateHeartbeat hb = RobotStateHeartbeat.parse(body.toString());
    if (!hb.present()) {
      // File existed on disk but was empty or whitespace-only; treat as present-but-corrupt.
      hb = new RobotStateHeartbeat(true, false, "CORRUPT", false, false, 0L, "", 0L);
    }
    return new HeartbeatSample(true, remoteEpochSec, hb, robotJvmRunning);
  }

  @Override
  public void assertRobotDisabledForTransfer(String operationName) throws IOException {
    if (lockout != null) {
      lockout.assertSafeForTransfer(operationName);
    }
    HeartbeatSample sample = readRobotStateHeartbeatWithClock();
    if (!sample.present()) {
      if (allowMissingHeartbeat || !sample.robotJvmRunning()) {
        return;
      }
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat file is ABSENT while robot JVM is running (fail-closed; pass --allow-missing-heartbeat only for initial bootstrap); blocked "
              + operationName);
    }
    RobotStateHeartbeat hb = sample.heartbeat();
    if (!hb.valid()) {
      if (!sample.robotJvmRunning()) {
        return;
      }
      throw new IllegalStateException(
          "LOCKOUT [/tmp/flux-robot-state]: Heartbeat file is CORRUPT; blocked " + operationName);
    }
    if (hb.enabled() || hb.fmsAttached()) {
      if (!sample.robotJvmRunning()
          && sample.remoteEpochSec() > 0L
          && hb.isStale(sample.remoteEpochSec() * 1_000L, HEARTBEAT_MAX_AGE_MS)) {
        return;
      }
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
      if (!sample.robotJvmRunning()) {
        return;
      }
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
   * {@code ash} on ARMv7 roboRIO 2.0 never overflows, and permits crash-boot recovery when zero
   * {@code java} process is running on the controller.
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
            + "_hb_valid=1; _hb_stale=0; "
            + "case \"$_ep\" in ''|0*|*[!0-9]*) _hb_valid=0 ;; esac; "
            + "if [ \"$_hb_valid\" -eq 1 ]; then "
            + "_hb=${_ep%???}; [ -n \"$_hb\" ] || _hb=0; _now=$(date +%s); _age=$((_now - _hb)); "
            + "if [ \"$_age\" -gt "
            + HEARTBEAT_MAX_AGE_SEC
            + " ] || [ \"$_age\" -lt -"
            + HEARTBEAT_MAX_AGE_SEC
            + " ]; then _hb_stale=1; fi; "
            + "fi; "
            + "if [ \"$_hb_valid\" -eq 0 ]; then "
            + PROC_JAVA_RUNNING_SNIPPET
            + "; [ \"$_jvm\" -eq 0 ] || { echo 'LOCKOUT: corrupt EPOCH_MS' >&2; exit "
            + LOCKOUT_EXIT_CODE
            + "; }; "
            + "elif [ \"$_hb_stale\" -eq 1 ]; then "
            + PROC_JAVA_RUNNING_SNIPPET
            + "; [ \"$_jvm\" -eq 0 ] || { echo \"LOCKOUT: stale heartbeat age=${_age}s\" >&2; exit "
            + LOCKOUT_EXIT_CODE
            + "; }; "
            + "else "
            + "[ \"$_st\" = 'DISABLED' ] && [ \"$_en\" = 'false' ] && [ \"$_fm\" = 'false' ] "
            + "|| { echo \"LOCKOUT: state=$_st enabled=$_en fms=$_fm\" >&2; exit "
            + LOCKOUT_EXIT_CODE
            + "; }; "
            + "fi";
    if (allowMissingHeartbeat) {
      return "( if [ -f " + quotedHb + " ]; then " + verifyBody + "; fi )";
    }
    return "( if [ ! -f "
        + quotedHb
        + " ]; then "
        + PROC_JAVA_RUNNING_SNIPPET
        + "; [ \"$_jvm\" -eq 0 ] || { echo 'LOCKOUT: /tmp/flux-robot-state absent' >&2; exit "
        + LOCKOUT_EXIT_CODE
        + "; }; "
        + "else "
        + verifyBody
        + "; fi )";
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
              + " && (if command -v systemctl >/dev/null 2>&1; then "
              + "(sudo -n systemctl restart frc-robot </dev/null >/dev/null 2>&1 "
              + "|| systemctl restart frc-robot </dev/null >/dev/null 2>&1); "
              + "elif [ -x /usr/local/frc/bin/frcKillRobot.sh ]; then "
              + "/usr/local/frc/bin/frcKillRobot.sh -t -r </dev/null >/dev/null 2>&1; "
              + "else echo 'No SystemCore robot restart mechanism found' >&2; exit 1; fi)");
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
      Path tempTarget =
          target.resolveSibling(target.getFileName().toString() + ".fluxtray.part");
      try {
        runSshTransferDownloadToFile(
            buildInlineHeartbeatGuard() + " && cat " + shQuote(entry.remoteFullPath()),
            tempTarget);
        try {
          Files.move(
              tempTarget,
              target,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(tempTarget);
      }
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
    // BusyBox df on roboRIO 2.0 supports -k (not GNU -m) and may wrap long device names onto 2 lines.
    String dfParseAwk =
        "awk 'NR==1{next} NF==1{dev=$1; next} "
            + "{if(dev==\"\"){dev=$1}; avail=int($(NF-2)/1024); tot=int($(NF-4)/1024); "
            + "printf \"%s|%d|%d\\n\", dev, avail, tot; exit}'";
    String script =
        "hinfo=$(df -k "
            + shQuote(remoteHomeDir)
            + " 2>/dev/null | "
            + dfParseAwk
            + "); "
            + "hdev=${hinfo%%|*}; "
            + "hrest=${hinfo#*|}; "
            + "[ -n \"$hinfo\" ] && printf 'INT=%s\\n' \"$hrest\"; "
            + "usb='0|-1|-1'; "
            + "for u in /U /media/sda1; do "
            + "  [ -d \"$u\" ] || continue; "
            + "  uinfo=$(df -k \"$u\" 2>/dev/null | "
            + dfParseAwk
            + "); "
            + "  udev=${uinfo%%|*}; urest=${uinfo#*|}; "
            + "  if [ -n \"$udev\" ] && [ \"$udev\" != \"$hdev\" ]; then "
            + "    usb=\"1|${urest}\"; "
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
      if (!createdRemoteDirs.contains(remoteParentDir)) {
        runSshCommand("mkdir -p " + shQuote(remoteParentDir));
        createdRemoteDirs.add(remoteParentDir);
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

  private void runSshTransferDownloadToFile(String remoteCmd, Path targetFile) throws IOException {
    List<String> cmd = buildSshInvocation(discoverReachableHost(), remoteCmd);
    ProcessResult result =
        processRunner.runToFile(
            List.copyOf(cmd), null, targetFile, Duration.ofSeconds(TRANSFER_TIMEOUT_SEC));
    checkProcessResult(result);
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

  ProcessResult runCheckedResult(List<String> argv, byte[] stdin, long timeoutSeconds)
      throws IOException {
    ProcessResult result =
        processRunner.run(List.copyOf(argv), stdin, Duration.ofSeconds(timeoutSeconds));
    checkProcessResult(result);
    return result;
  }

  private static void checkProcessResult(ProcessResult result) throws IOException {
    if (result.exitCode() != 0) {
      String stderr = result.stderr() != null ? result.stderr() : "";
      if (result.exitCode() == LOCKOUT_EXIT_CODE || stderr.contains("LOCKOUT:")) {
        throw new IllegalStateException("LOCKOUT [/tmp/flux-robot-state inline]: " + stderr.trim());
      }
      throw new IOException("SSH exited with " + result.exitCode() + ": " + stderr);
    }
  }

  ProcessResult runLocalProcess(List<String> argv, byte[] stdin, Duration timeout)
      throws IOException {
    return executeLocalProcess(argv, stdin, null, timeout);
  }

  ProcessResult runLocalProcessToFile(
      List<String> argv, byte[] stdin, Path stdoutTargetFile, Duration timeout) throws IOException {
    return executeLocalProcess(argv, stdin, stdoutTargetFile, timeout);
  }

  private ProcessResult executeLocalProcess(
      List<String> argv, byte[] stdin, Path stdoutTargetFile, Duration timeout) throws IOException {
    Process p = new ProcessBuilder(argv).start();
    activeProcesses.add(p);
    Thread inWriter = null;
    try {
      final byte[][] stdoutHolder = new byte[1][];
      final byte[][] stderrHolder = new byte[1][];
      final IOException[] stdoutError = new IOException[1];
      Thread outReader =
          new Thread(
              () -> {
                if (stdoutTargetFile != null) {
                  try (OutputStream out =
                      new BufferedOutputStream(Files.newOutputStream(stdoutTargetFile), 64 * 1024)) {
                    p.getInputStream().transferTo(out);
                    out.flush();
                  } catch (IOException ex) {
                    stdoutError[0] = ex;
                  }
                } else {
                  try {
                    stdoutHolder[0] = p.getInputStream().readAllBytes();
                  } catch (IOException ignored) {
                  }
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

      // Stream stdin asynchronously so early exit-42 lockouts or stalled ssh peers never deadlock
      // write() or mask the remote exit status with Broken pipe.
      if (stdin != null && stdin.length > 0) {
        inWriter =
            new Thread(
                () -> {
                  try (OutputStream procIn = p.getOutputStream()) {
                    int offset = 0;
                    while (offset < stdin.length && !Thread.currentThread().isInterrupted()) {
                      int chunk = Math.min(64 * 1024, stdin.length - offset);
                      procIn.write(stdin, offset, chunk);
                      offset += chunk;
                    }
                    procIn.flush();
                  } catch (IOException ignored) {
                  }
                },
                "fluxtray-ssh-stdin");
        inWriter.setDaemon(true);
        inWriter.start();
      } else {
        try {
          p.getOutputStream().close();
        } catch (IOException ignored) {
        }
      }

      long timeoutMs = Math.max(1L, timeout.toMillis());
      boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!finished) {
        p.destroyForcibly();
        if (stdoutTargetFile != null) {
          Files.deleteIfExists(stdoutTargetFile);
        }
        throw new IOException("SSH/SCP command timed out after " + timeout.toSeconds() + "s");
      }
      outReader.join(stdoutTargetFile != null ? 30_000L : 2_000L);
      errReader.join(2_000L);
      int exitCode = p.exitValue();
      String stderr =
          stderrHolder[0] != null ? new String(stderrHolder[0], StandardCharsets.UTF_8) : "";
      if (stdoutTargetFile != null) {
        if (exitCode != 0) {
          Files.deleteIfExists(stdoutTargetFile);
        } else if (stdoutError[0] != null) {
          Files.deleteIfExists(stdoutTargetFile);
          throw stdoutError[0];
        }
        return new ProcessResult(exitCode, "", stderr, new byte[0]);
      }
      byte[] rawStdout = stdoutHolder[0] != null ? stdoutHolder[0] : new byte[0];
      String stdout = new String(rawStdout, StandardCharsets.UTF_8);
      return new ProcessResult(exitCode, stdout, stderr, rawStdout);
    } catch (InterruptedException e) {
      p.destroyForcibly();
      if (stdoutTargetFile != null) {
        try {
          Files.deleteIfExists(stdoutTargetFile);
        } catch (IOException ignored) {
        }
      }
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted waiting for SSH/SCP", e);
    } finally {
      activeProcesses.remove(p);
      p.destroyForcibly();
      if (inWriter != null) {
        inWriter.interrupt();
        try {
          inWriter.join(1_000L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
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
