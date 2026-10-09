package com.winlator.renderer.effects;

import android.opengl.GLES20;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/**
 * Snapdragon Game Super Resolution 1 (SGSR1).
 *
 * Ported for this GLES-based post-process pipeline from the official Qualcomm source:
 * https://github.com/SnapdragonStudios/snapdragon-gsr/blob/main/sgsr/v1/include/glsl/sgsr1_shader_mobile.frag
 *
 * Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 */
public class SGSREffect extends Effect implements RenderScaleEffect, SourceTextureFilterEffect {
    private boolean preserveAspect = false;

    public boolean isPreserveAspect() {
        return preserveAspect;
    }

    public void setPreserveAspect(boolean preserveAspect) {
        this.preserveAspect = preserveAspect;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new SGSRMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        material.setUniformFloat("preserveAspect", preserveAspect ? 1.0f : 0.0f);
    }

    @Override
    public int getRenderWidth(GLRenderer renderer, int outputWidth) {
        return Math.max(1, Math.min(outputWidth, renderer.getXServerWidth()));
    }

    @Override
    public int getRenderHeight(GLRenderer renderer, int outputHeight) {
        return Math.max(1, Math.min(outputHeight, renderer.getXServerHeight()));
    }

    @Override
    public int getSourceMinFilter() {
        return GLES20.GL_LINEAR;
    }

    @Override
    public int getSourceMagFilter() {
        return GLES20.GL_LINEAR;
    }

    private static class SGSRMaterial extends ScreenMaterial {
        public SGSRMaterial() {
            setUniformNames("screenTexture", "inputResolution", "outputResolution", "preserveAspect");
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 inputResolution;\n" +
                "uniform vec2 outputResolution;\n" +
                "uniform float preserveAspect;\n" +
                "varying vec2 vUV;\n" +
                "float SgsrTap(vec2 p, vec2 texel) {\n" +
                "    return texture2D(screenTexture, clamp(p, 0.5 * texel, 1.0 - 0.5 * texel)).g;\n" +
                "}\n" +
                "vec4 SgsrGather(vec2 c, vec2 texel) {\n" +
                "    vec2 h = 0.5 * texel;\n" +
                "    return vec4(\n" +
                "        SgsrTap(c + vec2(-h.x, h.y), texel),\n" +
                "        SgsrTap(c + h, texel),\n" +
                "        SgsrTap(c + vec2(h.x, -h.y), texel),\n" +
                "        SgsrTap(c - h, texel)\n" +
                "    );\n" +
                "}\n" +
                "float SgsrFastLanczos2(float x) {\n" +
                "    float wA = x - 4.0;\n" +
                "    float wB = x * wA - wA;\n" +
                "    wA *= wA;\n" +
                "    return wB * wA;\n" +
                "}\n" +
                "vec2 SgsrWeightY(float dx, float dy, float c, float stdDev) {\n" +
                "    float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * stdDev, 0.0, 1.0);\n" +
                "    float w = SgsrFastLanczos2(x);\n" +
                "    return vec2(w, w * c);\n" +
                "}\n" +
                "vec3 SgsrF(vec2 uv) {\n" +
                "    vec2 texel = 1.0 / inputResolution;\n" +
                "    vec4 color;\n" +
                "    color.xyz = texture2D(screenTexture, uv).xyz;\n" +
                "    vec2 imgCoord = uv * inputResolution + vec2(-0.5, 0.5);\n" +
                "    vec2 imgCoordPixel = floor(imgCoord);\n" +
                "    vec2 coord = imgCoordPixel * texel;\n" +
                "    vec2 pl = imgCoord - imgCoordPixel;\n" +
                "    vec4 left = SgsrGather(coord, texel);\n" +
                "    float edgeVote = abs(left.z - left.y) + abs(color.y - left.y) + abs(color.y - left.z);\n" +
                "    if (edgeVote > 8.0 / 255.0) {\n" +
                "        coord.x += texel.x;\n" +
                "        vec4 right = SgsrGather(coord + vec2(texel.x, 0.0), texel);\n" +
                "        vec4 upDown;\n" +
                "        upDown.xy = SgsrGather(coord + vec2(0.0, -texel.y), texel).wz;\n" +
                "        upDown.zw = SgsrGather(coord + vec2(0.0, texel.y), texel).yx;\n" +
                "        float mean = (left.y + left.z + right.x + right.w) * 0.25;\n" +
                "        left = left - vec4(mean);\n" +
                "        right = right - vec4(mean);\n" +
                "        upDown = upDown - vec4(mean);\n" +
                "        color.w = color.y - mean;\n" +
                "        float sum = abs(left.x) + abs(left.y) + abs(left.z) + abs(left.w) +\n" +
                "            abs(right.x) + abs(right.y) + abs(right.z) + abs(right.w) +\n" +
                "            abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);\n" +
                "        float stdDev = 2.181818 / sum;\n" +
                "        vec2 aWY = SgsrWeightY(pl.x, pl.y + 1.0, upDown.x, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x - 1.0, pl.y + 1.0, upDown.y, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x - 1.0, pl.y - 2.0, upDown.z, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x, pl.y - 2.0, upDown.w, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x + 1.0, pl.y - 1.0, left.x, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x, pl.y - 1.0, left.y, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x, pl.y, left.z, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x + 1.0, pl.y, left.w, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x - 1.0, pl.y - 1.0, right.x, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x - 2.0, pl.y - 1.0, right.y, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x - 2.0, pl.y, right.z, stdDev);\n" +
                "        aWY += SgsrWeightY(pl.x - 1.0, pl.y, right.w, stdDev);\n" +
                "        float finalY = aWY.y / aWY.x;\n" +
                "        float maxY = max(max(left.y, left.z), max(right.x, right.w));\n" +
                "        float minY = min(min(left.y, left.z), min(right.x, right.w));\n" +
                "        finalY = clamp(2.0 * finalY, minY, maxY);\n" +
                "        float deltaY = clamp(finalY - color.w, -23.0 / 255.0, 23.0 / 255.0);\n" +
                "        color.xyz = clamp(color.xyz + deltaY, 0.0, 1.0);\n" +
                "    }\n" +
                "    return color.xyz;\n" +
                "}\n" +
                "void main() {\n" +
                "    if (preserveAspect > 0.5) {\n" +
                "        float inputAspect = inputResolution.x / inputResolution.y;\n" +
                "        float outputAspect = outputResolution.x / outputResolution.y;\n" +
                "        vec2 scaledOutput = outputResolution;\n" +
                "        if (outputAspect > inputAspect) {\n" +
                "            scaledOutput.x = outputResolution.y * inputAspect;\n" +
                "        } else {\n" +
                "            scaledOutput.y = outputResolution.x / inputAspect;\n" +
                "        }\n" +
                "        vec2 offset = 0.5 * (outputResolution - scaledOutput);\n" +
                "        vec2 coord = gl_FragCoord.xy - offset;\n" +
                "        if (coord.x < 0.0 || coord.x > scaledOutput.x || coord.y < 0.0 || coord.y > scaledOutput.y) {\n" +
                "            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                "            return;\n" +
                "        }\n" +
                "        gl_FragColor = vec4(SgsrF(coord / scaledOutput), 1.0);\n" +
                "    } else {\n" +
                "        gl_FragColor = vec4(SgsrF(vUV), 1.0);\n" +
                "    }\n" +
                "}";
        }
    }
}
