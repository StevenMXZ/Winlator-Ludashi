package com.winlator.cmod.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.ToggleButton;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.UnitUtils;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import kotlinx.coroutines.DebugKt;

/* loaded from: classes14.dex */
public class EnvVarsView extends FrameLayout {
    public static final String[][] knownEnvVars = {new String[]{"ZINK_DESCRIPTORS", "SELECT", DebugKt.DEBUG_PROPERTY_VALUE_AUTO, "lazy", "cached", "notemplates"}, new String[]{"ZINK_DEBUG", "SELECT_MULTIPLE", "nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder"}, new String[]{"MESA_SHADER_CACHE_DISABLE", "CHECKBOX", "false", "true"}, new String[]{"mesa_glthread", "CHECKBOX", "false", "true"}, new String[]{"WINEESYNC", "CHECKBOX", "0", "1"}, new String[]{"VKD3D_SHADER_MODEL", "TEXT"}, new String[]{"WRAPPER_BLIT", "TEXT"}, new String[]{"FD_DEV_FEATURES", "TEXT"}, new String[]{"TU_DEBUG", "SELECT_MULTIPLE", "forcecb", "nocb", "startup", "deck_emu", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "nofdm", "noconform", "rd"}, new String[]{"IR3_SHADER_DEBUG", "SELECT_MULTIPLE", "nouboopt", "nopreamble", "noearlypreamble", "nofp16", "nocache", "spillall", "fullsync", "fullnop", "nodescprefetch", "expandrpt", "noaliastex", "noaliasrt"}, new String[]{"DXVK_HUD", "SELECT_MULTIPLE", "scale=0.5", "scale=0.7", "opacity=0.5", "opacity=0.7", "devinfo", "fps", "frametimes", "submissions", "drawcalls", "pipelines", "descriptors", "memory", "gpuload", "version", "api", "cs", "compiler", "samplers"}, new String[]{"MESA_EXTENSION_MAX_YEAR", "TEXT"}, new String[]{"WRAPPER_MAX_IMAGE_COUNT", "TEXT"}, new String[]{"MESA_GL_VERSION_OVERRIDE", "TEXT"}, new String[]{"PULSE_LATENCY_MSEC", "NUMBER"}, new String[]{"WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "CHECKBOX", "0", "1"}, new String[]{"GALLIUM_HUD", "SELECT_MULTIPLE", "simple", "fps", "frametime"}, new String[]{"WINE_NEW_MEDIASOURCE", "CHECKBOX", "0", "1"}};
    private final LinearLayout container;
    private final TextView emptyTextView;
    private final LayoutInflater inflater;
    private boolean isDarkMode;

    /* JADX INFO: Access modifiers changed from: private */
    interface GetValueCallback {
        String call();
    }

    public EnvVarsView(Context context) {
        this(context, (AttributeSet) null);
    }

    public EnvVarsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EnvVarsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public EnvVarsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.inflater = LayoutInflater.from(context);
        this.container = new LinearLayout(context);
        this.container.setOrientation(1);
        this.container.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        addView(this.container);
        this.emptyTextView = new TextView(context);
        this.emptyTextView.setText(R.string.no_items_to_display);
        this.emptyTextView.setTextSize(1, 16.0f);
        this.emptyTextView.setGravity(17);
        int padding = (int) UnitUtils.dpToPx(16.0f);
        this.emptyTextView.setPadding(padding, padding, padding, padding);
        addView(this.emptyTextView);
    }

    public EnvVarsView(Context context, boolean isDarkMode) {
        this(context, (AttributeSet) null, isDarkMode);
    }

    public EnvVarsView(Context context, AttributeSet attrs, boolean isDarkMode) {
        this(context, attrs, 0, isDarkMode);
    }

    public EnvVarsView(Context context, AttributeSet attrs, int defStyleAttr, boolean isDarkMode) {
        this(context, attrs, defStyleAttr, 0, isDarkMode);
    }

    public EnvVarsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes, boolean isDarkMode) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.isDarkMode = isDarkMode;
        this.inflater = LayoutInflater.from(context);
        this.container = new LinearLayout(context);
        this.container.setOrientation(1);
        this.container.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        addView(this.container);
        this.emptyTextView = new TextView(context);
        this.emptyTextView.setText(R.string.no_items_to_display);
        this.emptyTextView.setTextSize(1, 16.0f);
        this.emptyTextView.setGravity(17);
        int padding = (int) UnitUtils.dpToPx(16.0f);
        this.emptyTextView.setPadding(padding, padding, padding, padding);
        addView(this.emptyTextView);
        applyDarkTheme(this.emptyTextView);
    }

    private String[] findKnownEnvVar(String name) {
        for (String[] values : knownEnvVars) {
            if (values[0].equals(name)) {
                return values;
            }
        }
        return null;
    }

    private void applyDarkTheme(View view) {
        if (this.isDarkMode) {
            if (view instanceof TextView) {
                ((TextView) view).setTextColor(-1);
                return;
            }
            if (view instanceof EditText) {
                view.setBackgroundResource(R.drawable.edit_text_dark);
                ((EditText) view).setTextColor(-1);
                ((EditText) view).setHintTextColor(-7829368);
            } else if (view instanceof Spinner) {
                ((Spinner) view).setPopupBackgroundResource(R.drawable.content_dialog_background_dark);
            } else {
                boolean z = view instanceof ToggleButton;
            }
        }
    }

    public String getEnvVars() {
        EnvVars envVars = new EnvVars();
        for (int i = 0; i < this.container.getChildCount(); i++) {
            View child = this.container.getChildAt(i);
            GetValueCallback getValueCallback = (GetValueCallback) child.getTag();
            String name = ((TextView) child.findViewById(R.id.TextView)).getText().toString();
            String value = getValueCallback.call().trim().replace(" ", "");
            if (!value.isEmpty()) {
                envVars.put(name, value);
            }
        }
        return envVars.toString();
    }

    public boolean containsName(String name) {
        for (int i = 0; i < this.container.getChildCount(); i++) {
            View child = this.container.getChildAt(i);
            String text = ((TextView) child.findViewById(R.id.TextView)).getText().toString();
            if (name.equals(text)) {
                return true;
            }
        }
        return false;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue */
    public void add(String name, String value) {
        char c;
        GetValueCallback getValueCallback;
        Context context = getContext();
        final View itemView = this.inflater.inflate(R.layout.env_vars_list_item, (ViewGroup) this.container, false);
        ((TextView) itemView.findViewById(R.id.TextView)).setText(name);
        final String[] knownEnvVar = findKnownEnvVar(name);
        String type = knownEnvVar != null ? knownEnvVar[1] : "TEXT";
        switch (type.hashCode()) {
            case -1981034679:
                if (type.equals("NUMBER")) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case -1975448637:
                if (type.equals("CHECKBOX")) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case -1852692228:
                if (type.equals("SELECT")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            case 2571565:
                if (type.equals("TEXT")) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case 1604975091:
                if (type.equals("SELECT_MULTIPLE")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        int i = R.drawable.edit_text_dark;
        switch (c) {
            case 0:
                final ToggleButton toggleButton = (ToggleButton) itemView.findViewById(R.id.ToggleButton);
                toggleButton.setVisibility(0);
                toggleButton.setChecked(value.equals("1") || value.equals("true"));
                applyDarkTheme(toggleButton);
                getValueCallback = new GetValueCallback() { // from class: com.winlator.cmod.widget.EnvVarsView$$ExternalSyntheticLambda0
                    @Override // com.winlator.cmod.widget.EnvVarsView.GetValueCallback
                    public final String call() {
                        return EnvVarsView.lambda$add$0(toggleButton, knownEnvVar);
                    }
                };
                break;
            case 1:
                String[] items = (String[]) Arrays.copyOfRange(knownEnvVar, 2, knownEnvVar.length);
                final Spinner spinner = (Spinner) itemView.findViewById(R.id.Spinner);
                spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items));
                AppUtils.setSpinnerSelectionFromValue(spinner, value);
                spinner.setVisibility(0);
                applyDarkTheme(spinner);
                getValueCallback = new GetValueCallback() { // from class: com.winlator.cmod.widget.EnvVarsView$$ExternalSyntheticLambda1
                    @Override // com.winlator.cmod.widget.EnvVarsView.GetValueCallback
                    public final String call() {
                        String obj;
                        obj = spinner.getSelectedItem().toString();
                        return obj;
                    }
                };
                break;
            case 2:
                final MultiSelectionComboBox comboBox = (MultiSelectionComboBox) itemView.findViewById(R.id.MultiSelectionComboBox);
                comboBox.setItems((String[]) Arrays.copyOfRange(knownEnvVar, 2, knownEnvVar.length));
                comboBox.setSelectedItems(value.split(","));
                comboBox.setVisibility(0);
                Objects.requireNonNull(comboBox);
                getValueCallback = new GetValueCallback() { // from class: com.winlator.cmod.widget.EnvVarsView$$ExternalSyntheticLambda2
                    @Override // com.winlator.cmod.widget.EnvVarsView.GetValueCallback
                    public final String call() {
                        return MultiSelectionComboBox.this.getSelectedItemsAsString();
                    }
                };
                break;
            case 3:
                final EditText editText = (EditText) itemView.findViewById(R.id.EditText);
                editText.setVisibility(0);
                editText.setText(value);
                if (!this.isDarkMode) {
                    i = R.drawable.edit_text;
                }
                editText.setBackgroundResource(i);
                getValueCallback = new GetValueCallback() { // from class: com.winlator.cmod.widget.EnvVarsView$$ExternalSyntheticLambda3
                    @Override // com.winlator.cmod.widget.EnvVarsView.GetValueCallback
                    public final String call() {
                        String obj;
                        obj = editText.getText().toString();
                        return obj;
                    }
                };
                break;
            default:
                final EditText editTextNumber = (EditText) itemView.findViewById(R.id.EditText);
                editTextNumber.setVisibility(0);
                editTextNumber.setText(value);
                if (type.equals("NUMBER")) {
                    editTextNumber.setInputType(2);
                }
                if (!this.isDarkMode) {
                    i = R.drawable.edit_text;
                }
                editTextNumber.setBackgroundResource(i);
                getValueCallback = new GetValueCallback() { // from class: com.winlator.cmod.widget.EnvVarsView$$ExternalSyntheticLambda4
                    @Override // com.winlator.cmod.widget.EnvVarsView.GetValueCallback
                    public final String call() {
                        String obj;
                        obj = editTextNumber.getText().toString();
                        return obj;
                    }
                };
                break;
        }
        itemView.setTag(getValueCallback);
        itemView.findViewById(R.id.BTRemove).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.widget.EnvVarsView$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                EnvVarsView.this.lambda$add$4(itemView, view);
            }
        });
        this.container.addView(itemView);
        this.emptyTextView.setVisibility(8);
    }

    static /* synthetic */ String lambda$add$0(ToggleButton toggleButton, String[] knownEnvVar) {
        return toggleButton.isChecked() ? knownEnvVar[3] : knownEnvVar[2];
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$add$4(View itemView, View v) {
        this.container.removeView(itemView);
        if (this.container.getChildCount() == 0) {
            this.emptyTextView.setVisibility(0);
        }
    }

    public void setEnvVars(EnvVars envVars) {
        this.container.removeAllViews();
        Iterator<String> it = envVars.iterator();
        while (it.hasNext()) {
            String name = it.next();
            add(name, envVars.get(name));
        }
    }

    public void setDarkMode(boolean isDarkMode) {
        this.isDarkMode = isDarkMode;
        applyDarkTheme(this.emptyTextView);
        for (int i = 0; i < this.container.getChildCount(); i++) {
            applyDarkTheme(this.container.getChildAt(i));
        }
    }
}
