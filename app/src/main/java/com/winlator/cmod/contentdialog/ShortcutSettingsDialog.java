package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;
import com.google.android.material.tabs.TabLayout;
import com.ludashi.benchmark.R;
import com.winlator.cmod.ContainerDetailFragment;
import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.EnvVarsView;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/* loaded from: classes4.dex */
public class ShortcutSettingsDialog extends ContentDialog {
    private String box64Version;
    private final ShortcutsFragment fragment;
    private InputControlsManager inputControlsManager;
    private final Shortcut shortcut;
    private TextView tvGraphicsDriverVersion;

    public ShortcutSettingsDialog(ShortcutsFragment fragment, Shortcut shortcut) {
        super(fragment.getContext(), R.layout.shortcut_settings_dialog);
        this.fragment = fragment;
        this.shortcut = shortcut;
        setTitle(shortcut.name);
        setIcon(R.drawable.icon_settings);
        shortcut.container.getManager();
        createContentView();
    }

    private void createContentView() {
        final Context context = this.fragment.getContext();
        this.inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = (LinearLayout) findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        applyDynamicStyles(findViewById(R.id.LLContent), isDarkMode);
        this.tvGraphicsDriverVersion = (TextView) findViewById(R.id.TVGraphicsDriverVersion);
        final EditText etName = (EditText) findViewById(R.id.ETName);
        etName.setText(this.shortcut.name);
        final EditText etExecArgs = (EditText) findViewById(R.id.ETExecArgs);
        etExecArgs.setText(this.shortcut.getExtra("execArgs"));
        final ContainerDetailFragment containerDetailFragment = new ContainerDetailFragment(this.shortcut.container.id);
        loadScreenSizeSpinner(getContentView(), this.shortcut.getExtra("screenSize", this.shortcut.container.getScreenSize()), isDarkMode);
        final Spinner sGraphicsDriver = (Spinner) findViewById(R.id.SGraphicsDriver);
        final Spinner sDXWrapper = (Spinner) findViewById(R.id.SDXWrapper);
        Spinner sBox64Version = (Spinner) findViewById(R.id.SBox64Version);
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        final View vGraphicsDriverConfig = findViewById(R.id.BTGraphicsDriverConfig);
        vGraphicsDriverConfig.setTag(this.shortcut.getExtra("graphicsDriverConfig", this.shortcut.container.getGraphicsDriverConfig()));
        final View vDXWrapperConfig = findViewById(R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(this.shortcut.getExtra("dxwrapperConfig", this.shortcut.container.getDXWrapperConfig()));
        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig, this.shortcut.getExtra("graphicsDriver", this.shortcut.container.getGraphicsDriver()), this.shortcut.getExtra("dxwrapper", this.shortcut.container.getDXWrapper()));
        findViewById(R.id.BTHelpDXWrapper).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda9
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                AppUtils.showHelpBox(context, view, R.string.dxwrapper_help_content);
            }
        });
        int cbNativeId = context.getResources().getIdentifier("CBNativeRendering", "id", context.getPackageName());
        CheckBox cbNativeRendering = cbNativeId != 0 ? (CheckBox) findViewById(cbNativeId) : null;
        if (cbNativeRendering != null) {
            boolean isNative = this.shortcut.getExtra("nativeRendering", "0").equals("1");
            cbNativeRendering.setChecked(isNative);
        }
        final Spinner sAudioDriver = (Spinner) findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, this.shortcut.getExtra("audioDriver", this.shortcut.container.getAudioDriver()));
        final Spinner sEmulator = (Spinner) findViewById(R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator, this.shortcut.getExtra("emulator", this.shortcut.container.getEmulator()));
        Spinner sEmulator64 = (Spinner) findViewById(R.id.SEmulator64);
        sEmulator64.setEnabled(false);
        final Spinner sMIDISoundFont = (Spinner) findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        final CheckBox cbNativeRendering2 = cbNativeRendering;
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, this.shortcut.getExtra("midiSoundFont", this.shortcut.container.getMIDISoundFont()));
        final EditText etLC_ALL = (EditText) findViewById(R.id.ETlcall);
        etLC_ALL.setText(this.shortcut.getExtra("lc_all", this.shortcut.container.getLC_ALL()));
        View btShowLCALL = findViewById(R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda11
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ShortcutSettingsDialog.lambda$createContentView$2(context, etLC_ALL, view);
            }
        });
        FrameLayout fexcoreFL = (FrameLayout) findViewById(R.id.fexcoreFrame);
        String wineVersion = this.shortcut.container.getWineVersion();
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.isArm64EC()) {
            fexcoreFL.setVisibility(0);
            sEmulator.setEnabled(true);
            sEmulator64.setSelection(0);
        } else {
            fexcoreFL.setVisibility(8);
            sEmulator.setEnabled(false);
            sEmulator.setSelection(1);
            sEmulator64.setSelection(1);
        }
        ContainerDetailFragment.setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig, wineInfo.isArm64EC());
        loadBox64VersionSpinner(context, contentsManager, sBox64Version, wineInfo.isArm64EC());
        String currentBox64Version = this.shortcut.getExtra("box64Version", this.shortcut.container.getBox64Version());
        if (currentBox64Version != null) {
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, currentBox64Version);
        } else {
            wineInfo.isArm64EC();
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, "0.4.1");
        }
        sBox64Version.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog.1
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedVersion = parent.getItemAtPosition(position).toString();
                ShortcutSettingsDialog.this.box64Version = selectedVersion;
                ShortcutSettingsDialog.this.shortcut.putExtra("box64Version", selectedVersion);
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        final CheckBox cbFullscreenStretched = (CheckBox) findViewById(R.id.CBFullscreenStretched);
        boolean fullscreenStretched = this.shortcut.getExtra("fullscreenStretched", "0").equals("1");
        cbFullscreenStretched.setChecked(fullscreenStretched);
        new Runnable() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda12
            @Override // java.lang.Runnable
            public final void run() {
                ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, (Runnable) null);
            }
        };
        final CheckBox cbEnableXInput = (CheckBox) findViewById(R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = (CheckBox) findViewById(R.id.CBEnableDInput);
        final CheckBox cbExclusiveXInput = (CheckBox) findViewById(R.id.CBExclusiveXInput);
        View btHelpXInput = findViewById(R.id.BTXInputHelp);
        View btHelpDInput = findViewById(R.id.BTDInputHelp);
        View btHelpExclusiveXInput = findViewById(R.id.BTExclusiveXInputHelp);
        int inputType = Integer.parseInt(this.shortcut.getExtra("inputType", String.valueOf(this.shortcut.container.getInputType())));
        cbEnableXInput.setChecked((inputType & 4) == 4);
        cbEnableDInput.setChecked((inputType & 8) == 8);
        String exclusiveXInputExtra = this.shortcut.getExtra("exclusiveXInput");
        boolean exclusiveXInput = exclusiveXInputExtra.isEmpty() ? this.shortcut.container.isExclusiveXInput() : exclusiveXInputExtra.equals("1");
        cbExclusiveXInput.setChecked(exclusiveXInput);
        cbEnableDInput.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda13
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                ShortcutSettingsDialog.lambda$createContentView$4(cbExclusiveXInput, cbEnableXInput, compoundButton, z);
            }
        });
        cbEnableXInput.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda14
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                ShortcutSettingsDialog.lambda$createContentView$5(cbExclusiveXInput, cbEnableDInput, compoundButton, z);
            }
        });
        cbExclusiveXInput.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda15
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                ShortcutSettingsDialog.lambda$createContentView$6(cbEnableXInput, cbEnableDInput, compoundButton, z);
            }
        });
        if (!cbExclusiveXInput.isChecked()) {
            cbEnableXInput.setChecked(true);
            cbEnableDInput.setChecked(true);
            cbEnableXInput.setEnabled(false);
            cbEnableDInput.setEnabled(false);
        }
        btHelpXInput.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                AppUtils.showHelpBox(context, view, R.string.help_xinput);
            }
        });
        btHelpDInput.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                AppUtils.showHelpBox(context, view, R.string.help_dinput);
            }
        });
        btHelpExclusiveXInput.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                AppUtils.showHelpBox(context, view, R.string.help_exclusive_xinput);
            }
        });
        final Spinner sBox64Preset = (Spinner) findViewById(R.id.SBox64Preset);
        Box64PresetManager.loadSpinner("box64", sBox64Preset, this.shortcut.getExtra("box64Preset", this.shortcut.container.getBox64Preset()));
        final Spinner sFEXCoreVersion = (Spinner) findViewById(R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion, this.shortcut.getExtra("fexcoreVersion", this.shortcut.container.getFEXCoreVersion()));
        final Spinner sFEXCorePreset = (Spinner) findViewById(R.id.SFEXCorePreset);
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, this.shortcut.getExtra("fexcorePreset", this.shortcut.container.getFEXCorePreset()));
        final Spinner sControlsProfile = (Spinner) findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, this.shortcut.getExtra("controlsProfile", "0"));
        final CheckBox cbDisabledXInput = (CheckBox) findViewById(R.id.CBDisabledXInput);
        boolean isXInputDisabled = this.shortcut.getExtra("disableXinput", "0").equals("1");
        cbDisabledXInput.setChecked(isXInputDisabled);
        final CheckBox cbSimTouchScreen = (CheckBox) findViewById(R.id.CBTouchscreenMode);
        String isTouchScreenMode = this.shortcut.getExtra("simTouchScreen");
        cbSimTouchScreen.setChecked(isTouchScreenMode.equals("1"));
        ContainerDetailFragment.createWinComponentsTabFromShortcut(this, getContentView(), this.shortcut.getExtra("wincomponents", this.shortcut.container.getWinComponents()), isDarkMode);
        final EnvVarsView envVarsView = createEnvVarsTab();
        AppUtils.setupTabLayout(getContentView(), R.id.TabLayout, R.id.LLTabWinComponents, R.id.LLTabEnvVars, R.id.LLTabAdvanced);
        TabLayout tabLayout = (TabLayout) findViewById(R.id.TabLayout);
        if (isDarkMode) {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background_dark);
        } else {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background);
        }
        findViewById(R.id.BTExtraArgsMenu).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ShortcutSettingsDialog.lambda$createContentView$11(context, etExecArgs, view);
            }
        });
        String selectedDriver = sGraphicsDriver.getSelectedItem().toString();
        List<String> sGraphicsItemsList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.graphics_driver_entries)));
        sGraphicsDriver.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sGraphicsItemsList));
        AppUtils.setSpinnerSelectionFromValue(sGraphicsDriver, selectedDriver);
        final Spinner sStartupSelection = (Spinner) findViewById(R.id.SStartupSelection);
        sStartupSelection.setSelection(Integer.parseInt(this.shortcut.getExtra("startupSelection", String.valueOf((int) this.shortcut.container.getStartupSelection()))));
        final Spinner sSharpnessEffect = (Spinner) findViewById(R.id.SSharpnessEffect);
        final SeekBar sbSharpnessLevel = (SeekBar) findViewById(R.id.SBSharpnessLevel);
        final SeekBar sbSharpnessDenoise = (SeekBar) findViewById(R.id.SBSharpnessDenoise);
        final TextView tvSharpnessLevel = (TextView) findViewById(R.id.TVSharpnessLevel);
        final TextView tvSharpnessDenoise = (TextView) findViewById(R.id.TVSharpnessDenoise);
        AppUtils.setSpinnerSelectionFromValue(sSharpnessEffect, this.shortcut.getExtra("sharpnessEffect", DefaultVersion.VKD3D));
        sbSharpnessLevel.setProgress(Integer.parseInt(this.shortcut.getExtra("sharpnessLevel", "100")));
        tvSharpnessLevel.setText(this.shortcut.getExtra("sharpnessLevel", "100") + "%");
        sbSharpnessLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog.2
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessLevel.setText(progress + "%");
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sbSharpnessDenoise.setProgress(Integer.parseInt(this.shortcut.getExtra("sharpnessDenoise", "100")));
        tvSharpnessDenoise.setText(this.shortcut.getExtra("sharpnessDenoise", "100") + "%");
        sbSharpnessDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog.3
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessDenoise.setText(progress + "%");
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        final CPUListView cpuListView = (CPUListView) findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(this.shortcut.getExtra("cpuList", this.shortcut.container.getCPUList(true)));
        setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                ShortcutSettingsDialog.this.lambda$createContentView$12(etName, sGraphicsDriver, vGraphicsDriverConfig, sDXWrapper, vDXWrapperConfig, sAudioDriver, sEmulator, etLC_ALL, sMIDISoundFont, containerDetailFragment, cbEnableXInput, cbEnableDInput, cbExclusiveXInput, cbDisabledXInput, cbSimTouchScreen, etExecArgs, cbFullscreenStretched, cbNativeRendering2, envVarsView, sFEXCoreVersion, sFEXCorePreset, sBox64Preset, sStartupSelection, sSharpnessEffect, sbSharpnessLevel, sbSharpnessDenoise, sControlsProfile, cpuListView);
            }
        });
    }

    static /* synthetic */ void lambda$createContentView$2(Context context, final EditText etLC_ALL, View v) {
        PopupMenu popupMenu = new PopupMenu(context, v);
        String[] lcs = context.getResources().getStringArray(R.array.some_lc_all);
        for (int i = 0; i < lcs.length; i++) {
            popupMenu.getMenu().add(0, i, 0, lcs[i]);
        }
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda6
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                return ShortcutSettingsDialog.lambda$createContentView$1(etLC_ALL, menuItem);
            }
        });
        popupMenu.show();
    }

    static /* synthetic */ boolean lambda$createContentView$1(EditText etLC_ALL, MenuItem item) {
        etLC_ALL.setText(item.toString() + ".UTF-8");
        return true;
    }

    static /* synthetic */ void lambda$createContentView$4(CheckBox cbExclusiveXInput, CheckBox cbEnableXInput, CompoundButton buttonView, boolean isChecked) {
        if (cbExclusiveXInput.isChecked() && isChecked && cbEnableXInput.isChecked()) {
            cbEnableXInput.setChecked(false);
        }
    }

    static /* synthetic */ void lambda$createContentView$5(CheckBox cbExclusiveXInput, CheckBox cbEnableDInput, CompoundButton buttonView, boolean isChecked) {
        if (cbExclusiveXInput.isChecked() && isChecked && cbEnableDInput.isChecked()) {
            cbEnableDInput.setChecked(false);
        }
    }

    static /* synthetic */ void lambda$createContentView$6(CheckBox cbEnableXInput, CheckBox cbEnableDInput, CompoundButton buttonView, boolean isChecked) {
        if (!isChecked) {
            cbEnableXInput.setChecked(true);
            cbEnableDInput.setChecked(true);
            cbEnableXInput.setEnabled(false);
            cbEnableDInput.setEnabled(false);
            return;
        }
        cbEnableXInput.setEnabled(true);
        cbEnableDInput.setEnabled(true);
        if (cbEnableXInput.isChecked() && cbEnableDInput.isChecked()) {
            cbEnableDInput.setChecked(false);
        }
    }

    static /* synthetic */ void lambda$createContentView$11(Context context, final EditText etExecArgs, View v) {
        PopupMenu popupMenu = new PopupMenu(context, v);
        popupMenu.inflate(R.menu.extra_args_popup_menu);
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda5
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                return ShortcutSettingsDialog.lambda$createContentView$10(etExecArgs, menuItem);
            }
        });
        popupMenu.show();
    }

    static /* synthetic */ boolean lambda$createContentView$10(EditText etExecArgs, MenuItem menuItem) {
        String value = String.valueOf(menuItem.getTitle());
        String execArgs = etExecArgs.getText().toString();
        if (!execArgs.contains(value)) {
            etExecArgs.setText(!execArgs.isEmpty() ? execArgs + " " + value : value);
            return true;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$createContentView$12(EditText etName, Spinner sGraphicsDriver, View vGraphicsDriverConfig, Spinner sDXWrapper, View vDXWrapperConfig, Spinner sAudioDriver, Spinner sEmulator, EditText etLC_ALL, Spinner sMIDISoundFont, ContainerDetailFragment containerDetailFragment, CheckBox cbEnableXInput, CheckBox cbEnableDInput, CheckBox cbExclusiveXInput, CheckBox cbDisabledXInput, CheckBox cbSimTouchScreen, EditText etExecArgs, CheckBox cbFullscreenStretched, CheckBox cbNativeRendering, EnvVarsView envVarsView, Spinner sFEXCoreVersion, Spinner sFEXCorePreset, Spinner sBox64Preset, Spinner sStartupSelection, Spinner sSharpnessEffect, SeekBar sbSharpnessLevel, SeekBar sbSharpnessDenoise, Spinner sControlsProfile, CPUListView cpuListView) {
        String name = etName.getText().toString().trim();
        boolean nameChanged = (this.shortcut.name.equals(name) || name.isEmpty()) ? false : true;
        if (nameChanged) {
            renameShortcut(name);
        }
        boolean renamingSuccess = !nameChanged || new File(this.shortcut.file.getParent(), new StringBuilder().append(name).append(".desktop").toString()).exists();
        if (renamingSuccess) {
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
            String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
            String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
            String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
            String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
            String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
            String lc_all = etLC_ALL.getText().toString();
            String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
            String screenSize = ContainerDetailFragment.getScreenSize(getContentView());
            int finalInputType = 0 | (cbEnableXInput.isChecked() ? 4 : 0);
            this.shortcut.putExtra("inputType", String.valueOf(finalInputType | (cbEnableDInput.isChecked() ? 8 : 0)));
            this.shortcut.putExtra("exclusiveXInput", cbExclusiveXInput.isChecked() ? "1" : "0");
            boolean disabledXInput = cbDisabledXInput.isChecked();
            this.shortcut.putExtra("disableXinput", disabledXInput ? "1" : null);
            boolean touchscreenMode = cbSimTouchScreen.isChecked();
            this.shortcut.putExtra("simTouchScreen", touchscreenMode ? "1" : "0");
            String execArgs = etExecArgs.getText().toString();
            this.shortcut.putExtra("execArgs", !execArgs.isEmpty() ? execArgs : null);
            this.shortcut.putExtra("screenSize", screenSize);
            this.shortcut.putExtra("graphicsDriver", graphicsDriver);
            this.shortcut.putExtra("graphicsDriverConfig", graphicsDriverConfig);
            this.shortcut.putExtra("dxwrapper", dxwrapper);
            this.shortcut.putExtra("dxwrapperConfig", dxwrapperConfig);
            this.shortcut.putExtra("audioDriver", audioDriver);
            this.shortcut.putExtra("emulator", emulator);
            this.shortcut.putExtra("midiSoundFont", midiSoundFont);
            this.shortcut.putExtra("lc_all", lc_all);
            this.shortcut.putExtra("fullscreenStretched", cbFullscreenStretched.isChecked() ? "1" : null);
            if (cbNativeRendering != null) {
                this.shortcut.putExtra("nativeRendering", cbNativeRendering.isChecked() ? "1" : "0");
            }
            String wincomponents = ContainerDetailFragment.getWinComponents(getContentView());
            this.shortcut.putExtra("wincomponents", wincomponents);
            String envVars = envVarsView.getEnvVars();
            this.shortcut.putExtra("envVars", !envVars.isEmpty() ? envVars : null);
            String fexcoreVersion = sFEXCoreVersion.getSelectedItem().toString();
            this.shortcut.putExtra("fexcoreVersion", fexcoreVersion);
            String fexcorePreset = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
            this.shortcut.putExtra("fexcorePreset", fexcorePreset);
            String box64Preset = Box64PresetManager.getSpinnerSelectedId(sBox64Preset);
            this.shortcut.putExtra("box64Preset", box64Preset);
            byte startupSelection = (byte) sStartupSelection.getSelectedItemPosition();
            Shortcut shortcut = this.shortcut;
            String fexcorePreset2 = String.valueOf((int) startupSelection);
            shortcut.putExtra("startupSelection", fexcorePreset2);
            String sharpeningEffect = sSharpnessEffect.getSelectedItem().toString();
            String sharpeningLevel = String.valueOf(sbSharpnessLevel.getProgress());
            String sharpeningDenoise = String.valueOf(sbSharpnessDenoise.getProgress());
            this.shortcut.putExtra("sharpnessEffect", sharpeningEffect);
            this.shortcut.putExtra("sharpnessLevel", sharpeningLevel);
            this.shortcut.putExtra("sharpnessDenoise", sharpeningDenoise);
            ArrayList<ControlsProfile> profiles = this.inputControlsManager.getProfiles(true);
            int controlsProfile = sControlsProfile.getSelectedItemPosition() > 0 ? profiles.get(sControlsProfile.getSelectedItemPosition() - 1).id : 0;
            this.shortcut.putExtra("controlsProfile", controlsProfile > 0 ? String.valueOf(controlsProfile) : null);
            String cpuList = cpuListView.getCheckedCPUListAsString();
            this.shortcut.putExtra("cpuList", cpuList);
            this.shortcut.saveData();
        }
    }

    private void applyFieldSetLabelStylesDynamically(ViewGroup rootView, boolean isDarkMode) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View child = rootView.getChildAt(i);
            if (child instanceof ViewGroup) {
                applyFieldSetLabelStylesDynamically((ViewGroup) child, isDarkMode);
            } else if (child instanceof TextView) {
                TextView textView = (TextView) child;
                if (isFieldSetLabel(textView.getText().toString())) {
                    applyFieldSetLabelStyle(textView, isDarkMode);
                }
            }
        }
    }

    private boolean isFieldSetLabel(String text) {
        return text.equalsIgnoreCase("DirectX") || text.equalsIgnoreCase("General") || text.equalsIgnoreCase("Box64") || text.equalsIgnoreCase("Input Controls") || text.equalsIgnoreCase("Game Controller") || text.equalsIgnoreCase(DefaultVersion.WRAPPER);
    }

    public void onWinComponentsViewsAdded(boolean isDarkMode) {
        ViewGroup llContent = (ViewGroup) findViewById(R.id.LLContent);
        applyFieldSetLabelStylesDynamically(llContent, isDarkMode);
    }

    public static void loadScreenSizeSpinner(View view, String selectedValue, boolean isDarkMode) {
        final Spinner sScreenSize = (Spinner) view.findViewById(R.id.SScreenSize);
        final LinearLayout llCustomScreenSize = (LinearLayout) view.findViewById(R.id.LLCustomScreenSize);
        applyDarkThemeToEditText((EditText) view.findViewById(R.id.ETScreenWidth), isDarkMode);
        applyDarkThemeToEditText((EditText) view.findViewById(R.id.ETScreenHeight), isDarkMode);
        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog.4
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view2, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? 0 : 8);
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String[] screenSize = selectedValue.split("x");
            ((EditText) view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText) view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    private void applyDynamicStyles(View view, boolean isDarkMode) {
        EditText etName = (EditText) view.findViewById(R.id.ETName);
        applyDarkThemeToEditText(etName, isDarkMode);
        Spinner sGraphicsDriver = (Spinner) view.findViewById(R.id.SGraphicsDriver);
        Spinner sDXWrapper = (Spinner) view.findViewById(R.id.SDXWrapper);
        Spinner sAudioDriver = (Spinner) view.findViewById(R.id.SAudioDriver);
        Spinner sEmulatorSpinner = (Spinner) view.findViewById(R.id.SEmulator);
        Spinner sBox64Preset = (Spinner) view.findViewById(R.id.SBox64Preset);
        Spinner sControlsProfile = (Spinner) view.findViewById(R.id.SControlsProfile);
        Spinner sMIDISoundFont = (Spinner) view.findViewById(R.id.SMIDISoundFont);
        Spinner sBox64Version = (Spinner) view.findViewById(R.id.SBox64Version);
        Spinner sFEXCoreVersion = (Spinner) view.findViewById(R.id.SFEXCoreVersion);
        Spinner sFEXCorePreset = (Spinner) view.findViewById(R.id.SFEXCorePreset);
        Spinner sStartupSelection = (Spinner) findViewById(R.id.SStartupSelection);
        sGraphicsDriver.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sDXWrapper.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sAudioDriver.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sEmulatorSpinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sBox64Preset.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sControlsProfile.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sMIDISoundFont.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sBox64Version.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sFEXCorePreset.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sFEXCoreVersion.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sStartupSelection.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        EditText etExecArgs = (EditText) view.findViewById(R.id.ETExecArgs);
        applyDarkThemeToEditText(etExecArgs, isDarkMode);
    }

    private void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        if (isDarkMode) {
            textView.setTextColor(Color.parseColor("#cccccc"));
            textView.setBackgroundColor(Color.parseColor("#424242"));
        } else {
            textView.setTextColor(Color.parseColor("#bdbdbd"));
            textView.setBackgroundResource(R.color.window_background_color);
        }
    }

    private static void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {
        if (isDarkMode) {
            editText.setTextColor(-1);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(R.drawable.edit_text_dark);
        } else {
            editText.setTextColor(ViewCompat.MEASURED_STATE_MASK);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(R.drawable.edit_text);
        }
    }

    private void updateExtra(String extraName, String containerValue, String newValue) {
        String extraValue = this.shortcut.getExtra(extraName);
        if (extraValue.isEmpty() && containerValue.equals(newValue)) {
            return;
        }
        this.shortcut.putExtra(extraName, newValue);
    }

    private void renameShortcut(String newName) {
        File parent = this.shortcut.file.getParentFile();
        File oldDesktopFile = this.shortcut.file;
        File newDesktopFile = new File(parent, newName + ".desktop");
        if (!newDesktopFile.isFile() && oldDesktopFile.renameTo(newDesktopFile)) {
            updateShortcutFileReference(newDesktopFile);
            deleteOldFileIfExists(oldDesktopFile);
        }
        File linkFile = new File(parent, this.shortcut.name + ".lnk");
        if (linkFile.isFile()) {
            File newLinkFile = new File(parent, newName + ".lnk");
            if (!newLinkFile.isFile()) {
                linkFile.renameTo(newLinkFile);
            }
        }
        this.fragment.loadShortcutsList();
        this.fragment.updateShortcutOnScreen(newName, newName, this.shortcut.container.id, newDesktopFile.getAbsolutePath(), Icon.createWithBitmap(this.shortcut.icon), this.shortcut.getExtra("uuid"));
    }

    private void deleteOldFileIfExists(File oldFile) {
        if (oldFile.exists() && !oldFile.delete()) {
            Log.e("ShortcutSettingsDialog", "Failed to delete old file: " + oldFile.getPath());
        }
    }

    private void updateShortcutFileReference(File newFile) {
        try {
            Field fileField = Shortcut.class.getDeclaredField("file");
            fileField.setAccessible(true);
            fileField.set(this.shortcut, newFile);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            Log.e("ShortcutSettingsDialog", "Error updating shortcut file reference", e);
        }
    }

    private EnvVarsView createEnvVarsTab() {
        View view = getContentView();
        final Context context = view.getContext();
        final EnvVarsView envVarsView = (EnvVarsView) view.findViewById(R.id.EnvVarsView);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        envVarsView.setDarkMode(isDarkMode);
        envVarsView.setEnvVars(new EnvVars(this.shortcut.getExtra("envVars")));
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                new AddEnvVarDialog(context, envVarsView).show();
            }
        });
        return envVarsView;
    }

    private void loadControlsProfileSpinner(Spinner spinner, String selectedValue) {
        Context context = this.fragment.getContext();
        ArrayList<ControlsProfile> profiles = this.inputControlsManager.getProfiles(true);
        ArrayList<String> values = new ArrayList<>();
        values.add(context.getString(R.string.none));
        int selectedPosition = 0;
        int selectedId = Integer.parseInt(selectedValue);
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile.id == selectedId) {
                selectedPosition = i + 1;
            }
            values.add(profile.getName());
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
    }

    private void showInputWarning() {
        Context context = this.fragment.getContext();
        ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, (Runnable) null);
    }

    public static void loadBox64VersionSpinner(Context context, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        List<String> itemList;
        if (isArm64EC) {
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.wowbox64_version_entries)));
        } else {
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.box64_version_entries)));
        }
        if (!isArm64EC) {
            for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf(45);
                itemList.add(entryName.substring(firstDashIndex + 1));
            }
        } else {
            for (ContentProfile profile2 : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)) {
                String entryName2 = ContentsManager.getEntryName(profile2);
                int firstDashIndex2 = entryName2.indexOf(45);
                itemList.add(entryName2.substring(firstDashIndex2 + 1));
            }
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }

    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, final String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();
        ContainerDetailFragment.updateGraphicsDriverSpinner(context, sGraphicsDriver);
        final String[] dxwrapperEntries = context.getResources().getStringArray(R.array.dxwrapper_entries);
        final Runnable update = new Runnable() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                ShortcutSettingsDialog.this.lambda$loadGraphicsDriverSpinner$15(sGraphicsDriver, vGraphicsDriverConfig, dxwrapperEntries, sDXWrapper, context, selectedDXWrapper);
            }
        };
        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog.5
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadGraphicsDriverSpinner$15(Spinner sGraphicsDriver, final View vGraphicsDriverConfig, String[] dxwrapperEntries, Spinner sDXWrapper, Context context, String selectedDXWrapper) {
        final String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
        String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
        this.tvGraphicsDriverVersion.setText(GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig));
        vGraphicsDriverConfig.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ShortcutSettingsDialog$$ExternalSyntheticLambda7
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ShortcutSettingsDialog.this.lambda$loadGraphicsDriverSpinner$14(vGraphicsDriverConfig, graphicsDriver, view);
            }
        });
        ArrayList<String> items = new ArrayList<>();
        for (String value : dxwrapperEntries) {
            items.add(value);
        }
        sDXWrapper.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, (String[]) items.toArray(new String[0])));
        AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadGraphicsDriverSpinner$14(View vGraphicsDriverConfig, String graphicsDriver, View v) {
        new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver, this.tvGraphicsDriverVersion).show();
    }
}
