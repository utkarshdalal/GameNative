package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class FSR1RcasEffect extends Effect {
    private float sharpnessStops = 1.0f;

    public float getSharpnessStops() {
        return sharpnessStops;
    }

    public void setSharpnessStops(float sharpnessStops) {
        this.sharpnessStops = Math.max(0.0f, Math.min(sharpnessStops, 2.0f));
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSR1RcasMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        material.setUniformFloat("sharpnessStops", sharpnessStops);
    }

    private static class FSR1RcasMaterial extends ScreenMaterial {
        public FSR1RcasMaterial() {
            setUniformNames("screenTexture", "resolution", "sharpnessStops");
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 resolution;\n" +
                "uniform float sharpnessStops;\n" +
                "varying vec2 vUV;\n" +
                "#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))\n" +
                "void FsrRcasCon(out float con, float sharpness) {\n" +
                "    con = exp2(-sharpness);\n" +
                "}\n" +
                "vec4 FsrRcasLoadF(vec2 p) {\n" +
                "    return texture2D(screenTexture, p / resolution);\n" +
                "}\n" +
                "vec3 FsrRcasF(vec2 ip, float con) {\n" +
                "    vec2 sp = vec2(ip);\n" +
                "    vec3 b = FsrRcasLoadF(sp + vec2(0.0, -1.0)).rgb;\n" +
                "    vec3 d = FsrRcasLoadF(sp + vec2(-1.0, 0.0)).rgb;\n" +
                "    vec3 e = FsrRcasLoadF(sp).rgb;\n" +
                "    vec3 f = FsrRcasLoadF(sp + vec2(1.0, 0.0)).rgb;\n" +
                "    vec3 h = FsrRcasLoadF(sp + vec2(0.0, 1.0)).rgb;\n" +
                "    float bL = b.g + 0.5 * (b.b + b.r);\n" +
                "    float dL = d.g + 0.5 * (d.b + d.r);\n" +
                "    float eL = e.g + 0.5 * (e.b + e.r);\n" +
                "    float fL = f.g + 0.5 * (f.b + f.r);\n" +
                "    float hL = h.g + 0.5 * (h.b + h.r);\n" +
                "    float nz = 0.25 * (bL + dL + fL + hL) - eL;\n" +
                "    float nzRange = max(max(bL, dL), max(eL, max(fL, hL))) - min(min(bL, dL), min(eL, min(fL, hL)));\n" +
                "    nz = clamp(abs(nz) / max(nzRange, 1e-4), 0.0, 1.0);\n" +
                "    nz = 1.0 - 0.5 * nz;\n" +
                "    vec3 mn4 = min(b, min(f, h));\n" +
                "    vec3 mx4 = max(b, max(f, h));\n" +
                "    vec3 hitMin = mn4 / max(4.0 * mx4, vec3(1e-4));\n" +
                "    vec3 hitMax = (vec3(1.0) - mx4) / max(4.0 * mn4 - 4.0, vec3(-1e-4));\n" +
                "    vec3 lobeRGB = max(-hitMin, hitMax);\n" +
                "    float lobe = max(-FSR_RCAS_LIMIT, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * con;\n" +
                "    lobe *= nz;\n" +
                "    return (lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0);\n" +
                "}\n" +
                "void main() {\n" +
                "    float con;\n" +
                "    FsrRcasCon(con, sharpnessStops);\n" +
                "    vec3 color = FsrRcasF(floor(gl_FragCoord.xy), con);\n" +
                "    gl_FragColor = vec4(color, texture2D(screenTexture, vUV).a);\n" +
                "}";
        }
    }
}
