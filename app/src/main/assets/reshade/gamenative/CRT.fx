#include "ReShade.fxh"

// CRT: barrel curvature, aperture-grille mask, scanlines and a lateral
// convergence error, all in one pass.
uniform float Curvature <
    ui_min = 0.0; ui_max = 0.5;
> = 0.08;

uniform float Lines <
    ui_min = 240.0; ui_max = 1080.0;
> = 540.0;

uniform float Scanlines <
    ui_min = 0.0; ui_max = 1.0;
> = 0.35;

uniform float Convergence <
    ui_min = 0.0; ui_max = 3.0;
> = 1.0;

float3 Sample(float2 uv)
{
    return tex2D(ReShade::BackBuffer, uv).rgb;
}

float3 PS_CRT(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    // Bulge the frame outward from its centre.
    float2 p = (uv - 0.5) * 2.0;
    p *= 1.0 + Curvature * dot(p, p);
    float2 warped = p * 0.5 + 0.5;

    // Outside the tube is black, not edge-clamped.
    if (any(warped < 0.0) || any(warped > 1.0)) return float3(0.0, 0.0, 0.0);

    // Convergence error grows toward the edges, as on a real tube.
    float2 off = (warped - 0.5) * 0.0015 * Convergence;
    float3 c;
    c.r = Sample(warped + off).r;
    c.g = Sample(warped).g;
    c.b = Sample(warped - off).b;

    // Aperture grille plus scanlines: one period per physical pixel row.
    float grille = 0.5 + 0.5 * sin(warped.x * BUFFER_WIDTH * 3.14159265);
    float scan = 0.5 + 0.5 * sin(warped.y * Lines * 3.14159265);
    float mask = lerp(1.0, grille * scan * 2.0, Scanlines);
    return saturate(c * mask);
}

technique CRT
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_CRT;
    }
}
