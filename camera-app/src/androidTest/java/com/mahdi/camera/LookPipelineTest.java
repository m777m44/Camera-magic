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

    @Test public void identityCubeRoundTripsThroughTheJavaParserOnDevice() throws Exception {
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
        Bitmap atlas = cube.toAtlas();
        assertEquals(size * size, atlas.getWidth());
        // Corner pixels must be pure black and pure white: proof the packing order is right.
        assertEquals(0xFF000000, atlas.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, atlas.getPixel(size - 1, size - 1));
        atlas.recycle();
    }

    @Test public void photoIsSavedWhileALookIsApplied() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap photo = null;
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
            // The renderer needs a frame before a readback can succeed.
            SystemClock.sleep(1500);

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

            // The Noir look removes saturation: a graded frame must be near-greyscale.
            double saturation = averageSaturation(photo);
            assertTrue("Noir still must be desaturated (measured " + saturation + ")",
                    saturation < 0.12);

            File evidence = new File(context.getExternalFilesDir(null), "test-evidence");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            try (FileOutputStream target = new FileOutputStream(new File(evidence, "look-still.jpg"))) {
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
