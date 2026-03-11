package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class ColorEffect extends Effect {
    private float brightness = 0.0f;
    private float contrast = 0.0f;
    private float gamma = 1.0f;

    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = brightness;
    }

    public float getContrast() {
        return contrast;
    }

    public void setContrast(float contrast) {
        this.contrast = contrast;
    }

    public float getGamma() {
        return gamma;
    }

    public void setGamma(float gamma) {
        this.gamma = gamma;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new ColorEffectMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        material.setUniformFloat("brightness", Math.max(-1.0f, Math.min(brightness, 1.0f)));
        material.setUniformFloat("contrast", Math.max(-1.0f, Math.min(contrast, 1.0f)));
        material.setUniformFloat("gamma", Math.max(0.1f, Math.min(gamma, 5.0f)));
    }

    private static class ColorEffectMaterial extends ScreenMaterial {
        public ColorEffectMaterial() {
            setUniformNames("screenTexture", "resolution", "brightness", "contrast", "gamma");
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform float brightness;\n" +
                "uniform float contrast;\n" +
                "uniform float gamma;\n" +
                "varying vec2 vUV;\n" +
                "void main() {\n" +
                "    vec4 texelColor = texture2D(screenTexture, vUV);\n" +
                "    vec3 color = texelColor.rgb;\n" +
                "    color = clamp(color + brightness, 0.0, 1.0);\n" +
                "    color = (color - 0.5) * clamp(contrast + 1.0, 0.5, 2.0) + 0.5;\n" +
                "    color = clamp(color, 0.0, 1.0);\n" +
                "    color = pow(color, vec3(1.0 / gamma));\n" +
                "    gl_FragColor = vec4(color, texelColor.a);\n" +
                "}";
        }
    }
}
