package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import java.util.Locale;

/* loaded from: classes9.dex */
public class FSREffect extends Effect {
    public static final int MODE_DLS = 1;
    public static final int MODE_SUPER_RESOLUTION = 0;
    private int currentMode = 0;
    private float strengthLevel = 1.0f;

    public void setMode(int mode) {
        this.currentMode = mode;
    }

    public int getMode() {
        return this.currentMode;
    }

    public void setLevel(float level) {
        this.strengthLevel = level;
    }

    public float getLevel() {
        return this.strengthLevel;
    }

    @Override // com.winlator.cmod.renderer.effects.Effect
    protected ShaderMaterial createMaterial() {
        return new FSRMaterial();
    }

    private class FSRMaterial extends ScreenMaterial {
        private FSRMaterial() {
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        protected String getFragmentShader() {
            float sharp_strength;
            float saturation = 1.0f;
            float contrast = 1.0f;
            if (FSREffect.this.currentMode == 1) {
                saturation = (FSREffect.this.strengthLevel * 0.05f) + 1.0f;
                contrast = (FSREffect.this.strengthLevel * 0.02f) + 1.0f;
                sharp_strength = FSREffect.this.strengthLevel * 0.3f;
            } else {
                sharp_strength = FSREffect.this.strengthLevel * 0.5f;
            }
            String sSat = String.format(Locale.US, "%.4f", Float.valueOf(saturation));
            String sCon = String.format(Locale.US, "%.4f", Float.valueOf(contrast));
            String sSharp = String.format(Locale.US, "%.4f", Float.valueOf(sharp_strength));
            StringBuilder shader = new StringBuilder();
            shader.append("precision highp float;\n");
            shader.append("uniform sampler2D screenTexture;\n");
            shader.append("uniform vec2 resolution;\n");
            shader.append("varying vec2 vUV;\n");
            shader.append("const float SAT = ").append(sSat).append(";\n");
            shader.append("const float CON = ").append(sCon).append(";\n");
            shader.append("const float SHARP = ").append(sSharp).append(";\n");
            shader.append("const int MODE = ").append(FSREffect.this.currentMode).append(";\n");
            shader.append("void main() {\n");
            shader.append("    vec2 uv = vUV;\n");
            shader.append("    vec2 res = resolution.x > 0.0 ? resolution : vec2(1280.0, 720.0);\n");
            shader.append("    vec2 step = 1.0 / res;\n");
            shader.append("    if (MODE == 0) {\n");
            shader.append("        vec3 c = texture2D(screenTexture, uv).rgb;\n");
            shader.append("        vec3 t = texture2D(screenTexture, uv + vec2(0.0, -step.y)).rgb;\n");
            shader.append("        vec3 b = texture2D(screenTexture, uv + vec2(0.0, step.y)).rgb;\n");
            shader.append("        vec3 l = texture2D(screenTexture, uv + vec2(-step.x, 0.0)).rgb;\n");
            shader.append("        vec3 r = texture2D(screenTexture, uv + vec2(step.x, 0.0)).rgb;\n");
            shader.append("        float cL = dot(c, vec3(0.299, 0.587, 0.114));\n");
            shader.append("        float tL = dot(t, vec3(0.299, 0.587, 0.114));\n");
            shader.append("        float bL = dot(b, vec3(0.299, 0.587, 0.114));\n");
            shader.append("        float lL = dot(l, vec3(0.299, 0.587, 0.114));\n");
            shader.append("        float rL = dot(r, vec3(0.299, 0.587, 0.114));\n");
            shader.append("        float mnL = min(cL, min(tL, min(bL, min(lL, rL))));\n");
            shader.append("        float mxL = max(cL, max(tL, max(bL, max(lL, rL))));\n");
            shader.append("        float hitMin = mnL / max(4.0 * mxL, 0.0001);\n");
            shader.append("        float hitMax = (1.0 - mxL) / min(4.0 * mnL - 4.0, -0.0001);\n");
            shader.append("        float lobe = max(-hitMin, hitMax);\n");
            shader.append("        lobe = clamp(lobe * SHARP, -0.1875, 0.0);\n");
            shader.append("        vec3 resolved = (lobe * (t + b + l + r) + c) / (4.0 * lobe + 1.0);\n");
            shader.append("        gl_FragColor = vec4(clamp(resolved, 0.0, 1.0), 1.0);\n");
            shader.append("    } else if (MODE == 1) {\n");
            shader.append("        vec3 center = texture2D(screenTexture, uv).rgb;\n");
            shader.append("        center = (center - 0.5) * CON + 0.5;\n");
            shader.append("        float gray = dot(center, vec3(0.299, 0.587, 0.114));\n");
            shader.append("        center = mix(vec3(gray), center, SAT);\n");
            shader.append("        vec3 blur = (texture2D(screenTexture, uv + vec2(0.0, -step.y)).rgb + texture2D(screenTexture, uv + vec2(0.0, step.y)).rgb + texture2D(screenTexture, uv + vec2(-step.x, 0.0)).rgb + texture2D(screenTexture, uv + vec2(step.x, 0.0)).rgb) * 0.25;\n");
            shader.append("        center = center + (center - blur) * SHARP;\n");
            shader.append("        gl_FragColor = vec4(clamp(center, 0.0, 1.0), 1.0);\n");
            shader.append("    }\n");
            shader.append("}\n");
            return shader.toString();
        }
    }
}
