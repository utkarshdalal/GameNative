#include "ReShade.fxh"

// Symmetric S-curve around mid-grey, with a brightness lift applied first.
uniform float Contrast <
    ui_min = 0.0; ui_max = 2.0;
> = 1.1;

uniform float Brightness <
    ui_min = -0.5; ui_max = 0.5;
> = 0.0;

float3 PS_Contrast(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float3 c = saturate(tex2D(ReShade::BackBuffer, uv).rgb + Brightness);
    // smoothstep about 0.5 keeps black and white fixed and steepens the midtones.
    float3 s = c * c * (3.0 - 2.0 * c);
    return saturate(lerp(c, s, Contrast - 1.0));
}

technique Contrast
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Contrast;
    }
}
