package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.widget.LogView;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

/* loaded from: classes4.dex */
public class DebugDialog extends ContentDialog implements Callback<String> {
    private static boolean paused = false;
    private final LogView logView;
    private BufferedWriter writer;

    public DebugDialog(Context context) {
        super(context, R.layout.debug_dialog);
        setIcon(R.drawable.icon_debug);
        setTitle(context.getString(R.string.logs));
        this.logView = (LogView) findViewById(R.id.LogView);
        this.logView.getLayoutParams().width = (int) UnitUtils.dpToPx(UnitUtils.pxToDp(AppUtils.getScreenWidth()) * 0.7f);
        findViewById(R.id.BTCancel).setVisibility(8);
        LinearLayout llBottomBarPanel = (LinearLayout) findViewById(R.id.LLBottomBarPanel);
        llBottomBarPanel.setVisibility(0);
        View toolbarView = LayoutInflater.from(context).inflate(R.layout.debug_toolbar, (ViewGroup) llBottomBarPanel, false);
        toolbarView.findViewById(R.id.BTClear).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.DebugDialog$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                DebugDialog.this.lambda$new$0(view);
            }
        });
        toolbarView.findViewById(R.id.BTPause).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.DebugDialog$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                DebugDialog.lambda$new$1(view);
            }
        });
        llBottomBarPanel.addView(toolbarView);
        try {
            this.writer = new BufferedWriter(new FileWriter(LogView.getLogFile(context)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$0(View v) {
        this.logView.clear();
    }

    static /* synthetic */ void lambda$new$1(View v) {
        setPaused(!paused);
        ((ImageButton) v).setImageResource(getPaused() ? R.drawable.icon_play : R.drawable.icon_pause);
    }

    @Override // com.winlator.cmod.core.Callback
    public void call(String line) {
        if (!getPaused()) {
            this.logView.append(line + "\n");
        }
        try {
            this.writer.write(line + "\n");
            this.writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setPaused(boolean cond) {
        paused = cond;
    }

    public static boolean getPaused() {
        return paused;
    }
}
