package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Bracket geometry: order, spacing and clamping are what make an HDR set usable. */
public class BracketPlanTest {
    @Test public void bracketStartsAtZeroAndSpreadsBothWays() {
        BracketPlan plan = BracketPlan.bracket(5, 4f, 120);
        assertEquals(5, plan.frames);
        assertEquals(0f, plan.compensations[0], 1e-5f);
        assertTrue(plan.compensations[1] > 0);
        assertTrue(plan.compensations[2] < 0);
        // 5 frames across 4 EV is one stop per step, visited centre-out.
        assertEquals(1f, plan.compensations[1], 1e-5f);
        assertEquals(-1f, plan.compensations[2], 1e-5f);
        assertEquals(2f, plan.compensations[3], 1e-5f);
        assertEquals(-2f, plan.compensations[4], 1e-5f);
    }

    @Test public void everyBracketValueFallsInsideTheRequestedSpread() {
        for (int frames : new int[]{3, 5, 7, 9}) {
            float spread = 3f;
            BracketPlan plan = BracketPlan.bracket(frames, spread, 100);
            assertEquals(frames, plan.compensations.length);
            for (float ev : plan.compensations) {
                assertTrue("ev " + ev, Math.abs(ev) <= spread / 2f + 1e-5f);
            }
            // Balanced: the sum of an odd bracket is zero, so the average exposure is unchanged.
            float sum = 0;
            for (float ev : plan.compensations) sum += ev;
            assertEquals(0f, sum, 1e-4f);
        }
    }

    @Test public void evenFrameCountsAndTinySpreadsAreRejected() {
        for (int frames : new int[]{2, 4, 11}) {
            try {
                BracketPlan.bracket(frames, 2f, 100);
                throw new AssertionError("expected rejection for " + frames);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("odd"));
            }
        }
        try {
            BracketPlan.bracket(3, 0f, 100);
            throw new AssertionError("expected rejection for a zero spread");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("spread"));
        }
    }

    @Test public void burstHasNoCompensationAndKeepsItsFrameCount() {
        BracketPlan plan = BracketPlan.burst(8, 50);
        assertEquals(8, plan.frames);
        for (float ev : plan.compensations) assertEquals(0f, ev, 0f);
        assertEquals(400L, plan.durationMs());
    }

    @Test public void burstSizesAreBounded() {
        for (int frames : new int[]{1, 31}) {
            try {
                BracketPlan.burst(frames, 50);
                throw new AssertionError("expected rejection for " + frames);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("burst"));
            }
        }
    }

    @Test public void stepConversionClampsToTheSensorRange() {
        int[] limits = BracketPlan.stepsFor(-4, 4);
        assertEquals(4, limits[0]);
        assertEquals(4, limits[1]);
        assertEquals(-4, BracketPlan.toStep(-9f, limits[0], limits[1]));
        assertEquals(4, BracketPlan.toStep(9f, limits[0], limits[1]));
        assertEquals(0, BracketPlan.toStep(0.1f, limits[0], limits[1]));
    }

    @Test public void planDescribesItselfForTheStatusLine() {
        String text = BracketPlan.bracket(3, 2f, 80).describe();
        assertTrue(text, text.contains("3 frames"));
        assertTrue(text, text.contains("±1.0"));
    }
}
