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

    private static class SGSR1Material extends ScreenMaterial {
        public SGSR1Material() {
            setUniformNames("screenTexture", "inputResolution", "outputResolution", "preserveAspect", "sharpness");
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 inputResolution;\n" +
                "uniform vec2 outputResolution;\n" +
                "uniform float preserveAspect;\n" +
                "uniform float sharpness;\n" +
                "varying vec2 vUV;\n" +
                "const float EDGE_THRESHOLD = 8.0 / 255.0;\n" +
                "float fastLanczos2(float x) {\n" +
                "    float wA = x - 4.0;\n" +
                "    float wB = x * wA - wA;\n" +
                "    wA *= wA;\n" +
                "    return wB * wA;\n" +
                "}\n" +
                "vec2 weightY(float dx, float dy, float c, float std) {\n" +
                "    float x = (dx * dx + dy * dy) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);\n" +
                "    float w = fastLanczos2(x);\n" +
                "    return vec2(w, w * c);\n" +
                "}\n" +
                "vec2 clampPixel(vec2 pixelCoord) {\n" +
                "    return clamp(pixelCoord, vec2(0.0), inputResolution - vec2(1.0));\n" +
                "}\n" +
                "float sampleLuma(vec2 pixelCoord) {\n" +
                "    vec2 uv = (clampPixel(pixelCoord) + vec2(0.5)) / inputResolution;\n" +
                "    return texture2D(screenTexture, uv).g;\n" +
                "}\n" +
                "vec4 sampleColor(vec2 uv) {\n" +
                "    return texture2D(screenTexture, clamp(uv, vec2(0.0), vec2(1.0)));\n" +
                "}\n" +
                "void main() {\n" +
                "    vec2 renderSize = outputResolution;\n" +
                "    vec2 renderOffset = vec2(0.0);\n" +
                "    vec2 fragCoord = gl_FragCoord.xy;\n" +
                "    if (preserveAspect > 0.5) {\n" +
                "        float inputAspect = inputResolution.x / inputResolution.y;\n" +
                "        float outputAspect = outputResolution.x / outputResolution.y;\n" +
                "        if (outputAspect > inputAspect) {\n" +
                "            renderSize.x = outputResolution.y * inputAspect;\n" +
                "        } else {\n" +
                "            renderSize.y = outputResolution.x / inputAspect;\n" +
                "        }\n" +
                "        renderOffset = 0.5 * (outputResolution - renderSize);\n" +
                "        fragCoord -= renderOffset;\n" +
                "        if (fragCoord.x < 0.0 || fragCoord.x > renderSize.x || fragCoord.y < 0.0 || fragCoord.y > renderSize.y) {\n" +
                "            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                "            return;\n" +
                "        }\n" +
                "    }\n" +
                "    vec2 uv = fragCoord / renderSize;\n" +
                "    vec4 color = sampleColor(uv);\n" +
                "    vec2 imgCoord = uv * inputResolution + vec2(-0.5, 0.5);\n" +
                "    vec2 imgCoordPixel = floor(imgCoord);\n" +
                "    vec2 pl = imgCoord - imgCoordPixel;\n" +
                "    float leftX = sampleLuma(imgCoordPixel + vec2(-1.0, 1.0));\n" +
                "    float leftY = sampleLuma(imgCoordPixel + vec2(0.0, 1.0));\n" +
                "    float leftZ = sampleLuma(imgCoordPixel + vec2(0.0, 0.0));\n" +
                "    float leftW = sampleLuma(imgCoordPixel + vec2(-1.0, 0.0));\n" +
                "    float centerY = color.g;\n" +
                "    float edgeVote = abs(leftZ - leftY) + abs(centerY - leftY) + abs(centerY - leftZ);\n" +
                "    if (edgeVote > EDGE_THRESHOLD) {\n" +
                "        float rightX = sampleLuma(imgCoordPixel + vec2(1.0, 1.0));\n" +
                "        float rightY = sampleLuma(imgCoordPixel + vec2(2.0, 1.0));\n" +
                "        float rightZ = sampleLuma(imgCoordPixel + vec2(2.0, 0.0));\n" +
                "        float rightW = sampleLuma(imgCoordPixel + vec2(1.0, 0.0));\n" +
                "        float upDownX = sampleLuma(imgCoordPixel + vec2(0.0, -1.0));\n" +
                "        float upDownY = sampleLuma(imgCoordPixel + vec2(1.0, -1.0));\n" +
                "        float upDownZ = sampleLuma(imgCoordPixel + vec2(1.0, 2.0));\n" +
                "        float upDownW = sampleLuma(imgCoordPixel + vec2(0.0, 2.0));\n" +
                "        float mean = (leftY + leftZ + rightX + rightW) * 0.25;\n" +
                "        leftX -= mean;\n" +
                "        leftY -= mean;\n" +
                "        leftZ -= mean;\n" +
                "        leftW -= mean;\n" +
                "        rightX -= mean;\n" +
                "        rightY -= mean;\n" +
                "        rightZ -= mean;\n" +
                "        rightW -= mean;\n" +
                "        upDownX -= mean;\n" +
                "        upDownY -= mean;\n" +
                "        upDownZ -= mean;\n" +
                "        upDownW -= mean;\n" +
                "        float centerDelta = centerY - mean;\n" +
                "        float sum = abs(leftX) + abs(leftY) + abs(leftZ) + abs(leftW) +\n" +
                "            abs(rightX) + abs(rightY) + abs(rightZ) + abs(rightW) +\n" +
                "            abs(upDownX) + abs(upDownY) + abs(upDownZ) + abs(upDownW);\n" +
                "        float std = 2.181818 / max(sum, 1.0e-6);\n" +
                "        vec2 aWY = weightY(pl.x, pl.y + 1.0, upDownX, std);\n" +
                "        aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDownY, std);\n" +
                "        aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDownZ, std);\n" +
                "        aWY += weightY(pl.x, pl.y - 2.0, upDownW, std);\n" +
                "        aWY += weightY(pl.x + 1.0, pl.y - 1.0, leftX, std);\n" +
                "        aWY += weightY(pl.x, pl.y - 1.0, leftY, std);\n" +
                "        aWY += weightY(pl.x, pl.y, leftZ, std);\n" +
                "        aWY += weightY(pl.x + 1.0, pl.y, leftW, std);\n" +
                "        aWY += weightY(pl.x - 1.0, pl.y - 1.0, rightX, std);\n" +
                "        aWY += weightY(pl.x - 2.0, pl.y - 1.0, rightY, std);\n" +
                "        aWY += weightY(pl.x - 2.0, pl.y, rightZ, std);\n" +
                "        aWY += weightY(pl.x - 1.0, pl.y, rightW, std);\n" +
                "        float finalY = aWY.y / max(aWY.x, 1.0e-6);\n" +
                "        float maxY = max(max(leftY, leftZ), max(rightX, rightW));\n" +
                "        float minY = min(min(leftY, leftZ), min(rightX, rightW));\n" +
                "        float edgeSharpness = mix(1.0, 2.0, clamp(sharpness, 0.0, 1.0));\n" +
                "        finalY = clamp(edgeSharpness * finalY, minY, maxY);\n" +
                "        float deltaY = clamp(finalY - centerDelta, -23.0 / 255.0, 23.0 / 255.0);\n" +
                "        color.rgb = clamp(color.rgb + vec3(deltaY), 0.0, 1.0);\n" +
                "    }\n" +
                "    gl_FragColor = vec4(color.rgb, color.a);\n" +
                "}";
        }
    }
}
