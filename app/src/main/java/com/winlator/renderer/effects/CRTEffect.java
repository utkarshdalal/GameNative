package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class CRTEffect extends Effect {
    @Override
    protected ShaderMaterial createMaterial() {
        return new CRTEffectMaterial();
    }

    private static class CRTEffectMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "#define CA_AMOUNT 1.0025\n" +
                "#define SCANLINE_INTENSITY_X 0.125\n" +
                "#define SCANLINE_INTENSITY_Y 0.375\n" +
                "#define SCANLINE_SIZE 1024.0\n" +
                "uniform sampler2D screenTexture;\n" +
                "varying vec2 vUV;\n" +
                "void main() {\n" +
                "    vec4 finalColor = texture2D(screenTexture, vUV);\n" +
                "    finalColor.rgb = vec3(\n" +
                "        texture2D(screenTexture, (vUV - 0.5) * CA_AMOUNT + 0.5).r,\n" +
                "        finalColor.g,\n" +
                "        texture2D(screenTexture, (vUV - 0.5) / CA_AMOUNT + 0.5).b\n" +
                "    );\n" +
                "    float scanlineX = abs(sin(vUV.x * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_X);\n" +
                "    float scanlineY = abs(sin(vUV.y * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_Y);\n" +
                "    gl_FragColor = vec4(mix(finalColor.rgb, vec3(0.0), scanlineX + scanlineY), finalColor.a);\n" +
                "}";
        }
    }
}
