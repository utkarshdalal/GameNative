package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;

public interface RenderScaleEffect {
    int getRenderWidth(GLRenderer renderer, int outputWidth);
    int getRenderHeight(GLRenderer renderer, int outputHeight);
}
