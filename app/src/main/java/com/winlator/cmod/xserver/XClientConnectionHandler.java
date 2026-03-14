package com.winlator.cmod.xserver;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;

/* loaded from: classes11.dex */
public class XClientConnectionHandler implements ConnectionHandler {
    private final XServer xServer;

    public XClientConnectionHandler(XServer xServer) {
        this.xServer = xServer;
    }

    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(new XClient(this.xServer, client.getInputStream(), client.getOutputStream()));
    }

    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleConnectionShutdown(Client client) {
        ((XClient) client.getTag()).freeResources();
    }
}
