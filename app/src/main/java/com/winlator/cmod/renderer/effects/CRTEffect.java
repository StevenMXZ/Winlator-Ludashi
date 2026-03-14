package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/* loaded from: classes9.dex */
public class CRTEffect extends Effect {
    @Override // com.winlator.cmod.renderer.effects.Effect
    protected ShaderMaterial createMaterial() {
        return new CRTEffectMaterial();
    }

    private class CRTEffectMaterial extends ScreenMaterial {
        public CRTEffectMaterial() {
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        protected String getFragmentShader() {
            return String.join("\n", "precision highp float;", "#define CA_AMOUNT 1.0025", "#define SCANLINE_INTENSITY_X 0.125", "#define SCANLINE_INTENSITY_Y 0.375", "#define SCANLINE_SIZE 1024.0", "uniform sampler2D screenTexture;", "varying vec2 vUV;", "void main() {", "    vec4 finalColor = texture2D(screenTexture, vUV);", "    finalColor.rgb = vec3(", "        texture2D(screenTexture, (vUV - 0.5) * CA_AMOUNT + 0.5).r,", "        finalColor.g,", "        texture2D(screenTexture, (vUV - 0.5) / CA_AMOUNT + 0.5).b", "    );", "    float scanlineX = abs(sin(vUV.x * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_X);", "    float scanlineY = abs(sin(vUV.y * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_Y);", "    gl_FragColor = vec4(mix(finalColor.rgb, vec3(0.0), scanlineX + scanlineY), finalColor.a);", "}");
        }
    }
}
