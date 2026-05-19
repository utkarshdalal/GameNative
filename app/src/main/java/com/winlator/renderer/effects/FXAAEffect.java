package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class FXAAEffect extends Effect {
    @Override
    protected ShaderMaterial createMaterial() {
        return new FXAAMaterial();
    }

    private static class FXAAMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "#define FXAA_MIN_REDUCE (1.0 / 128.0)\n" +
                "#define FXAA_MUL_REDUCE (1.0 / 8.0)\n" +
                "#define MAX_SPAN 8.0\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 resolution;\n" +
                "const vec3 luma = vec3(0.299, 0.587, 0.114);\n" +
                "void main() {\n" +
                "    vec2 invResolution = 1.0 / resolution;\n" +
                "    vec3 rgbNW = texture2D(screenTexture, (gl_FragCoord.xy + vec2(-1.0, -1.0)) * invResolution).rgb;\n" +
                "    vec3 rgbNE = texture2D(screenTexture, (gl_FragCoord.xy + vec2( 1.0, -1.0)) * invResolution).rgb;\n" +
                "    vec3 rgbSW = texture2D(screenTexture, (gl_FragCoord.xy + vec2(-1.0,  1.0)) * invResolution).rgb;\n" +
                "    vec3 rgbSE = texture2D(screenTexture, (gl_FragCoord.xy + vec2( 1.0,  1.0)) * invResolution).rgb;\n" +
                "    vec3 rgbM  = texture2D(screenTexture,  gl_FragCoord.xy * invResolution).rgb;\n" +
                "    float lumaNW = dot(rgbNW, luma);\n" +
                "    float lumaNE = dot(rgbNE, luma);\n" +
                "    float lumaSW = dot(rgbSW, luma);\n" +
                "    float lumaSE = dot(rgbSE, luma);\n" +
                "    float lumaM  = dot(rgbM,  luma);\n" +
                "    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));\n" +
                "    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));\n" +
                "    vec2 dir;\n" +
                "    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));\n" +
                "    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));\n" +
                "    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.25 * FXAA_MUL_REDUCE, FXAA_MIN_REDUCE);\n" +
                "    float minDirFactor = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);\n" +
                "    dir = clamp(dir * minDirFactor, vec2(-MAX_SPAN), vec2(MAX_SPAN)) * invResolution;\n" +
                "    vec4 rgbA = 0.5 * (\n" +
                "        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir * (1.0 / 3.0 - 0.5)) +\n" +
                "        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir * (2.0 / 3.0 - 0.5)));\n" +
                "    vec4 rgbB = rgbA * 0.5 + 0.25 * (\n" +
                "        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir * -0.5) +\n" +
                "        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir *  0.5));\n" +
                "    float lumaB = dot(rgbB, vec4(luma, 0.0));\n" +
                "    gl_FragColor = lumaB < lumaMin || lumaB > lumaMax ? rgbA : rgbB;\n" +
                "}";
        }
    }
}
