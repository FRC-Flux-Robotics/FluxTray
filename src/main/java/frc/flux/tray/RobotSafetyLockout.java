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
 *   <li><b>Observed {@code Enabled -> Disabled} 3-Second Guard:</b> Initializes {@code
 *       lastDisabledTimestampMs = -1L} so automatic pulls trigger strictly once per observed {@code
 *       Enabled -> Disabled} transition after {@code >= 3,000 ms} continuously Disabled.
 * </ol>
 */
public final class RobotSafetyLockout {

  public static final long MIN_DISABLE_DURATION_MS_FOR_LOG_PULL = 3_000L;

  private final AtomicBoolean enabled = new AtomicBoolean(false);
  private final AtomicBoolean fmsAttached = new AtomicBoolean(false);
  private final AtomicLong lastDisabledTimestampMs = new AtomicLong(-1L);
  private final AtomicBoolean autoPullClaimedForCurrentDisable = new AtomicBoolean(false);
  private final AtomicInteger activeTransfers = new AtomicInteger(0);
  private final AtomicInteger abortedTransferCount = new AtomicInteger(0);
  private final List<Runnable> abortListeners = new CopyOnWriteArrayList<>();

  public RobotSafetyLockout() {
    this(-1L);
  }

  /**
   * Constructs a lockout tracker initialized to {@code lastDisabledTimestampMs = -1L} so an idle
   * robot never triggers spurious auto-pulls before an observed {@code Enabled -> Disabled} cycle.
   */
  public RobotSafetyLockout(long ignoredInitialTimestampMs) {
    this.lastDisabledTimestampMs.set(-1L);
  }

  /** Updates robot state and immediately aborts any in-flight transfers if Enabled or on FMS. */
  public synchronized void updateState(
      boolean isEnabled, boolean isFmsAttached, long currentTimestampMs) {
    boolean wasEnabled = this.enabled.getAndSet(isEnabled);
    this.fmsAttached.set(isFmsAttached);
    if (isEnabled) {
      this.lastDisabledTimestampMs.set(-1L);
      this.autoPullClaimedForCurrentDisable.set(false);
    } else if (wasEnabled) {
      this.lastDisabledTimestampMs.set(currentTimestampMs);
      this.autoPullClaimedForCurrentDisable.set(false);
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
   * Returns {@code true} when an {@code Enabled -> Disabled} transition has been observed, the
   * robot is not Enabled or on FMS, and at least {@code 3.0 seconds} have elapsed since disable.
   */
  public boolean isDisabledStabilizedForAutoPull(long nowMs) {
    if (enabled.get() || fmsAttached.get()) {
      return false;
    }
    long disabledAt = lastDisabledTimestampMs.get();
    if (disabledAt < 0L) {
      return false;
    }
    return (nowMs - disabledAt) >= MIN_DISABLE_DURATION_MS_FOR_LOG_PULL;
  }

  /**
   * Returns {@code true} only when an unclaimed {@code Enabled -> Disabled} transition has
   * stabilized for {@code >= 3.0 seconds} off-FMS.
   */
  public boolean isReadyForAutoLogPull(long nowMs) {
    return !autoPullClaimedForCurrentDisable.get() && isDisabledStabilizedForAutoPull(nowMs);
  }

  /**
   * Atomically claims the automatic post-disable log pull so it fires at most once per {@code
   * Enabled -> Disabled} transition.
   */
  public synchronized boolean claimAutoLogPull(long nowMs) {
    if (!isReadyForAutoLogPull(nowMs)) {
      return false;
    }
    autoPullClaimedForCurrentDisable.set(true);
    return true;
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

  public long lastDisabledTimestampMs() {
    return lastDisabledTimestampMs.get();
  }

  public int activeTransferCount() {
    return activeTransfers.get();
  }

  public int abortedTransferCount() {
    return abortedTransferCount.get();
  }
}
