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
 *   <li><b>Immediate Enabled / FMS Transfer Lockout:</b> Blocks and immediately aborts any SSH
 *       package deploy, {@code FluxFig} CSV reload, rollback, or log pull the millisecond the robot
 *       becomes {@code Enabled} or connects to {@code FMS}.
 *   <li><b>Observed {@code Enabled -> Disabled} 3-Second Guard:</b> Initializes {@code
 *       lastDisabledTimestampMs = -1L} so automatic pulls trigger strictly once per observed {@code
 *       Enabled -> Disabled} transition after {@code >= 3,000 ms} continuously Disabled.
 * </ol>
 */
public final class RobotSafetyLockout {

  public static final long MIN_DISABLE_DURATION_MS_FOR_LOG_PULL = 3_000L;

  /** Generation-scoped transfer lease so an aborted transfer cannot decrement a newer transfer. */
  public interface TransferLease extends AutoCloseable {
    @Override
    void close();
  }

  private final AtomicBoolean enabled = new AtomicBoolean(false);
  private final AtomicBoolean fmsAttached = new AtomicBoolean(false);
  private final AtomicLong lastDisabledTimestampMs = new AtomicLong(-1L);
  private final AtomicBoolean autoPullClaimedForCurrentDisable = new AtomicBoolean(false);
  private final AtomicInteger activeTransfers = new AtomicInteger(0);
  private final AtomicInteger abortedTransferCount = new AtomicInteger(0);
  private final List<Runnable> abortListeners = new CopyOnWriteArrayList<>();
  private long abortGeneration = 0L;

  public RobotSafetyLockout() {
    this.lastDisabledTimestampMs.set(-1L);
  }

  /** Updates robot state and immediately aborts any in-flight transfers if Enabled or on FMS. */
  public void updateState(boolean isEnabled, boolean isFmsAttached, long currentTimestampMs) {
    List<Runnable> listenersToNotify = null;
    synchronized (this) {
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
        abortGeneration++;
        int count = activeTransfers.getAndSet(0);
        if (count > 0) {
          abortedTransferCount.addAndGet(count);
        }
        listenersToNotify = List.copyOf(abortListeners);
      }
    }
    if (listenersToNotify != null) {
      // Run abort listeners outside the monitor so listener callbacks cannot deadlock or hide
      // downstream process-kill listeners when an earlier listener throws.
      for (Runnable listener : listenersToNotify) {
        try {
          listener.run();
        } catch (RuntimeException ex) {
          System.err.println(
              "FluxTray WARN: Abort listener threw during safety lockout: " + ex.getMessage());
        }
      }
    }
  }

  /**
   * Clears stale in-memory {@code Enabled}/{@code FMS} state when NT4 disconnects without arming
   * the post-disable auto-pull timer, so crash-boot recovery stays unblocked and cold boots do not
   * trigger bogus auto-pulls while robot-side {@code /tmp/flux-robot-state} stays fail-closed.
   */
  public synchronized void onRobotDisconnected() {
    this.enabled.set(false);
    this.fmsAttached.set(false);
    this.lastDisabledTimestampMs.set(-1L);
    this.autoPullClaimedForCurrentDisable.set(false);
  }

  /** Begins a generation-bound transfer lease that safely releases only if never aborted. */
  public synchronized TransferLease beginTransferLeaseOrThrow(String operationName) {
    assertSafeForTransfer(operationName);
    long leaseGen = this.abortGeneration;
    activeTransfers.incrementAndGet();
    AtomicBoolean released = new AtomicBoolean(false);
    return () -> {
      if (released.compareAndSet(false, true)) {
        endTransferForGeneration(leaseGen);
      }
    };
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

  private synchronized void endTransferForGeneration(long leaseGen) {
    if (leaseGen != this.abortGeneration) {
      return;
    }
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
