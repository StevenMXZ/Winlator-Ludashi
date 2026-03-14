package com.winlator.cmod.sysvshm;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;

/* loaded from: classes11.dex */
public class SysVSHMConnectionHandler implements ConnectionHandler {
    private final SysVSharedMemory sysVSharedMemory;

    public SysVSHMConnectionHandler(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(this.sysVSharedMemory);
    }

    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleConnectionShutdown(Client client) {
    }
}
