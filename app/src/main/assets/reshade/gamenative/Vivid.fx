#include "ReShade.fxh"

uniform float Vibrance <
    ui_min = 0.0; ui_max = 2.0;
> = 0.5;

float3 PS_Vivid(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    float sat = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
    float amount = Vibrance * (1.0 - sat);
    return saturate(lerp(float3(l, l, l), c, 1.0 + amount));
}

technique Vivid
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Vivid;
    }
}
