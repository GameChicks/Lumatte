#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
uniform sampler2D uInput;
uniform sampler2D uLens;
uniform sampler2D uHalo;
uniform sampler2D uRemjet;
uniform sampler3D uResponse;
uniform sampler3D uDevelopment;
uniform sampler3D uScan;
uniform vec3 uResponseLow, uResponseHigh, uDevelopmentLow, uDevelopmentHigh, uScanLow, uScanHigh;
uniform ivec2 uOrigin, uImageSize, uSourceSize;
uniform float uSoftness, uExposureGain, uPixelArea, uParticleArea;
uniform float uGrainSpacing;
uniform float uRemjetAmount;
uniform bool uHalation, uGrain;
uniform vec3 uParticleScale, uFloor, uUniformity, uDensityMaximum;
uniform vec3 uHalationMix;
uniform bool uSpectralReturn;
uniform vec3 uReturnRow0, uReturnRow1, uReturnRow2;
uniform uint uSeed;
layout(location = 0) out vec4 outColour;

vec3 cube(sampler3D data, vec3 colour, vec3 minimum, vec3 maximum) {
    ivec3 size = textureSize(data, 0);
    vec3 p = clamp((colour - minimum) / (maximum - minimum), 0.0, 1.0) * vec3(size - 1);
    ivec3 lo = ivec3(floor(p)), hi = min(lo + 1, size - 1);
    vec3 f = p - vec3(lo);
    vec3 a = texelFetch(data, ivec3(lo.x, lo.y, lo.z), 0).rgb;
    vec3 b = texelFetch(data, ivec3(hi.x, lo.y, lo.z), 0).rgb;
    vec3 c = texelFetch(data, ivec3(lo.x, hi.y, lo.z), 0).rgb;
    vec3 d = texelFetch(data, ivec3(hi.x, hi.y, lo.z), 0).rgb;
    vec3 e = texelFetch(data, ivec3(lo.x, lo.y, hi.z), 0).rgb;
    vec3 g = texelFetch(data, ivec3(hi.x, lo.y, hi.z), 0).rgb;
    vec3 h = texelFetch(data, ivec3(lo.x, hi.y, hi.z), 0).rgb;
    vec3 i = texelFetch(data, ivec3(hi.x, hi.y, hi.z), 0).rgb;
    vec3 low = (a * (1.0 - f.x) + b * f.x) * (1.0 - f.y) + (c * (1.0 - f.x) + d * f.x) * f.y;
    vec3 high = (e * (1.0 - f.x) + g * f.x) * (1.0 - f.y) + (h * (1.0 - f.x) + i * f.x) * f.y;
    return low * (1.0 - f.z) + high * f.z;
}
vec3 field(sampler2D data, ivec2 globalPixel) {
    ivec2 size = textureSize(data, 0);
    vec2 p = clamp((vec2(globalPixel) + .5) * vec2(size) / vec2(uImageSize) - .5, vec2(0.0), vec2(size - 1));
    ivec2 lo = ivec2(floor(p)), hi = min(lo + 1, size - 1);
    vec2 f = p - vec2(lo);
    vec3 a = texelFetch(data, ivec2(lo.x, lo.y), 0).rgb;
    vec3 b = texelFetch(data, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(data, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(data, ivec2(hi.x, hi.y), 0).rgb;
    return (a * (1.0 - f.x) + b * f.x) * (1.0 - f.y) + (c * (1.0 - f.x) + d * f.x) * f.y;
}
float decode(float v) { return v <= .04045 ? v / 12.92 : pow((v + .055) / 1.055, 2.4); }
float encode(float v) { return v <= .0031308 ? v * 12.92 : 1.055 * pow(v, 1.0 / 2.4) - .055; }
float remjetSeed(vec3 linear) {
    float peak=max(linear.r,max(linear.g*.85,linear.b*.65))*uExposureGain;
    float t=clamp((peak-.55)/.40,0.0,1.0);
    return t*t*(3.0-2.0*t)*clamp((peak-.35)/.65,0.0,3.0);
}
uint hash32(uint value) {
    value = (value ^ (value >> 16)) * 0x85ebca6bu;
    value = (value ^ (value >> 13)) * 0xc2b2ae35u;
    return value ^ (value >> 16);
}
float normalAt(ivec2 p, uint salt) {
    uint base = uint(p.x) * 73856093u ^ uint(p.y) * 19349663u ^ uSeed ^ salt;
    float sum = 0.0;
    for (uint n = 0u; n < 3u; ++n) sum += float(hash32(base + n * 0x9e3779b9u) & 65535u) / 65535.0 - .5;
    return sum * 2.0;
}
float noiseAt(vec2 globalPosition, uint salt) {
    vec2 p = globalPosition / uGrainSpacing;
    ivec2 lo = ivec2(floor(p)); vec2 f = p - vec2(lo);
    vec4 w = vec4((1.0-f.x)*(1.0-f.y), f.x*(1.0-f.y), (1.0-f.x)*f.y, f.x*f.y);
    vec4 v = vec4(normalAt(lo, salt), normalAt(lo + ivec2(1,0), salt),
                  normalAt(lo + ivec2(0,1), salt), normalAt(lo + ivec2(1,1), salt));
    return dot(w, v) / sqrt(dot(w, w));
}
bool invalid(vec3 value) { return any(isnan(value)) || any(isinf(value)); }
void failure(float alpha) { outColour = vec4(1.0, 0.0, 1.0, alpha == 0.0 ? 1.0 : 0.0); }
void main() {
    ivec2 localPixel = ivec2(gl_FragCoord.xy), globalPixel = uOrigin + localPixel;
    vec4 inputPixel = texelFetch(uInput, localPixel, 0);
    vec3 encoded = inputPixel.rgb;
    if (uSoftness > 0.0) {
        vec3 linear = vec3(decode(encoded.r), decode(encoded.g), decode(encoded.b));
        linear = clamp(linear * (1.0-uSoftness) + field(uLens, globalPixel), 0.0, 1.0);
        encoded = vec3(encode(linear.r), encode(linear.g), encode(linear.b));
    }
    vec3 raw = cube(uResponse, encoded, uResponseLow, uResponseHigh);
    if (uHalation) {
        if (uSpectralReturn) raw = raw + field(uHalo, globalPixel) -
            vec3(dot(uReturnRow0, raw), dot(uReturnRow1, raw), dot(uReturnRow2, raw));
        else raw = raw * (1.0 - uHalationMix) + field(uHalo, globalPixel);
    }
    vec3 logExposure = log(max(raw, 0.0) * uExposureGain + 1e-10) * .4342944819032518;
    if (invalid(logExposure)) { failure(inputPixel.a); return; }
    vec3 density = cube(uDevelopment, logExposure, uDevelopmentLow, uDevelopmentHigh);
    if (uGrain) {
        vec2 position = (vec2(globalPixel) + .5) * vec2(uSourceSize) / vec2(uImageSize) - .5;
        float sharedNoise = noiseAt(position, 0x7f4a7c15u);
        vec3 maximum = uDensityMaximum + uFloor;
        vec3 probability = clamp((density + uFloor) / maximum, 0.0, 1.0);
        vec3 variance = probability * (1.0-probability*uUniformity) * uParticleArea * uParticleScale / uPixelArea;
        vec3 deviation = maximum * sqrt(max(variance, 0.0));
        for (int c = 0; c < 3; ++c)
            density[c] += deviation[c] * (.35*sharedNoise + .9367497*noiseAt(position, 0x9e3779b9u * uint(c+1)));
    }
    if (invalid(density)) { failure(inputPixel.a); return; }
    vec3 rgb = cube(uScan, density, uScanLow, uScanHigh);
    if (invalid(rgb)) { failure(inputPixel.a); return; }
    if(uRemjetAmount>0.0) {
        vec3 source=vec3(decode(inputPixel.r),decode(inputPixel.g),decode(inputPixel.b));
        float rim=max(0.0,dot(field(uRemjet,globalPixel),vec3(.25,.65,.10))-remjetSeed(source))*uRemjetAmount;
        vec3 base=clamp(rgb,0.0,1.0);
        vec3 linear=vec3(decode(base.r),decode(base.g),decode(base.b))+vec3(.65,.014,.001)*rim;
        rgb=vec3(encode(linear.r),encode(linear.g),encode(linear.b));
    }
    if (invalid(rgb)) { failure(inputPixel.a); return; }
    outColour = vec4(clamp(rgb, 0.0, 1.0), inputPixel.a);
}
