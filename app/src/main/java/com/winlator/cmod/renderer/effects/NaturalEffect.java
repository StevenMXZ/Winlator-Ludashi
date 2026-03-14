package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/* loaded from: classes9.dex */
public class NaturalEffect extends Effect {
    @Override // com.winlator.cmod.renderer.effects.Effect
    protected ShaderMaterial createMaterial() {
        return new NaturalMaterial();
    }

    private class NaturalMaterial extends ScreenMaterial {
        private NaturalMaterial() {
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        protected String getFragmentShader() {
            return String.join("\n", "precision mediump float;", "uniform sampler2D screenTexture;", "uniform vec2 resolution;", "const mat3 RGBtoYIQ = mat3(0.299, 0.596, 0.212, ", "                           0.587,-0.275,-0.523, ", "                           0.114,-0.321, 0.311);", "const mat3 YIQtoRGB = mat3(1.0, 1.0, 1.0,", "                           0.95568806,-0.27158179,-1.10817732,", "                           0.61985809,-0.64687381, 1.70506455);", "const vec3 val00 = vec3(1.2, 1.2, 1.2);", "void main() {", "    vec2 uv = gl_FragCoord.xy / resolution;", "    vec3 c0 = texture2D(screenTexture, uv).rgb;", "    vec3 t0 = c0 * RGBtoYIQ;", "    t0 = vec3(pow(t0.r, 1.12), t0.gb * val00.gb);", "    vec3 cFinal = t0 * YIQtoRGB;", "    gl_FragColor = vec4(cFinal, 1.0);", "}");
        }
    }
}
