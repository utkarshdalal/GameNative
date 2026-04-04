package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class FSR1EasuEffect extends Effect {
    @Override
    protected ShaderMaterial createMaterial() {
        return new FSR1EasuMaterial();
    }

    private static class FSR1EasuMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 resolution;\n" +
                "varying vec2 vUV;\n" +
                "vec3 FsrEasuCF(vec2 p) {\n" +
                "    return texture2D(screenTexture, p).rgb;\n" +
                "}\n" +
                "void FsrEasuCon(out vec4 con0, out vec4 con1, out vec4 con2, out vec4 con3, vec2 inputViewportInPixels, vec2 inputSizeInPixels, vec2 outputSizeInPixels) {\n" +
                "    con0 = vec4(\n" +
                "        inputViewportInPixels.x / outputSizeInPixels.x,\n" +
                "        inputViewportInPixels.y / outputSizeInPixels.y,\n" +
                "        0.5 * inputViewportInPixels.x / outputSizeInPixels.x - 0.5,\n" +
                "        0.5 * inputViewportInPixels.y / outputSizeInPixels.y - 0.5\n" +
                "    );\n" +
                "    con1 = vec4(1.0, 1.0, 1.0, -1.0) / inputSizeInPixels.xyxy;\n" +
                "    con2 = vec4(-1.0, 2.0, 1.0, 2.0) / inputSizeInPixels.xyxy;\n" +
                "    con3 = vec4(0.0, 4.0, 0.0, 0.0) / inputSizeInPixels.xyxy;\n" +
                "}\n" +
                "void FsrEasuTapF(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {\n" +
                "    vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x)));\n" +
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
                "    float lenX = max(abs(lD - lC), abs(lC - lB));\n" +
                "    float dirX = lD - lB;\n" +
                "    dir.x += dirX * w;\n" +
                "    lenX = clamp(abs(dirX) / max(lenX, 1e-4), 0.0, 1.0);\n" +
                "    lenX *= lenX;\n" +
                "    len += lenX * w;\n" +
                "    float lenY = max(abs(lE - lC), abs(lC - lA));\n" +
                "    float dirY = lE - lA;\n" +
                "    dir.y += dirY * w;\n" +
                "    lenY = clamp(abs(dirY) / max(lenY, 1e-4), 0.0, 1.0);\n" +
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
                "    vec3 bC = FsrEasuCF(p0 + off.xw); float bL = bC.g + 0.5 * (bC.r + bC.b);\n" +
                "    vec3 cC = FsrEasuCF(p0 + off.yw); float cL = cC.g + 0.5 * (cC.r + cC.b);\n" +
                "    vec3 iC = FsrEasuCF(p1 + off.xw); float iL = iC.g + 0.5 * (iC.r + iC.b);\n" +
                "    vec3 jC = FsrEasuCF(p1 + off.yw); float jL = jC.g + 0.5 * (jC.r + jC.b);\n" +
                "    vec3 fC = FsrEasuCF(p1 + off.yz); float fL = fC.g + 0.5 * (fC.r + fC.b);\n" +
                "    vec3 eC = FsrEasuCF(p1 + off.xz); float eL = eC.g + 0.5 * (eC.r + eC.b);\n" +
                "    vec3 kC = FsrEasuCF(p2 + off.xw); float kL = kC.g + 0.5 * (kC.r + kC.b);\n" +
                "    vec3 lC = FsrEasuCF(p2 + off.yw); float lL = lC.g + 0.5 * (lC.r + lC.b);\n" +
                "    vec3 hC = FsrEasuCF(p2 + off.yz); float hL = hC.g + 0.5 * (hC.r + hC.b);\n" +
                "    vec3 gC = FsrEasuCF(p2 + off.xz); float gL = gC.g + 0.5 * (gC.r + gC.b);\n" +
                "    vec3 oC = FsrEasuCF(p3 + off.yz); float oL = oC.g + 0.5 * (oC.r + oC.b);\n" +
                "    vec3 nC = FsrEasuCF(p3 + off.xz); float nL = nC.g + 0.5 * (nC.r + nC.b);\n" +
                "    vec2 dir = vec2(0.0);\n" +
                "    float len = 0.0;\n" +
                "    FsrEasuSetF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n" +
                "    FsrEasuSetF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n" +
                "    FsrEasuSetF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n" +
                "    FsrEasuSetF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n" +
                "    vec2 dir2 = dir * dir;\n" +
                "    float dirR = dir2.x + dir2.y;\n" +
                "    bool zro = dirR < (1.0 / 32768.0);\n" +
                "    dirR = inversesqrt(max(dirR, 1e-6));\n" +
                "    dirR = zro ? 1.0 : dirR;\n" +
                "    dir = zro ? vec2(1.0, 0.0) : dir * dirR;\n" +
                "    len = len * 0.5;\n" +
                "    len *= len;\n" +
                "    float stretch = dot(dir, vec2(1.0, 1.0)) / max(max(abs(dir.x), abs(dir.y)), 1e-6);\n" +
                "    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n" +
                "    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n" +
                "    float clp = inversesqrt(max(lob, 1e-6));\n" +
                "    clp = clp * clp;\n" +
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
                "    pix = min(max4, max(min4, aC / max(aW, 1e-5)));\n" +
                "}\n" +
                "void main() {\n" +
                "    vec3 color;\n" +
                "    vec4 con0;\n" +
                "    vec4 con1;\n" +
                "    vec4 con2;\n" +
                "    vec4 con3;\n" +
                "    FsrEasuCon(con0, con1, con2, con3, resolution, resolution, resolution);\n" +
                "    FsrEasuF(color, gl_FragCoord.xy, con0, con1, con2, con3);\n" +
                "    gl_FragColor = vec4(color, texture2D(screenTexture, vUV).a);\n" +
                "}";
        }
    }
}
