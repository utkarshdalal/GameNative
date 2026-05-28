#version 450
layout(binding = 0) uniform sampler2D texSampler;
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
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

vec3 applyFSR(vec2 uv, float sharp) {
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec3 c = texture(texSampler, uv).rgb;
    vec3 t = texture(texSampler, uv + vec2( 0.0,    -texel.y)).rgb;
    vec3 b = texture(texSampler, uv + vec2( 0.0,     texel.y)).rgb;
    vec3 l = texture(texSampler, uv + vec2(-texel.x,  0.0   )).rgb;
    vec3 r = texture(texSampler, uv + vec2( texel.x,  0.0   )).rgb;

    vec3 mnRGB = min(c, min(min(t, b), min(l, r)));
    vec3 mxRGB = max(c, max(max(t, b), max(l, r)));

    vec3 num   = min(mnRGB, 1.0 - mxRGB);
    vec3 denom = mxRGB;
    vec3 wRGB  = sqrt(clamp(num / max(denom, 1e-4), 0.0, 1.0));
    float w    = (wRGB.r + wRGB.g + wRGB.b) * 0.333;

    float lobe = w * mix(-0.125, -0.200, sharp);
    return clamp((lobe * (t + b + l + r) + c) / (1.0 + 4.0 * lobe), 0.0, 1.0);
}

vec3 applyDLS(vec2 uv, float sharp) {
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    float SAT   = 1.0 + sharp * 0.20;
    float CON   = 1.0 + sharp * 0.12;
    float SHARP = sharp * 1.2;

    vec3 orig = texture(texSampler, uv).rgb;
    vec3 c    = clamp((orig - 0.5) * CON + 0.5, 0.0, 1.0);
    float gray = dot(c, vec3(0.299,0.587,0.114));
    c = mix(vec3(gray), c, SAT);

    vec3 blur = (texture(texSampler, uv + vec2( 0.0,    -texel.y)).rgb
               + texture(texSampler, uv + vec2( 0.0,     texel.y)).rgb
               + texture(texSampler, uv + vec2(-texel.x,  0.0   )).rgb
               + texture(texSampler, uv + vec2( texel.x,  0.0   )).rgb) * 0.25;
    return clamp(c + (orig - blur) * SHARP, 0.0, 1.0);
}

vec3 applyCRT(vec2 uv) {
    float CA = 1.0025;
    vec4 fc = texture(texSampler, uv);
    fc.r = texture(texSampler, (uv-0.5)*CA+0.5).r;
    fc.b = texture(texSampler, (uv-0.5)/CA+0.5).b;
    float sx = abs(sin(uv.x*1024.0)*0.5*0.125);
    float sy = abs(sin(uv.y*1024.0)*0.5*0.375);
    return mix(fc.rgb, vec3(0.0), sx+sy);
}

vec3 applyHDR(vec2 uv) {
    vec2 px = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec3 c = texture(texSampler, uv).rgb;
    float r1=0.793, r2=0.870;
    vec3 b1=vec3(0.0), b2=vec3(0.0);
    vec2 offs[8] = vec2[](vec2(1.5,-1.5),vec2(-1.5,-1.5),vec2(1.5,1.5),vec2(-1.5,1.5),
                          vec2(0.0,-2.5),vec2(0.0,2.5),vec2(-2.5,0.0),vec2(2.5,0.0));
    for(int i=0;i<8;i++){
        b1+=texture(texSampler,uv+offs[i]*r1*px).rgb;
        b2+=texture(texSampler,uv+offs[i]*r2*px).rgb;
    }
    b1*=0.005; b2*=0.010;
    float dist=r2-r1;
    vec3 HDR=(c+(b2-b1))*dist;
    return clamp(pow(abs(HDR+c),vec3(1.30))+HDR, 0.0, 1.0);
}

vec3 applyNatural(vec2 uv) {
    mat3 toYIQ = mat3(0.299, 0.596, 0.212,
                      0.587,-0.275,-0.523,
                      0.114,-0.321, 0.311);
    mat3 toRGB = mat3(1.0, 1.0, 1.0,
                      0.95568806,-0.27158179,-1.10817732,
                      0.61985809,-0.64687381, 1.70506455);
    vec3 c = texture(texSampler, uv).rgb;
    vec3 t = c * toYIQ;
    t = vec3(pow(t.r,1.12), t.g*1.2, t.b*1.2);
    return clamp(t * toRGB, 0.0, 1.0);
}

void main() {
    vec2 uv = fragTexCoord;
    vec4 src = texture(texSampler, uv);
    vec3 rgb;

    if (pc.useTexAlpha != 0 || pc.effectId == 0) rgb = src.rgb;
    else if (pc.effectId == 1) rgb = applyFSR    (uv, pc.sharpness);
    else if (pc.effectId == 2) rgb = applyDLS    (uv, pc.sharpness);
    else if (pc.effectId == 3) rgb = applyCRT    (uv);
    else if (pc.effectId == 4) rgb = applyHDR    (uv);
    else if (pc.effectId == 5) rgb = applyNatural(uv);
    else                       rgb = src.rgb;

    outColor = vec4(rgb, pc.useTexAlpha != 0 ? src.a : 1.0);
}
