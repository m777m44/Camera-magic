// Pass 2: 3D colour cube (packed as a square atlas) plus optional spatial detail.
// Every uniform defaults to an identity result: strength 0 returns the untouched frame.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uSource;
uniform sampler2D uLut;
uniform vec2 uTexel;
uniform float uStrength;
uniform float uLutSize;
uniform float uLutTiles;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
uniform float uClarity;
uniform float uSharpenRadius;
uniform float uGrain;
uniform float uVignette;
varying vec2 vTexCoord;

vec3 lookupCube(vec3 color) {
    vec3 normalized = clamp((color - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
    float blue = normalized.b * (uLutSize - 1.0);
    float lower = floor(blue);
    float upper = min(lower + 1.0, uLutSize - 1.0);
    vec2 tile0 = vec2(mod(lower, uLutTiles), floor(lower / uLutTiles));
    vec2 tile1 = vec2(mod(upper, uLutTiles), floor(upper / uLutTiles));
    // Half-texel centres stop bilinear filtering from bleeding into a neighbouring tile.
    vec2 inTile = vec2(0.5) + clamp(normalized.rg, 0.0, 1.0) * (uLutSize - 1.0);
    float atlasSize = uLutSize * uLutTiles;
    vec3 c0 = texture2D(uLut, (tile0 * uLutSize + inTile) / atlasSize).rgb;
    vec3 c1 = texture2D(uLut, (tile1 * uLutSize + inTile) / atlasSize).rgb;
    return mix(c0, c1, fract(blue));
}

// Deterministic, sign-symmetric noise: no frame-to-frame flicker in stills.
float noise(vec2 position) {
    return fract(sin(dot(position, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 source = texture2D(uSource, vTexCoord);
    vec2 dx = vec2(uTexel.x * uSharpenRadius, 0.0);
    vec2 dy = vec2(0.0, uTexel.y * uSharpenRadius);
    vec3 fine = (texture2D(uSource, vTexCoord + dx).rgb
               + texture2D(uSource, vTexCoord - dx).rgb
               + texture2D(uSource, vTexCoord + dy).rgb
               + texture2D(uSource, vTexCoord - dy).rgb) * 0.25;
    vec3 detailed = clamp(source.rgb + uClarity * (source.rgb - fine), 0.0, 1.0);
    vec3 graded = lookupCube(detailed);
    vec3 result = mix(source.rgb, graded, clamp(uStrength, 0.0, 1.0));
    if (uGrain > 0.0) {
        float grain = (noise(vTexCoord * uTexel * 1024.0) - 0.5) * uGrain * 0.25;
        result = clamp(result + grain * (1.0 - abs(result * 2.0 - 1.0)), 0.0, 1.0);
    }
    if (uVignette > 0.0) {
        vec2 centred = vTexCoord - 0.5;
        float falloff = smoothstep(0.35, 0.8, length(centred) * 1.4142);
        result *= 1.0 - uVignette * falloff * 0.65;
    }
    gl_FragColor = vec4(result, source.a);
}
