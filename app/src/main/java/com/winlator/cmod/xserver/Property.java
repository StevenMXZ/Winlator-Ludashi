package com.winlator.cmod.xserver;

import com.winlator.cmod.core.ArrayUtils;
import com.winlator.cmod.core.StringUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/* loaded from: classes11.dex */
public class Property {
    public ByteBuffer data;
    public final Format format;
    public final int name;
    public final int type;

    public enum Mode {
        REPLACE,
        PREPEND,
        APPEND
    }

    public enum Format {
        BYTE_ARRAY(8),
        SHORT_ARRAY(16),
        INT_ARRAY(32);

        public final byte value;

        Format(int value) {
            this.value = (byte) value;
        }

        public static Format valueOf(int format) {
            switch (format) {
                case 8:
                    return BYTE_ARRAY;
                case 16:
                    return SHORT_ARRAY;
                case 32:
                    return INT_ARRAY;
                default:
                    return null;
            }
        }
    }

    public Property(int name, int type, Format format, byte[] data) {
        this.name = name;
        this.type = type;
        this.format = format;
        replace(data);
    }

    public void replace(byte[] data) {
        this.data = ByteBuffer.wrap(data != null ? data : new byte[0]).order(ByteOrder.LITTLE_ENDIAN);
    }

    public void prepend(byte[] values) {
        replace(ArrayUtils.concat(values, this.data.array()));
    }

    public void append(byte[] values) {
        replace(ArrayUtils.concat(this.data.array(), values));
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public String toString() {
        char c;
        String type = Atom.getName(this.type);
        this.data.rewind();
        switch (type.hashCode()) {
            case -1838656495:
                if (type.equals("STRING")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            case 2019665:
                if (type.equals("ATOM")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 265876607:
                if (type.equals("UTF8_STRING")) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        switch (c) {
            case 0:
                return StringUtils.fromANSIString(this.data.array(), StandardCharsets.UTF_8);
            case 1:
                return StringUtils.fromANSIString(this.data.array(), XServer.LATIN1_CHARSET);
            case 2:
                return Atom.getName(this.data.getInt(0));
            default:
                StringBuilder sb = new StringBuilder();
                int size = this.data.capacity() / (this.format.value >> 3);
                for (int i = 0; i < size; i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    switch (this.format) {
                        case BYTE_ARRAY:
                            sb.append((int) this.data.get());
                            break;
                        case SHORT_ARRAY:
                            sb.append((int) this.data.getShort());
                            break;
                        case INT_ARRAY:
                            sb.append(this.data.getInt());
                            break;
                    }
                }
                this.data.rewind();
                return sb.toString();
        }
    }

    public int getInt(int index) {
        return this.data.getInt(index * 4);
    }

    public long getLong(int index) {
        return this.data.getLong(index * 8);
    }

    public String nameAsString() {
        return Atom.getName(this.name);
    }
}
