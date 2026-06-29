package com.winlator.xserver;

import android.graphics.Bitmap;

import com.winlator.core.Callback;
import com.winlator.math.Mathf;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xserver.GraphicsContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import timber.log.Timber;

public class Drawable extends XResource {
    private static boolean DRAWABLE_FOR_ASR = false;
    private ByteBuffer data;
    public final short height;
    private boolean offscreenStorage;
    private Callback<Drawable> onDestroyListener;
    private Runnable onDrawListener;
    public final Object renderLock;
    private Texture texture;
    private boolean useSharedData;
    public final Visual visual;
    public final short width;

    private static native void copyArea(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2);

    private static native void copyAreaOp(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, int i);

    private static native void drawAlphaMaskedBitmap(byte b, byte b2, byte b3, byte b4, byte b5, byte b6, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, ByteBuffer byteBuffer3);

    private static native void drawBitmap(short s, short s2, ByteBuffer byteBuffer, ByteBuffer byteBuffer2);

    private static native void drawLine(short s, short s2, short s3, short s4, int i, short s5, short s6, ByteBuffer byteBuffer);

    private static native void fillRect(short s, short s2, short s3, short s4, int i, short s5, ByteBuffer byteBuffer);

    private static native void fromBitmap(Bitmap bitmap, ByteBuffer byteBuffer);

    public static void DRAWABLE_ASR_MODE(boolean value) {
        DRAWABLE_FOR_ASR = value;
    }

    static {
        System.loadLibrary("winlator_11");
    }

    public Drawable(int id, int width, int height, Visual visual) {
        super(id);
        this.texture = new Texture();
        this.offscreenStorage = false;
        this.renderLock = new Object();
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;

        if (Drawable.DRAWABLE_FOR_ASR) {
            GPUImage g = new GPUImage((short) width, (short) height);
            this.texture = g;
            this.data = g.getVirtualData();
        } else {
            this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null);
        fromBitmap(bitmap, drawable.data);
        return drawable;
    }

    public boolean isOffscreenStorage() {
        return this.offscreenStorage;
    }

    public void setOffscreenStorage(boolean offscreenStorage) {
        this.offscreenStorage = offscreenStorage;
    }

    public Texture getTexture() {
        return texture;
    }

    public void setTexture(Texture texture) {
        if (texture instanceof GPUImage) data = ((GPUImage)texture).getVirtualData();
        this.texture = texture;
    }

    public ByteBuffer getData() {
        return data;
    }

    // Alias for {@link #getData()}. Added to mirror the API expected by
    // {@code com.winlator.renderer.VulkanRenderer} (ported from Winlator-Ludashi),
    // which uses {@code getBuffer()}. Keeping the alias avoids diverging from upstream.
    public ByteBuffer getBuffer() {
        return data;
    }

    public boolean isDirectScanout() {
        return false;
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }

    private short getStride() {
        return texture instanceof GPUImage ? ((GPUImage)texture).getStride() : width;
    }

    public Runnable getOnDrawListener() {
        return onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        ByteBuffer byteBuffer = this.data;
        if (byteBuffer == null) {
            return;
        }
        if (depth == 1) {
            drawBitmap(width, height, data, byteBuffer);
        }
        else {
            if (depth == 24 || depth == 32) {
                dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
                dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
                if ((dstX + width) > this.width) width = (short)((this.width - dstX));
                if ((dstY + height) > this.height) height = (short)((this.height - dstY));

                copyArea(srcX, srcY, dstX, dstY, width, height, totalWidth, this.getStride(), data, this.data);
            }
            this.data.rewind();
            data.rewind();
            forceUpdate();
        }
        this.data.rewind();
        data.rewind();
        forceUpdate();
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        if (this.data == null) {
            return dstData;
        }
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea(x, y, (short)0, (short)0, width, height, this.getStride(), width, this.data, dstData);

        this.data.rewind();
        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        if (this.data != null && drawable.data != null) {
            dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
            dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
            if ((dstX + width) > this.width) width = (short)(this.width - dstX);
            if ((dstY + height) > this.height) height = (short)(this.height - dstY);

            if (gcFunction == GraphicsContext.Function.COPY) {
                copyArea(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data);
            }
            else copyAreaOp(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data, gcFunction.ordinal());

            this.data.rewind();
            drawable.data.rewind();
            forceUpdate();
        }
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        if (this.data == null) {
            return;
        }
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)((this.width - x));
        if ((y + height) > this.height) height = (short)((this.height - y));

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), this.data);
        this.data.rewind();
        forceUpdate();
    }

    public void drawLines(int color, int lineWidth, short... points) {
        for (int i = 2; i < points.length; i += 2) {
            drawLine(points[i-2], points[i-1], points[i+0], points[i+1], color, (short)lineWidth);
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        if (this.data == null) {
            return;
        }
        x0 = Mathf.clamp(x0, 0, width-lineWidth);
        y0 = Mathf.clamp(y0, 0, height-lineWidth);
        x1 = Mathf.clamp(x1, 0, width-lineWidth);
        y1 = Mathf.clamp(y1, 0, height-lineWidth);

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), this.data);

        this.data.rewind();
        forceUpdate();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        ByteBuffer byteBuffer;
        ByteBuffer byteBuffer2 = this.data;
        if (byteBuffer2 != null && (byteBuffer = srcDrawable.data) != null) {
            ByteBuffer byteBuffer3 = maskDrawable.data;
            if (byteBuffer3 == null) {
                return;
            }
            drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, byteBuffer, byteBuffer3, byteBuffer2);
        this.data.rewind();
            forceUpdate();
        }
    }

    public void forceUpdate() {
        if (!this.offscreenStorage) {
            this.texture.setNeedsUpdate(true);
            Runnable runnable = this.onDrawListener;
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    public boolean isUseSharedData() {
        return this.useSharedData;
    }

    public void setUseSharedData(boolean useSharedData) {
        this.useSharedData = useSharedData;
    }
}
