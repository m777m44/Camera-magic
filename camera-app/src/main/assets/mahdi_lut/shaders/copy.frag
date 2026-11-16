// Final pass: draw the processed texture into a preview or MediaCodec surface.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uSource;
varying vec2 vTexCoord;
void main() {
    gl_FragColor = texture2D(uSource, vTexCoord);
}
