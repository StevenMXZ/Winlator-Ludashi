#include <jni.h>
#include <string.h>
#include <malloc.h>
#include <stdbool.h>
#include <stdlib.h>
#include <math.h>

#include <android/hardware_buffer.h>
#include <android/bitmap.h>
#include <android/log.h>

#define WHITE 0xffffff
#define BLACK 0x000000
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, "Drawable", __VA_ARGS__);
#define HAL_PIXEL_FORMAT_BGRA_8888 5

enum GCFunction {GCF_CLEAR, GCF_AND, GCF_AND_REVERSE, GCF_COPY, GCF_AND_INVERTED, GCF_NO_OP, GCF_XOR, GCF_OR, GCF_NOR, GCF_EQUIV, GCF_INVERT, GCF_OR_REVERSE, GCF_COPY_INVERTED, GCF_OR_INVERTED, GCF_NAND, GCF_SET};

static int packColor(int8_t r, int8_t g, int8_t b) {
    return ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
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
        case GCF_CLEAR:
            return BLACK;
        case GCF_AND:
            return srcColor & dstColor;
        case GCF_AND_REVERSE:
            return srcColor & ~dstColor;
        case GCF_COPY:
            return srcColor;
        case GCF_AND_INVERTED:
            return ~srcColor & dstColor;
        case GCF_XOR:
            return srcColor ^ dstColor;
        case GCF_OR:
            return srcColor | dstColor;
        case GCF_NOR:
            return ~srcColor & ~dstColor;
        case GCF_EQUIV:
            return ~srcColor ^ dstColor;
        case GCF_INVERT:
            return ~dstColor;
        case GCF_OR_REVERSE:
            return srcColor | ~dstColor;
        case GCF_COPY_INVERTED:
            return ~srcColor;
        case GCF_OR_INVERTED:
            return ~srcColor | dstColor;
        case GCF_NAND:
            return ~srcColor | ~dstColor;
        case GCF_SET:
            return WHITE;
        case GCF_NO_OP:
        default:
            return dstColor;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_drawBitmap(JNIEnv *env, jclass obj,
                                                   jshort width, jshort height, 
                                                   jobject srcData, jshort dstStride,
                                                   jlong dstData) 
{
    int *dstDataAddr;
    int ret;
    
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)dstData;
    ret = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr);
    if (ret != 0)    
        return;
                                                  
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in drawBitmap\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    int stride = getBitmapBytePad(width);
    for (int16_t y = 0, x; y < height; y++) {
        int *dst = dstDataAddr + (y * dstStride);
        for (x = 0; x < width; x++) {
            dst[x] = getBit(srcDataAddr, x) ? WHITE : BLACK;
        }
        srcDataAddr += stride;
    }
    
    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyArea1(JNIEnv *env, jclass obj, jshort srcX,
                                                  jshort srcY, jshort dstX, jshort dstY,
                                                  jshort width, jshort height, 
                                                  jshort srcStride, jshort dstStride, 
                                                  jobject srcData, jlong dstData)
{
    int ret;
    uint8_t *dstDataAddr;
    
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)dstData;
    ret = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr);
    if (ret != 0)    
        return;
                                                
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in copyArea\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    int copyAmount = width * 4;
    for (int16_t y = 0; y < height; y++) {
        memcpy(dstDataAddr + (dstX + (y + dstY) * dstStride) * 4, srcDataAddr + (srcX + (y + srcY) * srcStride) * 4, copyAmount);
    }
    
    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyArea2(JNIEnv *env, jclass obj, jshort srcX,
                                                  jshort srcY, jshort dstX, jshort dstY,
                                                  jshort width, jshort height, jshort srcStride,
                                                  jshort dstStride, jlong srcData,
                                                  jobject dstData) 
{
    int ret;                                            
    uint8_t *srcDataAddr;
    
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)srcData;
    ret = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&srcDataAddr);
    if (ret != 0)    
        return;
        
    uint8_t *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in copyArea\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    int copyAmount = width * 4;
    for (int16_t y = 0; y < height; y++) {
        memcpy(dstDataAddr + (dstX + (y + dstY) * dstStride) * 4, srcDataAddr + (srcX + (y + srcY) * srcStride) * 4, copyAmount);
    }
    
    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyArea3(jshort srcX, jshort srcY, 
                                                  jshort dstX, jshort dstY,
                                                  jshort width, jshort height, jshort srcStride,
                                                  jshort dstStride, jlong srcData,
                                                  jlong dstData)
{
    int ret;                                            
    uint8_t *srcDataAddr;
    uint8_t *dstDataAddr;
    
    AHardwareBuffer *srcHardwareBuffer = (AHardwareBuffer *)srcData;
    ret = AHardwareBuffer_lock(srcHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&srcDataAddr);
    if (ret != 0)    
        return;
    
    AHardwareBuffer *dstHardwareBuffer = (AHardwareBuffer *)dstData;
    ret = AHardwareBuffer_lock(dstHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr);
    if (ret != 0)    
        return;    

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in copyArea\n");
        AHardwareBuffer_unlock(dstHardwareBuffer, NULL);
        AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
        return;
    }

    int copyAmount = width * 4;
    for (int16_t y = 0; y < height; y++) {
        memcpy(dstDataAddr + (dstX + (y + dstY) * dstStride) * 4, srcDataAddr + (srcX + (y + srcY) * srcStride) * 4, copyAmount);
    }
    
    AHardwareBuffer_unlock(dstHardwareBuffer, NULL);
    AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyAreaOp(jshort srcX, jshort srcY, 
                                                   jshort dstX, jshort dstY,
                                                   jshort width, jshort height, jshort srcStride,
                                                   jshort dstStride, jlong srcData,
                                                   jlong dstData, int gcFunction) 
{
    int ret;                                              
    uint8_t *srcDataAddr;
    uint8_t *dstDataAddr;
    
    AHardwareBuffer *srcHardwareBuffer = (AHardwareBuffer *)srcData;
    ret = AHardwareBuffer_lock(srcHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&srcDataAddr);
    if (ret != 0)    
        return;
    
    AHardwareBuffer *dstHardwareBuffer = (AHardwareBuffer *)dstData;
    ret = AHardwareBuffer_lock(dstHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr);
    if (ret != 0)    
        return;    

    if (!srcDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in copyAreaOp\n");
        AHardwareBuffer_unlock(dstHardwareBuffer, NULL);
        AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
        return;
    }

    for (int16_t y = 0; y < height; y++) {
        for (int16_t x = 0; x < width; x++) {
            int i = (x + srcX + (y + srcY) * srcStride) * 4;
            int j = (x + dstX + (y + dstY) * dstStride) * 4;
            int srcColor = (srcDataAddr[i] << 16) | (srcDataAddr[i+1] << 8) | srcDataAddr[i+2];
            int dstColor = (dstDataAddr[j] << 16) | (dstDataAddr[j+1] << 8) | dstDataAddr[j+2];

            dstColor = setPixelOp(srcColor, dstColor, gcFunction);

            dstDataAddr[j] = (dstColor >> 16) & 0xff;
            dstDataAddr[j+1] = (dstColor >> 8) & 0xff;
            dstDataAddr[j+2] = dstColor & 0xff;
        }
    }
    
    AHardwareBuffer_unlock(dstHardwareBuffer, NULL);
    AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_fillRect(jshort x, jshort y,
                                                 jshort width, jshort height, 
                                                 jint color, jshort stride,
                                                 jlong data) 
{
    int ret;                                            
    uint8_t *dataAddr;
     
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)data;
    ret = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dataAddr);
    if (ret != 0)    
        return;

    if (!dataAddr) {
        printf("Error: NULL buffer address in fillRect\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    uint8_t rgba[4];
    unpackColor(color, rgba);

    int rowSize = width * 4;
    uint8_t *row = malloc(rowSize);
    if (!row) {
        printf("Error: Failed to allocate memory for row\n");
        return;
    }

    for (int i = 0; i < rowSize; i += 4) {
        memcpy(row + i, rgba, 4);
    }
    for (int16_t i = 0; i < height; i++) {
        memcpy(dataAddr + (x + (i + y) * stride) * 4, row, rowSize);
    }

    free(row);
    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_drawLine(jshort x0, jshort y0,
                                                 jshort x1, jshort y1, 
                                                 jint color, jshort lineWidth,
                                                 jshort stride, jlong data) 
{
    int ret;                                            
    uint8_t *dataAddr;
    
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)data;
    ret = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dataAddr);
    if (ret != 0)    
        return;


    if (!dataAddr) {
        printf("Error: NULL buffer address in drawLine\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    int dx =  abs(x1 - x0);
    int dy = -abs(y1 - y0);
    int8_t sx = x0 < x1 ? 1 : -1;
    int8_t sy = y0 < y1 ? 1 : -1;
    int e1 = dx + dy, e2;

    uint8_t rgba[4];
    unpackColor(color, rgba);

    int rowSize = lineWidth * 4;
    uint8_t *row = malloc(rowSize);
    if (!row) {
        printf("Error: Failed to allocate memory for row\n");
        return;
    }

    for (int i = 0; i < rowSize; i += 4) {
        memcpy(row + i, rgba, 4);
    }

    while (true) {
        for (int16_t i = 0; i < lineWidth; i++) {
            memcpy(dataAddr + (x0 + (i + y0) * stride) * 4, row, rowSize);
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

    free(row);
    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_drawAlphaMaskedBitmap(jbyte foreRed, jbyte foreGreen,
                                                              jbyte foreBlue, jbyte backRed,
                                                              jbyte backGreen, jbyte backBlue,
                                                              jlong srcData, jshort srcStride,
                                                              jlong maskData, jshort maskStride,
                                                              jshort width, jshort height,
                                                              jshort stride, jlong dstData) 
{
    int ret;                                                         
    int *srcDataAddr;
    int *maskDataAddr;
    int *dstDataAddr;
    
    AHardwareBuffer *srcHardwareBuffer = (AHardwareBuffer *)srcData;
    ret = AHardwareBuffer_lock(srcHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&srcDataAddr);
    if (ret != 0)    
        return;
        
    AHardwareBuffer *maskHardwareBuffer = (AHardwareBuffer *)maskData;
    ret = AHardwareBuffer_lock(maskHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&maskDataAddr);
    if (ret != 0)    
        return;    
    
    AHardwareBuffer *dstHardwareBuffer = (AHardwareBuffer *)dstData;
    ret = AHardwareBuffer_lock(dstHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr);
    if (ret != 0)    
        return;    
        
    if (!srcDataAddr || !maskDataAddr || !dstDataAddr) {
        printf("Error: NULL buffer address in drawAlphaMaskedBitmap\n");
        AHardwareBuffer_unlock(dstHardwareBuffer, NULL);
        AHardwareBuffer_unlock(maskHardwareBuffer, NULL);
        AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
        return;
    }

    int foreColor = packColor(foreRed, foreGreen, foreBlue);
    int backColor = packColor(backRed, backGreen, backBlue);

    for (int16_t y = 0; y < height; y++) {
        int rowStart = y * stride;
        int srcStart = y * srcStride;
        int maskStart = y * maskStride;
        for (int16_t x = 0; x < width; x++) {
            int srcIdx = x + srcStart;
            int dstIdx = x + rowStart;
            int maskIdx = x + maskStart;
            dstDataAddr[dstIdx] = maskDataAddr[maskIdx] == WHITE ? (srcDataAddr[srcIdx] == WHITE ? foreColor : backColor) | 0xff000000 : 0x00000000;
        }
    }
    
    AHardwareBuffer_unlock(dstHardwareBuffer, NULL);
    AHardwareBuffer_unlock(maskHardwareBuffer, NULL);
    AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Pixmap_toBitmap(JNIEnv *env, jclass obj, 
                                               jshort colorStride, long colorData,
                                               jshort maskStride, long maskData, 
                                               jobject bitmap) 
{
    int ret;                                          
    char *colorDataAddr;
    char *maskDataAddr;
    
    AHardwareBuffer *colorHardwareBuffer = (AHardwareBuffer *)colorData;
    ret = AHardwareBuffer_lock(colorHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&colorDataAddr);
    if (ret != 0)    
        return;
        
    if (!colorDataAddr) {
        printf("Error: NULL color data address in toBitmap\n");
        AHardwareBuffer_unlock(colorHardwareBuffer, NULL);
        return;
    }   
        
    AHardwareBuffer *maskHardwareBuffer = (AHardwareBuffer *)maskData;
    if (maskHardwareBuffer != 0) {
        ret = AHardwareBuffer_lock(maskHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&maskDataAddr);
        if (ret != 0)    
            return;    
    }
    else {
        maskDataAddr = NULL;
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
    
    for (int16_t y = 0; y < info.height; y++) {
        int srcStart = y * colorStride;
        int dstStart = y * info.width;
        int maskStart = y * maskStride;
        for (int16_t x = 0; x < info.width; x++) {
            int srcIdx = (x + srcStart) * 4;
            int dstIdx = (x + dstStart) * 4;
            int maskIdx = (x + maskStart) * 4;
            pixels[dstIdx + 2] = colorDataAddr[srcIdx + 0];
            pixels[dstIdx + 1] = colorDataAddr[srcIdx + 1];
            pixels[dstIdx + 0] = colorDataAddr[srcIdx + 2];
            pixels[dstIdx + 3] = maskDataAddr ? maskDataAddr[maskIdx + 0] : colorDataAddr[srcIdx + 3];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    AHardwareBuffer_unlock(colorHardwareBuffer, NULL);
    if (maskHardwareBuffer != 0)
        AHardwareBuffer_unlock(maskHardwareBuffer, NULL);
}