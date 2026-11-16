package com.mahdi.camera.lut;

import android.opengl.GLES20;
import java.util.HashMap;
import java.util.Map;

/** Minimal GLES20 program wrapper with cached attribute/uniform locations. */
final class GlProgram {
    final int id;
    final int position;
    final int texCoord;
    final int texMatrix;
    private final Map<String, Integer> uniforms = new HashMap<>();

    private GlProgram(int id) {
        this.id = id;
        position = GLES20.glGetAttribLocation(id, "aPosition");
        texCoord = GLES20.glGetAttribLocation(id, "aTexCoord");
        texMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix");
        if (position < 0 || texCoord < 0) {
            GLES20.glDeleteProgram(id);
            throw new IllegalStateException("Shader is missing its vertex attributes");
        }
    }

    static GlProgram create(String vertexSource, String fragmentSource) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment;
        try {
            fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        } catch (RuntimeException error) {
            GLES20.glDeleteShader(vertex);
            throw error;
        }
        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            throw new IllegalStateException("Could not create a GL program");
        }
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (linked[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Shader link failed: " + log);
        }
        return new GlProgram(program);
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Could not create a shader");
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + log);
        }
        return shader;
    }

    int uniform(String name) {
        Integer cached = uniforms.get(name);
        if (cached != null) return cached;
        int location = GLES20.glGetUniformLocation(id, name);
        uniforms.put(name, location);
        return location;
    }

    void release() {
        GLES20.glDeleteProgram(id);
    }
}
