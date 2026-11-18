package com.winlator.cmod.stream.moonlight;

final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    static String encode(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = DIGITS[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = DIGITS[b[i] & 0xF];
        }
        return new String(out);
    }

    static byte[] decode(String s) {
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4) | Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return out;
    }
}
