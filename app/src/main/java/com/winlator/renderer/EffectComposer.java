package com.winlator.renderer;

import android.opengl.GLES20;

import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    private final ArrayList<Effect> effects = new ArrayList<>();
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
        int width = renderer.getSurfaceWidth();
        int height = renderer.getSurfaceHeight();
        if (effects.isEmpty() || width <= 0 || height <= 0) {
            renderer.drawScene();
            return;
        }

        readBuffer.allocateFramebuffer(width, height);
        writeBuffer.allocateFramebuffer(width, height);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
        renderer.drawScene();

        ArrayList<Effect> snapshot = new ArrayList<>(effects);
        RenderTarget source = readBuffer;
        RenderTarget target = writeBuffer;

        for (int i = 0; i < snapshot.size(); i++) {
            boolean renderToScreen = i == snapshot.size() - 1;
            GLES20.glBindFramebuffer(
                GLES20.GL_FRAMEBUFFER,
                renderToScreen ? 0 : target.getFramebuffer()
            );
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            Effect effect = snapshot.get(i);
            effect.use(renderer);
            ShaderMaterial material = effect.getMaterial();

            renderer.getQuadVertices().bind(material.programId);
            material.setUniformVec2("resolution", width, height);
            material.setUniformInt("screenTexture", 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.getTextureId());
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.getQuadVertices().count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            renderer.getQuadVertices().disable();

            if (!renderToScreen) {
                RenderTarget temp = source;
                source = target;
                target = temp;
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.setViewportNeedsUpdate(true);
    }

    public synchronized void invalidateGLResources() {
        for (Effect effect : effects) {
            effect.destroy();
        }
        readBuffer.invalidate();
        writeBuffer.invalidate();
    }

    public synchronized void destroy() {
        for (Effect effect : effects) {
            effect.destroy();
        }
        effects.clear();
        readBuffer.destroy();
        writeBuffer.destroy();
    }
}
