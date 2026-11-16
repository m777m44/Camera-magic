package com.mahdi.camera;

/**
 * Process-wide ownership of the single camera device.
 *
 * <p>Two {@link CameraEngine} instances overlap during an Activity recreation: the old one
 * is still closing its {@code CameraDevice} while the new one wants to open it. Android
 * rejects a reopen before the previous close completes, so opens are serialized here.
 *
 * <p>The lease is an <b>expiring</b> claim, not a semaphore permit. A permit can be lost for
 * good if the owning engine's thread dies before it closes the device (a stopped Activity, a
 * drained main thread), and then every later open in the process fails with "still closing".
 * A deadline can always expire, so a stuck engine can never lock the camera out.
 *
 * <p>Deliberately free of Android imports: {@code scripts/run_engine_tests.py} compiles and
 * tests it on a plain JVM, where the real bug this guards against is reproducible.
 */
public final class CameraLease {
    /** How long an owner may hold the lease before another open may proceed regardless. */
    public static final long DEFAULT_HOLD_MS = 4000L;

    private final long holdMs;
    private long heldUntil;

    public CameraLease() {
        this(DEFAULT_HOLD_MS);
    }

    public CameraLease(long holdMs) {
        if (holdMs <= 0) throw new IllegalArgumentException("A hold must be positive");
        this.holdMs = holdMs;
    }

    /** True while another owner's unexpired claim is still in force. */
    public boolean isHeldByAnother(long now) {
        return heldUntil > now;
    }

    /** Claim the lease for the caller; idempotent while the caller still owns it. */
    public void acquire(long now, boolean alreadyOwner) {
        if (!alreadyOwner) heldUntil = now + holdMs;
    }

    /** Give the lease up; a no-op for a caller that never owned it. */
    public void release(boolean owner) {
        if (owner) heldUntil = 0L;
    }

    /** Timestamp the current claim expires at, 0 when free. Exposed for tests and diagnostics. */
    public long heldUntil() {
        return heldUntil;
    }
}
