package com.mahdi.camera;

/**
 * Pure geometry and timing helpers. No Android class is referenced here on purpose: the
 * texture matrix, the encoder rotation hint and the viewport maths are exactly where a
 * silent orientation or double-rotation bug hides, so they must run in JVM unit tests.
 *
 * <p>Matrices are 4×4, column-major, matching the layout GLES expects for
 * {@code glUniformMatrix4fv} with {@code transpose = false}.
 */
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

    /**
     * Texture matrix that turns a Camera2 buffer into an upright screen image.
     *
     * <p>The renderer's output size is the preview buffer size, so the buffer always fits
     * 0..1 without a crop: only a mirror (front lens), the sensor-to-display rotation and a
     * translation to put the rotated quad back into the texture rectangle are needed.
     * Camera2 buffers themselves stay bottom-up, which is why no vertical flip is applied.
     */
    public static float[] inputTransform(int sensorOrientation, int displayRotation, boolean front) {
        int turn = rotation(sensorOrientation, displayRotation, front);
        float[] result = identity();
        if (front) {
            // Mirror about u = 0.5 before rotating, so text is legible like a selfie preview.
            result = multiply(scaleMatrix(-1f, 1f), result);
            result = multiply(translate(1f, 0f), result);
        }
        result = multiply(rotate(turn), result);
        switch (turn) {
            case 90: result = multiply(translate(1f, 0f), result); break;
            case 180: result = multiply(translate(1f, 1f), result); break;
            case 270: result = multiply(translate(0f, 1f), result); break;
            default: break;
        }
        return result;
    }

    /**
     * Rotation hint for the MP4/JPEG container. The GPU already draws upright frames, so a
     * renderer-backed recording must be tagged 0 or the player would rotate it twice.
     */
    public static int containerRotation(boolean lookRendering, int sensorOrientation,
                                        int displayRotation, boolean front) {
        return lookRendering ? 0 : rotation(sensorOrientation, displayRotation, front);
    }

    /** Aspect-fit viewport into a target surface: letterboxed, never stretched. */
    public static int[] fitViewport(int sourceWidth, int sourceHeight,
                                    int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new int[]{0, 0, 1, 1};
        }
        float scale = Math.min((float) targetWidth / sourceWidth, (float) targetHeight / sourceHeight);
        int fitWidth = Math.max(1, Math.round(sourceWidth * scale));
        int fitHeight = Math.max(1, Math.round(sourceHeight * scale));
        return new int[]{(targetWidth - fitWidth) / 2, (targetHeight - fitHeight) / 2,
                fitWidth, fitHeight};
    }

    /** Guard against accepting an absurd still-photo buffer that would exhaust memory. */
    public static boolean validPhotoSize(int width, int height) {
        return width > 0 && height > 0 && width <= 8192 && height <= 8192;
    }

    // ---- column-major 4x4 helpers (public so unit tests can assert on the result) ----

    public static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        return m;
    }

    public static float[] multiply(float[] left, float[] right) {
        float[] out = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += left[k * 4 + row] * right[column * 4 + k];
                }
                out[column * 4 + row] = sum;
            }
        }
        return out;
    }

    public static float[] translate(float x, float y) {
        float[] m = identity();
        m[12] = x;
        m[13] = y;
        return m;
    }

    public static float[] scaleMatrix(float x, float y) {
        float[] m = identity();
        m[0] = x;
        m[5] = y;
        return m;
    }

    public static float[] rotate(int degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float[] m = identity();
        m[0] = cos;
        m[1] = sin;
        m[4] = -sin;
        m[5] = cos;
        return m;
    }

    /** Transform a texture coordinate; used by tests and by the crop-region maths. */
    public static float[] apply(float[] matrix, float u, float v) {
        return new float[]{
                matrix[0] * u + matrix[4] * v + matrix[12],
                matrix[1] * u + matrix[5] * v + matrix[13]};
    }
}
