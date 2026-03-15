package com.winlator.cmod.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes10.dex */
public class MSLogFont {
    private int height = -11;
    private int width = 0;
    private int escapement = 0;
    private int orientation = 0;
    private int weight = 400;
    private byte italic = 0;
    private byte underline = 0;
    private byte strikeOut = 0;
    private byte charSet = 0;
    private byte outPrecision = 0;
    private byte clipPrecision = 0;
    private byte quality = 0;
    private byte pitchAndFamily = 34;
    private String faceName = "Tahoma";

    public int getHeight() {
        return this.height;
    }

    public MSLogFont setHeight(int height) {
        this.height = height;
        return this;
    }

    public int getWidth() {
        return this.width;
    }

    public MSLogFont setWidth(int width) {
        this.width = width;
        return this;
    }

    public int getEscapement() {
        return this.escapement;
    }

    public MSLogFont setEscapement(int escapement) {
        this.escapement = escapement;
        return this;
    }

    public int getOrientation() {
        return this.orientation;
    }

    public MSLogFont setOrientation(int orientation) {
        this.orientation = orientation;
        return this;
    }

    public int getWeight() {
        return this.weight;
    }

    public MSLogFont setWeight(int weight) {
        this.weight = weight;
        return this;
    }

    public byte getItalic() {
        return this.italic;
    }

    public MSLogFont setItalic(byte italic) {
        this.italic = italic;
        return this;
    }

    public byte getUnderline() {
        return this.underline;
    }

    public MSLogFont setUnderline(byte underline) {
        this.underline = underline;
        return this;
    }

    public byte getStrikeOut() {
        return this.strikeOut;
    }

    public MSLogFont setStrikeOut(byte strikeOut) {
        this.strikeOut = strikeOut;
        return this;
    }

    public byte getCharSet() {
        return this.charSet;
    }

    public MSLogFont setCharSet(byte charSet) {
        this.charSet = charSet;
        return this;
    }

    public byte getOutPrecision() {
        return this.outPrecision;
    }

    public MSLogFont setOutPrecision(byte outPrecision) {
        this.outPrecision = outPrecision;
        return this;
    }

    public byte getClipPrecision() {
        return this.clipPrecision;
    }

    public MSLogFont setClipPrecision(byte clipPrecision) {
        this.clipPrecision = clipPrecision;
        return this;
    }

    public byte getQuality() {
        return this.quality;
    }

    public MSLogFont setQuality(byte quality) {
        this.quality = quality;
        return this;
    }

    public byte getPitchAndFamily() {
        return this.pitchAndFamily;
    }

    public MSLogFont setPitchAndFamily(byte pitchAndFamily) {
        this.pitchAndFamily = pitchAndFamily;
        return this;
    }

    public String getFaceName() {
        return this.faceName;
    }

    public MSLogFont setFaceName(String faceName) {
        this.faceName = faceName;
        return this;
    }

    public byte[] toByteArray() {
        ByteBuffer data = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(this.height);
        data.putInt(this.width);
        data.putInt(this.escapement);
        data.putInt(this.orientation);
        data.putInt(this.weight);
        data.put(this.italic);
        data.put(this.underline);
        data.put(this.strikeOut);
        data.put(this.charSet);
        data.put(this.outPrecision);
        data.put(this.clipPrecision);
        data.put(this.quality);
        data.put(this.pitchAndFamily);
        for (int i = 0; i < this.faceName.length(); i++) {
            data.putChar(this.faceName.charAt(i));
        }
        return data.array();
    }
}
