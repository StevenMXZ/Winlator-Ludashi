package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.XClientConnectionHandler;
import com.winlator.cmod.xserver.XClientRequestHandler;
import com.winlator.cmod.xserver.XServer;

/* loaded from: classes10.dex */
public class XServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;
    private final XServer xServer;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void start() {
        if (this.connector != null) {
            return;
        }
        this.connector = new XConnectorEpoll(this.socketConfig, new XClientConnectionHandler(this.xServer), new XClientRequestHandler());
        this.connector.setInitialInputBufferCapacity(262144);
        this.connector.setCanReceiveAncillaryMessages(true);
        this.connector.start();
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void stop() {
        if (this.connector != null) {
            this.connector.stop();
            this.connector = null;
        }
    }

    public XServer getXServer() {
        return this.xServer;
    }
}
