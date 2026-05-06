#include <jni.h>
#include <string.h>
#include <malloc.h>
#include <stdbool.h>
#include <stdlib.h>
#include <math.h>
#include <android/bitmap.h>
#include <android/log.h>

#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

#define WHITE 0xffffff
#define BLACK 0x000000
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, "System.out", __VA_ARGS__);

enum GCFunction {GCF_CLEAR, GCF_AND, GCF_AND_REVERSE, GCF_COPY, GCF_AND_INVERTED, GCF_NO_OP, GCF_XOR, GCF_OR, GCF_NOR, GCF_EQUIV, GCF_INVERT, GCF_OR_REVERSE, GCF_COPY_INVERTED, GCF_OR_INVERTED, GCF_NAND, GCF_SET};

static int packColor(int8_t r, int8_t g, int8_t b) {
    return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
}

static void unpackColor(int color, uint8_t *rgba) {
    rgba[2] = (color >> 16) & 255;
    rgba[1] = (color >> 8) & 255;
    rgba[0] = color & 255;
    rgba[3] = 255;
}

static int8_t getBit(uint8_t *line, int x) {
    uint8_t mask = (1 << (x & 7));
    line += (x >> 3);
    return (*line & mask) ? 1 : 0;
}

static int getBitmapBytePad(int width) {
    return ((width + 32 - 1) >> 5) << 2;
}

static int setPixelOp(int srcColor, int dstColor, enum GCFunction gcFunction) {
    switch (gcFunction) {
        case GCF_CLEAR :
            return BLACK;
        case GCF_AND :
            return srcColor & dstColor;
        case GCF_AND_REVERSE :
            return srcColor & ~dstColor;
        case GCF_COPY :
            return srcColor;
        case GCF_AND_INVERTED :
            return ~srcColor & dstColor;
        case GCF_XOR :
            return srcColor ^ dstColor;
        case GCF_OR :
            return srcColor | dstColor;
        case GCF_NOR :
            return ~srcColor & ~dstColor;
        case GCF_EQUIV :
            return ~srcColor ^ dstColor;
        case GCF_INVERT :
            return ~dstColor;
        case GCF_OR_REVERSE :
            return srcColor | ~dstColor;
        case GCF_COPY_INVERTED :
            return ~srcColor;
        case GCF_OR_INVERTED :
            return ~srcColor | dstColor;
        case GCF_NAND :
            return ~srcColor | ~dstColor;
        case GCF_SET :
            return WHITE;
        case GCF_NO_OP :
        default:
            return dstColor;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawBitmap(JNIEnv *env, jclass obj,
                                              jshort width, jshort height, jobject srcData,
                                              jobject dstData) {
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    int *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in drawBitmap\n");
        return;
    }

    int stride = getBitmapBytePad(width);
    int fullBytes = width >> 3;   // number of complete 8-pixel bytes per row
    int remainder = width & 7;    // leftover pixels in the last partial byte  */

    for (int16_t y = 0; y < height; y++) {
        // Unpack all 8 pixels from full byte
        for (int b = 0; b < fullBytes; b++) {
            uint8_t byte = srcDataAddr[b];
            for (int bit = 0; bit < 8; bit++) {
                *dstDataAddr++ = (byte >> bit) & 1 ? WHITE : BLACK;
            }
        }
        // Handle remainders and grab only first 4 bits (X11 pads out last 4)
        if (remainder) {
            uint8_t byte = srcDataAddr[fullBytes];
            for (int bit = 0; bit < remainder; bit++) {
                *dstDataAddr++ = (byte >> bit) & 1 ? WHITE : BLACK;
            }
        }
        srcDataAddr += stride;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_copyArea(JNIEnv *env, jclass obj, jshort srcX,
                                            jshort srcY, jshort dstX, jshort dstY,
                                            jshort width, jshort height, jshort srcStride,
                                            jshort dstStride, jobject srcData,
                                            jobject dstData) {
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    uint8_t *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in copyArea\n");
        return;
    }

    /* Fast path when the image is tightly packed (width == stride on both buffers) */
    if (width == srcStride && width == dstStride) {
        size_t bytes = (size_t)height * dstStride * 4;
        memcpy(dstDataAddr + (dstX + dstY * dstStride) * 4,
        srcDataAddr + (srcX + srcY * srcStride) * 4,
        bytes);
        return;
    }

    /* General case: row-by-row copy */
    size_t rowBytes = (size_t)width * 4;
    for (int16_t y = 0; y < height; y++) {
        memcpy(dstDataAddr + (dstX + (y + dstY) * dstStride) * 4,
        srcDataAddr + (srcX + (y + srcY) * srcStride) * 4,
        rowBytes);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_copyAreaOp(JNIEnv *env, jclass obj, jshort srcX,
                                              jshort srcY, jshort dstX, jshort dstY,
                                              jshort width, jshort height, jshort srcStride,
                                              jshort dstStride, jobject srcData,
                                              jobject dstData, int gcFunction) {
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    uint8_t *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in copyAreaOp\n");
        return;
    }

    // Fast path: GCF_COPY is plain pixel blitting — copy only RGB bytes to match
    if (gcFunction == GCF_COPY) {
        for (int16_t y = 0; y < height; y++) {
            for (int16_t x = 0; x < width; x++) {
                int i = (x + srcX + (y + srcY) * srcStride) * 4;
                int j = (x + dstX + (y + dstY) * dstStride) * 4;
                dstDataAddr[j+0] = srcDataAddr[i+0];
                dstDataAddr[j+1] = srcDataAddr[i+1];
                dstDataAddr[j+2] = srcDataAddr[i+2];
                /* byte 3 (alpha) intentionally not copied */
            }
        }
        return;
    }

    for (int16_t y = 0; y < height; y++) {
        for (int16_t x = 0; x < width; x++) {
            int i = (x + srcX + (y + srcY) * srcStride) * 4;
            int j = (x + dstX + (y + dstY) * dstStride) * 4;
            int srcColor = (srcDataAddr[i+0] << 16) | (srcDataAddr[i+1] << 8) | srcDataAddr[i+2];
            int dstColor = (dstDataAddr[j+0] << 16) | (dstDataAddr[j+1] << 8) | dstDataAddr[j+2];

            dstColor = setPixelOp(srcColor, dstColor, gcFunction);

            dstDataAddr[j+0] = (dstColor >> 16) & 0xff;
            dstDataAddr[j+1] = (dstColor >> 8) & 0xff;
            dstDataAddr[j+2] = dstColor & 0xff;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_fillRect(JNIEnv *env, jclass obj, jshort x, jshort y,
                                            jshort width, jshort height, jint color, jshort stride,
                                            jobject data) {
    uint8_t *dataAddr = (*env)->GetDirectBufferAddress(env, data);

    if (!dataAddr) {
        printf("Error: NULL buffer address in fillRect\n");
        return;
    }

    uint8_t rgba[4];
    unpackColor(color, rgba);

    int rowSize = width * 4;
    uint8_t stackRow[4096 * 4];
    uint8_t *row = stackRow;
    bool heapRow = false;
    if (width > 4096) {
        row = malloc(rowSize);
        if (!row) {
            printf("Error: Failed to allocate memory for row\n");
            return;
        }
        heapRow = true;
    }

    uint32_t color32 = ((uint32_t)rgba[3] << 24) | ((uint32_t)rgba[2] << 16) | ((uint32_t)rgba[1] << 8) | rgba[0];
    uint32_t *row32 = (uint32_t *)row;
    for (int i = 0; i < width; i++) row32[i] = color32;
    for (int16_t i = 0; i < height; i++) {
        memcpy(dataAddr + (x + (i + y) * stride) * 4, row, rowSize);
    }

    if (heapRow) free(row);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawLine(JNIEnv *env, jclass obj, jshort x0, jshort y0,
                                            jshort x1, jshort y1, jint color, jshort lineWidth,
                                            jshort stride, jobject data) {
    uint8_t *dataAddr = (*env)->GetDirectBufferAddress(env, data);

    if (!dataAddr) {
        printf("Error: NULL buffer address in drawLine\n");
        return;
    }

    int dx =  abs(x1-x0);
    int dy = -abs(y1-y0);
    int8_t sx = x0 < x1 ? 1 : -1;
    int8_t sy = y0 < y1 ? 1 : -1;
    int e1 = dx + dy, e2;

    uint8_t rgba[4];
    unpackColor(color, rgba);

    int rowSize = lineWidth * 4;
    uint8_t stackRow[4096 * 4];
    uint8_t *row = stackRow;
    bool heapRow = false;
    if (lineWidth > 4096) {
        row = malloc(rowSize);
        if (!row) {
            printf("Error: Failed to allocate memory for row\n");
            return;
        }
        heapRow = true;
    }

    uint32_t color32 = ((uint32_t)rgba[3] << 24) | ((uint32_t)rgba[2] << 16) | ((uint32_t)rgba[1] << 8) | rgba[0];
    uint32_t *row32 = (uint32_t *)row;
    for (int i = 0; i < lineWidth; i++) row32[i] = color32;

    /* Determine dominant direction once before the loop — not per-step,
     * since x0/y0 change each iteration and would flip the branch mid-line. */
    bool isHorizontal = abs(x1 - x0) >= abs(y1 - y0);

    while (true) {
        if (isHorizontal) {
            // Horizontal-ish: write a full row of pixels at once
            for (int16_t i = 0; i < lineWidth; i++) {
                memcpy(dataAddr + (x0 + (i + y0) * stride) * 4, row, rowSize);
            }
        } else {
            // Vertical-ish: write individual pixels
            for (int16_t i = 0; i < lineWidth; i++) {
                ((uint32_t *)dataAddr)[(x0 + i) + y0 * stride] = color32;
            }
        }
        if (x0 == x1 && y0 == y1) break;

        e2 = e1 * 2;
        if (e2 >= dy) {
            e1 += dy;
            x0 += sx;
        }
        if (e2 <= dx) {
            e1 += dx;
            y0 += sy;
        }
    }

    if (heapRow) free(row);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(JNIEnv *env, jclass obj,
                                                         jbyte foreRed, jbyte foreGreen,
                                                         jbyte foreBlue, jbyte backRed,
                                                         jbyte backGreen, jbyte backBlue,
                                                         jobject srcData, jobject maskData,
                                                         jobject dstData) {
    uint32_t *srcDataAddr  = (*env)->GetDirectBufferAddress(env, srcData);
    uint32_t *maskDataAddr = (*env)->GetDirectBufferAddress(env, maskData);
    uint32_t *dstDataAddr  = (*env)->GetDirectBufferAddress(env, dstData);

    if (!srcDataAddr || !maskDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in drawAlphaMaskedBitmap\n");
        return;
    }

    uint32_t foreColor = (uint32_t)packColor(foreRed, foreGreen, foreBlue) | 0xff000000u;
    uint32_t backColor = (uint32_t)packColor(backRed, backGreen, backBlue) | 0xff000000u;

    jlong dstLength = (*env)->GetDirectBufferCapacity(env, dstData) / 4;
    const uint32_t whiteMask = (uint32_t)WHITE;
#ifdef __ARM_NEON
    uint32x4_t vFore  = vdupq_n_u32(foreColor);
    uint32x4_t vBack  = vdupq_n_u32(backColor);
    uint32x4_t vWhite = vdupq_n_u32(whiteMask);
    uint32x4_t vZero  = vdupq_n_u32(0u);
    jlong i = 0;
    for (; i + 3 < dstLength; i += 4) {
        uint32x4_t vMask       = vld1q_u32(maskDataAddr + i);
        uint32x4_t vSrc        = vld1q_u32(srcDataAddr  + i);
        uint32x4_t maskIsWhite = vceqq_u32(vMask, vWhite);
        uint32x4_t srcIsWhite  = vceqq_u32(vSrc,  vWhite);
        uint32x4_t color       = vbslq_u32(srcIsWhite,  vFore, vBack);
        uint32x4_t result      = vbslq_u32(maskIsWhite, color,  vZero);
        vst1q_u32(dstDataAddr + i, result);
    }
    for (; i < dstLength; i++) {
        dstDataAddr[i] = maskDataAddr[i] == whiteMask
            ? (srcDataAddr[i] == whiteMask ? foreColor : backColor)
            : 0u;
    }
#else
    for (jlong i = 0; i < dstLength; i++) {
        dstDataAddr[i] = maskDataAddr[i] == whiteMask
            ? (srcDataAddr[i] == whiteMask ? foreColor : backColor)
            : 0u;
    }
#endif
}

/* replace the whole JNI body */
JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_fromBitmap(JNIEnv *env, jclass obj,
        jobject bitmap, jobject data) {
    uint8_t *dst = (*env)->GetDirectBufferAddress(env, data);
    if (!dst) {
        printf("Error: NULL buffer address in fromBitmap\n");
        return;
    }

    AndroidBitmapInfo info;
    uint8_t *pixels;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    if (AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels) < 0) return;

    memcpy(dst, pixels, (size_t)info.width * info.height * 4);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Pixmap_toBitmap(JNIEnv *env, jclass obj, jobject colorData,
                                          jobject maskData, jobject bitmap) {
    char *colorDataAddr = (*env)->GetDirectBufferAddress(env, colorData);
    char *maskDataAddr = maskData ? (*env)->GetDirectBufferAddress(env, maskData) : NULL;

    if (!colorDataAddr) {
        printf("Error: NULL color data address in toBitmap\n");
        return;
    }

    AndroidBitmapInfo info;
    uint8_t *pixels;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        printf("Error: Failed to get bitmap info in toBitmap\n");
        return;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, (void**)&pixels) < 0) {
        printf("Error: Failed to lock bitmap pixels in toBitmap\n");
        return;
    }

    int size = info.width * info.height;
    uint8_t *src = (uint8_t *)colorDataAddr;

// Byte-Swapping using ARM NEON in order to rely on a shuffle table
// to reduce operations since they're deterministic.
#ifdef __ARM_NEON
    if (!maskDataAddr) {
        /* Fast path: no mask — swap R and B channels across 4 pixels at a time.
         * src layout per pixel: [R, G, B, A]
         * dst layout per pixel: [B, G, R, A]
         * vrev32q_u8 reverses the 4 bytes within each 32-bit pixel: RGBA → ABGR,
         * which maps R→B and B→R with G and A landing in wrong positions.
         * Instead we use vtbl (byte table lookup) to do an exact per-byte shuffle. */
        static const uint8_t shuffle[16] = {
            2, 1, 0, 3,   /* pixel 0: swap bytes 0 and 2 (R↔B), keep 1 (G) and 3 (A) */
            6, 5, 4, 7,   /* pixel 1 */
            10, 9, 8, 11, /* pixel 2 */
            14, 13, 12, 15 /* pixel 3 */
        };
        uint8x16_t vShuffle = vld1q_u8(shuffle);
        int i = 0;
        for (; i + 3 < size; i += 4) {
            uint8x16_t vSrc = vld1q_u8(src + i * 4);
            uint8x16_t vDst = vqtbl1q_u8(vSrc, vShuffle);
            vst1q_u8(pixels + i * 4, vDst);
        }
        /* Scalar cleanup for remaining 0-3 pixels */
        for (; i < size; i++) {
            int j = i * 4;
            pixels[j+0] = src[j+2];
            pixels[j+1] = src[j+1];
            pixels[j+2] = src[j+0];
            pixels[j+3] = src[j+3];
        }
    } else {
        /* Mask path — scalar, same as before */
        uint8_t *mask = (uint8_t *)maskDataAddr;
        for (int i = 0; i < size; i++) {
            int j = i * 4;
            pixels[j+0] = src[j+2];
            pixels[j+1] = src[j+1];
            pixels[j+2] = src[j+0];
            pixels[j+3] = mask[j];
        }
    }
#else
    for (int i = 0; i < size; i++) {
        int j = i * 4;
        pixels[j+0] = src[j+2];
        pixels[j+1] = src[j+1];
        pixels[j+2] = src[j+0];
        pixels[j+3] = maskDataAddr ? ((uint8_t *)maskDataAddr)[j] : src[j+3];
    }
#endif

    AndroidBitmap_unlockPixels(env, bitmap);
}
