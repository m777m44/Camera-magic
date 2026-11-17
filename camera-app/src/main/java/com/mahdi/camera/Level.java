package com.mahdi.camera;

/**
 * Pitch/roll level from the device's gravity vector.
 *
 * <p>Pure Java so the tilt thresholds and the smoothing can be tested without a sensor, and
 * so the hysteresis that decides "level" versus "almost level" is a spec rather than a
 * constant buried in a listener.
 *
 * <p>Convention: the phone is held upright in portrait, screen facing the user. Roll is the
 * rotation of the horizon (how sideways the phone is), pitch is nose-up/nose-down.
 */
public final class Level {
    /** Within this many degrees of horizontal counts as level. */
    public static final float LEVEL_TOLERANCE = 0.8f;
    /** Within this many degrees makes the indicator glow and the haptics tick. */
    public static final float NEAR_TOLERANCE = 2.5f;

    private float rollDegrees;
    private float pitchDegrees;
    private boolean level;
    private int lockedTicks;

    /**
     * Feed one gravity sample (m/s², as Android reports it: gravity points down).
     *
     * @param smoothing 0..1, where higher means a calmer needle (0.9 is a good default)
     */
    public void update(float gravityX, float gravityY, float gravityZ, float smoothing) {
        // A phone held flat has gravity along -Z; held upright, mostly along +Y or -Y.
        double magnitude = Math.sqrt(
                gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ);
        if (magnitude < 1e-3) return; // free fall: keep the last reading rather than spin
        double roll = Math.toDegrees(Math.atan2(gravityX, gravityY));
        double pitch = Math.toDegrees(Math.atan2(
                -gravityZ, Math.sqrt(gravityX * gravityX + gravityY * gravityY)));
        // Roll is only meaningful when the phone is not pointing at the floor or ceiling.
        boolean horizonVisible = Math.abs(pitch) <= 60;
        if (!horizonVisible) roll = 0;
        float factor = Math.max(0f, Math.min(1f, smoothing));
        rollDegrees = (float) (rollDegrees * factor + roll * (1 - factor));
        pitchDegrees = (float) (pitchDegrees * factor + pitch * (1 - factor));
        // Aiming at the floor or the ceiling makes the horizon meaningless, so neither the
        // "level" claim nor a roll correction is offered there.
        boolean wasLevel = level;
        level = horizonVisible && Math.abs(rollDegrees) <= LEVEL_TOLERANCE
                && Math.abs(pitchDegrees) <= LEVEL_TOLERANCE;
        if (level && !wasLevel) lockedTicks++;
    }

    public void reset() {
        rollDegrees = 0;
        pitchDegrees = 0;
        level = true;
        lockedTicks = 0;
    }

    public float roll() {
        return rollDegrees;
    }

    public float pitch() {
        return pitchDegrees;
    }

    public boolean isLevel() {
        return level;
    }

    public boolean isNearLevel() {
        return Math.abs(rollDegrees) <= NEAR_TOLERANCE
                && Math.abs(pitchDegrees) <= NEAR_TOLERANCE;
    }

    /** How many times the user has brought the phone back to level; useful for haptics. */
    public int lockedTicks() {
        return lockedTicks;
    }

    /**
     * Degrees the user must rotate the phone to be level again, signed, so the UI can point
     * the tick the right way instead of just saying "not level".
     */
    public float correction() {
        return -rollDegrees;
    }

    /** One-line readout for the status strip. */
    public String describe() {
        if (level) return "LEVEL";
        return String.format(java.util.Locale.US, "%s%.1f°",
                rollDegrees > 0 ? "↻" : "↺", Math.abs(rollDegrees));
    }
}
