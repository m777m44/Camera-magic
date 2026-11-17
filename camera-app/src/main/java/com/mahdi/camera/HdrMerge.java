package com.mahdi.camera;

/**
 * Multi-frame exposure fusion, the merge half of HDR capture.
 *
 * <p>This is <b>not</b> a sensor HDR claim. It takes already-captured frames at different
 * exposures and fuses them into one display-referred image that keeps highlight and shadow
 * detail no single frame had. The output is 8-bit sRGB, exactly what a phone screen shows.
 *
 * <p>The method is a weight-based radiance merge: each frame is linearised, scaled down by its
 * exposure ratio, and combined with a weight that favours mid-tones (a pixel near black is
 * mostly noise, a pixel near white carries no highlight information). Pixels saturating in
 * every frame fall back to the brightest frame, so the result never invents detail.
 *
 * <p>Pure Java and integer-free of Android types: the maths that decides whether an HDR photo
 * is better than a normal one must be testable on the JVM.
 */
public final class HdrMerge {
    /** Linear response curve: a common gamma-2.2 approximation in 256 steps, 0..255 → 0..1. */
    private static final float[] LINEAR = new float[256];
    /** Inverse of {@link #LINEAR}, for the final encode. */
    private static final int[] ENCODE = new int[4097];

    static {
        for (int value = 0; value < 256; value++) {
            LINEAR[value] = (float) Math.pow(value / 255.0, 2.2);
        }
        for (int value = 0; value <= 4096; value++) {
            double linear = value / 4096.0;
            ENCODE[value] = (int) Math.round(Math.pow(linear, 1 / 2.2) * 255);
        }
    }

    private HdrMerge() {
    }

    /**
     * Radiance weight for one 8-bit channel sample.
     *
     * <p>Zero at both ends, one in the middle: a fully white pixel is clipped and carries no
     * information, a fully black pixel is dominated by read noise.
     */
    public static float weight(int value) {
        float normalised = value / 255f;
        return 1f - (float) Math.pow(Math.abs(normalised * 2f - 1f), 4);
    }

    /**
     * Fuse frames with the same dimensions.
     *
     * @param frames      ARGB pixel arrays of identical length, in any order
     * @param evPerFrame  exposure of each frame relative to the base: +2 is two stops
     *                    brighter (longer exposure), -2 two stops darker
     * @return a new ARGB array, or {@code null} when the frames do not line up
     */
    public static int[] fuse(int[][] frames, float[] evPerFrame) {
        if (frames == null || evPerFrame == null || frames.length != evPerFrame.length
                || frames.length < 2) {
            throw new IllegalArgumentException("At least two frames and matching EVs are needed");
        }
        int length = frames[0].length;
        for (int[] frame : frames) {
            if (frame == null || frame.length != length) return null;
        }
        int[] result = new int[length];
        for (int index = 0; index < length; index++) {
            float totalRed = 0f;
            float totalGreen = 0f;
            float totalBlue = 0f;
            float totalWeight = 0f;
            float fallbackRed = 0f;
            float fallbackGreen = 0f;
            float fallbackBlue = 0f;
            float fallbackWeight = -1f;
            for (int frame = 0; frame < frames.length; frame++) {
                int pixel = frames[frame][index];
                int red = (pixel >> 16) & 255;
                int green = (pixel >> 8) & 255;
                int blue = pixel & 255;
                // A frame exposed 2 stops brighter holds 4x the radiance: scale it back down.
                float ratio = (float) Math.pow(2.0, -evPerFrame[frame]);
                float weight = weight(red) * weight(green) * weight(blue);
                // Remember the most trustworthy frame in case this pixel has no usable weight
                // anywhere (for example a pure white highlight in every exposure).
                if (weight > fallbackWeight && weight > 0f) {
                    fallbackWeight = weight;
                    fallbackRed = LINEAR[red] / ratio;
                    fallbackGreen = LINEAR[green] / ratio;
                    fallbackBlue = LINEAR[blue] / ratio;
                }
                if (weight <= 0f) continue;
                totalRed += (LINEAR[red] / ratio) * weight;
                totalGreen += (LINEAR[green] / ratio) * weight;
                totalBlue += (LINEAR[blue] / ratio) * weight;
                totalWeight += weight;
            }
            float red;
            float green;
            float blue;
            if (totalWeight <= 0f && fallbackWeight <= 0f) {
                // Every frame clipped this pixel in every channel; keep the middle exposure.
                int pixel = frames[frames.length / 2][index];
                result[index] = 0xFF000000 | (pixel & 0x00FFFFFF);
                continue;
            }
            if (totalWeight <= 0f) {
                red = fallbackRed;
                green = fallbackGreen;
                blue = fallbackBlue;
            } else {
                red = totalRed / totalWeight;
                green = totalGreen / totalWeight;
                blue = totalBlue / totalWeight;
            }
            result[index] = 0xFF000000
                    | (encode(red) << 16) | (encode(green) << 8) | encode(blue);
        }
        return result;
    }

    /** Encode one linear 0..1 channel back to 8-bit display values. */
    public static int encode(float linear) {
        if (Float.isNaN(linear) || linear <= 0f) return 0;
        if (linear >= 1f) return 255;
        return ENCODE[Math.round(linear * 4096)];
    }

    /** Decode one 8-bit display channel to linear 0..1, for tests and tonal analysis. */
    public static float decode(int value) {
        return LINEAR[CameraMath.clamp(value, 0, 255)];
    }
}
