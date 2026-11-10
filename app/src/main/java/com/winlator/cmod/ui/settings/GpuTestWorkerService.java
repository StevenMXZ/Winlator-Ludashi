package com.winlator.cmod.ui.settings;

import android.app.Service;
import android.graphics.Bitmap;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import androidx.annotation.Keep;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import com.winlator.cmod.core.GPUInformation;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vulkan GPU tests execute in :labs_gpu_test, never in the Winlator UI process.
 * The worker has the app UID to access installed AdrenoTools drivers.
 */
public class GpuTestWorkerService extends Service {
    public static final int CHECK = 1;
    public static final int RUN = 2;
    public static final int STARTED = 100;
    public static final int FINISHED = 101;
    public static final int PREVIEW = 102;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean bound;
    private final Runnable endIdleProcess = () -> Process.killProcess(Process.myPid());
    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), request -> {
        if (request.what != CHECK && request.what != RUN) return true;
        final int id = request.getData().getInt("id", -1);
        final String driver = request.getData().getString("driver", "");
        final boolean checkOnly = request.what == CHECK;
        final Messenger client = request.replyTo;
        if (client == null) return true;
        try {
            Message started = Message.obtain(null, STARTED);
            started.arg1 = Process.myPid();
            Bundle data = new Bundle();
            data.putInt("id", id);
            started.setData(data);
            client.send(started);
        } catch (RemoteException e) {
            return true;
        }
        executor.execute(() -> {
            String answer;
            try {
                if (!validDriver(driver)) {
                    answer = "ERROR\tDriver is not installed or metadata is missing";
                } else {
                    if (checkOnly) {
                        // Real VkDevice/queue initialization is required, not just metadata.
                        answer = GPUInformation.runGpuTransferTest(driver, getApplicationContext(), true);
                    } else {
                        answer = GPUInformation.runGpuCubeBenchmark(driver, getApplicationContext(),
                                new PreviewReporter(GpuTestWorkerService.this, id, client));
                    }
                }
            } catch (Throwable e) {
                answer = "ERROR\t" + (e.getMessage() != null ? e.getMessage() : "Vulkan test unavailable");
            }
            try {
                Message done = Message.obtain(null, FINISHED);
                Bundle data = new Bundle();
                data.putInt("id", id);
                data.putString("answer", answer);
                done.setData(data);
                client.send(done);
            } catch (RemoteException ignored) {}
        });
        return true;
    }));

    /** Encodes only a few actual Vulkan-rendered frames during the 3D benchmark. */
    @Keep
    public static final class PreviewReporter {
        private final Service service;
        private final int id;
        private final Messenger client;
        public PreviewReporter(Service service, int id, Messenger client) {
            this.service = service;
            this.id = id;
            this.client = client;
        }
        @Keep
        public void onCubeFrame(byte[] rgba, int width, int height) {
            if (width < 256 || width > 1024 || height < 256 || height > 1024
                    || rgba == null || rgba.length != width * height * 4) return;
            Bitmap bitmap = null;
            File tmp = null;
            try {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
                File output = new File(service.getCacheDir(), "gpu_cube_" + id + ".png");
                tmp = new File(service.getCacheDir(), "gpu_cube_" + id + ".tmp");
                try (FileOutputStream stream = new FileOutputStream(tmp)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)) return;
                }
                if (!tmp.renameTo(output)) return;
                Message preview = Message.obtain(null, PREVIEW);
                Bundle bundle = new Bundle();
                bundle.putInt("id", id);
                bundle.putString("path", output.getAbsolutePath());
                preview.setData(bundle);
                client.send(preview);
            } catch (Exception ignored) {
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (tmp != null && tmp.exists()) tmp.delete();
            }
        }
    }

    private boolean validDriver(String driver) {
        if ("System".equals(driver)) return true;
        if (driver == null || driver.isEmpty() || driver.equals(".") || driver.equals("..")
                || driver.indexOf('/') >= 0 || driver.indexOf('\\') >= 0) return false;
        try {
            File root = new File(getFilesDir(), "contents/adrenotools").getCanonicalFile();
            File selected = new File(root, driver).getCanonicalFile();
            return root.equals(selected.getParentFile())
                    && new File(selected, "meta.json").isFile();
        } catch (Exception e) {
            return false;
        }
    }

    @Override public IBinder onBind(Intent intent) {
        bound = true;
        mainHandler.removeCallbacks(endIdleProcess);
        return incoming.getBinder();
    }

    @Override public void onRebind(Intent intent) {
        bound = true;
        mainHandler.removeCallbacks(endIdleProcess);
        super.onRebind(intent);
    }

    @Override public boolean onUnbind(Intent intent) {
        // Drop the loader's entire address space between checks/runs or drivers.
        bound = false;
        // Keep a quick check->run transition reliable; stop the process when idle.
        mainHandler.removeCallbacks(endIdleProcess);
        mainHandler.postDelayed(endIdleProcess, 2500L);
        return true;
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(endIdleProcess);
        executor.shutdownNow();
        super.onDestroy();
    }
}
