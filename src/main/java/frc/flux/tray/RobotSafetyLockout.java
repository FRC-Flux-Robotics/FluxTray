package frc.flux.tray;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enforces strict competition and practice safety lockouts for {@code FluxTray.jar}:
 *
 * <ol>
 *   <li><b>Immediate Enabled / FMS Transfer Lockout:</b> Blocks and immediately aborts any SFTP
 *       package deploy, {@code FluxFig} CSV hot-reload, rollback, or log pull the millisecond the
 *       robot becomes {@code Enabled} or connects to {@code FMS}.
 *   <li><b>3-Second Post-Disable Stabilization Guard:</b> Requires the robot to be continuously
 *       {@code Disabled} for at least {@code 3,000 ms} before auto-pulling closed {@code .wpilog},
 *       {@code .hoot}, and {@code system-snapshot.txt} files over direct robot Wi-Fi.
 * </ol>
 */
public final class RobotSafetyLockout {

  public static final long MIN_DISABLE_DURATION_MS_FOR_LOG_PULL = 3_000L;

  private final AtomicBoolean enabled = new AtomicBoolean(false);
  private final AtomicBoolean fmsAttached = new AtomicBoolean(false);
  private final AtomicLong lastDisabledTimestampMs = new AtomicLong(0L);
  private final AtomicInteger activeTransfers = new AtomicInteger(0);
  private final AtomicInteger abortedTransferCount = new AtomicInteger(0);
  private final List<Runnable> abortListeners = new CopyOnWriteArrayList<>();

  public RobotSafetyLockout() {
    this(System.currentTimeMillis());
  }

  public RobotSafetyLockout(long initialTimestampMs) {
    this.lastDisabledTimestampMs.set(initialTimestampMs);
  }

  /** Updates robot state and immediately aborts any in-flight transfers if Enabled or on FMS. */
  public synchronized void updateState(
      boolean isEnabled, boolean isFmsAttached, long currentTimestampMs) {
    boolean wasEnabled = this.enabled.getAndSet(isEnabled);
    this.fmsAttached.set(isFmsAttached);
    if (!isEnabled && wasEnabled) {
      this.lastDisabledTimestampMs.set(currentTimestampMs);
    }
    if (isEnabled || isFmsAttached) {
      abortAllInFlightTransfers();
    }
  }

  public synchronized void beginTransferOrThrow(String operationName) {
    assertSafeForTransfer(operationName);
    activeTransfers.incrementAndGet();
  }

  public synchronized void endTransfer() {
    int cur = activeTransfers.get();
    if (cur > 0) {
      activeTransfers.decrementAndGet();
    }
  }

  /** Throws {@link IllegalStateException} if the robot is currently Enabled or attached to FMS. */
  public void assertSafeForTransfer(String operationName) {
    if (enabled.get()) {
      throw new IllegalStateException(
          "LOCKOUT: Cannot execute '" + operationName + "' while robot is ENABLED.");
    }
    if (fmsAttached.get()) {
      throw new IllegalStateException(
          "LOCKOUT: Cannot execute '"
              + operationName
              + "' while connected to FMS (Capture on Field, Sync in Pit mode active).");
    }
  }

  /**
   * Returns {@code true} only when the robot is Disabled, not on FMS, and has been Disabled for at
   * least {@code 3.0 seconds} so {@code WPILOGWriter} and CTRE {@code SignalLogger} have flushed.
   */
  public boolean isReadyForAutoLogPull(long nowMs) {
    if (enabled.get() || fmsAttached.get()) {
      return false;
    }
    return (nowMs - lastDisabledTimestampMs.get()) >= MIN_DISABLE_DURATION_MS_FOR_LOG_PULL;
  }

  public void addAbortListener(Runnable listener) {
    abortListeners.add(listener);
  }

  private void abortAllInFlightTransfers() {
    int count = activeTransfers.getAndSet(0);
    if (count > 0) {
      abortedTransferCount.addAndGet(count);
    }
    for (Runnable listener : abortListeners) {
      listener.run();
    }
  }

  public boolean isEnabled() {
    return enabled.get();
  }

  public boolean isFmsAttached() {
    return fmsAttached.get();
  }

  public int activeTransferCount() {
    return activeTransfers.get();
  }

  public int abortedTransferCount() {
    return abortedTransferCount.get();
  }
}
