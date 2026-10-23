#include "ReShade.fxh"

// ACES filmic curve (Narkowicz fit) for SDR output: rolls highlights off instead
// of clipping them. The buffer is display-referred, so it is linearised first and
// re-encoded afterwards; without that the curve lifts every midtone.
uniform float Exposure <
    ui_min = 0.0; ui_max = 4.0;
> = 1.0;

float3 PS_Tonemap(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
#if BUFFER_COLOR_SPACE >= 2
    return tex2D(ReShade::BackBuffer, uv).rgb;
#else
    float3 x = pow(saturate(tex2D(ReShade::BackBuffer, uv).rgb), 2.2) * Exposure;
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    float3 mapped = saturate((x * (a * x + b)) / (x * (c * x + d) + e));
    return pow(mapped, 1.0 / 2.2);
#endif
}

technique Tonemap
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Tonemap;
    }
}
