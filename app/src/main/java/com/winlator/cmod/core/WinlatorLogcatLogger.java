package com.winlator.cmod.core;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

public final class WinlatorLogcatLogger implements Callback<String> {
    private static final String TAG = "WinlatorLogcatLogger";

    private final File outputFile;
    private final Object writerLock = new Object();
    private volatile boolean running;
    private java.lang.Process logcatProcess;
    private Thread workerThread;
    private BufferedWriter writer;

    public WinlatorLogcatLogger(File outputFile) {
        this.outputFile = outputFile;
    }

    public synchronized void start() {
        if (running) return;

        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "Unable to create log directory: " + parent);
            return;
        }

        try {
            synchronized (writerLock) {
                writer = new BufferedWriter(new FileWriter(outputFile, true));
                writer.write("\n===== Winlator log session started =====");
                writer.newLine();
                writer.write("uid=" + android.os.Process.myUid()
                        + " pid=" + android.os.Process.myPid());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to open Winlator log file: " + outputFile, e);
            return;
        }

        running = true;
        workerThread = new Thread(this::captureLoop, "WinlatorLogcat");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void captureLoop() {
        try {
            int result = runLogcat("--uid=" + android.os.Process.myUid());

            if (running && result != 0) {
                writeLine("===== UID logcat filter unavailable; falling back to app process =====");
                runLogcat("--pid=" + android.os.Process.myPid());
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to capture Winlator logcat: " + outputFile, e);
        } finally {
            closeWriter();
            synchronized (this) {
                logcatProcess = null;
                workerThread = null;
                running = false;
            }
        }
    }

    private int runLogcat(String processFilter) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                "/system/bin/logcat",
                "-b", "all",
                processFilter,
                "-v", "threadtime",
                "-T", "1"
        );
        builder.redirectErrorStream(true);

        java.lang.Process process = builder.start();
        synchronized (this) {
            if (!running) {
                process.destroy();
                return 0;
            }
            logcatProcess = process;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                writeLine(line);
            }
        }

        if (!running) {
            process.destroy();
            return 0;
        }

        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            synchronized (this) {
                if (logcatProcess == process) logcatProcess = null;
            }
        }
    }

    @Override
    public void call(String line) {
        if (running && line != null) writeLine("[process] " + line);
    }

    private void writeLine(String line) {
        synchronized (writerLock) {
            if (writer == null) return;
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                Log.e(TAG, "Unable to append Winlator log line", e);
            }
        }
    }

    private void closeWriter() {
        synchronized (writerLock) {
            if (writer == null) return;
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close Winlator log file", e);
            } finally {
                writer = null;
            }
        }
    }

    public synchronized void stop() {
        if (!running) {
            closeWriter();
            return;
        }
        running = false;

        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }

        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
