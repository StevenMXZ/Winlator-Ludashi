package com.winlator.cmod.xconnector;

public interface ConnectionHandler {
    default ConnectedClient newConnectedClient(long clientPtr, int fd) {
        return new ConnectedClient(clientPtr, fd);
    }

    void handleConnectionShutdown(ConnectedClient client);

    void handleNewConnection(ConnectedClient client);
}