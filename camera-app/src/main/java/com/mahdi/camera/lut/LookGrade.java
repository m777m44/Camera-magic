package com.mahdi.camera.lut;

import android.graphics.Bitmap;

/**
 * CPU grading of a still with the same 3D cube the preview uploads to the GPU.
 *
 * <p>Photos must not depend on the GPU path: on some devices and on emulated GL the
 * renderer never receives a camera frame, and a still that silently skipped the look would
 * be wrong. Grading is trilinear in the cube, exactly like the fragment shader.
 */
public final class LookGrade {
    private final CubeLut cube;
    private final float strength;

    public LookGrade(CubeLut cube, float strength) {
        if (cube == null) throw new IllegalArgumentException("A cube is required");
        this.cube = cube;
        this.strength = strength < 0f ? 0f : (strength > 1f ? 1f : strength);
    }

    public CubeLut cube() {
        return cube;
    }

    public float strength() {
        return strength;
    }

    /** Grade a decoded still in place-free fashion; returns a new ARGB_8888 bitmap. */
    public Bitmap apply(Bitmap source) {
        if (source == null) throw new IllegalArgumentException("A source bitmap is required");
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        float mix = strength;
        // A quantised cache keeps a 12 MP still fast: neighbouring pixels usually repeat.
        int[] lookup = new int[1 << 15];
        int[] seen = new int[1 << 15];
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            int red = (pixel >> 16) & 255;
            int green = (pixel >> 8) & 255;
            int blue = pixel & 255;
            int key = ((red >> 3) << 10) | ((green >> 3) << 5) | (blue >> 3);
            int graded;
            if (seen[key] == 1) {
                graded = lookup[key];
            } else {
                graded = cube.sample(red / 255f, green / 255f, blue / 255f);
                lookup[key] = graded;
                seen[key] = 1;
            }
            int outRed = Math.round(red + (((graded >> 16) & 255) - red) * mix);
            int outGreen = Math.round(green + (((graded >> 8) & 255) - green) * mix);
            int outBlue = Math.round(blue + ((graded & 255) - blue) * mix);
            pixels[index] = 0xFF000000 | (clamp(outRed) << 16) | (clamp(outGreen) << 8)
                    | clamp(outBlue);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}
