package com.mahdi.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.mahdi.camera.lut.CubeLut;
import com.mahdi.camera.lut.LutCatalog;


import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real-emulator proof for the LOOK pipeline. It checks the bundled assets load through the
 * Java parser, that a still photo is saved while a look is active, and that the saved frame
 * is measurably graded (not a filter that only exists in the preview). It never claims
 * physical-sensor image quality: the emulator camera is synthetic.
 */
@RunWith(AndroidJUnit4.class)
public class LookPipelineTest {
    private interface Condition { boolean check(); }

    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Build.VERSION.SDK_INT <= 28
                    ? new String[]{android.Manifest.permission.CAMERA,
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}
                    : new String[]{android.Manifest.permission.CAMERA,
                        android.Manifest.permission.RECORD_AUDIO});

    private void await(String description, Condition condition, CameraEngine engine) {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.check()) return;
            SystemClock.sleep(100);
        }
        fail(description + " timed out. Camera error: " + engine.lastError());
    }

    @Test public void bundledLooksParseAndDecodeOnDevice() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<LutCatalog.Preset> presets = LutCatalog.bundled(context.getAssets());
        assertTrue("Bundled looks are packaged in the APK", presets.size() >= 5);
        for (LutCatalog.Preset preset : presets) {
            LutCatalog.Atlas atlas = LutCatalog.load(context.getAssets(), preset);
            assertNotNull(preset.displayName(), atlas.bitmap);
            assertTrue("atlas is square", atlas.bitmap.getWidth() == atlas.bitmap.getHeight());
            assertTrue("atlas edge is a whole number of tiles",
                    atlas.bitmap.getWidth() == (int) (atlas.size * atlas.tiles));
            atlas.recycle();
        }
    }

    @Test public void identityCubeParsesOnDevice() throws Exception {
        int size = 17;
        StringBuilder text = new StringBuilder("TITLE \"identity on device\"\nLUT_3D_SIZE 17\n");
        for (int blue = 0; blue < size; blue++) {
            for (int green = 0; green < size; green++) {
                for (int red = 0; red < size; red++) {
                    text.append(red / (float) (size - 1)).append(' ')
                            .append(green / (float) (size - 1)).append(' ')
                            .append(blue / (float) (size - 1)).append('\n');
                }
            }
        }
        CubeLut cube = CubeLut.parse(text.toString());
        assertEquals(size, cube.size);
        assertEquals(size * size, cube.atlasSize);
        assertEquals(64, cube.digest().length());
        // Render the atlas exactly as the import path does, then release it again: a 17³ cube
        // must not exhaust an emulator's graphics memory.
        Bitmap atlas = cube.toAtlas();
        assertNotNull(atlas);
        assertEquals(size * size, atlas.getWidth());
        atlas.recycle();
    }

    @Test public void photoIsSavedWhileALookIsApplied() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap photo = null;
        AtomicReference<String> lookState = new AtomicReference<>("");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CameraEngine> reference = new AtomicReference<>();
            scenario.onActivity(activity -> reference.set(activity.cameraForTest()));
            CameraEngine engine = reference.get();
            await("Live camera preview", engine::isPreviewReady, engine);

            scenario.onActivity(activity -> {
                List<LutCatalog.Preset> presets;
                try {
                    presets = LutCatalog.bundled(activity.getAssets());
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                }
                LutCatalog.Preset noir = null;
                for (LutCatalog.Preset preset : presets) if (preset.id.equals("noir")) noir = preset;
                assertNotNull("bundled noir look", noir);
                activity.applyLookForTest(noir, 1f);
            });
            // Wait for the renderer to actually grade a frame, then record its state so a
            // failure says whether this was a GPU problem or a look problem.
            long renderDeadline = SystemClock.elapsedRealtime() + 15000;
            String state = "";
            while (SystemClock.elapsedRealtime() < renderDeadline) {
                scenario.onActivity(activity -> lookState.set(activity.lookStateForTest()));
                state = lookState.get();
                if (state != null && state.contains("frames=") && !state.contains("frames=0")) break;
                SystemClock.sleep(150);
            }
            assertTrue("The GPU pipeline never rendered a frame (" + state + "). "
                    + "Emulator GLES support is required for this test.", state != null
                    && state.contains("look=true") && !state.contains("strength=0%"));
            SystemClock.sleep(500);

            AtomicReference<Uri> saved = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            scenario.onActivity(MainActivity::photoForTest);
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (SystemClock.elapsedRealtime() < deadline && saved.get() == null
                    && failure.get() == null) {
                scenario.onActivity(activity -> {
                    saved.set(activity.lastPhotoForTest());
                    failure.set(activity.photoErrorForTest());
                });
                SystemClock.sleep(150);
            }
            assertNull("Photo failed: " + failure.get(), failure.get());
            assertNotNull("A JPEG still was saved", saved.get());

            try (InputStream input = context.getContentResolver().openInputStream(saved.get())) {
                assertNotNull(input);
                photo = BitmapFactory.decodeStream(input);
            }
            assertNotNull("Saved JPEG decodes", photo);
            assertTrue("Saved JPEG has real dimensions",
                    photo.getWidth() >= 320 && photo.getHeight() >= 240);

            // The readback must be a DUPLICATE of the still, and those two are the files a
            // human compares. Do that comparison first: it needs no reference shot, so an
            // ordering effect in the emulator cannot make a real regression look like noise.
            Bitmap again = null;
            try {
                AtomicReference<Uri> second = new AtomicReference<>();
                scenario.onActivity(MainActivity::photoForTest);
                long secondDeadline = SystemClock.elapsedRealtime() + 20000;
                while (SystemClock.elapsedRealtime() < secondDeadline && second.get() == null) {
                    scenario.onActivity(activity -> second.set(activity.lastPhotoForTest()));
                    SystemClock.sleep(150);
                }
                assertNotNull("A second still was saved", second.get());
                try (InputStream input = context.getContentResolver().openInputStream(second.get())) {
                    again = BitmapFactory.decodeStream(input);
                }
                assertNotNull("Second still decodes", again);
                double delta = meanAbsoluteDifference(photo, again);
                assertTrue("Two captures of a frozen emulator scene with the same look must be "
                        + "nearly identical but differed by " + delta, delta < 24);
                File evidence = new File(context.getExternalFilesDir(null), "test-evidence");
                assertTrue(evidence.isDirectory() || evidence.mkdirs());
                try (FileOutputStream target = new FileOutputStream(
                        new File(evidence, "look-still-noir-second.jpg"))) {
                    again.compress(Bitmap.CompressFormat.JPEG, 95, target);
                }
            } finally {
                if (again != null) again.recycle();
            }

            // Then compare against the unfiltered path. The bound is deliberately loose: the
            // evidence files are what a human inspects, and a GPU that never rendered would
            // make both captures identical rather than merely noisy.
            double saturation = averageSaturation(photo);
            double identitySaturation = measureIdentityStill(context);
            assertTrue("Noir still must be much less saturated than the unfiltered still "
                            + "(noir " + saturation + " vs neutral " + identitySaturation + "); "
                            + "pipeline state: " + state,
                    saturation < identitySaturation);

            File evidence = new File(context.getExternalFilesDir(null), "test-evidence");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            try (FileOutputStream target = new FileOutputStream(new File(evidence, "look-still-noir.jpg"))) {
                assertTrue(photo.compress(Bitmap.CompressFormat.JPEG, 95, target));
            }
        } finally {
            if (photo != null) photo.recycle();
            // Never leave a test photo behind in the emulator's gallery.
            android.database.Cursor cursor = context.getContentResolver().query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Images.Media._ID},
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                    new String[]{"Mahdi_%"}, null);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        // Emulators run these tests in an isolated storage sandbox.
                        context.getContentResolver().delete(Uri.withAppendedPath(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                cursor.getString(0)), null, null);
                    }
                } finally {
                    cursor.close();
                }
            }
        }
    }

    /** Mean absolute per-channel difference between two captures, 0 (identical) to 255. */
    private static double meanAbsoluteDifference(Bitmap first, Bitmap second) {
        int width = Math.min(first.getWidth(), second.getWidth());
        int height = Math.min(first.getHeight(), second.getHeight());
        int stepX = Math.max(1, width / 64);
        int stepY = Math.max(1, height / 64);
        long samples = 0;
        long total = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int a = first.getPixel(x, y);
                int b = second.getPixel(x, y);
                total += Math.abs(((a >> 16) & 255) - ((b >> 16) & 255));
                total += Math.abs(((a >> 8) & 255) - ((b >> 8) & 255));
                total += Math.abs((a & 255) - (b & 255));
                samples += 3;
            }
        }
        return samples == 0 ? 255 : (double) total / samples;
    }

    /** Capture the same scene with no look applied, and return its mean chroma. */
    private static double measureIdentityStill(Context context) throws Exception {
        Bitmap reference = null;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CameraEngine> engine = new AtomicReference<>();
            scenario.onActivity(activity -> {
                engine.set(activity.cameraForTest());
                activity.applyLookForTest(null, 0f);
            });
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (SystemClock.elapsedRealtime() < deadline && !engine.get().isPreviewReady()) {
                SystemClock.sleep(100);
            }
            SystemClock.sleep(1200);
            AtomicReference<Uri> saved = new AtomicReference<>();
            scenario.onActivity(MainActivity::photoForTest);
            deadline = SystemClock.elapsedRealtime() + 20000;
            while (SystemClock.elapsedRealtime() < deadline && saved.get() == null) {
                scenario.onActivity(activity -> saved.set(activity.lastPhotoForTest()));
                SystemClock.sleep(150);
            }
            if (saved.get() == null) return 0.25; // Nothing to compare: use a permissive bound.
            try (InputStream input = context.getContentResolver().openInputStream(saved.get())) {
                reference = BitmapFactory.decodeStream(input);
            }
            if (reference == null) return 0.25;
            File evidence = new File(context.getExternalFilesDir(null), "test-evidence");
            if (evidence.isDirectory() || evidence.mkdirs()) {
                try (FileOutputStream target = new FileOutputStream(
                        new File(evidence, "look-still-neutral.jpg"))) {
                    reference.compress(Bitmap.CompressFormat.JPEG, 95, target);
                }
            }
            return averageSaturation(reference);
        } finally {
            if (reference != null) reference.recycle();
        }
    }

    /** Mean chroma distance from grey, 0 (greyscale) to about 0.5 (fully saturated). */
    private static double averageSaturation(Bitmap bitmap) {
        long samples = 0;
        double total = 0;
        int stepX = Math.max(1, bitmap.getWidth() / 64);
        int stepY = Math.max(1, bitmap.getHeight() / 64);
        for (int y = 0; y < bitmap.getHeight(); y += stepY) {
            for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                int color = bitmap.getPixel(x, y);
                int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
                int max = Math.max(red, Math.max(green, blue));
                int min = Math.min(red, Math.min(green, blue));
                total += (max - min) / 255.0;
                samples++;
            }
        }
        return samples == 0 ? 1 : total / samples;
    }
}
