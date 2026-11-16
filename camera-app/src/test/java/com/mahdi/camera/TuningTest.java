package com.mahdi.camera;

import com.mahdi.camera.lut.LutCatalog;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Look state is immutable so the camera thread never renders a half-applied look. */
public class TuningTest {
    @Test public void defaultTuningIsAnIdentityLook() {
        assertTrue(Tuning.DEFAULT.isIdentity());
        assertEquals(1f, Tuning.DEFAULT.strength, 0f);
        assertEquals("Look: none", Tuning.DEFAULT.describe());
    }

    @Test public void strengthIsClampedAndRejectsNaN() {
        assertEquals(0f, Tuning.DEFAULT.withStrength(-3f).strength, 0f);
        assertEquals(1f, Tuning.DEFAULT.withStrength(9f).strength, 0f);
        assertEquals(0f, Tuning.DEFAULT.withStrength(Float.NaN).strength, 0f);
        assertEquals(0.5f, Tuning.DEFAULT.withStrength(0.5f).strength, 0f);
    }

    @Test public void changingOneFieldLeavesTheOthersAlone() {
        Tuning tuned = Tuning.DEFAULT.withStrength(0.4f).withDetail(0.2f, 0.1f, 0.3f);
        Tuning look = tuned.withStrength(0.9f);
        assertEquals(0.9f, look.strength, 0f);
        assertEquals(0.2f, look.clarity, 1e-6f);
        assertEquals(0.1f, look.grain, 1e-6f);
        assertEquals(0.3f, look.vignette, 1e-6f);
    }

    @Test public void zeroStrengthCountsAsIdentityEvenWithALook() {
        LutCatalog.Preset preset = preset("cinema");
        Tuning zero = Tuning.DEFAULT.withLook(preset).withStrength(0f);
        assertTrue(zero.isIdentity());
        assertFalse(zero.withStrength(0.01f).isIdentity());
    }

    @Test public void describeMentionsTheLookAndPercentage() {
        Tuning tuning = Tuning.DEFAULT.withLook(preset("noir")).withStrength(0.65f);
        assertEquals("Look: Noir 65%", tuning.describe());
    }

    @Test public void domainDefaultsToUnitRangeWithoutACube() {
        float[] domain = Tuning.domainOf(null);
        assertEquals(0f, domain[0], 0f);
        assertEquals(1f, domain[5], 0f);
    }

    private static LutCatalog.Preset preset(String name) {
        return new LutCatalog.Preset(name, capitalise(name), "mahdi_lut/luts/" + name + ".png", null);
    }

    private static String capitalise(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
