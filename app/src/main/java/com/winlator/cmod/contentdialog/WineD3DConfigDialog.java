package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import androidx.exifinterface.media.ExifInterface;
import com.ludashi.benchmark.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes4.dex */
public class WineD3DConfigDialog extends ContentDialog {
    private Context context;
    public static String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;
    public static String[] csmtValues = {"Enabled", "Disabled"};
    public static String[] strictShaderMathValues = {"Enabled", "Disabled"};
    public static String[] offscreenRenderingModeValues = {"fbo", "backbuffer"};
    public static String[] rendererValues = {"gl", "vulkan", "gdi"};

    public WineD3DConfigDialog(final View view) {
        super(view.getContext(), R.layout.wined3d_config_dialog);
        this.context = view.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("WineD3D " + this.context.getString(R.string.configuration));
        final Spinner spinner = (Spinner) findViewById(R.id.SCSMT);
        final Spinner spinner2 = (Spinner) findViewById(R.id.SGPUName);
        final Spinner spinner3 = (Spinner) findViewById(R.id.SVideoMemorySize);
        final Spinner spinner4 = (Spinner) findViewById(R.id.SStrictShaderMath);
        final Spinner spinner5 = (Spinner) findViewById(R.id.SOffscreenRenderingMode);
        final Spinner spinner6 = (Spinner) findViewById(R.id.SRenderer);
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(this.context, android.R.layout.simple_spinner_dropdown_item, csmtValues));
        spinner4.setAdapter((SpinnerAdapter) new ArrayAdapter(this.context, android.R.layout.simple_spinner_dropdown_item, strictShaderMathValues));
        spinner5.setAdapter((SpinnerAdapter) new ArrayAdapter(this.context, android.R.layout.simple_spinner_dropdown_item, offscreenRenderingModeValues));
        spinner6.setAdapter((SpinnerAdapter) new ArrayAdapter(this.context, android.R.layout.simple_spinner_dropdown_item, rendererValues));
        loadGPUNameSpinner(spinner2);
        final KeyValueSet parseConfig = parseConfig(view.getTag());
        spinner.setSelection(!parseConfig.get("csmt").equals(ExifInterface.GPS_MEASUREMENT_3D) ? 1 : 0);
        spinner4.setSelection(!parseConfig.get("strict_shader_math").equals("1") ? 1 : 0);
        AppUtils.setSpinnerSelectionFromValue(spinner5, parseConfig.get("OffscreenRenderingMode"));
        AppUtils.setSpinnerSelectionFromValue(spinner2, parseConfig.get("gpuName"));
        AppUtils.setSpinnerSelectionFromValue(spinner6, parseConfig.get("renderer"));
        AppUtils.setSpinnerSelectionFromNumber(spinner3, parseConfig.get("videoMemorySize"));
        setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.WineD3DConfigDialog$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                WineD3DConfigDialog.lambda$new$0(KeyValueSet.this, spinner, spinner4, spinner5, spinner2, spinner3, spinner6, view);
            }
        });
    }

    static /* synthetic */ void lambda$new$0(KeyValueSet config, Spinner sCSMT, Spinner sStrictShaderMath, Spinner sOffscreenRenderingMode, Spinner sGPUName, Spinner sVideoMemorySize, Spinner sRenderer, View anchor) {
        config.put("csmt", sCSMT.getSelectedItem().toString().equals("Enabled") ? ExifInterface.GPS_MEASUREMENT_3D : "0");
        config.put("strict_shader_math", sStrictShaderMath.getSelectedItem().toString().equals("Enabled") ? "1" : "0");
        config.put("OffscreenRenderingMode", sOffscreenRenderingMode.getSelectedItem().toString());
        config.put("gpuName", sGPUName.getSelectedItem().toString());
        config.put("videoMemorySize", StringUtils.parseNumber(sVideoMemorySize.getSelectedItem().toString()));
        config.put("renderer", sRenderer.getSelectedItem().toString());
        anchor.setTag(config.toString());
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = (config == null || config.toString().isEmpty()) ? DEFAULT_CONFIG : config.toString();
        return new KeyValueSet(data);
    }

    private void loadGPUNameSpinner(Spinner spinner) {
        String gpuNameList = FileUtils.readString(this.context, "gpu_cards.json");
        ArrayList<String> entries = new ArrayList<>();
        try {
            JSONArray jarray = new JSONArray(gpuNameList);
            for (int i = 0; i < jarray.length(); i++) {
                JSONObject jobj = jarray.getJSONObject(i);
                String gpuName = jobj.getString("name");
                entries.add(gpuName);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this.context, android.R.layout.simple_spinner_dropdown_item, entries);
            spinner.setAdapter((SpinnerAdapter) adapter);
        } catch (JSONException e) {
        }
    }

    public static String getDeviceIdFromGPUName(Context context, String gpuName) {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        String deviceId = "";
        try {
            JSONArray jsonArray = new JSONArray(gpuNameList);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jobj = jsonArray.getJSONObject(i);
                if (jobj.getString("name").contains(gpuName)) {
                    deviceId = jobj.getString("deviceID");
                }
            }
        } catch (JSONException e) {
        }
        return deviceId;
    }

    public static String getVendorIdFromGPUName(Context context, String gpuName) {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        String vendorId = "";
        try {
            JSONArray jsonArray = new JSONArray(gpuNameList);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jobj = jsonArray.getJSONObject(i);
                if (jobj.getString("name").contains(gpuName)) {
                    vendorId = jobj.getString("vendorID");
                }
            }
        } catch (JSONException e) {
        }
        return vendorId;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars vars) {
        String deviceID = getDeviceIdFromGPUName(context, config.get("gpuName"));
        String vendorID = getVendorIdFromGPUName(context, config.get("vendorID"));
        String wined3dConfig = "csmt=0x" + config.get("csmt") + ",strict_shader_math=0x" + config.get("strict_shader_math") + ",OffscreenRenderingMode=" + config.get("OffscreenRenderingMode") + ",VideoMemorySize=" + config.get("videoMemorySize") + ",VideoPciDeviceID=" + deviceID + ",VideoPciVendorID=" + vendorID + ",renderer=" + config.get("renderer");
        vars.put("WINE_D3D_CONFIG", wined3dConfig);
    }
}
