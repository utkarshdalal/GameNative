#include "ReShade.fxh"

// Cel-shading outlines: a Sobel gradient of luma, with everything above the
// threshold painted toward black so flat areas stay clean.
uniform float Threshold <
    ui_min = 0.02; ui_max = 1.0;
> = 0.15;

uniform float Thickness <
    ui_min = 0.5; ui_max = 4.0;
> = 1.0;

uniform float Darkness <
    ui_min = 0.0; ui_max = 1.0;
> = 1.0;

float Luma(float3 c)
{
    return dot(c, float3(0.299, 0.587, 0.114));
}

float3 PS_Toon(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float2 px = ReShade::PixelSize * Thickness;
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;

    // Sobel: nine taps, horizontal and vertical derivative of luma.
    float tl = Luma(tex2D(ReShade::BackBuffer, uv + float2(-px.x, -px.y)).rgb);
    float tc = Luma(tex2D(ReShade::BackBuffer, uv + float2( 0.0,  -px.y)).rgb);
    float tr = Luma(tex2D(ReShade::BackBuffer, uv + float2( px.x, -px.y)).rgb);
    float ml = Luma(tex2D(ReShade::BackBuffer, uv + float2(-px.x,  0.0)).rgb);
    float mr = Luma(tex2D(ReShade::BackBuffer, uv + float2( px.x,  0.0)).rgb);
    float bl = Luma(tex2D(ReShade::BackBuffer, uv + float2(-px.x,  px.y)).rgb);
    float bc = Luma(tex2D(ReShade::BackBuffer, uv + float2( 0.0,   px.y)).rgb);
    float br = Luma(tex2D(ReShade::BackBuffer, uv + float2( px.x,  px.y)).rgb);

    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
    // A Sobel response of 4 is a full 0..1 step, so normalise by it: the threshold is then the
    // per-pixel luma change that counts as an edge.
    float edge = saturate(sqrt(gx * gx + gy * gy) / (4.0 * max(Threshold, 1e-4)));
    return c * (1.0 - edge * Darkness);
}

technique Toon
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Toon;
    }
}
