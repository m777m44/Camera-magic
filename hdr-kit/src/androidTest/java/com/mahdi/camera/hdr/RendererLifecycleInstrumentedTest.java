package com.mahdi.camera.hdr;

import android.content.Context;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real renderer construction/preset upload/cleanup, without a camera or encoder. */
@RunWith(AndroidJUnit4.class)
public class RendererLifecycleInstrumentedTest {
    @Test
    public void rendererInitializesUploadsPresetsAndClosesOnItsGlThread() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HandlerThread thread = new HandlerThread("hdr-lifecycle-test");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            handler.post(() -> {
                try (HdrRenderer renderer = new HdrRenderer(context, 64, 64, error::set)) {
                    assertTrue(renderer.getCameraInputSurface().isValid());
                    assertEquals(thread, renderer.getHandler().getLooper().getThread());
                    renderer.setStrength(0f);
                    renderer.setStrength(0.5f);
                    renderer.setStrength(1f);
                    for (HdrPreset preset : HdrPreset.load(context.getAssets())) {
                        renderer.setPreset(preset);
                    }
                    float[] identity = new float[16];
                    Matrix.setIdentityM(identity, 0);
                    renderer.setInputTransform(identity);
                    renderer.setPreviewSurface(null, 0, 0);
                    renderer.setEncoderSurface(null);
                    try {
                        renderer.captureFilteredFrame();
                        fail("Readback without a processed camera frame must not fabricate an image");
                    } catch (IllegalStateException expected) {
                        assertTrue(expected.getMessage().contains("No processed frame"));
                    }
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                } finally {
                    complete.countDown();
                }
            });
            assertTrue("Renderer test timed out", complete.await(60, TimeUnit.SECONDS));
            if (error.get() != null) {
                throw new AssertionError("Renderer lifecycle failed", error.get());
            }
        } finally {
            thread.quitSafely();
            thread.join(3000);
        }
    }
}
