#version 450

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    int   useTexAlpha;
    int   effectId;
    float sharpness;
    float resW;
    float resH;
    int   effectMask;
    float brightness;
    float contrast;
    float gamma;
    float outW;
    float outH;
    float barrelStrength;
    float barrelHeight;
    float barrelCylindricalRatio;
} pc;

layout(location = 0) out vec2 fragTexCoord;
layout(location = 1) out vec3 vUVBarrel;
layout(location = 2) out vec2 vUVDotBarrel;

void main() {
    int xi = (gl_VertexIndex >> 1) & 1;
    int yi = gl_VertexIndex & 1;
    float x = xi == 1 ? pc.ndcX1 : pc.ndcX0;
    float y = yi == 1 ? pc.ndcY1 : pc.ndcY0;
    gl_Position = vec4(x, y, 0.0, 1.0);
    fragTexCoord = vec2(float(xi), float(yi));

    float scaledHeight = pc.barrelStrength * pc.barrelHeight;
    float aspectRatio = max(pc.resW / max(pc.resH, 1e-6), 1e-6);
    float cylAspectRatio = aspectRatio * pc.barrelCylindricalRatio;
    float aspectDiagSq = aspectRatio * aspectRatio + 1.0;
    float diagSq = scaledHeight * scaledHeight * aspectDiagSq;
    vec2 signedUV = fragTexCoord * 2.0 - 1.0;

    float z = 0.5 * sqrt(diagSq + 1.0) + 0.5;
    float ny = (z - 1.0) / (cylAspectRatio * cylAspectRatio + 1.0);

    vUVDotBarrel = sqrt(max(ny, 0.0)) * vec2(cylAspectRatio, 1.0) * signedUV;
    vUVBarrel = vec3(0.5, 0.5, 1.0) * z + vec3(-0.5, -0.5, 0.0);
    vUVBarrel.xy += fragTexCoord;
}
