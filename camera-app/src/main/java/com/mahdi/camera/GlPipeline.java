package com.mahdi.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import com.mahdi.camera.lut.LutCatalog;
import com.mahdi.camera.lut.LutRenderer;

/**
 * Owns the GL thread and the {@link LutRenderer} for the whole activity lifetime.
 *
 * <p>Two distinct failure paths are intentional:
 * <ul>
 *   <li><b>Look failure</b> (one bad .cube): reported to the user and the previous look is
 *       kept, so a broken file never breaks the camera.</li>
 *   <li><b>Render failure</b>: the renderer is closed, {@link State#failed} stays true and
 *       the camera is told to fall back to the plain, unfiltered preview path. The UI must
 *       say so; silently recording unfiltered frames labelled with a LUT would be a lie.</li>
 * </ul>
 */
final class GlPipeline {
    private static final String TAG = "MahdiCamera";

    interface Listener {
        /** Called on the main thread. {@code null} means no look is applied. */
        void lookChanged(String label, String message);

        /** Called on the main thread; the camera must restart without the renderer. */
        void renderFailed(String message);
    }

    static final class State {
        boolean ready;
        boolean failed;
        String error = "";
        Tuning tuning = Tuning.DEFAULT;
        int width;
        int height;
    }

    private final HandlerThread thread = new HandlerThread("MahdiCameraGL");
    private final State state = new State();
    private final Listener listener;
    private final Handler main;
    private final Context context;
    private LutRenderer renderer;
    private Handler handler;
    private volatile String lookLabel = "None";
    private boolean closed;

    GlPipeline(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.main = new Handler(android.os.Looper.getMainLooper());
    }

    State state() {
        return state;
    }

    synchronized boolean isUsable() {
        return state.ready && !state.failed;
    }

    boolean failed() {
        return state.failed;
    }

    /** Diagnostics for the status line and instrumented tests. */
    String describeState() {
        LutRenderer current = renderer;
        if (current == null) return state.failed ? "failed: " + state.error : "not ready";
        return (state.ready ? "ready" : "opening")
                + " " + current.outputWidth() + "x" + current.outputHeight()
                + " look=" + current.hasLook()
                + " strength=" + Math.round(current.currentStrength() * 100) + "%"
                + " frames=" + current.frameCount() + " first=" + current.hasFrame();
    }

    /** True once at least one graded frame has been rendered. Safe from any thread. */
    boolean hasFrame() {
        LutRenderer current = renderer;
        return current != null && current.hasFrame();
    }

    /** Start the GL thread and create a renderer sized to the given output, if possible. */
    void open(final int outputWidth, final int outputHeight) {
        if (state.failed) return;
        if (thread.isAlive() && handler != null) return; // Already opening or open.
        if (!thread.isAlive()) thread.start();
        if (handler == null) handler = new Handler(thread.getLooper());
        handler.post(() -> {
            if (closed) return;
            if (renderer != null) {
                state.width = outputWidth;
                state.height = outputHeight;
                return;
            }
            try {
                LutRenderer created = new LutRenderer(context, outputWidth, outputHeight,
                        this::onRenderError);
                // Publish the renderer only after a successful construction, and mark the
                // pipeline ready before the camera may ask for its input surface.
                renderer = created;
                state.width = outputWidth;
                state.height = outputHeight;
                state.ready = true;
                state.error = "";
                applyTuningInternal(state.tuning);
                main.post(() -> listener.lookChanged(lookLabel, null));
            } catch (RuntimeException | java.io.IOException error) {
                Log.w(TAG, "GPU look pipeline unavailable: " + error.getMessage());
                renderer = null;
                state.ready = false;
                state.failed = true;
                state.error = describe(error);
                main.post(() -> listener.renderFailed(state.error));
            }
        });
    }

    /**
     * Input surface for Camera2, or null when the renderer is not ready. Never returns a
     * surface that no one will draw into: Camera2 would create a session that never paints.
     */
    Surface cameraInput() {
        LutRenderer current = renderer;
        return current == null || !state.ready ? null : current.getCameraInputSurface();
    }

    void setPreviewSurface(final Surface surface, final int width, final int height) {
        LutRenderer current = renderer;
        if (current == null) return;
        current.getHandler().post(() -> {
            try {
                current.setPreviewSurface(surface, width, height);
            } catch (RuntimeException error) {
                onRenderError(error);
            }
        });
    }

    /** Queue an orientation change; the camera calls this whenever the display rotates. */
    void setInputTransform(final float[] matrix) {
        LutRenderer current = renderer;
        if (current == null || matrix == null) return;
        final float[] copy = matrix.clone();
        current.getHandler().post(() -> current.setInputTransform(copy));
    }

    /** Timestamp of the last frame handed to the encoder, or 0 before the first frame. */
    long lastFrameNanos() {
        LutRenderer current = renderer;
        return current == null ? 0L : current.lastEncoderNanos();
    }

    /**
     * Block briefly until a queued encoder detach has run. MediaRecorder.stop() must not
     * race the GL thread on API 29+, where the codec drains its surface synchronously.
     */
    void flushEncoderDetach() {
        LutRenderer current = renderer;
        if (current == null) return;
        final Object lock = new Object();
        final boolean[] done = {false};
        current.getHandler().post(() -> {
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            long deadline = android.os.SystemClock.elapsedRealtime() + 1500;
            while (!done[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    lock.wait(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    void setEncoderSurface(final Surface surface, final long repeatNanos) {
        LutRenderer current = renderer;
        if (current == null) return;
        current.getHandler().post(() -> {
            try {
                current.setEncoderSurface(surface, repeatNanos);
            } catch (RuntimeException error) {
                onRenderError(error);
            }
        });
    }

    /** Queue a tuning change; the camera keeps running while the new look is uploaded. */
    void apply(final Tuning tuning) {
        state.tuning = tuning;
        LutRenderer current = renderer;
        if (current == null) {
            if (!state.failed) main.post(() -> listener.lookChanged(lookLabel, null));
            return;
        }
        current.getHandler().post(() -> applyTuningInternal(tuning));
    }

    private void applyTuningInternal(Tuning tuning) {
        LutRenderer current = renderer;
        if (current == null) return;
        Bitmap atlas = null;
        try {
            float lutSize = 2f;
            float lutTiles = 1f;
            float[] domain = {0f, 0f, 0f, 1f, 1f, 1f};
            if (tuning.look != null) {
                LutCatalog.Preset preset = tuning.look;
                LutCatalog.Atlas loaded = LutCatalog.load(context.getAssets(), preset);
                atlas = loaded.bitmap;
                lutSize = loaded.size;
                lutTiles = loaded.tiles;
                domain = new float[]{loaded.domainMin[0], loaded.domainMin[1], loaded.domainMin[2],
                        loaded.domainMax[0], loaded.domainMax[1], loaded.domainMax[2]};
                current.setLook(atlas, lutSize, lutTiles, loaded.domainMin, loaded.domainMax);
                current.setStrength(tuning.strength);
                current.setDetail(tuning.clarity, tuning.grain, tuning.vignette);
                lookLabel = preset.displayName();
            } else {
                current.setLook(null, 0f, 0f, null, null);
                current.setStrength(0f);
                current.setDetail(0f, 0f, 0f);
                lookLabel = "None";
            }
            final String label = lookLabel;
            main.post(() -> listener.lookChanged(label, null));
        } catch (com.mahdi.camera.lut.CubeLut.LutException error) {
            final String message = error.getMessage();
            main.post(() -> listener.lookChanged(lookLabel, message));
        } catch (java.io.IOException | RuntimeException error) {
            final String message = describe(error);
            main.post(() -> listener.lookChanged(lookLabel, message));
        } finally {
            if (atlas != null) atlas.recycle();
        }
    }

    /** Read back the last filtered frame for a still photo. Blocking; call off the UI thread. */
    Bitmap captureFrame() {
        LutRenderer current = renderer;
        if (current == null || !isUsable()) return null;
        final Bitmap[] result = new Bitmap[1];
        final RuntimeException[] failure = new RuntimeException[1];
        final Object lock = new Object();
        current.getHandler().post(() -> {
            try {
                result[0] = current.readFilteredFrame();
            } catch (RuntimeException error) {
                failure[0] = error;
            } finally {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
            while (result[0] == null && failure[0] == null) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try {
                    lock.wait(Math.min(remaining, 250));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (failure[0] != null) {
            Log.w(TAG, "Still photo readback failed: " + failure[0].getMessage());
            return null;
        }
        return result[0];
    }

    private void onRenderError(RuntimeException error) {
        Log.e(TAG, "GPU look pipeline failed: " + error.getMessage());
        state.failed = true;
        state.ready = false;
        state.error = describe(error);
        final String message = state.error;
        main.post(() -> listener.renderFailed(message));
    }

    void close() {
        closed = true;
        final LutRenderer current = renderer;
        renderer = null;
        state.ready = false;
        if (current != null) {
            current.getHandler().post(current::close);
        }
        thread.quitSafely();
    }

    /** The camera needs a fallback when GL dies; the message must stay user-readable. */
    static String describe(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isEmpty()) return error.getClass().getSimpleName();
        return text.length() > 160 ? text.substring(0, 160) : text;
    }
}
