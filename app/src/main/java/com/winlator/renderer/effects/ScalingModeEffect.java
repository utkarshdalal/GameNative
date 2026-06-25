package com.winlator.renderer.effects;

import android.opengl.GLES20;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class ScalingModeEffect extends Effect implements RenderScaleEffect, SourceTextureFilterEffect {
    public enum Mode {
        NEAREST,
        LINEAR,
        FILL,
        STRETCH,
    }

    private Mode mode = Mode.LINEAR;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode != null ? mode : Mode.LINEAR;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new ScalingModeMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        float modeValue = 0.0f;
        if (mode == Mode.FILL) {
            modeValue = 1.0f;
        }
        else if (mode == Mode.STRETCH) {
            modeValue = 2.0f;
        }
        material.setUniformFloat("scaleMode", modeValue);
    }

    @Override
    public int getRenderWidth(GLRenderer renderer, int outputWidth) {
        return Math.max(1, renderer.getXServerWidth());
    }

    @Override
    public int getRenderHeight(GLRenderer renderer, int outputHeight) {
        return Math.max(1, renderer.getXServerHeight());
    }

    @Override
    public int getSourceMinFilter() {
        return mode == Mode.NEAREST ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
    }

    @Override
    public int getSourceMagFilter() {
        return mode == Mode.NEAREST ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
    }

    private static class ScalingModeMaterial extends ScreenMaterial {
        public ScalingModeMaterial() {
            setUniformNames("screenTexture", "inputResolution", "outputResolution", "scaleMode");
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision highp float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform vec2 inputResolution;\n" +
                "uniform vec2 outputResolution;\n" +
                "uniform float scaleMode;\n" +
                "varying vec2 vUV;\n" +
                "void main() {\n" +
                "    vec2 uv;\n" +
                "    if (scaleMode > 1.5) {\n" +
                "        uv = vUV;\n" +
                "    } else {\n" +
                "        float scale = scaleMode > 0.5\n" +
                "            ? max(outputResolution.x / inputResolution.x, outputResolution.y / inputResolution.y)\n" +
                "            : min(outputResolution.x / inputResolution.x, outputResolution.y / inputResolution.y);\n" +
                "        vec2 scaledSize = inputResolution * scale;\n" +
                "        vec2 offset = 0.5 * (outputResolution - scaledSize);\n" +
                "        uv = (gl_FragCoord.xy - offset) / scaledSize;\n" +
                "        if (scaleMode < 0.5) {\n" +
                "            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n" +
                "                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                "                return;\n" +
                "            }\n" +
                "        } else {\n" +
                "            uv = clamp(uv, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "    gl_FragColor = texture2D(screenTexture, uv);\n" +
                "}";
        }
    }
}
