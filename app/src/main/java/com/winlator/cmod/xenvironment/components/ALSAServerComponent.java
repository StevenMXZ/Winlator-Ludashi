package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.alsaserver.ALSAClientConnectionHandler;
import com.winlator.cmod.alsaserver.ALSARequestHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

/* loaded from: classes10.dex */
public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;

    public ALSAServerComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void start() {
        if (this.connector != null) {
            return;
        }
        this.connector = new XConnectorEpoll(this.socketConfig, new ALSAClientConnectionHandler(), new ALSARequestHandler());
        this.connector.setMultithreadedClients(true);
        this.connector.start();
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void stop() {
        if (this.connector != null) {
            this.connector.stop();
            this.connector = null;
        }
    }
}
