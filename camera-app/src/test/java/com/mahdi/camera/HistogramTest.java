package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistogramTest {
    private static int grey(int value) {
        return 0xFF000000 | (value << 16) | (value << 8) | value;
    }

    @Test public void lumaMatchesRec601Weights() {
        assertEquals(0, Histogram.lumaOf(0xFF000000));
        assertEquals(255, Histogram.lumaOf(0xFFFFFFFF));
        assertEquals(128, Histogram.lumaOf(grey(128)));
        // Pure red is 77/256 of full scale, not 85: this is Rec.601, not an average.
        assertEquals(76, Histogram.lumaOf(0xFFFF0000));
        assertEquals(149, Histogram.lumaOf(0xFF00FF00));
        assertEquals(28, Histogram.lumaOf(0xFF0000FF));
    }

    @Test public void countsAndMeanFollowThePixels() {
        int[] pixels = new int[100];
        for (int index = 0; index < 100; index++) pixels[index] = grey(index * 2);
        Histogram histogram = Histogram.of(pixels, 1);
        assertEquals(100, histogram.samples);
        assertEquals(99, histogram.mean, 0.6);
        assertEquals(1, histogram.luma[0]);   // the first pixel is black
        assertEquals(1, histogram.luma[2]);
        assertTrue(histogram.peak() >= 1);
    }

    @Test public void stepSkipsPixelsWithoutLosingTheRange() {
        int[] pixels = new int[10];
        for (int index = 0; index < 10; index++) pixels[index] = grey(200);
        Histogram histogram = Histogram.of(pixels, 4);
        assertEquals(3, histogram.samples); // 0, 4, 8
        assertEquals(3, histogram.luma[200]);
        Histogram zeroStep = Histogram.of(pixels, 0);
        assertEquals(10, zeroStep.samples); // never divide by zero
    }

    @Test public void clippingIsReportedAsAFraction() {
        int[] pixels = {grey(255), grey(255), grey(128), grey(0)};
        Histogram histogram = Histogram.of(pixels, 1);
        assertEquals(0.5, histogram.clippedHigh, 1e-9);
        assertEquals(0.25, histogram.clippedLow, 1e-9);
        assertTrue(histogram.advice().contains("Highlights clipped"));
    }

    @Test public void crushedShadowsAndDullScenesGetTheirOwnAdvice() {
        int[] dark = new int[10];
        for (int index = 0; index < 10; index++) dark[index] = grey(1);
        assertTrue(Histogram.of(dark, 1).advice().contains("Shadows crushed"));
        int[] mid = new int[10];
        for (int index = 0; index < 10; index++) mid[index] = grey(128);
        assertTrue(Histogram.of(mid, 1).advice().contains("Balanced"));
        assertTrue(Histogram.of(new int[0], 1).advice().contains("No frame"));
    }

    @Test public void zonesPartitionTheFrame() {
        int[] pixels = new int[10];
        for (int index = 0; index < 10; index++) pixels[index] = grey(index * 25);
        double[] zones = Histogram.of(pixels, 1).zones();
        double total = 0;
        for (double zone : zones) {
            total += zone;
            assertTrue(zone <= 1.0);
        }
        assertEquals(1.0, total, 1e-9);
    }
}
