#include "ReShade.fxh"

// Radial darkening, measured in aspect-corrected screen units so the falloff is
// round on a 16:9 panel rather than elliptical.
uniform float Amount <
    ui_min = 0.0; ui_max = 1.0;
> = 0.35;

uniform float Radius <
    ui_min = 0.1; ui_max = 1.5;
> = 0.8;

float3 PS_Vignette(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float2 d = (uv - 0.5) * float2(BUFFER_ASPECT_RATIO, 1.0);
    float r = saturate(length(d) / max(Radius, 1e-3));
    float falloff = 1.0 - Amount * r * r;
    return saturate(tex2D(ReShade::BackBuffer, uv).rgb * falloff);
}

technique Vignette
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Vignette;
    }
}
