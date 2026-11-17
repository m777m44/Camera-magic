package com.mahdi.camera;

/**
 * Exposure-bracket and burst sequencing, as data rather than as code buried in the camera.
 *
 * <p>Bracketing is the first half of HDR capture: several frames at different exposures so
 * they can be merged later. The part that must be exact — how many frames, which compensation
 * each one gets, in what order, and how long the whole sequence may take — is pure arithmetic,
 * so it is unit-tested here and the camera thread only executes the plan.
 *
 * <p>Order matters: the frame nearest zero compensation is captured <b>first</b> (so a burst
 * that the user aborts still has a normally-exposed frame) and the most extreme exposure comes
 * last (largest motion/seam risk at the end, when everything else is already safe).
 */
public final class BracketPlan {
    public final float[] compensations;
    public final int frames;
    public final int intervalMs;
    public final String label;

    private BracketPlan(float[] compensations, int intervalMs, String label) {
        this.compensations = compensations;
        this.frames = compensations.length;
        this.intervalMs = intervalMs;
        this.label = label;
    }

    /**
     * The standard picture-style brackets, ordered so the safest frame is first.
     *
     * @param frames how many exposures (3, 5 or 7)
     * @param evSpread total spread across the bracket in EV (e.g. 2 means ±1)
     */
    public static BracketPlan bracket(int frames, float evSpread, int intervalMs) {
        if (frames < 3 || frames > 9 || frames % 2 == 0) {
            throw new IllegalArgumentException("A bracket needs an odd frame count of 3..9");
        }
        if (!(evSpread > 0f) || evSpread > 6f) {
            throw new IllegalArgumentException("The EV spread must be within 0..6");
        }
        float half = evSpread / 2f;
        float step = evSpread / (frames - 1);
        // Centre, then outward alternating: 0, +step, -step, +2step, -2step, …
        float[] ordered = new float[frames];
        ordered[0] = 0f;
        for (int index = 1; index < frames; index++) {
            float magnitude = ((index + 1) / 2) * step;
            ordered[index] = index % 2 == 1 ? (index == frames - 1 ? half : magnitude) : -magnitude;
        }
        return new BracketPlan(ordered, Math.max(0, intervalMs),
                String.format(java.util.Locale.US, "%d frames · ±%.1f EV", frames, half));
    }

    /** A burst of identical exposures, for multi-frame noise reduction. */
    public static BracketPlan burst(int frames, int intervalMs) {
        if (frames < 2 || frames > 30) {
            throw new IllegalArgumentException("A burst needs 2..30 frames");
        }
        float[] result = new float[frames];
        return new BracketPlan(result, Math.max(0, intervalMs),
                String.format(java.util.Locale.US, "%d frames · burst", frames));
    }

    /**
     * Convert EV compensation to the integer steps Camera2 wants.
     *
     * <p>The sensor reports its own step count, so a small range on a cheap HAL must not
     * silently round a requested −2 EV to 0 and produce seven identical photos.
     */
    public static int[] stepsFor(int compensationLowerSteps, int compensationUpperSteps) {
        int down = Math.max(0, -compensationLowerSteps);
        int up = Math.max(0, compensationUpperSteps);
        return new int[]{down, up};
    }

    /** One exposure-compensation step from a float EV value. */
    public static int toStep(float ev, int maxDown, int maxUp) {
        int step = Math.round(ev * 100f / 50f);
        return CameraMath.clamp(step, -maxDown, maxUp);
    }

    /** Total wall-clock budget of the sequence, including the settle time between frames. */
    public long durationMs() {
        return (long) frames * intervalMs;
    }

    public String describe() {
        StringBuilder text = new StringBuilder(label);
        for (float ev : compensations) {
            text.append(String.format(java.util.Locale.US, " %+.1f", ev));
        }
        return text.toString();
    }
}
