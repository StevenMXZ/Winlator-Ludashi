package com.winlator.cmod.alsaserver;

import com.winlator.cmod.sysvshm.SysVSharedMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes7.dex */
public class ALSAClient {
    private int bufferSize;
    private int frameBytes;
    private int position;
    private ByteBuffer sharedBuffer;
    private DataType dataType = DataType.U8;
    private byte channelCount = 2;
    private int sampleRate = 0;
    private boolean playing = false;
    private long streamPtr = 0;

    private native void close(long j);

    private native long create(int i, byte b, int i2, int i3);

    private native void flush(long j);

    private native void pause(long j);

    private native void start(long j);

    private native void stop(long j);

    private native int write(long j, ByteBuffer byteBuffer, int i);

    public enum DataType {
        U8(1),
        S16LE(2),
        S16BE(2),
        FLOATLE(4),
        FLOATBE(4);

        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte) byteCount;
        }
    }

    static {
        System.loadLibrary("winlator");
    }

    public void release() {
        if (this.sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(this.sharedBuffer, this.sharedBuffer.capacity());
            this.sharedBuffer = null;
        }
        stop(this.streamPtr);
        close(this.streamPtr);
        this.playing = false;
        this.streamPtr = 0L;
    }

    public void prepare() {
        this.position = 0;
        this.frameBytes = this.channelCount * this.dataType.byteCount;
        release();
        if (isValidBufferSize()) {
            this.streamPtr = create(this.dataType.ordinal(), this.channelCount, this.sampleRate, this.bufferSize);
            if (this.streamPtr > 0) {
                start();
            }
        }
    }

    public void start() {
        if (this.streamPtr > 0 && !this.playing) {
            start(this.streamPtr);
            this.playing = true;
        }
    }

    public void stop() {
        if (this.streamPtr > 0 && this.playing) {
            stop(this.streamPtr);
            this.playing = false;
        }
    }

    public void pause() {
        if (this.streamPtr > 0) {
            pause(this.streamPtr);
            this.playing = false;
        }
    }

    public void drain() {
        if (this.streamPtr > 0) {
            flush(this.streamPtr);
        }
    }

    public void writeDataToStream(ByteBuffer data) {
        if (this.dataType == DataType.S16LE || this.dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        } else if (this.dataType == DataType.S16BE || this.dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }
        if (this.playing) {
            int numFrames = data.limit() / this.frameBytes;
            int framesWritten = write(this.streamPtr, data, numFrames);
            if (framesWritten > 0) {
                this.position += framesWritten;
            }
            data.rewind();
        }
    }

    public int pointer() {
        return this.position;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannelCount(int channelCount) {
        this.channelCount = (byte) channelCount;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return this.sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        this.sharedBuffer = sharedBuffer;
    }

    public DataType getDataType() {
        return this.dataType;
    }

    public byte getChannelCount() {
        return this.channelCount;
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public int getBufferSize() {
        return this.bufferSize;
    }

    public int getBufferSizeInBytes() {
        return this.bufferSize * this.frameBytes;
    }

    private boolean isValidBufferSize() {
        return getBufferSizeInBytes() % this.frameBytes == 0 && this.bufferSize > 0;
    }

    public int computeLatencyMillis() {
        return (int) ((this.bufferSize / this.sampleRate) * 1000.0f);
    }
}
