package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.ToggleButton;
import com.ludashi.benchmark.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.VKD3DVersionItem;
import com.winlator.cmod.xenvironment.ImageFs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* loaded from: classes4.dex */
public class DXVKConfigDialog extends ContentDialog {
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    public static final int DXVK_TYPE_NONE = 0;
    private static List<String> dxvkVersions;
    private final Context context;
    private boolean isARM64EC;
    private final View llAsync;
    private final View llAsyncCache;
    private final ToggleButton swAsync;
    private final ToggleButton swAsyncCache;
    public static final String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;
    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};

    /* JADX INFO: Access modifiers changed from: private */
    public static Integer tryGetMajor(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = SEMVER.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compareVersion(String varA, String varB) {
        String[] levelsA = varA.split("\\.");
        String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        for (int i = 0; i < minLen; i++) {
            int numA = Integer.parseInt(levelsA[i]);
            int numB = Integer.parseInt(levelsB[i]);
            if (numA != numB) {
                return numA - numB;
            }
        }
        int i2 = levelsA.length;
        if (i2 != levelsB.length) {
            return levelsA.length - levelsB.length;
        }
        return 0;
    }

    public DXVKConfigDialog(final View anchor, final boolean isARM64EC) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        this.isARM64EC = false;
        this.context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("DXVK " + this.context.getString(R.string.configuration));
        final Spinner sDXVKVersion = (Spinner) findViewById(R.id.SDXVKVersion);
        final Spinner sVKD3DVersion = (Spinner) findViewById(R.id.SVKD3DVersion);
        final Spinner sFramerate = (Spinner) findViewById(R.id.SFramerate);
        final Spinner sVKD3DFeatureLevel = (Spinner) findViewById(R.id.SVKD3DFeatureLevel);
        final Spinner sDDRAWrapper = (Spinner) findViewById(R.id.SDDRAWrapper);
        this.swAsync = (ToggleButton) findViewById(R.id.SWAsync);
        this.swAsyncCache = (ToggleButton) findViewById(R.id.SWAsyncCache);
        this.llAsync = findViewById(R.id.LLAsync);
        this.llAsyncCache = findViewById(R.id.LLAsyncCache);
        final ContentsManager contentsManager = new ContentsManager(this.context);
        contentsManager.syncContents();
        final KeyValueSet config = parseConfig(anchor.getTag());
        loadDxvkVersionSpinner(contentsManager, sDXVKVersion, isARM64EC);
        loadVkd3dVersionSpinner(contentsManager, sVKD3DVersion);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this.context, android.R.layout.simple_spinner_item, VKD3D_FEATURE_LEVEL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sVKD3DFeatureLevel.setAdapter((SpinnerAdapter) adapter);
        setDXVKSpinner(sDXVKVersion, config, contentsManager, isARM64EC);
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate"));
        AppUtils.setSpinnerSelectionFromIdentifier(sVKD3DVersion, config.get("vkd3dVersion"));
        AppUtils.setSpinnerSelectionFromIdentifier(sVKD3DFeatureLevel, config.get("vkd3dLevel"));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDRAWrapper, config.get("ddrawrapper"));
        this.swAsync.setChecked(config.get("async").equals("1"));
        this.swAsyncCache.setChecked(config.get("asyncCache").equals("1"));
        updateConfigVisibility(getDXVKType(sDXVKVersion.getSelectedItemPosition()));
        sDXVKVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.DXVKConfigDialog.1
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                DXVKConfigDialog.this.updateConfigVisibility(DXVKConfigDialog.this.getDXVKType(position));
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        sVKD3DVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.DXVKConfigDialog.2
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedVersion = sVKD3DVersion.getSelectedItem().toString();
                String currentDXVKVersion = config.get("version");
                if (!selectedVersion.equals(DefaultVersion.VKD3D)) {
                    ArrayList<String> versions = new ArrayList<>();
                    for (int i = 0; i < DXVKConfigDialog.dxvkVersions.size(); i++) {
                        Integer major = DXVKConfigDialog.tryGetMajor((String) DXVKConfigDialog.dxvkVersions.get(i));
                        if (major != null && major.intValue() < 2) {
                            versions.add((String) DXVKConfigDialog.dxvkVersions.get(i));
                        }
                    }
                    DXVKConfigDialog.dxvkVersions.removeAll(versions);
                    ArrayAdapter<String> adapter2 = new ArrayAdapter<>(DXVKConfigDialog.this.context, android.R.layout.simple_spinner_dropdown_item, (List<String>) DXVKConfigDialog.dxvkVersions);
                    sDXVKVersion.setAdapter((SpinnerAdapter) adapter2);
                    Integer curMajor = DXVKConfigDialog.tryGetMajor(currentDXVKVersion);
                    AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, (curMajor == null || curMajor.intValue() < 2) ? DefaultVersion.DXVK : currentDXVKVersion);
                    DXVKConfigDialog.this.updateConfigVisibility(DXVKConfigDialog.this.getDXVKType(sDXVKVersion.getSelectedItemPosition()));
                    return;
                }
                DXVKConfigDialog.this.loadDxvkVersionSpinner(contentsManager, sDXVKVersion, isARM64EC);
                AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, config.get("version"));
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.DXVKConfigDialog$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                DXVKConfigDialog.this.lambda$new$0(config, sDXVKVersion, sFramerate, sVKD3DVersion, sVKD3DFeatureLevel, sDDRAWrapper, anchor);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$0(KeyValueSet config, Spinner sDXVKVersion, Spinner sFramerate, Spinner sVKD3DVersion, Spinner sVKD3DFeatureLevel, Spinner sDDRAWrapper, View anchor) {
        config.put("version", sDXVKVersion.getSelectedItem().toString());
        config.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
        config.put("async", (this.swAsync.isChecked() && this.llAsync.getVisibility() == 0) ? "1" : "0");
        config.put("asyncCache", (this.swAsyncCache.isChecked() && this.llAsyncCache.getVisibility() == 0) ? "1" : "0");
        VKD3DVersionItem selectedItem = (VKD3DVersionItem) sVKD3DVersion.getSelectedItem();
        config.put("vkd3dVersion", selectedItem.getIdentifier());
        config.put("vkd3dLevel", sVKD3DFeatureLevel.getSelectedItem().toString());
        config.put("ddrawrapper", StringUtils.parseIdentifier(sDDRAWrapper.getSelectedItem().toString()));
        anchor.setTag(config.toString());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateConfigVisibility(int dxvkType) {
        if (dxvkType == 1) {
            this.llAsync.setVisibility(0);
            this.llAsyncCache.setVisibility(8);
        } else if (dxvkType == 2) {
            this.llAsync.setVisibility(0);
            this.llAsyncCache.setVisibility(0);
        } else {
            this.llAsync.setVisibility(8);
            this.llAsyncCache.setVisibility(8);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getDXVKType(int pos) {
        String v = dxvkVersions.get(pos);
        if (v.contains("gplasync")) {
            return 2;
        }
        if (!v.contains("async")) {
            return 0;
        }
        return 1;
    }

    private void setDXVKSpinner(Spinner sDXVKVersion, KeyValueSet config, ContentsManager contentsManager, boolean isARM64EC) {
        String selectedVersion = config.get("vkd3dVersion");
        String currentDXVKVersion = config.get("version");
        if (!selectedVersion.equals(DefaultVersion.VKD3D)) {
            ArrayList<String> versions = new ArrayList<>();
            for (int i = 0; i < dxvkVersions.size(); i++) {
                Integer major = tryGetMajor(dxvkVersions.get(i));
                if (major != null && major.intValue() < 2) {
                    versions.add(dxvkVersions.get(i));
                }
            }
            dxvkVersions.removeAll(versions);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this.context, android.R.layout.simple_spinner_dropdown_item, dxvkVersions);
            sDXVKVersion.setAdapter((SpinnerAdapter) adapter);
            Integer curMajor = tryGetMajor(currentDXVKVersion);
            AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, (curMajor == null || curMajor.intValue() < 2) ? DefaultVersion.DXVK : currentDXVKVersion);
            return;
        }
        AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, currentDXVKVersion);
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = (config == null || config.toString().isEmpty()) ? DEFAULT_CONFIG : config.toString();
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String content = "";
        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            content = ("dxgi.maxFrameRate = " + framerate + "; ") + "d3d9.maxFrameRate = " + framerate;
            envVars.put("DXVK_FRAME_RATE", framerate);
        }
        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0")) {
            envVars.put("DXVK_ASYNC", "1");
        }
        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0")) {
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        }
        if (!content.isEmpty()) {
            envVars.put("DXVK_CONFIG", content);
        }
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner, boolean isARM64EC) {
        this.isARM64EC = isARM64EC;
        String[] originalItems = this.context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf(45);
            itemList.add(entryName.substring(firstDashIndex + 1));
        }
        for (int i = 0; i < itemList.size(); i++) {
            if (itemList.get(i).contains("arm64ec") && !isARM64EC) {
                itemList.remove(i);
            }
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(this.context, android.R.layout.simple_spinner_dropdown_item, itemList));
        dxvkVersions = itemList;
    }

    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner) {
        List<VKD3DVersionItem> itemList = new ArrayList<>();
        String[] originalItems = this.context.getResources().getStringArray(R.array.vkd3d_version_entries);
        for (String version : originalItems) {
            itemList.add(new VKD3DVersionItem(version));
        }
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            String displayName = profile.verName;
            int versionCode = profile.verCode;
            itemList.add(new VKD3DVersionItem(displayName, versionCode));
        }
        ArrayAdapter<VKD3DVersionItem> adapter = new ArrayAdapter<>(this.context, android.R.layout.simple_spinner_dropdown_item, itemList);
        spinner.setAdapter((SpinnerAdapter) adapter);
    }
}
