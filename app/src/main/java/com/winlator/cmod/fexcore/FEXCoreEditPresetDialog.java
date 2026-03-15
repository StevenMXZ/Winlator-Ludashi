package com.winlator.cmod.fexcore;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.ToggleButton;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ArrayUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes3.dex */
public class FEXCoreEditPresetDialog extends ContentDialog {
    private final Context context;
    private boolean isDarkMode;
    private Runnable onConfirmCallback;
    private final FEXCorePreset preset;
    private final boolean readonly;

    public FEXCoreEditPresetDialog(final Context context, String presetId) {
        super(context, R.layout.box64_edit_preset_dialog);
        this.context = context;
        this.preset = presetId != null ? FEXCorePresetManager.getPreset(context, presetId) : null;
        this.readonly = (this.preset == null || this.preset.isCustom()) ? false : true;
        setTitle(StringUtils.getString(context, "fexcore_preset"));
        setIcon(R.drawable.icon_env_var);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.isDarkMode = sharedPreferences.getBoolean("dark_mode", false);
        TextView environmentVariablesLabel = (TextView) findViewById(R.id.TVEnvironmentVariables);
        applyFieldSetLabelStyle(environmentVariablesLabel, this.isDarkMode);
        final EditText etName = (EditText) findViewById(R.id.ETName);
        etName.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        etName.setEnabled(true ^ this.readonly);
        if (this.preset != null) {
            etName.setText(this.preset.name);
        } else {
            etName.setText(context.getString(R.string.preset) + "-" + FEXCorePresetManager.getNextPresetId(context));
        }
        applyDarkThemeToEditText(etName);
        loadEnvVarsList();
        super.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.fexcore.FEXCoreEditPresetDialog$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                FEXCoreEditPresetDialog.this.lambda$new$0(etName, context);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$0(EditText etName, Context context) {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            return;
        }
        FEXCorePresetManager.editPreset(context, this.preset != null ? this.preset.id : null, name.replaceAll("[,\\|]+", ""), getEnvVars());
        if (this.onConfirmCallback != null) {
            this.onConfirmCallback.run();
        }
    }

    @Override // com.winlator.cmod.contentdialog.ContentDialog
    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    private EnvVars getEnvVars() {
        String value;
        EnvVars envVars = new EnvVars();
        LinearLayout parent = (LinearLayout) findViewById(R.id.LLContent);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            String name = ((TextView) child.findViewById(R.id.TextView)).getText().toString();
            Spinner spinner = (Spinner) child.findViewById(R.id.Spinner);
            ToggleButton toggleButton = (ToggleButton) child.findViewById(R.id.ToggleButton);
            EditText edt = (EditText) child.findViewById(R.id.EditText);
            boolean toggleSwitch = toggleButton.getVisibility() == 0;
            boolean editText = edt.getVisibility() == 0;
            if (editText) {
                value = edt.getText().toString();
            } else if (toggleSwitch) {
                value = toggleButton.isChecked() ? "1" : "0";
            } else {
                value = spinner.getSelectedItem().toString();
            }
            envVars.put(name, value);
        }
        return envVars;
    }

    private void loadEnvVarsList() {
        final FEXCoreEditPresetDialog fEXCoreEditPresetDialog = this;
        try {
            LinearLayout parent = (LinearLayout) fEXCoreEditPresetDialog.findViewById(R.id.LLContent);
            LayoutInflater inflater = LayoutInflater.from(fEXCoreEditPresetDialog.context);
            JSONArray data = new JSONArray(FileUtils.readString(fEXCoreEditPresetDialog.context, "fexcore_env_vars.json"));
            EnvVars envVars = fEXCoreEditPresetDialog.preset != null ? FEXCorePresetManager.getEnvVars(fEXCoreEditPresetDialog.context, fEXCoreEditPresetDialog.preset.id) : null;
            int i = 0;
            while (i < data.length()) {
                JSONObject item = data.getJSONObject(i);
                final String name = item.getString("name");
                View child = inflater.inflate(R.layout.box64_env_var_list_item, (ViewGroup) parent, false);
                ((TextView) child.findViewById(R.id.TextView)).setText(name);
                child.findViewById(R.id.BTHelp).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.fexcore.FEXCoreEditPresetDialog$$ExternalSyntheticLambda0
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        FEXCoreEditPresetDialog.this.lambda$loadEnvVarsList$1(name, view);
                    }
                });
                Spinner spinner = (Spinner) child.findViewById(R.id.Spinner);
                ToggleButton toggleButton = (ToggleButton) child.findViewById(R.id.ToggleButton);
                EditText editText = (EditText) child.findViewById(R.id.EditText);
                String[] values = ArrayUtils.toStringArray(item.getJSONArray("values"));
                String value = (envVars == null || !envVars.has(name)) ? item.getString("defaultValue") : envVars.get(name);
                if (item.optBoolean("toggleSwitch", false)) {
                    toggleButton.setVisibility(0);
                    toggleButton.setEnabled(!fEXCoreEditPresetDialog.readonly);
                    toggleButton.setChecked(value.equals("1"));
                    if (fEXCoreEditPresetDialog.readonly) {
                        toggleButton.setAlpha(0.5f);
                    }
                } else if (item.optBoolean("editText", false)) {
                    editText.setVisibility(0);
                    editText.setEnabled(!fEXCoreEditPresetDialog.readonly);
                    editText.setText(value);
                    if (fEXCoreEditPresetDialog.readonly) {
                        editText.setAlpha(0.5f);
                    }
                } else {
                    spinner.setPopupBackgroundResource(fEXCoreEditPresetDialog.isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
                    spinner.setVisibility(0);
                    spinner.setEnabled(fEXCoreEditPresetDialog.readonly ? false : true);
                    spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(fEXCoreEditPresetDialog.context, android.R.layout.simple_spinner_dropdown_item, values));
                    AppUtils.setSpinnerSelectionFromValue(spinner, value);
                }
                parent.addView(child);
                i++;
                fEXCoreEditPresetDialog = this;
            }
        } catch (JSONException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadEnvVarsList$1(String name, View v) {
        String suffix = name.replace("FEX_", "").toLowerCase(Locale.ENGLISH);
        String value = StringUtils.getString(this.context, "fexcore_env_var_help__" + suffix);
        if (value != null) {
            AppUtils.showHelpBox(this.context, v, value);
        }
    }

    private static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        if (isDarkMode) {
            textView.setTextColor(Color.parseColor("#cccccc"));
            textView.setBackgroundResource(R.color.content_dialog_background_dark);
        } else {
            textView.setTextColor(Color.parseColor("#bdbdbd"));
            textView.setBackgroundResource(R.color.window_background_color);
        }
    }

    private void applyDarkThemeToEditText(EditText editText) {
        if (this.isDarkMode) {
            editText.setTextColor(-1);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(R.drawable.edit_text_dark);
        } else {
            editText.setTextColor(ViewCompat.MEASURED_STATE_MASK);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(R.drawable.edit_text);
        }
    }
}
