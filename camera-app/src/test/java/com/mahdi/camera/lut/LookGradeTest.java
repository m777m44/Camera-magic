package com.mahdi.camera.lut;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The CPU still path. A saved photo must carry the same look the preview shows, so this
 * class pins the arithmetic that turns a cube plus a strength into graded pixels.
 */
public class LookGradeTest {

    /** A cube that maps everything to a fixed colour, whatever goes in. */
    private static CubeLut constant(int red, int green, int blue) {
        int size = 2;
        int[] atlas = new int[size * size * size];
        int packed = 0xFF000000 | (red << 16) | (green << 8) | blue;
        for (int index = 0; index < atlas.length; index++) atlas[index] = packed;
        return CubeLut.fromPixels(atlas, size, 0f, 1f, 0f, 1f, 0f, 1f);
    }

    @Test public void aFullStrengthLookReplacesTheColourEntirely() {
        LookGrade grade = new LookGrade(constant(10, 200, 30), 1f);
        int sampled = grade.cube().sample(0.5f, 0.5f, 0.5f);
        assertEquals(10, (sampled >> 16) & 255);
        assertEquals(200, (sampled >> 8) & 255);
        assertEquals(30, sampled & 255);
        assertEquals("strength is clamped to 1", 1f, grade.strength(), 0f);
    }

    @Test public void zeroStrengthIsAPassthrough() {
        LookGrade grade = new LookGrade(constant(10, 200, 30), 0f);
        assertEquals(0f, grade.strength(), 0f);
    }

    @Test public void strengthIsClampedIntoTheUsableRange() {
        assertEquals(1f, new LookGrade(constant(0, 0, 0), 4f).strength(), 0f);
        assertEquals(0f, new LookGrade(constant(0, 0, 0), -4f).strength(), 0f);
        assertEquals(0.5f, new LookGrade(constant(0, 0, 0), 0.5f).strength(), 0f);
    }

    @Test public void aCubeIsRequired() {
        try {
            new LookGrade(null, 1f);
            throw new AssertionError("grading without a cube must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cube"));
        }
    }

    @Test public void anIdentityCubeGradedAtFullStrengthLeavesPixelsUnchanged() throws Exception {
        CubeLut identity = CubeLut.identity();
        LookGrade grade = new LookGrade(identity, 1f);
        for (int grey = 0; grey <= 255; grey += 51) {
            int packed = grade.cube().sample(grey / 255f, grey / 255f, grey / 255f);
            assertEquals("grey " + grey, grey, (packed >> 16) & 255, 1);
            assertEquals("grey " + grey, grey, (packed >> 8) & 255, 1);
            assertEquals("grey " + grey, grey, packed & 255, 1);
        }
    }

    @Test public void theGradedCubeIsTheOneThatWasSupplied() {
        CubeLut cube = constant(1, 2, 3);
        assertTrue(new LookGrade(cube, 1f).cube() == cube);
    }
}
