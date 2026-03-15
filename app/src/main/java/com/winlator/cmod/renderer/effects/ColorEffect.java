package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/* loaded from: classes9.dex */
public class ColorEffect extends Effect {
    private float brightness = 0.0f;
    private float contrast = 0.0f;
    private float gamma = 1.0f;

    @Override // com.winlator.cmod.renderer.effects.Effect
    protected ShaderMaterial createMaterial() {
        return new ColorEffectMaterial();
    }

    public float getBrightness() {
        return this.brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = brightness;
    }

    public float getContrast() {
        return this.contrast;
    }

    public void setContrast(float contrast) {
        this.contrast = contrast;
    }

    public float getGamma() {
        return this.gamma;
    }

    public void setGamma(float gamma) {
        this.gamma = gamma;
    }

    private class ColorEffectMaterial extends ScreenMaterial {
        public ColorEffectMaterial() {
            setUniformNames("brightness", "contrast", "gamma", "screenTexture");
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        protected String getFragmentShader() {
            return String.join("\n", "precision highp float;", "uniform sampler2D screenTexture;", "uniform float brightness;", "uniform float contrast;", "uniform float gamma;", "varying vec2 vUV;", "void main() {", "    vec4 texelColor = texture2D(screenTexture, vUV);", "    vec3 color = texelColor.rgb;", "    color = clamp(color + brightness, 0.0, 1.0);", "    color = (color - 0.5) * clamp(contrast + 1.0, 0.5, 2.0) + 0.5;", "    color = pow(color, vec3(1.0 / gamma));", "    gl_FragColor = vec4(color, texelColor.a);", "}");
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        public void use() {
            super.use();
            float brightness = ColorEffect.this.getBrightness();
            float contrast = ColorEffect.this.getContrast();
            float gamma = ColorEffect.this.getGamma();
            float brightness2 = Math.max(-1.0f, Math.min(brightness, 1.0f));
            float contrast2 = Math.max(0.0f, Math.min(contrast, 2.0f));
            float gamma2 = Math.max(0.1f, Math.min(gamma, 5.0f));
            setUniformFloat("brightness", brightness2);
            setUniformFloat("contrast", contrast2);
            setUniformFloat("gamma", gamma2);
        }
    }
}
