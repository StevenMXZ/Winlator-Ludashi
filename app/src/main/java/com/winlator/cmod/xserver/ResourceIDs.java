package com.winlator.cmod.xserver;

import androidx.collection.ArraySet;
import java.util.Iterator;

/* loaded from: classes11.dex */
public class ResourceIDs {
    private final ArraySet<Integer> idBases = new ArraySet<>();
    public final int idMask;

    public ResourceIDs(int maxClients) {
        int clientsBits = 32 - Integer.numberOfLeadingZeros(maxClients);
        int base = 29 - (Integer.bitCount(maxClients) == 1 ? clientsBits - 1 : clientsBits);
        this.idMask = (1 << base) - 1;
        for (int i = 1; i < maxClients; i++) {
            this.idBases.add(Integer.valueOf(i << base));
        }
    }

    public synchronized Integer get() {
        if (this.idBases.isEmpty()) {
            return -1;
        }
        Iterator<Integer> iter = this.idBases.iterator();
        int idBase = iter.next().intValue();
        iter.remove();
        return Integer.valueOf(idBase);
    }

    public boolean isInInterval(int value, int idBase) {
        return (this.idMask | value) == (this.idMask | idBase);
    }

    public synchronized void free(Integer idBase) {
        this.idBases.add(idBase);
    }
}
