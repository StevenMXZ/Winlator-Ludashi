package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.widget.TextView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.ludashi.benchmark.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/* loaded from: classes4.dex */
public class StorageInfoDialog extends ContentDialog {
    public StorageInfoDialog(final Activity activity, final Container container) {
        super(activity, R.layout.container_storage_info_dialog);
        setTitle(R.string.storage_info);
        setIcon(R.drawable.icon_info);
        final AtomicLong driveCSize = new AtomicLong();
        driveCSize.set(0L);
        final AtomicLong cacheSize = new AtomicLong();
        cacheSize.set(0L);
        final AtomicLong totalSize = new AtomicLong();
        totalSize.set(0L);
        final TextView tvDriveCSize = (TextView) findViewById(R.id.TVDriveCSize);
        final TextView tvCacheSize = (TextView) findViewById(R.id.TVCacheSize);
        final TextView tvTotalSize = (TextView) findViewById(R.id.TVTotalSize);
        final TextView tvUsedSpace = (TextView) findViewById(R.id.TVUsedSpace);
        final CircularProgressIndicator circularProgressIndicator = (CircularProgressIndicator) findViewById(R.id.CircularProgressIndicator);
        final long internalStorageSize = FileUtils.getInternalStorageSize();
        final Runnable updateUI = new Runnable() { // from class: com.winlator.cmod.contentdialog.StorageInfoDialog$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                StorageInfoDialog.lambda$new$0(tvDriveCSize, driveCSize, tvCacheSize, cacheSize, tvTotalSize, totalSize, internalStorageSize, tvUsedSpace, circularProgressIndicator);
            }
        };
        File rootDir = container.getRootDir();
        File driveCDir = new File(rootDir, ".wine/drive_c");
        final File cacheDir = new File(rootDir, ".cache");
        final AtomicLong lastTime = new AtomicLong(System.currentTimeMillis());
        final Callback<Long> onAddSize = new Callback() { // from class: com.winlator.cmod.contentdialog.StorageInfoDialog$$ExternalSyntheticLambda1
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                StorageInfoDialog.lambda$new$1(totalSize, lastTime, activity, updateUI, (Long) obj);
            }
        };
        FileUtils.getSizeAsync(driveCDir, new Callback() { // from class: com.winlator.cmod.contentdialog.StorageInfoDialog$$ExternalSyntheticLambda2
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                StorageInfoDialog.lambda$new$2(driveCSize, onAddSize, (Long) obj);
            }
        });
        FileUtils.getSizeAsync(cacheDir, new Callback() { // from class: com.winlator.cmod.contentdialog.StorageInfoDialog$$ExternalSyntheticLambda3
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                StorageInfoDialog.lambda$new$3(cacheSize, onAddSize, (Long) obj);
            }
        });
        ((TextView) findViewById(R.id.BTCancel)).setText(R.string.clear_cache);
        setOnCancelCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.StorageInfoDialog$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                StorageInfoDialog.lambda$new$4(cacheDir, container);
            }
        });
    }

    static /* synthetic */ void lambda$new$0(TextView tvDriveCSize, AtomicLong driveCSize, TextView tvCacheSize, AtomicLong cacheSize, TextView tvTotalSize, AtomicLong totalSize, long internalStorageSize, TextView tvUsedSpace, CircularProgressIndicator circularProgressIndicator) {
        tvDriveCSize.setText(StringUtils.formatBytes(driveCSize.get()));
        tvCacheSize.setText(StringUtils.formatBytes(cacheSize.get()));
        tvTotalSize.setText(StringUtils.formatBytes(totalSize.get()));
        int progress = Math.toIntExact((totalSize.get() / internalStorageSize) * 100);
        tvUsedSpace.setText(progress + "%");
        circularProgressIndicator.setProgress(progress, true);
    }

    static /* synthetic */ void lambda$new$1(AtomicLong totalSize, AtomicLong lastTime, Activity activity, Runnable updateUI, Long size) {
        totalSize.addAndGet(size.longValue());
        long currTime = System.currentTimeMillis();
        int elapsedTime = (int) (currTime - lastTime.get());
        if (elapsedTime > 30) {
            activity.runOnUiThread(updateUI);
            lastTime.set(currTime);
        }
    }

    static /* synthetic */ void lambda$new$2(AtomicLong driveCSize, Callback onAddSize, Long size) {
        driveCSize.addAndGet(size.longValue());
        onAddSize.call(size);
    }

    static /* synthetic */ void lambda$new$3(AtomicLong cacheSize, Callback onAddSize, Long size) {
        cacheSize.addAndGet(size.longValue());
        onAddSize.call(size);
    }

    static /* synthetic */ void lambda$new$4(File cacheDir, Container container) {
        FileUtils.clear(cacheDir);
        container.putExtra("desktopTheme", null);
        container.saveData();
    }
}
