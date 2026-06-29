package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class BarrelDistortionEffect extends Effect {
    private float strength = 1.0f;
    private float height = 90.0f;
    private float cylindricalRatio = 1.0f;

    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = strength;
    }

    public float getHeight() {
        return height;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public float getCylindricalRatio() {
        return cylindricalRatio;
    }

    public void setCylindricalRatio(float cylindricalRatio) {
        this.cylindricalRatio = cylindricalRatio;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new BarrelDistortionEffectMaterial();
    }

    @Override
    protected void onUse(ShaderMaterial material, GLRenderer renderer) {
        material.setUniformFloat("strength", Math.max(-1.0f, Math.min(strength, 1.0f)));
        material.setUniformFloat("height", Math.max(-1.0f, Math.min(height, 1.0f)));
        material.setUniformFloat("cylindricalRatio", Math.max(0.5f, Math.min(cylindricalRatio, 1.5f)));
    }

    private static class BarrelDistortionEffectMaterial extends ScreenMaterial {
        public BarrelDistortionEffectMaterial() {
            setUniformNames("screenTexture", "resolution", "strength", "height", "cylindricalRatio");
        }

        @Override
        protected String getVertexShader() {
            return
                "uniform float strength;\n" +           // s: 0 = perspective, 1 = stereographic
                "uniform float height;\n" +             // h: tan(verticalFOVInRadians / 2)
                "uniform vec2 resolution;\n" +        // a: screenWidth / screenHeight
                "uniform float cylindricalRatio;\n" +   // c: cylindrical distortion ratio. 1 = spherical
                "varying vec3 vUV;\n" +                 // output to interpolate over screen
                "varying vec2 vUVDot;\n" +              // output to interpolate over screen
                "void main() {\n" +
                "    gl_Position = projectionMatrix * (modelViewMatrix * vec4(position, 1.0));\n" +

                "    float scaledHeight = strength * height;\n" +
                "    float aspectRatio = resolution.x / resolution.y;\n" +
                "    float cylAspectRatio = aspectRatio * cylindricalRatio;\n" +
                "    float aspectDiagSq = aspectRatio * aspectRatio + 1.0;\n" +
                "    float diagSq = scaledHeight * scaledHeight * aspectDiagSq;\n" +
                "    vec2 signedUV = (2.0 * uv + vec2(-1.0, -1.0));\n" +

                "    float z = 0.5 * sqrt(diagSq + 1.0) + 0.5;\n" +
                "    float ny = (z - 1.0) / (cylAspectRatio * cylAspectRatio + 1.0);\n" +

                "    vUVDot = sqrt(ny) * vec2(cylAspectRatio, 1.0) * signedUV;\n" +
                "    vUV = vec3(0.5, 0.5, 1.0) * z + vec3(-0.5, -0.5, 0.0);\n" +
                "    vUV.xy += uv;\n" +
                "}";
        }

        @Override
        protected String getFragmentShader() {
            return
                "uniform sampler2D screenTexture;\n" +     // sampler of rendered scenes render target
                "varying vec3 vUV;\n" +               // interpolated vertex output data
                "varying vec2 vUVDot;\n" +            // interpolated vertex output data

                "void main() {\n" +
                "    vec3 uv = dot(vUVDot, vUVDot) * vec3(-0.5, -0.5, -1.0) + vUV;\n" +
                "    gl_FragColor = texture2DProj(screenTexture, uv);\n" +
                "}";
        }
    }
}
