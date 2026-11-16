#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uSource;
uniform sampler2D uLut;
uniform vec2 uTexel;
uniform float uStrength;
uniform float uClarity;
uniform float uLocalContrast;
uniform float uLutSize;
uniform float uTiles;
varying vec2 vTexCoord;

vec3 lookup(vec3 color) {
    float blue = clamp(color.b, 0.0, 1.0) * (uLutSize - 1.0);
    float lower = floor(blue);
    float upper = min(lower + 1.0, uLutSize - 1.0);
    vec2 tile0 = vec2(mod(lower, uTiles), floor(lower / uTiles));
    vec2 tile1 = vec2(mod(upper, uTiles), floor(upper / uTiles));
    // Half-texel centers prevent interpolation into the neighboring blue tile.
    vec2 inTile = vec2(0.5) + clamp(color.rg, 0.0, 1.0) * (uLutSize - 1.0);
    float atlasSize = uLutSize * uTiles;
    vec3 c0 = texture2D(uLut, (tile0 * uLutSize + inTile) / atlasSize).rgb;
    vec3 c1 = texture2D(uLut, (tile1 * uLutSize + inTile) / atlasSize).rgb;
    return mix(c0, c1, fract(blue));
}

void main() {
    vec4 source = texture2D(uSource, vTexCoord);
    vec2 dx = vec2(uTexel.x, 0.0);
    vec2 dy = vec2(0.0, uTexel.y);
    vec3 fine = (texture2D(uSource, vTexCoord + dx).rgb
               + texture2D(uSource, vTexCoord - dx).rgb
               + texture2D(uSource, vTexCoord + dy).rgb
               + texture2D(uSource, vTexCoord - dy).rgb) * 0.25;
    vec3 broad = (texture2D(uSource, vTexCoord + dx * 6.0).rgb
                + texture2D(uSource, vTexCoord - dx * 6.0).rgb
                + texture2D(uSource, vTexCoord + dy * 6.0).rgb
                + texture2D(uSource, vTexCoord - dy * 6.0).rgb) * 0.25;
    vec3 detailed = clamp(source.rgb + uClarity * (source.rgb - fine)
                        + uLocalContrast * (source.rgb - broad), 0.0, 1.0);
    vec3 graded = lookup(detailed);
    // Strength zero is identity for both the LUT and the spatial-detail effect.
    gl_FragColor = vec4(mix(source.rgb, graded, clamp(uStrength, 0.0, 1.0)), source.a);
}
