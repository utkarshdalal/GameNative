#include "ReShade.fxh"

// CRT scanlines: a sine mask locked to the output's vertical resolution.
uniform float Intensity <
    ui_min = 0.0; ui_max = 1.0;
> = 0.35;

uniform float Period <
    ui_min = 2.0; ui_max = 8.0;
> = 2.0;

float3 PS_Scanlines(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;
    float wave = 0.5 + 0.5 * sin(uv.y * BUFFER_HEIGHT * 6.28318530 / max(Period, 2.0));
    return saturate(c * (1.0 - Intensity * (1.0 - wave)));
}

technique Scanlines
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Scanlines;
    }
}
