package com.mahdi.camera.hdr;

import android.opengl.GLES20;

/** Package-private GL program helper; must be used only on the owning EGL thread. */
final class GlProgram {
    final int id;
    final int position;
    final int texCoord;
    final int texMatrix;

    GlProgram(String vertexSource, String fragmentSource) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = 0;
        int program = 0;
        try {
            fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException("GL link failed: " + GLES20.glGetProgramInfoLog(program));
            }
            id = program;
            position = GLES20.glGetAttribLocation(id, "aPosition");
            texCoord = GLES20.glGetAttribLocation(id, "aTexCoord");
            texMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix");
        } catch (RuntimeException error) {
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
            throw error;
        } finally {
            GLES20.glDeleteShader(vertex);
            if (fragment != 0) {
                GLES20.glDeleteShader(fragment);
            }
        }
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("GL compile failed: " + log);
        }
        return shader;
    }

    int uniform(String name) {
        return GLES20.glGetUniformLocation(id, name);
    }

    void release() {
        GLES20.glDeleteProgram(id);
    }
}
