package com.winlator.cmod.renderer.material;

/* loaded from: classes15.dex */
public class ScreenMaterial extends ShaderMaterial {
    public ScreenMaterial() {
        setUniformNames("resolution", "screenTexture");
    }

    @Override // com.winlator.cmod.renderer.material.ShaderMaterial
    protected String getVertexShader() {
        return String.join("\n", "attribute vec2 position;", "varying vec2 vUV;", "void main() {", "    vUV = position;", "    gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);", "}");
    }
}
