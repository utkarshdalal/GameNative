package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ShaderMaterial;

public abstract class Effect {
    private ShaderMaterial material;

    protected abstract ShaderMaterial createMaterial();

    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
    }

    public final ShaderMaterial getMaterial() {
        if (material == null) {
            material = createMaterial();
        }
        return material;
    }

    public final void use(GLRenderer renderer) {
        ShaderMaterial shaderMaterial = getMaterial();
        shaderMaterial.use();
        onUse(shaderMaterial, renderer);
    }

    public void destroy() {
        if (material != null) {
            material.destroy();
            material = null;
        }
    }
}
