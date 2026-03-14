package com.winlator.cmod.contentdialog;

import android.R;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.widget.MultiSelectionComboBox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes4.dex */
public class GraphicsDriverConfigDialog extends ContentDialog {
    private static final String TAG = "GraphicsDriverConfigDialog";
    private static String blacklistedExtensions = "";
    private static String isBCnCacheEnabled;
    private static String isDisablePresentWait;
    private static String isSyncFrame;
    private static String selectedBCnEmulation;
    private static String selectedBCnEmulationType;
    private static String selectedDeviceMemory;
    private static String selectedGPUName;
    private static String selectedPresentMode;
    private static String selectedResourceType;
    private static String selectedVersion;
    private static String selectedVulkanVersion;
    private CheckBox cbDisablePresentWait;
    private CheckBox cbSyncFrame;
    private MultiSelectionComboBox mscAvailableExtensions;
    private Spinner sBCnEmulation;
    private Spinner sBCnEmulationCache;
    private Spinner sBCnEmulationType;
    private Spinner sGPUName;
    private Spinner sMaxDeviceMemory;
    private Spinner sPresentMode;
    private Spinner sResourceType;
    private Spinner sVersion;
    private Spinner sVulkanVersion;

    private void loadGPUNameSpinner(Context context, Spinner spinner) {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        ArrayList<String> entries = new ArrayList<>();
        entries.add("Device");
        try {
            JSONArray jarray = new JSONArray(gpuNameList);
            for (int i = 0; i < jarray.length(); i++) {
                JSONObject jobj = jarray.getJSONObject(i);
                String gpuName = jobj.getString("name");
                entries.add(gpuName);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.simple_spinner_dropdown_item, entries);
            spinner.setAdapter((SpinnerAdapter) adapter);
        } catch (JSONException e) {
        }
    }

    public static HashMap<String, String> parseGraphicsDriverConfig(String graphicsDriverConfig) {
        String value;
        HashMap<String, String> mappedConfig = new HashMap<>();
        String[] configElements = graphicsDriverConfig.split(";");
        for (String element : configElements) {
            String[] splittedElement = element.split("=");
            String key = splittedElement[0];
            if (splittedElement.length > 1) {
                value = element.split("=")[1];
            } else {
                value = "";
            }
            mappedConfig.put(key, value);
        }
        return mappedConfig;
    }

    public static String toGraphicsDriverConfig(HashMap<String, String> config) {
        String graphicsDriverConfig = "";
        for (Map.Entry<String, String> entry : config.entrySet()) {
            graphicsDriverConfig = graphicsDriverConfig + entry.getKey() + "=" + entry.getValue() + ";";
        }
        return graphicsDriverConfig.substring(0, graphicsDriverConfig.length() - 1);
    }

    public static String getVersion(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("version");
    }

    public static String getExtensionsBlacklist(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("blacklistedExtensions");
    }

    public static String writeGraphicsDriverConfig() {
        String graphicsDriverConfig = "vulkanVersion=" + selectedVulkanVersion + ";version=" + selectedVersion + ";blacklistedExtensions=" + blacklistedExtensions + ";maxDeviceMemory=" + StringUtils.parseNumber(selectedDeviceMemory) + ";presentMode=" + selectedPresentMode + ";syncFrame=" + isSyncFrame + ";disablePresentWait=" + isDisablePresentWait + ";resourceType=" + selectedResourceType + ";bcnEmulation=" + selectedBCnEmulation + ";bcnEmulationType=" + selectedBCnEmulationType + ";bcnEmulationCache=" + isBCnCacheEnabled + ";gpuName=" + selectedGPUName;
        Log.i(TAG, "Written config " + graphicsDriverConfig);
        return graphicsDriverConfig;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String[] queryAvailableExtensions(String driver, Context context) {
        return GPUInformation.enumerateExtensions(driver, context);
    }

    public GraphicsDriverConfigDialog(View anchor, String graphicsDriver, TextView graphicsDriverVersionView) {
        super(anchor.getContext(), com.ludashi.benchmark.R.layout.graphics_driver_config_dialog);
        initializeDialog(anchor, graphicsDriver, graphicsDriverVersionView);
    }

    private void initializeDialog(final View anchor, String graphicsDriver, final TextView graphicsDriverVersionView) {
        setIcon(com.ludashi.benchmark.R.drawable.icon_settings);
        setTitle(anchor.getContext().getString(com.ludashi.benchmark.R.string.graphics_driver_configuration));
        String graphicsDriverConfig = anchor.getTag().toString();
        this.sVersion = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverVersion);
        this.sVulkanVersion = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverVulkanVersion);
        this.mscAvailableExtensions = (MultiSelectionComboBox) findViewById(com.ludashi.benchmark.R.id.MSCAvailableExtensions);
        this.sPresentMode = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverPresentMode);
        this.sGPUName = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverGPUName);
        this.sMaxDeviceMemory = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverMaxDeviceMemory);
        this.sResourceType = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverResourceType);
        this.sBCnEmulation = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverBCnEmulation);
        this.sBCnEmulationType = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverBCnEmulationType);
        this.sBCnEmulationCache = (Spinner) findViewById(com.ludashi.benchmark.R.id.SGraphicsDriverBCnEmulationCache);
        this.cbSyncFrame = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBSyncFrame);
        this.cbDisablePresentWait = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBDisablePresentWait);
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        String vulkanVersion = config.get("vulkanVersion");
        final String initialVersion = config.get("version");
        final String blExtensions = config.get("blacklistedExtensions");
        String gpuName = config.get("gpuName");
        String maxDeviceMemory = config.get("maxDeviceMemory");
        String syncFrame = config.get("syncFrame");
        String disablePresentWait = config.get("disablePresentWait");
        String presentMode = config.get("presentMode");
        String resourceType = config.get("resourceType");
        String bcnEmulation = config.get("bcnEmulation");
        String bcnEmulationType = config.get("bcnEmulationType");
        String bcnEmulationCache = config.get("bcnEmulationCache");
        this.sVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.1
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                GraphicsDriverConfigDialog.selectedVersion = GraphicsDriverConfigDialog.this.sVersion.getSelectedItem().toString();
                String[] availableExtensions = GraphicsDriverConfigDialog.this.queryAvailableExtensions(GraphicsDriverConfigDialog.selectedVersion, anchor.getContext());
                String blacklistedExtensions2 = "";
                GraphicsDriverConfigDialog.this.mscAvailableExtensions.setItems(availableExtensions, "Extensions");
                GraphicsDriverConfigDialog.this.mscAvailableExtensions.setSelectedItems(availableExtensions);
                if (GraphicsDriverConfigDialog.selectedVersion.equals(initialVersion)) {
                    blacklistedExtensions2 = blExtensions;
                }
                String[] bl = blacklistedExtensions2.split("\\,");
                for (String extension : bl) {
                    GraphicsDriverConfigDialog.this.mscAvailableExtensions.unsetSelectedItem(extension);
                }
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
                GraphicsDriverConfigDialog.selectedVersion = GraphicsDriverConfigDialog.this.sVersion.getSelectedItem().toString();
                Log.d(GraphicsDriverConfigDialog.TAG, "User selected version: " + GraphicsDriverConfigDialog.selectedVersion);
            }
        });
        this.sVulkanVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.2
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                GraphicsDriverConfigDialog.selectedVulkanVersion = GraphicsDriverConfigDialog.this.sVulkanVersion.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        this.sGPUName.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.3
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                GraphicsDriverConfigDialog.selectedGPUName = GraphicsDriverConfigDialog.this.sGPUName.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        this.sMaxDeviceMemory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.4
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                GraphicsDriverConfigDialog.selectedDeviceMemory = GraphicsDriverConfigDialog.this.sMaxDeviceMemory.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        this.sPresentMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.5
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                GraphicsDriverConfigDialog.selectedPresentMode = GraphicsDriverConfigDialog.this.sPresentMode.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        this.sResourceType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.6
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                GraphicsDriverConfigDialog.selectedResourceType = GraphicsDriverConfigDialog.this.sResourceType.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
        this.sBCnEmulation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.7
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                GraphicsDriverConfigDialog.selectedBCnEmulation = GraphicsDriverConfigDialog.this.sBCnEmulation.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
        this.sBCnEmulationType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.8
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                GraphicsDriverConfigDialog.selectedBCnEmulationType = GraphicsDriverConfigDialog.this.sBCnEmulationType.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
        this.sBCnEmulationCache.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog.9
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                GraphicsDriverConfigDialog.isBCnCacheEnabled = GraphicsDriverConfigDialog.this.sBCnEmulationCache.getSelectedItem().toString();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
        isSyncFrame = syncFrame;
        this.cbSyncFrame.setChecked(isSyncFrame.equals("1"));
        this.cbSyncFrame.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog$$ExternalSyntheticLambda0
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                GraphicsDriverConfigDialog.isSyncFrame = isChecked ? "1" : "0";
            }
        });
        isDisablePresentWait = disablePresentWait;
        this.cbDisablePresentWait.setChecked(isDisablePresentWait.equals("1"));
        this.cbDisablePresentWait.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog$$ExternalSyntheticLambda1
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                GraphicsDriverConfigDialog.isDisablePresentWait = isChecked ? "1" : "0";
            }
        });
        ContentsManager contentsManager = new ContentsManager(anchor.getContext());
        contentsManager.syncContents();
        populateGraphicsDriverVersions(anchor.getContext(), contentsManager, vulkanVersion, initialVersion, blExtensions, gpuName, maxDeviceMemory, presentMode, resourceType, bcnEmulation, bcnEmulationType, bcnEmulationCache, graphicsDriver);
        setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                GraphicsDriverConfigDialog.this.lambda$initializeDialog$2(graphicsDriverVersionView, anchor);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$initializeDialog$2(TextView graphicsDriverVersionView, View anchor) {
        blacklistedExtensions = this.mscAvailableExtensions.getUnSelectedItemsAsString();
        if (graphicsDriverVersionView != null) {
            graphicsDriverVersionView.setText(selectedVersion);
        }
        anchor.setTag(writeGraphicsDriverConfig());
    }

    private void populateGraphicsDriverVersions(Context context, ContentsManager contentsManager, String vulkanVersion, String initialVersion, String blExtensions, String gpuName, String maxDeviceMemory, String presentMode, String selectedResourceType2, String bcnEmulation, String bcnEmulationType, String bcnEmulationCache, String graphicsDriver) {
        List<String> wrapperVersions = new ArrayList<>();
        String[] wrapperDefaultVersions = context.getResources().getStringArray(com.ludashi.benchmark.R.array.wrapper_graphics_driver_version_entries);
        for (String version : wrapperDefaultVersions) {
            if (GPUInformation.isDriverSupported(version, context)) {
                wrapperVersions.add(version);
            }
        }
        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(context);
        wrapperVersions.addAll(adrenotoolsManager.enumarateInstalledDrivers());
        ArrayAdapter<String> wrapperAdapter = new ArrayAdapter<>(context, R.layout.simple_spinner_dropdown_item, wrapperVersions);
        this.sVersion.setAdapter((SpinnerAdapter) wrapperAdapter);
        Log.d(TAG, "Graphics driver: " + graphicsDriver);
        Log.d(TAG, "Initial version: " + initialVersion);
        loadGPUNameSpinner(context, this.sGPUName);
        setSpinnerSelectionWithFallback(this.sVersion, initialVersion, graphicsDriver);
        AppUtils.setSpinnerSelectionFromValue(this.sVulkanVersion, vulkanVersion);
        AppUtils.setSpinnerSelectionFromValue(this.sGPUName, gpuName);
        AppUtils.setSpinnerSelectionFromNumber(this.sMaxDeviceMemory, maxDeviceMemory);
        AppUtils.setSpinnerSelectionFromValue(this.sPresentMode, presentMode);
        AppUtils.setSpinnerSelectionFromValue(this.sResourceType, selectedResourceType2);
        AppUtils.setSpinnerSelectionFromValue(this.sBCnEmulation, bcnEmulation);
        AppUtils.setSpinnerSelectionFromValue(this.sBCnEmulationType, bcnEmulationType);
        AppUtils.setSpinnerSelectionFromValue(this.sBCnEmulationCache, bcnEmulationCache);
        Log.d(TAG, "Spinner selected position: " + this.sVersion.getSelectedItemPosition());
        Log.d(TAG, "Spinner selected value: " + this.sVersion.getSelectedItem());
    }

    private void setSpinnerSelectionWithFallback(Spinner spinner, String version, String graphicsDriver) {
        for (int i = 0; i < spinner.getCount(); i++) {
            String item = spinner.getItemAtPosition(i).toString();
            if (item.equalsIgnoreCase(version)) {
                spinner.setSelection(i);
                return;
            }
        }
        Context context = getContext();
        String str = DefaultVersion.WRAPPER_ADRENO;
        if (!GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)) {
            str = DefaultVersion.WRAPPER;
        }
        AppUtils.setSpinnerSelectionFromValue(spinner, str);
    }
}
