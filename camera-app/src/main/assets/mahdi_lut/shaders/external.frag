// Pass 1: camera external texture -> RGBA intermediate, orientation applied exactly once.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#extension GL_OES_EGL_image_external : require
uniform samplerExternalOES uCamera;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
void main() {
    gl_FragColor = vec4(texture2D(uCamera, vTexCoord).rgb, 1.0);
}
