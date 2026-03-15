package com.winlator.cmod.core;

import android.os.Process;
import android.util.Log;
import com.winlator.cmod.contents.ContentProfile;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import kotlin.text.Typography;

/* loaded from: classes10.dex */
public abstract class ProcessHelper {
    public static final boolean PRINT_DEBUG = true;
    private static final byte SIGCONT = 18;
    private static final byte SIGKILL = 9;
    private static final byte SIGSTOP = 19;
    private static final byte SIGTERM = 15;
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, 19);
        Log.d("ProcessHelper", "Process suspended with pid: " + pid);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, 18);
        Log.d("ProcessHelper", "Process resumed with pid: " + pid);
    }

    public static void terminateProcess(int pid) {
        Process.sendSignal(pid, 15);
        Log.d("ProcessHelper", "Process terminated with pid: " + pid);
    }

    public static void killProcess(int pid) {
        Process.sendSignal(pid, 9);
        Log.d("ProcessHelper", "Process killed with pid: " + pid);
    }

    public static void terminateAllWineProcesses() {
        Iterator<String> it = listRunningWineProcesses().iterator();
        while (it.hasNext()) {
            String process = it.next();
            terminateProcess(Integer.parseInt(process));
        }
    }

    public static void pauseAllWineProcesses() {
        Iterator<String> it = listRunningWineProcesses().iterator();
        while (it.hasNext()) {
            String process = it.next();
            suspendProcess(Integer.parseInt(process));
        }
    }

    public static void resumeAllWineProcesses() {
        Iterator<String> it = listRunningWineProcesses().iterator();
        while (it.hasNext()) {
            String process = it.next();
            resumeProcess(Integer.parseInt(process));
        }
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, String[] envp) {
        return exec(command, envp, null);
    }

    public static int exec(String command, String[] envp, File workingDir) {
        return exec(command, envp, workingDir, null);
    }

    public static int exec(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback) {
        Log.d("ProcessHelper", "env: " + Arrays.toString(envp) + "\ncmd: " + command);
        EnvironmentManager.setEnvVars(envp);
        int pid = -1;
        try {
            Log.d("ProcessHelper", "Splitting command: " + command);
            String[] splitCommand = splitCommand(command);
            Log.d("ProcessHelper", "Split command result: " + Arrays.toString(splitCommand));
            Log.d("ProcessHelper", "Starting process...");
            ProcessBuilder pb = new ProcessBuilder(splitCommand);
            pb.directory(workingDir);
            pb.environment().putAll(EnvironmentManager.getEnvVars());
            if (debugCallbacks.isEmpty()) {
                File null_file = new File("/dev/null");
                pb.redirectError(null_file);
                pb.redirectOutput(null_file);
            }
            Process process = pb.start();
            Log.d("ProcessHelper", "Accessing hidden field to get PID");
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);
            Log.d("ProcessHelper", "Process started with pid: " + pid);
            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }
            if (terminationCallback != null) {
                createWaitForThread(process, terminationCallback);
            }
        } catch (Exception e) {
            Log.e("ProcessHelper", "Error executing command: " + command, e);
        }
        return pid;
    }

    private static void createDebugThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.core.ProcessHelper$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ProcessHelper.lambda$createDebugThread$0(inputStream);
            }
        });
    }

    static /* synthetic */ void lambda$createDebugThread$0(InputStream inputStream) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            while (true) {
                try {
                    String line = reader.readLine();
                    if (line != null) {
                        System.out.println(line);
                        synchronized (debugCallbacks) {
                            if (!debugCallbacks.isEmpty()) {
                                Iterator<Callback<String>> it = debugCallbacks.iterator();
                                while (it.hasNext()) {
                                    Callback<String> callback = it.next();
                                    callback.call(line);
                                }
                            }
                        }
                    } else {
                        reader.close();
                        return;
                    }
                } finally {
                }
            }
        } catch (IOException e) {
            Log.e("ProcessHelper", "Error in debug thread", e);
        }
    }

    private static void createWaitForThread(final Process process, final Callback<Integer> terminationCallback) {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.core.ProcessHelper.1
            @Override // java.lang.Runnable
            public void run() {
                try {
                    int status = process.waitFor();
                    terminationCallback.call(Integer.valueOf(status));
                } catch (InterruptedException e) {
                    Log.e("ProcessHelper", "Error waiting for process termination", e);
                }
            }
        });
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
            Log.d("ProcessHelper", "All debug callbacks removed");
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) {
                debugCallbacks.add(callback);
            }
            Log.d("ProcessHelper", "Added debug callback: " + callback.toString());
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
            Log.d("ProcessHelper", "Removed debug callback: " + callback.toString());
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        int i = 0;
        int count = command.length();
        while (true) {
            if (i < count) {
                char currChar = command.charAt(i);
                if (startedQuotes) {
                    if (currChar == '\"') {
                        startedQuotes = false;
                        if (!value.isEmpty()) {
                            result.add(value + Typography.quote);
                            value = "";
                        }
                    } else {
                        value = value + currChar;
                    }
                } else if (currChar == '\"') {
                    startedQuotes = true;
                    value = value + Typography.quote;
                } else {
                    char nextChar = i < count + (-1) ? command.charAt(i + 1) : (char) 0;
                    if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                        if (currChar == '\\') {
                            value = value + ' ';
                            i++;
                        } else if (!value.isEmpty()) {
                            result.add(value);
                            value = "";
                        }
                    } else {
                        value = value + currChar;
                        if (i == count - 1) {
                            result.add(value);
                            value = "";
                        }
                    }
                }
                i++;
            } else {
                return (String[]) result.toArray(new String[0]);
            }
        }
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int) Math.pow(2.0d, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) {
            return 0;
        }
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int) Math.pow(2.0d, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) {
                affinityMask |= (int) Math.pow(2.0d, i);
            }
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) {
            affinityMask |= (int) Math.pow(2.0d, i);
        }
        return affinityMask;
    }

    public static ArrayList<String> listRunningWineProcesses() {
        File proc = new File("/proc");
        String[] filters = {ContentProfile.MARK_WINE, "exe"};
        ArrayList<String> filteredPids = new ArrayList<>();
        List<String> filterList = Arrays.asList(filters);
        String[] allPids = proc.list(new FilenameFilter() { // from class: com.winlator.cmod.core.ProcessHelper.2
            @Override // java.io.FilenameFilter
            public boolean accept(File proc2, String filename) {
                return new File(proc2, filename).isDirectory() && filename.matches("[0-9]+");
            }
        });
        for (int index = 0; index < allPids.length; index++) {
            String data = "";
            try {
                FileInputStream fr = new FileInputStream(proc + "/" + allPids[index] + "/stat");
                BufferedReader br = new BufferedReader(new InputStreamReader(fr));
                data = br.readLine();
            } catch (IOException e) {
            }
            for (String filter : filterList) {
                if (data.contains(filter)) {
                    filteredPids.add(allPids[index]);
                }
            }
        }
        return filteredPids;
    }
}
