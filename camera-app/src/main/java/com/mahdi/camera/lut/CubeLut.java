package com.mahdi.camera.lut;

import android.graphics.Bitmap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * A parsed 3D colour cube: red changes fastest, then green, then blue.
 *
 * <p>This is an original implementation of the public .cube text format used by most
 * colour-grading tools; it does not copy any third-party code. The values are stored
 * as a packed 8-bit atlas that the GLES shader samples trilinearly, so a 33³ cube keeps
 * the same memory footprint as the bundled 16³ presets. Only the display-referred SDR
 * path is supported: LOG domain cubes are rejected rather than silently misapplied.
 */
public final class CubeLut {
    /** The shader texture is square, so the atlas size must fit comfortably in GL memory. */
    public static final int MAX_SIZE = 64;
    public static final int MIN_SIZE = 2;
    /** 3D textures larger than this are unusual and usually a sign of a wrong file. */
    public static final long MAX_ENTRIES = 64L * 64L * 64L;

    public final String title;
    public final int size;
    public final int atlasSize;
    private final int[] argb;
    private final float[] domainMin;
    private final float[] domainMax;
    private final float minValue;
    private final float maxValue;

    private CubeLut(String title, int size, int[] argb,
                    float[] domainMin, float[] domainMax, float minValue, float maxValue) {
        this.title = title;
        this.size = size;
        this.atlasSize = size * size;
        this.argb = argb;
        this.domainMin = domainMin;
        this.domainMax = domainMax;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /** Short human-readable name for the preset row. */
    public String label() {
        if (title != null && !title.isEmpty()) return title;
        return "Custom LUT";
    }

    /**
     * Identity cube used when no LUT is selected. Kept tiny so selecting "None" costs
     * no texture upload at all in the renderer.
     */
    public static CubeLut identity() {
        int size = 2;
        int[] argb = new int[size * size * size * size];
        int index = 0;
        for (int blue = 0; blue < size; blue++) {
            for (int green = 0; green < size; green++) {
                for (int red = 0; red < size; red++) {
                    argb[index++] = pack(red * 255 / (size - 1), green * 255 / (size - 1),
                            blue * 255 / (size - 1));
                }
            }
        }
        return new CubeLut("None (no colour transform)", size, argb,
                new float[]{0f, 0f, 0f}, new float[]{1f, 1f, 1f}, 0f, 1f);
    }

    /**
     * Atlas layout: the 3D cube is written as {@code size} tiles of {@code size × size},
     * laid out {@code size} per row, left to right and top to bottom, one slice per blue
     * value. This matches the shader in {@code assets/mahdi_lut/shaders/lut.frag}.
     */
    public Bitmap toAtlas() {
        if (atlasSize * atlasSize != argb.length) {
            throw new IllegalStateException("Corrupt atlas buffer");
        }
        return Bitmap.createBitmap(argb, atlasSize, atlasSize, Bitmap.Config.ARGB_8888);
    }

    public float domainMin(int channel) { return domainMin[channel]; }
    public float domainMax(int channel) { return domainMax[channel]; }
    public float minValue() { return minValue; }
    public float maxValue() { return maxValue; }

    /** Stable identifier used for caches and for remembering the last selected LUT. */
    public String digest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((size + ":" + minValue + ":" + maxValue).getBytes("US-ASCII"));
            for (float value : domainMin) sha.update(Float.toString(value).getBytes("US-ASCII"));
            for (float value : domainMax) sha.update(Float.toString(value).getBytes("US-ASCII"));
            for (int value : argb) {
                sha.update((byte) (value >>> 16));
                sha.update((byte) (value >>> 8));
                sha.update((byte) value);
            }
            StringBuilder text = new StringBuilder();
            for (byte value : sha.digest()) text.append(String.format(Locale.US, "%02x", value));
            return text.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException("SHA-256 is mandatory on Android", impossible);
        }
    }

    /**
     * Parse a .cube document.
     *
     * @throws LutException with a message that is safe to show to a user.
     */
    public static CubeLut parse(String text) throws LutException {
        if (text == null) throw new LutException("Empty LUT file");
        String[] rawLines = text.split("\r\n|\r|\n", -1);
        String title = null;
        int size = -1;
        float[] domainMin = {0f, 0f, 0f};
        float[] domainMax = {1f, 1f, 1f};
        float minValue = 0f;
        float maxValue = 1f;
        float[] red = null, green = null, blue = null;
        int count = 0;
        boolean sawData = false;
        for (int lineNumber = 0; lineNumber < rawLines.length; lineNumber++) {
            String line = rawLines[lineNumber];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("TITLE")) {
                title = unquote(line.substring(5).trim());
                continue;
            }
            if (upper.startsWith("LUT_3D_SIZE")) {
                if (size != -1) throw new LutException("Duplicate LUT_3D_SIZE");
                size = parseInt(line.substring(11).trim(), "LUT_3D_SIZE");
                if (size < MIN_SIZE || size > MAX_SIZE) {
                    throw new LutException("Unsupported LUT size " + size + " (2–" + MAX_SIZE + ")");
                }
                long entries = (long) size * size * size;
                if (entries > MAX_ENTRIES) throw new LutException("LUT is too large");
                red = new float[(int) entries];
                green = new float[(int) entries];
                blue = new float[(int) entries];
                continue;
            }
            if (upper.startsWith("LUT_1D_SIZE")) {
                throw new LutException("1D LUTs are not supported; export a 3D .cube");
            }
            if (upper.startsWith("LUT_3D_INPUT_RANGE")) {
                // "LUT_3D_INPUT_RANGE" is 18 characters and takes TWO numbers: min max.
                float[] range = parsePair(line.substring("LUT_3D_INPUT_RANGE".length()).trim(),
                        "LUT_3D_INPUT_RANGE");
                minValue = range[0];
                maxValue = range[1];
                if (!(maxValue > minValue)) throw new LutException("Invalid input range");
                continue;
            }
            if (upper.startsWith("DOMAIN_MIN")) {
                domainMin = parseTriple(line.substring(10).trim(), "DOMAIN_MIN");
                continue;
            }
            if (upper.startsWith("DOMAIN_MAX")) {
                domainMax = parseTriple(line.substring(10).trim(), "DOMAIN_MAX");
                validateDomain(domainMin, domainMax);
                continue;
            }
            if (startsNumeric(line)) {
                if (size == -1) throw new LutException("Colour data before LUT_3D_SIZE");
                if (count >= red.length) throw new LutException("Too many colour entries");
                float[] rgb = parseTriple(line, "colour entry");
                for (float channel : rgb) {
                    if (Float.isNaN(channel) || Float.isInfinite(channel)) {
                        throw new LutException("Colour entry " + (count + 1)
                                + " is not a finite number");
                    }
                }
                red[count] = rgb[0];
                green[count] = rgb[1];
                blue[count] = rgb[2];
                count++;
                sawData = true;
                continue;
            }
            // Unknown keywords are allowed by the format; ignore them instead of failing.
        }
        if (size == -1) throw new LutException("Missing LUT_3D_SIZE");
        if (!sawData || count != red.length) {
            throw new LutException("Expected " + red.length + " entries, found " + count);
        }
        validateDomain(domainMin, domainMax);
        int[] argb = new int[red.length];
        for (int index = 0; index < argb.length; index++) {
            // Normalise into the atlas: the shader samples the cube at 0..1.
            float r = normalise(red[index], minValue, maxValue);
            float g = normalise(green[index], minValue, maxValue);
            float b = normalise(blue[index], minValue, maxValue);
            argb[index] = pack(to8(r), to8(g), to8(b));
        }
        return new CubeLut(title, size, argb, domainMin, domainMax, minValue, maxValue);
    }

    /** DOMAIN_MIN/MAX bracket the values in the file; an inverted or huge range is a mistake. */
    private static void validateDomain(float[] min, float[] max) throws LutException {
        for (int channel = 0; channel < 3; channel++) {
            if (!(max[channel] > min[channel])) {
                throw new LutException("Invalid DOMAIN_MIN/DOMAIN_MAX: max must exceed min");
            }
            if (Math.abs(min[channel]) > 4f || Math.abs(max[channel]) > 4f) {
                // Outside -4..4 is almost always a log-domain or 0..255 scale mix-up.
                throw new LutException("Unsupported DOMAIN range; export a display-referred LUT");
            }
        }
    }

    private static float[] parsePair(String text, String what) throws LutException {
        String[] parts = text.split("[\\s,]+");
        if (parts.length != 2) throw new LutException(what + ": expected two numbers");
        float[] values = new float[2];
        for (int i = 0; i < 2; i++) {
            try {
                values[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException error) {
                throw new LutException(what + ": invalid number");
            }
            if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) {
                throw new LutException(what + ": non-finite number");
            }
        }
        return values;
    }

    private static float normalise(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        float t = (value - min) / (max - min);
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    private static int to8(float value) {
        int scaled = Math.round(value * 255f);
        return scaled < 0 ? 0 : (scaled > 255 ? 255 : scaled);
    }

    private static int pack(int red, int green, int blue) {
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static boolean startsNumeric(String line) {
        char first = line.charAt(0);
        return first == '-' || first == '+' || first == '.' || (first >= '0' && first <= '9');
    }

    private static float[] parseTriple(String text, String what) throws LutException {
        String[] parts = text.split("[\\s,]+");
        if (parts.length != 3) throw new LutException(what + ": expected three numbers");
        float[] values = new float[3];
        for (int i = 0; i < 3; i++) {
            try {
                values[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException error) {
                throw new LutException(what + ": invalid number");
            }
            if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) {
                throw new LutException(what + ": non-finite number");
            }
        }
        return values;
    }

    private static int parseInt(String text, String what) throws LutException {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException error) {
            throw new LutException(what + ": invalid integer");
        }
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1).replace("\\\"", "\"");
        }
        return text;
    }

    /** A rejected .cube file. The message is user-facing and never contains file paths. */
    public static final class LutException extends Exception {
        private static final long serialVersionUID = 1L;

        public LutException(String message) {
            super(message);
        }
    }
}
