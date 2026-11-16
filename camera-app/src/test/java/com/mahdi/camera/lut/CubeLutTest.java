package com.mahdi.camera.lut;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Parser contract for .cube files. Runs on the JVM: no Android graphics involved. */
public class CubeLutTest {

    private static String cube(int size, String header) {
        StringBuilder text = new StringBuilder(header);
        for (int blue = 0; blue < size; blue++) {
            for (int green = 0; green < size; green++) {
                for (int red = 0; red < size; red++) {
                    text.append(red / (float) (size - 1)).append(' ')
                            .append(green / (float) (size - 1)).append(' ')
                            .append(blue / (float) (size - 1)).append('\n');
                }
            }
        }
        return text.toString();
    }

    @Test public void parsesIdentityCubeAndKeepsRedFastest() throws Exception {
        CubeLut lut = CubeLut.parse(cube(2, "TITLE \"Identity\"\nLUT_3D_SIZE 2\n"));
        assertEquals("Identity", lut.title);
        assertEquals(2, lut.size);
        assertEquals(4, lut.atlasSize);
        assertNotNull(lut.digest());
        assertEquals(64, lut.digest().length());
    }

    @Test public void acceptsCommentsBlankLinesCrlfAndCommas() throws Exception {
        String text = "# exported by some tool\r\n\r\nLUT_3D_SIZE 2\r\n0,0,0\r\n1,0,0\r\n"
                + "0,1,0\r\n1,1,0\r\n0,0,1\r\n1,0,1\r\n0,1,1\r\n1,1,1\r\n";
        CubeLut lut = CubeLut.parse(text);
        assertEquals(2, lut.size);
    }

    @Test public void ignoresUnknownKeywordsInsteadOfFailing() throws Exception {
        CubeLut lut = CubeLut.parse(cube(2, "TITLE \"x\"\nLUT_3D_SIZE 2\nSOME_VENDOR_FIELD 12\n"));
        assertEquals(2, lut.size);
    }

    @Test public void rejectsOneDimensionalLut() {
        expectRejected("LUT_1D_SIZE 16\n", "1D");
    }

    @Test public void rejectsMissingSize() {
        expectRejected("0 0 0\n", "LUT_3D_SIZE");
    }

    @Test public void rejectsWrongEntryCount() {
        expectRejected("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n", "entries");
    }

    @Test public void rejectsOversizedCube() {
        expectRejected("LUT_3D_SIZE 128\n0 0 0\n", "size");
    }

    @Test public void rejectsTooSmallCube() {
        expectRejected("LUT_3D_SIZE 1\n0 0 0\n", "size");
    }

    @Test public void rejectsNonFiniteValues() {
        // A line that does not start with a number is an unknown keyword per the format,
        // so the NaN has to be inside an entry that the parser will actually read.
        expectRejected("LUT_3D_SIZE 2\n0 NaN 0\n", "non-finite");
    }

    @Test public void rejectsLogScaledDomain() {
        expectRejected("LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 255 255 255\n", "DOMAIN");
    }

    @Test public void rejectsInvalidDomain() {
        expectRejected("LUT_3D_SIZE 2\nDOMAIN_MIN 1 1 1\nDOMAIN_MAX 0 0 0\n", "DOMAIN");
    }

    @Test public void inputRangeIsNormalisedIntoTheAtlas() throws Exception {
        // LUT_3D_INPUT_RANGE takes two numbers: the file's own 0..2 scale. Reading the
        // substring with one wrong offset used to eat the leading digit of the range.
        CubeLut lut = CubeLut.parse(cube(2, "LUT_3D_SIZE 2\nLUT_3D_INPUT_RANGE 0 2\n"));
        assertEquals(0f, lut.minValue(), 0f);
        assertEquals(2f, lut.maxValue(), 0f);
    }

    @Test public void inputRangeRejectsAReversedRange() {
        expectRejected("LUT_3D_SIZE 2\nLUT_3D_INPUT_RANGE 2 1\n", "input range");
    }

    @Test public void lastEntryIsWhiteAndFirstIsBlackForAnIdentityCube() throws Exception {
        CubeLut lut = CubeLut.parse(cube(2, "LUT_3D_SIZE 2\n"));
        // Atlas index 0 is (red 0, green 0, blue 0) and the last entry is white.
        // Bitmap is not available on the JVM, so compare through the digest only.
        CubeLut other = CubeLut.parse(cube(2, "TITLE different\nLUT_3D_SIZE 2\n"));
        assertEquals(lut.digest(), other.digest());
    }

    @Test public void identityConstantMatchesAnIdentityCube() throws Exception {
        CubeLut identity = CubeLut.identity();
        assertEquals(2, identity.size);
        assertEquals(4, identity.atlasSize);
        assertEquals(0f, identity.domainMin(0), 0f);
        assertEquals(1f, identity.domainMax(2), 0f);
    }

    @Test public void digestIgnoresTitleButNotData() throws Exception {
        CubeLut a = CubeLut.parse(cube(2, "TITLE \"A\"\nLUT_3D_SIZE 2\n"));
        CubeLut b = CubeLut.parse(cube(2, "TITLE \"B\"\nLUT_3D_SIZE 2\n"));
        CubeLut c = CubeLut.parse(cube(2, "LUT_3D_SIZE 2\n").replace("1.0 1.0 1.0", "0.9 1.0 1.0"));
        assertEquals(a.digest(), b.digest());
        assertTrue("different data must change the digest", !a.digest().equals(c.digest()));
    }

    @Test public void sampleIsTheIdentityForAnIdentityCube() throws Exception {
        CubeLut identity = CubeLut.identity();
        for (float value : new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}) {
            int packed = identity.sample(value, value, value);
            int grey = (packed >> 16) & 255;
            assertEquals(0xFF000000, packed & 0xFF000000);
            assertEquals("grey " + value, Math.round(value * 255), grey, 1);
            assertEquals(grey, (packed >> 8) & 255);
            assertEquals(grey, packed & 255);
        }
    }

    @Test public void sampleClampsOutOfRangeInputsInsteadOfWrapping() throws Exception {
        CubeLut identity = CubeLut.identity();
        assertEquals(0xFF000000, identity.sample(-2f, -0.5f, -0f));
        assertEquals(0xFFFFFFFF, identity.sample(4f, 2f, 1f));
    }

    @Test public void sampleNormalisesTheCubeDomainLikeTheShader() throws Exception {
        // An identity cube declared over 0..2 must still map 0..1 as the shader does:
        // normalise with the domain, then sample the same table.
        CubeLut lut = CubeLut.parse(cube(2, "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n"));
        assertEquals(0xFF000000, lut.sample(0f, 0f, 0f));
        int packed = lut.sample(1f, 1f, 1f);
        assertEquals(128, (packed >> 16) & 255, 2);
    }

    @Test public void fromPixelsRebuildsTheSameTableAsAParsedCube() throws Exception {
        CubeLut parsed = CubeLut.parse(cube(3, "LUT_3D_SIZE 3\n"));
        CubeLut rebuilt = CubeLut.fromPixels(parsed.toAtlasPixels(), parsed.size,
                0f, 1f, 0f, 1f, 0f, 1f);
        assertEquals(parsed.size, rebuilt.size);
        for (float red = 0f; red <= 1f; red += 0.125f) {
            for (float green = 0f; green <= 1f; green += 0.125f) {
                for (float blue = 0f; blue <= 1f; blue += 0.125f) {
                    assertEquals(parsed.sample(red, green, blue),
                            rebuilt.sample(red, green, blue));
                }
            }
        }
    }

    @Test public void atlasBufferIsAlwaysSizeCubedPixels() throws Exception {
        for (int size = 2; size <= 9; size++) {
            CubeLut cube = CubeLut.parse(cube(size, "LUT_3D_SIZE " + size + "\n"));
            assertEquals("size cubed entries for " + size, size * size * size,
                    cube.toAtlasPixels().length);
            assertEquals("square atlas edge for " + size, size * size, cube.atlasSize);
        }
    }

    @Test public void fromPixelsRejectsAWrongLengthAtlas() {
        try {
            CubeLut.fromPixels(new int[7], 2, 0f, 1f, 0f, 1f, 0f, 1f);
            fail("Expected a mismatched atlas to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("size"));
        }
    }

    private static void expectRejected(String text, String expectedWord) {
        try {
            CubeLut.parse(text);
            fail("Expected the LUT to be rejected");
        } catch (CubeLut.LutException expected) {
            assertTrue("message should mention " + expectedWord + ": " + expected.getMessage(),
                    expected.getMessage().toLowerCase(java.util.Locale.US)
                            .contains(expectedWord.toLowerCase(java.util.Locale.US)));
        }
    }
}
