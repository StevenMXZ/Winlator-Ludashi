package com.winlator.cmod.xserver;

import java.util.Iterator;

/* loaded from: classes11.dex */
public class Bitmask implements Iterable<Integer> {
    private int bits;

    public Bitmask() {
        this.bits = 0;
    }

    public Bitmask(int bits) {
        this.bits = 0;
        this.bits = bits;
    }

    public boolean isSet(int flag) {
        return (this.bits & flag) != 0;
    }

    public boolean intersects(Bitmask mask) {
        return (mask.bits & this.bits) != 0;
    }

    public void set(int flag) {
        this.bits |= flag;
    }

    public void set(int flag, boolean value) {
        if (value) {
            set(flag);
        } else {
            unset(flag);
        }
    }

    public void unset(int flag) {
        this.bits &= ~flag;
    }

    public boolean isEmpty() {
        return this.bits == 0;
    }

    public int getBits() {
        return this.bits;
    }

    public void join(Bitmask mask) {
        this.bits = mask.bits | this.bits;
    }

    @Override // java.lang.Iterable
    public Iterator<Integer> iterator() {
        final int[] bits = {this.bits};
        return new Iterator<Integer>() { // from class: com.winlator.cmod.xserver.Bitmask.1
            @Override // java.util.Iterator
            public boolean hasNext() {
                return bits[0] != 0;
            }

            /* JADX WARN: Can't rename method to resolve collision */
            @Override // java.util.Iterator
            public Integer next() {
                int index = Integer.lowestOneBit(bits[0]);
                int[] iArr = bits;
                iArr[0] = iArr[0] & (~index);
                return Integer.valueOf(index);
            }
        };
    }

    public int hashCode() {
        return this.bits;
    }
}
