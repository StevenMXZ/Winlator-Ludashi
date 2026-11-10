package com.winlator.cmod.core;

import android.app.Activity;
import android.view.View;

import com.winlator.cmod.R;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.ui.ThemedDownloadProgressHost;

// Same public API as before (constructor, show()/show(int)/show(Runnable)/show(int, Runnable),
// setProgress, setMessage, close, closeOnUiThread, isShowing) — callers (ImageFsInstaller,
// HttpUtils, AdrenotoolsManager) don't need to change. Internally this now just forwards to
// ThemedDownloadProgressHost, which shows the themed Compose card instead of inflating
// download_progress_dialog.xml into a plain android.app.Dialog.
public class DownloadProgressDialog {
    private final Activity activity;
    private View overlay;
    private final LegacyDownloadProgressDialog legacy;

    public DownloadProgressDialog(Activity activity) {
        this.activity = activity;
        legacy = activity instanceof androidx.activity.ComponentActivity ? null : new LegacyDownloadProgressDialog(activity);
    }

    public void show() {
        show(0, null);
    }

    public void show(int textResId) {
        show(textResId, null);
    }

    public void show(Runnable onCancelCallback) {
        show(0, onCancelCallback);
    }

    public void show(int textResId, final Runnable onCancelCallback) {
        if (legacy != null) { legacy.show(textResId, onCancelCallback); return; }
        if (isShowing()) return;
        close();
        String message = activity.getString(textResId > 0 ? textResId : R.string.downloading_file);
        overlay = ThemedDownloadProgressHost.show(activity, message, onCancelCallback);
    }

    public void setProgress(int progress) {
        if (legacy != null) { legacy.setProgress(progress); return; }
        if (overlay == null) return;
        ThemedDownloadProgressHost.setProgress(overlay, Mathf.clamp(progress, 0, 100));
    }

    public void close() {
        if (legacy != null) { legacy.close(); return; }
        try {
            ThemedDownloadProgressHost.dismiss(overlay);
        } catch (Exception e) {
        }
        overlay = null;
    }

    public void setMessage(int textResId) {
        if (legacy != null) { legacy.setMessage(textResId); return; }
        if (textResId > 0) {
            ThemedDownloadProgressHost.setMessage(overlay, activity.getString(textResId));
        }
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        if (legacy != null) return legacy.isShowing();
        return ThemedDownloadProgressHost.isShowing(overlay);
    }
}
