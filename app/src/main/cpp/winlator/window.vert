#version 450

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
} pc;

layout(location = 0) out vec2 fragTexCoord;

void main() {
    int xi = (gl_VertexIndex >> 1) & 1;
    int yi = gl_VertexIndex & 1;
    float x = xi == 1 ? pc.ndcX1 : pc.ndcX0;
    float y = yi == 1 ? pc.ndcY1 : pc.ndcY0;
    gl_Position = vec4(x, y, 0.0, 1.0);
    fragTexCoord = vec2(float(xi), float(yi));
}
