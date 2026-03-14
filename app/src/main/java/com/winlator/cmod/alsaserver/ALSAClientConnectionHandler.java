package com.winlator.cmod.alsaserver;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;

/* loaded from: classes7.dex */
public class ALSAClientConnectionHandler implements ConnectionHandler {
    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(new ALSAClient());
    }

    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleConnectionShutdown(Client client) {
        ((ALSAClient) client.getTag()).release();
    }
}
