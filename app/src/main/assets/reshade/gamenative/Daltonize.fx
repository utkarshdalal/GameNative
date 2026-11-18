#include "ReShade.fxh"

// Daltonization (daltonize.org): simulate the missing cone response, then push
// the lost information into the channels that still differ.
// 0 = protanopia, 1 = deuteranopia, 2 = tritanopia.
uniform int Type <
    ui_min = 0; ui_max = 2;
> = 1;

uniform float Strength <
    ui_min = 0.0; ui_max = 1.0;
> = 0.7;

float3 PS_Daltonize(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float3 c = tex2D(ReShade::BackBuffer, uv).rgb;

    // LMS cone space.
    float l = dot(c, float3(17.8824, 43.5161, 4.11935));
    float m = dot(c, float3(3.45565, 27.1554, 3.86714));
    float s = dot(c, float3(0.0299566, 0.184309, 1.46709));

    float ls = l;
    float ms = m;
    float ss = s;
    if (Type == 0) ls = 2.02344 * m - 2.52581 * s;
    else if (Type == 1) ms = 0.494207 * l + 1.24827 * s;
    else ss = -0.395913 * l + 0.801109 * m;

    // Back to RGB: what the viewer perceives from the same scene.
    float3 sim;
    sim.r = dot(float3(ls, ms, ss), float3(0.0809444479, -0.130504409, 0.116721066));
    sim.g = dot(float3(ls, ms, ss), float3(-0.0102485335, 0.0540193266, -0.113614708));
    sim.b = dot(float3(ls, ms, ss), float3(-0.000365296938, -0.00412161469, 0.693511405));

    float3 error = c - sim;
    float3 shifted;
    shifted.r = 0.0;
    shifted.g = error.r * 0.7 + error.g;
    shifted.b = error.r * 0.7 + error.b;
    return saturate(c + shifted * Strength);
}

technique Daltonize
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_Daltonize;
    }
}
