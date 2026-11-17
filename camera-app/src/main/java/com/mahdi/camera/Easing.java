package com.mahdi.camera;

/**
 * Deterministic easing and interpolation helpers for the UI layer.
 *
 * <p>Pure arithmetic with no Android import, because "smooth" is a measurable claim: a curve
 * that overshoots, a fade that never reaches 1.0 or a label that jitters between two values is
 * a bug, and the cheapest place to catch it is a JVM unit test.
 *
 * <p>All inputs are normalised to 0..1 and every function is total: out-of-range input is
 * clamped, never thrown on, so an animation thread that arrives one frame late cannot crash.
 */
public final class Easing {
    private Easing() {
    }

    public static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f; // an animation that lost a frame must not poison a view
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** Smooth acceleration and deceleration; the default for panel and overlay motion. */
    public static float smooth(float t) {
        float x = clamp01(t);
        return x * x * (3f - 2f * x);
    }

    /** Stronger, more "designed" curve for a view entering: fast out, gentle settle. */
    public static float decelerate(float t) {
        float x = clamp01(t);
        float inverse = 1f - x;
        return 1f - inverse * inverse * inverse;
    }

    /** Curve for a view leaving, so it does not vanish abruptly. */
    public static float accelerate(float t) {
        float x = clamp01(t);
        return x * x * x;
    }

    /** Gentle overshoot for a tap acknowledgement; peaks at about 1.1 then settles at 1.0. */
    public static float overshoot(float t) {
        float x = clamp01(t) - 1f;
        return 1f + 2.70158f * x * x * x + 1.70158f * x * x;
    }

    /** A breathing 0..1 pulse for a recording dot, period driven by {@code millis}. */
    public static float pulse(long millis, long periodMillis) {
        long period = Math.max(1L, periodMillis);
        double phase = (millis % period) / (double) period;
        return (float) (0.5 - 0.5 * Math.cos(phase * 2 * Math.PI));
    }

    /** Linear interpolation between two values. */
    public static float lerp(float from, float to, float t) {
        float x = clamp01(t);
        return from + (to - from) * x;
    }

    public static int lerpArgb(int from, int to, float t) {
        float x = clamp01(t);
        int a = Math.round(Easing.lerp((from >>> 24) & 255, (to >>> 24) & 255, x));
        int r = Math.round(Easing.lerp((from >> 16) & 255, (to >> 16) & 255, x));
        int g = Math.round(Easing.lerp((from >> 8) & 255, (to >> 8) & 255, x));
        int b = Math.round(Easing.lerp(from & 255, to & 255, x));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * A stable readout for a value that is being animated: one decimal, no flicker between
     * {@code 1.0×} and {@code 1×}, and never {@code -0.0}.
     */
    public static String zoomLabel(float zoom) {
        float value = Math.max(1f, zoom);
        double rounded = Math.round(value * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 0.05) {
            return String.format(java.util.Locale.US, "%.0f×", rounded);
        }
        return String.format(java.util.Locale.US, "%.1f×", rounded);
    }

    /** Exposure-compensation label with a true minus sign and one decimal. */
    public static String evLabel(float ev) {
        double rounded = Math.round(ev * 10.0) / 10.0;
        if (rounded == 0) return "0.0 EV";
        return String.format(java.util.Locale.US, "%s%.1f EV", rounded > 0 ? "+" : "−",
                Math.abs(rounded));
    }
}
