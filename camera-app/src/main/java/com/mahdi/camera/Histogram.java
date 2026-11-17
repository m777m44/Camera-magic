package com.mahdi.camera;

/**
 * Luminance histogram, clipping and exposure advice from a small ARGB sample grid.
 *
 * <p>Pure Java on purpose: the numbers a photographer acts on (how much of the frame is
 * blown out, whether the scene is under-exposed) must be unit-tested on the JVM rather than
 * eyeballed on a phone. A histogram drawn from Y-only luma also keeps the maths honest about
 * what the RGB channels are doing.
 */
public final class Histogram {
    /** Bins in the luma histogram: 8-bit luma, so one bin per level. */
    public static final int BINS = 256;
    /** A pixel is "clipped" at or above this luma. */
    public static final int CLIP_HIGH = 250;
    /** A pixel is "crushed" at or below this luma. */
    public static final int CLIP_LOW = 5;

    public final int[] luma = new int[BINS];
    public final int[] red = new int[BINS];
    public final int[] green = new int[BINS];
    public final int[] blue = new int[BINS];
    public final int samples;
    public final double mean;
    /** Fraction of samples at or above {@link #CLIP_HIGH}, 0..1. */
    public final double clippedHigh;
    /** Fraction of samples at or below {@link #CLIP_LOW}, 0..1. */
    public final double clippedLow;

    private Histogram(int[] luma, int[] red, int[] green, int[] blue, int samples) {
        System.arraycopy(luma, 0, this.luma, 0, BINS);
        System.arraycopy(red, 0, this.red, 0, BINS);
        System.arraycopy(green, 0, this.green, 0, BINS);
        System.arraycopy(blue, 0, this.blue, 0, BINS);
        this.samples = samples;
        long total = 0;
        for (int value = 0; value < BINS; value++) total += (long) value * this.luma[value];
        this.mean = samples == 0 ? 0 : (double) total / samples;
        int high = 0;
        int low = 0;
        for (int value = CLIP_HIGH; value < BINS; value++) high += this.luma[value];
        for (int value = 0; value <= CLIP_LOW; value++) low += this.luma[value];
        this.clippedHigh = samples == 0 ? 0 : (double) high / samples;
        this.clippedLow = samples == 0 ? 0 : (double) low / samples;
    }

    /** Rec. 601 luma, which matches what a display shows for an sRGB frame. */
    public static int lumaOf(int argb) {
        int red = (argb >> 16) & 255;
        int green = (argb >> 8) & 255;
        int blue = argb & 255;
        return (red * 77 + green * 150 + blue * 29) >> 8;
    }

    /** Build a histogram from packed ARGB pixels, stepping through the array. */
    public static Histogram of(int[] argb, int step) {
        if (argb == null) throw new IllegalArgumentException("Pixels are required");
        int stride = Math.max(1, step);
        int[] luma = new int[BINS];
        int[] red = new int[BINS];
        int[] green = new int[BINS];
        int[] blue = new int[BINS];
        int samples = 0;
        for (int index = 0; index < argb.length; index += stride) {
            int pixel = argb[index];
            int r = (pixel >> 16) & 255;
            int g = (pixel >> 8) & 255;
            int b = pixel & 255;
            luma[lumaOf(pixel)]++;
            red[r]++;
            green[g]++;
            blue[b]++;
            samples++;
        }
        return new Histogram(luma, red, green, blue, samples);
    }

    /** Highest bin count, for scaling bars. Never zero. */
    public int peak() {
        int peak = 1;
        for (int value : luma) peak = Math.max(peak, value);
        return peak;
    }

    /**
     * A short, actionable exposure line: what is wrong and by how much.
     *
     * <p>These thresholds are deliberately conservative — a bright sky in a quarter of the
     * frame is normal, a majority of blown highlights or crushed shadows is not.
     */
    public String advice() {
        if (samples == 0) return "No frame to measure";
        if (clippedHigh > 0.25) {
            return String.format(java.util.Locale.US,
                    "Highlights clipped %.0f%% · lower EV", clippedHigh * 100);
        }
        if (clippedLow > 0.35) {
            return String.format(java.util.Locale.US,
                    "Shadows crushed %.0f%% · raise EV", clippedLow * 100);
        }
        if (mean < 70) return "Under-exposed · raise EV";
        if (mean > 190) return "Over-exposed · lower EV";
        if (clippedHigh > 0.08) {
            return String.format(java.util.Locale.US,
                    "Bright sky risk %.0f%% · watch highlights", clippedHigh * 100);
        }
        return String.format(java.util.Locale.US, "Balanced · mean %.0f%%", mean * 100 / 255);
    }

    /** Fraction of the frame per zone, for a zone-system style readout. */
    public double[] zones() {
        double[] zones = new double[5];
        for (int value = 0; value < BINS; value++) {
            int zone = Math.min(4, value * 5 / BINS);
            zones[zone] += samples == 0 ? 0 : (double) luma[value] / samples;
        }
        return zones;
    }
}
