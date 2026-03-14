package com.winlator.cmod.xserver;

import android.util.SparseArray;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.events.SelectionClear;

/* loaded from: classes11.dex */
public class SelectionManager implements XResourceManager.OnResourceLifecycleListener {
    private final SparseArray<Selection> selections = new SparseArray<>();

    public static class Selection {
        private XClient client;
        public Window owner;
    }

    public SelectionManager(WindowManager windowManager) {
        windowManager.addOnResourceLifecycleListener(this);
    }

    public void setSelection(int atom, Window owner, XClient client, int timestamp) {
        Selection selection = getSelection(atom);
        if (selection.owner != null && (owner == null || selection.client != client)) {
            selection.client.sendEvent(new SelectionClear(timestamp, owner, atom));
        }
        selection.owner = owner;
        selection.client = client;
    }

    public Selection getSelection(int atom) {
        Selection selection = this.selections.get(atom);
        if (selection != null) {
            return selection;
        }
        Selection selection2 = new Selection();
        this.selections.put(atom, selection2);
        return selection2;
    }

    @Override // com.winlator.cmod.xserver.XResourceManager.OnResourceLifecycleListener
    public void onFreeResource(XResource resource) {
        for (int i = 0; i < this.selections.size(); i++) {
            Selection selection = this.selections.valueAt(i);
            if (selection.owner == resource) {
                selection.owner = null;
            }
        }
    }
}
