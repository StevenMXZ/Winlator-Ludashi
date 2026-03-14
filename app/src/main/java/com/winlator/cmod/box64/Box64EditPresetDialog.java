package com.winlator.cmod.box64;

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

/* loaded from: classes6.dex */
public class Box64EditPresetDialog extends ContentDialog {
    private final Context context;
    private boolean isDarkMode;
    private Runnable onConfirmCallback;
    private final String prefix;
    private final Box64Preset preset;
    private final boolean readonly;

    public Box64EditPresetDialog(final Context context, final String prefix, String presetId) {
        super(context, R.layout.box64_edit_preset_dialog);
        this.context = context;
        this.prefix = prefix;
        this.preset = presetId != null ? Box64PresetManager.getPreset(prefix, context, presetId) : null;
        this.readonly = (this.preset == null || this.preset.isCustom()) ? false : true;
        setTitle(StringUtils.getString(context, prefix + "_preset"));
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
            etName.setText(context.getString(R.string.preset) + "-" + Box64PresetManager.getNextPresetId(context, prefix));
        }
        applyDarkThemeToEditText(etName);
        loadEnvVarsList();
        super.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.box64.Box64EditPresetDialog$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                Box64EditPresetDialog.this.lambda$new$0(etName, prefix, context);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$0(EditText etName, String prefix, Context context) {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            return;
        }
        Box64PresetManager.editPreset(prefix, context, this.preset != null ? this.preset.id : null, name.replaceAll("[,\\|]+", ""), getEnvVars());
        if (this.onConfirmCallback != null) {
            this.onConfirmCallback.run();
        }
    }

    @Override // com.winlator.cmod.contentdialog.ContentDialog
    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    private EnvVars getEnvVars() {
        EnvVars envVars = new EnvVars();
        LinearLayout parent = (LinearLayout) findViewById(R.id.LLContent);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            String name = ((TextView) child.findViewById(R.id.TextView)).getText().toString();
            Spinner spinner = (Spinner) child.findViewById(R.id.Spinner);
            ToggleButton toggleButton = (ToggleButton) child.findViewById(R.id.ToggleButton);
            boolean toggleSwitch = toggleButton.getVisibility() == 0;
            String value = toggleSwitch ? toggleButton.isChecked() ? "1" : "0" : spinner.getSelectedItem().toString();
            envVars.put(name, value);
        }
        return envVars;
    }

    private void loadEnvVarsList() {
        try {
            LinearLayout parent = (LinearLayout) findViewById(R.id.LLContent);
            LayoutInflater inflater = LayoutInflater.from(this.context);
            JSONArray data = new JSONArray(FileUtils.readString(this.context, this.prefix + "_env_vars.json"));
            EnvVars envVars = this.preset != null ? Box64PresetManager.getEnvVars(this.prefix, this.context, this.preset.id) : null;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                final String name = item.getString("name");
                View child = inflater.inflate(R.layout.box64_env_var_list_item, (ViewGroup) parent, false);
                ((TextView) child.findViewById(R.id.TextView)).setText(name);
                child.findViewById(R.id.BTHelp).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.box64.Box64EditPresetDialog$$ExternalSyntheticLambda0
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        Box64EditPresetDialog.this.lambda$loadEnvVarsList$1(name, view);
                    }
                });
                Spinner spinner = (Spinner) child.findViewById(R.id.Spinner);
                ToggleButton toggleButton = (ToggleButton) child.findViewById(R.id.ToggleButton);
                String[] values = ArrayUtils.toStringArray(item.getJSONArray("values"));
                String value = (envVars == null || !envVars.has(name)) ? item.getString("defaultValue") : envVars.get(name);
                if (item.optBoolean("toggleSwitch", false)) {
                    toggleButton.setVisibility(0);
                    toggleButton.setEnabled(this.readonly ? false : true);
                    toggleButton.setChecked(value.equals("1"));
                    if (this.readonly) {
                        toggleButton.setAlpha(0.5f);
                    }
                } else {
                    spinner.setPopupBackgroundResource(this.isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
                    spinner.setVisibility(0);
                    spinner.setEnabled(this.readonly ? false : true);
                    spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(this.context, android.R.layout.simple_spinner_dropdown_item, values));
                    AppUtils.setSpinnerSelectionFromValue(spinner, value);
                }
                parent.addView(child);
            }
        } catch (JSONException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadEnvVarsList$1(String name, View v) {
        String suffix = name.replace(this.prefix.toUpperCase(Locale.ENGLISH) + "_", "").toLowerCase(Locale.ENGLISH);
        String value = StringUtils.getString(this.context, "box64_env_var_help__" + suffix);
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
