#include "ReShade.fxh"

// Interleaved gradient noise dither: breaks 8-bit banding in gradients by
// varying the quantisation threshold per pixel instead of using a Bayer tile.
uniform float Amount <
    ui_min = 0.0; ui_max = 4.0;
> = 1.0;

float3 PS_Dither(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;
    float2 p = uv * ReShade::ScreenSize;
    // One 8-bit step is 1/255; the offset stays inside that.
    float noise = frac(52.9829189 * frac(dot(p, float2(0.06711056, 0.00583715))));
    return saturate(c + (noise - 0.5) * Amount / 255.0);
}

technique Dither
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Dither;
    }
}
