package com.winlator.renderer;

import java.nio.ByteBuffer;

public abstract class NativeTexture extends Texture {
    public abstract short getStride();
    public abstract ByteBuffer getVirtualData();
}
