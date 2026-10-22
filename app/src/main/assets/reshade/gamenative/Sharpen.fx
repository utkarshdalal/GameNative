#include "ReShade.fxh"

uniform float Sharpness <
    ui_min = 0.0; ui_max = 2.0;
> = 0.6;

float3 PS_Sharpen(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float2 px = ReShade::PixelSize;
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;
    float3 n = tex2D(ReShade::BackBuffer, uv + float2(0.0, -px.y)).rgb;
    float3 s = tex2D(ReShade::BackBuffer, uv + float2(0.0,  px.y)).rgb;
    float3 w = tex2D(ReShade::BackBuffer, uv + float2(-px.x, 0.0)).rgb;
    float3 e = tex2D(ReShade::BackBuffer, uv + float2( px.x, 0.0)).rgb;
    float3 blur = (n + s + w + e) * 0.25;
    return saturate(c + (c - blur) * Sharpness);
}

technique Sharpen
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Sharpen;
    }
}
