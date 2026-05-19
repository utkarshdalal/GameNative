package com.winlator.renderer.material;

public class ScreenMaterial extends ShaderMaterial {
    public ScreenMaterial() {
        setUniformNames("screenTexture", "resolution");
    }

    @Override
    protected String getVertexShader() {
        return
            "attribute vec2 position;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "vUV = position;\n" +
            "gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);\n" +
            "}";
    }
}
