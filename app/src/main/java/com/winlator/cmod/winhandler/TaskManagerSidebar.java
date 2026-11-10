package com.winlator.cmod.winhandler;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class TaskManagerSidebar implements OnGetProcessInfoListener {
    private final XServerDisplayActivity activity;
    private final View rootView;
    private final LayoutInflater inflater;
    private final Object lock = new Object();
    private Timer timer;

    public TaskManagerSidebar(XServerDisplayActivity activity, View rootView) {
        this.activity = activity;
        this.rootView = rootView;
        this.inflater = LayoutInflater.from(activity);

        styleMetricCard(R.id.TVCPUInfoCompact, 18f);
        styleMetricCard(R.id.TVMemoryInfo, 11f);
        adjustMetricCardWidths();

        TextView empty = rootView.findViewById(R.id.TVEmptyText);
        if (empty != null) empty.setTextColor(sidebarSecondary());

        View newTask = rootView.findViewById(R.id.BTTaskNewTask);
        if (newTask != null) {
            newTask.setVisibility(View.VISIBLE);
            if (newTask instanceof ViewGroup) tintTextChildren((ViewGroup) newTask, sidebarPrimary());
            newTask.setOnClickListener(v ->
                ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe",
                    (command) -> activity.getWinHandler().exec(command)));
        }
    }

    private int sidebarPrimary() {
        return ContextCompat.getColor(activity, R.color.ingame_sidebar_icon_selected);
    }

    private int sidebarSecondary() {
        return ContextCompat.getColor(activity, R.color.ingame_sidebar_icon);
    }

    private void tintTextChildren(ViewGroup group, int color) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) ((TextView) child).setTextColor(color);
            else if (child instanceof ViewGroup) tintTextChildren((ViewGroup) child, color);
        }
    }

    private void styleMetricCard(int valueId, float valueTextSizeSp) {
        TextView value = rootView.findViewById(valueId);
        if (value == null) return;

        value.setTextColor(sidebarPrimary());
        value.setTextSize(valueTextSizeSp);
        value.setSingleLine(true);
        value.setEllipsize(null);

        if (value.getParent() instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) value.getParent();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView && child != value) {
                    ((TextView) child).setTextColor(sidebarSecondary());
                }
            }
        }
    }

    private void adjustMetricCardWidths() {
        TextView cpu = rootView.findViewById(R.id.TVCPUInfoCompact);
        TextView memory = rootView.findViewById(R.id.TVMemoryInfo);
        if (cpu == null || memory == null) return;

        View cpuCard = (View) cpu.getParent();
        View memoryCard = (View) memory.getParent();
        if (!(cpuCard.getLayoutParams() instanceof LinearLayout.LayoutParams)
                || !(memoryCard.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;

        LinearLayout.LayoutParams cpuParams = (LinearLayout.LayoutParams) cpuCard.getLayoutParams();
        LinearLayout.LayoutParams memoryParams = (LinearLayout.LayoutParams) memoryCard.getLayoutParams();
        cpuParams.width = 0;
        memoryParams.width = 0;
        cpuParams.weight = 0.82f;
        memoryParams.weight = 1.18f;
        cpuCard.setLayoutParams(cpuParams);
        memoryCard.setLayoutParams(memoryParams);
    }

    public void start() {
        stop();
        activity.getWinHandler().setOnGetProcessInfoListener(this);

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.getWinHandler().listProcesses();
                activity.runOnUiThread(() -> {
                    updateCPUInfoView();
                    updateMemoryInfoView();
                });
            }
        }, 0, 1000);
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        activity.getWinHandler().setOnGetProcessInfoListener(null);
    }

    public void updateNow() {
        activity.getWinHandler().listProcesses();
    }

    @Override
    public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            synchronized (lock) {
                LinearLayout container = rootView.findViewById(R.id.LLProcessList);
                if (container == null) return;

                TextView title = rootView.findViewById(R.id.TVProcessesTitle);
                if (title != null) title.setText(activity.getString(R.string.sidebar_processes_1_d, numProcesses));

                View empty = rootView.findViewById(R.id.TVEmptyText);
                if (numProcesses == 0) {
                    container.removeAllViews();
                    if (empty != null) empty.setVisibility(View.VISIBLE);
                    return;
                }
                if (empty != null) empty.setVisibility(View.GONE);

                int childCount = container.getChildCount();
                View itemView = index < childCount
                        ? container.getChildAt(index)
                        : inflater.inflate(R.layout.process_info_list_item, container, false);

                ((TextView) itemView.findViewById(R.id.TVName)).setText(
                        processInfo.name + (processInfo.wow64Process ? " *32" : ""));
                ((TextView) itemView.findViewById(R.id.TVPID)).setText("PID: " + processInfo.pid);
                ((TextView) itemView.findViewById(R.id.TVMemoryUsage)).setText(
                        processInfo.getFormattedMemoryUsage());

                itemView.findViewById(R.id.BTMenu)
                        .setOnClickListener(v -> showListItemMenu(v, processInfo));

                XServer xServer = activity.getXServer();
                Window window;
                try (XLock xlock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    window = xServer.windowManager.findWindowByProcessName(processInfo.name);
                }

                ImageView ivIcon = itemView.findViewById(R.id.IVIcon);
                ivIcon.setImageResource(R.drawable.taskmgr_process);
                if (window != null) {
                    Bitmap icon = xServer.pixmapManager.getWindowIcon(window);
                    if (icon != null) ivIcon.setImageBitmap(icon);
                }

                if (index >= childCount) container.addView(itemView);

                if (index == numProcesses - 1 && childCount > numProcesses) {
                    for (int i = childCount - 1; i >= numProcesses; i--) container.removeViewAt(i);
                }
            }
        });
    }

    private void showListItemMenu(View anchorView, ProcessInfo processInfo) {
        PopupMenu listItemMenu = new PopupMenu(activity, anchorView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

        listItemMenu.inflate(R.menu.process_popup_menu);
        listItemMenu.setOnMenuItemClickListener(menuItem -> {
            int itemId = menuItem.getItemId();
            final WinHandler winHandler = activity.getWinHandler();
            if (itemId == R.id.process_affinity) {
                showProcessorAffinityDialog(processInfo);
            } else if (itemId == R.id.bring_to_front) {
                winHandler.bringToFront(processInfo.name);
            } else if (itemId == R.id.process_end) {
                ContentDialog.confirm(activity, R.string.do_you_want_to_end_this_process,
                        () -> winHandler.killProcess(processInfo.name));
            }
            return true;
        });
        listItemMenu.show();
    }

    private void showProcessorAffinityDialog(final ProcessInfo processInfo) {
        ContentDialog dialog = new ContentDialog(activity, R.layout.cpu_list_dialog);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = dialog.findViewById(R.id.CPUListView);
        // Read affinity from Linux /proc — Wine's reported mask is unreliable after SetProcessAffinityMask
        String cpuList = processInfo.getCPUList();
        int linuxMask = ProcessHelper.getProcessAffinityMask(processInfo.pid);
        if (linuxMask != 0) {
            cpuList = new ProcessInfo(processInfo.pid, "", 0, linuxMask, false).getCPUList();
        }
        cpuListView.setCheckedCPUList(cpuList);
        dialog.setOnConfirmCallback(() -> {
            WinHandler winHandler = activity.getWinHandler();
            winHandler.setProcessAffinity(processInfo.pid, ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList()));
            updateNow();
        });
        dialog.show();
    }

    private void updateCPUInfoView() {
        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        if (clockSpeeds.length == 0) return;

        int totalClockSpeed = 0;
        short maxClockSpeed = 0;
        for (int i = 0; i < clockSpeeds.length; i++) {
            short clockSpeed = CPUStatus.getMaxClockSpeed(i);
            totalClockSpeed += clockSpeeds[i];
            maxClockSpeed = (short) Math.max(maxClockSpeed, clockSpeed);
        }

        int avgClockSpeed = totalClockSpeed / clockSpeeds.length;
        byte cpuUsagePercent = maxClockSpeed == 0 ? 0 :
                (byte) (((float) avgClockSpeed / maxClockSpeed) * 100.0f);

        TextView compact = rootView.findViewById(R.id.TVCPUInfoCompact);
        if (compact != null) compact.setText(cpuUsagePercent + "%");
    }

    private String formatMemoryPair(long usedBytes, long totalBytes) {
        final double gib = 1024.0 * 1024.0 * 1024.0;
        if (totalBytes >= gib) {
            return String.format(Locale.US, "%.2f / %.2f GB", usedBytes / gib, totalBytes / gib);
        }

        final double mib = 1024.0 * 1024.0;
        return String.format(Locale.US, "%.0f / %.0f MB", usedBytes / mib, totalBytes / mib);
    }

    private void updateMemoryInfoView() {
        ActivityManager activityManager =
                (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;

        TextView info = rootView.findViewById(R.id.TVMemoryInfo);
        if (info != null) info.setText(formatMemoryPair(usedMem, memoryInfo.totalMem));
    }
}
