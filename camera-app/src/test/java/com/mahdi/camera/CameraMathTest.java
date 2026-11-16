package com.mahdi.camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    /** Every corner of the texture must land inside 0..1 or the preview shows a gap. */
    @Test public void inputTransformKeepsTheWholeQuadOnScreen() {
        int[][] sensors = {{90, 0}, {90, 90}, {90, 180}, {90, 270}, {270, 0}, {270, 90}, {0, 0}};
        for (int[] pair : sensors) {
            for (boolean front : new boolean[]{false, true}) {
                float[] matrix = CameraMath.inputTransform(pair[0], pair[1], front);
                for (float[] corner : new float[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                    float[] mapped = CameraMath.apply(matrix, corner[0], corner[1]);
                    String where = "sensor " + pair[0] + " display " + pair[1] + " front " + front;
                    assertTrue(where + " u=" + mapped[0], mapped[0] >= -1e-5f && mapped[0] <= 1f + 1e-5f);
                    assertTrue(where + " v=" + mapped[1], mapped[1] >= -1e-5f && mapped[1] <= 1f + 1e-5f);
                }
            }
        }
    }

    @Test public void uprightDeviceNeedsNoRotatedTexture() {
        // Sensor 90 with the display already turned 90 is the ordinary portrait case.
        assertArrayEquals(CameraMath.identity(), CameraMath.inputTransform(90, 90, false), 1e-6f);
    }

    @Test public void quarterTurnKeepsTheOriginAtTheOrigin() {
        float[] matrix = CameraMath.inputTransform(90, 0, false);
        float[] origin = CameraMath.apply(matrix, 0f, 0f);
        assertEquals(0f, origin[0], 1e-5f);
        assertEquals(0f, origin[1], 1e-5f);
    }

    @Test public void frontCamerasAreMirrored() {
        // The top-left of a front buffer must appear at the top-right of the screen.
        float[] matrix = CameraMath.inputTransform(270, 90, true);
        float[] mapped = CameraMath.apply(matrix, 0f, 0f);
        assertEquals(1f, mapped[0], 1e-5f);
    }

    @Test public void rendererBackedRecordingMustNotDoubleRotateInTheContainer() {
        assertEquals(0, CameraMath.containerRotation(true, 90, 0, false));
        assertEquals(90, CameraMath.containerRotation(false, 90, 0, false));
        assertEquals(270, CameraMath.containerRotation(false, 270, 0, true));
    }

    @Test public void viewportLetterboxesInsteadOfStretching() {
        int[] wide = CameraMath.fitViewport(1920, 1080, 1080, 1920);
        assertEquals(1080, wide[2]);
        assertEquals(608, wide[3]);
        assertEquals(656, wide[1]);
        int[] exact = CameraMath.fitViewport(1600, 900, 800, 450);
        assertEquals(0, exact[0]);
        assertEquals(0, exact[1]);
        assertEquals(800, exact[2]);
        assertEquals(450, exact[3]);
        int[] broken = CameraMath.fitViewport(0, 0, 100, 100);
        assertEquals(1, broken[2]);
        assertEquals(1, broken[3]);
    }

    @Test public void photoSizeGuardRejectsAbsurdBuffers() {
        assertTrue(CameraMath.validPhotoSize(4000, 3000));
        assertFalse(CameraMath.validPhotoSize(0, 3000));
        assertFalse(CameraMath.validPhotoSize(9000, 3000));
    }
}
