package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EasingTest {
    @Test public void everyCurvePassesThroughBothEnds() {
        for (int index = 0; index < 3; index++) {
            float end = index == 0 ? Easing.smooth(1f) : index == 1 ? Easing.decelerate(1f) : Easing.accelerate(1f);
            assertEquals(1f, end, 1e-5f);
        }
        assertEquals(0f, Easing.smooth(0f), 0f);
        assertEquals(0f, Easing.decelerate(0f), 0f);
        assertEquals(0f, Easing.accelerate(0f), 0f);
        assertEquals(1f, Easing.overshoot(1f), 1e-4f);
    }

    @Test public void curvesAreMonotonicWhereTheyShouldBe() {
        float previous = -1f;
        for (int step = 0; step <= 20; step++) {
            float value = Easing.smooth(step / 20f);
            assertTrue("smooth must not go backwards", value >= previous);
            previous = value;
        }
        assertTrue(Easing.decelerate(0.5f) > Easing.accelerate(0.5f));
    }

    @Test public void overshootActuallyOvershootsThenSettles() {
        float peak = 0f;
        for (int step = 0; step <= 100; step++) peak = Math.max(peak, Easing.overshoot(step / 100f));
        assertTrue("peak " + peak, peak > 1.02f && peak < 1.2f);
    }

    @Test public void outOfRangeInputIsClampedNotThrown() {
        assertEquals(0f, Easing.smooth(-4f), 0f);
        assertEquals(1f, Easing.smooth(9f), 0f);
        assertEquals(0f, Easing.clamp01(Float.NaN), 0f);
        assertEquals(0f, Easing.clamp01(-1f), 0f);
        assertEquals(1f, Easing.clamp01(2f), 0f);
    }

    @Test public void pulseBreathesBetweenZeroAndOne() {
        float min = 1f;
        float max = 0f;
        for (long millis = 0; millis <= 1200; millis += 10) {
            float value = Easing.pulse(millis, 1200);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        assertEquals(0f, min, 1e-3f);
        assertEquals(1f, max, 1e-3f);
        assertEquals(Easing.pulse(0, 1200), Easing.pulse(1200, 1200), 1e-3f);
    }

    @Test public void colourLerpHitsBothEndsAndStaysOpaque() {
        int from = 0xFF0B0F14;
        int to = 0xFF51D6C7;
        assertEquals(from, Easing.lerpArgb(from, to, 0f));
        assertEquals(to, Easing.lerpArgb(from, to, 1f));
        assertEquals(0xFF, (Easing.lerpArgb(from, to, 0.5f) >>> 24));
    }

    @Test public void zoomLabelIsStableAndNeverShowsNegativeZero() {
        assertEquals("1×", Easing.zoomLabel(1f));
        assertEquals("2×", Easing.zoomLabel(1.98f));
        assertEquals("1.5×", Easing.zoomLabel(1.46f));
        assertEquals("1×", Easing.zoomLabel(0.2f));
        assertEquals("10×", Easing.zoomLabel(10f));
    }

    @Test public void evLabelUsesATrueMinus() {
        assertEquals("0.0 EV", Easing.evLabel(0f));
        assertEquals("+0.5 EV", Easing.evLabel(0.5f));
        assertEquals("−1.0 EV", Easing.evLabel(-1f));
    }
}
