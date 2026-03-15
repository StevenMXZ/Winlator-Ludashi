package com.winlator.cmod.xconnector;

/* loaded from: classes13.dex */
public interface ConnectionHandler {
    void handleConnectionShutdown(Client client);

    void handleNewConnection(Client client);
}
