package com.winlator.cmod.core;

import android.R;
import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.winlator.cmod.math.Mathf;

/* loaded from: classes10.dex */
public class DownloadProgressDialog {
    private final Activity activity;
    private Dialog dialog;

    public DownloadProgressDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (this.dialog != null) {
            return;
        }
        this.dialog = new Dialog(this.activity, R.style.Theme.Translucent.NoTitleBar.Fullscreen);
        this.dialog.requestWindowFeature(1);
        this.dialog.setCancelable(false);
        this.dialog.setCanceledOnTouchOutside(false);
        this.dialog.setContentView(com.ludashi.benchmark.R.layout.download_progress_dialog);
        Window window = this.dialog.getWindow();
        if (window != null) {
            window.clearFlags(16);
            window.clearFlags(8);
        }
    }

    public void show() {
        show((Runnable) null);
    }

    public void show(int textResId) {
        show(textResId, null);
    }

    public void show(Runnable onCancelCallback) {
        show(0, onCancelCallback);
    }

    public void show(int textResId, final Runnable onCancelCallback) {
        if (isShowing()) {
            return;
        }
        close();
        if (this.dialog == null) {
            create();
        }
        if (textResId > 0) {
            ((TextView) this.dialog.findViewById(com.ludashi.benchmark.R.id.TextView)).setText(textResId);
        }
        setProgress(0);
        if (onCancelCallback != null) {
            this.dialog.findViewById(com.ludashi.benchmark.R.id.BTCancel).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.core.DownloadProgressDialog$$ExternalSyntheticLambda1
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    onCancelCallback.run();
                }
            });
            this.dialog.findViewById(com.ludashi.benchmark.R.id.LLBottomBar).setVisibility(0);
        }
        this.dialog.show();
    }

    public void setProgress(int progress) {
        if (this.dialog == null) {
            return;
        }
        int progress2 = Mathf.clamp(progress, 0, 100);
        ((CircularProgressIndicator) this.dialog.findViewById(com.ludashi.benchmark.R.id.CircularProgressIndicator)).setProgress(progress2);
        ((TextView) this.dialog.findViewById(com.ludashi.benchmark.R.id.TVProgress)).setText(progress2 + "%");
    }

    public void close() {
        try {
            if (this.dialog != null) {
                this.dialog.dismiss();
            }
        } catch (Exception e) {
        }
    }

    public void closeOnUiThread() {
        this.activity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.core.DownloadProgressDialog$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                DownloadProgressDialog.this.close();
            }
        });
    }

    public boolean isShowing() {
        return this.dialog != null && this.dialog.isShowing();
    }
}
