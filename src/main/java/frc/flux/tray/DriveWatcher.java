package frc.flux.tray;

import frc.flux.tray.BatteryAndRunManager.CompletedRunBundle;
import frc.lib.fluxfig.FluxFig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Watches the synced Google Drive {@code code/} folder (and {@code code/configs/*.csv}) with
 * SHA-256 2-poll stabilization, per-robot persisted applied state and global rejected latches in
 * {@code drive-watcher-state.json}, local fail-closed validation, pull-pending-logs-FIRST before
 * mutating the robot, atomic deploy + restart, and a {@code >= 25 s} empty-{@code code/} rollback
 * debounce. Supports offline-first sync on home Wi-Fi followed by automatic deploy upon connecting
 * to each robot ({@code FUEL}, {@code CORAL}, etc.).
 */
public final class DriveWatcher {

  public static final int REQUIRED_STABLE_POLLS = 2;
  public static final long DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS = 25_000L;
  public static final long INITIAL_UNREACHABLE_BACKOFF_MS = 2_000L;
  public static final long MAX_UNREACHABLE_BACKOFF_MS = 60_000L;
  public static final String STATE_FILE_NAME = "drive-watcher-state.json";
  public static final String BOOTSTRAP_ROBOT_KEY = "BOOTSTRAP";
  public static final String PARTIAL_CONFIGS_ERROR_MESSAGE =
      "code/configs/ requires full config folder including parameters.csv";
  public static final String UNIDENTIFIED_ROBOT_ERROR_MESSAGE =
      "Robot identity is unknown — write the robot name (e.g. CHASSIS_3, FUEL, CORAL) to /home/lvuser/robot-identity.txt before FluxTray can deploy or sync configs.";
  private static final String ROBOT_PKG_KEY_PREFIX = "robotPkg.";
  private static final String ROBOT_CFG_KEY_PREFIX = "robotCfg.";
  private static final String ROBOT_ACTIVE_KEY_PREFIX = "robotActivePkg.";

  public enum ActionType {
    NONE,
    DEPLOYED_PACKAGE,
    PUSHED_CONFIGS,
    RESTORED_STABLE,
    REJECTED_INVALID_PACKAGE,
    REJECTED_INVALID_CONFIG,
    DEFERRED_LOCKOUT
  }

  public record PollOutcome(
      ActionType action, String summary, CompletedRunBundle preDeployLogsBundle) {}

  @FunctionalInterface
  public interface NotificationSink {
    void notify(String level, String title, String message);
  }

  private final Path codeDir;
  private final Path logsDir;
  private final Path localDsLogDir;
  private final RobotTransport robot;
  private final PackageDeployer deployer;
  private final BatteryAndRunManager runManager;
  private final RobotSafetyLockout lockout;
  private final NotificationSink notifications;
  private final Path stateFile;
  private final long emptyCodeRollbackDelayMs;
  private final Consumer<Boolean> transferBusyCallback;

  private record CachedFileFingerprint(
      Path path, long sizeBytes, long lastModifiedMs, String sha256) {}

  private String pendingPackageHash = null;
  private int stablePackagePolls = 0;
  private String lastAppliedPackageHash = null;
  private String lastRejectedPackageHash = null;
  private final Map<String, String> appliedPackageHashByRobot = new LinkedHashMap<>();
  private final Map<String, Boolean> hadActivePackageByRobot = new LinkedHashMap<>();
  private int emptyPackagePolls = 0;
  private long emptyCodeSinceMs = -1L;
  private CachedFileFingerprint cachedPkgFingerprint = null;
  private String lastValidatedPackageHash = null;
  private String lastValidatedPackagePlatform = null;
  private int packageValidationExtractCount = 0;

  private String pendingConfigsHash = null;
  private int stableConfigsPolls = 0;
  private String lastAppliedConfigsHash = null;
  private String lastRejectedConfigsHash = null;
  private final Map<String, String> appliedConfigsHashByRobot = new LinkedHashMap<>();
  private String cachedConfigsMetadataSignature = null;
  private String cachedConfigsHash = null;
  private String lastValidatedConfigsHash = null;

  private String connectedRobotId = null;
  private long nextReachableProbeAllowedMs = 0L;
  private long currentUnreachableBackoffMs = INITIAL_UNREACHABLE_BACKOFF_MS;
  private long nextDeployRetryAllowedMs = 0L;
  private long currentDeployFailureBackoffMs = INITIAL_UNREACHABLE_BACKOFF_MS;
  private String failedOperationKey = null;
  private String lastErrorToastKey = null;

  public DriveWatcher(
      Path codeDir,
      Path logsDir,
      Path localDsLogDir,
      RobotTransport robot,
      PackageDeployer deployer,
      BatteryAndRunManager runManager,
      RobotSafetyLockout lockout,
      NotificationSink notifications) {
    this(
        codeDir,
        logsDir,
        localDsLogDir,
        robot,
        deployer,
        runManager,
        lockout,
        notifications,
        runManager != null ? runManager.stateDir() : defaultStateDir(),
        DEFAULT_EMPTY_CODE_ROLLBACK_DELAY_MS,
        null);
  }

  public DriveWatcher(
      Path codeDir,
      Path logsDir,
      Path localDsLogDir,
      RobotTransport robot,
      PackageDeployer deployer,
      BatteryAndRunManager runManager,
      RobotSafetyLockout lockout,
      NotificationSink notifications,
      Path stateDir,
      long emptyCodeRollbackDelayMs) {
    this(
        codeDir,
        logsDir,
        localDsLogDir,
        robot,
        deployer,
        runManager,
        lockout,
        notifications,
        stateDir,
        emptyCodeRollbackDelayMs,
        null);
  }

  public DriveWatcher(
      Path codeDir,
      Path logsDir,
      Path localDsLogDir,
      RobotTransport robot,
      PackageDeployer deployer,
      BatteryAndRunManager runManager,
      RobotSafetyLockout lockout,
      NotificationSink notifications,
      Path stateDir,
      long emptyCodeRollbackDelayMs,
      Consumer<Boolean> transferBusyCallback) {
    this.codeDir = codeDir;
    this.logsDir = logsDir;
    this.localDsLogDir = localDsLogDir;
    this.robot = robot;
    this.deployer = deployer;
    this.runManager = runManager;
    this.lockout = lockout;
    this.notifications = notifications != null ? notifications : (lvl, title, msg) -> {};
    Path resolvedStateDir = stateDir != null ? stateDir : defaultStateDir();
    this.stateFile = resolvedStateDir.resolve(STATE_FILE_NAME);
    this.emptyCodeRollbackDelayMs = Math.max(0L, emptyCodeRollbackDelayMs);
    this.transferBusyCallback =
        transferBusyCallback != null ? transferBusyCallback : ignored -> {};
    loadPersistedState();
  }

  private static Path defaultStateDir() {
    String stateProp = System.getProperty("flux.state.dir");
    if (stateProp != null && !stateProp.isBlank()) {
      return Path.of(stateProp.trim());
    }
    return Path.of(System.getProperty("user.home"), ".fluxtray");
  }

  public Path stateFile() {
    return stateFile;
  }

  /**
   * Clears unreachable-robot probe backoff and cached robot identity (called immediately when NT4
   * connects or reconnects so swapping between robots such as {@code FUEL} and {@code CORAL}
   * resolves the newly connected robot).
   */
  public synchronized void onRobotConnected() {
    connectedRobotId = null;
    resetUnreachableBackoff();
    clearOperationFailureState();
  }

  /** Clears cached robot identity when disconnected so the next connected robot is re-identified. */
  public synchronized void onRobotDisconnected() {
    connectedRobotId = null;
    clearOperationFailureState();
  }

  /** Clears unreachable-robot probe backoff and cached robot identity. */
  public synchronized void resetUnreachableBackoff() {
    connectedRobotId = null;
    nextReachableProbeAllowedMs = 0L;
    currentUnreachableBackoffMs = INITIAL_UNREACHABLE_BACKOFF_MS;
  }

  private void clearOperationFailureState() {
    nextDeployRetryAllowedMs = 0L;
    currentDeployFailureBackoffMs = INITIAL_UNREACHABLE_BACKOFF_MS;
    failedOperationKey = null;
    lastErrorToastKey = null;
  }

  private void notifyErrorOnce(String toastKey, String title, String message) {
    if (!toastKey.equals(lastErrorToastKey)) {
      lastErrorToastKey = toastKey;
      notifications.notify("ERROR", title, message);
    }
  }

  private boolean isBackedOffForOperation(String opKey, long nowMs) {
    return opKey != null
        && opKey.equals(failedOperationKey)
        && nowMs < nextDeployRetryAllowedMs;
  }

  private void recordOperationFailure(long nowMs, String opKey, String title, Throwable ex) {
    String detail =
        (ex.getMessage() != null && !ex.getMessage().isBlank())
            ? ex.getMessage().trim()
            : ex.getClass().getSimpleName();
    if (!opKey.equals(failedOperationKey)) {
      failedOperationKey = opKey;
      currentDeployFailureBackoffMs = INITIAL_UNREACHABLE_BACKOFF_MS;
    }
    nextDeployRetryAllowedMs = nowMs + currentDeployFailureBackoffMs;
    currentDeployFailureBackoffMs =
        Math.min(MAX_UNREACHABLE_BACKOFF_MS, currentDeployFailureBackoffMs * 2L);
    notifyErrorOnce(title + "|" + detail, title, detail);
  }

  synchronized int packageValidationExtractCount() {
    return packageValidationExtractCount;
  }

  private boolean checkRobotReachableWithBackoff(long nowMs) {
    if (nowMs < nextReachableProbeAllowedMs) {
      return false;
    }
    if (robot.isReachable()) {
      nextReachableProbeAllowedMs = 0L;
      currentUnreachableBackoffMs = INITIAL_UNREACHABLE_BACKOFF_MS;
      return true;
    }
    connectedRobotId = null;
    nextReachableProbeAllowedMs = nowMs + currentUnreachableBackoffMs;
    currentUnreachableBackoffMs =
        Math.min(MAX_UNREACHABLE_BACKOFF_MS, currentUnreachableBackoffMs * 2L);
    return false;
  }

  private String resolveConnectedRobotId(long nowMs) {
    if (connectedRobotId != null) {
      return connectedRobotId;
    }
    try {
      String resolved = normalizeRobotKey(robot.detectRobotIdentity());
      if (resolved == null) {
        if (robot.isBootstrapAllowed()) {
          return BOOTSTRAP_ROBOT_KEY;
        }
        notifyErrorOnce(
            "unidentified-robot",
            "FluxTray Robot Identity Unknown",
            UNIDENTIFIED_ROBOT_ERROR_MESSAGE);
        return null;
      }
      if ("unidentified-robot".equals(lastErrorToastKey)) {
        lastErrorToastKey = null;
      }
      connectedRobotId = resolved;
      return resolved;
    } catch (IOException ioEx) {
      connectedRobotId = null;
      nextReachableProbeAllowedMs = nowMs + currentUnreachableBackoffMs;
      currentUnreachableBackoffMs =
          Math.min(MAX_UNREACHABLE_BACKOFF_MS, currentUnreachableBackoffMs * 2L);
      return null;
    }
  }

  private static String normalizeRobotKey(String raw) {
    if (RobotTransport.isUnknownRobotIdentity(raw)) {
      return null;
    }
    String cleaned = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
    if (cleaned.isEmpty() || "UNKNOWN".equals(cleaned)) {
      return null;
    }
    return cleaned;
  }

  private boolean isPackageAlreadyAppliedToConnectedRobot(String pkgHash) {
    return connectedRobotId != null && pkgHash.equals(appliedPackageHashByRobot.get(connectedRobotId));
  }

  private boolean isConfigsAlreadyAppliedToConnectedRobot(String cfgHash) {
    return connectedRobotId != null && cfgHash.equals(appliedConfigsHashByRobot.get(connectedRobotId));
  }

  private boolean hasAnyActivePackage() {
    for (Boolean active : hadActivePackageByRobot.values()) {
      if (Boolean.TRUE.equals(active)) {
        return true;
      }
    }
    return false;
  }

  private String resolvePackageHashCached(Path latestPkg) throws IOException {
    long size = Files.size(latestPkg);
    long mtime = lastModifiedSafe(latestPkg);
    if (cachedPkgFingerprint != null
        && cachedPkgFingerprint.path().equals(latestPkg)
        && cachedPkgFingerprint.sizeBytes() == size
        && cachedPkgFingerprint.lastModifiedMs() == mtime) {
      String cachedHash = cachedPkgFingerprint.sha256();
      boolean settled =
          isPackageAlreadyAppliedToConnectedRobot(cachedHash)
              || cachedHash.equals(lastRejectedPackageHash)
              || (cachedHash.equals(pendingPackageHash)
                  && stablePackagePolls >= REQUIRED_STABLE_POLLS);
      if (settled) {
        return cachedHash;
      }
    }
    String computed = sha256File(latestPkg);
    cachedPkgFingerprint = new CachedFileFingerprint(latestPkg, size, mtime, computed);
    return computed;
  }

  private String resolveConfigsHashCached(Path configsDir) throws IOException {
    String metaSig = csvDirectoryMetadataSignature(configsDir);
    if (metaSig.equals(cachedConfigsMetadataSignature) && cachedConfigsHash != null) {
      boolean settled =
          isConfigsAlreadyAppliedToConnectedRobot(cachedConfigsHash)
              || cachedConfigsHash.equals(lastRejectedConfigsHash)
              || (cachedConfigsHash.equals(pendingConfigsHash)
                  && stableConfigsPolls >= REQUIRED_STABLE_POLLS);
      if (settled) {
        return cachedConfigsHash;
      }
    }
    String computed = sha256CsvDirectory(configsDir);
    cachedConfigsMetadataSignature = metaSig;
    cachedConfigsHash = computed;
    return computed;
  }

  /**
   * Executes one deterministic watch cycle (serialized onto the tray action worker in production or
   * called directly in unit tests).
   */
  public synchronized PollOutcome pollOnce(long nowMs) throws IOException {
    if (codeDir == null || !Files.isDirectory(codeDir)) {
      return new PollOutcome(ActionType.NONE, "codeDir absent", null);
    }

    Path latestPkg = findNewestFluxPkg(codeDir);
    if (latestPkg != null) {
      emptyPackagePolls = 0;
      emptyCodeSinceMs = -1L;
      String pkgHash = resolvePackageHashCached(latestPkg);
      if (isPackageAlreadyAppliedToConnectedRobot(pkgHash)) {
        if (!Boolean.TRUE.equals(hadActivePackageByRobot.get(connectedRobotId))) {
          hadActivePackageByRobot.put(connectedRobotId, Boolean.TRUE);
          persistStateQuietly();
        }
      } else if (!pkgHash.equals(lastRejectedPackageHash)) {
        if (pkgHash.equals(pendingPackageHash)) {
          stablePackagePolls++;
        } else {
          pendingPackageHash = pkgHash;
          stablePackagePolls = 1;
        }
        if (stablePackagePolls >= REQUIRED_STABLE_POLLS) {
          String platform = robot.controllerPlatform();
          if (!pkgHash.equals(lastValidatedPackageHash)
              || !platform.equals(lastValidatedPackagePlatform)) {
            try {
              packageValidationExtractCount++;
              PackageDeployer.validatePackageArchiveLocally(latestPkg, platform);
              lastValidatedPackageHash = pkgHash;
              lastValidatedPackagePlatform = platform;
            } catch (IOException | IllegalArgumentException | IllegalStateException badPkg) {
              lastRejectedPackageHash = pkgHash;
              pendingPackageHash = null;
              stablePackagePolls = 0;
              persistStateQuietly();
              String msg =
                  "Rejected invalid package "
                      + latestPkg.getFileName()
                      + ": "
                      + badPkg.getMessage();
              notifications.notify("ERROR", "FluxTray Package Rejected", msg);
              return new PollOutcome(ActionType.REJECTED_INVALID_PACKAGE, msg, null);
            }
          }

          if (lockout.isEnabled() || lockout.isFmsAttached()) {
            return new PollOutcome(
                ActionType.DEFERRED_LOCKOUT, "Deferred package deploy while Enabled/FMS", null);
          }
          if (!checkRobotReachableWithBackoff(nowMs)) {
            return new PollOutcome(ActionType.NONE, "Robot unreachable for package deploy", null);
          }
          String robotId = resolveConnectedRobotId(nowMs);
          if (robotId == null) {
            return new PollOutcome(
                ActionType.NONE, "Robot unreachable or identity unknown for package deploy", null);
          }
          if (pkgHash.equals(appliedPackageHashByRobot.get(robotId))) {
            pendingPackageHash = null;
            stablePackagePolls = 0;
            if (!BOOTSTRAP_ROBOT_KEY.equals(robotId)
                && !Boolean.TRUE.equals(hadActivePackageByRobot.get(robotId))) {
              hadActivePackageByRobot.put(robotId, Boolean.TRUE);
              persistStateQuietly();
            }
          } else {
            String opKey = "pkg:" + robotId + ":" + pkgHash;
            if (isBackedOffForOperation(opKey, nowMs)) {
              return new PollOutcome(
                  ActionType.NONE, "Backing off failed package deploy for " + robotId, null);
            }
            transferBusyCallback.accept(true);
            try {
              CompletedRunBundle preLogs = pullPendingLogsBestEffort(nowMs, "package deploy");
              PackageDeployer.DeployStamp stamp = deployer.deployPackage(latestPkg, robot, true);
              appliedPackageHashByRobot.put(robotId, pkgHash);
              if (!BOOTSTRAP_ROBOT_KEY.equals(robotId)) {
                hadActivePackageByRobot.put(robotId, Boolean.TRUE);
                lastAppliedPackageHash = pkgHash;
                lastRejectedPackageHash = null;
                persistStateQuietly();
              } else {
                lastRejectedPackageHash = null;
              }
              pendingPackageHash = null;
              stablePackagePolls = 0;
              clearOperationFailureState();
              String msg =
                  "Deployed "
                      + stamp.packageName()
                      + " to "
                      + robotId
                      + " (git="
                      + stamp.gitSha()
                      + ", cfg="
                      + stamp.configSha()
                      + ") and restarted robot.";
              notifications.notify("INFO", "FluxTray Deploy Complete", msg);
              return new PollOutcome(ActionType.DEPLOYED_PACKAGE, msg, preLogs);
            } catch (IllegalStateException stateEx) {
              if (isSafetyLockoutError(stateEx)) {
                recordOperationFailure(nowMs, opKey, "FluxTray Safety Lockout", stateEx);
                throw stateEx;
              }
              recordOperationFailure(nowMs, opKey, "FluxTray Deploy Failed", stateEx);
              return new PollOutcome(
                  ActionType.NONE, "Deploy failed (backing off): " + stateEx.getMessage(), null);
            } catch (IOException | RuntimeException deployEx) {
              recordOperationFailure(nowMs, opKey, "FluxTray Deploy Failed", deployEx);
              return new PollOutcome(
                  ActionType.NONE, "Deploy failed (backing off): " + deployEx.getMessage(), null);
            } finally {
              transferBusyCallback.accept(false);
            }
          }
        } else {
          return new PollOutcome(
              ActionType.NONE, "Stabilizing package " + latestPkg.getFileName(), null);
        }
      }
    } else if (hasAnyActivePackage()) {
      pendingPackageHash = null;
      stablePackagePolls = 0;
      boolean connectedRobotNeverHadPackage =
          connectedRobotId != null
              && !Boolean.TRUE.equals(hadActivePackageByRobot.get(connectedRobotId));
      if (!connectedRobotNeverHadPackage) {
        if (emptyCodeSinceMs < 0L) {
          emptyCodeSinceMs = nowMs;
        }
        emptyPackagePolls++;
        long elapsedEmptyMs = nowMs - emptyCodeSinceMs;
        if (emptyPackagePolls >= REQUIRED_STABLE_POLLS
            && elapsedEmptyMs >= emptyCodeRollbackDelayMs) {
          if (lockout.isEnabled() || lockout.isFmsAttached()) {
            return new PollOutcome(
                ActionType.DEFERRED_LOCKOUT, "Deferred Restore STABLE while Enabled/FMS", null);
          }
          if (!checkRobotReachableWithBackoff(nowMs)) {
            return new PollOutcome(ActionType.NONE, "Robot unreachable for Restore STABLE", null);
          }
          String robotId = resolveConnectedRobotId(nowMs);
          if (robotId == null) {
            return new PollOutcome(ActionType.NONE, "Robot unreachable for Restore STABLE", null);
          }
          if (Boolean.TRUE.equals(hadActivePackageByRobot.get(robotId))) {
            String opKey = "restore:" + robotId;
            if (isBackedOffForOperation(opKey, nowMs)) {
              return new PollOutcome(
                  ActionType.NONE, "Backing off failed Restore STABLE for " + robotId, null);
            }
            transferBusyCallback.accept(true);
            try {
              CompletedRunBundle preLogs = pullPendingLogsBestEffort(nowMs, "Restore STABLE");
              if (robot.remoteFileExists("jars/prev_stable.jar")) {
                deployer.restoreStable(robot);
                hadActivePackageByRobot.remove(robotId);
                appliedPackageHashByRobot.remove(robotId);
                lastAppliedPackageHash =
                    appliedPackageHashByRobot.isEmpty() ? null : lastAppliedPackageHash;
                emptyPackagePolls = 0;
                emptyCodeSinceMs = -1L;
                clearOperationFailureState();
                persistStateQuietly();
                String msg =
                    "code/ emptied — restored prev_stable.jar on "
                        + robotId
                        + " and restarted robot.";
                notifications.notify("INFO", "FluxTray Restored STABLE", msg);
                return new PollOutcome(ActionType.RESTORED_STABLE, msg, preLogs);
              }
              hadActivePackageByRobot.remove(robotId);
              appliedPackageHashByRobot.remove(robotId);
              lastAppliedPackageHash =
                  appliedPackageHashByRobot.isEmpty() ? null : lastAppliedPackageHash;
              emptyPackagePolls = 0;
              emptyCodeSinceMs = -1L;
              clearOperationFailureState();
              persistStateQuietly();
            } catch (IllegalStateException stateEx) {
              if (isSafetyLockoutError(stateEx)) {
                recordOperationFailure(nowMs, opKey, "FluxTray Safety Lockout", stateEx);
                throw stateEx;
              }
              recordOperationFailure(nowMs, opKey, "FluxTray Restore STABLE Failed", stateEx);
              return new PollOutcome(
                  ActionType.NONE,
                  "Restore STABLE failed (backing off): " + stateEx.getMessage(),
                  null);
            } catch (IOException | RuntimeException restoreEx) {
              recordOperationFailure(nowMs, opKey, "FluxTray Restore STABLE Failed", restoreEx);
              return new PollOutcome(
                  ActionType.NONE,
                  "Restore STABLE failed (backing off): " + restoreEx.getMessage(),
                  null);
            } finally {
              transferBusyCallback.accept(false);
            }
          }
        }
      }
    }

    Path configsDir = codeDir.resolve("configs");
    if (Files.isDirectory(configsDir) && hasAnyCsvFile(configsDir)) {
      String cfgHash = resolveConfigsHashCached(configsDir);
      if (!isConfigsAlreadyAppliedToConnectedRobot(cfgHash)
          && !cfgHash.equals(lastRejectedConfigsHash)) {
        if (cfgHash.equals(pendingConfigsHash)) {
          stableConfigsPolls++;
        } else {
          pendingConfigsHash = cfgHash;
          stableConfigsPolls = 1;
        }
        if (stableConfigsPolls >= REQUIRED_STABLE_POLLS) {
          if (!cfgHash.equals(lastValidatedConfigsHash)) {
            if (!Files.isRegularFile(configsDir.resolve("parameters.csv"))) {
              lastRejectedConfigsHash = cfgHash;
              pendingConfigsHash = null;
              stableConfigsPolls = 0;
              persistStateQuietly();
              notifications.notify(
                  "ERROR", "FluxFig Config Folder Incomplete", PARTIAL_CONFIGS_ERROR_MESSAGE);
              return new PollOutcome(
                  ActionType.REJECTED_INVALID_CONFIG, PARTIAL_CONFIGS_ERROR_MESSAGE, null);
            }
            List<String> violations;
            try {
              FluxFig localCheck = new FluxFig(configsDir);
              violations = localCheck.validateStrict();
            } catch (RuntimeException parseEx) {
              lastRejectedConfigsHash = cfgHash;
              pendingConfigsHash = null;
              stableConfigsPolls = 0;
              persistStateQuietly();
              String msg = "Blocked malformed code/configs/*.csv push: " + parseEx.getMessage();
              notifications.notify("ERROR", "FluxFig Validation Blocked", msg);
              return new PollOutcome(ActionType.REJECTED_INVALID_CONFIG, msg, null);
            }
            if (!violations.isEmpty()) {
              lastRejectedConfigsHash = cfgHash;
              pendingConfigsHash = null;
              stableConfigsPolls = 0;
              persistStateQuietly();
              String msg = "Blocked unsafe code/configs/*.csv push: " + violations.get(0);
              notifications.notify("ERROR", "FluxFig Validation Blocked", msg);
              return new PollOutcome(ActionType.REJECTED_INVALID_CONFIG, msg, null);
            }
            lastValidatedConfigsHash = cfgHash;
          }
          if (lockout.isEnabled() || lockout.isFmsAttached()) {
            return new PollOutcome(
                ActionType.DEFERRED_LOCKOUT, "Deferred config push while Enabled/FMS", null);
          }
          if (!checkRobotReachableWithBackoff(nowMs)) {
            return new PollOutcome(ActionType.NONE, "Robot unreachable for config push", null);
          }
          String robotId = resolveConnectedRobotId(nowMs);
          if (robotId == null) {
            return new PollOutcome(
                ActionType.NONE, "Robot unreachable or identity unknown for config push", null);
          }
          if (cfgHash.equals(appliedConfigsHashByRobot.get(robotId))) {
            pendingConfigsHash = null;
            stableConfigsPolls = 0;
          } else {
            String opKey = "cfg:" + robotId + ":" + cfgHash;
            if (isBackedOffForOperation(opKey, nowMs)) {
              return new PollOutcome(
                  ActionType.NONE, "Backing off failed config push for " + robotId, null);
            }
            transferBusyCallback.accept(true);
            try {
              CompletedRunBundle preLogs = pullPendingLogsBestEffort(nowMs, "config push");
              String deployedSha =
                  deployer.pushFluxFigConfigs(configsDir, null, FluxFig.Mode.PRODUCTION, robot);
              appliedConfigsHashByRobot.put(robotId, cfgHash);
              if (!BOOTSTRAP_ROBOT_KEY.equals(robotId)) {
                lastAppliedConfigsHash = cfgHash;
                lastRejectedConfigsHash = null;
                persistStateQuietly();
              } else {
                lastRejectedConfigsHash = null;
              }
              pendingConfigsHash = null;
              stableConfigsPolls = 0;
              clearOperationFailureState();
              String msg =
                  "Pushed code/configs/*.csv to "
                      + robotId
                      + " (sha="
                      + deployedSha
                      + ") and restarted robot.";
              notifications.notify("INFO", "FluxTray Configs Pushed", msg);
              return new PollOutcome(ActionType.PUSHED_CONFIGS, msg, preLogs);
            } catch (IllegalStateException stateEx) {
              if (isSafetyLockoutError(stateEx)) {
                recordOperationFailure(nowMs, opKey, "FluxTray Safety Lockout", stateEx);
                throw stateEx;
              }
              recordOperationFailure(nowMs, opKey, "FluxTray Config Push Failed", stateEx);
              return new PollOutcome(
                  ActionType.NONE,
                  "Config push failed (backing off): " + stateEx.getMessage(),
                  null);
            } catch (IOException | RuntimeException pushEx) {
              recordOperationFailure(nowMs, opKey, "FluxTray Config Push Failed", pushEx);
              return new PollOutcome(
                  ActionType.NONE,
                  "Config push failed (backing off): " + pushEx.getMessage(),
                  null);
            } finally {
              transferBusyCallback.accept(false);
            }
          }
        }
      }
    }

    return new PollOutcome(ActionType.NONE, "Idle", null);
  }

  private CompletedRunBundle pullPendingLogsBestEffort(long nowMs, String actionLabel) {
    try {
      return pullPendingLogsBeforeMutating(nowMs);
    } catch (IllegalStateException lockoutEx) {
      if (isSafetyLockoutError(lockoutEx)) {
        throw lockoutEx;
      }
      notifications.notify(
          "WARN",
          "FluxTray Pre-Deploy Log Pull Skipped",
          "Continuing " + actionLabel + " after pre-pull error: " + lockoutEx.getMessage());
      return null;
    } catch (IOException | RuntimeException ex) {
      notifications.notify(
          "WARN",
          "FluxTray Pre-Deploy Log Pull Skipped",
          "Continuing " + actionLabel + " after pre-pull error: " + ex.getMessage());
      return null;
    }
  }

  private boolean isSafetyLockoutError(IllegalStateException ex) {
    if (lockout.isEnabled() || lockout.isFmsAttached()) {
      return true;
    }
    String msg = ex.getMessage();
    return msg != null && msg.toUpperCase(Locale.ROOT).contains("LOCKOUT");
  }

  private CompletedRunBundle pullPendingLogsBeforeMutating(long nowMs) throws IOException {
    return runManager.pullDriveSafeRunZip(
        "predeploy_" + nowMs,
        null,
        robot,
        localDsLogDir,
        logsDir,
        nowMs,
        false,
        true,
        true);
  }

  private void loadPersistedState() {
    if (stateFile == null || !Files.isRegularFile(stateFile)) {
      return;
    }
    try {
      Map<String, String> m =
          PackageDeployer.parseFlatJson(Files.readString(stateFile, StandardCharsets.UTF_8));
      lastAppliedPackageHash = blankToNull(m.get("lastAppliedPackageHash"));
      lastAppliedConfigsHash = blankToNull(m.get("lastAppliedConfigsHash"));
      lastRejectedPackageHash = blankToNull(m.get("lastRejectedPackageHash"));
      lastRejectedConfigsHash = blankToNull(m.get("lastRejectedConfigsHash"));

      for (Map.Entry<String, String> entry : m.entrySet()) {
        String key = entry.getKey();
        if (key.startsWith(ROBOT_PKG_KEY_PREFIX)) {
          String robotKey = normalizeRobotKey(key.substring(ROBOT_PKG_KEY_PREFIX.length()));
          String val = blankToNull(entry.getValue());
          if (robotKey != null && !BOOTSTRAP_ROBOT_KEY.equals(robotKey) && val != null) {
            appliedPackageHashByRobot.put(robotKey, val);
          }
        } else if (key.startsWith(ROBOT_CFG_KEY_PREFIX)) {
          String robotKey = normalizeRobotKey(key.substring(ROBOT_CFG_KEY_PREFIX.length()));
          String val = blankToNull(entry.getValue());
          if (robotKey != null && !BOOTSTRAP_ROBOT_KEY.equals(robotKey) && val != null) {
            appliedConfigsHashByRobot.put(robotKey, val);
          }
        } else if (key.startsWith(ROBOT_ACTIVE_KEY_PREFIX)) {
          String robotKey = normalizeRobotKey(key.substring(ROBOT_ACTIVE_KEY_PREFIX.length()));
          if (robotKey != null
              && !BOOTSTRAP_ROBOT_KEY.equals(robotKey)
              && "true".equalsIgnoreCase(entry.getValue())) {
            hadActivePackageByRobot.put(robotKey, Boolean.TRUE);
          }
        }
      }
      for (String robotKey : appliedPackageHashByRobot.keySet()) {
        hadActivePackageByRobot.putIfAbsent(robotKey, Boolean.TRUE);
      }
    } catch (IOException ignored) {
    }
  }

  private void persistStateQuietly() {
    if (stateFile == null) {
      return;
    }
    try {
      if (stateFile.getParent() != null) {
        Files.createDirectories(stateFile.getParent());
      }
      StringBuilder sb = new StringBuilder(512);
      sb.append("{\n");
      sb.append("  \"lastAppliedPackageHash\": \"").append(esc(lastAppliedPackageHash)).append("\",\n");
      sb.append("  \"lastAppliedConfigsHash\": \"").append(esc(lastAppliedConfigsHash)).append("\",\n");
      sb.append("  \"lastRejectedPackageHash\": \"").append(esc(lastRejectedPackageHash)).append("\",\n");
      sb.append("  \"lastRejectedConfigsHash\": \"").append(esc(lastRejectedConfigsHash)).append("\",\n");
      sb.append("  \"hadActivePackage\": ").append(hasAnyActivePackage());
      TreeSet<String> allRobots = new TreeSet<>();
      allRobots.addAll(appliedPackageHashByRobot.keySet());
      allRobots.addAll(appliedConfigsHashByRobot.keySet());
      allRobots.addAll(hadActivePackageByRobot.keySet());
      for (String robotKey : allRobots) {
        if (BOOTSTRAP_ROBOT_KEY.equals(robotKey) || RobotTransport.isUnknownRobotIdentity(robotKey)) {
          continue;
        }
        String pkg = appliedPackageHashByRobot.get(robotKey);
        if (pkg != null) {
          sb.append(",\n  \"")
              .append(ROBOT_PKG_KEY_PREFIX)
              .append(esc(robotKey))
              .append("\": \"")
              .append(esc(pkg))
              .append("\"");
        }
        String cfg = appliedConfigsHashByRobot.get(robotKey);
        if (cfg != null) {
          sb.append(",\n  \"")
              .append(ROBOT_CFG_KEY_PREFIX)
              .append(esc(robotKey))
              .append("\": \"")
              .append(esc(cfg))
              .append("\"");
        }
        if (Boolean.TRUE.equals(hadActivePackageByRobot.get(robotKey))) {
          sb.append(",\n  \"")
              .append(ROBOT_ACTIVE_KEY_PREFIX)
              .append(esc(robotKey))
              .append("\": true");
        }
      }
      sb.append("\n}\n");
      String json = sb.toString();
      Path tmp = stateFile.resolveSibling(stateFile.getFileName().toString() + ".tmp");
      Files.writeString(tmp, json, StandardCharsets.UTF_8);
      try {
        Files.move(
            tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ignored) {
    }
  }

  private static boolean hasAnyCsvFile(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isRegularFile)
          .anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"));
    }
  }

  private static Path findNewestFluxPkg(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isRegularFile)
          .filter(
              p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return !n.startsWith(".")
                    && !n.endsWith(".part")
                    && !n.endsWith(".tmp")
                    && n.endsWith("fluxpkg.zip");
              })
          .max(Comparator.comparingLong(DriveWatcher::lastModifiedSafe))
          .orElse(null);
    }
  }

  private static long lastModifiedSafe(Path p) {
    try {
      return Files.getLastModifiedTime(p).toMillis();
    } catch (IOException e) {
      return 0L;
    }
  }

  static String sha256File(Path file) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[16 * 1024];
      try (InputStream in = Files.newInputStream(file)) {
        int n;
        while ((n = in.read(buf)) >= 0) {
          if (n > 0) {
            md.update(buf, 0, n);
          }
        }
      }
      return toHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String csvDirectoryMetadataSignature(Path dir) throws IOException {
    StringBuilder sb = new StringBuilder(256);
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p :
          s.filter(Files::isRegularFile)
              .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
              .sorted(Comparator.comparing(f -> f.getFileName().toString()))
              .toList()) {
        sb.append(p.getFileName().toString())
            .append('|')
            .append(Files.size(p))
            .append('|')
            .append(lastModifiedSafe(p))
            .append('\n');
      }
    }
    return sb.toString();
  }

  static String sha256CsvDirectory(Path dir) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (Stream<Path> s = Files.list(dir)) {
        for (Path p :
            s.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                .toList()) {
          md.update(p.getFileName().toString().getBytes(StandardCharsets.UTF_8));
          md.update((byte) 0);
          md.update(Files.readAllBytes(p));
          md.update((byte) 0);
        }
      }
      return toHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  private static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format(Locale.US, "%02x", b));
    }
    return sb.toString();
  }
}
