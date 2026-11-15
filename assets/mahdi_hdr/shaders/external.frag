#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES uCamera;
varying vec2 vTexCoord;
void main() {
    gl_FragColor = texture2D(uCamera, vTexCoord);
}
