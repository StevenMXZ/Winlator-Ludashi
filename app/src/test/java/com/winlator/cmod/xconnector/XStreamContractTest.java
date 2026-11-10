package com.winlator.cmod.xconnector;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {XStreamContractTest.OutputShadow.class, XStreamContractTest.InputShadow.class})
public class XStreamContractTest {
    private static ByteArrayOutputStream captured;
    @Implements(value = XOutputStream.class, isInAndroidSdk = false)
    public static class OutputShadow {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected long nativeAllocate(int fd, int capacity) { captured = new ByteArrayOutputStream(); return 1; }
        @Implementation protected static void writeBytes(long pointer, byte[] data, int offset, int length) { captured.write(data, offset, length); }
        @Implementation protected static void writeByteBuffer(long pointer, ByteBuffer data, int offset, int length) {
            byte[] bytes = new byte[length];
            ByteBuffer copy = data.duplicate(); copy.position(offset); copy.get(bytes);
            captured.write(bytes, 0, length);
        }
    }
    @Implements(value = XInputStream.class, isInAndroidSdk = false)
    public static class InputShadow {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected long nativeAllocate(int fd, int capacity) { return 1; }
    }
    @Test public void writesRangesAndConsumesBothHeapAndDirectBuffers() {
        XOutputStream output = new XOutputStream(-1, 8);
        output.write(new byte[]{0, 1, 2, 3, 4, 5}, 3, 2);
        ByteBuffer heap = ByteBuffer.wrap(new byte[]{6, 7, 8, 9}); heap.position(1).limit(3);
        output.write(heap); assertEquals(3, heap.position());
        ByteBuffer direct = ByteBuffer.allocateDirect(5); direct.put(new byte[]{10, 11, 12, 13, 14});
        direct.position(2).limit(4); output.write(direct); assertEquals(4, direct.position());
        assertArrayEquals(new byte[]{3, 4, 7, 8, 12, 13}, captured.toByteArray());
    }
    @Test public void emptyInputRejectsReadsBeforeEnteringNativeCode() {
        XInputStream input = new XInputStream(-1, 8);
        assertEquals(0, input.available());
        assertThrows(BufferUnderflowException.class, input::readInt);
        assertThrows(BufferUnderflowException.class, () -> input.readByteBuffer(1));
        assertThrows(BufferUnderflowException.class, () -> input.skip(-1));
    }
}
