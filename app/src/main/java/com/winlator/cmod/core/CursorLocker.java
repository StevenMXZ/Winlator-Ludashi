package com.winlator.cmod.core;

import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.xserver.XServer;
import java.util.Timer;
import java.util.TimerTask;

/* loaded from: classes10.dex */
public class CursorLocker extends TimerTask {
    private short maxDistance;
    private final XServer xServer;
    private float damping = 0.25f;
    private boolean enabled = true;
    private final Object pauseLock = new Object();

    public CursorLocker(XServer xServer) {
        this.xServer = xServer;
        this.maxDistance = (short) (xServer.screenInfo.width * 0.05f);
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(this, 0L, 16L);
    }

    public short getMaxDistance() {
        return this.maxDistance;
    }

    public void setMaxDistance(short maxDistance) {
        this.maxDistance = maxDistance;
    }

    public float getDamping() {
        return this.damping;
    }

    public void setDamping(float damping) {
        this.damping = damping;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled) {
            synchronized (this.pauseLock) {
                this.enabled = true;
                this.pauseLock.notifyAll();
            }
            return;
        }
        this.enabled = enabled;
    }

    @Override // java.util.TimerTask, java.lang.Runnable
    public void run() {
        synchronized (this.pauseLock) {
            if (!this.enabled) {
                try {
                    this.pauseLock.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        short x = (short) Mathf.clamp((int) this.xServer.pointer.getX(), -this.maxDistance, this.xServer.screenInfo.width + this.maxDistance);
        short y = (short) Mathf.clamp((int) this.xServer.pointer.getY(), -this.maxDistance, this.xServer.screenInfo.height + this.maxDistance);
        if (x < 0) {
            this.xServer.pointer.setX((short) Math.ceil(x * this.damping));
        } else if (x >= this.xServer.screenInfo.width) {
            this.xServer.pointer.setX((short) Math.floor(this.xServer.screenInfo.width + ((x - this.xServer.screenInfo.width) * this.damping)));
        }
        if (y < 0) {
            this.xServer.pointer.setY((short) Math.ceil(y * this.damping));
        } else if (y >= this.xServer.screenInfo.height) {
            this.xServer.pointer.setY((short) Math.floor(this.xServer.screenInfo.height + ((y - this.xServer.screenInfo.height) * this.damping)));
        }
    }
}
