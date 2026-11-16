package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CameraMathTest {
    @Test public void rearRotationIncludesSensorAndDisplay() {
        assertEquals(90, CameraMath.rotation(90, 0, false));
        assertEquals(0, CameraMath.rotation(90, 90, false));
        assertEquals(270, CameraMath.rotation(90, 180, false));
        assertEquals(180, CameraMath.rotation(90, 270, false));
    }
    @Test public void frontRotationIsNotTheRearFormula() {
        assertEquals(270, CameraMath.rotation(270, 0, true));
        assertEquals(0, CameraMath.rotation(270, 90, true));
    }
    @Test public void exposureFitsFrameAndSensorBounds() {
        assertEquals(33_333_333L, CameraMath.exposure(100_000_000L, 1000, 1_000_000_000L, 30));
        assertEquals(1000, CameraMath.exposure(10, 1000, 1_000_000_000L, 30));
        assertEquals(20_000_000L, CameraMath.exposure(30_000_000L, 1000, 20_000_000L, 30));
    }
    @Test public void clampUsesRealDeviceRange() {
        assertEquals(100, CameraMath.clamp(20, 100, 800));
        assertEquals(800, CameraMath.clamp(3200, 100, 800));
    }
    @Test public void timerDoesNotWrapAfterOneHour() {
        assertEquals("01:01:01", CameraMath.clock(3661));
    }
}
