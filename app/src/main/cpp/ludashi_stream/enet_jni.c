// Thin JNI bridge over ENet (cgutman/enet, MIT) for the Moonlight control stream.
// All protocol logic lives in Java (com.winlator.cmod.stream.moonlight.EnetHost).
#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <netdb.h>
#include <sys/socket.h>
#include <enet/enet.h>

static int g_enet_ready = 0;

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_stream_moonlight_EnetHost_nativeCreate(JNIEnv *env, jclass cls, jint port, jint peers) {
    if (!g_enet_ready) {
        if (enet_initialize() != 0) return 0;
        g_enet_ready = 1;
    }
    // IPv4 like Sunshine's default address family.
    ENetAddress addr;
    memset(&addr, 0, sizeof(addr));
    if (enet_address_set_host(&addr, "0.0.0.0") != 0) return 0;
    enet_address_set_port(&addr, (enet_uint16) port);
    ENetHost *host = enet_host_create(AF_INET, &addr, (size_t) peers, 0, 0, 0);
    return (jlong) (intptr_t) host;
}

// Services the host once. Returns the event type (0 none, 1 connect, 2 disconnect, 3 receive)
// or -1 on error. info[0]=peer handle (low 32 bits), info[1]=peer handle (high), info[2]=data
// length or connect data, info[3]=channel. Received bytes are copied into buf.
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_stream_moonlight_EnetHost_nativeService(JNIEnv *env, jclass cls, jlong hostPtr,
                                                               jint timeoutMs, jbyteArray buf, jlongArray info) {
    ENetHost *host = (ENetHost *) (intptr_t) hostPtr;
    ENetEvent event;
    int res = enet_host_service(host, &event, (enet_uint32) timeoutMs);
    if (res <= 0) return res;
    jlong out[4] = {(jlong) (intptr_t) event.peer, 0, 0, event.channelID};
    if (event.type == ENET_EVENT_TYPE_RECEIVE) {
        jsize cap = (*env)->GetArrayLength(env, buf);
        jsize n = event.packet->dataLength < (size_t) cap ? (jsize) event.packet->dataLength : cap;
        (*env)->SetByteArrayRegion(env, buf, 0, n, (const jbyte *) event.packet->data);
        out[2] = n;
        enet_packet_destroy(event.packet);
    } else {
        out[2] = (jlong) event.data;
    }
    (*env)->SetLongArrayRegion(env, info, 0, 4, out);
    return (jint) event.type;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_stream_moonlight_EnetHost_nativeSend(JNIEnv *env, jclass cls, jlong hostPtr, jlong peerPtr,
                                                            jbyteArray data, jint len, jboolean reliable) {
    ENetHost *host = (ENetHost *) (intptr_t) hostPtr;
    ENetPeer *peer = (ENetPeer *) (intptr_t) peerPtr;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    ENetPacket *packet = enet_packet_create(bytes, (size_t) len, reliable ? ENET_PACKET_FLAG_RELIABLE : 0);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (packet == NULL) return -1;
    if (enet_peer_send(peer, 0, packet) != 0) {
        enet_packet_destroy(packet);
        return -1;
    }
    enet_host_flush(host);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_stream_moonlight_EnetHost_nativeDisconnect(JNIEnv *env, jclass cls, jlong hostPtr, jlong peerPtr) {
    ENetPeer *peer = (ENetPeer *) (intptr_t) peerPtr;
    enet_peer_disconnect_now(peer, 0);
    enet_host_flush((ENetHost *) (intptr_t) hostPtr);
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_stream_moonlight_EnetHost_nativePeerAddress(JNIEnv *env, jclass cls, jlong peerPtr) {
    ENetPeer *peer = (ENetPeer *) (intptr_t) peerPtr;
    char name[128] = {0};
    if (getnameinfo((struct sockaddr *) &peer->address.address, peer->address.addressLength,
                    name, sizeof(name), NULL, 0, NI_NUMERICHOST) != 0) return NULL;
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_stream_moonlight_EnetHost_nativeDestroy(JNIEnv *env, jclass cls, jlong hostPtr) {
    ENetHost *host = (ENetHost *) (intptr_t) hostPtr;
    if (host == NULL) return;
    for (size_t i = 0; i < host->peerCount; i++) {
        ENetPeer *p = &host->peers[i];
        if (p->state != ENET_PEER_STATE_DISCONNECTED) enet_peer_disconnect_now(p, 0);
    }
    enet_host_flush(host);
    enet_host_destroy(host);
}
