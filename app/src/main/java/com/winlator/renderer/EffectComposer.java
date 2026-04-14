package com.winlator.renderer;

import android.opengl.GLES20;

import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.effects.RenderScaleEffect;
import com.winlator.renderer.effects.SourceTextureFilterEffect;
import com.winlator.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    private final ArrayList<Effect> effects = new ArrayList<>();
    private final RenderTarget sceneBuffer = new RenderTarget();
    private final RenderTarget readBuffer = new RenderTarget();
    private final RenderTarget writeBuffer = new RenderTarget();
    private final GLRenderer renderer;

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    public synchronized <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effectClass.isInstance(effect)) {
                return effectClass.cast(effect);
            }
        }
        return null;
    }

    public synchronized void setEffects(List<? extends Effect> newEffects) {
        ArrayList<Effect> previousEffects = new ArrayList<>(effects);
        effects.clear();
        effects.addAll(newEffects);

        ArrayList<Effect> removedEffects = new ArrayList<>();
        for (Effect effect : previousEffects) {
            if (!effects.contains(effect)) {
                removedEffects.add(effect);
            }
        }

        if (!removedEffects.isEmpty()) {
            renderer.xServerView.queueEvent(() -> {
                for (Effect effect : removedEffects) {
                    effect.destroy();
                }
            });
        }

        renderer.xServerView.requestRender();
    }

    public synchronized void clearEffects() {
        setEffects(new ArrayList<Effect>());
    }

    public synchronized void render() {
        int outputWidth = renderer.getSurfaceWidth();
        int outputHeight = renderer.getSurfaceHeight();
        if (effects.isEmpty() || outputWidth <= 0 || outputHeight <= 0) {
            renderer.drawScene();
            return;
        }

        ArrayList<Effect> snapshot = new ArrayList<>(effects);
        int sceneWidth = outputWidth;
        int sceneHeight = outputHeight;
        if (!snapshot.isEmpty() && snapshot.get(0) instanceof RenderScaleEffect) {
            RenderScaleEffect renderScaleEffect = (RenderScaleEffect) snapshot.get(0);
            sceneWidth = Math.max(1, renderScaleEffect.getRenderWidth(renderer, outputWidth));
            sceneHeight = Math.max(1, renderScaleEffect.getRenderHeight(renderer, outputHeight));
        }
        boolean scaledScene = sceneWidth != outputWidth || sceneHeight != outputHeight;

        sceneBuffer.setFilters(GLES20.GL_NEAREST, GLES20.GL_NEAREST);
        readBuffer.setFilters(GLES20.GL_NEAREST, GLES20.GL_NEAREST);
        writeBuffer.setFilters(GLES20.GL_NEAREST, GLES20.GL_NEAREST);

        sceneBuffer.allocateFramebuffer(sceneWidth, sceneHeight);
        readBuffer.allocateFramebuffer(outputWidth, outputHeight);
        writeBuffer.allocateFramebuffer(outputWidth, outputHeight);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneBuffer.getFramebuffer());
        GLES20.glViewport(0, 0, sceneWidth, sceneHeight);
        if (scaledScene) {
            renderer.setRenderTargetSizeOverride(sceneWidth, sceneHeight);
        }
        renderer.drawScene();
        if (scaledScene) {
            renderer.clearRenderTargetSizeOverride();
        }

        RenderTarget source = sceneBuffer;
        RenderTarget target = readBuffer;
        int sourceWidth = sceneWidth;
        int sourceHeight = sceneHeight;

        for (int i = 0; i < snapshot.size(); i++) {
            boolean renderToScreen = i == snapshot.size() - 1;
            int targetFramebuffer = renderToScreen ? 0 : target.getFramebuffer();
            int targetWidth = renderToScreen ? outputWidth : target.getWidth();
            int targetHeight = renderToScreen ? outputHeight : target.getHeight();

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
            GLES20.glViewport(0, 0, targetWidth, targetHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            Effect effect = snapshot.get(i);
            effect.use(renderer);
            ShaderMaterial material = effect.getMaterial();

            if (effect instanceof SourceTextureFilterEffect) {
                SourceTextureFilterEffect textureFilterEffect = (SourceTextureFilterEffect) effect;
                source.setFilters(textureFilterEffect.getSourceMinFilter(), textureFilterEffect.getSourceMagFilter());
            }
            else {
                source.setFilters(GLES20.GL_NEAREST, GLES20.GL_NEAREST);
            }

            renderer.getQuadVertices().bind(material.programId);
            material.setUniformVec2("resolution", targetWidth, targetHeight);
            material.setUniformVec2("inputResolution", sourceWidth, sourceHeight);
            material.setUniformVec2("outputResolution", targetWidth, targetHeight);
            material.setUniformInt("screenTexture", 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.getTextureId());
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.getQuadVertices().count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            renderer.getQuadVertices().disable();

            if (!renderToScreen) {
                source = target;
                sourceWidth = targetWidth;
                sourceHeight = targetHeight;
                target = target == readBuffer ? writeBuffer : readBuffer;
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (scaledScene) {
            GLES20.glViewport(0, 0, outputWidth, outputHeight);
        }
        renderer.setViewportNeedsUpdate(true);
    }

    public synchronized void invalidateGLResources() {
        for (Effect effect : effects) {
            effect.destroy();
        }
        sceneBuffer.invalidate();
        readBuffer.invalidate();
        writeBuffer.invalidate();
    }

    public synchronized void destroy() {
        for (Effect effect : effects) {
            effect.destroy();
        }
        effects.clear();
        sceneBuffer.destroy();
        readBuffer.destroy();
        writeBuffer.destroy();
    }
}
