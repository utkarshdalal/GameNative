package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class FSRRCASEffect extends Effect {
    private float sharpness = 0.5f;

    public float getSharpness() {
        return sharpness;
    }

    public void setSharpness(float sharpness) {
        this.sharpness = sharpness;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSRRCASMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        material.setUniformFloat("sharpness", Math.max(0.0f, Math.min(sharpness, 2.0f)));
    }

    private static class FSRRCASMaterial extends ScreenMaterial {
        public FSRRCASMaterial() {
            setUniformNames("screenTexture", "resolution", "sharpness");
        }

        @Override
        protected String getFragmentShader() {
            return "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 resolution;\n" +
                "uniform float sharpness;\n" +
                "\n" +
                "// FidelityFX Super Resolution 1.0 RCAS\n" +
                "void main() {\n" +
                "    vec2 fragCoord = gl_FragCoord.xy;\n" +
                "    vec2 invResolution = 1.0 / resolution;\n" +
                "\n" +
                "    vec3 cC = texture2D(screenTexture, fragCoord * invResolution).rgb;\n" +
                "    vec3 cB = texture2D(screenTexture, (fragCoord + vec2( 0.0, -1.0)) * invResolution).rgb;\n" +
                "    vec3 cD = texture2D(screenTexture, (fragCoord + vec2(-1.0,  0.0)) * invResolution).rgb;\n" +
                "    vec3 cF = texture2D(screenTexture, (fragCoord + vec2( 1.0,  0.0)) * invResolution).rgb;\n" +
                "    vec3 cH = texture2D(screenTexture, (fragCoord + vec2( 0.0,  1.0)) * invResolution).rgb;\n" +
                "\n" +
                "    float minRGB = min(min(min(min(cB.g, cD.g), cF.g), cH.g), cC.g);\n" +
                "    float maxRGB = max(max(max(max(cB.g, cD.g), cF.g), cH.g), cC.g);\n" +
                "    float limit = clamp(min(minRGB, 1.0 - maxRGB) / max(maxRGB, 1e-5), 0.0, 1.0);\n" +
                "    float w = sharpness * limit;\n" +
                "    vec3 rcas = (cC + w * (cB + cD + cF + cH)) / (1.0 + 4.0 * w);\n" +
                "\n" +
                "    gl_FragColor = vec4(rcas, 1.0);\n" +
                "}\n";
        }
    }
}
