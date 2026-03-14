package com.winlator.cmod.midi;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;
import cn.sherlock.com.sun.media.sound.SoftSynthesizer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jp.kshoji.javax.sound.midi.Receiver;
import jp.kshoji.javax.sound.midi.ShortMessage;

/* loaded from: classes5.dex */
public class MidiHandler {
    private static final int BUF_SIZE = 9;
    private static final long CHECK_DELAY = 200;
    private static final short CLIENT_PORT = 7941;
    private static final short SERVER_PORT = 7942;
    private static final String TAG = "MidiHandler";
    private Receiver recv;
    private ScheduledExecutorService scheduler;
    private SF2Soundbank sf2SoundBank;
    private DatagramSocket socket;
    private SoftSynthesizer synth;
    private boolean running = false;
    private final ByteBuffer receiveData = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket receivePacket = new DatagramPacket(this.receiveData.array(), 9);
    private long lastMidiMsgTime = 0;
    private ShortMessage message = new ShortMessage();

    public void setSoundBank(SF2Soundbank soundBank) {
        clearRecv();
        clearSynth();
        this.sf2SoundBank = soundBank;
    }

    public void start() {
        this.running = true;
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.midi.MidiHandler$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                MidiHandler.this.lambda$start$0();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$start$0() {
        try {
            this.socket = new DatagramSocket((SocketAddress) null);
            this.socket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress((InetAddress) null, 7942));
            while (this.running) {
                this.socket.receive(this.receivePacket);
                this.receiveData.rewind();
                handleRequest(this.receiveData);
            }
        } catch (IOException e) {
        }
    }

    public void stop() {
        this.running = false;
        if (this.socket != null) {
            this.socket.close();
            this.socket = null;
        }
        clearRecv();
        clearSynth();
        if (this.scheduler != null) {
            this.scheduler.shutdown();
            this.scheduler = null;
        }
    }

    private void handleRequest(ByteBuffer received) {
        byte requestCode = received.get();
        switch (requestCode) {
            case 1:
                if (this.recv != null) {
                    try {
                        this.lastMidiMsgTime = System.currentTimeMillis();
                        this.message.setMessage(received.get(), received.get(), received.get());
                        this.recv.send(this.message, -1L);
                        break;
                    } catch (Exception e) {
                        return;
                    }
                }
                break;
            case 5:
                if (this.synth == null || this.recv == null) {
                    clearRecv();
                    clearSynth();
                    prepareSynthAndRecv();
                    startMidiDataChecking();
                    break;
                }
                break;
            case 6:
                clearRecv();
                clearSynth();
                if (this.scheduler != null) {
                    this.scheduler.shutdown();
                    break;
                }
                break;
        }
    }

    private void clearRecv() {
        if (this.recv != null) {
            this.recv.close();
            this.recv = null;
        }
    }

    private void clearSynth() {
        if (this.synth != null) {
            this.synth.close();
            this.synth = null;
        }
    }

    private void prepareSynthAndRecv() {
        try {
            this.synth = new SoftSynthesizer();
            this.synth.open();
            this.synth.loadAllInstruments(this.sf2SoundBank);
            this.recv = this.synth.getReceiver();
        } catch (Exception e) {
            clearRecv();
            clearSynth();
        }
    }

    private void sendAllOff() {
        if (this.recv != null) {
            try {
                ShortMessage msg = new ShortMessage();
                for (int i = 0; i < 128; i++) {
                    for (int j = 0; j < 16; j++) {
                        msg.setMessage(128, j, i, 0);
                        this.recv.send(msg, -1L);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void startMidiDataChecking() {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
        this.scheduler = Executors.newScheduledThreadPool(1);
        Runnable checkTask = new Runnable() { // from class: com.winlator.cmod.midi.MidiHandler$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                MidiHandler.this.lambda$startMidiDataChecking$1();
            }
        };
        this.scheduler.scheduleWithFixedDelay(checkTask, 0L, CHECK_DELAY, TimeUnit.MILLISECONDS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startMidiDataChecking$1() {
        long currentTime = System.currentTimeMillis();
        if (this.lastMidiMsgTime != 0 && currentTime - this.lastMidiMsgTime > 100) {
            sendAllOff();
            this.lastMidiMsgTime = 0L;
        }
    }
}
