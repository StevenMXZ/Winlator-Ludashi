package com.winlator.cmod;

import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.NumberPicker;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/* loaded from: classes8.dex */
public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(com.ludashi.benchmark.R.layout.controls_editor_activity);
        this.inputControlsView = new InputControlsView(this);
        this.inputControlsView.setEditMode(true);
        this.inputControlsView.setOverlayOpacity(0.6f);
        this.profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        ((TextView) findViewById(com.ludashi.benchmark.R.id.TVProfileName)).setText(this.profile.getName());
        this.inputControlsView.setProfile(this.profile);
        FrameLayout container = (FrameLayout) findViewById(com.ludashi.benchmark.R.id.FLContainer);
        container.addView(this.inputControlsView, 0);
        container.findViewById(com.ludashi.benchmark.R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(com.ludashi.benchmark.R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(com.ludashi.benchmark.R.id.BTElementSettings).setOnClickListener(this);
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onResume() {
        super.onResume();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                ControlsEditorActivity.this.lambda$onResume$1();
            }
        }, 500L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onResume$1() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("mix_warning_shown_v4", false)) {
            ContentDialog.alert(this, com.ludashi.benchmark.R.string.warning_gamepad_mouse_mix, new Runnable() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda8
                @Override // java.lang.Runnable
                public final void run() {
                    prefs.edit().putBoolean("mix_warning_shown_v4", true).apply();
                }
            });
        }
    }

    @Override // android.view.View.OnClickListener
    public void onClick(View v) {
        switch (v.getId()) {
            case com.ludashi.benchmark.R.id.BTAddElement /* 2131296262 */:
                if (!this.inputControlsView.addElement()) {
                    AppUtils.showToast(this, com.ludashi.benchmark.R.string.no_profile_selected);
                    break;
                }
                break;
            case com.ludashi.benchmark.R.id.BTElementSettings /* 2131296283 */:
                ControlElement selectedElement = this.inputControlsView.getSelectedElement();
                if (selectedElement != null) {
                    showControlElementSettings(v);
                    break;
                } else {
                    AppUtils.showToast(this, com.ludashi.benchmark.R.string.no_control_element_selected);
                    break;
                }
            case com.ludashi.benchmark.R.id.BTRemoveElement /* 2131296321 */:
                if (!this.inputControlsView.removeElement()) {
                    AppUtils.showToast(this, com.ludashi.benchmark.R.string.no_control_element_selected);
                    break;
                }
                break;
        }
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = this.inputControlsView.getSelectedElement();
        final View view = LayoutInflater.from(this).inflate(com.ludashi.benchmark.R.layout.control_element_settings, (ViewGroup) null);
        Runnable updateLayout = new Runnable() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ControlsEditorActivity.this.lambda$showControlElementSettings$2(element, view);
            }
        };
        loadTypeSpinner(element, (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SType), updateLayout);
        loadShapeSpinner(element, (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SShape));
        loadRangeSpinner(element, (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SRange));
        RadioGroup rgOrientation = (RadioGroup) view.findViewById(com.ludashi.benchmark.R.id.RGOrientation);
        rgOrientation.check(element.getOrientation() == 1 ? com.ludashi.benchmark.R.id.RBVertical : com.ludashi.benchmark.R.id.RBHorizontal);
        rgOrientation.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda1
            @Override // android.widget.RadioGroup.OnCheckedChangeListener
            public final void onCheckedChanged(RadioGroup radioGroup, int i) {
                ControlsEditorActivity.this.lambda$showControlElementSettings$3(element, radioGroup, i);
            }
        });
        NumberPicker npColumns = (NumberPicker) view.findViewById(com.ludashi.benchmark.R.id.NPColumns);
        npColumns.setValue(element.getBindingCount());
        npColumns.setOnValueChangeListener(new NumberPicker.OnValueChangeListener() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda2
            @Override // com.winlator.cmod.widget.NumberPicker.OnValueChangeListener
            public final void onValueChange(NumberPicker numberPicker, int i) {
                ControlsEditorActivity.this.lambda$showControlElementSettings$4(element, numberPicker, i);
            }
        });
        final TextView tvScale = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVScale);
        SeekBar sbScale = (SeekBar) view.findViewById(com.ludashi.benchmark.R.id.SBScale);
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: com.winlator.cmod.ControlsEditorActivity.1
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvScale.setText(progress + "%");
                if (fromUser) {
                    int progress2 = (int) Mathf.roundTo(progress, 5.0f);
                    seekBar.setProgress(progress2);
                    element.setScale(progress2 / 100.0f);
                    ControlsEditorActivity.this.profile.save();
                    ControlsEditorActivity.this.inputControlsView.invalidate();
                }
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sbScale.setProgress((int) (element.getScale() * 100.0f));
        CheckBox cbToggleSwitch = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda3
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                ControlsEditorActivity.this.lambda$showControlElementSettings$5(element, compoundButton, z);
            }
        });
        final EditText etCustomText = (EditText) view.findViewById(com.ludashi.benchmark.R.id.ETCustomText);
        etCustomText.setText(element.getText());
        final LinearLayout llIconList = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLIconList);
        loadIcons(llIconList, element.getIconId());
        updateLayout.run();
        PopupWindow popupWindow = AppUtils.showPopupWindow(anchorView, view, 340, 0);
        popupWindow.setOnDismissListener(new PopupWindow.OnDismissListener() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda4
            @Override // android.widget.PopupWindow.OnDismissListener
            public final void onDismiss() {
                ControlsEditorActivity.this.lambda$showControlElementSettings$6(etCustomText, llIconList, element);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showControlElementSettings$2(ControlElement element, View view) {
        ControlElement.Type type = element.getType();
        view.findViewById(com.ludashi.benchmark.R.id.LLShape).setVisibility(8);
        view.findViewById(com.ludashi.benchmark.R.id.CBToggleSwitch).setVisibility(8);
        view.findViewById(com.ludashi.benchmark.R.id.LLCustomTextIcon).setVisibility(8);
        view.findViewById(com.ludashi.benchmark.R.id.LLRangeOptions).setVisibility(8);
        if (type == ControlElement.Type.BUTTON) {
            view.findViewById(com.ludashi.benchmark.R.id.LLShape).setVisibility(0);
            view.findViewById(com.ludashi.benchmark.R.id.CBToggleSwitch).setVisibility(0);
            view.findViewById(com.ludashi.benchmark.R.id.LLCustomTextIcon).setVisibility(0);
        } else if (type == ControlElement.Type.RANGE_BUTTON) {
            view.findViewById(com.ludashi.benchmark.R.id.LLRangeOptions).setVisibility(0);
        }
        loadBindingSpinners(element, view);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showControlElementSettings$3(ControlElement element, RadioGroup group, int checkedId) {
        element.setOrientation((byte) (checkedId == com.ludashi.benchmark.R.id.RBVertical ? 1 : 0));
        this.profile.save();
        this.inputControlsView.invalidate();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showControlElementSettings$4(ControlElement element, NumberPicker numberPicker, int value) {
        element.setBindingCount(value);
        this.profile.save();
        this.inputControlsView.invalidate();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showControlElementSettings$5(ControlElement element, CompoundButton buttonView, boolean isChecked) {
        element.setToggleSwitch(isChecked);
        this.profile.save();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showControlElementSettings$6(EditText etCustomText, LinearLayout llIconList, ControlElement element) {
        String text = etCustomText.getText().toString().trim();
        byte iconId = 0;
        int i = 0;
        while (true) {
            if (i >= llIconList.getChildCount()) {
                break;
            }
            View child = llIconList.getChildAt(i);
            if (!child.isSelected()) {
                i++;
            } else {
                iconId = ((Byte) child.getTag()).byteValue();
                break;
            }
        }
        element.setText(text);
        element.setIconId(iconId);
        this.profile.save();
        this.inputControlsView.invalidate();
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, final Runnable callback) {
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ControlsEditorActivity.2
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setType(ControlElement.Type.values()[position]);
                ControlsEditorActivity.this.profile.save();
                callback.run();
                ControlsEditorActivity.this.inputControlsView.invalidate();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ControlsEditorActivity.3
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setShape(ControlElement.Shape.values()[position]);
                ControlsEditorActivity.this.profile.save();
                ControlsEditorActivity.this.inputControlsView.invalidate();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLBindings);
        container.removeAllViews();
        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            loadBindingSpinner(element, container, 0, com.ludashi.benchmark.R.string.binding);
            return;
        }
        if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            loadBindingSpinner(element, container, 0, com.ludashi.benchmark.R.string.binding_up);
            loadBindingSpinner(element, container, 1, com.ludashi.benchmark.R.string.binding_right);
            loadBindingSpinner(element, container, 2, com.ludashi.benchmark.R.string.binding_down);
            loadBindingSpinner(element, container, 3, com.ludashi.benchmark.R.string.binding_left);
        }
    }

    private void loadBindingSpinner(final ControlElement element, LinearLayout container, final int index, int titleResId) {
        View view = LayoutInflater.from(this).inflate(com.ludashi.benchmark.R.layout.binding_field, (ViewGroup) container, false);
        ((TextView) view.findViewById(com.ludashi.benchmark.R.id.TVTitle)).setText(titleResId);
        final Spinner sBindingType = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBindingType);
        final Spinner sBinding = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBinding);
        final Runnable update = new Runnable() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                ControlsEditorActivity.this.lambda$loadBindingSpinner$7(sBindingType, sBinding, element, index);
            }
        };
        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ControlsEditorActivity.4
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view2, int position, long id) {
                update.run();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        Binding selectedBinding = element.getBindingAt(index);
        if (selectedBinding.isKeyboard()) {
            sBindingType.setSelection(0, false);
        } else if (selectedBinding.isMouse()) {
            sBindingType.setSelection(1, false);
        } else if (selectedBinding.isGamepad()) {
            sBindingType.setSelection(2, false);
        }
        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ControlsEditorActivity.5
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view2, int position, long id) {
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0:
                        binding = Binding.keyboardBindingValues()[position];
                        break;
                    case 1:
                        binding = Binding.mouseBindingValues()[position];
                        break;
                    case 2:
                        binding = Binding.gamepadBindingValues()[position];
                        break;
                }
                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding);
                    ControlsEditorActivity.this.profile.save();
                    ControlsEditorActivity.this.inputControlsView.invalidate();
                }
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        update.run();
        container.addView(view);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadBindingSpinner$7(Spinner sBindingType, Spinner sBinding, ControlElement element, int index) {
        String[] bindingEntries = null;
        switch (sBindingType.getSelectedItemPosition()) {
            case 0:
                bindingEntries = Binding.keyboardBindingLabels();
                break;
            case 1:
                bindingEntries = Binding.mouseBindingLabels();
                break;
            case 2:
                bindingEntries = Binding.gamepadBindingLabels();
                break;
        }
        sBinding.setAdapter((SpinnerAdapter) new ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
        AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ControlsEditorActivity.6
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                ControlsEditorActivity.this.profile.save();
                ControlsEditorActivity.this.inputControlsView.invalidate();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadIcons(final LinearLayout parent, byte selectedId) {
        String str;
        InputStream is;
        String str2 = "inputcontrols/icons/";
        boolean z = false;
        byte[] iconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            iconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
            }
        } catch (IOException e) {
        }
        Arrays.sort(iconIds);
        int size = (int) UnitUtils.dpToPx(40.0f);
        int margin = (int) UnitUtils.dpToPx(2.0f);
        int padding = (int) UnitUtils.dpToPx(4.0f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);
        int length = iconIds.length;
        int i2 = 0;
        while (i2 < length) {
            byte id = iconIds[i2];
            final ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setBackgroundResource(com.ludashi.benchmark.R.drawable.icon_background);
            imageView.setTag(Byte.valueOf(id));
            imageView.setSelected(id == selectedId ? true : z);
            imageView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ControlsEditorActivity$$ExternalSyntheticLambda5
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ControlsEditorActivity.lambda$loadIcons$8(parent, imageView, view);
                }
            });
            try {
                is = getAssets().open(str2 + ((int) id) + ".png");
            } catch (IOException e2) {
                str = str2;
            }
            try {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
                if (is != null) {
                    is.close();
                }
                str = str2;
                parent.addView(imageView);
                i2++;
                str2 = str;
                z = false;
            } catch (Throwable th) {
                if (is != null) {
                    try {
                        try {
                            is.close();
                            str = str2;
                        } catch (Throwable th2) {
                            str = str2;
                            th.addSuppressed(th2);
                        }
                    } catch (IOException e3) {
                    }
                } else {
                    str = str2;
                }
                throw th;
            }
        }
    }

    static /* synthetic */ void lambda$loadIcons$8(LinearLayout parent, ImageView imageView, View v) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            parent.getChildAt(i).setSelected(false);
        }
        imageView.setSelected(true);
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(com.ludashi.benchmark.R.anim.slide_in_down, com.ludashi.benchmark.R.anim.slide_out_up);
    }
}
