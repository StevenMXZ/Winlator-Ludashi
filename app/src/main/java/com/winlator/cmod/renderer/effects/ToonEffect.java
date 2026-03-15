package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/* loaded from: classes9.dex */
public class ToonEffect extends Effect {
    @Override // com.winlator.cmod.renderer.effects.Effect
    protected ShaderMaterial createMaterial() {
        return new ToonMaterial();
    }

    private class ToonMaterial extends ScreenMaterial {
        public ToonMaterial() {
            setUniformNames("screenTexture", "resolution");
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        protected String getFragmentShader() {
            return String.join("\n", "precision highp float;", "uniform sampler2D screenTexture;", "uniform vec2 resolution;", "void main() {", "    vec2 uv = gl_FragCoord.xy / resolution;", "    float edgeThreshold = 0.2;", "    vec2 offset = vec2(1.0) / resolution;", "    vec3 colorCenter = texture2D(screenTexture, uv).rgb;", "    vec3 colorLeft = texture2D(screenTexture, uv - vec2(offset.x, 0.0)).rgb;", "    vec3 colorRight = texture2D(screenTexture, uv + vec2(offset.x, 0.0)).rgb;", "    vec3 colorUp = texture2D(screenTexture, uv - vec2(0.0, offset.y)).rgb;", "    vec3 colorDown = texture2D(screenTexture, uv + vec2(0.0, offset.y)).rgb;", "    float diffHorizontal = length(colorRight - colorLeft);", "    float diffVertical = length(colorUp - colorDown);", "    float edgeFactor = step(edgeThreshold, diffHorizontal + diffVertical);", "    vec3 outlineColor = mix(colorCenter, vec3(0.0), edgeFactor);", "    gl_FragColor = vec4(outlineColor, 1.0);", "}");
        }
    }
}
