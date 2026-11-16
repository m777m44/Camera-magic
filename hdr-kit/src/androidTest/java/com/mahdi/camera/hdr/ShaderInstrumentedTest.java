package com.mahdi.camera.hdr;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import static org.junit.Assert.*;

/** Shader/atlas tests only: not Blackmagic integration, camera, or codec tests. */
@RunWith(AndroidJUnit4.class)
public class ShaderInstrumentedTest {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private AssetManager assets;

    @Before
    public void createContext() {
        assets = InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] eglVersion = new int[2]; // API-23 EGL14 rejects null major/minor arrays.
        assertTrue(EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1));
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        assertTrue(EGL14.eglChooseConfig(display, new int[]{
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
    }

    @After
    public void releaseContext() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
    }

    @Test
    public void allShadersCompileAndLink() throws Exception {
        String vertex = HdrPreset.readText(assets, "mahdi_hdr/shaders/fullscreen.vert");
        for (String fragment : new String[]{"external.frag", "detail_lut.frag", "texture.frag"}) {
            GlProgram program = new GlProgram(vertex,
                    HdrPreset.readText(assets, "mahdi_hdr/shaders/" + fragment));
            program.release();
        }
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    @Test
    public void tenPresetsLoadWithExpectedDimensions() throws Exception {
        assertEquals(10, HdrPreset.load(assets).size());
        for (HdrPreset preset : HdrPreset.load(assets)) {
            try (InputStream input = assets.open(preset.atlasAsset())) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                assertNotNull(bitmap);
                assertEquals(preset.lutSize * preset.tiles, bitmap.getWidth());
                assertEquals(bitmap.getWidth(), bitmap.getHeight());
                bitmap.recycle();
            }
        }
    }

    @Test
    public void strengthZeroIsIdentityAndFullStrengthMatchesLutReference() throws Exception {
        int[] original = {64, 128, 192};
        int[] identity = render(original, 0f);
        int[] full = render(original, 1f);
        int[] half = render(original, 0.5f);
        JSONObject config = new JSONObject(HdrPreset.readText(assets, "mahdi_hdr/presets.json"));
        JSONObject preset = config.getJSONArray("presets").getJSONObject(0);
        double[] expected = gradeReference(original, preset);
        boolean changed = false;
        for (int i = 0; i < 3; i++) {
            assertEquals(original[i], identity[i], 1.0);
            assertEquals(expected[i] * 255.0, full[i], 4.0); // 16^3 interpolation + PNG quantization.
            assertEquals((identity[i] + full[i]) * 0.5, half[i], 1.0);
            changed |= Math.abs(full[i] - identity[i]) > 1;
        }
        assertTrue("A non-identity preset must actually alter this test pixel", changed);
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private int[] render(int[] rgb, float strength) throws Exception {
        String vertex = HdrPreset.readText(assets, "mahdi_hdr/shaders/fullscreen.vert");
        GlProgram program = new GlProgram(vertex,
                HdrPreset.readText(assets, "mahdi_hdr/shaders/detail_lut.frag"));
        HdrPreset preset = HdrPreset.load(assets).get(0);
        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            configure(textures[0]);
            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            pixel.put(new byte[]{(byte) rgb[0], (byte) rgb[1], (byte) rgb[2], (byte) 255}).position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            configure(textures[1]);
            try (InputStream input = assets.open(preset.atlasAsset())) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                options.inPremultiplied = false;
                Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                assertNotNull(bitmap);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                bitmap.recycle();
            }
            FloatBuffer quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f}).position(0);
            GLES20.glUseProgram(program.id);
            quad.position(0);
            GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(program.position);
            quad.position(2);
            GLES20.glVertexAttribPointer(program.texCoord, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(program.texCoord);
            float[] matrix = new float[16];
            Matrix.setIdentityM(matrix, 0);
            GLES20.glUniformMatrix4fv(program.texMatrix, 1, false, matrix, 0);
            GLES20.glUniform1i(program.uniform("uSource"), 0);
            GLES20.glUniform1i(program.uniform("uLut"), 1);
            GLES20.glUniform2f(program.uniform("uTexel"), 1f, 1f);
            GLES20.glUniform1f(program.uniform("uStrength"), strength);
            GLES20.glUniform1f(program.uniform("uClarity"), preset.clarity);
            GLES20.glUniform1f(program.uniform("uLocalContrast"), preset.localContrast);
            GLES20.glUniform1f(program.uniform("uLutSize"), preset.lutSize);
            GLES20.glUniform1f(program.uniform("uTiles"), preset.tiles);
            GLES20.glViewport(0, 0, 1, 1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            ByteBuffer output = ByteBuffer.allocateDirect(4);
            GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, output);
            GLES20.glDisableVertexAttribArray(program.position);
            GLES20.glDisableVertexAttribArray(program.texCoord);
            return new int[]{output.get(0) & 255, output.get(1) & 255, output.get(2) & 255};
        } finally {
            GLES20.glDeleteTextures(2, textures, 0);
            program.release();
        }
    }

    private void configure(int texture) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private double[] gradeReference(int[] rgb, JSONObject preset) throws Exception {
        double[] values = new double[3];
        for (int i = 0; i < 3; i++) {
            double value = clamp(rgb[i] / 255.0 * Math.pow(2.0, preset.getDouble("exposure")));
            value += preset.getDouble("shadows") * (Math.sqrt(value) - value) * 0.55;
            value -= preset.getDouble("highlights") * value * value * (1.0 - value);
            values[i] = clamp(0.5 + (value - 0.5) * preset.getDouble("contrast"));
        }
        double luma = values[0] * 0.2126 + values[1] * 0.7152 + values[2] * 0.0722;
        for (int i = 0; i < 3; i++) values[i] = luma + (values[i] - luma) * preset.getDouble("saturation");
        double tint = preset.getDouble("warmth") * 0.08 * 4.0 * luma * (1.0 - luma);
        values[0] = clamp(values[0] + tint);
        values[1] = clamp(values[1]);
        values[2] = clamp(values[2] - tint);
        return values;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
