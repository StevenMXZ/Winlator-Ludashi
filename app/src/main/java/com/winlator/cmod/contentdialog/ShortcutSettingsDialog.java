package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.ContainerDetailFragment;
import com.winlator.cmod.R;
import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.EnvVarsView;
import com.winlator.cmod.winhandler.WinHandler;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class ShortcutSettingsDialog extends ContentDialog implements DXVKConfigDialog.ContentInstallHost {
    private final ShortcutsFragment fragment;
    private final Shortcut shortcut;
    private InputControlsManager inputControlsManager;
    private TextView tvGraphicsDriverVersion;
    private String box64Version;
    private ContentsManager contentsManager;
    private static final ExecutorService CONTENT_IO_EXECUTOR = Executors.newSingleThreadExecutor();

    public ShortcutSettingsDialog(ShortcutsFragment fragment, Shortcut shortcut) {
        super(fragment.getContext(), R.layout.shortcut_settings_dialog);
        this.fragment = fragment;
        this.shortcut = shortcut;
        setTitle(shortcut.name);
        setIcon(R.drawable.icon_settings);

        ContainerManager containerManager = shortcut.container.getManager();
        createContentView();
    }

    private void createContentView() {
        final Context context = fragment.getContext();
        inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", true);

        applyDynamicStyles(findViewById(R.id.LLContent), isDarkMode);

        tvGraphicsDriverVersion = findViewById(R.id.TVGraphicsDriverVersion);

        final EditText etName = findViewById(R.id.ETName);
        etName.setText(shortcut.name);

        final EditText etExecArgs = findViewById(R.id.ETExecArgs);
        etExecArgs.setText(shortcut.getExtra("execArgs"));

        ContainerDetailFragment containerDetailFragment = new ContainerDetailFragment(shortcut.container.id);
        loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()), isDarkMode);

        final Spinner sWineVersion = findViewById(R.id.SWineVersion);
        final View btWineVersionManage = findViewById(R.id.BTWineVersionManage);
        final Spinner sGraphicsDriver = findViewById(R.id.SGraphicsDriver);
        final Spinner sDXWrapper = findViewById(R.id.SDXWrapper);
        final Spinner sBox64Version = findViewById(R.id.SBox64Version);
        
        contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        final View vGraphicsDriverConfig = findViewById(R.id.BTGraphicsDriverConfig);
        vGraphicsDriverConfig.setTag(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
        
        final View vDXWrapperConfig = findViewById(R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig()));

        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig, shortcut.getExtra("graphicsDriver", shortcut.container.getGraphicsDriver()),
            shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper()));

        findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));
        
        android.widget.TextView tvRendererMode = findViewById(R.id.TVRendererMode);
        if (tvRendererMode != null) {
            shortcut.setRendererNative(false);
            tvRendererMode.setText("Vulkan");
            tvRendererMode.setOnClickListener(null);
        }
        View btRendererOptions = findViewById(R.id.BTRendererOptions);
        if (btRendererOptions != null) {
            btRendererOptions.setOnClickListener(v -> {
                new com.winlator.cmod.contentdialog.RendererOptionsDialog(v, new com.winlator.cmod.contentdialog.RendererOptionsDialog.Config() {
                    public boolean getRendererNative() { return shortcut.getRendererNative(); }
                    public void setRendererNative(boolean val) { shortcut.setRendererNative(val); }
                    public String getRendererPresentMode() { return shortcut.getRendererPresentMode(); }
                    public void setRendererPresentMode(String val) { shortcut.setRendererPresentMode(val); }
                    public String getRendererDriverId() { return shortcut.getRendererDriverId(); }
                    public void setRendererDriverId(String val) { shortcut.setRendererDriverId(val); }
                    public int getRendererFilterMode() { return shortcut.getRendererFilterMode(); }
                    public void setRendererFilterMode(int val) { shortcut.setRendererFilterMode(val); }
                    public int getRendererRefreshRateLimit() { return shortcut.getRendererRefreshRateLimit(); }
                    public void setRendererRefreshRateLimit(int val) { shortcut.setRendererRefreshRateLimit(val); }
                    public boolean getRendererSwapRB() { return shortcut.getRendererSwapRB(); }
                    public void setRendererSwapRB(boolean val) { shortcut.setRendererSwapRB(val); }
                }, false).show();
            });
        }

        final Spinner sAudioDriver = findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, shortcut.getExtra("audioDriver", shortcut.container.getAudioDriver()));
        /* Per-shortcut Graphics Pipeline override. Falls back to the
         * container's setting if the shortcut hasn't set one. The
         * extra key matches the container's field name "graphicsPipeline"
         * so XServerDisplayActivity's lookup pattern (extra-or-container)
         * can reuse the same resolution rule used elsewhere. */
        final Spinner sGraphicsPipeline = findViewById(R.id.SGraphicsPipeline);
        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsPipeline,
                shortcut.getExtra("graphicsPipeline", shortcut.container.getGraphicsPipeline()));
        final Spinner sEmulator = findViewById(R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator, shortcut.getExtra("emulator", shortcut.container.getEmulator()));
        final Spinner sEmulator64 = findViewById(R.id.SEmulator64);
        sEmulator64.setEnabled(false);
        final Spinner sMIDISoundFont = findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, shortcut.getExtra("midiSoundFont", shortcut.container.getMIDISoundFont()));

        final EditText etLC_ALL = findViewById(R.id.ETlcall);
        etLC_ALL.setText(shortcut.getExtra("lc_all", shortcut.container.getLC_ALL()));

        final View btShowLCALL = findViewById(R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            String[] lcs = context.getResources().getStringArray(R.array.some_lc_all);
            for (int i = 0; i < lcs.length; i++)
                popupMenu.getMenu().add(Menu.NONE, i, Menu.NONE, lcs[i]);
            popupMenu.setOnMenuItemClickListener(item -> {
                etLC_ALL.setText(item.toString() + ".UTF-8");
                return true;
            });
            popupMenu.show();
        });

        FrameLayout fexcoreFL = findViewById(R.id.fexcoreFrame);
        String wineVersion = shortcut.container.getWineVersion();
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.isArm64EC()) {
            fexcoreFL.setVisibility(View.VISIBLE);
            sEmulator.setEnabled(true);
            sEmulator64.setSelection(0);
        }
        else {
            fexcoreFL.setVisibility(View.GONE);
            sEmulator.setEnabled(false);
            sEmulator.setSelection(1);
            sEmulator64.setSelection(1);
        }

        loadWineVersionSpinner(sWineVersion, sBox64Version);
        if (btWineVersionManage != null) {
            btWineVersionManage.setOnClickListener(v -> showWineConfigurationDialog(sWineVersion, sBox64Version));
        }
        setupDXWrapperSpinnerWithDialogHost(sDXWrapper, vDXWrapperConfig, wineInfo.isArm64EC());
        loadBox64VersionSpinner(context, contentsManager, sBox64Version, wineInfo.isArm64EC());

        String currentBox64Version = shortcut.getExtra("box64Version", shortcut.container.getBox64Version());
        if (currentBox64Version != null) {
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, currentBox64Version);
        } else {
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, wineInfo.isArm64EC() ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
        }

        sBox64Version.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedVersion = parent.getItemAtPosition(position).toString();
                box64Version = selectedVersion;
                shortcut.putExtra("box64Version", selectedVersion);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        final CheckBox cbFullscreenStretched =  findViewById(R.id.CBFullscreenStretched);
        boolean fullscreenStretched = shortcut.getExtra("fullscreenStretched", "0").equals("1");
        cbFullscreenStretched.setChecked(fullscreenStretched);

        final Runnable showInputWarning = () -> ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
        final CheckBox cbEnableXInput = findViewById(R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = findViewById(R.id.CBEnableDInput);
        final CheckBox cbExclusiveXInput = findViewById(R.id.CBExclusiveXInput);
        final View btHelpXInput = findViewById(R.id.BTXInputHelp);
        final View btHelpDInput = findViewById(R.id.BTDInputHelp);
        final View btHelpExclusiveXInput = findViewById(R.id.BTExclusiveXInputHelp);
        int inputType = Integer.parseInt(shortcut.getExtra("inputType", String.valueOf(shortcut.container.getInputType())));

        cbEnableXInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_XINPUT) == WinHandler.FLAG_INPUT_TYPE_XINPUT);
        cbEnableDInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT);
        
        String exclusiveXInputExtra = shortcut.getExtra("exclusiveXInput");
        boolean exclusiveXInput = exclusiveXInputExtra.isEmpty() ? shortcut.container.isExclusiveXInput() : exclusiveXInputExtra.equals("1");
        cbExclusiveXInput.setChecked(exclusiveXInput);

        cbEnableDInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (cbExclusiveXInput.isChecked()) {
                if (isChecked && cbEnableXInput.isChecked()) {
                    cbEnableXInput.setChecked(false);
                }
            }
        });
        cbEnableXInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (cbExclusiveXInput.isChecked()) {
                if (isChecked && cbEnableDInput.isChecked()) {
                    cbEnableDInput.setChecked(false);
                }
            }
        });

        cbExclusiveXInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                cbEnableXInput.setChecked(true);
                cbEnableDInput.setChecked(true);
                cbEnableXInput.setEnabled(false);
                cbEnableDInput.setEnabled(false);
            } else {
                cbEnableXInput.setEnabled(true);
                cbEnableDInput.setEnabled(true);
                if (cbEnableXInput.isChecked() && cbEnableDInput.isChecked()) cbEnableDInput.setChecked(false);
            }
        });

        if (!cbExclusiveXInput.isChecked()) {
            cbEnableXInput.setChecked(true);
            cbEnableDInput.setChecked(true);
            cbEnableXInput.setEnabled(false);
            cbEnableDInput.setEnabled(false);
        }
        btHelpXInput.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_xinput));
        btHelpDInput.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_dinput));
        btHelpExclusiveXInput.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_exclusive_xinput));

        final Spinner sBox64Preset = findViewById(R.id.SBox64Preset);
        Box64PresetManager.loadSpinner("box64", sBox64Preset, shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset()));

        final Spinner sFEXCoreVersion = findViewById(R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion, shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion()));
        View btBox64VersionRemove = findViewById(R.id.BTBox64VersionRemove);
        View btBox64VersionDownload = findViewById(R.id.BTBox64VersionDownload);
        View btFEXCoreVersionRemove = findViewById(R.id.BTFEXCoreVersionRemove);
        View btFEXCoreVersionDownload = findViewById(R.id.BTFEXCoreVersionDownload);
        Runnable refreshBox64 = () -> {
            String wineVersionSelected = (sWineVersion != null && sWineVersion.getSelectedItem() != null)
                    ? sWineVersion.getSelectedItem().toString()
                    : shortcut.container.getWineVersion();
            WineInfo wi = WineInfo.fromIdentifier(context, contentsManager, wineVersionSelected);
            if (sBox64Version != null) loadBox64VersionSpinner(context, contentsManager, sBox64Version, wi.isArm64EC());
        };
        if (btBox64VersionRemove != null) btBox64VersionRemove.setOnClickListener(v ->
                removeSelectedContent(Collections.singletonList(getBox64LikeContentType(context, sWineVersion)),
                        () -> sBox64Version.getSelectedItem() != null ? sBox64Version.getSelectedItem().toString() : "",
                        refreshBox64));
        if (btBox64VersionDownload != null) btBox64VersionDownload.setOnClickListener(v ->
                showInstallChoicePopup(v, Collections.singletonList(getBox64LikeContentType(context, sWineVersion)), refreshBox64));
        if (btFEXCoreVersionRemove != null) btFEXCoreVersionRemove.setOnClickListener(v ->
                removeSelectedContent(Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE),
                        () -> sFEXCoreVersion.getSelectedItem() != null ? sFEXCoreVersion.getSelectedItem().toString() : "",
                        () -> FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion,
                                sFEXCoreVersion.getSelectedItem() != null ? sFEXCoreVersion.getSelectedItem().toString() : DefaultVersion.FEXCORE)));
        if (btFEXCoreVersionDownload != null) btFEXCoreVersionDownload.setOnClickListener(v ->
                showInstallChoicePopup(v, Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE),
                        () -> FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion,
                                sFEXCoreVersion.getSelectedItem() != null ? sFEXCoreVersion.getSelectedItem().toString() : DefaultVersion.FEXCORE)));

        final Spinner sFEXCorePreset = findViewById(R.id.SFEXCorePreset);
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset()));

        final Spinner sControlsProfile = findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, shortcut.getExtra("controlsProfile", "0"));

        final CheckBox cbDisabledXInput = findViewById(R.id.CBDisabledXInput);
        boolean isXInputDisabled = shortcut.getExtra("disableXinput", "0").equals("1");
        cbDisabledXInput.setChecked(isXInputDisabled);

        final CheckBox cbSimTouchScreen = findViewById(R.id.CBTouchscreenMode);
        String isTouchScreenMode = shortcut.getExtra("simTouchScreen");
        cbSimTouchScreen.setChecked(isTouchScreenMode.equals("1") ? true : false);

        ContainerDetailFragment.createWinComponentsTabFromShortcut(this, getContentView(),
                shortcut.getExtra("wincomponents", shortcut.container.getWinComponents()), isDarkMode);

        final EnvVarsView envVarsView = createEnvVarsTab();

        AppUtils.setupTabLayout(getContentView(), R.id.TabLayout, R.id.LLTabWinComponents, R.id.LLTabEnvVars, R.id.LLTabAdvanced);

        TabLayout tabLayout = findViewById(R.id.TabLayout);

        if (isDarkMode) {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background_dark);
        } else {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background);
        }

        findViewById(R.id.BTExtraArgsMenu).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.inflate(R.menu.extra_args_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                String value = String.valueOf(menuItem.getTitle());
                String execArgs = etExecArgs.getText().toString();
                if (!execArgs.contains(value)) etExecArgs.setText(!execArgs.isEmpty() ? execArgs + " " + value : value);
                return true;
            });
            popupMenu.show();
        });

        String selectedDriver = sGraphicsDriver.getSelectedItem().toString();
        List<String> sGraphicsItemsList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.graphics_driver_entries)));
        sGraphicsDriver.setAdapter(buildSpinnerAdapter(context, sGraphicsItemsList));
        AppUtils.setSpinnerSelectionFromValue(sGraphicsDriver, selectedDriver);

        final Spinner sStartupSelection = findViewById(R.id.SStartupSelection);
        sStartupSelection.setSelection(Integer.parseInt(shortcut.getExtra("startupSelection", String.valueOf(shortcut.container.getStartupSelection()))));

        final Spinner sSharpnessEffect = findViewById(R.id.SSharpnessEffect);
        final SeekBar sbSharpnessLevel = findViewById(R.id.SBSharpnessLevel);
        final SeekBar sbSharpnessDenoise = findViewById(R.id.SBSharpnessDenoise);
        final TextView tvSharpnessLevel = findViewById(R.id.TVSharpnessLevel);
        final TextView tvSharpnessDenoise = findViewById(R.id.TVSharpnessDenoise);

        AppUtils.setSpinnerSelectionFromValue(sSharpnessEffect, shortcut.getExtra("sharpnessEffect", "None"));

        sbSharpnessLevel.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessLevel", "100")));
        tvSharpnessLevel.setText(shortcut.getExtra("sharpnessLevel", "100") + "%");
        sbSharpnessLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessLevel.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbSharpnessDenoise.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessDenoise", "100")));
        tvSharpnessDenoise.setText(shortcut.getExtra("sharpnessDenoise", "100") + "%");
        sbSharpnessDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessDenoise.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        final CPUListView cpuListView = findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(shortcut.getExtra("cpuList", shortcut.container.getCPUList(true)));

        setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            boolean nameChanged = !shortcut.name.equals(name) && !name.isEmpty();

            if (nameChanged) {
                renameShortcut(name);
            }

            boolean renamingSuccess = !nameChanged || new File(shortcut.file.getParent(), name + ".desktop").exists();

            if (renamingSuccess) {
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String graphicsPipeline = StringUtils.parseIdentifier(sGraphicsPipeline.getSelectedItem());
                String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
                String lc_all = etLC_ALL.getText().toString();
                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
                String screenSize = containerDetailFragment.getScreenSize(getContentView());

                int finalInputType = 0;
                finalInputType |= cbEnableXInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_XINPUT : 0;
                finalInputType |= cbEnableDInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_DINPUT : 0;

                shortcut.putExtra("inputType", String.valueOf(finalInputType));
                shortcut.putExtra("exclusiveXInput", cbExclusiveXInput.isChecked() ? "1" : "0");

                boolean disabledXInput = cbDisabledXInput.isChecked();
                shortcut.putExtra("disableXinput", disabledXInput ? "1" : null);

                boolean touchscreenMode = cbSimTouchScreen.isChecked();
                shortcut.putExtra("simTouchScreen", touchscreenMode ? "1" : "0");

                String execArgs = etExecArgs.getText().toString();
                shortcut.putExtra("execArgs", !execArgs.isEmpty() ? execArgs : null);
                shortcut.putExtra("screenSize", screenSize);
                shortcut.putExtra("graphicsDriver", graphicsDriver);
                shortcut.putExtra("graphicsDriverConfig", graphicsDriverConfig);
                shortcut.putExtra("dxwrapper", dxwrapper);
                shortcut.putExtra("dxwrapperConfig", dxwrapperConfig);
                shortcut.putExtra("audioDriver", audioDriver);
                /* Persist only when different from the container's value so
                 * un-overridden shortcuts inherit container changes. The
                 * lookup at launch time uses the same extra-or-container
                 * pattern that audioDriver/emulator/etc. do. */
                if (!graphicsPipeline.equals(shortcut.container.getGraphicsPipeline())) {
                    shortcut.putExtra("graphicsPipeline", graphicsPipeline);
                } else {
                    shortcut.putExtra("graphicsPipeline", null);
                }
                shortcut.putExtra("emulator", emulator);
                shortcut.putExtra("midiSoundFont", midiSoundFont);
                shortcut.putExtra("lc_all", lc_all);

                shortcut.putExtra("fullscreenStretched", cbFullscreenStretched.isChecked() ? "1" : null);
                

                String wincomponents = containerDetailFragment.getWinComponents(getContentView());
                shortcut.putExtra("wincomponents", wincomponents);

                String envVars = envVarsView.getEnvVars();
                shortcut.putExtra("envVars", !envVars.isEmpty() ? envVars : null);

                String fexcoreVersion = sFEXCoreVersion.getSelectedItem().toString();
                shortcut.putExtra("fexcoreVersion", fexcoreVersion);

                String fexcorePreset = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
                shortcut.putExtra("fexcorePreset", fexcorePreset);

                String box64Preset = Box64PresetManager.getSpinnerSelectedId(sBox64Preset);
                shortcut.putExtra("box64Preset", box64Preset);

                byte startupSelection = (byte)sStartupSelection.getSelectedItemPosition();
                shortcut.putExtra("startupSelection", String.valueOf(startupSelection));

                String sharpeningEffect = sSharpnessEffect.getSelectedItem().toString();
                String sharpeningLevel = String.valueOf(sbSharpnessLevel.getProgress());
                String sharpeningDenoise = String.valueOf(sbSharpnessDenoise.getProgress());
                shortcut.putExtra("sharpnessEffect", sharpeningEffect);
                shortcut.putExtra("sharpnessLevel", sharpeningLevel);
                shortcut.putExtra("sharpnessDenoise", sharpeningDenoise);

                ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
                int controlsProfile = sControlsProfile.getSelectedItemPosition() > 0 ? profiles.get(sControlsProfile.getSelectedItemPosition() - 1).id : 0;
                shortcut.putExtra("controlsProfile", controlsProfile > 0 ? String.valueOf(controlsProfile) : null);

                String cpuList = cpuListView.getCheckedCPUListAsString();
                shortcut.putExtra("cpuList", cpuList);

                shortcut.saveData();
            }
        });
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
        return text.equalsIgnoreCase("DirectX") ||
                text.equalsIgnoreCase("General") ||
                text.equalsIgnoreCase("Box64") ||
                text.equalsIgnoreCase("Input Controls") ||
                text.equalsIgnoreCase("Game Controller") ||
                text.equalsIgnoreCase("System");
    }

    public void onWinComponentsViewsAdded(boolean isDarkMode) {
        ViewGroup llContent = findViewById(R.id.LLContent);
        applyFieldSetLabelStylesDynamically(llContent, isDarkMode);
    }

    public static void loadScreenSizeSpinner(View view, String selectedValue, boolean isDarkMode) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);

        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenWidth), isDarkMode);
        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenHeight), isDarkMode);

        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String[] screenSize = selectedValue.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }
    private void applyDynamicStyles(View view, boolean isDarkMode) {
        EditText etName = view.findViewById(R.id.ETName);
        applyDarkThemeToEditText(etName, isDarkMode);

        Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);
        Spinner sWineVersion = view.findViewById(R.id.SWineVersion);
        Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);
        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        Spinner sEmulatorSpinner = view.findViewById(R.id.SEmulator);
        Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        Spinner sControlsProfile = view.findViewById(R.id.SControlsProfile);
        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        Spinner sBox64Version = view.findViewById(R.id.SBox64Version);
        Spinner sFEXCoreVersion = view.findViewById(R.id.SFEXCoreVersion);
        Spinner sFEXCorePreset = view.findViewById(R.id.SFEXCorePreset);
        Spinner sStartupSelection = view.findViewById(R.id.SStartupSelection);
        // Graphics Pipeline spinner — was missing from the popup-background
        // theming list, so its dropdown kept the default light background
        // while the text inherited white (dark theme), making items invisible
        // ("white box that pops up"). Theme it like the others.
        Spinner sGraphicsPipeline = view.findViewById(R.id.SGraphicsPipeline);

        int popupBackground = isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background;
        applySpinnerPopupBackground(sGraphicsDriver, popupBackground);
        applySpinnerPopupBackground(sWineVersion, popupBackground);
        applySpinnerPopupBackground(sDXWrapper, popupBackground);
        applySpinnerPopupBackground(sAudioDriver, popupBackground);
        applySpinnerPopupBackground(sEmulatorSpinner, popupBackground);
        applySpinnerPopupBackground(sBox64Preset, popupBackground);
        applySpinnerPopupBackground(sControlsProfile, popupBackground);
        applySpinnerPopupBackground(sMIDISoundFont, popupBackground);
        applySpinnerPopupBackground(sBox64Version, popupBackground);
        applySpinnerPopupBackground(sFEXCorePreset, popupBackground);
        applySpinnerPopupBackground(sFEXCoreVersion, popupBackground);
        applySpinnerPopupBackground(sStartupSelection, popupBackground);
        applySpinnerPopupBackground(sGraphicsPipeline, popupBackground);

        int comboBackground = isDarkMode ? R.drawable.combo_box_dark : R.drawable.edit_text;
        applySpinnerBackground(sWineVersion, comboBackground);
        applySpinnerBackground(sGraphicsDriver, comboBackground);
        applySpinnerBackground(sDXWrapper, comboBackground);
        TextView tvRendererMode = view.findViewById(R.id.TVRendererMode);
        if (tvRendererMode != null) {
            tvRendererMode.setBackgroundResource(comboBackground);
            tvRendererMode.setTextColor(isDarkMode ? Color.WHITE : Color.BLACK);
        }

        EditText etExecArgs = view.findViewById(R.id.ETExecArgs);
        applyDarkThemeToEditText(etExecArgs, isDarkMode);
    }

    private void applySpinnerPopupBackground(@Nullable Spinner spinner, int popupBackground) {
        if (spinner != null) spinner.setPopupBackgroundResource(popupBackground);
    }

    private void applySpinnerBackground(@Nullable Spinner spinner, int background) {
        if (spinner != null) spinner.setBackgroundResource(background);
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
            editText.setTextColor(Color.WHITE);
            editText.setHintTextColor(Color.GRAY);
            editText.setBackgroundResource(R.drawable.edit_text_dark);
        } else {
            editText.setTextColor(Color.BLACK);
            editText.setHintTextColor(Color.GRAY);
            editText.setBackgroundResource(R.drawable.edit_text);
        }
    }
    private void updateExtra(String extraName, String containerValue, String newValue) {
        String extraValue = shortcut.getExtra(extraName);
        if (extraValue.isEmpty() && containerValue.equals(newValue))
            return;
        shortcut.putExtra(extraName, newValue);
    }

    private void renameShortcut(String newName) {
        File parent = shortcut.file.getParentFile();
        File oldDesktopFile = shortcut.file; 
        File newDesktopFile = new File(parent, newName + ".desktop");

        if (!newDesktopFile.isFile() && oldDesktopFile.renameTo(newDesktopFile)) {
            updateShortcutFileReference(newDesktopFile); 
            deleteOldFileIfExists(oldDesktopFile);
        }

        File linkFile = new File(parent, shortcut.name + ".lnk");
        if (linkFile.isFile()) {
            File newLinkFile = new File(parent, newName + ".lnk");
            if (!newLinkFile.isFile()) linkFile.renameTo(newLinkFile);
        }

        fragment.loadShortcutsList();
        fragment.updateShortcutOnScreen(newName, newName, shortcut.container.id, newDesktopFile.getAbsolutePath(),
                Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid"));
    }

    private void deleteOldFileIfExists(File oldFile) {
        if (oldFile.exists()) {
            if (!oldFile.delete()) {
                Log.e("ShortcutSettingsDialog", "Failed to delete old file: " + oldFile.getPath());
            }
        }
    }

    private void updateShortcutFileReference(File newFile) {
        try {
            Field fileField = Shortcut.class.getDeclaredField("file");
            fileField.setAccessible(true);
            fileField.set(shortcut, newFile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e("ShortcutSettingsDialog", "Error updating shortcut file reference", e);
        }
    }


    private EnvVarsView createEnvVarsTab() {
        final View view = getContentView();
        final Context context = view.getContext();

        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", true);
        envVarsView.setDarkMode(isDarkMode);

        envVarsView.setEnvVars(new EnvVars(shortcut.getExtra("envVars")));

        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) ->
                new AddEnvVarDialog(context, envVarsView).show()
        );

        return envVarsView;
    }
    private void loadControlsProfileSpinner(Spinner spinner, String selectedValue) {
        final Context context = fragment.getContext();
        final ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> values = new ArrayList<>();
        values.add(context.getString(R.string.none));

        int selectedPosition = 0;
        int selectedId = Integer.parseInt(selectedValue);
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile.id == selectedId) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(buildSpinnerAdapter(context, values));
        spinner.setSelection(selectedPosition, false);
    }

    private void showInputWarning() {
        final Context context = fragment.getContext();
        ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
    }

    public static void loadBox64VersionSpinner(Context context, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        versions.add(isArm64EC ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
        if (!isArm64EC) {
            for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64)) {
                if (profile.remoteUrl != null) continue;
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                versions.add(entryName.substring(firstDashIndex + 1));
            }
        } else {
            for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)) {
                if (profile.remoteUrl != null) continue;
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                versions.add(entryName.substring(firstDashIndex + 1));
            }
        }
        List<String> itemList = new ArrayList<>(versions);
        spinner.setAdapter(buildSpinnerAdapter(context, itemList));
    }

    private ContentProfile.ContentType getBox64LikeContentType(Context context, Spinner sWineVersion) {
        String wineVersion = (sWineVersion != null && sWineVersion.getSelectedItem() != null)
                ? sWineVersion.getSelectedItem().toString()
                : shortcut.container.getWineVersion();
        WineInfo wi = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        return wi.isArm64EC() ? ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 : ContentProfile.ContentType.CONTENT_TYPE_BOX64;
    }

    private void loadWineVersionSpinner(Spinner sWineVersion, Spinner sBox64Version) {
        final Context context = fragment.getContext();
        if (sWineVersion == null) return;
        sWineVersion.setEnabled(false);
        sWineVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                FrameLayout fexcoreFL = findViewById(R.id.fexcoreFrame);
                Spinner sEmulator = findViewById(R.id.SEmulator);
                Spinner sEmulator64 = findViewById(R.id.SEmulator64);
                Spinner sDXWrapper = findViewById(R.id.SDXWrapper);
                View vDXWrapperConfig = findViewById(R.id.BTDXWrapperConfig);
                if (sEmulator64 != null) sEmulator64.setEnabled(false);
                String wineVersion = sWineVersion.getSelectedItem().toString();
                WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
                if (wineInfo.isArm64EC()) {
                    if (fexcoreFL != null) fexcoreFL.setVisibility(View.VISIBLE);
                    if (sEmulator != null) sEmulator.setEnabled(true);
                    if (sEmulator64 != null) sEmulator64.setSelection(0);
                } else {
                    if (fexcoreFL != null) fexcoreFL.setVisibility(View.GONE);
                    if (sEmulator != null) {
                        sEmulator.setEnabled(false);
                        sEmulator.setSelection(1);
                    }
                    if (sEmulator64 != null) sEmulator64.setSelection(1);
                }
                if (sBox64Version != null) {
                    loadBox64VersionSpinner(context, contentsManager, sBox64Version, wineInfo.isArm64EC());
                }
                if (sDXWrapper != null) {
                    setupDXWrapperSpinnerWithDialogHost(sDXWrapper, vDXWrapperConfig, wineInfo.isArm64EC());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        String[] versions = context.getResources().getStringArray(R.array.wine_entries);
        ArrayList<String> wineVersions = new ArrayList<>(Arrays.asList(versions));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)) {
            if (profile.remoteUrl != null) continue;
            wineVersions.add(ContentsManager.getEntryName(profile));
        }
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            if (profile.remoteUrl != null) continue;
            wineVersions.add(ContentsManager.getEntryName(profile));
        }
        sWineVersion.setAdapter(buildSpinnerAdapter(context, wineVersions));
        AppUtils.setSpinnerSelectionFromValue(sWineVersion, shortcut.container.getWineVersion());
    }

    private void syncSpinnerChoices(Spinner source, Spinner target) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < source.getCount(); i++) values.add(source.getItemAtPosition(i).toString());
        target.setAdapter(buildSpinnerAdapter(fragment.getContext(), values));
        if (source.getSelectedItem() != null) AppUtils.setSpinnerSelectionFromValue(target, source.getSelectedItem().toString());
    }

    private void showWineConfigurationDialog(Spinner sWineVersion, Spinner sBox64Version) {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.wine_config_dialog);
        dialog.setTitle("Wine configuration");
        dialog.setIcon(R.drawable.icon_monitor);
        final Spinner sWineConfigVersion = dialog.findViewById(R.id.SWineConfigVersion);
        final View btWineConfigRemove = dialog.findViewById(R.id.BTWineConfigRemove);
        final View btWineConfigDownload = dialog.findViewById(R.id.BTWineConfigDownload);
        Runnable refreshWineDialogData = () -> {
            loadWineVersionSpinner(sWineVersion, sBox64Version);
            syncSpinnerChoices(sWineVersion, sWineConfigVersion);
        };
        syncSpinnerChoices(sWineVersion, sWineConfigVersion);
        boolean darkModeEnabled = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("dark_mode", true);
        sWineConfigVersion.setEnabled(false);
        sWineConfigVersion.setPopupBackgroundResource(darkModeEnabled
                ? R.drawable.content_dialog_background_dark
                : R.drawable.content_dialog_background);
        sWineConfigVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < sWineVersion.getCount() && sWineConfigVersion.isEnabled()) {
                    sWineVersion.setSelection(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        btWineConfigRemove.setOnClickListener(v -> removeSelectedContent(
                Arrays.asList(ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON),
                () -> sWineConfigVersion.getSelectedItem() != null ? sWineConfigVersion.getSelectedItem().toString() : "",
                refreshWineDialogData));
        btWineConfigDownload.setOnClickListener(v -> showInstallChoicePopup(v,
                Arrays.asList(ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON),
                refreshWineDialogData));
        dialog.show();
    }

    public void showInstallChoicePopup(View anchor, List<ContentProfile.ContentType> types, Runnable refreshAction) {
        boolean isDarkMode = PreferenceManager.getDefaultSharedPreferences(getContext()).getBoolean("dark_mode", true);
        Context themedContext = isDarkMode
                ? new ContextThemeWrapper(getContext(), R.style.AppTheme_Dark)
                : new ContextThemeWrapper(getContext(), R.style.AppTheme);
        PopupMenu popupMenu = new PopupMenu(themedContext, anchor);
        MenuItem openItem = popupMenu.getMenu().add(0, 0, 0, "Open file");
        openItem.setIcon(R.drawable.icon_popup_menu_open);
        MenuItem downloadItem = popupMenu.getMenu().add(0, 1, 1, "Download file");
        downloadItem.setIcon(R.drawable.icon_popup_menu_download);
        int tint = getContext().getResources().getColor(R.color.colorAccent, themedContext.getTheme());
        if (openItem.getIcon() != null) openItem.getIcon().setTint(tint);
        if (downloadItem.getIcon() != null) downloadItem.getIcon().setTint(tint);
        popupMenu.setOnMenuItemClickListener(item -> {
            handleInstallChoice(item.getItemId(), types, refreshAction);
            return true;
        });
        forceShowMenuIcons(popupMenu);
        popupMenu.show();
    }

    private void handleInstallChoice(int idx, List<ContentProfile.ContentType> types, Runnable refreshAction) {
        if (idx == 0) {
            fragment.pickContentArchive(uri -> {
                if (uri != null) installImportedContent(uri, types, refreshAction);
            });
        } else if (idx == 1) {
            downloadContentForTypes(types, refreshAction);
        }
    }

    public void removeSelectedContent(List<ContentProfile.ContentType> types, Supplier<String> selectedValue, Runnable refreshAction) {
        ContentProfile profile = resolveProfile(types, selectedValue.get());
        if (profile == null || profile.remoteUrl != null) {
            Toast.makeText(getContext(), "Unable to remove content", Toast.LENGTH_SHORT).show();
            return;
        }
        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_content, () -> {
            contentsManager.removeContent(profile);
            contentsManager.syncContents();
            refreshAction.run();
        });
    }

    private ContentProfile resolveProfile(List<ContentProfile.ContentType> types, String selectedValue) {
        if (selectedValue == null || selectedValue.isEmpty()) return null;
        ContentProfile byEntry = contentsManager.getProfileByEntryName(selectedValue);
        if (byEntry != null) return byEntry;
        for (ContentProfile.ContentType type : types) {
            for (ContentProfile profile : contentsManager.getProfiles(type)) {
                if (selectedValue.equals(profile.verName) || selectedValue.contains(profile.verName)) return profile;
            }
        }
        return null;
    }

    private void downloadContentForTypes(List<ContentProfile.ContentType> types, Runnable refreshAction) {
        PreloaderDialog dialog = new PreloaderDialog(fragment.requireActivity());
        dialog.showOnUiThread(R.string.loading);
        CONTENT_IO_EXECUTOR.execute(() -> {
            String contentsURL = PreferenceManager.getDefaultSharedPreferences(getContext())
                    .getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
            String json = Downloader.downloadString(contentsURL);
            if (json != null) contentsManager.setRemoteProfiles(json);
            List<ContentProfile> candidates = new ArrayList<>();
            for (ContentProfile.ContentType type : types)
                for (ContentProfile profile : contentsManager.getProfiles(type))
                    if (profile.remoteUrl != null) candidates.add(profile);
            fragment.requireActivity().runOnUiThread(() -> {
                dialog.closeOnUiThread();
                if (candidates.isEmpty()) {
                    AppUtils.showToast(getContext(), R.string.no_items_to_display);
                    return;
                }
                String[] entries = new String[candidates.size()];
                for (int i = 0; i < candidates.size(); i++) entries[i] = candidates.get(i).verName;
                ContentDialog.showSingleChoiceList(fragment.requireActivity(), R.string.install_content, entries, idx -> {
                    if (idx < 0 || idx >= candidates.size()) return;
                    ContentProfile selected = candidates.get(idx);
                    downloadAndInstallProfile(selected, () -> {
                        refreshAction.run();
                        applyDownloadedProfileSelection(selected);
                    });
                });
            });
        });
    }

    private void downloadAndInstallProfile(ContentProfile profile, Runnable refreshAction) {
        PreloaderDialog dialog = new PreloaderDialog(fragment.requireActivity());
        dialog.showOnUiThread(R.string.downloading_file);
        CONTENT_IO_EXECUTOR.execute(() -> {
            long timestamp = System.currentTimeMillis();
            File output = new File(getContext().getCacheDir(), "content_" + timestamp);
            if (!Downloader.downloadFile(profile.remoteUrl, output, (progress, downloadedBytes, totalBytes) ->
                    fragment.requireActivity().runOnUiThread(() -> dialog.setProgress(progress, downloadedBytes, totalBytes)))) {
                fragment.requireActivity().runOnUiThread(() -> {
                    dialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), R.string.unable_to_download_file);
                });
                return;
            }
            fragment.requireActivity().runOnUiThread(() -> {
                dialog.setProgress(100);
                dialog.setText(R.string.installing_content);
                dialog.setIndeterminate(true);
            });
            installImportedContent(Uri.fromFile(output), Collections.singletonList(profile.type), refreshAction, dialog);
        });
    }

    private void applyDownloadedProfileSelection(ContentProfile profile) {
        String entryName = ContentsManager.getEntryName(profile);
        int firstDash = entryName.indexOf('-');
        String normalizedVersion = firstDash >= 0 ? entryName.substring(firstDash + 1) : entryName;

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK) {
            Spinner spinner = findViewById(R.id.SDXVKVersion);
            if (spinner != null) AppUtils.setSpinnerSelectionFromValue(spinner, normalizedVersion);
            return;
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_FEXCORE) {
            Spinner spinner = findViewById(R.id.SFEXCoreVersion);
            if (spinner != null) AppUtils.setSpinnerSelectionFromValue(spinner, normalizedVersion);
            return;
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64 || profile.type == ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64) {
            Spinner spinner = findViewById(R.id.SBox64Version);
            if (spinner != null) AppUtils.setSpinnerSelectionFromValue(spinner, normalizedVersion);
        }
    }

    private void installImportedContent(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction) {
        installImportedContent(uri, expectedTypes, refreshAction, null);
    }

    private void installImportedContent(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction, @Nullable PreloaderDialog existingDialog) {
        PreloaderDialog dialog = existingDialog != null ? existingDialog : new PreloaderDialog(fragment.requireActivity());
        if (existingDialog == null) dialog.showOnUiThread(R.string.installing_content);
        else fragment.requireActivity().runOnUiThread(() -> {
            dialog.setText(R.string.installing_content);
            dialog.setIndeterminate(true);
        });
        CONTENT_IO_EXECUTOR.execute(() -> contentsManager.extraContentFile(uri, new ContentsManager.OnInstallFinishedCallback() {
            @Override
            public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                fragment.requireActivity().runOnUiThread(() -> {
                    dialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), R.string.install_failed);
                });
            }

            @Override
            public void onSucceed(ContentProfile profile) {
                if (!expectedTypes.contains(profile.type)) {
                    fragment.requireActivity().runOnUiThread(() -> {
                        dialog.closeOnUiThread();
                        ContentDialog.alert(getContext(), R.string.profile_cannot_be_recognized, null);
                    });
                    return;
                }
                contentsManager.finishInstallContent(profile, new ContentsManager.OnInstallFinishedCallback() {
                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        fragment.requireActivity().runOnUiThread(() -> {
                            dialog.closeOnUiThread();
                            AppUtils.showToast(getContext(), R.string.install_failed);
                        });
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        fragment.requireActivity().runOnUiThread(() -> {
                            dialog.closeOnUiThread();
                            contentsManager.syncContents();
                            refreshAction.run();
                        });
                    }
                });
            }
        }));
    }

    private void forceShowMenuIcons(PopupMenu popupMenu) {
        try {
            java.lang.reflect.Field field = PopupMenu.class.getDeclaredField("mPopup");
            field.setAccessible(true);
            Object menuHelper = field.get(popupMenu);
            menuHelper.getClass().getDeclaredMethod("setForceShowIcon", boolean.class).invoke(menuHelper, true);
        } catch (Exception ignored) {}
    }

    private void setupDXWrapperSpinnerWithDialogHost(final Spinner sDXWrapper, final View vDXWrapperConfig, boolean isARM64EC) {
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.startsWith("dxvk"))
                    vDXWrapperConfig.setOnClickListener((v) -> (new DXVKConfigDialog(vDXWrapperConfig, isARM64EC, ShortcutSettingsDialog.this, contentsManager)).show());
                else if (dxwrapper.equals("wined3d"))
                    vDXWrapperConfig.setOnClickListener((v) -> (new WineD3DConfigDialog(vDXWrapperConfig)).show());
                vDXWrapperConfig.setVisibility(View.VISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };

        sDXWrapper.setOnItemSelectedListener(listener);
        int selectedPosition = sDXWrapper.getSelectedItemPosition();
        if (selectedPosition >= 0 && selectedPosition < sDXWrapper.getCount()) {
            listener.onItemSelected(
                    sDXWrapper,
                    sDXWrapper.getSelectedView(),
                    selectedPosition,
                    sDXWrapper.getSelectedItemId()
            );
        }
    }
    
    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();
        
        ContainerDetailFragment.updateGraphicsDriverSpinner(context, sGraphicsDriver);
        
        final String[] dxwrapperEntries = context.getResources().getStringArray(R.array.dxwrapper_entries);
        
        Runnable update = () -> {
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
            String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();

            tvGraphicsDriverVersion.setText(GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig));

            vGraphicsDriverConfig.setOnClickListener((v) -> {
                new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver, tvGraphicsDriverVersion).show();
            });
            ArrayList<String> items = new ArrayList<>();
            for (String value : dxwrapperEntries) {
                    items.add(value);
            }
            sDXWrapper.setAdapter(buildSpinnerAdapter(context, Arrays.asList(items.toArray(new String[0]))));
            AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);
        };

        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }

    private static <T> ArrayAdapter<T> buildSpinnerAdapter(Context context, List<T> items) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }
}
