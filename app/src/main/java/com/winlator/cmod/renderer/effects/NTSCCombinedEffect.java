package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/* loaded from: classes9.dex */
public class NTSCCombinedEffect extends Effect {
    @Override // com.winlator.cmod.renderer.effects.Effect
    protected ShaderMaterial createMaterial() {
        return new NTSCCombinedEffectMaterial();
    }

    private class NTSCCombinedEffectMaterial extends ScreenMaterial {
        public NTSCCombinedEffectMaterial() {
            setUniformNames("screenTexture", "FrameCount", "TextureSize", "resolution");
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        protected String getFragmentShader() {
            return String.join("\n", "precision highp float;", "#define PI 3.14159265", "#define SCANLINE_INTENSITY 0.35", "#define CHROMA_OFFSET 0.005", "#define BLUR_RADIUS 0.002", "#define WARP_AMOUNT 0.01", "#define SCANLINE_DARKEN 0.5", "uniform sampler2D screenTexture;", "uniform int FrameCount;", "uniform vec2 TextureSize;", "uniform vec2 resolution;", "varying vec2 vUV;", "const mat3 yiq_mat = mat3(", "   0.299, 0.587, 0.114,", "   0.596, -0.275, -0.321,", "   0.212, -0.523, 0.311", ");", "const mat3 yiq2rgb_mat = mat3(", "   1.0, 0.956, 0.621,", "   1.0, -0.272, -0.647,", "   1.0, -1.106, 1.705", ");", "vec3 applyNTSC(vec2 uv) {", "   vec3 col = texture2D(screenTexture, uv).rgb;", "   vec3 yiq = col * yiq_mat;", "   float chromaPhase = PI * (mod(uv.y * TextureSize.y, 2.0) + float(FrameCount));", "   yiq.y *= cos(chromaPhase * 0.5);", "   yiq.z *= sin(chromaPhase * 0.5);", "   vec3 rgb = yiq * yiq2rgb_mat;", "   vec3 finalColor;", "   finalColor.r = texture2D(screenTexture, uv + vec2(CHROMA_OFFSET, 0.0)).r;", "   finalColor.g = texture2D(screenTexture, uv + vec2(0.0, BLUR_RADIUS)).g;", "   finalColor.b = texture2D(screenTexture, uv - vec2(CHROMA_OFFSET, 0.0)).b;", "   return finalColor;", "}", "vec3 applyScanlines(vec2 uv) {", "   vec3 col = texture2D(screenTexture, uv).rgb;", "   float scanline = abs(sin(uv.y * resolution.y * 2.0)) * SCANLINE_INTENSITY;", "   col *= 1.0 - (scanline * SCANLINE_DARKEN);", "   return col;", "}", "vec2 applyWarp(vec2 uv) {", "   uv = uv * 2.0 - 1.0;", "   float r = sqrt(uv.x * uv.x + uv.y * uv.y);", "   uv += uv * (r * r) * WARP_AMOUNT;", "   return uv * 0.5 + 0.5;", "}", "void main() {", "   vec2 warpedUV = applyWarp(vUV);", "   vec3 ntscColor = applyNTSC(warpedUV);", "   vec3 scanlineColor = applyScanlines(warpedUV);", "   vec3 finalColor = mix(ntscColor, scanlineColor, 0.7);", "   gl_FragColor = vec4(finalColor, 1.0);", "}");
        }
    }
}
