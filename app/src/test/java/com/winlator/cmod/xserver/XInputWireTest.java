package com.winlator.cmod.xserver;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.events.XIRawEvent;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(shadows = XInputWireTest.StreamShadow.class)
public class XInputWireTest {
    @org.robolectric.annotation.Implements(value = XOutputStream.class, isInAndroidSdk = false)
    public static class StreamShadow {
        @org.robolectric.annotation.Implementation protected static void __staticInitializer__() {}
        @org.robolectric.annotation.Implementation protected long nativeAllocate(int fd, int capacity) { return 1; }
    }
    private static final class Output extends XOutputStream {
        private final ByteBuffer captured = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        Output() { super(-1, 128); }
        @Override public XStreamLock lock() { return () -> {}; }
        @Override public void writeByte(byte value) { captured.put(value); }
        @Override public void writeShort(short value) { captured.putShort(value); }
        @Override public void writeInt(int value) { captured.putInt(value); }
        @Override public void writeLong(long value) { captured.putLong(value); }
        @Override public void writePad(int length) { for (int i = 0; i < length; ++i) captured.put((byte)0); }
        ByteBuffer packet() { ByteBuffer result = captured.duplicate().order(captured.order()); result.flip(); return result; }
    }

    @Test public void signedFixedPointKeepsFractionalBitsInBothDirections() {
        Output output = new Output();
        for (double value : new double[]{-1.25, -0.5, 0.75, 1.5}) output.writeFP3232(value);
        ByteBuffer packet = output.packet();
        for (double expected : new double[]{-1.25, -0.5, 0.75, 1.5}) {
            double actual = packet.getInt() + Integer.toUnsignedLong(packet.getInt()) / 4294967296.0;
            assertEquals(expected, actual, 0);
        }
    }

    @Test public void rawMotionHasConsistentLengthDevicesMaskAndBothValueSets() throws Exception {
        Output output = new Output();
        XIRawEvent.motion(123, 5, -3).send((short)7, output);
        ByteBuffer packet = output.packet();
        assertEquals(68, packet.remaining());
        assertEquals(35, packet.get(0));
        assertEquals(7, packet.getShort(2));
        assertEquals(9, packet.getInt(4));
        assertEquals(17, packet.getShort(8));
        assertEquals(2, packet.getShort(10));
        assertEquals(123, packet.getInt(12));
        assertEquals(4, packet.getShort(20));
        assertEquals(1, packet.getShort(22));
        assertEquals(3, packet.getInt(32));
        packet.position(36);
        for (int expected : new int[]{5, -3, 5, -3}) {
            assertEquals(expected, packet.getInt());
            assertEquals(0, packet.getInt());
        }
    }
}
