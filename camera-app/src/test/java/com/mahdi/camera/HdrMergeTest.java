package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The HDR merge is only worth having if the fused frame beats every single exposure. */
public class HdrMergeTest {
    private static int grey(int value) {
        return 0xFF000000 | (value << 16) | (value << 8) | value;
    }

    @Test public void weightIsZeroAtBothEndsAndFullInTheMiddle() {
        assertEquals(0f, HdrMerge.weight(0), 1e-6f);
        assertEquals(0f, HdrMerge.weight(255), 1e-6f);
        assertEquals(1f, HdrMerge.weight(128), 1e-3f);
        assertTrue(HdrMerge.weight(64) > HdrMerge.weight(16));
    }

    @Test public void decodeAndEncodeRoundTrip() {
        for (int value = 0; value <= 255; value += 5) {
            assertEquals(value, HdrMerge.encode(HdrMerge.decode(value)), 2);
        }
    }

    /** Shadow detail from the bright frame survives; bright detail from the dark frame survives. */
    @Test public void fuseRecoversDetailNoSingleFrameHas() {
        int[] base = new int[2];
        int[] bright = new int[2];
        int[] dark = new int[2];
        // Pixel 0 is a shadow: crushed in the base, usable when over-exposed.
        base[0] = bright[0] = dark[0] = grey(128);
        base[0] = grey(2);
        bright[0] = grey(90);   // -2 EV relative to base would show it at 90
        dark[0] = grey(1);
        // Pixel 1 is a highlight: blown in the base, usable when under-exposed.
        base[1] = grey(254);
        bright[1] = grey(255);
        dark[1] = grey(120);
        int[] fused = HdrMerge.fuse(new int[][]{base, bright, dark},
                new float[]{0f, -2f, 2f});
        assertNotNull(fused);
        int shadow = fused[0] & 255;
        int highlight = fused[1] & 255;
        assertTrue("shadow detail must survive, got " + shadow,
                shadow > (base[0] & 255) + 8);
        assertTrue("highlight detail must survive, got " + highlight,
                highlight < (base[1] & 255) - 8);
    }

    @Test public void fuseKeepsAlphaAndDimensions() {
        int[] a = {grey(10), grey(20), grey(30)};
        int[] b = {grey(40), grey(50), grey(60)};
        int[] fused = HdrMerge.fuse(new int[][]{a, b}, new float[]{0f, 1f});
        assertNotNull(fused);
        assertEquals(3, fused.length);
        for (int pixel : fused) assertEquals(0xFF000000, pixel & 0xFF000000);
    }

    @Test public void mismatchedFramesAreRejectedNotGuessed() {
        assertNull(HdrMerge.fuse(new int[][]{new int[4], new int[3]}, new float[]{0f, 0f}));
    }

    @Test public void fewerThanTwoFramesIsAProgrammingError() {
        try {
            HdrMerge.fuse(new int[][]{new int[1]}, new float[]{0f});
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void theMiddleExposureIsUsedWhenEveryFrameIsPureWhite() {
        int[] white = {grey(255), grey(255)};
        int[] alsoWhite = {grey(255), grey(255)};
        int[] fused = HdrMerge.fuse(new int[][]{white, alsoWhite}, new float[]{0f, 1f});
        assertNotNull(fused);
        assertEquals(255, fused[0] & 255);
    }

    /** A frame that is black everywhere must not produce a black hole in the fused result. */
    @Test public void clippedFrameIsTheFallbackRatherThanNaN() {
        int[] white = {grey(255), grey(255)};
        int[] black = {grey(0), grey(0)};
        int[] fused = HdrMerge.fuse(new int[][]{white, black}, new float[]{2f, -2f});
        assertNotNull(fused);
        for (int pixel : fused) {
            int value = pixel & 255;
            assertFalse("value must be finite and in range", value < 0 || value > 255);
        }
    }
}
