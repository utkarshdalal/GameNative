#include "ReShade.fxh"

// Lateral chromatic aberration: red and blue sampled slightly further out than
// green, scaled by distance from centre so the middle of the frame stays clean.
uniform float Amount <
    ui_min = 0.0; ui_max = 4.0;
> = 1.0;

float3 PS_Chromatic(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float2 offset = (uv - 0.5) * Amount * 0.002;
    float3 c;
    c.r = tex2D(ReShade::BackBuffer, uv + offset).r;
    c.g = tex2D(ReShade::BackBuffer, uv).g;
    c.b = tex2D(ReShade::BackBuffer, uv - offset).b;
    return c;
}

technique Chromatic
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Chromatic;
    }
}
