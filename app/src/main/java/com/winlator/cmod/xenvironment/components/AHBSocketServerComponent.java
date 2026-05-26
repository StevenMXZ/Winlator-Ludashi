package com.winlator.cmod.xenvironment.components;

import android.util.Log;

import com.winlator.cmod.renderer.AHardwareBufferPool;
import com.winlator.cmod.renderer.VulkanRenderer;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Unix socket server that Wine's Vulkan WSI connects to for AHardwareBuffer
 * handshake and frame synchronization.
 *
 * <p>Protocol:
 * <ol>
 *   <li>Wine connects to the socket at {@code /usr/tmp/.ahb/AHB0}</li>
 *   <li>Android sends MSG_BUFFER messages (one per pool slot) with AHB handles
 *       transmitted as ancillary data via {@code AHardwareBuffer_sendHandleToUnixSocket}</li>
 *   <li>Wine sends MSG_PRESENT messages with {slot_index, acquire_fence_fd}</li>
 *   <li>Android sends MSG_RELEASE messages with {slot_index, release_fence_fd}</li>
 * </ol>
 *
 * <p>This component has zero compile-time imports from {@code com.winlator.cmod.xserver.*}.
 */
public class AHBSocketServerComponent extends EnvironmentComponent
        implements ConnectionHandler, RequestHandler {

    private static final String LOG_TAG = "AHBSocketServer";

    /** Socket path relative to imageFs root — Wine looks for this. */
    public static final String AHB_SOCKET_PATH = "/usr/tmp/.ahb/AHB0";

    // Message types (must match Wine-side vulkan_ahb.c)
    private static final byte MSG_BUFFER  = 3;  // Android → Wine: here's an AHB handle
    private static final byte MSG_PRESENT = 1;  // Wine → Android: frame ready
    private static final byte MSG_RELEASE = 2;  // Android → Wine: buffer reusable

    private final UnixSocketConfig socketConfig;
    private final DirectCompositorComponent compositor;
    private XConnectorEpoll connector;
    private Client wineClient;

    public AHBSocketServerComponent(UnixSocketConfig socketConfig,
                                    DirectCompositorComponent compositor) {
        this.socketConfig = socketConfig;
        this.compositor = compositor;
    }

    @Override
    public void start() {
        try {
            connector = new XConnectorEpoll(socketConfig, this, this);
            connector.setCanReceiveAncillaryMessages(true);
            // Don't use multithreaded clients — the native present receiver thread
            // handles all reads from the Wine client fd after connection.
            connector.setMultithreadedClients(false);
            connector.start();
            Log.i(LOG_TAG, "start: listening on " + socketConfig.path);
        } catch (Exception e) {
            Log.e(LOG_TAG, "start: failed to create socket server", e);
        }
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
        wineClient = null;
        Log.i(LOG_TAG, "stop: socket server shut down");
    }

    // ── ConnectionHandler ────────────────────────────────────────────────────

    @Override
    public void handleNewConnection(Client client) {
        Log.i(LOG_TAG, "handleNewConnection: Wine WSI connected (fd="
                + client.clientSocket.fd + ")");
        wineClient = client;

        // Send all AHB handles to Wine so it can import them as VkImages
        AHardwareBufferPool pool = compositor.getPool();
        if (pool == null) {
            Log.e(LOG_TAG, "handleNewConnection: pool is null, cannot send buffers");
            return;
        }

        for (int i = 0; i < pool.getCount(); i++) {
            long bufPtr = pool.getBufferPtr(i);
            if (bufPtr == 0) {
                Log.e(LOG_TAG, "handleNewConnection: buffer " + i + " is null");
                continue;
            }
            int result = AHardwareBufferPool.nativeSendBufferToSocket(bufPtr,
                    client.clientSocket.fd);
            if (result < 0) {
                Log.e(LOG_TAG, "handleNewConnection: failed to send buffer " + i);
            } else {
                Log.i(LOG_TAG, "handleNewConnection: sent buffer " + i + " to Wine");
            }
        }

        // Start the native present-receiver thread.
        // This thread reads present_msg directly from the Wine socket fd and
        // calls scanoutSetBuffer() in C++, bypassing XConnectorEpoll entirely.
        //
        // IMPORTANT: Do NOT detach the client fd from epoll. We intentionally
        // keep it in epoll so that when Wine crashes (fd EPOLLHUP), the epoll
        // handler calls handleConnectionShutdown() → state is reset and the
        // next game can connect without needing to reboot the container.
        //
        // handleRequest() below is a pure no-op that always returns true so
        // epoll never calls killConnection() prematurely due to stale EPOLLIN
        // events (the native thread consumes the actual data).
        VulkanRenderer renderer = compositor.getRenderer();
        if (renderer != null && pool.getCount() >= 3) {
            int screenWidth = pool.getWidth();
            int screenHeight = pool.getHeight();
            renderer.startPresentReceiver(
                client.clientSocket.fd,
                pool.getBufferPtr(0),
                pool.getBufferPtr(1),
                pool.getBufferPtr(2),
                pool.getCount() > 3 ? pool.getBufferPtr(3) : 0L,
                screenWidth, screenHeight);
            // Detach the client fd from epoll so the native C++ thread is the
            // sole reader. Without this, epoll wakes up on incoming data and
            // the Java side "steals" bytes before the native thread can read them.
            if (connector != null) {
                connector.detachClientFromEpoll(client);
            }
            Log.i(LOG_TAG, "handleNewConnection: native present receiver started (fd="
                    + client.clientSocket.fd + ", " + screenWidth + "x" + screenHeight + ")");
        } else {
            Log.w(LOG_TAG, "handleNewConnection: cannot start present receiver (renderer="
                    + renderer + ", poolCount=" + (pool != null ? pool.getCount() : 0) + ")");
        }
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        Log.i(LOG_TAG, "handleConnectionShutdown: Wine WSI disconnected");
        if (wineClient == client) {
            wineClient = null;
            // Stop the native present receiver thread
            VulkanRenderer renderer = compositor.getRenderer();
            if (renderer != null) {
                renderer.stopPresentReceiver();
                Log.i(LOG_TAG, "handleConnectionShutdown: present receiver stopped");
            }
        }
    }

    // ── RequestHandler ───────────────────────────────────────────────────────

    @Override
    public boolean handleRequest(Client client) {
        // Pure no-op: all present_msg reads are handled by the native present-receiver
        // thread in vulkan_jni.cpp via recvmsg().  The epoll fd is kept open only so
        // that EPOLLHUP fires when Wine crashes, allowing handleConnectionShutdown()
        // to be called and reset the server state for the next game launch.
        //
        // Returning true here prevents XConnectorEpoll from calling killConnection()
        // on stale EPOLLIN events (the native thread consumed the actual data bytes).
        return true;
    }

    /** Returns true if Wine's Vulkan WSI is currently connected. */
    public boolean isWineConnected() {
        return wineClient != null;
    }
}
