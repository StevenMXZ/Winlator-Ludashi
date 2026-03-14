package com.winlator.cmod.winhandler;

import com.winlator.cmod.core.StringUtils;
import java.util.ArrayList;

/* loaded from: classes12.dex */
public class ProcessInfo {
    public final int affinityMask;
    public final long memoryUsage;
    public final String name;
    public final int pid;
    public final boolean wow64Process;

    public ProcessInfo(int pid, String name, long memoryUsage, int affinityMask, boolean wow64Process) {
        this.pid = pid;
        this.name = name;
        this.memoryUsage = memoryUsage;
        this.affinityMask = affinityMask;
        this.wow64Process = wow64Process;
    }

    public String getFormattedMemoryUsage() {
        return StringUtils.formatBytes(this.memoryUsage);
    }

    public String getCPUList() {
        int numProcessors = Runtime.getRuntime().availableProcessors();
        ArrayList<String> cpuList = new ArrayList<>();
        for (byte i = 0; i < numProcessors; i = (byte) (i + 1)) {
            if ((this.affinityMask & (1 << i)) != 0) {
                cpuList.add(String.valueOf((int) i));
            }
        }
        return String.join(",", (CharSequence[]) cpuList.toArray(new String[0]));
    }
}
