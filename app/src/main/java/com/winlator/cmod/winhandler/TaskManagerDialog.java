package com.winlator.cmod.winhandler;

import android.app.ActivityManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import com.ludashi.benchmark.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/* loaded from: classes12.dex */
public class TaskManagerDialog extends ContentDialog implements OnGetProcessInfoListener {
    private final XServerDisplayActivity activity;
    private final LayoutInflater inflater;
    private final Object lock;
    private Timer timer;

    public TaskManagerDialog(final XServerDisplayActivity activity) {
        super(activity, R.layout.task_manager_dialog);
        this.lock = new Object();
        this.activity = activity;
        setCancelable(false);
        setTitle(R.string.task_manager);
        setIcon(R.drawable.icon_task_manager);
        Button cancelButton = (Button) findViewById(R.id.BTCancel);
        cancelButton.setText(R.string.new_task);
        cancelButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                TaskManagerDialog.this.lambda$new$1(activity, view);
            }
        });
        setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda1
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                TaskManagerDialog.this.lambda$new$2(activity, dialogInterface);
            }
        });
        FileUtils.clear(getIconDir(activity));
        this.inflater = LayoutInflater.from(activity);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$1(final XServerDisplayActivity activity, View v) {
        dismiss();
        ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe", new Callback() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda3
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                XServerDisplayActivity.this.getWinHandler().lambda$execWithDelay$10((String) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$2(XServerDisplayActivity activity, DialogInterface dialog) {
        if (this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }
        activity.getWinHandler().setOnGetProcessInfoListener(null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void update() {
        synchronized (this.lock) {
            this.activity.getWinHandler().listProcesses();
            LinearLayout container = (LinearLayout) findViewById(R.id.LLProcessList);
            if (container.getChildCount() == 0) {
                findViewById(R.id.TVEmptyText).setVisibility(0);
            }
        }
        updateCPUInfoView();
        updateMemoryInfoView();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: showListItemMenu, reason: merged with bridge method [inline-methods] */
    public void lambda$onGetProcessInfo$6(View anchorView, final ProcessInfo processInfo) {
        PopupMenu listItemMenu = new PopupMenu(this.activity, anchorView);
        if (Build.VERSION.SDK_INT >= 29) {
            listItemMenu.setForceShowIcon(true);
        }
        listItemMenu.inflate(R.menu.process_popup_menu);
        listItemMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda7
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showListItemMenu$4;
                lambda$showListItemMenu$4 = TaskManagerDialog.this.lambda$showListItemMenu$4(processInfo, menuItem);
                return lambda$showListItemMenu$4;
            }
        });
        listItemMenu.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showListItemMenu$4(final ProcessInfo processInfo, MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        final WinHandler winHandler = this.activity.getWinHandler();
        if (itemId == R.id.process_affinity) {
            showProcessorAffinityDialog(processInfo);
            return true;
        }
        if (itemId == R.id.bring_to_front) {
            winHandler.bringToFront(processInfo.name);
            dismiss();
            return true;
        }
        if (itemId == R.id.process_end) {
            ContentDialog.confirm(this.activity, R.string.do_you_want_to_end_this_process, new Runnable() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    WinHandler.this.killProcess(processInfo.name);
                }
            });
            return true;
        }
        return true;
    }

    private void showProcessorAffinityDialog(final ProcessInfo processInfo) {
        ContentDialog dialog = new ContentDialog(this.activity, R.layout.cpu_list_dialog);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = (CPUListView) dialog.findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(processInfo.getCPUList());
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                TaskManagerDialog.this.lambda$showProcessorAffinityDialog$5(processInfo, cpuListView);
            }
        });
        dialog.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showProcessorAffinityDialog$5(ProcessInfo processInfo, CPUListView cpuListView) {
        WinHandler winHandler = this.activity.getWinHandler();
        winHandler.setProcessAffinity(processInfo.pid, ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList()));
        update();
    }

    public static File getIconDir(Context context) {
        File iconDir = new File(ImageFs.find(context).getRootDir(), "home/xuser/.local/share/icons/taskmgr");
        if (!iconDir.isDirectory()) {
            iconDir.mkdirs();
        }
        return iconDir;
    }

    @Override // android.app.Dialog
    public void show() {
        update();
        this.activity.getWinHandler().setOnGetProcessInfoListener(this);
        this.timer = new Timer();
        this.timer.schedule(new AnonymousClass1(), 0L, 1000L);
        super.show();
    }

    /* renamed from: com.winlator.cmod.winhandler.TaskManagerDialog$1, reason: invalid class name */
    class AnonymousClass1 extends TimerTask {
        AnonymousClass1() {
        }

        @Override // java.util.TimerTask, java.lang.Runnable
        public void run() {
            XServerDisplayActivity xServerDisplayActivity = TaskManagerDialog.this.activity;
            final TaskManagerDialog taskManagerDialog = TaskManagerDialog.this;
            xServerDisplayActivity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$1$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    TaskManagerDialog.this.update();
                }
            });
        }
    }

    @Override // com.winlator.cmod.winhandler.OnGetProcessInfoListener
    public void onGetProcessInfo(final int index, final int numProcesses, final ProcessInfo processInfo) {
        this.activity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                TaskManagerDialog.this.lambda$onGetProcessInfo$7(numProcesses, index, processInfo);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onGetProcessInfo$7(int numProcesses, int index, final ProcessInfo processInfo) {
        Bitmap icon;
        synchronized (this.lock) {
            LinearLayout container = (LinearLayout) findViewById(R.id.LLProcessList);
            setBottomBarText(this.activity.getString(R.string.processes) + ": " + numProcesses);
            if (numProcesses == 0) {
                container.removeAllViews();
                findViewById(R.id.TVEmptyText).setVisibility(0);
                return;
            }
            findViewById(R.id.TVEmptyText).setVisibility(8);
            int childCount = container.getChildCount();
            View itemView = index < childCount ? container.getChildAt(index) : this.inflater.inflate(R.layout.process_info_list_item, (ViewGroup) container, false);
            ((TextView) itemView.findViewById(R.id.TVName)).setText(processInfo.name + (processInfo.wow64Process ? " *32" : ""));
            ((TextView) itemView.findViewById(R.id.TVPID)).setText(String.valueOf(processInfo.pid));
            ((TextView) itemView.findViewById(R.id.TVMemoryUsage)).setText(processInfo.getFormattedMemoryUsage());
            itemView.findViewById(R.id.BTMenu).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.winhandler.TaskManagerDialog$$ExternalSyntheticLambda6
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    TaskManagerDialog.this.lambda$onGetProcessInfo$6(processInfo, view);
                }
            });
            XServer xServer = this.activity.getXServer();
            XLock xlock = xServer.lock(XServer.Lockable.WINDOW_MANAGER);
            try {
                Window window = xServer.windowManager.findWindowWithProcessId(processInfo.pid);
                if (xlock != null) {
                    xlock.close();
                }
                ImageView ivIcon = (ImageView) itemView.findViewById(R.id.IVIcon);
                ivIcon.setImageResource(R.drawable.taskmgr_process);
                if (window != null && (icon = xServer.pixmapManager.getWindowIcon(window)) != null) {
                    ivIcon.setImageBitmap(icon);
                }
                if (index >= childCount) {
                    container.addView(itemView);
                }
                if (index == numProcesses - 1 && childCount > numProcesses) {
                    for (int i = childCount - 1; i >= numProcesses; i--) {
                        container.removeViewAt(i);
                    }
                }
            } finally {
            }
        }
    }

    private void updateCPUInfoView() {
        LinearLayout llCPUInfo = (LinearLayout) findViewById(R.id.LLCPUInfo);
        llCPUInfo.removeAllViews();
        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        int totalClockSpeed = 0;
        short maxClockSpeed = 0;
        for (int i = 0; i < clockSpeeds.length; i++) {
            TextView textView = new TextView(this.activity);
            textView.setTextSize(1, 14.0f);
            short clockSpeed = CPUStatus.getMaxClockSpeed(i);
            textView.setText(((int) clockSpeeds[i]) + "/" + ((int) clockSpeed) + " MHz");
            llCPUInfo.addView(textView);
            totalClockSpeed += clockSpeeds[i];
            maxClockSpeed = (short) Math.max((int) maxClockSpeed, (int) clockSpeed);
        }
        int i2 = clockSpeeds.length;
        int avgClockSpeed = totalClockSpeed / i2;
        TextView tvCPUTitle = (TextView) findViewById(R.id.TVCPUTitle);
        byte cpuUsagePercent = (byte) ((avgClockSpeed / maxClockSpeed) * 100.0f);
        tvCPUTitle.setText("CPU (" + ((int) cpuUsagePercent) + "%)");
    }

    private void updateMemoryInfoView() {
        ActivityManager activityManager = (ActivityManager) this.activity.getSystemService("activity");
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        byte memUsagePercent = (byte) ((usedMem / memoryInfo.totalMem) * 100.0d);
        TextView tvMemoryTitle = (TextView) findViewById(R.id.TVMemoryTitle);
        tvMemoryTitle.setText(this.activity.getString(R.string.memory) + " (" + ((int) memUsagePercent) + "%)");
        TextView tvMemoryInfo = (TextView) findViewById(R.id.TVMemoryInfo);
        tvMemoryInfo.setText(StringUtils.formatBytes(usedMem, false) + "/" + StringUtils.formatBytes(memoryInfo.totalMem));
    }
}
