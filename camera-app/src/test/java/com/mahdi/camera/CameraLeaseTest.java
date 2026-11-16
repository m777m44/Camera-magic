package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The process-wide camera lease: a claim must never outlive its owner. */
public class CameraLeaseTest {

    @Test public void aFreshLeaseIsFree() {
        CameraLease lease = new CameraLease(1000);
        assertFalse(lease.isHeldByAnother(0));
        assertFalse(lease.isHeldByAnother(5000));
    }

    @Test public void anAcquiredLeaseBlocksAnotherOpenerUntilItExpires() {
        CameraLease lease = new CameraLease(1000);
        lease.acquire(100, false);
        assertTrue("inside the hold", lease.isHeldByAnother(500));
        assertFalse("exactly at the deadline must be allowed", lease.isHeldByAnother(1100));
        assertFalse("after the deadline", lease.isHeldByAnother(2000));
    }

    @Test public void releaseFreesTheLeaseImmediately() {
        CameraLease lease = new CameraLease(1000);
        lease.acquire(100, false);
        lease.release(true);
        assertFalse(lease.isHeldByAnother(101));
    }

    @Test public void anOwnerThatNeverClosedCannotLockTheCameraOut() {
        // The regression this guards: the owning engine's thread died, so nothing ever
        // called release. Without expiry every later open in the process would fail.
        CameraLease lease = new CameraLease(1000);
        lease.acquire(0, false);
        assertFalse("a dead owner must not block the process forever",
                lease.isHeldByAnother(60_000));
    }

    @Test public void reacquiringByTheSameOwnerDoesNotExtendTheDeadline() {
        CameraLease lease = new CameraLease(1000);
        lease.acquire(0, false);
        lease.acquire(900, true);
        assertFalse("the original deadline still applies", lease.isHeldByAnother(1000));
    }

    @Test public void releasingWithoutOwnershipLeavesTheLeaseAlone() {
        CameraLease lease = new CameraLease(1000);
        lease.acquire(0, false);
        lease.release(false);
        assertTrue(lease.isHeldByAnother(10));
    }

    @Test public void aLeaseCannotBeConstructedWithNoHold() {
        try {
            new CameraLease(0);
            throw new AssertionError("a zero hold would never protect anything");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
    }
}
