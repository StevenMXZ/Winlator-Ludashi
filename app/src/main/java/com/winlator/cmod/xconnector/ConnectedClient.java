package com.winlator.cmod.xconnector;

public class ConnectedClient {
    protected final long nativePtr;
    public final int fd;
    protected XInputStream inputStream;
    protected XOutputStream outputStream;
    private Object tag;

    public ConnectedClient(long nativePtr, int fd) {
        this.nativePtr = nativePtr;
        this.fd = fd;
    }

    public void createInputStream(int initialInputBufferCapacity) {
        if (inputStream == null && initialInputBufferCapacity > 0) {
            inputStream = new XInputStream(fd, initialInputBufferCapacity);
        }
    }

    public void createOutputStream(int initialOutputBufferCapacity) {
        if (outputStream == null && initialOutputBufferCapacity > 0) {
            outputStream = new XOutputStream(fd, initialOutputBufferCapacity);
        }
    }

    public XInputStream getInputStream() {
        return inputStream;
    }

    public XOutputStream getOutputStream() {
        return outputStream;
    }

    public Object getTag() {
        return tag;
    }

    public void setTag(Object tag) {
        this.tag = tag;
    }

    public void destroy() {
        if (inputStream != null) {
            inputStream.destroy();
            inputStream = null;
        }

        if (outputStream != null) {
            outputStream.destroy();
            outputStream = null;
        }
    }
}