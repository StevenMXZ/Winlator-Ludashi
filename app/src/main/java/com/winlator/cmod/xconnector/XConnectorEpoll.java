package com.winlator.cmod.xconnector;

import android.util.SparseArray;
import java.io.IOException;
import java.nio.ByteBuffer;

/* loaded from: classes13.dex */
public class XConnectorEpoll implements Runnable {
    private final ConnectionHandler connectionHandler;
    private final int epollFd;
    private Thread epollThread;
    private final RequestHandler requestHandler;
    private final int serverFd;
    private final int shutdownFd;
    private boolean running = false;
    private boolean multithreadedClients = false;
    private boolean canReceiveAncillaryMessages = false;
    private int initialInputBufferCapacity = 4096;
    private int initialOutputBufferCapacity = 4096;
    private final SparseArray<Client> connectedClients = new SparseArray<>();

    private native boolean addFdToEpoll(int i, int i2);

    public static native void closeFd(int i);

    private native int createAFUnixSocket(String str);

    private native int createEpollFd();

    private native int createEventFd();

    private native boolean doEpollIndefinitely(int i, int i2, boolean z);

    private native void removeFdFromEpoll(int i, int i2);

    private native boolean waitForSocketRead(int i, int i2);

    static {
        System.loadLibrary("winlator");
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;
        this.serverFd = createAFUnixSocket(socketConfig.path);
        if (this.serverFd < 0) {
            throw new RuntimeException("Failed to create an AF_UNIX socket.");
        }
        this.epollFd = createEpollFd();
        if (this.epollFd < 0) {
            closeFd(this.serverFd);
            throw new RuntimeException("Failed to create epoll fd.");
        }
        if (!addFdToEpoll(this.epollFd, this.serverFd)) {
            closeFd(this.serverFd);
            closeFd(this.epollFd);
            throw new RuntimeException("Failed to add server fd to epoll.");
        }
        this.shutdownFd = createEventFd();
        if (!addFdToEpoll(this.epollFd, this.shutdownFd)) {
            closeFd(this.serverFd);
            closeFd(this.shutdownFd);
            closeFd(this.epollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }
        this.epollThread = new Thread(this);
    }

    public synchronized void start() {
        if (!this.running && this.epollThread != null) {
            this.running = true;
            this.epollThread.start();
        }
    }

    public synchronized void stop() {
        if (this.running && this.epollThread != null) {
            this.running = false;
            requestShutdown();
            while (this.epollThread.isAlive()) {
                try {
                    this.epollThread.join();
                } catch (InterruptedException e) {
                }
            }
            this.epollThread = null;
        }
    }

    @Override // java.lang.Runnable
    public void run() {
        while (this.running && doEpollIndefinitely(this.epollFd, this.serverFd, !this.multithreadedClients)) {
        }
        shutdown();
    }

    private void handleNewConnection(int fd) {
        final Client client = new Client(this, new ClientSocket(fd));
        client.connected = true;
        if (this.multithreadedClients) {
            client.shutdownFd = createEventFd();
            client.pollThread = new Thread(new Runnable() { // from class: com.winlator.cmod.xconnector.XConnectorEpoll$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    XConnectorEpoll.this.lambda$handleNewConnection$0(client);
                }
            });
            client.pollThread.start();
        } else {
            this.connectionHandler.handleNewConnection(client);
        }
        this.connectedClients.put(fd, client);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$handleNewConnection$0(Client client) {
        this.connectionHandler.handleNewConnection(client);
        while (client.connected && waitForSocketRead(client.clientSocket.fd, client.shutdownFd)) {
        }
    }

    private void handleExistingConnection(int fd) {
        Client client = this.connectedClients.get(fd);
        if (client == null) {
            return;
        }
        XInputStream inputStream = client.getInputStream();
        try {
            if (inputStream != null) {
                if (inputStream.readMoreData(this.canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;
                    while (this.running && this.requestHandler.handleRequest(client)) {
                        activePosition = inputStream.getActivePosition();
                    }
                    inputStream.setActivePosition(activePosition);
                    return;
                }
                killConnection(client);
                return;
            }
            this.requestHandler.handleRequest(client);
        } catch (IOException e) {
            killConnection(client);
        }
    }

    public Client getClient(int fd) {
        return this.connectedClients.get(fd);
    }

    public void killConnection(Client client) {
        client.connected = false;
        this.connectionHandler.handleConnectionShutdown(client);
        if (this.multithreadedClients) {
            if (Thread.currentThread() != client.pollThread) {
                client.requestShutdown();
                while (client.pollThread.isAlive()) {
                    try {
                        client.pollThread.join();
                    } catch (InterruptedException e) {
                    }
                }
                client.pollThread = null;
            }
            closeFd(client.shutdownFd);
        } else {
            removeFdFromEpoll(this.epollFd, client.clientSocket.fd);
        }
        closeFd(client.clientSocket.fd);
        this.connectedClients.remove(client.clientSocket.fd);
    }

    private void shutdown() {
        while (this.connectedClients.size() > 0) {
            Client client = this.connectedClients.valueAt(this.connectedClients.size() - 1);
            killConnection(client);
        }
        removeFdFromEpoll(this.epollFd, this.serverFd);
        removeFdFromEpoll(this.epollFd, this.shutdownFd);
        closeFd(this.serverFd);
        closeFd(this.shutdownFd);
        closeFd(this.epollFd);
    }

    public int getInitialInputBufferCapacity() {
        return this.initialInputBufferCapacity;
    }

    public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
        this.initialInputBufferCapacity = initialInputBufferCapacity;
    }

    public int getInitialOutputBufferCapacity() {
        return this.initialOutputBufferCapacity;
    }

    public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
        this.initialOutputBufferCapacity = initialOutputBufferCapacity;
    }

    public boolean isMultithreadedClients() {
        return this.multithreadedClients;
    }

    public void setMultithreadedClients(boolean multithreadedClients) {
        this.multithreadedClients = multithreadedClients;
    }

    public boolean isCanReceiveAncillaryMessages() {
        return this.canReceiveAncillaryMessages;
    }

    public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
        this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
    }

    private void requestShutdown() {
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(8);
            data.asLongBuffer().put(1L);
            new ClientSocket(this.shutdownFd).write(data);
        } catch (IOException e) {
        }
    }
}
