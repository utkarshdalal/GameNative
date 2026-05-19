package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class NTSCCombinedEffect extends Effect {
    private int frameCount = 0;

    @Override
    protected ShaderMaterial createMaterial() {
        return new NTSCCombinedEffectMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        frameCount++;
        material.setUniformInt("FrameCount", frameCount);
        material.setUniformVec2("TextureSize", renderer.getSurfaceWidth(), renderer.getSurfaceHeight());
    }

    private static class NTSCCombinedEffectMaterial extends ScreenMaterial {
        public NTSCCombinedEffectMaterial() {
            setUniformNames("screenTexture", "resolution", "FrameCount", "TextureSize");
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "#define PI 3.14159265\n" +
                "#define SCANLINE_INTENSITY 0.35\n" +
                "#define CHROMA_OFFSET 0.005\n" +
                "#define BLUR_RADIUS 0.002\n" +
                "#define WARP_AMOUNT 0.01\n" +
                "#define SCANLINE_DARKEN 0.5\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform int FrameCount;\n" +
                "uniform vec2 TextureSize;\n" +
                "uniform vec2 resolution;\n" +
                "varying vec2 vUV;\n" +
                "const mat3 yiq_mat = mat3(\n" +
                "   0.299, 0.587, 0.114,\n" +
                "   0.596, -0.275, -0.321,\n" +
                "   0.212, -0.523, 0.311\n" +
                ");\n" +
                "const mat3 yiq2rgb_mat = mat3(\n" +
                "   1.0, 0.956, 0.621,\n" +
                "   1.0, -0.272, -0.647,\n" +
                "   1.0, -1.106, 1.705\n" +
                ");\n" +
                "vec3 applyNTSC(vec2 uv) {\n" +
                "   vec3 col = texture2D(screenTexture, uv).rgb;\n" +
                "   vec3 yiq = col * yiq_mat;\n" +
                "   float chromaPhase = PI * (mod(uv.y * TextureSize.y, 2.0) + float(FrameCount));\n" +
                "   yiq.y *= cos(chromaPhase * 0.5);\n" +
                "   yiq.z *= sin(chromaPhase * 0.5);\n" +
                "   vec3 rgb = yiq * yiq2rgb_mat;\n" +
                "   vec3 finalColor;\n" +
                "   finalColor.r = texture2D(screenTexture, uv + vec2(CHROMA_OFFSET, 0.0)).r;\n" +
                "   finalColor.g = texture2D(screenTexture, uv + vec2(0.0, BLUR_RADIUS)).g;\n" +
                "   finalColor.b = texture2D(screenTexture, uv - vec2(CHROMA_OFFSET, 0.0)).b;\n" +
                "   return mix(rgb, finalColor, 0.6);\n" +
                "}\n" +
                "vec3 applyScanlines(vec2 uv) {\n" +
                "   vec3 col = texture2D(screenTexture, uv).rgb;\n" +
                "   float scanline = abs(sin(uv.y * resolution.y * 2.0)) * SCANLINE_INTENSITY;\n" +
                "   col *= 1.0 - (scanline * SCANLINE_DARKEN);\n" +
                "   return col;\n" +
                "}\n" +
                "vec2 applyWarp(vec2 uv) {\n" +
                "   uv = uv * 2.0 - 1.0;\n" +
                "   float r = sqrt(uv.x * uv.x + uv.y * uv.y);\n" +
                "   uv += uv * (r * r) * WARP_AMOUNT;\n" +
                "   return uv * 0.5 + 0.5;\n" +
                "}\n" +
                "void main() {\n" +
                "   vec2 warpedUV = applyWarp(vUV);\n" +
                "   vec3 ntscColor = applyNTSC(warpedUV);\n" +
                "   vec3 scanlineColor = applyScanlines(warpedUV);\n" +
                "   vec3 finalColor = mix(ntscColor, scanlineColor, 0.7);\n" +
                "   gl_FragColor = vec4(finalColor, 1.0);\n" +
                "}";
        }
    }
}
