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
        this.sharpness = Math.max(0.0f, Math.min(2.0f, sharpness));
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSRRCASMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        material.setUniformFloat("sharpness", Math.max(0.0f, Math.min(2.0f, sharpness)));
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
        "    float bL = cB.g + 0.5 * (cB.r + cB.b);\n" +
        "    float dL = cD.g + 0.5 * (cD.r + cD.b);\n" +
        "    float eL = pix.g + 0.5 * (pix.r + pix.b);\n" +
        "    float fL = cF.g + 0.5 * (cF.r + cF.b);\n" +
        "    float hL = cH.g + 0.5 * (cH.r + cH.b);\n" +
        "\n" +
        "    float nz = 0.25 * (bL + dL + fL + hL) - eL;\n" +
        "    nz = clamp(abs(nz) / (max(max(bL, dL), max(eL, max(fL, hL))) - min(min(bL, dL), min(eL, min(fL, hL))) + 1e-5), 0.0, 1.0);\n" +
        "    nz = 1.0 - 0.5 * nz;\n" +
        "\n" +
        "    vec3 mn4 = min(min(cB, cD), min(cF, cH));\n" +
        "    vec3 mx4 = max(max(cB, cD), max(cF, cH));\n" +
        "    vec3 hitMin = min(mn4, pix) / (4.0 * max(mx4, pix) + 1e-5);\n" +
        "    vec3 hitMax = (1.0 - max(mx4, pix)) / (4.0 * min(mn4, pix) - 4.0 - 1e-5);\n" +
        "    vec3 hit = max(-hitMin, hitMax);\n" +
        "    float con = exp2(-sharpness);\n" +
        "    float lobe = max(-0.1875, min(min(hit.x, hit.y), hit.z)) * con * nz;\n" +
        "    vec3 rcas = (pix + lobe * (cB + cD + cF + cH)) / (1.0 + 4.0 * lobe);\n" +
        "\n" +
        "    gl_FragColor = vec4(rcas, 1.0);\n" +
        "}\n";
}
