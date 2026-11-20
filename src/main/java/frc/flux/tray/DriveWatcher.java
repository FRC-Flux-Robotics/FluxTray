package frc.flux.tray;

import frc.flux.tray.BatteryAndRunManager.CompletedRunBundle;
import frc.lib.fluxfig.FluxFig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Watches the synced Google Drive {@code code/} folder (and {@code code/configs/*.csv}) with
 * SHA-256 2-poll stabilization ({@code ~2 s}), runs local {@link FluxFig#validateStrict()}, pulls
 * pending robot logs into {@code logs/} FIRST before mutating the robot, atomically deploys +
 * restarts, and safely restores {@code prev_stable} if {@code code/} is emptied.
 */
public final class DriveWatcher {

  public static final int REQUIRED_STABLE_POLLS = 2;

  public enum ActionType {
    NONE,
    DEPLOYED_PACKAGE,
    PUSHED_CONFIGS,
    RESTORED_STABLE,
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

  private String pendingPackageHash = null;
  private int stablePackagePolls = 0;
  private String lastAppliedPackageHash = null;
  private boolean hadActivePackage = false;
  private int emptyPackagePolls = 0;

  private String pendingConfigsHash = null;
  private int stableConfigsPolls = 0;
  private String lastAppliedConfigsHash = null;
  private String lastRejectedConfigsHash = null;

  public DriveWatcher(
      Path codeDir,
      Path logsDir,
      Path localDsLogDir,
      RobotTransport robot,
      PackageDeployer deployer,
      BatteryAndRunManager runManager,
      RobotSafetyLockout lockout,
      NotificationSink notifications) {
    this.codeDir = codeDir;
    this.logsDir = logsDir;
    this.localDsLogDir = localDsLogDir;
    this.robot = robot;
    this.deployer = deployer;
    this.runManager = runManager;
    this.lockout = lockout;
    this.notifications = notifications != null ? notifications : (lvl, title, msg) -> {};
  }

  /**
   * Executes one deterministic watch cycle (called every ~1 s by the tray scheduler or directly in
   * unit tests).
   */
  public synchronized PollOutcome pollOnce(long nowMs) throws IOException {
    if (codeDir == null || !Files.isDirectory(codeDir)) {
      return new PollOutcome(ActionType.NONE, "codeDir absent", null);
    }

    Path latestPkg = findNewestFluxPkg(codeDir);
    if (latestPkg != null) {
      emptyPackagePolls = 0;
      String pkgHash = sha256File(latestPkg);
      if (pkgHash.equals(lastAppliedPackageHash)) {
        hadActivePackage = true;
      } else {
        if (pkgHash.equals(pendingPackageHash)) {
          stablePackagePolls++;
        } else {
          pendingPackageHash = pkgHash;
          stablePackagePolls = 1;
        }
        if (stablePackagePolls >= REQUIRED_STABLE_POLLS) {
          if (lockout.isEnabled() || lockout.isFmsAttached()) {
            return new PollOutcome(
                ActionType.DEFERRED_LOCKOUT, "Deferred package deploy while Enabled/FMS", null);
          }
          if (!robot.isReachable()) {
            return new PollOutcome(ActionType.NONE, "Robot unreachable for package deploy", null);
          }
          CompletedRunBundle preLogs = pullPendingLogsBeforeMutating(nowMs);
          PackageDeployer.DeployStamp stamp = deployer.deployPackage(latestPkg, robot, true);
          lastAppliedPackageHash = pkgHash;
          pendingPackageHash = null;
          stablePackagePolls = 0;
          hadActivePackage = true;
          String msg =
              "Deployed "
                  + stamp.packageName()
                  + " (git="
                  + stamp.gitSha()
                  + ", cfg="
                  + stamp.configSha()
                  + ") and restarted robot.";
          notifications.notify("INFO", "FluxTray Deploy Complete", msg);
          return new PollOutcome(ActionType.DEPLOYED_PACKAGE, msg, preLogs);
        }
        return new PollOutcome(ActionType.NONE, "Stabilizing package " + latestPkg.getFileName(), null);
      }
    } else if (hadActivePackage) {
      pendingPackageHash = null;
      stablePackagePolls = 0;
      emptyPackagePolls++;
      if (emptyPackagePolls >= REQUIRED_STABLE_POLLS) {
        if (lockout.isEnabled() || lockout.isFmsAttached()) {
          return new PollOutcome(
              ActionType.DEFERRED_LOCKOUT, "Deferred Restore STABLE while Enabled/FMS", null);
        }
        if (!robot.isReachable()) {
          return new PollOutcome(ActionType.NONE, "Robot unreachable for Restore STABLE", null);
        }
        CompletedRunBundle preLogs = pullPendingLogsBeforeMutating(nowMs);
        if (robot.remoteFileExists("jars/prev_stable.jar")) {
          deployer.restoreStable(robot);
          hadActivePackage = false;
          lastAppliedPackageHash = null;
          emptyPackagePolls = 0;
          String msg = "code/ emptied — restored prev_stable.jar and restarted robot.";
          notifications.notify("INFO", "FluxTray Restored STABLE", msg);
          return new PollOutcome(ActionType.RESTORED_STABLE, msg, preLogs);
        }
        hadActivePackage = false;
        lastAppliedPackageHash = null;
        emptyPackagePolls = 0;
      }
    }

    Path configsDir = codeDir.resolve("configs");
    if (Files.isDirectory(configsDir) && Files.isRegularFile(configsDir.resolve("parameters.csv"))) {
      String cfgHash = sha256CsvDirectory(configsDir);
      if (!cfgHash.equals(lastAppliedConfigsHash) && !cfgHash.equals(lastRejectedConfigsHash)) {
        if (cfgHash.equals(pendingConfigsHash)) {
          stableConfigsPolls++;
        } else {
          pendingConfigsHash = cfgHash;
          stableConfigsPolls = 1;
        }
        if (stableConfigsPolls >= REQUIRED_STABLE_POLLS) {
          FluxFig localCheck = new FluxFig(configsDir);
          List<String> violations = localCheck.validateStrict();
          if (!violations.isEmpty()) {
            lastRejectedConfigsHash = cfgHash;
            pendingConfigsHash = null;
            stableConfigsPolls = 0;
            String msg = "Blocked unsafe code/configs/*.csv push: " + violations.get(0);
            notifications.notify("ERROR", "FluxFig Validation Blocked", msg);
            return new PollOutcome(ActionType.REJECTED_INVALID_CONFIG, msg, null);
          }
          if (lockout.isEnabled() || lockout.isFmsAttached()) {
            return new PollOutcome(
                ActionType.DEFERRED_LOCKOUT, "Deferred config push while Enabled/FMS", null);
          }
          if (!robot.isReachable()) {
            return new PollOutcome(ActionType.NONE, "Robot unreachable for config push", null);
          }
          CompletedRunBundle preLogs = pullPendingLogsBeforeMutating(nowMs);
          String deployedSha =
              deployer.pushFluxFigConfigs(configsDir, null, FluxFig.Mode.PRODUCTION, robot);
          lastAppliedConfigsHash = cfgHash;
          pendingConfigsHash = null;
          stableConfigsPolls = 0;
          String msg = "Pushed code/configs/*.csv (sha=" + deployedSha + ") and restarted robot.";
          notifications.notify("INFO", "FluxTray Configs Pushed", msg);
          return new PollOutcome(ActionType.PUSHED_CONFIGS, msg, preLogs);
        }
      }
    }

    return new PollOutcome(ActionType.NONE, "Idle", null);
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

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format(Locale.US, "%02x", b));
    }
    return sb.toString();
  }
}
