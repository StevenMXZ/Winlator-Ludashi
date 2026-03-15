package com.winlator.cmod.renderer;

import android.opengl.GLES20;
import com.winlator.cmod.XrActivity;
import com.winlator.cmod.xserver.Drawable;
import java.nio.ByteBuffer;
import org.apache.commons.compress.archivers.tar.TarConstants;

/* loaded from: classes12.dex */
public class Texture {
    protected int textureId = 0;
    private int wrapS = 33071;
    private int wrapT = 33071;
    private int magFilter = 9729;
    private int minFilter = 9729;
    protected int format = 32993;
    protected boolean needsUpdate = true;
    protected byte unpackAlignment = 4;

    public void allocateTexture(short width, short height, ByteBuffer data) {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        this.textureId = textureIds[0];
        GLES20.glActiveTexture(33984);
        GLES20.glPixelStorei(3317, 4);
        GLES20.glBindTexture(3553, this.textureId);
        if (data != null) {
            GLES20.glTexImage2D(3553, 0, this.format, width, height, 0, this.format, 5121, data);
        }
        GLES20.glTexParameteri(3553, 10242, this.wrapS);
        GLES20.glTexParameteri(3553, 10243, this.wrapT);
        GLES20.glTexParameteri(3553, TarConstants.DEFAULT_BLKSIZE, this.magFilter);
        GLES20.glTexParameteri(3553, 10241, this.minFilter);
        GLES20.glBindTexture(3553, 0);
    }

    public int getWrapS() {
        return this.wrapS;
    }

    public void setWrapS(int wrapS) {
        this.wrapS = wrapS;
    }

    public int getWrapT() {
        return this.wrapT;
    }

    public void setWrapT(int wrapT) {
        this.wrapT = wrapT;
    }

    public int getMagFilter() {
        return this.magFilter;
    }

    public void setMagFilter(int magFilter) {
        this.magFilter = magFilter;
    }

    public int getMinFilter() {
        return this.minFilter;
    }

    public void setMinFilter(int minFilter) {
        this.minFilter = minFilter;
    }

    public int getFormat() {
        return this.format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isNeedsUpdate() {
        return this.needsUpdate;
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        this.needsUpdate = needsUpdate;
    }

    public void updateFromDrawable(Drawable drawable) {
        ByteBuffer data = drawable.getData();
        if (data == null) {
            return;
        }
        if (!isAllocated()) {
            allocateTexture(drawable.width, drawable.height, data);
        } else if (this.needsUpdate) {
            GLES20.glBindTexture(3553, this.textureId);
            GLES20.glTexSubImage2D(3553, 0, 0, 0, drawable.width, drawable.height, this.format, 5121, data);
            GLES20.glBindTexture(3553, 0);
            this.needsUpdate = false;
        }
    }

    public boolean isAllocated() {
        return this.textureId > 0;
    }

    public int getTextureId() {
        return this.textureId;
    }

    public void copyFromFramebuffer(int framebuffer, short width, short height) {
        if (!isAllocated()) {
            allocateTexture(width, height, null);
        }
        GLES20.glBindFramebuffer(36160, framebuffer);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.textureId);
        GLES20.glCopyTexImage2D(3553, 0, 6408, 0, 0, width, height, 0);
        GLES20.glBindTexture(3553, 0);
        GLES20.glBindFramebuffer(36160, 0);
        if (XrActivity.isEnabled(null)) {
            XrActivity.getInstance().bindFramebuffer();
        }
    }

    public void destroy() {
        if (this.textureId > 0) {
            int[] textureIds = {this.textureId};
            GLES20.glDeleteTextures(textureIds.length, textureIds, 0);
            this.textureId = 0;
        }
    }

    protected void generateTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        this.textureId = textureIds[0];
    }

    protected void setTextureParameters() {
        GLES20.glTexParameteri(3553, 10242, this.wrapS);
        GLES20.glTexParameteri(3553, 10243, this.wrapT);
        GLES20.glTexParameteri(3553, TarConstants.DEFAULT_BLKSIZE, this.magFilter);
        GLES20.glTexParameteri(3553, 10241, this.minFilter);
    }
}
