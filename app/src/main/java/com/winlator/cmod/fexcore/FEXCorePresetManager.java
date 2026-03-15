package com.winlator.cmod.fexcore;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;

/* loaded from: classes3.dex */
public class FEXCorePresetManager {
    public static EnvVars getEnvVars(Context context, String id) {
        EnvVars envVars = new EnvVars();
        if (id.equals("STABILITY")) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "0");
        } else if (id.equals("COMPATIBILITY")) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "1");
        } else if (id.equals("INTERMEDIATE")) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        } else if (id.equals("PERFORMANCE")) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        } else if (id.startsWith("CUSTOM")) {
            Iterator<String[]> it = customPresetsIterator(context).iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                String[] preset = it.next();
                if (preset[0].equals(id)) {
                    envVars.putAll(preset[2]);
                    break;
                }
            }
        }
        return envVars;
    }

    public static ArrayList<FEXCorePreset> getPresets(Context context) {
        ArrayList<FEXCorePreset> presets = new ArrayList<>();
        presets.add(new FEXCorePreset("STABILITY", context.getString(R.string.stability)));
        presets.add(new FEXCorePreset("COMPATIBILITY", context.getString(R.string.compatibility)));
        presets.add(new FEXCorePreset("INTERMEDIATE", context.getString(R.string.intermediate)));
        presets.add(new FEXCorePreset("PERFORMANCE", context.getString(R.string.performance)));
        for (String[] preset : customPresetsIterator(context)) {
            presets.add(new FEXCorePreset(preset[0], preset[1]));
        }
        return presets;
    }

    public static FEXCorePreset getPreset(Context context, String id) {
        Iterator<FEXCorePreset> it = getPresets(context).iterator();
        while (it.hasNext()) {
            FEXCorePreset preset = it.next();
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return null;
    }

    private static Iterable<String[]> customPresetsIterator(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String customPresetsStr = preferences.getString("fexcore_custom_presets", "");
        final String[] customPresets = customPresetsStr.split(",");
        final int[] index = {0};
        return new Iterable() { // from class: com.winlator.cmod.fexcore.FEXCorePresetManager$$ExternalSyntheticLambda0
            @Override // java.lang.Iterable
            public final Iterator iterator() {
                return FEXCorePresetManager.lambda$customPresetsIterator$0(index, customPresets, customPresetsStr);
            }
        };
    }

    static /* synthetic */ Iterator lambda$customPresetsIterator$0(final int[] index, final String[] customPresets, final String customPresetsStr) {
        return new Iterator<String[]>() { // from class: com.winlator.cmod.fexcore.FEXCorePresetManager.1
            @Override // java.util.Iterator
            public boolean hasNext() {
                return index[0] < customPresets.length && !customPresetsStr.isEmpty();
            }

            @Override // java.util.Iterator
            public String[] next() {
                String[] strArr = customPresets;
                int[] iArr = index;
                int i = iArr[0];
                iArr[0] = i + 1;
                return strArr[i].split("\\|");
            }
        };
    }

    public static int getNextPresetId(Context context) {
        int maxId = 0;
        for (String[] preset : customPresetsIterator(context)) {
            maxId = Math.max(maxId, Integer.parseInt(preset[0].replace("CUSTOM-", "")));
        }
        return maxId + 1;
    }

    public static void editPreset(Context context, String id, String name, EnvVars envVars) {
        String customPresetsStr;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String str = "";
        String customPresetsStr2 = preferences.getString("fexcore_custom_presets", "");
        if (id != null) {
            String[] customPresets = customPresetsStr2.split(",");
            int i = 0;
            while (true) {
                if (i >= customPresets.length) {
                    break;
                }
                String[] preset = customPresets[i].split("\\|");
                if (!preset[0].equals(id)) {
                    i++;
                } else {
                    customPresets[i] = id + "|" + name + "|" + envVars.toString();
                    break;
                }
            }
            customPresetsStr = String.join(",", customPresets);
        } else {
            String preset2 = "CUSTOM-" + getNextPresetId(context) + "|" + name + "|" + envVars.toString();
            StringBuilder append = new StringBuilder().append(customPresetsStr2);
            if (!customPresetsStr2.isEmpty()) {
                str = ",";
            }
            customPresetsStr = append.append(str).append(preset2).toString();
        }
        preferences.edit().putString("fexcore_custom_presets", customPresetsStr).apply();
    }

    public static void duplicatePreset(Context context, String id) {
        ArrayList<FEXCorePreset> presets = getPresets(context);
        FEXCorePreset originPreset = null;
        Iterator<FEXCorePreset> it = presets.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            FEXCorePreset preset = it.next();
            if (preset.id.equals(id)) {
                originPreset = preset;
                break;
            }
        }
        if (originPreset == null) {
            return;
        }
        int i = 1;
        while (true) {
            String newName = originPreset.name + " (" + i + ")";
            boolean found = false;
            Iterator<FEXCorePreset> it2 = presets.iterator();
            while (true) {
                if (!it2.hasNext()) {
                    break;
                } else if (it2.next().name.equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                i++;
            } else {
                editPreset(context, null, newName, getEnvVars(context, originPreset.id));
                return;
            }
        }
    }

    public static void removePreset(Context context, String id) {
        String str;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String oldCustomPresetsStr = preferences.getString("fexcore_custom_presets", "");
        String newCustomPresetsStr = "";
        String[] customPresets = oldCustomPresetsStr.split(",");
        for (int i = 0; i < customPresets.length; i++) {
            String[] preset = customPresets[i].split("\\|");
            if (!preset[0].equals(id)) {
                StringBuilder append = new StringBuilder().append(newCustomPresetsStr);
                if (newCustomPresetsStr.isEmpty()) {
                    str = "";
                } else {
                    str = ",";
                }
                newCustomPresetsStr = append.append(str).append(customPresets[i]).toString();
            }
        }
        preferences.edit().putString("fexcore_custom_presets", newCustomPresetsStr).apply();
    }

    public static void exportPreset(Context context, String id) {
        File presetFile = null;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String[] customPresets = preferences.getString("fexcore_custom_presets", "").split(",");
        int i = 0;
        while (true) {
            if (i >= customPresets.length) {
                break;
            }
            String[] preset = customPresets[i].split("\\|");
            if (!preset[0].equals(id)) {
                i++;
            } else {
                String uriPath = preferences.getString("winlator_path_uri", null);
                if (uriPath == null) {
                    presetFile = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "Presets/fexcore_" + preset[1] + ".wbp");
                } else {
                    Uri uri = Uri.parse(uriPath);
                    String path = FileUtils.getFilePathFromUri(context, uri);
                    presetFile = new File(path, "Presets/fexcore_" + preset[1] + ".wbp");
                }
                if (!presetFile.getParentFile().exists()) {
                    presetFile.getParentFile().mkdirs();
                }
                try {
                    FileOutputStream fos = new FileOutputStream(presetFile);
                    PrintWriter pw = new PrintWriter(fos);
                    pw.write("ID:" + preset[0] + "\n");
                    pw.write("Name:" + preset[1] + "\n");
                    pw.write("EnvVars:" + preset[2] + "\n");
                    pw.close();
                    fos.close();
                } catch (IOException e) {
                }
            }
        }
        if (presetFile == null || !presetFile.exists()) {
            AppUtils.showToast(context, "Failed to export preset");
        } else {
            AppUtils.showToast(context, "Preset " + presetFile.getName() + " exported successfully at " + presetFile.getParentFile().getPath());
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public static void importPreset(Context context, InputStream stream) {
        char c;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String customPresetStr = preferences.getString("fexcore_custom_presets", "");
        ArrayList<String> lines = new ArrayList<>();
        try {
            String[] preset = new String[3];
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    } else {
                        lines.add(line);
                    }
                }
                for (int i = 0; i < lines.size(); i++) {
                    String[] contents = lines.get(i).split(":");
                    String str = contents[0];
                    switch (str.hashCode()) {
                        case 2331:
                            if (str.equals("ID")) {
                                c = 0;
                                break;
                            }
                            c = 65535;
                            break;
                        case 2420395:
                            if (str.equals("Name")) {
                                c = 1;
                                break;
                            }
                            c = 65535;
                            break;
                        case 74085529:
                            if (str.equals("EnvVars")) {
                                c = 2;
                                break;
                            }
                            c = 65535;
                            break;
                        default:
                            c = 65535;
                            break;
                    }
                    switch (c) {
                        case 0:
                            preset[0] = contents[1];
                            break;
                        case 1:
                            preset[1] = contents[1];
                            break;
                        case 2:
                            preset[2] = contents[1];
                            break;
                    }
                }
                customPresetStr = customPresetStr + (customPresetStr.equals("") ? "" : ",") + "CUSTOM-" + getNextPresetId(context) + "|" + preset[1] + "|" + preset[2];
            } catch (IOException e) {
            }
        } catch (IOException e2) {
        }
        preferences.edit().putString("fexcore_custom_presets", customPresetStr).apply();
    }

    public static void loadSpinner(Spinner spinner, String selectedId) {
        Context context = spinner.getContext();
        ArrayList<FEXCorePreset> presets = getPresets(context);
        int selectedPosition = 0;
        int i = 0;
        while (true) {
            if (i >= presets.size()) {
                break;
            }
            if (!presets.get(i).id.equals(selectedId)) {
                i++;
            } else {
                selectedPosition = i;
                break;
            }
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, presets));
        spinner.setSelection(selectedPosition);
    }

    public static String getSpinnerSelectedId(Spinner spinner) {
        SpinnerAdapter adapter = spinner.getAdapter();
        int selectedPosition = spinner.getSelectedItemPosition();
        if (adapter != null && adapter.getCount() > 0 && selectedPosition >= 0) {
            return ((FEXCorePreset) adapter.getItem(selectedPosition)).id;
        }
        return "COMPATIBILITY";
    }
}
