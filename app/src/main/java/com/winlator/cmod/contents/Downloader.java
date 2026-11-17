package com.winlator.cmod.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import com.winlator.cmod.core.Callback;
import java.net.URL;
import java.net.URLConnection;

public class Downloader {
    private static final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    public static boolean isBusy() { return active.get() > 0; }

    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, Callback<Integer> progressCallback) {
        if (!com.winlator.cmod.core.ContainerSessionGate.transfer()) return false;
        active.incrementAndGet();
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            long contentLength = connection.getContentLengthLong();
            long downloadedSize = 0;
            int lastProgress = -1;

            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(file.getAbsolutePath())) {
                byte[] data = new byte[8192];
                int count;
                while ((count = input.read(data)) != -1) {
                    output.write(data, 0, count);
                    if (progressCallback != null && contentLength > 0) {
                        downloadedSize += count;
                        int progress = Math.min(100, (int)(downloadedSize * 100 / contentLength));
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            progressCallback.call(progress);
                        }
                    }
                }
                output.flush();
            }
            if (progressCallback != null) progressCallback.call(100);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally { active.decrementAndGet(); com.winlator.cmod.core.ContainerSessionGate.transferred(); }
    }

    public static String downloadString(String address) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            InputStream input = url.openStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder sb = new StringBuilder();
            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
