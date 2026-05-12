package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/**
 * AMD FidelityFX Super Resolution 1.0 EASU pass.
 *
 * Ported for this GLES-based post-process pipeline from the official GPUOpen source:
 * https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h
 *
 * Copyright (c) 2021 Advanced Micro Devices, Inc.
 * Released under the MIT license by AMD.
 */
public class FSR1EasuEffect extends Effect implements RenderScaleEffect {
    private boolean preserveAspect = false;

    public boolean isPreserveAspect() {
        return preserveAspect;
    }

    public void setPreserveAspect(boolean preserveAspect) {
        this.preserveAspect = preserveAspect;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSR1EasuMaterial();
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

    private static class FSR1EasuMaterial extends ScreenMaterial {
        public FSR1EasuMaterial() {
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
                "vec3 FsrEasuCF(vec2 p) { return texture2D(screenTexture, p).rgb; }\n" +
                "void FsrEasuCon(out vec4 con0, out vec4 con1, out vec4 con2, out vec4 con3, vec2 inputViewportInPixels, vec2 inputSizeInPixels, vec2 outputSizeInPixels) {\n" +
                "    con0 = vec4(\n" +
                "        inputViewportInPixels.x / outputSizeInPixels.x,\n" +
                "        inputViewportInPixels.y / outputSizeInPixels.y,\n" +
                "        0.5 * inputViewportInPixels.x / outputSizeInPixels.x - 0.5,\n" +
                "        0.5 * inputViewportInPixels.y / outputSizeInPixels.y - 0.5\n" +
                "    );\n" +
                "    con1 = vec4(1.0 / inputSizeInPixels.x, 1.0 / inputSizeInPixels.y, 1.0 / inputSizeInPixels.x, -1.0 / inputSizeInPixels.y);\n" +
                "    con2 = vec4(-1.0 / inputSizeInPixels.x, 2.0 / inputSizeInPixels.y, 1.0 / inputSizeInPixels.x, 2.0 / inputSizeInPixels.y);\n" +
                "    con3 = vec4(0.0, 4.0 / inputSizeInPixels.y, 0.0, 0.0);\n" +
                "}\n" +
                "void FsrEasuTapF(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {\n" +
                "    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x);\n" +
                "    v *= len;\n" +
                "    float d2 = min(dot(v, v), clp);\n" +
                "    float wB = 0.4 * d2 - 1.0;\n" +
                "    float wA = lob * d2 - 1.0;\n" +
                "    wB *= wB;\n" +
                "    wA *= wA;\n" +
                "    wB = 1.5625 * wB - 0.5625;\n" +
                "    float w = wB * wA;\n" +
                "    aC += c * w;\n" +
                "    aW += w;\n" +
                "}\n" +
                "void FsrEasuSetF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {\n" +
                "    float dc = lD - lC;\n" +
                "    float cb = lC - lB;\n" +
                "    float lenX = max(abs(dc), abs(cb));\n" +
                "    lenX = 1.0 / max(lenX, 1e-6);\n" +
                "    float dirX = lD - lB;\n" +
                "    dir.x += dirX * w;\n" +
                "    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);\n" +
                "    lenX *= lenX;\n" +
                "    len += lenX * w;\n" +
                "    float ec = lE - lC;\n" +
                "    float ca = lC - lA;\n" +
                "    float lenY = max(abs(ec), abs(ca));\n" +
                "    lenY = 1.0 / max(lenY, 1e-6);\n" +
                "    float dirY = lE - lA;\n" +
                "    dir.y += dirY * w;\n" +
                "    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);\n" +
                "    lenY *= lenY;\n" +
                "    len += lenY * w;\n" +
                "}\n" +
                "void FsrEasuF(out vec3 pix, vec2 ip, vec4 con0, vec4 con1, vec4 con2, vec4 con3) {\n" +
                "    vec2 pp = ip * con0.xy + con0.zw;\n" +
                "    vec2 fp = floor(pp);\n" +
                "    pp -= fp;\n" +
                "    vec2 p0 = fp * con1.xy + con1.zw;\n" +
                "    vec2 p1 = p0 + con2.xy;\n" +
                "    vec2 p2 = p0 + con2.zw;\n" +
                "    vec2 p3 = p0 + con3.xy;\n" +
                "    vec4 off = vec4(-0.5, 0.5, -0.5, 0.5) * con1.xxyy;\n" +
                "    vec3 bC = FsrEasuCF(p0 + off.xw); float bL = bC.b * 0.5 + (bC.r * 0.5 + bC.g);\n" +
                "    vec3 cC = FsrEasuCF(p0 + off.yw); float cL = cC.b * 0.5 + (cC.r * 0.5 + cC.g);\n" +
                "    vec3 iC = FsrEasuCF(p1 + off.xw); float iL = iC.b * 0.5 + (iC.r * 0.5 + iC.g);\n" +
                "    vec3 jC = FsrEasuCF(p1 + off.yw); float jL = jC.b * 0.5 + (jC.r * 0.5 + jC.g);\n" +
                "    vec3 fC = FsrEasuCF(p1 + off.yz); float fL = fC.b * 0.5 + (fC.r * 0.5 + fC.g);\n" +
                "    vec3 eC = FsrEasuCF(p1 + off.xz); float eL = eC.b * 0.5 + (eC.r * 0.5 + eC.g);\n" +
                "    vec3 kC = FsrEasuCF(p2 + off.xw); float kL = kC.b * 0.5 + (kC.r * 0.5 + kC.g);\n" +
                "    vec3 lC = FsrEasuCF(p2 + off.yw); float lL = lC.b * 0.5 + (lC.r * 0.5 + lC.g);\n" +
                "    vec3 hC = FsrEasuCF(p2 + off.yz); float hL = hC.b * 0.5 + (hC.r * 0.5 + hC.g);\n" +
                "    vec3 gC = FsrEasuCF(p2 + off.xz); float gL = gC.b * 0.5 + (gC.r * 0.5 + gC.g);\n" +
                "    vec3 oC = FsrEasuCF(p3 + off.yz); float oL = oC.b * 0.5 + (oC.r * 0.5 + oC.g);\n" +
                "    vec3 nC = FsrEasuCF(p3 + off.xz); float nL = nC.b * 0.5 + (nC.r * 0.5 + nC.g);\n" +
                "    vec2 dir = vec2(0.0);\n" +
                "    float len = 0.0;\n" +
                "    FsrEasuSetF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n" +
                "    FsrEasuSetF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n" +
                "    FsrEasuSetF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n" +
                "    FsrEasuSetF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n" +
                "    float dirR = dir.x * dir.x + dir.y * dir.y;\n" +
                "    bool zro = dirR < (1.0 / 32768.0);\n" +
                "    dirR = inversesqrt(max(dirR, 1e-6));\n" +
                "    if (zro) { dir = vec2(1.0, 0.0); dirR = 1.0; }\n" +
                "    dir *= dirR;\n" +
                "    len = 0.5 * len;\n" +
                "    len *= len;\n" +
                "    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(max(abs(dir.x), abs(dir.y)), 1e-6);\n" +
                "    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n" +
                "    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n" +
                "    float clp = 1.0 / max(lob, 1e-6);\n" +
                "    vec3 min4 = min(min(fC, gC), min(jC, kC));\n" +
                "    vec3 max4 = max(max(fC, gC), max(jC, kC));\n" +
                "    vec3 aC = vec3(0.0);\n" +
                "    float aW = 0.0;\n" +
                "    FsrEasuTapF(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, bC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, cC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, iC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, jC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, fC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, eC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, kC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, lC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, hC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, gC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, oC);\n" +
                "    FsrEasuTapF(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, nC);\n" +
                "    pix = min(max4, max(min4, aC / max(aW, 1e-6)));\n" +
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
                "        vec3 color;\n" +
                "        vec4 con0, con1, con2, con3;\n" +
                "        FsrEasuCon(con0, con1, con2, con3, inputResolution, inputResolution, scaledOutput);\n" +
                "        FsrEasuF(color, coord, con0, con1, con2, con3);\n" +
                "        gl_FragColor = vec4(color, 1.0);\n" +
                "    } else {\n" +
                "        vec3 color;\n" +
                "        vec4 con0, con1, con2, con3;\n" +
                "        FsrEasuCon(con0, con1, con2, con3, inputResolution, inputResolution, outputResolution);\n" +
                "        FsrEasuF(color, gl_FragCoord.xy, con0, con1, con2, con3);\n" +
                "        gl_FragColor = vec4(color, texture2D(screenTexture, vUV).a);\n" +
                "    }\n" +
                "}";
        }
    }
}
