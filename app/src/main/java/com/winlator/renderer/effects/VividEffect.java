package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/**
 * A punchy vivid post-process inspired by the shader used in Ludashi/Winlator forks.
 * This is a bloom + contrast style fullscreen effect for a more vivid image.
 */
public class VividEffect extends Effect {
    @Override
    protected ShaderMaterial createMaterial() {
        return new VividMaterial();
    }

    private static class VividMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 resolution;\n" +
                "const float VIVID_POWER = 1.30;\n" +
                "const float RADIUS_1 = 0.793;\n" +
                "const float RADIUS_2 = 0.870;\n" +
                "void main() {\n" +
                "    vec2 texcoord = gl_FragCoord.xy / resolution;\n" +
                "    vec2 px = 1.0 / resolution;\n" +
                "    vec3 color = texture2D(screenTexture, texcoord).rgb;\n" +
                "\n" +
                "    vec3 bloom1 = texture2D(screenTexture, texcoord + vec2( 1.5, -1.5) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 += texture2D(screenTexture, texcoord + vec2(-1.5, -1.5) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 += texture2D(screenTexture, texcoord + vec2( 1.5,  1.5) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 += texture2D(screenTexture, texcoord + vec2(-1.5,  1.5) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 += texture2D(screenTexture, texcoord + vec2( 0.0, -2.5) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 += texture2D(screenTexture, texcoord + vec2( 0.0,  2.5) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 += texture2D(screenTexture, texcoord + vec2(-2.5,  0.0) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 += texture2D(screenTexture, texcoord + vec2( 2.5,  0.0) * RADIUS_1 * px).rgb;\n" +
                "    bloom1 *= 0.005;\n" +
                "\n" +
                "    vec3 bloom2 = texture2D(screenTexture, texcoord + vec2( 1.5, -1.5) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 += texture2D(screenTexture, texcoord + vec2(-1.5, -1.5) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 += texture2D(screenTexture, texcoord + vec2( 1.5,  1.5) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 += texture2D(screenTexture, texcoord + vec2(-1.5,  1.5) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 += texture2D(screenTexture, texcoord + vec2( 0.0, -2.5) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 += texture2D(screenTexture, texcoord + vec2( 0.0,  2.5) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 += texture2D(screenTexture, texcoord + vec2(-2.5,  0.0) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 += texture2D(screenTexture, texcoord + vec2( 2.5,  0.0) * RADIUS_2 * px).rgb;\n" +
                "    bloom2 *= 0.010;\n" +
                "\n" +
                "    float dist = RADIUS_2 - RADIUS_1;\n" +
                "    vec3 vivid = (color + (bloom2 - bloom1)) * dist;\n" +
                "    vec3 blend = vivid + color;\n" +
                "    color = pow(abs(blend), vec3(abs(VIVID_POWER))) + vivid;\n" +
                "\n" +
                "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
                "}";
        }
    }
}
