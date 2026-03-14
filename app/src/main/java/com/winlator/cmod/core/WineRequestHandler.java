package com.winlator.cmod.core;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* loaded from: classes10.dex */
public class WineRequestHandler {
    private Context context;
    private ServerSocket serverSocket;

    abstract class RequestCodes {
        static final int GET_WINE_CLIPBOARD = 2;
        static final int OPEN_URL = 1;
        static final int SET_WINE_CLIPBAORD = 3;

        RequestCodes() {
        }
    }

    public WineRequestHandler(Context context) {
        this.context = context;
    }

    public void start() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(new Runnable() { // from class: com.winlator.cmod.core.WineRequestHandler$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                WineRequestHandler.this.lambda$start$0();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$start$0() {
        try {
            this.serverSocket = new ServerSocket(20000);
            while (true) {
                Socket socket = this.serverSocket.accept();
                DataInputStream inputStream = new DataInputStream(socket.getInputStream());
                DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                int requestCode = inputStream.readInt();
                handleRequest(inputStream, outputStream, requestCode);
            }
        } catch (IOException e) {
        }
    }

    public void stop() {
        if (this.serverSocket != null) {
            try {
                this.serverSocket.close();
            } catch (IOException e) {
            }
        }
    }

    public void handleRequest(DataInputStream inputStream, DataOutputStream outputStream, int requestCode) throws IOException {
        switch (requestCode) {
            case 1:
                openURL(inputStream, outputStream);
                break;
            case 2:
                getWineClipboard(inputStream, outputStream);
                break;
            case 3:
                setWineClipboard(inputStream, outputStream);
                break;
        }
    }

    private void openURL(DataInputStream inputStream, DataOutputStream outputStream) throws IOException {
        int messageLength = inputStream.readInt();
        byte[] data = new byte[messageLength];
        inputStream.readFully(data);
        String url = new String(data, "UTF-8");
        Log.d("WineRequestHandler", "Received request code OPEN_URL with url " + url);
        Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(url));
        this.context.startActivity(intent);
    }

    private void getWineClipboard(DataInputStream inputStream, DataOutputStream outputStream) throws IOException {
        int format = inputStream.readInt();
        int size = inputStream.readInt();
        byte[] data = new byte[size];
        inputStream.readFully(data);
        if (format == 13) {
            String clipboardData = new String(data, StandardCharsets.UTF_16LE);
            String clipboardData2 = clipboardData.replace("\u0000", "");
            ClipboardManager clpm = (ClipboardManager) this.context.getSystemService("clipboard");
            ClipData clipData = ClipData.newPlainText("", clipboardData2);
            clpm.setPrimaryClip(clipData);
        }
        Log.d("WineRequestHandler", "Received request code GET_WINE_CLIPBOARD with format " + format + " and size " + size);
    }

    private void setWineClipboard(DataInputStream inputStream, DataOutputStream outputStream) throws IOException {
        String clipText;
        ClipboardManager clipboardManager = (ClipboardManager) this.context.getSystemService("clipboard");
        ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData != null) {
            ClipData.Item item = clipData.getItemAt(0);
            clipText = item.getText().toString();
        } else {
            clipText = "";
        }
        Log.d("WineRequestHandler", "Received request code SET_WINE_CLIPBOARD for clipboard " + clipText);
        byte[] dataByte = (clipText + "\u0000").getBytes(StandardCharsets.UTF_16LE);
        int size = dataByte.length;
        outputStream.writeInt(13);
        outputStream.writeInt(size);
        outputStream.write(dataByte);
    }
}
