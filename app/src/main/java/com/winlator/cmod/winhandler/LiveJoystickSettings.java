package com.winlator.cmod.winhandler;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.winlator.cmod.core.JoystickRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Import through the existing Wine session; never edit a live wineserver's user.reg. */
public final class LiveJoystickSettings {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    public interface Callback { void done(boolean success); }
    private LiveJoystickSettings() {}

    public static void apply(WinHandler handler, File driveC, boolean dinput,
                             boolean exclusive, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            String id = "joy" + Long.toHexString(System.nanoTime());
            File reg = new File(driveC, id + ".reg");
            File cmd = new File(driveC, id + ".cmd");
            File result = new File(driveC, id + ".done");
            boolean success = false;
            try {
                Files.write(reg.toPath(), JoystickRegistry.regFile(dinput, exclusive).getBytes(StandardCharsets.US_ASCII));
                String script = "@echo off\r\nregedit.exe /S C:\\" + id + ".reg\r\n"
                        + "if errorlevel 1 (echo error>C:\\" + id + ".done) else (echo ok>C:\\" + id + ".done)\r\n";
                Files.write(cmd.toPath(), script.getBytes(StandardCharsets.US_ASCII));
                handler.exec("cmd.exe /c C:\\" + id + ".cmd");
                long deadline = SystemClock.uptimeMillis() + 8000;
                while (!result.isFile() && SystemClock.uptimeMillis() < deadline) Thread.sleep(80);
                success = result.isFile() && new String(Files.readAllBytes(result.toPath()), StandardCharsets.US_ASCII).trim().equals("ok");
            } catch (Exception e) {
                android.util.Log.w("LiveJoystickSettings", "Could not apply joystick settings", e);
            } finally {
                reg.delete(); cmd.delete(); result.delete();
            }
            final boolean applied = success;
            main.post(() -> callback.done(applied));
        });
    }
}
