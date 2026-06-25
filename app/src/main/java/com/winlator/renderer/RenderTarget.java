package com.winlator.renderer;

import android.opengl.GLES20;
import android.util.Log;

public class RenderTarget extends Texture {
    private static final String TAG = "RenderTarget";
    private int framebuffer = 0;
    private int width = 0;
    private int height = 0;

    public RenderTarget() {
        format = GLES20.GL_RGBA;
    }

    public void allocateFramebuffer(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (framebuffer != 0 && this.width == width && this.height == height) return;

        destroy();
        this.width = width;
        this.height = height;

        int[] framebuffers = new int[1];
        GLES20.glGenFramebuffers(1, framebuffers, 0);
        framebuffer = framebuffers[0];

        generateTextureId();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, unpackAlignment);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            format,
            width,
            height,
            0,
            format,
            GLES20.GL_UNSIGNED_BYTE,
            null
        );
        setTextureParameters();
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId,
            0
        );

        int framebufferStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (framebufferStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer incomplete: 0x" + Integer.toHexString(framebufferStatus));
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            destroy();
            throw new IllegalStateException("Framebuffer incomplete: 0x" + Integer.toHexString(framebufferStatus));
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public void invalidate() {
        super.invalidate();
        framebuffer = 0;
        width = 0;
        height = 0;
    }

    public int getFramebuffer() {
        return framebuffer;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public void destroy() {
        super.destroy();
        if (framebuffer != 0) {
            int[] framebuffers = new int[] {framebuffer};
            GLES20.glDeleteFramebuffers(1, framebuffers, 0);
            framebuffer = 0;
        }
        width = 0;
        height = 0;
    }
}
