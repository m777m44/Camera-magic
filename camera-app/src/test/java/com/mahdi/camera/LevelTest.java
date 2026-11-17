package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A level that lies is worse than no level: test the signs, the smoothing and the locks. */
public class LevelTest {
    private static Level settled(float x, float y, float z) {
        Level level = new Level();
        for (int pass = 0; pass < 200; pass++) level.update(x, y, z, 0.5f);
        return level;
    }

    @Test public void phoneHeldUprightIsLevel() {
        Level level = settled(0f, 9.81f, 0f);
        assertTrue(level.describe(), level.isLevel());
        assertEquals("LEVEL", level.describe());
        assertEquals(0f, level.roll(), 0.01f);
    }

    @Test public void tiltingRightGivesPositiveRollAndACorrectionTheOtherWay() {
        Level level = settled(0.34f, 9.8f, 0f); // ~2° roll to the right
        assertTrue("roll " + level.roll(), level.roll() > 1f);
        assertFalse(level.isLevel());
        assertTrue(level.isNearLevel());
        assertEquals(-level.roll(), level.correction(), 1e-6f);
        assertTrue(level.describe().startsWith("↻"));
    }

    @Test public void tiltingLeftIsTheMirrorImage() {
        Level right = settled(0.34f, 9.8f, 0f);
        Level left = settled(-0.34f, 9.8f, 0f);
        assertEquals(-right.roll(), left.roll(), 0.01f);
        assertTrue(left.describe().startsWith("↺"));
    }

    @Test public void farTiltIsNotNearLevel() {
        Level level = settled(6f, 4f, 0f);
        assertFalse(level.isLevel());
        assertFalse(level.isNearLevel());
    }

    @Test public void pointingAtTheFloorDisablesRollAndDoesNotClaimLevel() {
        Level level = settled(3f, 3f, 9f);
        assertEquals(0f, level.roll(), 1e-6f);
        assertFalse(level.isLevel()); // held flat, "level" would be meaningless
    }

    @Test public void freeFallKeepsTheLastReading() {
        Level level = new Level();
        level.update(0.8f, 9.8f, 0f, 0f); // a real reading first
        float before = level.roll();
        level.update(0f, 0f, 0f, 0.9f); // free fall carries no gravity direction
        assertEquals(before, level.roll(), 1e-6f);
        assertFalse(Float.isNaN(level.pitch()));
    }

    @Test public void lockTicksCountOnlyFreshLevels() {
        Level level = new Level();
        level.reset();
        int before = level.lockedTicks();
        level.update(0f, 9.81f, 0f, 0.9f);
        assertEquals(before, level.lockedTicks()); // it was already level
        level.update(1.2f, 9.7f, 0f, 0f);  // ~7° off: definitely not level
        assertFalse(level.isLevel());
        level.update(0f, 9.81f, 0f, 0f);
        assertEquals(before + 1, level.lockedTicks());
    }
}
