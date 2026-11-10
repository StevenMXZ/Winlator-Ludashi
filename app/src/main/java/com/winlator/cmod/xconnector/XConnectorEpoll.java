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
        registerNatives();
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;

        nativePtr = nativeAllocate(socketConfig.path);
        if (nativePtr == 0) throw new RuntimeException("Failed to allocate XConnectorEpoll.");
    }

    public synchronized void start() {
        if (nativePtr != 0) startEpollThread(nativePtr, multithreadedClients);
    }

    public void destroy() {
        final long pointer;
        synchronized (this) { pointer = nativePtr; nativePtr = 0; }
        // Joining outside the Java monitor lets callbacks finish without deadlock.
        if (pointer != 0) destroy(pointer);
    }

    @Keep
    private void handleConnectionShutdown(Object tag) {
        ConnectedClient client = (ConnectedClient)tag;
        try { connectionHandler.handleConnectionShutdown(client); }
        finally {
            client.destroy();
            synchronized (connectedClients) { connectedClients.remove(client); }
        }
    }

    @Keep
    private Object handleNewConnection(long clientPtr, int fd) {
        ConnectedClient client = connectionHandler.newConnectedClient(clientPtr, fd);
        try {
            client.createInputStream(initialInputBufferCapacity);
            client.createOutputStream(initialOutputBufferCapacity);
            synchronized (connectedClients) { connectedClients.add(client); }
            connectionHandler.handleNewConnection(client);
            return client;
        } catch (RuntimeException | Error error) {
            handleConnectionShutdown(client);
            throw error;
        }
    }

    @Keep
    private void handleExistingConnection(Object tag) {
        ConnectedClient client = (ConnectedClient)tag;
        XInputStream inputStream = client.getInputStream();

        try {
            if (inputStream != null) {
                if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;
                    while (requestHandler.handleRequest(client)) {
                        int position = inputStream.getActivePosition();
                        if (position <= activePosition) throw new IOException("Request made no progress");
                        activePosition = position;
                    }
                    inputStream.setActivePosition(activePosition);
                }
                else killConnection(client);
            }
            else requestHandler.handleRequest(client);
        }
        catch (IOException | RuntimeException e) {
            killConnection(client);
        }
    }

    public List<ConnectedClient> getClients() {
        synchronized (connectedClients) { return Collections.unmodifiableList(new ArrayList<>(connectedClients)); }
    }

    public ConnectedClient getClientWidthFd(int fd) {
        synchronized (connectedClients) {
            for (ConnectedClient client : connectedClients) {
                if (client.fd == fd) return client;
            }
        }
        return null;
    }

    public synchronized void killConnection(ConnectedClient client) {
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

    private static native void registerNatives();

    private static native void killConnection(long connectorPtr, long clientPtr);
}