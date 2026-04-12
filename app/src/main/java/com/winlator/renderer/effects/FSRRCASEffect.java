package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class FSRRCASEffect extends Effect {
    public static final float MIN_SHARPNESS = 0.0f;
    public static final float MAX_SHARPNESS = 2.0f;
    public static final float DEFAULT_SHARPNESS = 1.75f;

    private float sharpness = DEFAULT_SHARPNESS;

    public float getSharpness() {
        return sharpness;
    }

    public void setSharpness(float sharpness) {
        this.sharpness = clampSharpness(sharpness);
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSRRCASMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        // AMD RCAS uses sharpening attenuation: 0.0 is strongest and larger values soften the effect.
        material.setUniformFloat("sharpness", MAX_SHARPNESS - clampSharpness(sharpness));
    }

    private static float clampSharpness(float sharpness) {
        return Math.max(MIN_SHARPNESS, Math.min(MAX_SHARPNESS, sharpness));
    }

    private static class FSRRCASMaterial extends ScreenMaterial {
        public FSRRCASMaterial() {
            setUniformNames("screenTexture", "resolution", "sharpness");
        }

        @Override
        protected String getFragmentShader() {
            return RCAS_FRAGMENT_SHADER;
        }
    }

    private static final String RCAS_FRAGMENT_SHADER =
        "precision highp float;\n" +
        "uniform sampler2D screenTexture;\n" +
        "uniform vec2 resolution;\n" +
        "uniform float sharpness;\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 fragCoord = gl_FragCoord.xy;\n" +
        "    vec3 pix = texture2D(screenTexture, fragCoord / resolution).rgb;\n" +
        "\n" +
        "    vec3 cB = texture2D(screenTexture, (fragCoord + vec2(0.0, -1.0)) / resolution).rgb;\n" +
        "    vec3 cD = texture2D(screenTexture, (fragCoord + vec2(-1.0, 0.0)) / resolution).rgb;\n" +
        "    vec3 cF = texture2D(screenTexture, (fragCoord + vec2(1.0, 0.0)) / resolution).rgb;\n" +
        "    vec3 cH = texture2D(screenTexture, (fragCoord + vec2(0.0, 1.0)) / resolution).rgb;\n" +
        "\n" +
        "    vec3 mn4 = min(min(cB, cD), min(cF, cH));\n" +
        "    vec3 mx4 = max(max(cB, cD), max(cF, cH));\n" +
        "    vec3 hitMin = min(mn4, pix) / max(4.0 * mx4, vec3(1e-5));\n" +
        "    vec3 hitMax = (1.0 - max(mx4, pix)) / min(4.0 * mn4 - 4.0, vec3(-1e-5));\n" +
        "    vec3 lobeRgb = max(-hitMin, hitMax);\n" +
        "    float con = exp2(-sharpness);\n" +
        "    float lobe = max(-0.1875, min(max(max(lobeRgb.r, lobeRgb.g), lobeRgb.b), 0.0)) * con;\n" +
        "    float rcpL = 1.0 / (1.0 + 4.0 * lobe);\n" +
        "    vec3 rcas = (pix + lobe * (cB + cD + cF + cH)) * rcpL;\n" +
        "\n" +
        "    gl_FragColor = vec4(rcas, 1.0);\n" +
        "}\n";
}
