#include "ReShade.fxh"

// Compact FXAA: luma-detect an edge, pick its direction from the local gradient
// and average along it. One pass, four extra taps.
uniform float Threshold <
    ui_min = 0.0; ui_max = 0.5;
> = 0.1;

float Luma(float3 c)
{
    return dot(c, float3(0.299, 0.587, 0.114));
}

float3 PS_FXAA(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float2 px = ReShade::PixelSize;
    float3 m = tex2D(ReShade::BackBuffer, uv).rgb;
    float3 n = tex2D(ReShade::BackBuffer, uv + float2(0.0, -px.y)).rgb;
    float3 s = tex2D(ReShade::BackBuffer, uv + float2(0.0,  px.y)).rgb;
    float3 w = tex2D(ReShade::BackBuffer, uv + float2(-px.x, 0.0)).rgb;
    float3 e = tex2D(ReShade::BackBuffer, uv + float2( px.x, 0.0)).rgb;

    float lm = Luma(m);
    float lmin = min(lm, min(min(Luma(n), Luma(s)), min(Luma(w), Luma(e))));
    float lmax = max(lm, max(max(Luma(n), Luma(s)), max(Luma(w), Luma(e))));
    // Contrast below the threshold is not an edge; leave it untouched.
    if (lmax - lmin < max(0.0312 * lmax, Threshold * 0.25)) return m;

    // The axis whose neighbours differ least is the one the edge runs along.
    float vertical = abs(Luma(n) - Luma(s));
    float horizontal = abs(Luma(w) - Luma(e));
    float3 a = vertical < horizontal ? n : w;
    float3 b = vertical < horizontal ? s : e;
    float3 blend = (a + b + m * 2.0) * 0.25;
    float edge = saturate((lmax - lmin) / max(lmax, 1e-5));
    return lerp(m, blend, edge * 0.75);
}

technique FXAA
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_FXAA;
    }
}
