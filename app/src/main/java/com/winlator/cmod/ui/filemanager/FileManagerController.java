package com.winlator.cmod.ui.filemanager;

import com.winlator.cmod.ui.UiStrings;
import com.winlator.cmod.R;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Toast;
import com.winlator.cmod.ui.toast.WinToast;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.ui.ThemedProgressHost;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

// File Manager screen logic — formerly FileManagerFragment. Same behaviour; it is now a plain
// object owned by FileManagerRoute (FileManagerRoute.kt), which MainShell hosts as a tab, and it
// hands each rebuilt model to the route through modelSink instead of updating a ComposeView.
// The only Fragment-specific pieces that changed: requireContext()/requireActivity() are the
// activity passed in, and the old attached check is the route's active flag.
public class FileManagerController {
    private static final String DRIVE_ID_D = "D";
    private static final String DRIVE_ID_C = "C";
    private static final String DRIVE_ID_Z = "Z";
    private static final String DRIVE_ID_SCAN = "scan";

    private final AppCompatActivity activity;
    private final Consumer<FileManagerModel> modelSink;
    private boolean active;
    private List<File> discoveredExternalStorageRoots;
    private File currentDir;
    private File currentDriveRoot;
    private ContainerManager containerManager;
    private File clipboardFile = null;
    private boolean isCutOperation = false;
    private View progressOverlay;
    private volatile boolean isOperationCancelled = false;

    // A folder another screen asked to show (the game page's "Open saves folder"); taken by the
    // next refresh, i.e. when the File Manager tab is shown.
    private static volatile File pendingOpenDir;

    public static void requestOpen(File dir) {
        pendingOpenDir = dir;
    }

    private interface ContainerAction {
        void onContainerSelected(Container container);
    }

    public FileManagerController(AppCompatActivity activity, Consumer<FileManagerModel> modelSink) {
        this.activity = activity;
        UiStrings.install(activity);
        this.modelSink = modelSink;
        containerManager = new ContainerManager(activity);

        File startDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!startDir.exists()) startDir = Environment.getExternalStorageDirectory();
        currentDriveRoot = inferDriveRoot(startDir);
        currentDir = startDir;
    }

    // True while the route is in composition; model pushes are dropped otherwise — async
    // paste/progress callbacks can land after the screen is gone.
    public void setActive(boolean active) {
        this.active = active;
    }

    // Rebuilds and publishes the model (first show, and whenever the tab is shown again).
    public String getCurrentPath() { return currentDir != null ? currentDir.getAbsolutePath() : ""; }
    public String getDriveRootPath() { return currentDriveRoot != null ? currentDriveRoot.getAbsolutePath() : ""; }
    public void openAt(String path, String root) {
        if (path != null && new File(path).isDirectory()) currentDir = new File(path);
        currentDriveRoot = root != null && !root.isEmpty() && new File(root).isDirectory() ? new File(root) : inferDriveRoot(currentDir);
    }
    public void refresh() {
        File requested = pendingOpenDir;
        if (requested != null) {
            pendingOpenDir = null;
            if (requested.isDirectory()) {
                currentDriveRoot = inferDriveRoot(requested);
                currentDir = requested;
            }
        }
        pushState();
    }

    public FileManagerCallbacks createCallbacks() {
        return new FileManagerCallbacks() {
            @Override
            public void onUpDir() {
                navigateUp();
            }

            @Override
            public void onDriveOptionSelected(String id) {
                handleDriveOptionSelected(id);
            }

            @Override
            public void onOpenDirectory(String path) {
                loadDirectory(new File(path));
            }

            @Override
            public void onRunFile(String path) {
                File file = new File(path);
                performContainerAction(file, container -> runFileDirectly(file, container));
            }

            @Override
            public void onAddGame(String path) {
                File file = new File(path);
                performContainerAction(file, container -> createShortcutDirectly(file, container));
            }

            @Override
            public void onCopyFile(String path) {
                copyToClipboard(new File(path), false);
            }

            @Override
            public void onCutFile(String path) {
                copyToClipboard(new File(path), true);
            }

            @Override
            public void onRenameFile(String path) {
                renameFile(new File(path));
            }

            @Override
            public void onDeleteFile(String path) {
                confirmDelete(new File(path));
            }

            @Override
            public void onPasteClick() {
                startPasteOperation();
            }
        };
    }

    // Rebuilds the whole screen state from the current Java-side fields and pushes it into the
    // already-created Compose view. Every method below that changes directory, drive, clipboard
    // or external-storage state ends by calling this instead of touching individual Views.
    private void pushState() {
        if (!active || currentDir == null) return;
        modelSink.accept(buildModel());
    }

    private FileManagerModel buildModel() {
        String path = currentDir.getAbsolutePath();

        String driveTitle;
        FileManagerIcon driveIcon;
        String normalized = normalizeFilePath(path);
        String primary = normalizeFilePath(Environment.getExternalStorageDirectory().getAbsolutePath());
        if (normalized.contains("/.wine/drive_c")) {
            driveTitle = "Drive C:";
            driveIcon = FileManagerIcon.WINE;
        } else if (normalized.equals(primary) || normalized.startsWith(primary + File.separator)) {
            driveTitle = "Drive D:";
            driveIcon = FileManagerIcon.INTERNAL_STORAGE;
        } else if (normalized.startsWith("/storage/") && !normalized.startsWith("/storage/emulated")) {
            driveTitle = "External Storage";
            driveIcon = FileManagerIcon.INTERNAL_STORAGE;
        } else {
            driveTitle = "Drive Z:";
            driveIcon = FileManagerIcon.SYSTEM_DRIVE;
        }

        String storageUsedText = "";
        int storagePercent = 0;
        File storageTarget = currentDriveRoot != null ? currentDriveRoot : currentDir;
        if (storageTarget != null && storageTarget.exists()) {
            try {
                StatFs stat = new StatFs(storageTarget.getAbsolutePath());
                long total = stat.getTotalBytes();
                long free = stat.getAvailableBytes();
                long used = Math.max(0L, total - free);
                storagePercent = total > 0 ? Math.min(100, Math.round((used * 100f) / total)) : 0;
                storageUsedText = Formatter.formatShortFileSize(activity, used) + " / " +
                        Formatter.formatShortFileSize(activity, total);
            } catch (Exception ignored) {}
        }

        return new FileManagerModel(
                path,
                buildEntries(currentDir),
                driveTitle,
                driveIcon,
                buildDriveOptions(),
                storageUsedText,
                storagePercent,
                clipboardFile != null
        );
    }

    private List<FileEntryUiModel> buildEntries(File dir) {
        File[] files = dir.listFiles();
        List<File> fileList = new ArrayList<>();
        if (files != null) fileList.addAll(Arrays.asList(files));

        Collections.sort(fileList, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            if (!f1.isDirectory() && !f2.isDirectory()) {
                boolean isExe1 = isExecutable(f1);
                boolean isExe2 = isExecutable(f2);
                if (isExe1 && !isExe2) return -1;
                if (!isExe1 && isExe2) return 1;
            }
            return f1.getName().compareToIgnoreCase(f2.getName());
        });

        List<FileEntryUiModel> result = new ArrayList<>();
        for (File file : fileList) {
            boolean isDir = file.isDirectory();
            boolean executable = !isDir && isExecutable(file);
            FileManagerIcon icon;
            String iconCachePath = null;
            if (isDir) {
                icon = FileManagerIcon.FOLDER;
            } else if (executable) {
                icon = FileManagerIcon.WINE;
                iconCachePath = getFileIconCacheFile(file).getAbsolutePath();
            } else {
                icon = FileManagerIcon.FILE;
            }
            result.add(new FileEntryUiModel(
                    file.getAbsolutePath(), file.getName(), isDir, executable,
                    file.length(), file.lastModified(), icon, iconCachePath
            ));
        }
        return result;
    }

    private List<DriveOptionUiModel> buildDriveOptions() {
        List<DriveOptionUiModel> options = new ArrayList<>();

        File dRoot = Environment.getExternalStorageDirectory();
        options.add(new DriveOptionUiModel(DRIVE_ID_D, "Drive D:", "Downloads", samePath(currentDriveRoot, dRoot)));

        boolean inDriveC = currentDir != null && normalizeFilePath(currentDir.getAbsolutePath()).contains("/.wine/drive_c");
        options.add(new DriveOptionUiModel(DRIVE_ID_C, "Drive C:", "Wine System", inDriveC));

        File rootFs = new File(activity.getFilesDir(), "imagefs");
        options.add(new DriveOptionUiModel(DRIVE_ID_Z, "Drive Z:", "RootFS", samePath(currentDriveRoot, rootFs)));

        if (discoveredExternalStorageRoots != null) {
            for (File external : discoveredExternalStorageRoots) {
                options.add(new DriveOptionUiModel(
                        external.getAbsolutePath(), "External Storage", external.getName(),
                        samePath(currentDriveRoot, external)));
            }
        }

        options.add(new DriveOptionUiModel(
                DRIVE_ID_SCAN,
                "Add External Storage",
                discoveredExternalStorageRoots == null ? "Find SD card or USB storage" : "Scan again",
                false));
        return options;
    }

    private void handleDriveOptionSelected(String id) {
        if (DRIVE_ID_D.equals(id)) {
            File dRoot = Environment.getExternalStorageDirectory();
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            openDrive(downloads.exists() ? downloads : dRoot, dRoot);
        } else if (DRIVE_ID_C.equals(id)) {
            handleDriveCSelection();
        } else if (DRIVE_ID_Z.equals(id)) {
            File rootFs = new File(activity.getFilesDir(), "imagefs");
            if (rootFs.exists()) openDrive(rootFs, rootFs);
            else WinToast.show(activity, UiStrings.get(R.string.ui_rootfs_not_found), Toast.LENGTH_SHORT);
        } else if (DRIVE_ID_SCAN.equals(id)) {
            discoverExternalStorage();
        } else {
            File external = new File(id);
            openDrive(external, external);
        }
    }

    private String normalizeFilePath(String path) {
        if (path == null) return "";
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return new File(path).getAbsolutePath();
        }
    }

    private boolean samePath(File first, File second) {
        if (first == null || second == null) return false;
        return normalizeFilePath(first.getAbsolutePath()).equals(normalizeFilePath(second.getAbsolutePath()));
    }

    private boolean isWithinRoot(File file, File root) {
        if (file == null || root == null) return true;
        String filePath = normalizeFilePath(file.getAbsolutePath());
        String rootPath = normalizeFilePath(root.getAbsolutePath());
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private List<File> getExternalStorageRoots() {
        ArrayList<File> result = new ArrayList<>();
        java.util.LinkedHashMap<String, File> roots = new java.util.LinkedHashMap<>();
        String primaryPath = normalizeFilePath(Environment.getExternalStorageDirectory().getAbsolutePath());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.storage.StorageManager storageManager =
                    (android.os.storage.StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);
            if (storageManager != null) {
                for (android.os.storage.StorageVolume volume : storageManager.getStorageVolumes()) {
                    if (volume.isPrimary()) continue;
                    File directory = volume.getDirectory();
                    if (directory != null && directory.exists()) {
                        roots.put(normalizeFilePath(directory.getAbsolutePath()), directory);
                    }
                }
            }
        }

        File[] appExternalDirs = activity.getExternalFilesDirs(null);
        if (appExternalDirs != null) {
            for (File appDir : appExternalDirs) {
                if (appDir == null) continue;
                File cursor = appDir;
                while (cursor != null && cursor.getParentFile() != null) {
                    File parent = cursor.getParentFile();
                    if ("/storage".equals(parent.getAbsolutePath())) {
                        String path = normalizeFilePath(cursor.getAbsolutePath());
                        if (!path.equals(primaryPath)) roots.put(path, cursor);
                        break;
                    }
                    cursor = parent;
                }
            }
        }

        File[] entries = new File("/storage").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                String name = entry.getName();
                if (!entry.isDirectory() || name.equals("emulated") || name.equals("self")) continue;
                String path = normalizeFilePath(entry.getAbsolutePath());
                if (!path.equals(primaryPath) && entry.exists()) roots.put(path, entry);
            }
        }

        result.addAll(roots.values());
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    private void discoverExternalStorage() {
        discoveredExternalStorageRoots = getExternalStorageRoots();
        pushState();
        if (discoveredExternalStorageRoots.isEmpty()) {
            WinToast.show(activity, UiStrings.get(R.string.ui_no_external_storage_found), Toast.LENGTH_SHORT);
        }
    }

    private void openDrive(File directory, File driveRoot) {
        if (directory == null || !directory.exists() || !directory.isDirectory() || !directory.canRead()) {
            WinToast.show(activity, UiStrings.get(R.string.ui_storage_is_not_accessible), Toast.LENGTH_SHORT);
            return;
        }
        currentDriveRoot = driveRoot != null ? driveRoot : inferDriveRoot(directory);
        loadDirectory(directory);
    }

    private File inferDriveRoot(File directory) {
        if (directory == null) return Environment.getExternalStorageDirectory();
        String path = normalizeFilePath(directory.getAbsolutePath());
        String primary = normalizeFilePath(Environment.getExternalStorageDirectory().getAbsolutePath());
        if (path.equals(primary) || path.startsWith(primary + File.separator)) {
            return Environment.getExternalStorageDirectory();
        }

        int driveCIndex = path.indexOf("/.wine/drive_c");
        if (driveCIndex >= 0) {
            return new File(path.substring(0, driveCIndex + "/.wine/drive_c".length()));
        }

        if (path.startsWith("/storage/")) {
            String rest = path.substring("/storage/".length());
            int slash = rest.indexOf('/');
            String volume = slash >= 0 ? rest.substring(0, slash) : rest;
            if (!volume.isEmpty() && !volume.equals("emulated") && !volume.equals("self")) {
                File external = new File("/storage/" + volume);
                if (external.exists()) return external;
            }
        }

        File imageFs = new File(activity.getFilesDir(), "imagefs");
        String imageFsPath = normalizeFilePath(imageFs.getAbsolutePath());
        if (path.equals(imageFsPath) || path.startsWith(imageFsPath + File.separator)) return imageFs;
        return directory;
    }

    private void handleDriveCSelection() {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers == null || containers.isEmpty()) {
            ThemedAlertHost.info(activity, "No Containers", "You need to create a container first to access Drive C:.");
            return;
        }

        if (containers.size() == 1) {
            navigateToContainerDriveC(containers.get(0));
        } else {
            List<ThemedAlertHost.ActionItem> items = new ArrayList<>();
            for (Container container : containers) {
                items.add(new ThemedAlertHost.ActionItem(container.getName(), () -> navigateToContainerDriveC(container)));
            }
            ThemedAlertHost.actions(activity, "Select Container Drive C:", items);
        }
    }

    private void navigateToContainerDriveC(Container container) {
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        File windowsDir = new File(driveC, "windows");
        if (driveC.exists() && driveC.isDirectory() && windowsDir.exists()) {
            openDrive(driveC, driveC);
            WinToast.show(activity, UiStrings.get(R.string.ui_opened_c_drive, container.getName()), Toast.LENGTH_SHORT);
        } else {
            ThemedAlertHost.info(activity, "Drive C: Not Initialized",
                    "The Wine system files (Drive C:) for '" + container.getName() + "' are missing.\n\n" +
                            "Please RUN this container once to generate the filesystem.");
        }
    }

    private void navigateUp() {
        if (currentDir == null) return;
        if (currentDriveRoot != null && samePath(currentDir, currentDriveRoot)) {
            WinToast.show(activity, UiStrings.get(R.string.ui_drive_root_reached), Toast.LENGTH_SHORT);
            return;
        }

        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead() && isWithinRoot(parent, currentDriveRoot)) {
            loadDirectory(parent);
        } else {
            WinToast.show(activity, UiStrings.get(R.string.ui_drive_root_reached), Toast.LENGTH_SHORT);
        }
    }

    private void loadDirectory(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            WinToast.show(activity, UiStrings.get(R.string.ui_folder_is_not_accessible), Toast.LENGTH_SHORT);
            return;
        }
        currentDir = dir;
        if (currentDriveRoot == null) currentDriveRoot = inferDriveRoot(dir);
        pushState();
    }

    private void performContainerAction(File file, ContainerAction action) {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers == null || containers.isEmpty()) {
            WinToast.show(activity, UiStrings.get(R.string.ui_create_a_container_first), Toast.LENGTH_SHORT);
            return;
        }

        if (containers.size() == 1) {
            action.onContainerSelected(containers.get(0));
        } else {
            List<ThemedAlertHost.ActionItem> items = new ArrayList<>();
            for (Container container : containers) {
                items.add(new ThemedAlertHost.ActionItem(container.getName(), () -> action.onContainerSelected(container)));
            }
            ThemedAlertHost.actions(activity, "Select Container", items);
        }
    }

    private File getRemovableStorageRoot(File file) {
        if (file == null) return null;
        String path = normalizeFilePath(file.getAbsolutePath());
        if (!path.startsWith("/storage/")) return null;

        String rest = path.substring("/storage/".length());
        int slash = rest.indexOf('/');
        String volume = slash >= 0 ? rest.substring(0, slash) : rest;
        if (volume.isEmpty() || volume.equals("emulated") || volume.equals("self")) return null;

        File root = new File("/storage/" + volume);
        return root.exists() && root.isDirectory() ? root : null;
    }

    private void setDriveMapping(Container container, String letter, String path) {
        StringBuilder rebuilt = new StringBuilder();
        for (String[] drive : container.drivesIterator()) {
            if (drive == null || drive.length < 2 || drive[0] == null || drive[1] == null) continue;
            String existingLetter = drive[0].replace(":", "").trim().toUpperCase(Locale.ENGLISH);
            if (existingLetter.equals(letter)) continue;
            rebuilt.append(existingLetter).append(':').append(drive[1]);
        }
        rebuilt.append(letter).append(':').append(path);
        container.setDrives(rebuilt.toString());
        container.saveData();
    }

    private boolean ensureExternalStorageMapped(Container container, File file) {
        File volumeRoot = getRemovableStorageRoot(file);
        if (volumeRoot == null) return true;

        String filePath = normalizeFilePath(file.getAbsolutePath());
        String volumePath = normalizeFilePath(volumeRoot.getAbsolutePath());
        String volumeName = volumeRoot.getName();
        boolean[] used = new boolean[26];

        for (String[] drive : container.drivesIterator()) {
            if (drive == null || drive.length < 2 || drive[0] == null || drive[1] == null) continue;
            String letter = drive[0].replace(":", "").trim().toUpperCase(Locale.ENGLISH);
            if (letter.length() == 1) {
                int index = letter.charAt(0) - 'A';
                if (index >= 0 && index < used.length) used[index] = true;
            }

            String mappedPath = normalizeFilePath(drive[1]);
            if (!mappedPath.isEmpty() && (filePath.equals(mappedPath) || filePath.startsWith(mappedPath + File.separator))) {
                WineUtils.createDosdevicesSymlinks(container);
                return true;
            }

            String legacyRoot = normalizeFilePath("/mnt/media_rw/" + volumeName);
            if (mappedPath.equals(legacyRoot) || mappedPath.startsWith(legacyRoot + File.separator)) {
                setDriveMapping(container, letter, volumePath);
                WineUtils.createDosdevicesSymlinks(container);
                return true;
            }
        }

        char driveLetter = 0;
        for (char candidate = 'E'; candidate <= 'Y'; candidate++) {
            int index = candidate - 'A';
            if (!used[index]) {
                driveLetter = candidate;
                break;
            }
        }
        if (driveLetter == 0) return false;

        setDriveMapping(container, String.valueOf(driveLetter), volumePath);
        WineUtils.createDosdevicesSymlinks(container);
        return true;
    }

    private String getContainerWineHome(Container container) {
        File imagefs = new File(activity.getFilesDir(), "imagefs");
        String imagefsPath = normalizeFilePath(imagefs.getAbsolutePath());
        String rootPath = normalizeFilePath(container.getRootDir().getAbsolutePath());
        return rootPath.startsWith(imagefsPath)
                ? rootPath.substring(imagefsPath.length())
                : "/home/" + ImageFs.USER;
    }

    private String toDesktopWindowsPath(File file, Container container) {
        String filePath = normalizeFilePath(file.getAbsolutePath());
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        String driveCPath = normalizeFilePath(driveC.getAbsolutePath());
        if (filePath.equals(driveCPath) || filePath.startsWith(driveCPath + File.separator)) {
            String rel = filePath.substring(driveCPath.length()).replace(File.separatorChar, '\\');
            while (rel.startsWith("\\")) rel = rel.substring(1);
            return "C:\\" + rel;
        }

        for (String[] drive : container.drivesIterator()) {
            if (drive == null || drive.length < 2 || drive[0] == null || drive[1] == null) continue;
            String driveLetter = drive[0].replace(":", "").trim();
            String drivePath = normalizeFilePath(drive[1]);
            if (driveLetter.isEmpty() || drivePath.isEmpty()) continue;
            if (filePath.equals(drivePath) || filePath.startsWith(drivePath + File.separator)) {
                String relativePath = filePath.substring(drivePath.length()).replace(File.separatorChar, '\\');
                while (relativePath.startsWith("\\")) relativePath = relativePath.substring(1);
                return driveLetter.toUpperCase(Locale.ENGLISH) + ":\\" + relativePath;
            }
        }

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String downloadsPath = normalizeFilePath(downloadsDir.getAbsolutePath());
        if (filePath.equals(downloadsPath) || filePath.startsWith(downloadsPath + File.separator)) {
            String relativePath = filePath.substring(downloadsPath.length()).replace(File.separatorChar, '\\');
            while (relativePath.startsWith("\\")) relativePath = relativePath.substring(1);
            return "D:\\" + relativePath;
        }

        String externalPath = normalizeFilePath(Environment.getExternalStorageDirectory().getAbsolutePath());
        if (filePath.equals(externalPath) || filePath.startsWith(externalPath + File.separator)) {
            String relativePath = filePath.substring(externalPath.length()).replace(File.separatorChar, '\\');
            while (relativePath.startsWith("\\")) relativePath = relativePath.substring(1);
            return "D:\\" + relativePath;
        }
        return "Z:" + filePath.replace('/', '\\');
    }

    private String toDesktopPath(File file, Container container) {
        File parent = file.getParentFile();
        if (parent == null) return "";
        String parentPath = normalizeFilePath(parent.getAbsolutePath());

        for (String[] drive : container.drivesIterator()) {
            if (drive == null || drive.length < 2 || drive[0] == null || drive[1] == null) continue;
            String driveLetter = drive[0].replace(":", "").trim();
            String drivePath = normalizeFilePath(drive[1]);
            if (driveLetter.isEmpty() || drivePath.isEmpty()) continue;
            if (parentPath.equals(drivePath) || parentPath.startsWith(drivePath + File.separator)) {
                String relativePath = parentPath.substring(drivePath.length()).replace(File.separatorChar, '/');
                while (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                String basePath = getContainerWineHome(container) + "/.wine/dosdevices/" +
                        driveLetter.toLowerCase(Locale.ENGLISH) + ":";
                return relativePath.isEmpty() ? basePath : basePath + "/" + relativePath;
            }
        }

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String downloadsPath = normalizeFilePath(downloadsDir.getAbsolutePath());
        if (parentPath.equals(downloadsPath) || parentPath.startsWith(downloadsPath + File.separator)) {
            String relativePath = parentPath.substring(downloadsPath.length()).replace(File.separatorChar, '/');
            while (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            String dBase = getContainerWineHome(container) + "/.wine/dosdevices/d:";
            return relativePath.isEmpty() ? dBase : dBase + "/" + relativePath;
        }

        String externalPath = normalizeFilePath(Environment.getExternalStorageDirectory().getAbsolutePath());
        if (parentPath.equals(externalPath) || parentPath.startsWith(externalPath + File.separator)) {
            String relativePath = parentPath.substring(externalPath.length()).replace(File.separatorChar, '/');
            while (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            String dBase = getContainerWineHome(container) + "/.wine/dosdevices/d:";
            return relativePath.isEmpty() ? dBase : dBase + "/" + relativePath;
        }
        return parentPath;
    }

    private void writeDesktopEntry(PrintWriter writer, String name, String execPath, String path, String icon, Container container) {
        writer.println("[Desktop Entry]");
        writer.println("Name=" + name);
        String escapedExecPath = StringUtils.escapeFileDOSPath(execPath);
        String winePrefix = getContainerWineHome(container) + "/.wine";
        writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine " + escapedExecPath);
        writer.println("Type=Application");
        if (path != null && !path.isEmpty()) writer.println("Path=" + path);
        if (icon != null && !icon.isEmpty()) writer.println("Icon=" + icon);
        writer.println("container_id:" + container.id);
    }

    private void runFileDirectly(File file, Container container) {
        try {
            if (!ensureExternalStorageMapped(container, file)) {
                WinToast.show(activity, UiStrings.get(R.string.ui_no_free_drive_letter), Toast.LENGTH_LONG);
                return;
            }

            File tempShortcut = new File(activity.getCacheDir(), "temp_run.desktop");
            String winePrefix = getContainerWineHome(container) + "/.wine";

            try (PrintWriter writer = new PrintWriter(new FileWriter(tempShortcut))) {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + file.getName());
                writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine \"" + file.getAbsolutePath() + "\"");
                writer.println("Type=Application");
                writer.println("container_id:" + container.id);
            }

            Intent intent = new Intent();
            intent.setClassName(activity.getPackageName(), "com.winlator.cmod.XServerDisplayActivity");
            intent.putExtra("container_id", container.id);
            intent.putExtra("shortcut_path", tempShortcut.getAbsolutePath());
            activity.startActivity(intent);
        } catch (Exception e) {
            WinToast.show(activity, UiStrings.get(R.string.ui_error_launching, e.getMessage()), Toast.LENGTH_LONG);
            e.printStackTrace();
        }
    }

    private void createShortcutDirectly(File file, Container container) {
        try {
            if (!ensureExternalStorageMapped(container, file)) {
                WinToast.show(activity, UiStrings.get(R.string.ui_no_free_drive_letter), Toast.LENGTH_LONG);
                return;
            }

            String displayName = getSmartDisplayName(file);
            String unixPath = file.getAbsolutePath();
            String winePrefix = getContainerWineHome(container) + "/.wine";
            File shortcutsDir = container.getDesktopDir();
            if (!shortcutsDir.exists()) shortcutsDir.mkdirs();
            File desktopFile = new File(shortcutsDir, displayName + ".desktop");

            try (PrintWriter writer = new PrintWriter(new FileWriter(desktopFile))) {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + displayName);
                writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine \"" + unixPath + "\"");
                writer.println("Type=Application");
                writer.println("Icon=" + displayName);
                writer.println("container_id:" + container.id);
            }
            WinToast.show(activity, UiStrings.get(R.string.ui_game_added_to_library), Toast.LENGTH_SHORT);

            File iconDir64 = container.getIconsDir(64);
            if (!iconDir64.exists()) iconDir64.mkdirs();
            File iconDest = new File(iconDir64, displayName + ".png");
            boolean iconExtracted = ExeIconExtractor.extractIcon(file, iconDest);

            File iconsDir = new File(Environment.getExternalStorageDirectory(), "Winlator/icons");
            if (!iconsDir.exists()) iconsDir.mkdirs();
            if (iconExtracted) {
                File userIcon = new File(iconsDir, displayName + ".png");
                if (!userIcon.exists()) {
                    try {
                        FileUtils.copy(iconDest, userIcon);
                    } catch (Exception ignored) {}
                }
            }

            File coversDir = new File(Environment.getExternalStorageDirectory(), "Winlator/covers");
            if (!coversDir.exists()) coversDir.mkdirs();
            File autoCover = new File(coversDir, displayName + ".png");
            if (autoCover.exists()) autoCover.delete();
            // A new library entry starts with clean artwork state: no stale banner from an earlier
            // game of the same name, and no remembered "not found" from before.
            File staleBanner = new File(new File(Environment.getExternalStorageDirectory(), "Winlator/banners"), displayName + ".png");
            if (staleBanner.exists()) staleBanner.delete();
            File staleGenerated = new File(coversDir, displayName + ".gen.png");
            if (staleGenerated.exists()) staleGenerated.delete();
            // Preserve Ludashi artwork handling; no generated icon/blur fallback.
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void copyToClipboard(File file, boolean isCut) {
        this.clipboardFile = file;
        this.isCutOperation = isCut;
        pushState();
        WinToast.show(activity, (isCut ? UiStrings.get(R.string.ui_cut_file, file.getName()) : UiStrings.get(R.string.ui_copied_file, file.getName())), Toast.LENGTH_SHORT);
    }

    private void startPasteOperation() {
        if (clipboardFile == null || !clipboardFile.exists()) {
            WinToast.show(activity, UiStrings.get(R.string.ui_nothing_to_paste), Toast.LENGTH_SHORT);
            return;
        }

        final File source = clipboardFile;
        File dest = new File(currentDir, source.getName());
        if (dest.exists()) {
            List<ThemedAlertHost.Option> options = new ArrayList<>();
            options.add(new ThemedAlertHost.Option("Replace", () -> {
                deleteRecursive(dest);
                executePaste(source, dest);
            }, true));
            options.add(new ThemedAlertHost.Option("Rename", () ->
                    executePaste(source, getUniqueDestination(currentDir, source.getName()))));
            ThemedAlertHost.choice(activity, "File Conflict",
                    "The destination \"" + dest.getName() + "\" already exists.", options, "Cancel");
        } else {
            executePaste(source, dest);
        }
    }

    private File getUniqueDestination(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        String baseName = name;
        String extension = "";
        int dotIndex = name.lastIndexOf('.');
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

    private void executePaste(File source, File dest) {
        if (isCutOperation && source.renameTo(dest)) {
            WinToast.show(activity, UiStrings.get(R.string.ui_moved_instantly), Toast.LENGTH_SHORT);
            finishPaste(true);
            return;
        }

        showProgressDialog(isCutOperation ? "Moving..." : "Copying...");
        isOperationCancelled = false;
        new Thread(() -> {
            try {
                long totalBytes = getFolderSize(source);
                AtomicLong copiedBytes = new AtomicLong(0);
                copyRecursiveWithProgress(source, dest, totalBytes, copiedBytes);
                if (isCutOperation && !isOperationCancelled) {
                    long srcSize = getFolderSize(source);
                    long dstSize = getFolderSize(dest);
                    if (srcSize > 0 && srcSize == dstSize && source.canRead()) deleteRecursive(source);
                    else throw new IOException("Safety Stop: Sizes mismatch (" + srcSize + " vs " + dstSize + ") or source unreadable.");
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    dismissProgressDialog();
                    if (!isOperationCancelled) {
                        WinToast.show(activity, UiStrings.get(R.string.ui_success), Toast.LENGTH_SHORT);
                        finishPaste(isCutOperation);
                    } else {
                        WinToast.show(activity, UiStrings.get(R.string.ui_cancelled), Toast.LENGTH_SHORT);
                        deleteRecursive(dest);
                        loadDirectory(currentDir);
                    }
                });
            } catch (Exception e) {
                final String errorMsg = e.getMessage();
                new Handler(Looper.getMainLooper()).post(() -> {
                    dismissProgressDialog();
                    WinToast.show(activity, UiStrings.get(R.string.ui_error_source_preserved, errorMsg), Toast.LENGTH_LONG);
                    deleteRecursive(dest);
                    loadDirectory(currentDir);
                });
            }
        }).start();
    }

    private void finishPaste(boolean clearClipboard) {
        if (clearClipboard) {
            clipboardFile = null;
        }
        loadDirectory(currentDir);
    }

    private void showProgressDialog(String title) {
        progressOverlay = ThemedProgressHost.show(activity, title, () -> isOperationCancelled = true);
    }

    private void dismissProgressDialog() {
        ThemedProgressHost.dismiss(progressOverlay);
        progressOverlay = null;
    }

    private void updateProgress(long current, long total) {
        int percent = total > 0 ? (int) ((current * 100) / total) : 0;
        final String status = formatSize(current) + " / " + formatSize(total);
        new Handler(Looper.getMainLooper()).post(() -> ThemedProgressHost.update(progressOverlay, percent, status));
    }

    private long getFolderSize(File file) {
        long size = 0;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) for (File child : files) size += getFolderSize(child);
        } else {
            size = file.length();
        }
        return size;
    }

    private void copyRecursiveWithProgress(File src, File dst, long totalBytes, AtomicLong copiedBytes) throws IOException {
        if (isOperationCancelled) return;
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("Failed to create dir: " + dst.getName());
            String[] children = src.list();
            if (children != null) {
                for (String child : children) {
                    copyRecursiveWithProgress(new File(src, child), new File(dst, child), totalBytes, copiedBytes);
                }
            }
        } else {
            copyFileSafe(src, dst, totalBytes, copiedBytes);
        }
    }

    private void copyFileSafe(File source, File dest, long totalBytes, AtomicLong totalCopied) throws IOException {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(dest);
            byte[] buffer = new byte[8192];
            int len;
            long oneMb = 1024 * 1024;

            while ((len = in.read(buffer)) > 0) {
                if (isOperationCancelled) break;
                out.write(buffer, 0, len);
                long oldTotal = totalCopied.get();
                long newTotal = totalCopied.addAndGet(len);
                if ((newTotal / oneMb) > (oldTotal / oneMb)) updateProgress(newTotal, totalBytes);
            }
            out.flush();
            out.getFD().sync();
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
        }
    }

    private boolean deleteRecursive(File fileOrDirectory) {
        boolean allDeleted = true;
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) for (File child : children) allDeleted &= deleteRecursive(child);
        }
        return fileOrDirectory.delete() && allDeleted;
    }

    private void renameFile(File file) {
        ThemedAlertHost.prompt(activity, "Rename", file.getName(), "OK", newName -> {
            String trimmed = newName == null ? "" : newName.trim();
            if (trimmed.isEmpty() || trimmed.equals(file.getName())) return;
            if (trimmed.contains("/")) {
                WinToast.show(activity, UiStrings.get(R.string.ui_name_cant_contain_slash), Toast.LENGTH_SHORT);
                return;
            }
            File newFile = new File(file.getParent(), trimmed);
            if (newFile.exists()) {
                WinToast.show(activity, "\"" + trimmed + "\" already exists", Toast.LENGTH_SHORT);
                return;
            }
            if (file.renameTo(newFile)) {
                loadDirectory(currentDir);
                WinToast.show(activity, UiStrings.get(R.string.ui_renamed_to_plain, trimmed), Toast.LENGTH_SHORT);
            } else {
                WinToast.show(activity, UiStrings.get(R.string.ui_rename_failed), Toast.LENGTH_SHORT);
            }
        });
    }

    private boolean isExecutable(File f) {
        String name = f.getName().toLowerCase(Locale.ENGLISH);
        return name.endsWith(".exe") || name.endsWith(".msi") || name.endsWith(".bat");
    }

    private void confirmDelete(File file) {
        ThemedAlertHost.confirm(activity, "Delete", "Are you sure you want to delete " + file.getName() + "?", "Delete", () -> {
            boolean deleted = deleteRecursive(file);
            loadDirectory(currentDir);
            if (deleted) WinToast.show(activity, UiStrings.get(R.string.ui_deleted_file, file.getName()), Toast.LENGTH_SHORT);
            else WinToast.show(activity, UiStrings.get(R.string.ui_couldnt_delete_file, file.getName()), Toast.LENGTH_LONG);
        }, true);
    }

    private String getSmartDisplayName(File file) {
        String filename = cleanGameName(file.getName());
        String lowerName = filename.toLowerCase(Locale.ENGLISH);
        List<String> genericNames = Arrays.asList(
                "game", "launcher", "setup", "installer", "start", "run",
                "speed", "update", "patch", "loader", "client", "app", "main", "boot", "play",
                "application", "shipping", "x64", "x86", "win64", "win32", "binaries"
        );
        boolean isModOrGeneric = false;
        if (lowerName.contains("mod") || lowerName.contains("fix") || lowerName.contains("crack") || lowerName.contains("patch")) {
            isModOrGeneric = true;
        }
        if (!isModOrGeneric && filename.length() < 4) isModOrGeneric = true;
        if (!isModOrGeneric) {
            for (String gen : genericNames) {
                if (lowerName.equals(gen) || lowerName.startsWith(gen + " ")) {
                    isModOrGeneric = true;
                    break;
                }
            }
        }
        if (isModOrGeneric) {
            File parent = file.getParentFile();
            if (parent != null) {
                String parentName = cleanGameName(parent.getName());
                List<String> genericFolders = Arrays.asList("bin", "bin32", "bin64", "system", "release", "retail", "win64");
                if (genericFolders.contains(parentName.toLowerCase(Locale.ENGLISH))) {
                    File grandParent = parent.getParentFile();
                    if (grandParent != null) return cleanGameName(grandParent.getName());
                }
                return parentName;
            }
        }
        return filename;
    }

    private String cleanGameName(String filename) {
        String name = filename;
        int pos = name.lastIndexOf('.');
        if (pos > 0) name = name.substring(0, pos);
        name = name.replace("_", " ").replace(".", " ").replace("-", " ");
        name = name.replaceAll("(?i)\\b(v\\d+|repack|setup|installer|portable|goty|edition)\\b", "");
        name = name.replaceAll("[^a-zA-Z0-9 ]", "");
        name = name.replaceAll("\\s+", " ").trim();
        return name;
    }

    private File getFileIconCacheFile(File file) {
        File cacheDir = new File(activity.getCacheDir(), "file-manager-icons");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        String source = normalizeFilePath(file.getAbsolutePath());
        String key = Integer.toHexString(source.hashCode()) + "-" + file.length() + "-" + file.lastModified();
        return new File(cacheDir, key + ".png");
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format(Locale.getDefault(), "%.1f %sB", (double) size / (1L << (z * 10)), " KMGTPE".charAt(z));
    }
}
