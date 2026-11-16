package com.mahdi.camera.lut;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GPU look renderer: Camera2 feeds an external texture, one shader applies the LUT plus
 * optional detail/grain/vignette, and the SAME processed texture is presented to the
 * preview surface and, while recording, to the MediaCodec input surface. A preview-only
 * filter is therefore impossible by construction.
 *
 * <p>Threading: construct, configure, set surfaces, capture and close on the single
 * HandlerThread supplied by the owner ({@link #getHandler()}). Camera2 must target
 * {@link #getCameraInputSurface()}, never an unfiltered codec surface.
 *
 * <p>Ordering matters on Android 9+: detach the encoder surface with
 * {@link #setEncoderSurface(Surface, int, int, long, long)} before calling
 * {@code MediaCodec.signalEndOfInputStream()}, otherwise the last frame is dropped.
 *
 * <p>This class is original code for this project. It replaces the separate reference
 * library {@code hdr-kit}, which is intentionally not part of the application.
 */
public final class LutRenderer implements AutoCloseable {
    public interface Listener {
        /** Called on the GL thread after a fatal error; the owner must restore a safe path. */
        void onRenderError(RuntimeException error);
    }

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final String SHADER_ROOT = "mahdi_lut/shaders/";

    private final Thread owner = Thread.currentThread();
    private final AssetManager assets;
    private final Handler handler;
    private final Listener listener;
    private final int width;
    private final int height;
    private final FloatBuffer quad;
    private final float[] identity = new float[16];
    private final float[] textureTransform = new float[16];
    private final float[] composedTransform = new float[16];

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface offscreen = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface encoderSurface = EGL14.EGL_NO_SURFACE;
    private int previewWidth;
    private int previewHeight;
    private int previewRotation;

    private GlProgram externalProgram;
    private GlProgram lutProgram;
    private GlProgram copyProgram;
    private int externalTexture;
    private int lutTexture;
    private final int[] textures = new int[2];
    private final int[] framebuffers = new int[2];
    private SurfaceTexture cameraTexture;
    private Surface cameraInput;

    private float strength = 0.7f;
    private float clarity;
    private float grain;
    private float vignette;
    private float lutSize = 16f;
    private float lutTiles = 4f;
    private final float[] domainMin = {0f, 0f, 0f};
    private final float[] domainMax = {1f, 1f, 1f};

    private int frameWidth = -1;
    private int frameHeight = -1;
    private float[] pendingTransform;
    private final float[] inputTransform = new float[16];
    private long lastTimestamp = Long.MIN_VALUE;
    private volatile boolean hasFrame;
    private boolean failed;
    private volatile boolean closed;
    private long frameCount;

    public LutRenderer(Context appContext, int outputWidth, int outputHeight, Listener errorListener)
            throws IOException {
        if (Looper.myLooper() == null) {
            throw new IllegalStateException("Construct on a dedicated HandlerThread with a Looper");
        }
        if (outputWidth <= 0 || outputHeight <= 0 || errorListener == null) {
            throw new IllegalArgumentException("Positive dimensions and a listener are required");
        }
        assets = appContext.getApplicationContext().getAssets();
        handler = new Handler(Looper.myLooper());
        listener = errorListener;
        width = outputWidth;
        height = outputHeight;
        quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f,
                            -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f}).position(0);
        Matrix.setIdentityM(identity, 0);
        Matrix.setIdentityM(textureTransform, 0);
        Matrix.setIdentityM(inputTransform, 0);
        try {
            initializeEgl();
            makeCurrent(offscreen);
            int[] limit = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0);
            if (width > limit[0] || height > limit[0]) {
                throw new IllegalArgumentException("Output exceeds this GPU's maximum texture size");
            }
            String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (extensions == null || !extensions.contains("GL_OES_EGL_image_external")) {
                throw new IllegalStateException("External camera textures are unavailable");
            }
            String vertex = readAsset(SHADER_ROOT + "fullscreen.vert");
            externalProgram = GlProgram.create(vertex, readAsset(SHADER_ROOT + "external.frag"));
            lutProgram = GlProgram.create(vertex, readAsset(SHADER_ROOT + "lut.frag"));
            copyProgram = GlProgram.create(vertex, readAsset(SHADER_ROOT + "copy.frag"));
            createFramebuffers();
            int[] name = new int[1];
            GLES20.glGenTextures(1, name, 0);
            externalTexture = name[0];
            configureTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            cameraTexture = new SurfaceTexture(externalTexture);
            cameraInput = new Surface(cameraTexture);
            cameraTexture.setOnFrameAvailableListener(ignored -> onFrame(), handler);
            checkGl("initialize");
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    /** Renderer-owned input surface. Camera2 must add this as a capture target. */
    public Surface getCameraInputSurface() {
        if (closed) throw new IllegalStateException("Renderer closed");
        return cameraInput;
    }

    public Handler getHandler() {
        return handler;
    }

    /** Timestamp of the last frame presented to the encoder; 0 before the first frame. */
    public long lastEncoderNanos() {
        return lastEncoderTimestamp == Long.MIN_VALUE ? 0L : lastEncoderTimestamp;
    }

    public boolean hasFrame() {
        return hasFrame;
    }

    public long frameCount() {
        return frameCount;
    }

    public int outputWidth() {
        return width;
    }

    public int outputHeight() {
        return height;
    }

    /**
     * Select a look. {@code null} means "none" and is an exact passthrough of the camera
     * frame (the shader still runs, so preview and recording stay on the same path).
     */
    public void setLook(Bitmap atlas, float lutSize, float lutTiles,
                        float[] domainMin, float[] domainMax) throws IOException {
        checkUsable();
        makeCurrent(offscreen);
        if (atlas == null) {
            if (lutTexture != 0) {
                GLES20.glDeleteTextures(1, new int[]{lutTexture}, 0);
                lutTexture = 0;
            }
            this.lutSize = 2f;
            this.lutTiles = 1f;
            for (int i = 0; i < 3; i++) {
                this.domainMin[i] = 0f;
                this.domainMax[i] = 1f;
            }
            return;
        }
        int expected = Math.round(lutSize * lutTiles);
        if (atlas.getWidth() != expected || atlas.getHeight() != expected) {
            throw new IOException("LUT atlas is " + atlas.getWidth() + "×" + atlas.getHeight()
                    + " but " + expected + "×" + expected + " was expected");
        }
        int newTexture = 0;
        try {
            int[] name = new int[1];
            GLES20.glGenTextures(1, name, 0);
            newTexture = name[0];
            configureTexture(GLES20.GL_TEXTURE_2D, newTexture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, atlas, 0);
            checkGl("upload LUT");
            if (lutTexture != 0) GLES20.glDeleteTextures(1, new int[]{lutTexture}, 0);
            lutTexture = newTexture;
            newTexture = 0;
            this.lutSize = lutSize;
            this.lutTiles = lutTiles;
            for (int i = 0; i < 3; i++) {
                this.domainMin[i] = domainMin == null ? 0f : domainMin[i];
                this.domainMax[i] = domainMax == null ? 1f : domainMax[i];
            }
        } finally {
            if (newTexture != 0) GLES20.glDeleteTextures(1, new int[]{newTexture}, 0);
        }
    }

    public void setStrength(float value) {
        checkUsable();
        strength = clamp01(value);
    }

    public void setDetail(float clarity, float grain, float vignette) {
        checkUsable();
        this.clarity = clamp01(clarity);
        this.grain = clamp01(grain);
        this.vignette = clamp01(vignette);
    }

    /** Fix the camera orientation once, on the GL thread, rather than per frame. */
    public void setInputTransform(float[] matrix) {
        checkUsable();
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException("Expected a 4x4 texture matrix");
        }
        for (float value : matrix) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("Non-finite matrix");
            }
        }
        // Applied on the next frame so the update is ordered with texture uploads.
        pendingTransform = matrix.clone();
    }

    /** Borrow the preview surface. Pass null to detach it before its owner destroys it. */
    public void setPreviewSurface(Surface surface, int surfaceWidth, int surfaceHeight) {
        checkUsable();
        if (surface != null && (surfaceWidth <= 0 || surfaceHeight <= 0)) {
            throw new IllegalArgumentException("Invalid preview size");
        }
        makeCurrent(offscreen);
        destroySurface(previewSurface);
        previewSurface = EGL14.EGL_NO_SURFACE;
        if (surface != null) {
            previewSurface = windowSurface(surface);
            previewWidth = surfaceWidth;
            previewHeight = surfaceHeight;
        }
    }

    /**
     * Borrow a MediaCodec input surface created at exactly this renderer's output size.
     * Passing null detaches it, first repeating {@code repeatNanos} so the encoder never
     * sees a timestamp going backwards when end-of-stream follows.
     */
    public void setEncoderSurface(Surface surface, long repeatNanos) {
        setEncoderSurface(surface, width, height, repeatNanos, encoderWidth, encoderHeight);
    }

    /**
     * @param repeatNanos timestamp reused for the final frame when detaching, or 0 to detach
     *                    without repeating a frame (for example when the codec has failed).
     */
    public void setEncoderSurface(Surface surface, int surfaceWidth, int surfaceHeight,
                                  long repeatNanos, int previousWidth, int previousHeight) {
        checkUsable();
        makeCurrent(offscreen);
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            // Repeat the last frame so MediaCodec receives monotonic timestamps up to EOS.
            if (repeatNanos > 0) {
                present(encoderSurface, previousWidth, previousHeight, true, repeatNanos);
            }
            destroySurface(encoderSurface);
            encoderSurface = EGL14.EGL_NO_SURFACE;
        }
        if (surface == null) return;
        EGLSurface candidate = windowSurface(surface);
        int[] actualWidth = new int[1];
        int[] actualHeight = new int[1];
        if (!EGL14.eglQuerySurface(display, candidate, EGL14.EGL_WIDTH, actualWidth, 0)
                || !EGL14.eglQuerySurface(display, candidate, EGL14.EGL_HEIGHT, actualHeight, 0)
                || actualWidth[0] != width || actualHeight[0] != height) {
            destroySurface(candidate);
            throw new IllegalArgumentException("Codec surface size differs from the renderer output");
        }
        encoderSurface = candidate;
        encoderWidth = width;
        encoderHeight = height;
        lastEncoderTimestamp = Long.MIN_VALUE;
    }

    private long lastEncoderTimestamp = Long.MIN_VALUE;
    private int encoderWidth;
    private int encoderHeight;

    /**
     * Read back the last FILTERED frame at render resolution. Stalls the GPU and allocates;
     * call it once per still photo, never per video frame.
     */
    public Bitmap readFilteredFrame() {
        checkUsable();
        if (!hasFrame) throw new IllegalStateException("No processed frame available yet");
        long bytes = (long) width * height * 4L;
        makeCurrent(offscreen);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1]);
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        checkGl("readback");
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int red = buffer.get(offset) & 255;
                int green = buffer.get(offset + 1) & 255;
                int blue = buffer.get(offset + 2) & 255;
                // GL origin is bottom-left; bitmaps are top-left.
                argb[(height - 1 - y) * width + x] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private void onFrame() {
        if (closed || failed) return;
        try {
            checkOwner();
            makeCurrent(offscreen);
            cameraTexture.updateTexImage();
            long timestamp = cameraTexture.getTimestamp();
            if (timestamp <= lastTimestamp) return; // Coalesced callback: never repeat a timestamp.
            lastTimestamp = timestamp;
            cameraTexture.getTransformMatrix(textureTransform);
            if (pendingTransform != null) {
                System.arraycopy(pendingTransform, 0, inputTransform, 0, 16);
                pendingTransform = null;
            }
            // Orientation for the camera buffer, then any crop/mirror the owner supplied.
            Matrix.multiplyMM(composedTransform, 0, textureTransform, 0, inputTransform, 0);

            // Pass 1: external texture -> RGBA intermediate, with the camera matrix applied once.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0]);
            GLES20.glViewport(0, 0, width, height);
            begin(externalProgram, composedTransform);
            bindTexture(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            GLES20.glUniform1i(externalProgram.uniform("uCamera"), 0);
            draw(externalProgram);

            // Pass 2: look + detail into the shared processed texture.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1]);
            GLES20.glViewport(0, 0, width, height);
            begin(lutProgram, identity);
            bindTexture(0, GLES20.GL_TEXTURE_2D, textures[0]);
            bindTexture(1, GLES20.GL_TEXTURE_2D, lutTexture);
            GLES20.glUniform1i(lutProgram.uniform("uSource"), 0);
            GLES20.glUniform1i(lutProgram.uniform("uLut"), 1);
            GLES20.glUniform2f(lutProgram.uniform("uTexel"), 1f / width, 1f / height);
            GLES20.glUniform1f(lutProgram.uniform("uStrength"), lutTexture == 0 ? 0f : strength);
            GLES20.glUniform1f(lutProgram.uniform("uLutSize"), lutSize);
            GLES20.glUniform1f(lutProgram.uniform("uLutTiles"), lutTiles);
            GLES20.glUniform3f(lutProgram.uniform("uDomainMin"), domainMin[0], domainMin[1], domainMin[2]);
            GLES20.glUniform3f(lutProgram.uniform("uDomainMax"), domainMax[0], domainMax[1], domainMax[2]);
            GLES20.glUniform1f(lutProgram.uniform("uClarity"), clarity);
            GLES20.glUniform1f(lutProgram.uniform("uSharpenRadius"), 1.0f);
            GLES20.glUniform1f(lutProgram.uniform("uGrain"), grain);
            GLES20.glUniform1f(lutProgram.uniform("uVignette"), vignette);
            draw(lutProgram);
            checkGl("process frame");
            frameCount++;
            hasFrame = true;

            // Pass 3: identical processed texture for both consumers.
            if (previewSurface != EGL14.EGL_NO_SURFACE) {
                present(previewSurface, previewWidth, previewHeight, false, 0L);
            }
            if (encoderSurface != EGL14.EGL_NO_SURFACE && timestamp > lastEncoderTimestamp) {
                present(encoderSurface, width, height, true, timestamp);
                lastEncoderTimestamp = timestamp;
            }
            makeCurrent(offscreen);
        } catch (RuntimeException error) {
            failed = true;
            listener.onRenderError(error);
        }
    }

    private void present(EGLSurface target, int targetWidth, int targetHeight, boolean encoder,
                         long timestamp) {
        makeCurrent(target);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, targetWidth, targetHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int[] fit = com.mahdi.camera.CameraMath.fitViewport(width, height, targetWidth, targetHeight);
        GLES20.glViewport(fit[0], fit[1], fit[2], fit[3]);
        begin(copyProgram, identity);
        bindTexture(0, GLES20.GL_TEXTURE_2D, textures[1]);
        GLES20.glUniform1i(copyProgram.uniform("uSource"), 0);
        draw(copyProgram);
        checkGl("present");
        if (encoder && !EGLExt.eglPresentationTimeANDROID(display, target, timestamp)) {
            throw new IllegalStateException("Cannot set the encoder timestamp");
        }
        if (!EGL14.eglSwapBuffers(display, target)) {
            throw new IllegalStateException("EGL swap failed: " + EGL14.eglGetError());
        }
    }

    private void begin(GlProgram program, float[] matrix) {
        GLES20.glUseProgram(program.id);
        quad.position(0);
        GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(program.position);
        quad.position(2);
        GLES20.glVertexAttribPointer(program.texCoord, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(program.texCoord);
        if (program.texMatrix >= 0) GLES20.glUniformMatrix4fv(program.texMatrix, 1, false, matrix, 0);
    }

    private void draw(GlProgram program) {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(program.position);
        GLES20.glDisableVertexAttribArray(program.texCoord);
    }

    private static void configureTexture(int target, int texture) {
        GLES20.glBindTexture(target, texture);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static void bindTexture(int unit, int target, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(target, texture);
    }

    private void createFramebuffers() {
        GLES20.glGenTextures(2, textures, 0);
        GLES20.glGenFramebuffers(2, framebuffers, 0);
        for (int i = 0; i < 2; i++) {
            configureTexture(GLES20.GL_TEXTURE_2D, textures[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, textures[i], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("RGBA framebuffer unavailable");
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        // API-23 EGL14 bindings reject null version arrays.
        int[] version = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY
                || !EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("EGL display unavailable");
        }
        int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new IllegalStateException("No recordable ES2 EGL configuration");
        }
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("EGL context failed");
        offscreen = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (offscreen == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("EGL pbuffer failed");
    }

    private EGLSurface windowSurface(Surface surface) {
        EGLSurface result = EGL14.eglCreateWindowSurface(display, config, surface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (result == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("EGL window creation failed: " + EGL14.eglGetError());
        }
        return result;
    }

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("EGL makeCurrent failed: " + EGL14.eglGetError());
        }
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(operation + ": GL error 0x" + Integer.toHexString(error));
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Use the GL thread");
    }

    private void checkUsable() {
        checkOwner();
        if (closed || failed) {
            throw new IllegalStateException("Renderer closed or failed; recreate it");
        }
    }

    private void destroySurface(EGLSurface surface) {
        if (surface != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(display, surface);
        }
    }

    private String readAsset(String path) throws IOException {
        return LutCatalog.readAsset(assets, path);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    @Override
    public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        if (cameraTexture != null) cameraTexture.setOnFrameAvailableListener(null);
        if (cameraInput != null) cameraInput.release();
        if (cameraTexture != null) cameraTexture.release();
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && offscreen != EGL14.EGL_NO_SURFACE
                    && EGL14.eglMakeCurrent(display, offscreen, offscreen, context)) {
                if (externalProgram != null) externalProgram.release();
                if (lutProgram != null) lutProgram.release();
                if (copyProgram != null) copyProgram.release();
                GLES20.glDeleteTextures(2, textures, 0);
                GLES20.glDeleteTextures(1, new int[]{externalTexture}, 0);
                if (lutTexture != 0) GLES20.glDeleteTextures(1, new int[]{lutTexture}, 0);
                GLES20.glDeleteFramebuffers(2, framebuffers, 0);
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            destroySurface(previewSurface);
            destroySurface(encoderSurface);
            destroySurface(offscreen);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }
}
