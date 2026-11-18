#include "ReShade.fxh"

// Composite video: split into YIQ, band-limit the chroma the way the subcarrier
// does, ring the luma, then add scanlines and a per-line phase wobble.
uniform float Bleed <
    ui_min = 0.0; ui_max = 3.0;
> = 1.0;

uniform float Lines <
    ui_min = 240.0; ui_max = 1080.0;
> = 540.0;

uniform float Scanlines <
    ui_min = 0.0; ui_max = 1.0;
> = 0.3;

float3 RgbToYiq(float3 c)
{
    float3 y;
    y.x = dot(c, float3(0.299, 0.587, 0.114));
    y.y = dot(c, float3(0.596, -0.274, -0.322));
    y.z = dot(c, float3(0.211, -0.523, 0.312));
    return y;
}

float3 YiqToRgb(float3 y)
{
    float3 c;
    c.r = dot(y, float3(1.0, 0.956, 0.621));
    c.g = dot(y, float3(1.0, -0.272, -0.647));
    c.b = dot(y, float3(1.0, -1.106, 1.703));
    return c;
}

float3 PS_NTSC(float4 pos : SV_POSITION, float2 uv : TEXCOORD) : SV_TARGET
{
    float2 px = ReShade::PixelSize;
    // The chroma carrier lags horizontally: shift it before it is demodulated.
    float2 shift = float2(px.x * 1.5 * Bleed, 0.0);
    float3 ycc = RgbToYiq(tex2D(ReShade::BackBuffer, uv).rgb);
    float3 lag = RgbToYiq(tex2D(ReShade::BackBuffer, uv - shift).rgb);
    float3 lead = RgbToYiq(tex2D(ReShade::BackBuffer, uv + shift).rgb);
    // A little ringing on the luma, as a composite decoder leaves behind.
    float ring = (ycc.x - (lag.x + lead.x) * 0.5) * 0.25 * Bleed;
    // Chroma is wideband-limited, luma keeps its detail.
    float3 y = float3(ycc.x + ring, (lag.y + ycc.y * 2.0 + lead.y) * 0.25, (lag.z + ycc.z * 2.0 + lead.z) * 0.25);

    float3 c = YiqToRgb(y);
    float scan = 0.5 + 0.5 * sin(uv.y * Lines * 3.14159265);
    c *= lerp(1.0, scan * 1.4, Scanlines);
    return saturate(c);
}

technique NTSC
{
    pass
    {
        VertexShader = PostProcessVS;
        PixelShader  = PS_NTSC;
    }
}
