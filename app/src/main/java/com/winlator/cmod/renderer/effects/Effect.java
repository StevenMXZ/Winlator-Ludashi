package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ShaderMaterial;

/* loaded from: classes9.dex */
public abstract class Effect {
    private ShaderMaterial material;

    protected ShaderMaterial createMaterial() {
        return null;
    }

    public ShaderMaterial getMaterial() {
        if (this.material == null) {
            this.material = createMaterial();
        }
        return this.material;
    }
}
