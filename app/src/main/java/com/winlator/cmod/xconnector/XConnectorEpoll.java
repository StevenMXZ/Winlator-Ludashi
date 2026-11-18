package com.winlator.cmod.xconnector;

import androidx.annotation.Keep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dalvik.annotation.optimization.CriticalNative;

public class XConnectorEpoll {
    private final ConnectionHandler connectionHandler;
    private final RequestHandler requestHandler;
    private final ArrayList<ConnectedClient> connectedClients = new ArrayList<>();
    private boolean canReceiveAncillaryMessages = false;
    private boolean multithreadedClients = false;
    private int initialInputBufferCapacity = 64;
    private int initialOutputBufferCapacity = 64;
    private long nativePtr;

    static {
        System.loadLibrary("winlator");
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;

        nativePtr = nativeAllocate(socketConfig.path);
        if (nativePtr == 0) throw new RuntimeException("Failed to allocate XConnectorEpoll.");
    }

    public void start() {
        if (nativePtr != 0) startEpollThread(nativePtr, multithreadedClients);
    }

    public void destroy() {
        if (nativePtr != 0) {
            stopEpollThread(nativePtr);
            destroy(nativePtr);
            nativePtr = 0;
        }
    }

    @Keep
    private void handleConnectionShutdown(Object tag) {
        ConnectedClient client = (ConnectedClient)tag;
        connectionHandler.handleConnectionShutdown(client);
        client.destroy();

        synchronized (connectedClients) {
            connectedClients.remove(client);
        }
    }

    @Keep
    private Object handleNewConnection(long clientPtr, int fd) {
        ConnectedClient client = connectionHandler.newConnectedClient(clientPtr, fd);
        client.createInputStream(initialInputBufferCapacity);
        client.createOutputStream(initialOutputBufferCapacity);
        connectionHandler.handleNewConnection(client);

        synchronized (connectedClients) {
            connectedClients.add(client);
        }
        return client;
    }

    @Keep
    private void handleExistingConnection(Object tag) {
        ConnectedClient client = (ConnectedClient)tag;
        XInputStream inputStream = client.getInputStream();

        try {
            if (inputStream != null) {
                if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;
                    while (requestHandler.handleRequest(client)) activePosition = inputStream.getActivePosition();
                    inputStream.setActivePosition(activePosition);
                }
                else killConnection(client);
            }
            else requestHandler.handleRequest(client);
        }
        catch (IOException e) {
            killConnection(client);
        }
    }

    @Keep
    private void killAllConnections() {
        while (!connectedClients.isEmpty()) killConnection(connectedClients.remove(0));
    }

    public List<ConnectedClient> getClients() {
        return Collections.unmodifiableList(connectedClients);
    }

    public ConnectedClient getClientWidthFd(int fd) {
        synchronized (connectedClients) {
            for (ConnectedClient client : connectedClients) {
                if (client.fd == fd) return client;
            }
        }
        return null;
    }

    public void killConnection(ConnectedClient client) {
        if (nativePtr != 0) killConnection(nativePtr, client.nativePtr);
    }

    public boolean isCanReceiveAncillaryMessages() {
        return canReceiveAncillaryMessages;
    }

    public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
        this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
    }

    public int getInitialInputBufferCapacity() {
        return initialInputBufferCapacity;
    }

    public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
        this.initialInputBufferCapacity = initialInputBufferCapacity;
    }

    public int getInitialOutputBufferCapacity() {
        return initialOutputBufferCapacity;
    }

    public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
        this.initialOutputBufferCapacity = initialOutputBufferCapacity;
    }

    public boolean isMultithreadedClients() {
        return multithreadedClients;
    }

    public void setMultithreadedClients(boolean multithreadedClients) {
        this.multithreadedClients = multithreadedClients;
    }

    @CriticalNative
    public static native void closeFd(int fd);

    private native long nativeAllocate(String sockPath);

    private static native void destroy(long nativePtr);

    private static native void startEpollThread(long nativePtr, boolean multithreadedClients);

    private static native void stopEpollThread(long nativePtr);

    private static native void killConnection(long connectorPtr, long clientPtr);
}