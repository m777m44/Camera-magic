package com.mahdi.camera;

import com.mahdi.camera.lut.CubeLut;
import com.mahdi.camera.lut.LutCatalog;

/**
 * Everything the GPU look pipeline needs, copyable and independent of Android views.
 *
 * <p>Immutable on purpose: the camera thread reads it while the UI thread replaces it,
 * so a half-applied look (new LUT with the previous strength) can never be rendered.
 */
final class Tuning {
    /** The bundled "neutral" look is an identity cube; index 0 of the asset manifest. */
    static final Tuning DEFAULT = new Tuning(null, 1.0f, 0f, 0f, 0f);

    final LutCatalog.Preset look;
    final float strength;
    final float clarity;
    final float grain;
    final float vignette;

    Tuning(LutCatalog.Preset look, float strength, float clarity, float grain, float vignette) {
        this.look = look;
        this.strength = unit(strength);
        this.clarity = unit(clarity);
        this.grain = unit(grain);
        this.vignette = unit(vignette);
    }

    boolean isIdentity() {
        return look == null || strength <= 0f;
    }

    Tuning withStrength(float value) {
        return new Tuning(look, value, clarity, grain, vignette);
    }

    Tuning withLook(LutCatalog.Preset preset) {
        return new Tuning(preset, strength, clarity, grain, vignette);
    }

    Tuning withDetail(float clarityValue, float grainValue, float vignetteValue) {
        return new Tuning(look, strength, clarityValue, grainValue, vignetteValue);
    }

    /** Human-readable summary for the status line and the about dialog. */
    String describe() {
        if (look == null) return "Look: none";
        return "Look: " + look.displayName() + " " + Math.round(strength * 100) + "%";
    }

    private static float unit(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** Domain and size of a preset's atlas, used by the renderer without parsing again. */
    static float[] domainOf(CubeLut cube) {
        if (cube == null) return new float[]{0f, 0f, 0f, 1f, 1f, 1f};
        return new float[]{cube.domainMin(0), cube.domainMin(1), cube.domainMin(2),
                cube.domainMax(0), cube.domainMax(1), cube.domainMax(2)};
    }
}
