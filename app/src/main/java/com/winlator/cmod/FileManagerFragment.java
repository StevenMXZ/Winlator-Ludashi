package com.winlator.cmod;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.winlator.cmod.FileManagerFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.GameImageFetcher;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.internal.ws.RealWebSocket;

/* loaded from: classes8.dex */
public class FileManagerFragment extends Fragment {
    private FileAdapter adapter;
    private ContainerManager containerManager;
    private File currentDir;
    private FloatingActionButton fabPaste;
    private ImageView ivDriveIcon;
    private LinearLayout llDriveSelect;
    private ProgressBar progressBar;
    private AlertDialog progressDialog;
    private TextView progressPercent;
    private TextView progressText;
    private RecyclerView recyclerView;
    private TextView tvCurrentPath;
    private TextView tvDriveName;
    private File clipboardFile = null;
    private boolean isCutOperation = false;
    private boolean isOperationCancelled = false;

    /* JADX INFO: Access modifiers changed from: private */
    interface ContainerAction {
        void onContainerSelected(Container container);
    }

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.containerManager = new ContainerManager(getContext());
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getActivity() != null && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle("File Manager");
        }
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(com.ludashi.benchmark.R.layout.file_manager_fragment, container, false);
        this.tvCurrentPath = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVCurrentPath);
        this.recyclerView = (RecyclerView) view.findViewById(com.ludashi.benchmark.R.id.RecyclerViewFiles);
        this.llDriveSelect = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLDriveSelect);
        this.ivDriveIcon = (ImageView) view.findViewById(com.ludashi.benchmark.R.id.IVDriveIcon);
        this.tvDriveName = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVDriveName);
        view.findViewById(com.ludashi.benchmark.R.id.BTUpDir).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda11
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                FileManagerFragment.this.lambda$onCreateView$0(view2);
            }
        });
        if (this.llDriveSelect != null) {
            this.llDriveSelect.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda19
                @Override // android.view.View.OnClickListener
                public final void onClick(View view2) {
                    FileManagerFragment.this.lambda$onCreateView$1(view2);
                }
            });
        }
        this.fabPaste = (FloatingActionButton) view.findViewById(com.ludashi.benchmark.R.id.fabPaste);
        if (this.fabPaste != null) {
            this.fabPaste.setVisibility(8);
            this.fabPaste.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda20
                @Override // android.view.View.OnClickListener
                public final void onClick(View view2) {
                    FileManagerFragment.this.lambda$onCreateView$2(view2);
                }
            });
        }
        this.recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        this.recyclerView.addItemDecoration(new DividerItemDecoration(getContext(), 1));
        this.currentDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!this.currentDir.exists()) {
            this.currentDir = Environment.getExternalStorageDirectory();
        }
        loadDirectory(this.currentDir);
        return view;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$0(View v) {
        navigateUp();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$1(View v) {
        showDriveMenu();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$2(View v) {
        startPasteOperation();
    }

    private void showDriveMenu() {
        PopupMenu popup = new PopupMenu(getContext(), this.llDriveSelect);
        popup.getMenu().add("Drive D: (Downloads)").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda9
            @Override // android.view.MenuItem.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showDriveMenu$3;
                lambda$showDriveMenu$3 = FileManagerFragment.this.lambda$showDriveMenu$3(menuItem);
                return lambda$showDriveMenu$3;
            }
        });
        File storageRoot = new File("/storage");
        File[] externalDrives = storageRoot.listFiles();
        if (externalDrives != null) {
            for (final File drive : externalDrives) {
                if (!drive.getName().equals("emulated") && !drive.getName().equals("self") && 0 == 0) {
                    popup.getMenu().add("External (" + drive.getName() + ")").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda10
                        @Override // android.view.MenuItem.OnMenuItemClickListener
                        public final boolean onMenuItemClick(MenuItem menuItem) {
                            boolean lambda$showDriveMenu$4;
                            lambda$showDriveMenu$4 = FileManagerFragment.this.lambda$showDriveMenu$4(drive, menuItem);
                            return lambda$showDriveMenu$4;
                        }
                    });
                }
            }
        }
        popup.getMenu().add("Drive C: (Wine System)").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda12
            @Override // android.view.MenuItem.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showDriveMenu$5;
                lambda$showDriveMenu$5 = FileManagerFragment.this.lambda$showDriveMenu$5(menuItem);
                return lambda$showDriveMenu$5;
            }
        });
        popup.getMenu().add("Drive Z: (RootFS)").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda13
            @Override // android.view.MenuItem.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showDriveMenu$6;
                lambda$showDriveMenu$6 = FileManagerFragment.this.lambda$showDriveMenu$6(menuItem);
                return lambda$showDriveMenu$6;
            }
        });
        popup.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showDriveMenu$3(MenuItem item) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir.exists()) {
            loadDirectory(downloadDir);
            return true;
        }
        loadDirectory(Environment.getExternalStorageDirectory());
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showDriveMenu$4(File drive, MenuItem item) {
        loadDirectory(drive);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showDriveMenu$5(MenuItem item) {
        handleDriveCSelection();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showDriveMenu$6(MenuItem item) {
        File rootFs = new File(getContext().getFilesDir(), "imagefs");
        if (!rootFs.exists()) {
            Toast.makeText(getContext(), "RootFS not found", 0).show();
            return true;
        }
        loadDirectory(rootFs);
        return true;
    }

    private void handleDriveCSelection() {
        final ArrayList<Container> containers = this.containerManager.getContainers();
        if (containers == null || containers.isEmpty()) {
            new AlertDialog.Builder(getContext()).setTitle("No Containers").setMessage("You need to create a container first to access Drive C:.").setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
            return;
        }
        if (containers.size() == 1) {
            navigateToContainerDriveC(containers.get(0));
            return;
        }
        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            names[i] = containers.get(i).getName();
        }
        new AlertDialog.Builder(getContext()).setTitle("Select Container Drive C:").setItems(names, new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda8
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i2) {
                FileManagerFragment.this.lambda$handleDriveCSelection$7(containers, dialogInterface, i2);
            }
        }).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$handleDriveCSelection$7(ArrayList containers, DialogInterface dialog, int which) {
        navigateToContainerDriveC((Container) containers.get(which));
    }

    private void navigateToContainerDriveC(Container container) {
        String userName = "xuser-" + container.id;
        File driveC = new File(getContext().getFilesDir(), "imagefs/home/" + userName + "/.wine/drive_c");
        if (!driveC.exists()) {
            File defaultDriveC = new File(getContext().getFilesDir(), "imagefs/home/xuser/.wine/drive_c");
            if (defaultDriveC.exists()) {
                driveC = defaultDriveC;
            }
        }
        File windowsDir = new File(driveC, "windows");
        if (driveC.exists() && driveC.isDirectory() && windowsDir.exists()) {
            loadDirectory(driveC);
            Toast.makeText(getContext(), "Opened C: (" + container.getName() + ")", 0).show();
        } else {
            new AlertDialog.Builder(getContext()).setTitle("Drive C: Not Initialized").setMessage("The Wine system files (Drive C:) for '" + container.getName() + "' are missing.\n\nPlease RUN this container once to generate the filesystem.").setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
        }
    }

    private void navigateUp() {
        if (this.currentDir == null) {
            return;
        }
        File parent = this.currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            loadDirectory(parent);
        } else {
            Toast.makeText(getContext(), "Root reached", 0).show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadDirectory(File dir) {
        this.currentDir = dir;
        this.tvCurrentPath.setText(dir.getAbsolutePath());
        updateDriveButtonLabel(dir);
        File[] files = dir.listFiles();
        List<File> fileList = new ArrayList<>();
        if (files != null) {
            fileList.addAll(Arrays.asList(files));
        }
        Collections.sort(fileList, new Comparator() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda14
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                int lambda$loadDirectory$8;
                lambda$loadDirectory$8 = FileManagerFragment.this.lambda$loadDirectory$8((File) obj, (File) obj2);
                return lambda$loadDirectory$8;
            }
        });
        this.adapter = new FileAdapter(fileList);
        this.recyclerView.setAdapter(this.adapter);
        if (this.fabPaste != null) {
            this.fabPaste.setVisibility(this.clipboardFile != null ? 0 : 8);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ int lambda$loadDirectory$8(File f1, File f2) {
        if (f1.isDirectory() && !f2.isDirectory()) {
            return -1;
        }
        if (!f1.isDirectory() && f2.isDirectory()) {
            return 1;
        }
        if (!f1.isDirectory() && !f2.isDirectory()) {
            boolean isExe1 = isExecutable(f1);
            boolean isExe2 = isExecutable(f2);
            if (isExe1 && !isExe2) {
                return -1;
            }
            if (!isExe1 && isExe2) {
                return 1;
            }
        }
        return f1.getName().compareToIgnoreCase(f2.getName());
    }

    private void updateDriveButtonLabel(File dir) {
        if (this.tvDriveName == null || this.ivDriveIcon == null) {
            return;
        }
        String path = dir.getAbsolutePath();
        if (path.contains("/drive_c")) {
            this.tvDriveName.setText("Drive C:");
            this.ivDriveIcon.setImageResource(com.ludashi.benchmark.R.drawable.icon_wine);
            return;
        }
        if (path.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            this.tvDriveName.setText("Drive D:");
            this.ivDriveIcon.setImageResource(android.R.drawable.stat_sys_phone_call);
        } else if (path.startsWith("/storage") && !path.contains("emulated")) {
            this.tvDriveName.setText("External");
            this.ivDriveIcon.setImageResource(android.R.drawable.stat_sys_data_bluetooth);
        } else {
            this.tvDriveName.setText("System (Z:)");
            this.ivDriveIcon.setImageResource(android.R.drawable.stat_notify_sdcard);
        }
    }

    private void performContainerAction(File file, final ContainerAction action) {
        final ArrayList<Container> containers = this.containerManager.getContainers();
        if (containers == null || containers.isEmpty()) {
            Toast.makeText(getContext(), "Create a container first!", 0).show();
            return;
        }
        if (containers.size() == 1) {
            action.onContainerSelected(containers.get(0));
            return;
        }
        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            names[i] = containers.get(i).getName();
        }
        new AlertDialog.Builder(getContext()).setTitle("Select Container").setItems(names, new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda17
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i2) {
                FileManagerFragment.ContainerAction.this.onContainerSelected((Container) containers.get(i2));
            }
        }).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: runFileDirectly, reason: merged with bridge method [inline-methods] */
    public void lambda$showFileOptions$18(File file, Container container) {
        try {
            File cacheDir = getContext().getCacheDir();
            File tempShortcut = new File(cacheDir, "temp_run.desktop");
            PrintWriter writer = new PrintWriter(new FileWriter(tempShortcut));
            try {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + file.getName());
                writer.println("Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine \"" + file.getAbsolutePath() + "\"");
                writer.println("Type=Application");
                writer.println("container_id:" + container.id);
                writer.close();
                Intent intent = new Intent();
                intent.setClassName(getContext().getPackageName(), "com.winlator.cmod.XServerDisplayActivity");
                intent.putExtra("container_id", container.id);
                intent.putExtra("shortcut_path", tempShortcut.getAbsolutePath());
                startActivity(intent);
            } finally {
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Error launching: " + e.getMessage(), 1).show();
            e.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: createShortcutDirectly, reason: merged with bridge method [inline-methods] */
    public void lambda$showFileOptions$20(File file, Container container) {
        try {
            String displayName = getSmartDisplayName(file);
            String unixPath = file.getAbsolutePath();
            File shortcutsDir = container.getDesktopDir();
            if (!shortcutsDir.exists()) {
                shortcutsDir.mkdirs();
            }
            File desktopFile = new File(shortcutsDir, displayName + ".desktop");
            PrintWriter writer = new PrintWriter(new FileWriter(desktopFile));
            try {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + displayName);
                writer.println("Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine \"" + unixPath + "\"");
                writer.println("Type=Application");
                writer.println("container_id:" + container.id);
                writer.close();
                Toast.makeText(getContext(), "Shortcut created!", 0).show();
                File iconsDir = new File(Environment.getExternalStorageDirectory(), "Winlator/icons");
                if (!iconsDir.exists()) {
                    iconsDir.mkdirs();
                }
                GameImageFetcher.fetchImage(displayName, new File(iconsDir, displayName + ".png"), false, null);
            } finally {
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void copyToClipboard(File file, boolean isCut) {
        this.clipboardFile = file;
        this.isCutOperation = isCut;
        if (this.fabPaste != null) {
            this.fabPaste.setVisibility(0);
        }
        Toast.makeText(getContext(), (isCut ? "Cut: " : "Copied: ") + file.getName(), 0).show();
    }

    private void startPasteOperation() {
        if (this.clipboardFile == null || !this.clipboardFile.exists()) {
            Toast.makeText(getContext(), "Nothing to paste", 0).show();
            return;
        }
        final File source = this.clipboardFile;
        final File dest = new File(this.currentDir, source.getName());
        if (dest.exists()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("File Conflict");
            builder.setMessage("The destination \"" + dest.getName() + "\" already exists.");
            builder.setPositiveButton("Replace", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda15
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    FileManagerFragment.this.lambda$startPasteOperation$10(dest, source, dialogInterface, i);
                }
            });
            builder.setNeutralButton("Rename", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda16
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    FileManagerFragment.this.lambda$startPasteOperation$11(source, dialogInterface, i);
                }
            });
            builder.setNegativeButton("Cancel", (DialogInterface.OnClickListener) null);
            builder.show();
            return;
        }
        executePaste(source, dest);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startPasteOperation$10(File dest, File source, DialogInterface dialog, int which) {
        deleteRecursive(dest);
        executePaste(source, dest);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startPasteOperation$11(File source, DialogInterface dialog, int which) {
        File newDest = getUniqueDestination(this.currentDir, source.getName());
        executePaste(source, newDest);
    }

    private File getUniqueDestination(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) {
            return dest;
        }
        String baseName = name;
        String extension = "";
        int dotIndex = name.lastIndexOf(46);
        if (dotIndex > 0) {
            baseName = name.substring(0, dotIndex);
            extension = name.substring(dotIndex);
        }
        int counter = 1;
        while (dest.exists()) {
            dest = new File(dir, baseName + " (" + counter + ")" + extension);
            counter++;
        }
        return dest;
    }

    private void executePaste(final File source, final File dest) {
        if (this.isCutOperation && source.renameTo(dest)) {
            Toast.makeText(getContext(), "Moved instantly", 0).show();
            finishPaste(true);
        } else {
            showProgressDialog(this.isCutOperation ? "Moving..." : "Copying...");
            this.isOperationCancelled = false;
            new Thread(new Runnable() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    FileManagerFragment.this.lambda$executePaste$14(source, dest);
                }
            }).start();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$executePaste$14(File source, final File dest) {
        try {
            long totalBytes = getFolderSize(source);
            AtomicLong copiedBytes = new AtomicLong(0L);
            copyRecursiveWithProgress(source, dest, totalBytes, copiedBytes);
            if (this.isCutOperation && !this.isOperationCancelled) {
                long srcSize = getFolderSize(source);
                long dstSize = getFolderSize(dest);
                if (srcSize > 0 && srcSize == dstSize && source.canRead()) {
                    deleteRecursive(source);
                } else {
                    throw new IOException("Safety Stop: Sizes mismatch (" + srcSize + " vs " + dstSize + ") or source unreadable.");
                }
            }
            new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda21
                @Override // java.lang.Runnable
                public final void run() {
                    FileManagerFragment.this.lambda$executePaste$12(dest);
                }
            });
        } catch (Exception e) {
            final String errorMsg = e.getMessage();
            new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda22
                @Override // java.lang.Runnable
                public final void run() {
                    FileManagerFragment.this.lambda$executePaste$13(errorMsg, dest);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$executePaste$12(File dest) {
        dismissProgressDialog();
        if (!this.isOperationCancelled) {
            Toast.makeText(getContext(), "Success!", 0).show();
            finishPaste(this.isCutOperation);
        } else {
            Toast.makeText(getContext(), "Cancelled", 0).show();
            deleteRecursive(dest);
            loadDirectory(this.currentDir);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$executePaste$13(String errorMsg, File dest) {
        dismissProgressDialog();
        Toast.makeText(getContext(), "Error: " + errorMsg + ". Source preserved.", 1).show();
        deleteRecursive(dest);
        loadDirectory(this.currentDir);
    }

    private void finishPaste(boolean clearClipboard) {
        if (clearClipboard) {
            this.clipboardFile = null;
            if (this.fabPaste != null) {
                this.fabPaste.setVisibility(8);
            }
        }
        loadDirectory(this.currentDir);
    }

    private void showProgressDialog(String title) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(title);
        builder.setCancelable(false);
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(1);
        layout.setPadding(50, 40, 50, 20);
        this.progressPercent = new TextView(getContext());
        this.progressPercent.setText("0%");
        this.progressPercent.setTextAlignment(4);
        this.progressPercent.setTextSize(18.0f);
        layout.addView(this.progressPercent);
        this.progressBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        this.progressBar.setIndeterminate(false);
        this.progressBar.setMax(100);
        layout.addView(this.progressBar);
        this.progressText = new TextView(getContext());
        this.progressText.setText("Calculating...");
        this.progressText.setPadding(0, 20, 0, 0);
        layout.addView(this.progressText);
        builder.setView(layout);
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda6
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                FileManagerFragment.this.lambda$showProgressDialog$15(dialogInterface, i);
            }
        });
        this.progressDialog = builder.create();
        this.progressDialog.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showProgressDialog$15(DialogInterface d, int w) {
        this.isOperationCancelled = true;
    }

    private void dismissProgressDialog() {
        if (this.progressDialog != null && this.progressDialog.isShowing()) {
            this.progressDialog.dismiss();
        }
    }

    private void updateProgress(long current, long total) {
        final int percent = total > 0 ? (int) ((100 * current) / total) : 0;
        final String status = formatSize(current) + " / " + formatSize(total);
        new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda18
            @Override // java.lang.Runnable
            public final void run() {
                FileManagerFragment.this.lambda$updateProgress$16(percent, status);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$updateProgress$16(int percent, String status) {
        if (this.progressBar != null) {
            this.progressBar.setProgress(percent);
        }
        if (this.progressPercent != null) {
            this.progressPercent.setText(percent + "%");
        }
        if (this.progressText != null) {
            this.progressText.setText(status);
        }
    }

    private long getFolderSize(File file) {
        long size = 0;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null) {
                return 0L;
            }
            for (File child : files) {
                size += getFolderSize(child);
            }
            return size;
        }
        long size2 = file.length();
        return size2;
    }

    private void copyRecursiveWithProgress(File src, File dst, long totalBytes, AtomicLong copiedBytes) throws IOException {
        if (this.isOperationCancelled) {
            return;
        }
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("Failed to create dir: " + dst.getName());
            }
            String[] children = src.list();
            if (children != null) {
                for (String child : children) {
                    copyRecursiveWithProgress(new File(src, child), new File(dst, child), totalBytes, copiedBytes);
                }
                return;
            }
            return;
        }
        copyFileSafe(src, dst, totalBytes, copiedBytes);
    }

    /* JADX WARN: Removed duplicated region for block: B:27:0x0075  */
    /* JADX WARN: Removed duplicated region for block: B:29:0x007a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void copyFileSafe(java.io.File r19, java.io.File r20, long r21, java.util.concurrent.atomic.AtomicLong r23) throws java.io.IOException {
        /*
            r18 = this;
            r1 = r18
            r2 = 0
            r3 = 0
            java.io.FileInputStream r0 = new java.io.FileInputStream     // Catch: java.lang.Throwable -> L6a
            r4 = r19
            r0.<init>(r4)     // Catch: java.lang.Throwable -> L68
            r2 = r0
            java.io.FileOutputStream r0 = new java.io.FileOutputStream     // Catch: java.lang.Throwable -> L68
            r5 = r20
            r0.<init>(r5)     // Catch: java.lang.Throwable -> L66
            r3 = r0
            r0 = 8192(0x2000, float:1.14794E-41)
            byte[] r0 = new byte[r0]     // Catch: java.lang.Throwable -> L66
            r6 = 1048576(0x100000, double:5.180654E-318)
        L1b:
            int r8 = r2.read(r0)     // Catch: java.lang.Throwable -> L66
            r9 = r8
            if (r8 <= 0) goto L4f
            boolean r8 = r1.isOperationCancelled     // Catch: java.lang.Throwable -> L66
            if (r8 == 0) goto L2b
            r14 = r21
            r8 = r23
            goto L53
        L2b:
            r8 = 0
            r3.write(r0, r8, r9)     // Catch: java.lang.Throwable -> L66
            long r10 = r23.get()     // Catch: java.lang.Throwable -> L66
            long r12 = (long) r9
            r8 = r23
            long r12 = r8.addAndGet(r12)     // Catch: java.lang.Throwable -> L4b
            long r14 = r12 / r6
            long r16 = r10 / r6
            int r14 = (r14 > r16 ? 1 : (r14 == r16 ? 0 : -1))
            if (r14 <= 0) goto L48
            r14 = r21
            r1.updateProgress(r12, r14)     // Catch: java.lang.Throwable -> L64
            goto L4a
        L48:
            r14 = r21
        L4a:
            goto L1b
        L4b:
            r0 = move-exception
            r14 = r21
            goto L73
        L4f:
            r14 = r21
            r8 = r23
        L53:
            r3.flush()     // Catch: java.lang.Throwable -> L64
            java.io.FileDescriptor r10 = r3.getFD()     // Catch: java.lang.Throwable -> L64
            r10.sync()     // Catch: java.lang.Throwable -> L64
            r2.close()
            r3.close()
            return
        L64:
            r0 = move-exception
            goto L73
        L66:
            r0 = move-exception
            goto L6f
        L68:
            r0 = move-exception
            goto L6d
        L6a:
            r0 = move-exception
            r4 = r19
        L6d:
            r5 = r20
        L6f:
            r14 = r21
            r8 = r23
        L73:
            if (r2 == 0) goto L78
            r2.close()
        L78:
            if (r3 == 0) goto L7d
            r3.close()
        L7d:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.FileManagerFragment.copyFileSafe(java.io.File, java.io.File, long, java.util.concurrent.atomic.AtomicLong):void");
    }

    private void deleteRecursive(File fileOrDirectory) {
        File[] children;
        if (fileOrDirectory.isDirectory() && (children = fileOrDirectory.listFiles()) != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private void renameFile(final File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Rename");
        final EditText input = new EditText(getContext());
        input.setText(file.getName());
        builder.setView(input);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda0
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                FileManagerFragment.this.lambda$renameFile$17(input, file, dialogInterface, i);
            }
        });
        builder.setNegativeButton("Cancel", (DialogInterface.OnClickListener) null);
        builder.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$renameFile$17(EditText input, File file, DialogInterface dialog, int which) {
        String newName = input.getText().toString();
        File newFile = new File(file.getParent(), newName);
        if (file.renameTo(newFile)) {
            loadDirectory(this.currentDir);
        } else {
            Toast.makeText(getContext(), "Rename failed", 0).show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isExecutable(File f) {
        String name = f.getName().toLowerCase();
        return name.endsWith(".exe") || name.endsWith(".msi") || name.endsWith(".bat");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showFileOptions(final File file, View anchor) {
        PopupMenu popup = new PopupMenu(getContext(), anchor);
        if (isExecutable(file)) {
            popup.getMenu().add("Run / Open").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda23
                @Override // android.view.MenuItem.OnMenuItemClickListener
                public final boolean onMenuItemClick(MenuItem menuItem) {
                    boolean lambda$showFileOptions$19;
                    lambda$showFileOptions$19 = FileManagerFragment.this.lambda$showFileOptions$19(file, menuItem);
                    return lambda$showFileOptions$19;
                }
            });
            popup.getMenu().add("Create Shortcut").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda24
                @Override // android.view.MenuItem.OnMenuItemClickListener
                public final boolean onMenuItemClick(MenuItem menuItem) {
                    boolean lambda$showFileOptions$21;
                    lambda$showFileOptions$21 = FileManagerFragment.this.lambda$showFileOptions$21(file, menuItem);
                    return lambda$showFileOptions$21;
                }
            });
        }
        popup.getMenu().add("Copy").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda25
            @Override // android.view.MenuItem.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showFileOptions$22;
                lambda$showFileOptions$22 = FileManagerFragment.this.lambda$showFileOptions$22(file, menuItem);
                return lambda$showFileOptions$22;
            }
        });
        popup.getMenu().add("Cut (Move)").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda26
            @Override // android.view.MenuItem.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showFileOptions$23;
                lambda$showFileOptions$23 = FileManagerFragment.this.lambda$showFileOptions$23(file, menuItem);
                return lambda$showFileOptions$23;
            }
        });
        popup.getMenu().add("Rename").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda1
            @Override // android.view.MenuItem.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showFileOptions$24;
                lambda$showFileOptions$24 = FileManagerFragment.this.lambda$showFileOptions$24(file, menuItem);
                return lambda$showFileOptions$24;
            }
        });
        popup.getMenu().add("Delete").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda2
            @Override // android.view.MenuItem.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$showFileOptions$26;
                lambda$showFileOptions$26 = FileManagerFragment.this.lambda$showFileOptions$26(file, menuItem);
                return lambda$showFileOptions$26;
            }
        });
        popup.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showFileOptions$19(final File file, MenuItem item) {
        performContainerAction(file, new ContainerAction() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda7
            @Override // com.winlator.cmod.FileManagerFragment.ContainerAction
            public final void onContainerSelected(Container container) {
                FileManagerFragment.this.lambda$showFileOptions$18(file, container);
            }
        });
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showFileOptions$21(final File file, MenuItem item) {
        performContainerAction(file, new ContainerAction() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda3
            @Override // com.winlator.cmod.FileManagerFragment.ContainerAction
            public final void onContainerSelected(Container container) {
                FileManagerFragment.this.lambda$showFileOptions$20(file, container);
            }
        });
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showFileOptions$22(File file, MenuItem item) {
        copyToClipboard(file, false);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showFileOptions$23(File file, MenuItem item) {
        copyToClipboard(file, true);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showFileOptions$24(File file, MenuItem item) {
        renameFile(file);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$showFileOptions$26(final File file, MenuItem item) {
        new AlertDialog.Builder(getContext()).setTitle("Delete").setMessage("Are you sure you want to delete " + file.getName() + "?").setPositiveButton("Yes", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$$ExternalSyntheticLambda5
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                FileManagerFragment.this.lambda$showFileOptions$25(file, dialogInterface, i);
            }
        }).setNegativeButton("No", (DialogInterface.OnClickListener) null).show();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showFileOptions$25(File file, DialogInterface d, int w) {
        deleteRecursive(file);
        loadDirectory(this.currentDir);
    }

    private String getSmartDisplayName(File file) {
        File parent;
        File grandParent;
        String filename = cleanGameName(file.getName());
        String lowerName = filename.toLowerCase();
        List<String> genericNames = Arrays.asList("game", "launcher", "setup", "installer", "start", "run", "speed", "update", "patch", "loader", "client", "app", "main", "boot", "play", "application", "shipping", "x64", "x86", "win64", "win32", "binaries");
        boolean isModOrGeneric = lowerName.contains("mod") || lowerName.contains("fix") || lowerName.contains("crack") || lowerName.contains("patch");
        if (!isModOrGeneric && filename.length() < 4) {
            isModOrGeneric = true;
        }
        if (!isModOrGeneric) {
            for (String gen : genericNames) {
                if (lowerName.equals(gen) || lowerName.startsWith(gen + " ")) {
                    isModOrGeneric = true;
                    break;
                }
            }
        }
        if (isModOrGeneric && (parent = file.getParentFile()) != null) {
            String parentName = cleanGameName(parent.getName());
            List<String> genericFolders = Arrays.asList("bin", "bin32", "bin64", "system", "release", "retail", "win64");
            if (genericFolders.contains(parentName.toLowerCase()) && (grandParent = parent.getParentFile()) != null) {
                return cleanGameName(grandParent.getName());
            }
            return parentName;
        }
        return filename;
    }

    private String cleanGameName(String filename) {
        String name = filename;
        int pos = name.lastIndexOf(".");
        if (pos > 0) {
            name = name.substring(0, pos);
        }
        return name.replace("_", " ").replace(".", " ").replace("-", " ").replaceAll("(?i)\\b(v\\d+|repack|setup|installer|portable|goty|edition)\\b", "").replaceAll("[^a-zA-Z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    /* JADX INFO: Access modifiers changed from: private */
    class FileAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<File> files;

        public FileAdapter(List<File> files) {
            this.files = files;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(com.ludashi.benchmark.R.layout.file_list_item, parent, false);
            return new ViewHolder(v);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final File file = this.files.get(position);
            holder.tvName.setText(file.getName());
            if (file.isDirectory()) {
                holder.ivIcon.setImageResource(com.ludashi.benchmark.R.drawable.icon_open);
                holder.tvDetails.setText("Folder");
                holder.btMenu.setVisibility(0);
                holder.btMenu.setImageResource(android.R.drawable.ic_menu_more);
                holder.btMenu.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$FileAdapter$$ExternalSyntheticLambda0
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        FileManagerFragment.FileAdapter.this.lambda$onBindViewHolder$0(file, holder, view);
                    }
                });
                holder.itemView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$FileAdapter$$ExternalSyntheticLambda1
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        FileManagerFragment.FileAdapter.this.lambda$onBindViewHolder$1(file, view);
                    }
                });
                return;
            }
            holder.tvDetails.setText(FileManagerFragment.this.formatSize(file.length()));
            boolean isExe = FileManagerFragment.this.isExecutable(file);
            if (isExe) {
                holder.ivIcon.setImageResource(com.ludashi.benchmark.R.drawable.icon_wine);
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_agenda);
            }
            holder.btMenu.setVisibility(0);
            holder.btMenu.setImageResource(android.R.drawable.ic_menu_more);
            holder.btMenu.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$FileAdapter$$ExternalSyntheticLambda2
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    FileManagerFragment.FileAdapter.this.lambda$onBindViewHolder$2(file, holder, view);
                }
            });
            holder.itemView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.FileManagerFragment$FileAdapter$$ExternalSyntheticLambda3
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    FileManagerFragment.FileAdapter.this.lambda$onBindViewHolder$3(file, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(File file, ViewHolder holder, View v) {
            FileManagerFragment.this.showFileOptions(file, holder.btMenu);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$1(File file, View v) {
            FileManagerFragment.this.loadDirectory(file);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$2(File file, ViewHolder holder, View v) {
            FileManagerFragment.this.showFileOptions(file, holder.btMenu);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$3(File file, View v) {
            Toast.makeText(FileManagerFragment.this.getContext(), file.getName(), 0).show();
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return this.files.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView btMenu;
            ImageView ivIcon;
            TextView tvDetails;
            TextView tvName;

            ViewHolder(View v) {
                super(v);
                this.tvName = (TextView) v.findViewById(com.ludashi.benchmark.R.id.TVFileName);
                this.tvDetails = (TextView) v.findViewById(com.ludashi.benchmark.R.id.TVFileDetails);
                this.ivIcon = (ImageView) v.findViewById(com.ludashi.benchmark.R.id.IVIcon);
                this.btMenu = (ImageView) v.findViewById(com.ludashi.benchmark.R.id.BTFileMenu);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String formatSize(long size) {
        if (size < RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE) {
            return size + " B";
        }
        int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format("%.1f %sB", Double.valueOf(size / (1 << (z * 10))), Character.valueOf(" KMGTPE".charAt(z)));
    }
}
