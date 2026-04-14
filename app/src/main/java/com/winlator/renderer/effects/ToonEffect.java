package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class ToonEffect extends Effect {
    @Override
    protected ShaderMaterial createMaterial() {
        return new ToonMaterial();
    }

    private static class ToonMaterial extends ScreenMaterial {
        public ToonMaterial() {
            setUniformNames("screenTexture", "resolution");
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 resolution;\n" +
                "void main() {\n" +
                "    vec2 uv = gl_FragCoord.xy / resolution;\n" +
                "    float edgeThreshold = 0.2;\n" +
                "    vec2 offset = vec2(1.0) / resolution;\n" +
                "    vec3 colorCenter = texture2D(screenTexture, uv).rgb;\n" +
                "    vec3 colorLeft = texture2D(screenTexture, uv - vec2(offset.x, 0.0)).rgb;\n" +
                "    vec3 colorRight = texture2D(screenTexture, uv + vec2(offset.x, 0.0)).rgb;\n" +
                "    vec3 colorUp = texture2D(screenTexture, uv - vec2(0.0, offset.y)).rgb;\n" +
                "    vec3 colorDown = texture2D(screenTexture, uv + vec2(0.0, offset.y)).rgb;\n" +
                "    float diffHorizontal = length(colorRight - colorLeft);\n" +
                "    float diffVertical = length(colorUp - colorDown);\n" +
                "    float edgeFactor = step(edgeThreshold, diffHorizontal + diffVertical);\n" +
                "    vec3 outlineColor = mix(colorCenter, vec3(0.0), edgeFactor);\n" +
                "    gl_FragColor = vec4(outlineColor, 1.0);\n" +
                "}";
        }
    }
}
