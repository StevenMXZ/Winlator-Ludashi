package com.winlator.cmod.xserver;

import java.util.ArrayList;

/* loaded from: classes11.dex */
public abstract class XResourceManager {
    private final ArrayList<OnResourceLifecycleListener> onResourceLifecycleListeners = new ArrayList<>();

    public interface OnResourceLifecycleListener {
        default void onCreateResource(XResource resource) {
        }

        default void onFreeResource(XResource resource) {
        }
    }

    public void addOnResourceLifecycleListener(OnResourceLifecycleListener OnResourceLifecycleListener2) {
        this.onResourceLifecycleListeners.add(OnResourceLifecycleListener2);
    }

    public void removeOnResourceLifecycleListener(OnResourceLifecycleListener OnResourceLifecycleListener2) {
        this.onResourceLifecycleListeners.remove(OnResourceLifecycleListener2);
    }

    public void triggerOnCreateResourceListener(XResource resource) {
        for (int i = this.onResourceLifecycleListeners.size() - 1; i >= 0; i--) {
            this.onResourceLifecycleListeners.get(i).onCreateResource(resource);
        }
    }

    public void triggerOnFreeResourceListener(XResource resource) {
        for (int i = this.onResourceLifecycleListeners.size() - 1; i >= 0; i--) {
            this.onResourceLifecycleListeners.get(i).onFreeResource(resource);
        }
    }
}
