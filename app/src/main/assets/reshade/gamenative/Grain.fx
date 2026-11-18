#include "ReShade.fxh"

// Film grain: value noise re-seeded per pixel, weighted toward the midtones so
// black stays black and the HUD stays legible.
uniform float Amount <
    ui_min = 0.0; ui_max = 1.0;
> = 0.15;

float Hash(float2 p)
{
    return frac(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

float3 PS_Grain(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;
    float luma = dot(c, float3(0.299, 0.587, 0.114));
    float weight = luma * (1.0 - luma) * 4.0;
    float n = Hash(uv * ReShade::ScreenSize) - 0.5;
    return saturate(c + n * Amount * weight);
}

technique Grain
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Grain;
    }
}
