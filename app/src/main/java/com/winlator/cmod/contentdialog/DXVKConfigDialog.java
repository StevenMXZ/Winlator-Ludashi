package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.ToggleButton;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.D7VKManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.VKD3DVersionItem;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DXVKConfigDialog extends ContentDialog {
    public interface ContentInstallHost {
        void showInstallChoicePopup(View anchor, List<ContentProfile.ContentType> types, Runnable refreshAction);
        void removeSelectedContent(List<ContentProfile.ContentType> types, java.util.function.Supplier<String> selectedValue, Runnable refreshAction);
    }
    public static final String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    private final ToggleButton swAsync;
    private boolean isARM64EC = false;
    private final ToggleButton swAsyncCache;
    private final ToggleButton swMaxFrameLatency;
    private final View llAsync;
    private final View llAsyncCache;
    private final Context context;
    private final ContentInstallHost installHost;
    private final ContentsManager contentsManager;
    private static List<String> dxvkVersions;
    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static Integer tryGetMajor(String s) {
        if (s == null) return null;
        Matcher m = SEMVER.matcher(s);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};

    private static int compareVersion(String varA, String varB) {
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        int numA, numB;

        for (int i = 0; i < minLen; i++) {
            numA = Integer.parseInt(levelsA[i]);
            numB = Integer.parseInt(levelsB[i]);
            if (numA != numB)
                return numA - numB;
        }

        if (levelsA.length != levelsB.length)
            return levelsA.length - levelsB.length;

        return 0;
    }

    public DXVKConfigDialog(View anchor, boolean isARM64EC) {
        this(anchor, isARM64EC, null, new ContentsManager(anchor.getContext()));
    }

    public DXVKConfigDialog(View anchor, boolean isARM64EC, ContentInstallHost installHost, ContentsManager contentsManager) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        context = anchor.getContext();
        this.installHost = installHost;
        this.contentsManager = contentsManager;
        findViewById(R.id.FrameLayout).getLayoutParams().width = Math.min(AppUtils.getPreferredDialogWidth(context), Math.round(UnitUtils.dpToPx(300)));
        setIcon(R.drawable.icon_monitor);
        setTitle("DXVK "+context.getString(R.string.configuration));

        final Spinner sDXVKVersion = findViewById(R.id.SDXVKVersion);
        final Spinner sVKD3DVersion = findViewById(R.id.SVKD3DVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sVKD3DFeatureLevel = findViewById(R.id.SVKD3DFeatureLevel);
        final Spinner sDDRAWrapper = findViewById(R.id.SDDRAWrapper);
        swAsync = findViewById(R.id.SWAsync);
        swAsyncCache = findViewById(R.id.SWAsyncCache);
        swMaxFrameLatency = findViewById(R.id.SWMaxFrameLatency);
        llAsync = findViewById(R.id.LLAsync);
        llAsyncCache = findViewById(R.id.LLAsyncCache);

        this.contentsManager.syncContents();

        KeyValueSet config = parseConfig(anchor.getTag());
        final List<String> ddrawEntries = D7VKManager.getWrapperEntries(context);
        List<String> ddrawLabels = new ArrayList<>();
        for (String entry : ddrawEntries) ddrawLabels.add(D7VKManager.getWrapperLabel(entry));
        ArrayAdapter<String> ddrawAdapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, ddrawLabels);
        ddrawAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
        sDDRAWrapper.setAdapter(ddrawAdapter);
        configureContentSpinnerDropdown(sDDRAWrapper);
        loadDxvkVersionSpinner(this.contentsManager, sDXVKVersion, isARM64EC);
        loadVkd3dVersionSpinner(this.contentsManager, sVKD3DVersion, isARM64EC);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, VKD3D_FEATURE_LEVEL);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
        sVKD3DFeatureLevel.setAdapter(adapter);
        configureContentSpinnerDropdown(sVKD3DFeatureLevel);

        setDXVKSpinner(sDXVKVersion, config, this.contentsManager, isARM64EC);
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate"));
        AppUtils.setSpinnerSelectionFromIdentifier(sVKD3DVersion, config.get("vkd3dVersion"));
        AppUtils.setSpinnerSelectionFromIdentifier(sVKD3DFeatureLevel, config.get("vkd3dLevel"));
        for (int i = 0; i < ddrawEntries.size(); i++) {
            if (ddrawEntries.get(i).equalsIgnoreCase(config.get("ddrawrapper"))) {
                sDDRAWrapper.setSelection(i);
                break;
            }
        }

        swAsync.setChecked(config.get("async").equals("1"));
        swAsyncCache.setChecked(config.get("asyncCache").equals("1"));
        swMaxFrameLatency.setChecked(config.get("maxFrameLatency").equals("1"));

        updateConfigVisibility(getDXVKType(sDXVKVersion.getSelectedItemPosition()));

        sDXVKVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateConfigVisibility(getDXVKType(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sVKD3DVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedVersion = sVKD3DVersion.getSelectedItem().toString();
                String currentDXVKVersion = config.get("version");

                if (!selectedVersion.equals("None")) {
                    ArrayList<String> versions = new ArrayList<>();

                    for (int i = 0; i < dxvkVersions.size(); i++) {
                        Integer major = tryGetMajor(dxvkVersions.get(i));
                        if (major != null && major < 2) {
                            versions.add(dxvkVersions.get(i));
                        }
                    }

                    dxvkVersions.removeAll(versions);

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, dxvkVersions);
                    adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled);
                    sDXVKVersion.setAdapter(adapter);
                    sDXVKVersion.setPopupBackgroundResource(R.drawable.dialog_background_dark_blue);

                    Integer curMajor = tryGetMajor(currentDXVKVersion);
                    AppUtils.setSpinnerSelectionFromIdentifier(
                            sDXVKVersion,
                            (curMajor != null && curMajor >= 2) ? currentDXVKVersion : DefaultVersion.DXVK
                    );
                    updateConfigVisibility(getDXVKType(sDXVKVersion.getSelectedItemPosition()));
                }
                else {
                    loadDxvkVersionSpinner(DXVKConfigDialog.this.contentsManager, sDXVKVersion, isARM64EC);
                    AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, config.get("version"));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });


        if (installHost != null) {
            findViewById(R.id.BTDXVKInstall).setOnClickListener(v ->
                    installHost.showInstallChoicePopup(v, Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_DXVK), () -> {
                        this.contentsManager.syncContents();
                        loadDxvkVersionSpinner(this.contentsManager, sDXVKVersion, isARM64EC);
                    }));

            findViewById(R.id.BTDXVKRemove).setOnClickListener(v ->
                    installHost.removeSelectedContent(Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_DXVK),
                            () -> sDXVKVersion.getSelectedItem() != null ? sDXVKVersion.getSelectedItem().toString() : "",
                            () -> {
                                this.contentsManager.syncContents();
                                loadDxvkVersionSpinner(this.contentsManager, sDXVKVersion, isARM64EC);
                            }));

            findViewById(R.id.BTVkd3dInstall).setOnClickListener(v ->
                    installHost.showInstallChoicePopup(v, Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_VKD3D), () -> {
                        this.contentsManager.syncContents();
                        loadVkd3dVersionSpinner(this.contentsManager, sVKD3DVersion, isARM64EC);
                    }));

            findViewById(R.id.BTVkd3dRemove).setOnClickListener(v ->
                    installHost.removeSelectedContent(Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_VKD3D),
                            () -> sVKD3DVersion.getSelectedItem() != null ? sVKD3DVersion.getSelectedItem().toString() : "",
                            () -> {
                                this.contentsManager.syncContents();
                                loadVkd3dVersionSpinner(this.contentsManager, sVKD3DVersion, isARM64EC);
                            }));
        } else {
            findViewById(R.id.BTDXVKInstall).setVisibility(View.GONE);
            findViewById(R.id.BTDXVKRemove).setVisibility(View.GONE);
            findViewById(R.id.BTVkd3dInstall).setVisibility(View.GONE);
            findViewById(R.id.BTVkd3dRemove).setVisibility(View.GONE);
        }

        setOnConfirmCallback(() -> {
            config.put("version", sDXVKVersion.getSelectedItem().toString());
            config.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            config.put("async", ((swAsync.isChecked())&&(llAsync.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("asyncCache", ((swAsyncCache.isChecked())&&(llAsyncCache.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("maxFrameLatency", swMaxFrameLatency.isChecked()?"1":"0");
            VKD3DVersionItem selectedItem = (VKD3DVersionItem) sVKD3DVersion.getSelectedItem();
            config.put("vkd3dVersion", selectedItem.getIdentifier());
            config.put("vkd3dLevel", sVKD3DFeatureLevel.getSelectedItem().toString());
            config.put("ddrawrapper", ddrawEntries.get(sDDRAWrapper.getSelectedItemPosition()));
            anchor.setTag(config.toString());
        });
    }

    private void updateConfigVisibility(int dxvkType) {
        boolean wasAsyncVisible = llAsync.getVisibility() == View.VISIBLE;
        boolean wasAsyncCacheVisible = llAsyncCache.getVisibility() == View.VISIBLE;

        if (dxvkType == DXVK_TYPE_ASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.GONE);
            if (!wasAsyncVisible) swAsync.setChecked(true);
        } else if (dxvkType == DXVK_TYPE_GPLASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.VISIBLE);
            if (!wasAsyncVisible) swAsync.setChecked(true);
            if (!wasAsyncCacheVisible) swAsyncCache.setChecked(true);
        } else {
            llAsync.setVisibility(View.GONE);
            llAsyncCache.setVisibility(View.GONE);
        }
    }

    private int getDXVKType(int pos) {
        final String v = dxvkVersions.get(pos);
        int dxvkType = DXVK_TYPE_NONE;
        if (v.contains("gplasync"))
            dxvkType = DXVK_TYPE_GPLASYNC;
        else if (v.contains("async"))
            dxvkType = DXVK_TYPE_ASYNC;
        return dxvkType;
    }

    private void setDXVKSpinner(Spinner sDXVKVersion, KeyValueSet config, ContentsManager contentsManager, boolean isARM64EC) {
        String selectedVersion = config.get("vkd3dVersion");
        String currentDXVKVersion = config.get("version");
        // Preserve installed custom DXVK packages, but present the bundled fallback
        // for older configurations whose optional bundled archive was removed.
        if (!dxvkVersions.contains(currentDXVKVersion)) {
            if ("1.11.1-sarek".equals(currentDXVKVersion)
                    || "1.10.3-arm64ec-async".equals(currentDXVKVersion)) {
                currentDXVKVersion = "1.10.3";
            } else if ("2.3.1-arm64ec-gplasync".equals(currentDXVKVersion)) {
                currentDXVKVersion = "2.3.1";
            }
        }
        if (!selectedVersion.equals("None")) {
            ArrayList<String> versions = new ArrayList<>();

            for (int i = 0; i < dxvkVersions.size(); i++) {
                Integer major = tryGetMajor(dxvkVersions.get(i));
                if (major != null && major < 2) {
                    versions.add(dxvkVersions.get(i));
                }
            }

            dxvkVersions.removeAll(versions);

            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, dxvkVersions);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
            sDXVKVersion.setAdapter(adapter);
            configureContentSpinnerDropdown(sDXVKVersion);

            Integer curMajor = tryGetMajor(currentDXVKVersion);
            AppUtils.setSpinnerSelectionFromIdentifier(
                    sDXVKVersion,
                    (curMajor != null && curMajor >= 2) ? currentDXVKVersion : DefaultVersion.DXVK
            );
        }
        else
            AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, currentDXVKVersion);
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() :  DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String content = "";

        String framerate = config.get("framerate");

        if (!framerate.isEmpty() && !framerate.equals("0")) {
            content += "dxgi.maxFrameRate = " + framerate + "; ";
            content += "d3d9.maxFrameRate = " + framerate;
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String maxFrameLatency = config.get("maxFrameLatency");
        if (!maxFrameLatency.isEmpty() && !maxFrameLatency.equals("0")) {
            if (!content.isEmpty()) content += "; ";
            content += "dxgi.maxFrameLatency = 1";
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");

        if (!content.isEmpty())
            envVars.put("DXVK_CONFIG", content);

        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);
    }

    private boolean isVersionAllowedForArch(String version, boolean isARM64EC) {
        return isARM64EC || !version.toLowerCase().contains("arm64ec");
    }

    private void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner, boolean isARM64EC) {
        this.isARM64EC = isARM64EC;
        String[] originalItems = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));

        for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }

        itemList.removeIf(version -> !isVersionAllowedForArch(version, isARM64EC));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, itemList);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
        spinner.setAdapter(adapter);
        configureContentSpinnerDropdown(spinner);
        dxvkVersions = itemList;
    }

    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner, boolean isARM64EC) {
        List<VKD3DVersionItem> itemList = new ArrayList<>();

        String[] originalItems = context.getResources().getStringArray(R.array.vkd3d_version_entries);
        for (String version : originalItems) {
            if (isVersionAllowedForArch(version, isARM64EC))
                itemList.add(new VKD3DVersionItem(version));
        }

        for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            String displayName = profile.verName;
            int versionCode = profile.verCode;
            if (isVersionAllowedForArch(displayName, isARM64EC))
                itemList.add(new VKD3DVersionItem(displayName, versionCode));
        }

        ArrayAdapter<VKD3DVersionItem> adapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, itemList);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
        spinner.setAdapter(adapter);
        configureContentSpinnerDropdown(spinner);
    }

    private void configureContentSpinnerDropdown(Spinner spinner) {
        spinner.setPopupBackgroundResource(R.drawable.dialog_background_dark_blue);
        spinner.setDropDownWidth(Math.round(UnitUtils.dpToPx(240)));
        spinner.setDropDownVerticalOffset(Math.round(UnitUtils.dpToPx(-2)));
    }

}
