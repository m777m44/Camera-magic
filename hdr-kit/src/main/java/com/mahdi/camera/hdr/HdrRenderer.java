package com.mahdi.camera.hdr;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Reference API-23+ SDR renderer, NOT an automatic patch for Blackmagic Camera.
 * Construct/use/close on one dedicated HandlerThread. Stop the Camera2 session
 * before closing. Host preview/codec Surfaces are borrowed, never released here.
 * The host owns camera selection, permissions, orientation, codec drain, audio,
 * muxing and lifecycle. On GL failure it must explicitly restore its safe path.
 */
public final class HdrRenderer implements AutoCloseable {
    public interface Listener {
        /** Called on the GL thread. Stop the camera/encoder before restoring a fallback. */
        void onRenderError(RuntimeException error);
    }

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private final Thread owner = Thread.currentThread();
    private final AssetManager assets;
    private final Handler handler;
    private final Listener listener;
    private final int width;
    private final int height;
    private final FloatBuffer quad;
    private final float[] identity = new float[16];
    private final float[] inputTransform = new float[16];
    private final float[] textureTransform = new float[16];
    private final float[] composedTransform = new float[16];
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface offscreen = EGL14.EGL_NO_SURFACE;
    private EGLSurface preview = EGL14.EGL_NO_SURFACE;
    private EGLSurface encoder = EGL14.EGL_NO_SURFACE;
    private int previewWidth;
    private int previewHeight;
    private GlProgram externalProgram;
    private GlProgram detailProgram;
    private GlProgram copyProgram;
    private int externalTexture;
    private int lutTexture;
    private final int[] textures = new int[2];
    private final int[] framebuffers = new int[2];
    private SurfaceTexture cameraTexture;
    private Surface cameraInput;
    private HdrPreset preset;
    private float strength = 0.5f;
    private long lastCameraTimestamp = Long.MIN_VALUE;
    private boolean hasFrame;
    private boolean failed;
    private volatile boolean closed;

    public HdrRenderer(Context appContext, int outputWidth, int outputHeight, Listener errorListener)
            throws IOException {
        if (Looper.myLooper() == null) {
            throw new IllegalStateException("Construct on a dedicated HandlerThread with a Looper");
        }
        if (outputWidth <= 0 || outputHeight <= 0 || errorListener == null) {
            throw new IllegalArgumentException("Positive dimensions and an error listener are required");
        }
        assets = appContext.getApplicationContext().getAssets();
        handler = new Handler(Looper.myLooper());
        listener = errorListener;
        width = outputWidth;
        height = outputHeight;
        quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f,
                            -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f}).position(0);
        Matrix.setIdentityM(identity, 0);
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
                throw new IllegalStateException("Camera external-OES textures are unavailable");
            }
            String vertex = HdrPreset.readText(assets, "mahdi_hdr/shaders/fullscreen.vert");
            externalProgram = new GlProgram(vertex, HdrPreset.readText(assets, "mahdi_hdr/shaders/external.frag"));
            detailProgram = new GlProgram(vertex, HdrPreset.readText(assets, "mahdi_hdr/shaders/detail_lut.frag"));
            copyProgram = new GlProgram(vertex, HdrPreset.readText(assets, "mahdi_hdr/shaders/texture.frag"));
            createFramebuffers();
            int[] name = new int[1];
            GLES20.glGenTextures(1, name, 0);
            externalTexture = name[0];
            configureTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            cameraTexture = new SurfaceTexture(externalTexture);
            cameraTexture.setDefaultBufferSize(width, height);
            cameraInput = new Surface(cameraTexture);
            List<HdrPreset> presets = HdrPreset.load(assets);
            setPreset(presets.get(0));
            cameraTexture.setOnFrameAvailableListener(ignored -> onFrame(), handler);
            checkGl("initialize");
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    /** Renderer-owned input. Camera2 must target this, not an unfiltered codec Surface. */
    public Surface getCameraInputSurface() {
        if (closed) {
            throw new IllegalStateException("Renderer closed");
        }
        return cameraInput;
    }

    /** Post UI changes and lifecycle operations here to preserve thread ownership. */
    public Handler getHandler() {
        return handler;
    }

    public void setStrength(float value) {
        checkUsable();
        strength = HdrPreset.finiteUnit(value);
    }

    /** Preset/strength changes take effect on the next camera frame. */
    public void setPreset(HdrPreset value) throws IOException {
        checkUsable();
        if (value == null) {
            throw new IllegalArgumentException("Null preset");
        }
        makeCurrent(offscreen);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream input = assets.open(value.atlasAsset())) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) {
            throw new IOException("Cannot decode LUT atlas");
        }
        int newTexture = 0;
        try {
            if (bitmap.getWidth() != value.lutSize * value.tiles || bitmap.getHeight() != bitmap.getWidth()) {
                throw new IOException("Unexpected atlas dimensions");
            }
            int[] name = new int[1];
            GLES20.glGenTextures(1, name, 0);
            newTexture = name[0];
            configureTexture(GLES20.GL_TEXTURE_2D, newTexture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            checkGl("upload LUT");
            if (lutTexture != 0) {
                GLES20.glDeleteTextures(1, new int[]{lutTexture}, 0);
            }
            lutTexture = newTexture;
            newTexture = 0;
            preset = value;
        } finally {
            bitmap.recycle();
            if (newTexture != 0) {
                GLES20.glDeleteTextures(1, new int[]{newTexture}, 0);
            }
        }
    }

    /** Host-supplied sampling transform (sensor orientation/mirroring/crop), column-major. */
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
        System.arraycopy(matrix, 0, inputTransform, 0, 16);
    }

    /** Borrow a preview Surface; pass null to detach it before its owner destroys it. */
    public void setPreviewSurface(Surface surface, int surfaceWidth, int surfaceHeight) {
        checkUsable();
        if (surface != null && (surfaceWidth <= 0 || surfaceHeight <= 0)) {
            throw new IllegalArgumentException("Invalid preview size");
        }
        makeCurrent(offscreen);
        destroySurface(preview);
        preview = EGL14.EGL_NO_SURFACE;
        if (surface != null) {
            preview = windowSurface(surface);
            previewWidth = surfaceWidth;
            previewHeight = surfaceHeight;
        }
    }

    /** Borrow a MediaCodec input Surface configured at exactly this renderer's output size. */
    public void setEncoderSurface(Surface surface) {
        checkUsable();
        makeCurrent(offscreen);
        destroySurface(encoder);
        encoder = EGL14.EGL_NO_SURFACE;
        if (surface != null) {
            EGLSurface candidate = windowSurface(surface);
            int[] actualWidth = new int[1];
            int[] actualHeight = new int[1];
            if (!EGL14.eglQuerySurface(display, candidate, EGL14.EGL_WIDTH, actualWidth, 0)
                    || !EGL14.eglQuerySurface(display, candidate, EGL14.EGL_HEIGHT, actualHeight, 0)
                    || actualWidth[0] != width || actualHeight[0] != height) {
                destroySurface(candidate);
                throw new IllegalArgumentException("Codec Surface dimensions differ from renderer output");
            }
            encoder = candidate;
        }
    }

    /**
     * Read back the last FILTERED frame, at preview/recording resolution (not full sensor JPEG).
     * This stalls the GPU and allocates memory; do not call for every video frame.
     * Caller owns/recycles the returned Bitmap and handles JPEG/MediaStore permissions.
     */
    public Bitmap captureFilteredFrame() {
        checkUsable();
        if (!hasFrame) {
            throw new IllegalStateException("No processed frame available");
        }
        long byteCount = (long) width * height * 4L;
        if (byteCount > 64L * 1024L * 1024L) {
            throw new IllegalStateException("Readback exceeds the 64 MiB buffer safety limit");
        }
        makeCurrent(offscreen);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1]);
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) byteCount).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        checkGl("readback");
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int red = buffer.get(offset) & 255;
                int green = buffer.get(offset + 1) & 255;
                int blue = buffer.get(offset + 2) & 255;
                int alpha = buffer.get(offset + 3) & 255;
                argb[(height - 1 - y) * width + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private void onFrame() {
        if (closed || failed) {
            return;
        }
        try {
            checkOwner();
            makeCurrent(offscreen);
            cameraTexture.updateTexImage();
            long timestamp = cameraTexture.getTimestamp();
            // SurfaceTexture callbacks can coalesce. Do not submit duplicate codec timestamps.
            if (timestamp <= lastCameraTimestamp) {
                return;
            }
            lastCameraTimestamp = timestamp;
            cameraTexture.getTransformMatrix(textureTransform);
            Matrix.multiplyMM(composedTransform, 0, textureTransform, 0, inputTransform, 0);
            // Pass 1: external camera texture to RGBA, applying orientation exactly once.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0]);
            GLES20.glViewport(0, 0, width, height);
            begin(externalProgram, composedTransform);
            bindTexture(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            GLES20.glUniform1i(externalProgram.uniform("uCamera"), 0);
            draw(externalProgram);
            // Pass 2: spatial detail and trilinear 3D LUT into one shared output texture.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1]);
            begin(detailProgram, identity);
            bindTexture(0, GLES20.GL_TEXTURE_2D, textures[0]);
            bindTexture(1, GLES20.GL_TEXTURE_2D, lutTexture);
            GLES20.glUniform1i(detailProgram.uniform("uSource"), 0);
            GLES20.glUniform1i(detailProgram.uniform("uLut"), 1);
            GLES20.glUniform2f(detailProgram.uniform("uTexel"), 1f / width, 1f / height);
            GLES20.glUniform1f(detailProgram.uniform("uStrength"), strength);
            GLES20.glUniform1f(detailProgram.uniform("uClarity"), preset.clarity);
            GLES20.glUniform1f(detailProgram.uniform("uLocalContrast"), preset.localContrast);
            GLES20.glUniform1f(detailProgram.uniform("uLutSize"), preset.lutSize);
            GLES20.glUniform1f(detailProgram.uniform("uTiles"), preset.tiles);
            draw(detailProgram);
            checkGl("process camera frame");
            hasFrame = true;
            // Pass 3: both consumers receive the same processed texture, not separate camera paths.
            if (preview != EGL14.EGL_NO_SURFACE) {
                present(preview, previewWidth, previewHeight, false, 0L);
            }
            if (encoder != EGL14.EGL_NO_SURFACE) {
                present(encoder, width, height, true, timestamp);
            }
            makeCurrent(offscreen);
        } catch (RuntimeException error) {
            failed = true;
            listener.onRenderError(error); // Never silently encode unfiltered frames on error.
        }
    }

    private void present(EGLSurface target, int targetWidth, int targetHeight, boolean isEncoder, long timestamp) {
        makeCurrent(target);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, targetWidth, targetHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float scale = Math.min((float) targetWidth / width, (float) targetHeight / height);
        int fitWidth = Math.max(1, Math.round(width * scale));
        int fitHeight = Math.max(1, Math.round(height * scale));
        GLES20.glViewport((targetWidth - fitWidth) / 2, (targetHeight - fitHeight) / 2, fitWidth, fitHeight);
        begin(copyProgram, identity);
        bindTexture(0, GLES20.GL_TEXTURE_2D, textures[1]);
        GLES20.glUniform1i(copyProgram.uniform("uSource"), 0);
        draw(copyProgram);
        checkGl("present");
        if (isEncoder && !EGLExt.eglPresentationTimeANDROID(display, target, timestamp)) {
            throw new IllegalStateException("Cannot set encoder timestamp");
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
        GLES20.glUniformMatrix4fv(program.texMatrix, 1, false, matrix, 0);
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
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("RGBA framebuffer unavailable");
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        // API-23 EGL14 JNI bindings reject null version arrays, even though newer ones accept them.
        int[] eglVersion = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1)) {
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
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("EGL context creation failed");
        }
        offscreen = EGL14.eglCreatePbufferSurface(display, config,
                        new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (offscreen == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("EGL pbuffer creation failed");
        }
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
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Use the dedicated GL thread");
        }
    }

    private void checkUsable() {
        checkOwner();
        if (closed || failed) {
            throw new IllegalStateException("Renderer closed or failed; recreate after restoring a safe camera state");
        }
    }

    private void destroySurface(EGLSurface surface) {
        if (surface != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(display, surface);
        }
    }

    @Override
    public void close() {
        checkOwner();
        if (closed) {
            return;
        }
        closed = true;
        if (cameraTexture != null) {
            cameraTexture.setOnFrameAvailableListener(null);
        }
        if (cameraInput != null) {
            cameraInput.release();
        }
        if (cameraTexture != null) {
            cameraTexture.release();
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && offscreen != EGL14.EGL_NO_SURFACE
                    && EGL14.eglMakeCurrent(display, offscreen, offscreen, context)) {
                if (externalProgram != null) externalProgram.release();
                if (detailProgram != null) detailProgram.release();
                if (copyProgram != null) copyProgram.release();
                GLES20.glDeleteTextures(2, textures, 0);
                GLES20.glDeleteTextures(1, new int[]{externalTexture}, 0);
                GLES20.glDeleteTextures(1, new int[]{lutTexture}, 0);
                GLES20.glDeleteFramebuffers(2, framebuffers, 0);
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            destroySurface(preview);
            destroySurface(encoder);
            destroySurface(offscreen);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }
}
