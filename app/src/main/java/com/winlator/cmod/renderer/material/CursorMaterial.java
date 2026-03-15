package com.winlator.cmod.renderer.material;

/* loaded from: classes15.dex */
public class CursorMaterial extends ShaderMaterial {
    public CursorMaterial() {
        setUniformNames("xform", "viewSize", "texture");
    }

    @Override // com.winlator.cmod.renderer.material.ShaderMaterial
    protected String getVertexShader() {
        return "uniform float xform[6];\nuniform vec2 viewSize;\nattribute vec2 position;\nvarying vec2 vUV;\nvoid main() {\nvUV = position;\nvec2 transformedPos = applyXForm(position, xform);\ngl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);\n}";
    }

    @Override // com.winlator.cmod.renderer.material.ShaderMaterial
    protected String getFragmentShader() {
        return "precision mediump float;\nuniform sampler2D texture;\nvarying vec2 vUV;\nvoid main() {\ngl_FragColor = texture2D(texture, vUV);\n}";
    }
}
