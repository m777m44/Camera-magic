package com.mahdi.camera;

/** Small platform-independent calculations shared by preview and recording. */
public final class CameraMath {
    private CameraMath() { }

    public static int rotation(int sensor, int display, boolean front) {
        return ((front ? sensor + display : sensor - display) % 360 + 360) % 360;
    }

    public static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    public static long exposure(long value, long low, long high, int fps) {
        long frame = 1_000_000_000L / Math.max(1, fps);
        return Math.max(low, Math.min(value, Math.min(high, frame)));
    }

    public static String clock(long seconds) {
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
