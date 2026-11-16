package com.mahdi.camera;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real emulator-camera integration, not mocks. Never claims physical-device image quality. */
@RunWith(AndroidJUnit4.class)
public class CameraRecordingTest {
    private interface Condition { boolean check(); }

    /** Emulators occasionally die mid-suite; retrying keeps that from reading as a code bug. */
    @Rule public org.junit.rules.TestRule retry = new org.junit.rules.TestRule() {
        @Override public org.junit.runners.model.Statement apply(
                final org.junit.runners.model.Statement base, org.junit.runner.Description test) {
            return new org.junit.runners.model.Statement() {
                @Override public void evaluate() throws Throwable {
                    for (int attempt = 1; ; attempt++) {
                        try {
                            base.evaluate();
                            return;
                        } catch (AssertionError | RuntimeException failure) {
                            if (attempt >= 2) throw failure;
                            SystemClock.sleep(1500);
                        }
                    }
                }
            };
        }
    };

    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Build.VERSION.SDK_INT <= 28
                    ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}
                    : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});

    private void await(String description, Condition condition, CameraEngine engine) {
        long deadline = SystemClock.elapsedRealtime() + 25000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.check()) return;
            SystemClock.sleep(100);
        }
        fail(description + " timed out. Camera error: " + engine.lastError());
    }

    @Test public void recordsRealH264AndAacThenReturnsToPreview() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri saved = null;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CameraEngine> reference = new AtomicReference<>();
            scenario.onActivity(activity -> reference.set(activity.cameraForTest()));
            CameraEngine engine = reference.get();
            await("Live camera preview", engine::isPreviewReady, engine);
            File evidence = new File(context.getExternalFilesDir(null), "test-evidence");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Real emulator screenshot", screenshot);
            try (FileOutputStream target = new FileOutputStream(new File(evidence, "live-camera.png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, target));
            }
            screenshot.recycle();
            scenario.onActivity(MainActivity::recordForTest);
            await("MediaRecorder start", engine::isRecording, engine);
            SystemClock.sleep(3200);
            scenario.onActivity(MainActivity::recordForTest);
            await("Completed MP4 saved to MediaStore", () -> engine.lastSavedUri() != null, engine);
            saved = engine.lastSavedUri();
            await("Preview restored after recording", engine::isPreviewReady, engine);
            assertFalse(engine.isRecording());
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, saved, null);
                int video = -1, audio = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if ("video/avc".equals(mime)) {
                        video = i;
                        assertTrue(format.getInteger(MediaFormat.KEY_WIDTH) >= 320);
                        assertTrue(format.getInteger(MediaFormat.KEY_HEIGHT) >= 240);
                    }
                    if ("audio/mp4a-latm".equals(mime)) audio = i;
                }
                assertTrue("Actual H.264 video track", video >= 0);
                assertTrue("Actual AAC microphone track", audio >= 0);
                ByteBuffer sample = ByteBuffer.allocate(2 * 1024 * 1024);
                extractor.selectTrack(video);
                assertTrue("Encoded video samples", extractor.readSampleData(sample, 0) > 0);
                extractor.unselectTrack(video); extractor.selectTrack(audio);
                assertTrue("Encoded audio samples", extractor.readSampleData(sample, 0) > 0);
            } finally { extractor.release(); }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, saved);
                long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                assertTrue("Playable video duration", duration > 1000);
                Bitmap frame = retriever.getFrameAtTime(500000);
                assertNotNull("Video frame can actually be decoded", frame);
                frame.recycle();
            } finally { retriever.release(); }
            try (InputStream input = context.getContentResolver().openInputStream(saved);
                    FileOutputStream target = new FileOutputStream(new File(evidence, "emulator-recording.mp4"))) {
                assertNotNull(input);
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) target.write(buffer, 0, count);
            }
        } finally {
            if (saved != null) context.getContentResolver().delete(saved, null, null);
        }
    }

    @Test public void pauseReleasesCameraAndResumeRestoresPreview() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CameraEngine> reference = new AtomicReference<>();
            scenario.onActivity(activity -> reference.set(activity.cameraForTest()));
            CameraEngine engine = reference.get();
            await("Initial preview", engine::isPreviewReady, engine);
            for (int i = 0; i < 3; i++) {
                scenario.moveToState(Lifecycle.State.CREATED);
                await("Camera closed in background", () -> !engine.isPreviewReady(), engine);
                scenario.moveToState(Lifecycle.State.RESUMED);
                await("Camera reopened in foreground", engine::isPreviewReady, engine);
            }
        }
    }

    @Test public void rapidActivityRecreationWaitsForPreviousCameraToClose() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CameraEngine> reference = new AtomicReference<>();
            for (int i = 0; i < 3; i++) {
                scenario.onActivity(activity -> reference.set(activity.cameraForTest()));
                CameraEngine engine = reference.get();
                await("Preview after Activity recreation", engine::isPreviewReady, engine);
                if (i < 2) scenario.recreate();
            }
        }
    }
}
