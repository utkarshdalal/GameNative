#include "ReShade.fxh"

// Contrast adaptive sharpening: the CAS kernel, weights scaled by how flat the
// 3x3 neighbourhood is so edges are not over-enhanced.
uniform float Sharpness <
    ui_min = 0.0; ui_max = 1.0;
> = 0.5;

float3 PS_CAS(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float2 px = ReShade::PixelSize;
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;
    float3 n = tex2D(ReShade::BackBuffer, uv + float2(0.0, -px.y)).rgb;
    float3 s = tex2D(ReShade::BackBuffer, uv + float2(0.0,  px.y)).rgb;
    float3 w = tex2D(ReShade::BackBuffer, uv + float2(-px.x, 0.0)).rgb;
    float3 e = tex2D(ReShade::BackBuffer, uv + float2( px.x, 0.0)).rgb;
    float3 nw = tex2D(ReShade::BackBuffer, uv + float2(-px.x, -px.y)).rgb;
    float3 ne = tex2D(ReShade::BackBuffer, uv + float2( px.x, -px.y)).rgb;
    float3 sw = tex2D(ReShade::BackBuffer, uv + float2(-px.x,  px.y)).rgb;
    float3 se = tex2D(ReShade::BackBuffer, uv + float2( px.x,  px.y)).rgb;

    float3 mn = min(min(min(n, s), min(w, e)), c);
    float3 mx = max(max(max(n, s), max(w, e)), c);
    // A pixel whose neighbourhood is already at 0 or 1 gains nothing from sharpening.
    float3 amp = sqrt(saturate(min(mn, 2.0 - mx) / max(mx, 1e-5)));
    float peak = -1.0 / lerp(8.0, 4.0, Sharpness);
    float3 k = amp * peak;
    float3 blur = (n + s + w + e) * 0.25 + (nw + ne + sw + se) * 0.125 * 0.5;
    return saturate(c + (c - blur) * (-k * 2.0));
}

technique CAS
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_CAS;
    }
}
