package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/**
 * AMD FidelityFX Super Resolution 1.0 RCAS pass.
 *
 * Ported for this GLES-based post-process pipeline from the official GPUOpen source:
 * https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h
 *
 * Copyright (c) 2021 Advanced Micro Devices, Inc.
 * Released under the MIT license by AMD.
 */
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
                "void FsrRcasCon(out float con, float sharpness) { con = exp2(-sharpness); }\n" +
                "vec4 FsrRcasLoadF(vec2 p) { return texture2D(screenTexture, p / resolution); }\n" +
                "vec3 FsrRcasF(vec2 ip, float con) {\n" +
                "    vec2 sp = vec2(ip);\n" +
                "    vec3 b = FsrRcasLoadF(sp + vec2(0.0, -1.0)).rgb;\n" +
                "    vec3 d = FsrRcasLoadF(sp + vec2(-1.0, 0.0)).rgb;\n" +
                "    vec3 e = FsrRcasLoadF(sp).rgb;\n" +
                "    vec3 f = FsrRcasLoadF(sp + vec2(1.0, 0.0)).rgb;\n" +
                "    vec3 h = FsrRcasLoadF(sp + vec2(0.0, 1.0)).rgb;\n" +
                "    float bL = b.b * 0.5 + (b.r * 0.5 + b.g);\n" +
                "    float dL = d.b * 0.5 + (d.r * 0.5 + d.g);\n" +
                "    float eL = e.b * 0.5 + (e.r * 0.5 + e.g);\n" +
                "    float fL = f.b * 0.5 + (f.r * 0.5 + f.g);\n" +
                "    float hL = h.b * 0.5 + (h.r * 0.5 + h.g);\n" +
                "    float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;\n" +
                "    float nzRange = max(max(max(bL, dL), eL), max(fL, hL)) - min(min(min(bL, dL), eL), min(fL, hL));\n" +
                "    nz = clamp(abs(nz) / max(nzRange, 1e-6), 0.0, 1.0);\n" +
                "    nz = -0.5 * nz + 1.0;\n" +
                "    float mn4R = min(min(min(b.r, d.r), f.r), h.r);\n" +
                "    float mn4G = min(min(min(b.g, d.g), f.g), h.g);\n" +
                "    float mn4B = min(min(min(b.b, d.b), f.b), h.b);\n" +
                "    float mx4R = max(max(max(b.r, d.r), f.r), h.r);\n" +
                "    float mx4G = max(max(max(b.g, d.g), f.g), h.g);\n" +
                "    float mx4B = max(max(max(b.b, d.b), f.b), h.b);\n" +
                "    float hitMinR = min(mn4R, e.r) / max(4.0 * mx4R, 1e-6);\n" +
                "    float hitMinG = min(mn4G, e.g) / max(4.0 * mx4G, 1e-6);\n" +
                "    float hitMinB = min(mn4B, e.b) / max(4.0 * mx4B, 1e-6);\n" +
                "    float hitMaxR = (1.0 - max(mx4R, e.r)) / min(4.0 * mn4R - 4.0, -1e-6);\n" +
                "    float hitMaxG = (1.0 - max(mx4G, e.g)) / min(4.0 * mn4G - 4.0, -1e-6);\n" +
                "    float hitMaxB = (1.0 - max(mx4B, e.b)) / min(4.0 * mn4B - 4.0, -1e-6);\n" +
                "    float lobeR = max(-hitMinR, hitMaxR);\n" +
                "    float lobeG = max(-hitMinG, hitMaxG);\n" +
                "    float lobeB = max(-hitMinB, hitMaxB);\n" +
                "    float lobe = max(-FSR_RCAS_LIMIT, min(max(max(lobeR, lobeG), lobeB), 0.0)) * con;\n" +
                "    float rcpL = 1.0 / (4.0 * lobe + 1.0);\n" +
                "    return vec3(\n" +
                "        (lobe * b.r + lobe * d.r + lobe * h.r + lobe * f.r + e.r) * rcpL,\n" +
                "        (lobe * b.g + lobe * d.g + lobe * h.g + lobe * f.g + e.g) * rcpL,\n" +
                "        (lobe * b.b + lobe * d.b + lobe * h.b + lobe * f.b + e.b) * rcpL\n" +
                "    );\n" +
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
