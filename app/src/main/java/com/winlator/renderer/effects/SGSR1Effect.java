package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class SGSR1Effect extends Effect implements RenderScaleEffect {
    private boolean preserveAspect = false;
    private float sharpness = 0.5f;

    public boolean isPreserveAspect() {
        return preserveAspect;
    }

    public void setPreserveAspect(boolean preserveAspect) {
        this.preserveAspect = preserveAspect;
    }

    public float getSharpness() {
        return sharpness;
    }

    public void setSharpness(float sharpness) {
        this.sharpness = Math.max(0.0f, Math.min(sharpness, 1.0f));
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new SGSR1Material();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        material.setUniformFloat("preserveAspect", preserveAspect ? 1.0f : 0.0f);
        material.setUniformFloat("sharpness", sharpness);
    }

    @Override
    public int getRenderWidth(GLRenderer renderer, int outputWidth) {
        return Math.max(1, Math.min(outputWidth, renderer.getXServerWidth()));
    }

    @Override
    public int getRenderHeight(GLRenderer renderer, int outputHeight) {
        return Math.max(1, Math.min(outputHeight, renderer.getXServerHeight()));
    }

    // -------------------------------------------------------------------------

    private static class SGSR1Material extends ScreenMaterial {

        public SGSR1Material() {
            setUniformNames("screenTexture", "inputResolution", "outputResolution", "preserveAspect", "sharpness");
        }

        // Override to provide a GLES 3.0-compatible vertex shader so the
        // fragment shader can use textureGather / textureLod.
        @Override
        protected String getVertexShader() {
            return
                "#version 300 es\n" +
                "in vec2 position;\n" +
                "out highp vec2 vUV;\n" +
                "void main() {\n" +
                "    vUV = position;\n" +
                "    gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);\n" +
                "}\n";
        }

        @Override
        protected String getFragmentShader() {
            return buildEdgeDirectionShader();
        }

        // ------------------------------------------------------------------
        // Shared preamble helpers
        // ------------------------------------------------------------------

        /** Common uniforms + varyings + fastLanczos2, used by both variants. */
        private static String shaderPreamble() {
            return
                "#version 300 es\n" +
                "// Snapdragon(TM) Game Super Resolution\n" +
                "// Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.\n" +
                "// SPDX-License-Identifier: BSD-3-Clause\n" +
                "\n" +
                "precision mediump float;\n" +
                "precision highp int;\n" +
                "\n" +
                "uniform mediump sampler2D screenTexture;\n" +
                "uniform highp vec2 inputResolution;\n" +
                "uniform highp vec2 outputResolution;\n" +
                "uniform float preserveAspect;\n" +
                "uniform float sharpness;\n" +
                "\n" +
                "in highp vec2 vUV;\n" +
                "out vec4 fragColor;\n" +
                "\n" +
                "#define EDGE_THRESHOLD (8.0 / 255.0)\n" +
                "\n" +
                "float fastLanczos2(float x) {\n" +
                "    float wA = x - 4.0;\n" +
                "    float wB = x * wA - wA;\n" +
                "    wA *= wA;\n" +
                "    return wB * wA;\n" +
                "}\n";
        }

        /**
         * Preserves aspect ratio by letterboxing: returns the adjusted output UV
         * and writes to fragColor + returns true if the pixel is in the border.
         * Implemented as inlined GLSL that sets `uv` and may early-return.
         */
        private static String preserveAspectBlock() {
            return
                "    if (preserveAspect > 0.5) {\n" +
                "        float inputAspect = inputResolution.x / inputResolution.y;\n" +
                "        float outputAspect = outputResolution.x / outputResolution.y;\n" +
                "        if (outputAspect > inputAspect) {\n" +
                "            renderSize.x = outputResolution.y * inputAspect;\n" +
                "        } else {\n" +
                "            renderSize.y = outputResolution.x / inputAspect;\n" +
                "        }\n" +
                "        highp vec2 renderOffset = 0.5 * (outputResolution - renderSize);\n" +
                "        highp vec2 fc = gl_FragCoord.xy - renderOffset;\n" +
                "        if (fc.x < 0.0 || fc.x > renderSize.x || fc.y < 0.0 || fc.y > renderSize.y) {\n" +
                "            fragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                "            return;\n" +
                "        }\n" +
                "        uv = fc / renderSize;\n" +
                "    }\n";
        }

        /** The 12-tap neighbourhood gather + weight accumulation, shared by both variants. */
        private static String gatherBlock() {
            return
                "    highp vec2 texelSize = 1.0 / inputResolution;\n" +
                "\n" +
                "    highp vec2 imgCoord = uv * inputResolution + vec2(-0.5, 0.5);\n" +
                "    highp vec2 imgCoordPixel = floor(imgCoord);\n" +
                "    highp vec2 coord = imgCoordPixel * texelSize;\n" +
                "    vec2 pl = imgCoord - imgCoordPixel;\n" +
                "\n" +
                "    // textureGather component 1 = green channel (RGBA / mode 1)\n" +
                "    vec4 left = textureGather(screenTexture, coord, 1);\n" +
                "\n" +
                "    float centerY = color.g;\n" +
                "    float edgeVote = abs(left.z - left.y) + abs(centerY - left.y) + abs(centerY - left.z);\n" +
                "    if (edgeVote > EDGE_THRESHOLD) {\n" +
                "        coord.x += texelSize.x;\n" +
                "\n" +
                "        vec4 right   = textureGather(screenTexture, coord + vec2(texelSize.x, 0.0), 1);\n" +
                "        vec4 upDown;\n" +
                "        upDown.xy    = textureGather(screenTexture, coord + vec2(0.0, -texelSize.y), 1).wz;\n" +
                "        upDown.zw    = textureGather(screenTexture, coord + vec2(0.0,  texelSize.y), 1).yx;\n" +
                "\n" +
                "        float mean = (left.y + left.z + right.x + right.w) * 0.25;\n" +
                "        left   -= vec4(mean);\n" +
                "        right  -= vec4(mean);\n" +
                "        upDown -= vec4(mean);\n" +
                "        color.w = centerY - mean;\n" +
                "\n" +
                "        float sum = abs(left.x)   + abs(left.y)   + abs(left.z)   + abs(left.w)\n" +
                "                  + abs(right.x)  + abs(right.y)  + abs(right.z)  + abs(right.w)\n" +
                "                  + abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);\n";
        }

        /** The weight accumulation taps (identical across both variants once data is set). */
        private static String weightTapsBlock() {
            return
                "        vec2 aWY  = weightY(pl.x,       pl.y + 1.0, upDown.x, data);\n" +
                "        aWY      += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, data);\n" +
                "        aWY      += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, data);\n" +
                "        aWY      += weightY(pl.x,       pl.y - 2.0, upDown.w, data);\n" +
                "        aWY      += weightY(pl.x + 1.0, pl.y - 1.0, left.x,   data);\n" +
                "        aWY      += weightY(pl.x,       pl.y - 1.0, left.y,   data);\n" +
                "        aWY      += weightY(pl.x,       pl.y,       left.z,   data);\n" +
                "        aWY      += weightY(pl.x + 1.0, pl.y,       left.w,   data);\n" +
                "        aWY      += weightY(pl.x - 1.0, pl.y - 1.0, right.x,  data);\n" +
                "        aWY      += weightY(pl.x - 2.0, pl.y - 1.0, right.y,  data);\n" +
                "        aWY      += weightY(pl.x - 2.0, pl.y,       right.z,  data);\n" +
                "        aWY      += weightY(pl.x - 1.0, pl.y,       right.w,  data);\n";
        }

        /** Final Y reconstruction and color output, shared by both variants. */
        private static String finaliseBlock() {
            return
                "        float finalY = aWY.y / max(aWY.x, 1.0e-6);\n" +
                "        float maxY   = max(max(left.y, left.z), max(right.x, right.w));\n" +
                "        float minY   = min(min(left.y, left.z), min(right.x, right.w));\n" +
                "\n" +
                "        // EdgeSharpness: user sharpness [0,1] -> reference range [1.0, 2.0]\n" +
                "        float edgeSharpness = mix(1.0, 2.0, clamp(sharpness, 0.0, 1.0));\n" +
                "        float deltaY = clamp(edgeSharpness * finalY, minY, maxY) - color.w;\n" +
                "        deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);\n" +
                "\n" +
                "        color.x = clamp(color.x + deltaY, 0.0, 1.0);\n" +
                "        color.y = clamp(color.y + deltaY, 0.0, 1.0);\n" +
                "        color.z = clamp(color.z + deltaY, 0.0, 1.0);\n" +
                "    }\n" +
                "\n" +
                "    color.w = 1.0;\n" +
                "    fragColor = color;\n" +
                "}\n";
        }

        // ------------------------------------------------------------------
        // Basic variant  (sgsr1_shader_mobile.frag)
        // ------------------------------------------------------------------

        private static String buildBasicShader() {
            return shaderPreamble() +

                // weightY: radially-symmetric Lanczos kernel
                "vec2 weightY(float dx, float dy, float c, float data) {\n" +
                "    float std = data;\n" +
                "    float x = (dx * dx + dy * dy) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);\n" +
                "    float w = fastLanczos2(x);\n" +
                "    return vec2(w, w * c);\n" +
                "}\n" +

                "\nvoid main() {\n" +
                "    highp vec2 renderSize = outputResolution;\n" +
                "    highp vec2 uv = vUV;\n" +
                preserveAspectBlock() +

                "    vec4 color;\n" +
                "    color.xyz = textureLod(screenTexture, uv, 0.0).xyz;\n" +
                "\n" +
                gatherBlock() +

                // std: reference sgsr1_shader_mobile.frag formula
                "        float std  = 2.181818 / max(sum, 1.0e-6);\n" +
                "        float data = std;\n" +
                "\n" +
                weightTapsBlock() +
                finaliseBlock();
        }

        // ------------------------------------------------------------------
        // Edge-direction variant  (sgsr1_shader_mobile_edge_direction.frag)
        // ------------------------------------------------------------------

        private static String buildEdgeDirectionShader() {
            return shaderPreamble() +

                // edgeDirection: normalized gradient from left/right gather quads
                "vec2 edgeDirection(vec4 left, vec4 right) {\n" +
                "    float RxLz = right.x - left.z;\n" +
                "    float RwLy = right.w - left.y;\n" +
                "    vec2 delta;\n" +
                "    delta.x = RxLz + RwLy;\n" +
                "    delta.y = RxLz - RwLy;\n" +
                "    float lengthInv = inversesqrt(delta.x * delta.x + 3.075740e-05 + delta.y * delta.y);\n" +
                "    return vec2(delta.x * lengthInv, delta.y * lengthInv);\n" +
                "}\n" +
                "\n" +

                // weightY: edge-direction-aligned Lanczos kernel
                "vec2 weightY(float dx, float dy, float c, vec3 data) {\n" +
                "    float std  = data.x;\n" +
                "    vec2  dir  = data.yz;\n" +
                "    float edgeDis = dx * dir.y + dy * dir.x;\n" +
                "    float x = (dx * dx + dy * dy)\n" +
                "            + edgeDis * edgeDis * (clamp(c * c * std, 0.0, 1.0) * 0.7 - 1.0);\n" +
                "    float w = fastLanczos2(x);\n" +
                "    return vec2(w, w * c);\n" +
                "}\n" +

                "\nvoid main() {\n" +
                "    highp vec2 renderSize = outputResolution;\n" +
                "    highp vec2 uv = vUV;\n" +
                preserveAspectBlock() +

                "    vec4 color;\n" +
                "    color.xyz = textureLod(screenTexture, uv, 0.0).xyz;\n" +
                "\n" +
                gatherBlock() +

                // std: reference edge-direction formula — squared sumMean (tighter weights)
                "        float sumMean = 1.014185e+01 / max(sum, 1.0e-6);\n" +
                "        float std     = sumMean * sumMean;\n" +
                "        vec3  data    = vec3(std, edgeDirection(left, right));\n" +
                "\n" +
                weightTapsBlock() +
                finaliseBlock();
        }
    }
}
