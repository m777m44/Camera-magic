package com.mahdi.camera;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * On-device proof for the guides, the live histogram and the HDR bracket.
 *
 * <p>These run against the real emulator camera. They assert that the features are reachable
 * and that the produced file is a real, decodable JPEG, not that a synthetic emulator scene
 * looks better fused — image-quality claims belong on hardware.
 */
@RunWith(AndroidJUnit4.class)
public class GuidesAndHdrTest {
    private interface Condition { boolean check(); }

    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Build.VERSION.SDK_INT <= 28
                    ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}
                    : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});

    private void await(String description, Condition condition, CameraEngine engine) {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.check()) return;
            SystemClock.sleep(100);
        }
        fail(description + " timed out. Camera error: " + engine.lastError());
    }

    @Test public void gridLevelAndHistogramAreReachableAndMeasured() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CameraEngine> engine = new AtomicReference<>();
            scenario.onActivity(activity -> engine.set(activity.cameraForTest()));
            await("Preview for guides", engine.get()::isPreviewReady, engine.get());
            AtomicReference<String> state = new AtomicReference<>();
            scenario.onActivity(activity -> {
                activity.gridForTest(GuidesView.GRID_GOLDEN);
                activity.levelForTest(true);
                activity.histogramForTest(true);
                state.set(activity.overlayStateForTest());
            });
            assertTrue("golden grid reported: " + state.get(), state.get().contains("grid=golden"));
            assertTrue("level reported: " + state.get(), state.get().contains("level=on"));
            assertTrue("histogram reported: " + state.get(), state.get().contains("histo=on"));
            // The histogram samples the graded frame about every 400 ms; give it a moment.
            AtomicReference<String> advice = new AtomicReference<>("");
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> advice.set(activity.histogramAdviceForTest()));
                if (!advice.get().isEmpty()) break;
                SystemClock.sleep(200);
            }
            assertFalse("histogram produced an exposure reading", advice.get().isEmpty());
            assertNotNull("real screenshot of the overlays",
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot());
        }
    }

    @Test public void hdrBracketSavesARealDecodableJpeg() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri saved = null;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CameraEngine> engine = new AtomicReference<>();
            scenario.onActivity(activity -> engine.set(activity.cameraForTest()));
            await("Preview before HDR", engine.get()::isPreviewReady, engine.get());
            scenario.onActivity(activity -> activity.bracketForTest(
                    BracketPlan.bracket(3, 2f, 120)));
            AtomicReference<Boolean> active = new AtomicReference<>(false);
            scenario.onActivity(activity -> active.set(activity.bracketActiveForTest()));
            // A sensor without exposure compensation legitimately reports false; then a HDR
            // test would be a lie, so skip rather than fail on a device that cannot do it.
            org.junit.Assume.assumeTrue("This camera cannot bracket exposures", active.get());
            scenario.onActivity(MainActivity::photoForTest);
            long deadline = SystemClock.elapsedRealtime() + 60000;
            while (SystemClock.elapsedRealtime() < deadline) {
                AtomicReference<Uri> photo = new AtomicReference<>();
                scenario.onActivity(activity -> photo.set(activity.lastPhotoForTest()));
                if (photo.get() != null) { saved = photo.get(); break; }
                AtomicReference<String> error = new AtomicReference<>();
                scenario.onActivity(activity -> error.set(activity.photoErrorForTest()));
                if (error.get() != null && !error.get().isEmpty()) fail("HDR failed: " + error.get());
                SystemClock.sleep(200);
            }
            assertNotNull("HDR photo was saved", saved);
            Bitmap decoded;
            try (java.io.InputStream input = context.getContentResolver().openInputStream(saved)) {
                assertNotNull("the saved photo is readable", input);
                decoded = android.graphics.BitmapFactory.decodeStream(input);
            }
            assertNotNull("fused JPEG decodes", decoded);
            assertTrue(decoded.getWidth() >= 320);
            decoded.recycle();
        } finally {
            if (saved != null) context.getContentResolver().delete(saved, null, null);
        }
    }
}
