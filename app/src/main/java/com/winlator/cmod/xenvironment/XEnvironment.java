package com.winlator.cmod.xenvironment;

import android.content.Context;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

/* loaded from: classes12.dex */
public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();
    private final Context context;
    private final ImageFs imageFs;

    public XEnvironment(Context context, ImageFs imageFs) {
        this.context = context;
        this.imageFs = imageFs;
    }

    public Context getContext() {
        return this.context;
    }

    public ImageFs getImageFs() {
        return this.imageFs;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        this.components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        Iterator<EnvironmentComponent> it = this.components.iterator();
        while (it.hasNext()) {
            T t = (T) it.next();
            if (t.getClass() == componentClass) {
                return t;
            }
        }
        return null;
    }

    @Override // java.lang.Iterable
    public Iterator<EnvironmentComponent> iterator() {
        return this.components.iterator();
    }

    public File getTmpDir() {
        File tmpDir = new File(this.context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 505);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir());
        Iterator<EnvironmentComponent> it = iterator();
        while (it.hasNext()) {
            EnvironmentComponent environmentComponent = it.next();
            environmentComponent.start();
        }
    }

    public void stopEnvironmentComponents() {
        Iterator<EnvironmentComponent> it = iterator();
        while (it.hasNext()) {
            EnvironmentComponent environmentComponent = it.next();
            environmentComponent.stop();
        }
    }

    public void onPause() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = (GuestProgramLauncherComponent) getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) {
            guestProgramLauncherComponent.suspendProcess();
        }
    }

    public void onResume() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = (GuestProgramLauncherComponent) getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) {
            guestProgramLauncherComponent.resumeProcess();
        }
    }
}
