package com.winlator.cmod.core;

import android.app.Activity;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes10.dex */
public abstract class HttpUtils {
    /* JADX INFO: Access modifiers changed from: private */
    public static void downloadAsync(String url, Callback<String> onDownloadComplete) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            if (connection.getResponseCode() != 200) {
                onDownloadComplete.call(null);
                return;
            }
            InputStream inStream = connection.getInputStream();
            try {
                byte[] bytes = StreamUtils.copyToByteArray(inStream);
                if (inStream != null) {
                    inStream.close();
                }
                onDownloadComplete.call(new String(bytes, StandardCharsets.UTF_8));
            } finally {
            }
        } catch (Exception e) {
            onDownloadComplete.call(null);
        }
    }

    public static void download(final String url, final Callback<String> onDownloadComplete) {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.core.HttpUtils$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                HttpUtils.downloadAsync(url, onDownloadComplete);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void downloadAsync(String url, File destination, AtomicBoolean interruptRef, Callback<Integer> onPublishProgress, Callback<Boolean> onDownloadComplete) {
        try {
            interruptRef.set(false);
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            if (connection.getResponseCode() != 200) {
                onDownloadComplete.call(false);
                return;
            }
            int contentLength = connection.getContentLength();
            InputStream inStream = new BufferedInputStream(connection.getInputStream(), 65536);
            try {
                OutputStream outStream = new FileOutputStream(destination);
                try {
                    byte[] buffer = new byte[1024];
                    int totalSize = 0;
                    while (true) {
                        int bytesRead = inStream.read(buffer);
                        if (bytesRead == -1 || interruptRef.get()) {
                            break;
                        }
                        totalSize += bytesRead;
                        if (onPublishProgress != null) {
                            int progress = (int) ((totalSize / contentLength) * 100.0f);
                            onPublishProgress.call(Integer.valueOf(progress));
                        }
                        outStream.write(buffer, 0, bytesRead);
                    }
                    outStream.close();
                    inStream.close();
                    onDownloadComplete.call(Boolean.valueOf(!interruptRef.get()));
                } finally {
                }
            } finally {
            }
        } catch (Exception e) {
            onDownloadComplete.call(false);
        }
    }

    public static void download(final Activity activity, final String url, final File destination, final Callback<Boolean> onDownloadComplete) {
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        final AtomicBoolean interruptRef = new AtomicBoolean();
        dialog.show(new Runnable() { // from class: com.winlator.cmod.core.HttpUtils$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                interruptRef.set(true);
            }
        });
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.core.HttpUtils$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                HttpUtils.downloadAsync(url, r1, interruptRef, new Callback() { // from class: com.winlator.cmod.core.HttpUtils$$ExternalSyntheticLambda3
                    @Override // com.winlator.cmod.core.Callback
                    public final void call(Object obj) {
                        r1.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.core.HttpUtils$$ExternalSyntheticLambda2
                            @Override // java.lang.Runnable
                            public final void run() {
                                DownloadProgressDialog.this.setProgress(r2.intValue());
                            }
                        });
                    }
                }, new Callback() { // from class: com.winlator.cmod.core.HttpUtils$$ExternalSyntheticLambda4
                    @Override // com.winlator.cmod.core.Callback
                    public final void call(Object obj) {
                        HttpUtils.lambda$download$5(r1, r2, r3, r4, (Boolean) obj);
                    }
                });
            }
        });
    }

    static /* synthetic */ void lambda$download$5(File destination, Activity activity, final DownloadProgressDialog dialog, final Callback onDownloadComplete, final Boolean success) {
        if (!success.booleanValue() && destination.isFile()) {
            destination.delete();
        }
        activity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.core.HttpUtils$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                HttpUtils.lambda$download$4(DownloadProgressDialog.this, onDownloadComplete, success);
            }
        });
    }

    static /* synthetic */ void lambda$download$4(DownloadProgressDialog dialog, Callback onDownloadComplete, Boolean success) {
        dialog.close();
        onDownloadComplete.call(success);
    }
}
