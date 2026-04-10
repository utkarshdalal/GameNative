package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class FSREASUEffect extends Effect {
    public static final float QUALITY_ULTRA = 0.77f;
    public static final float QUALITY_QUALITY = 0.67f;
    public static final float QUALITY_BALANCED = 0.59f;
    public static final float QUALITY_PERFORMANCE = 0.5f;

    private float renderScale = QUALITY_ULTRA;

    public float getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(float renderScale) {
        this.renderScale = Math.max(0.25f, Math.min(renderScale, 1.0f));
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSREASUMaterial();
    }

    private static class FSREASUMaterial extends ScreenMaterial {
        public FSREASUMaterial() {
            setUniformNames("screenTexture", "resolution", "inputResolution");
        }

        @Override
        protected String getFragmentShader() {
            return "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 resolution;\n" +
                "uniform vec2 inputResolution;\n" +
                "\n" +
                "vec3 FsrEasuCF(vec2 p) {\n" +
                "    return texture2D(screenTexture, p).rgb;\n" +
                "}\n" +
                "\n" +
                "void FsrEasuSetF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {\n" +
                "    float lenX = max(abs(lD - lC), abs(lC - lB));\n" +
                "    float dirX = lD - lB;\n" +
                "    dir.x += dirX * w;\n" +
                "    lenX = clamp(abs(dirX)/max(lenX, 1e-5), 0.0, 1.0);\n" +
                "    lenX *= lenX;\n" +
                "    len += lenX * w;\n" +
                "    float lenY = max(abs(lE - lC), abs(lC - lA));\n" +
                "    float dirY = lE - lA;\n" +
                "    dir.y += dirY * w;\n" +
                "    lenY = clamp(abs(dirY)/max(lenY, 1e-5), 0.0, 1.0);\n" +
                "    lenY *= lenY;\n" +
                "    len += lenY * w;\n" +
                "}\n" +
                "\n" +
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
                "\n" +
                "// FidelityFX Super Resolution 1.0 EASU\n" +
                "void main() {\n" +
                "    vec2 inputSizeInPixels = inputResolution;\n" +
                "    vec2 outputSizeInPixels = resolution;\n" +
                "    vec2 fragCoord = gl_FragCoord.xy;\n" +
                "\n" +
                "    vec4 con0 = vec4(inputSizeInPixels.x/outputSizeInPixels.x, inputSizeInPixels.y/outputSizeInPixels.y, 0.5*inputSizeInPixels.x/outputSizeInPixels.x-0.5, 0.5*inputSizeInPixels.y/outputSizeInPixels.y-0.5);\n" +
                "    vec4 con1 = vec4(1.0, 1.0, 1.0, -1.0) / inputSizeInPixels.xyxy;\n" +
                "    vec4 con2 = vec4(-1.0, 2.0, 1.0, 2.0) / inputSizeInPixels.xyxy;\n" +
                "    vec4 con3 = vec4(0.0, 4.0, 0.0, 0.0) / inputSizeInPixels.xyxy;\n" +
                "\n" +
                "    vec2 pp = fragCoord * con0.xy + con0.zw;\n" +
                "    vec2 fp = floor(pp);\n" +
                "    pp -= fp;\n" +
                "\n" +
                "    vec2 p0 = fp * con1.xy + con1.zw;\n" +
                "    vec2 p1 = p0 + con2.xy;\n" +
                "    vec2 p2 = p0 + con2.zw;\n" +
                "    vec2 p3 = p0 + con3.xy;\n" +
                "    vec4 off = vec4(-0.5, 0.5, -0.5, 0.5) * con1.xxyy;\n" +
                "\n" +
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
                "\n" +
                "    vec2 dir = vec2(0.0);\n" +
                "    float len = 0.0;\n" +
                "    FsrEasuSetF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n" +
                "    FsrEasuSetF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n" +
                "    FsrEasuSetF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n" +
                "    FsrEasuSetF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n" +
                "\n" +
                "    vec2 dir2 = dir * dir;\n" +
                "    float dirR = dir2.x + dir2.y;\n" +
                "    bool zro = dirR < (1.0 / 32768.0);\n" +
                "    dirR = inversesqrt(max(dirR, 1e-6));\n" +
                "    dirR = zro ? 1.0 : dirR;\n" +
                "    dir.x = zro ? 1.0 : dir.x;\n" +
                "    dir.y = zro ? 0.0 : dir.y;\n" +
                "    dir *= vec2(dirR);\n" +
                "    len = len * 0.5;\n" +
                "    len *= len;\n" +
                "    float stretch = dot(dir, dir) / max(max(abs(dir.x), abs(dir.y)), 1e-6);\n" +
                "    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n" +
                "    float lob = 0.5 - 0.29 * len;\n" +
                "    float clp = 1.0 / lob;\n" +
                "\n" +
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
                "\n" +
                "    if(aW < 1e-6) aW = 1.0;\n" +
                "    vec3 pix = min(max4, max(min4, aC / aW));\n" +
                "\n" +
                "    gl_FragColor = vec4(pix, 1.0);\n" +
                "}\n";
        }
    }
}
