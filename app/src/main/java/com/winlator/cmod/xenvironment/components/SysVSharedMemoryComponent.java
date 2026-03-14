package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.sysvshm.SysVSHMConnectionHandler;
import com.winlator.cmod.sysvshm.SysVSHMRequestHandler;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.SHMSegmentManager;
import com.winlator.cmod.xserver.XServer;

/* loaded from: classes10.dex */
public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;
    private final XServer xServer;

    public SysVSharedMemoryComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void start() {
        if (this.connector != null) {
            return;
        }
        this.sysVSharedMemory = new SysVSharedMemory();
        this.connector = new XConnectorEpoll(this.socketConfig, new SysVSHMConnectionHandler(this.sysVSharedMemory), new SysVSHMRequestHandler());
        this.connector.start();
        this.xServer.setSHMSegmentManager(new SHMSegmentManager(this.sysVSharedMemory));
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void stop() {
        if (this.connector != null) {
            this.connector.stop();
            this.connector = null;
        }
        this.sysVSharedMemory.deleteAll();
    }
}
