package com.winlator.cmod.container;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.ludashi.benchmark.R;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class ContainerManager {
    private final Context context;
    private final File homeDir;
    private boolean isInitialized;
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;

    public ContainerManager(Context context) {
        this.isInitialized = false;
        this.context = context;
        File rootDir = ImageFs.find(context).getRootDir();
        this.homeDir = new File(rootDir, "home");
        loadContainers();
        this.isInitialized = true;
    }

    public boolean isInitialized() {
        return this.isInitialized;
    }

    public ArrayList<Container> getContainers() {
        return this.containers;
    }

    private void loadContainers() {
        this.containers.clear();
        this.maxContainerId = 0;
        try {
            File[] files = this.homeDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && file.getName().startsWith("xuser-")) {
                        Container container = new Container(Integer.parseInt(file.getName().replace("xuser-", "")), this);
                        container.setRootDir(new File(this.homeDir, "xuser-" + container.id));
                        JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                        container.loadData(data);
                        this.containers.add(container);
                        this.maxContainerId = Math.max(this.maxContainerId, container.id);
                    }
                }
            }
        } catch (NullPointerException | JSONException e) {
            Log.e("ContainerManager", "Error loading containers", e);
        }
    }

    public Context getContext() {
        return this.context;
    }

    public void activateContainer(Container container) {
        container.setRootDir(new File(this.homeDir, "xuser-" + container.id));
        File file = new File(this.homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./xuser-" + container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, final ContentsManager contentsManager, final Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.container.ContainerManager$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                ContainerManager.this.lambda$createContainerAsync$1(data, contentsManager, handler, callback);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$createContainerAsync$1(JSONObject data, ContentsManager contentsManager, Handler handler, final Callback callback) {
        final Container container = createContainer(data, contentsManager);
        handler.post(new Runnable() { // from class: com.winlator.cmod.container.ContainerManager$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                Callback.this.call(container);
            }
        });
    }

    public void duplicateContainerAsync(final Container container, final Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.container.ContainerManager$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ContainerManager.this.lambda$duplicateContainerAsync$2(container, handler, callback);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$duplicateContainerAsync$2(Container container, Handler handler, Runnable callback) {
        duplicateContainer(container);
        handler.post(callback);
    }

    public void removeContainerAsync(final Container container, final Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.container.ContainerManager$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                ContainerManager.this.lambda$removeContainerAsync$3(container, handler, callback);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$removeContainerAsync$3(Container container, Handler handler, Runnable callback) {
        removeContainer(container);
        handler.post(callback);
    }

    private Container createContainer(JSONObject data, ContentsManager contentsManager) {
        try {
            int id = this.maxContainerId + 1;
            data.put("id", id);
            File containerDir = new File(this.homeDir, "xuser-" + id);
            if (!containerDir.mkdirs()) {
                return null;
            }
            Container container = new Container(id, this);
            container.setRootDir(containerDir);
            container.loadData(data);
            container.setWineVersion(data.getString("wineVersion"));
            if (!extractContainerPatternFile(container, container.getWineVersion(), contentsManager, containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }
            container.saveData();
            this.maxContainerId++;
            this.containers.add(container);
            return container;
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void duplicateContainer(Container srcContainer) {
        int id = this.maxContainerId + 1;
        File dstDir = new File(this.homeDir, "xuser-" + id);
        if (dstDir.mkdirs()) {
            if (!FileUtils.copy(srcContainer.getRootDir(), dstDir, (Callback<File>) new Callback() { // from class: com.winlator.cmod.container.ContainerManager$$ExternalSyntheticLambda3
                @Override // com.winlator.cmod.core.Callback
                public final void call(Object obj) {
                    FileUtils.chmod((File) obj, 505);
                }
            })) {
                FileUtils.delete(dstDir);
                return;
            }
            Container dstContainer = new Container(id, this);
            dstContainer.setRootDir(dstDir);
            dstContainer.setName(srcContainer.getName() + " (" + this.context.getString(R.string._copy) + ")");
            dstContainer.setScreenSize(srcContainer.getScreenSize());
            dstContainer.setEnvVars(srcContainer.getEnvVars());
            dstContainer.setCPUList(srcContainer.getCPUList());
            dstContainer.setCPUListWoW64(srcContainer.getCPUListWoW64());
            dstContainer.setGraphicsDriver(srcContainer.getGraphicsDriver());
            dstContainer.setDXWrapper(srcContainer.getDXWrapper());
            dstContainer.setDXWrapperConfig(srcContainer.getDXWrapperConfig());
            dstContainer.setAudioDriver(srcContainer.getAudioDriver());
            dstContainer.setWinComponents(srcContainer.getWinComponents());
            dstContainer.setDrives(srcContainer.getDrives());
            dstContainer.setShowFPS(srcContainer.isShowFPS());
            dstContainer.setStartupSelection(srcContainer.getStartupSelection());
            dstContainer.setBox64Preset(srcContainer.getBox64Preset());
            dstContainer.setDesktopTheme(srcContainer.getDesktopTheme());
            dstContainer.setWineVersion(srcContainer.getWineVersion());
            dstContainer.saveData();
            this.maxContainerId++;
            this.containers.add(dstContainer);
        }
    }

    private void removeContainer(Container container) {
        if (FileUtils.delete(container.getRootDir())) {
            this.containers.remove(container);
        }
    }

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();
        Iterator<Container> it = this.containers.iterator();
        while (it.hasNext()) {
            Container container = it.next();
            File desktopDir = container.getDesktopDir();
            ArrayList<File> files = new ArrayList<>();
            if (desktopDir.exists()) {
                files.addAll(Arrays.asList(desktopDir.listFiles()));
            }
            Iterator<File> it2 = files.iterator();
            while (it2.hasNext()) {
                File file = it2.next();
                String fileName = file.getName();
                if (fileName.endsWith(".lnk")) {
                    String filePath = file.getPath();
                    File desktopFile = new File(filePath.substring(0, filePath.lastIndexOf(".")) + ".desktop");
                    if (!desktopFile.exists()) {
                        MSLink.createDesktopFile(file, this.context);
                        shortcuts.add(new Shortcut(container, desktopFile));
                    }
                } else if (fileName.endsWith(".desktop")) {
                    shortcuts.add(new Shortcut(container, file));
                }
            }
        }
        shortcuts.sort(Comparator.comparing(new Function() { // from class: com.winlator.cmod.container.ContainerManager$$ExternalSyntheticLambda5
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                String str;
                str = ((Shortcut) obj).name;
                return str;
            }
        }));
        return shortcuts;
    }

    public int getNextContainerId() {
        return this.maxContainerId + 1;
    }

    public Container getContainerById(int id) {
        Iterator<Container> it = this.containers.iterator();
        while (it.hasNext()) {
            Container container = it.next();
            if (container.id == id) {
                return container;
            }
        }
        return null;
    }

    private void extractCommonDlls(WineInfo wineInfo, String srcName, String dstName, File containerDir, OnExtractFileListener onExtractFileListener) throws JSONException {
        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);
        File[] srcfiles = srcDir.listFiles(new FileFilter() { // from class: com.winlator.cmod.container.ContainerManager$$ExternalSyntheticLambda4
            @Override // java.io.FileFilter
            public final boolean accept(File file) {
                boolean isFile;
                isFile = file.isFile();
                return isFile;
            }
        });
        int length = srcfiles.length;
        for (int i = 0; i < length; i++) {
            File file = srcfiles[i];
            String dllName = file.getName();
            if (dllName.equals("iexplore.exe") && wineInfo.isArm64EC() && srcName.equals("aarch64-windows")) {
                file = new File(wineInfo.path + "/lib/wine/i386-windows/iexplore.exe");
            }
            if (!dllName.equals("tabtip.exe") && !dllName.equals("icu.dll")) {
                File dstFile = new File(containerDir, ".wine/drive_c/windows/" + dstName + "/" + dllName);
                if (!dstFile.exists() && (onExtractFileListener == null || (dstFile = onExtractFileListener.onExtractFile(dstFile, 0L)) != null)) {
                    FileUtils.copy(file, dstFile);
                }
            }
        }
    }

    public boolean extractContainerPatternFile(Container container, String wineVersion, ContentsManager contentsManager, File containerDir, OnExtractFileListener onExtractFileListener) {
        boolean result;
        WineInfo wineInfo = WineInfo.fromIdentifier(this.context, contentsManager, wineVersion);
        String containerPattern = wineVersion + "_container_pattern.tzst";
        boolean result2 = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this.context, containerPattern, containerDir, onExtractFileListener);
        if (result2) {
            result = result2;
        } else {
            File containerPatternFile = new File(wineInfo.path + "/prefixPack.txz");
            result = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, containerPatternFile, containerDir);
        }
        if (result) {
            try {
                if (wineInfo.isArm64EC()) {
                    extractCommonDlls(wineInfo, "aarch64-windows", "system32", containerDir, onExtractFileListener);
                } else {
                    extractCommonDlls(wineInfo, "x86_64-windows", "system32", containerDir, onExtractFileListener);
                }
                extractCommonDlls(wineInfo, "i386-windows", "syswow64", containerDir, onExtractFileListener);
            } catch (JSONException e) {
                return false;
            }
        }
        return result;
    }

    public Container getContainerForShortcut(Shortcut shortcut) {
        Iterator<Container> it = this.containers.iterator();
        while (it.hasNext()) {
            Container container = it.next();
            if (container.id == shortcut.getContainerId()) {
                return container;
            }
        }
        return null;
    }

    private void runOnUiThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }
}
