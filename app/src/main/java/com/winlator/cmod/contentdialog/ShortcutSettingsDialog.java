package com.winlator.cmod.contentdialog;

import android.graphics.drawable.Icon;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
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
import com.winlator.cmod.container.Container;
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
        setIcon(R.drawable.icon_monitor);
        ContainerManager containerManager = shortcut.container.getManager();

        createContentView();
    }

    private void createContentView() {
        final Context context = fragment.getContext();
        inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        View scrollView = (View) llContent.getParent();
        ViewGroup.LayoutParams scrollParams = scrollView.getLayoutParams();
        scrollParams.height = (int)(AppUtils.getScreenHeight() * 0.76f);
        scrollView.setLayoutParams(scrollParams);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);

        applyDynamicStyles(findViewById(R.id.LLContent), isDarkMode);
        tvGraphicsDriverVersion = findViewById(R.id.TVGraphicsDriverVersion);

        final EditText etName = findViewById(R.id.ETName);
        etName.setText(shortcut.name);

        final EditText etExecArgs = findViewById(R.id.ETExecArgs);
        etExecArgs.setText(shortcut.getExtra("execArgs"));

        ContainerDetailFragment containerDetailFragment = new ContainerDetailFragment(shortcut.container.id);

        loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()), isDarkMode);

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

        final boolean[] rendererNativeHolder = new boolean[] { shortcut.getRendererNative() };
        final boolean[] useDisplayXHolder = new boolean[] { shortcut.getUseDisplayX() };
        final String[] rendererPresentModeHolder = new String[] { shortcut.getRendererPresentMode() };
        final String[] rendererDriverHolder = new String[] { shortcut.getRendererDriverId() };
        final int[] rendererFilterHolder = new int[] { shortcut.getRendererFilterMode() };
        final boolean[] rendererSwapRBHolder = new boolean[] { shortcut.getRendererSwapRB() };
        final Spinner spRendererMode = findViewById(R.id.SPRendererMode);
        if (spRendererMode != null) {
            ArrayAdapter<String> rendererModeAdapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled,
                    new String[]{"Vulkan", "EGL", "DisplayX"});
            rendererModeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled);
            spRendererMode.setAdapter(rendererModeAdapter);
            spRendererMode.setSelection(useDisplayXHolder[0] ? 2 : (rendererNativeHolder[0] ? 1 : 0));
            spRendererMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                    rendererNativeHolder[0] = position == 1;
                    useDisplayXHolder[0] = position == 2;
                }
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }
        View btRendererOptions = findViewById(R.id.BTRendererOptions);
        if (btRendererOptions != null) {
            btRendererOptions.setOnClickListener(v -> new com.winlator.cmod.contentdialog.RendererOptionsDialog(v, new com.winlator.cmod.contentdialog.RendererOptionsDialog.Config() {
                public String getRendererPresentMode() { return rendererPresentModeHolder[0]; }
                public void setRendererPresentMode(String val) { rendererPresentModeHolder[0] = val; }
                public String getRendererDriverId() { return rendererDriverHolder[0]; }
                public void setRendererDriverId(String val) { rendererDriverHolder[0] = val; }
                public int getRendererFilterMode() { return rendererFilterHolder[0]; }
                public void setRendererFilterMode(int val) { rendererFilterHolder[0] = val; }
                public boolean getRendererSwapRB() { return rendererSwapRBHolder[0]; }
                public void setRendererSwapRB(boolean val) { rendererSwapRBHolder[0] = val; }
            }, rendererNativeHolder[0]).show());
        }

        final Spinner sAudioDriver = findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(
                sAudioDriver,
                shortcut.getAudioDriver());
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
            public void onNothingSelected(AdapterView<?> parent) {
            }
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
            String wineVersionSelected = shortcut.container.getWineVersion();
            WineInfo wi = WineInfo.fromIdentifier(context, contentsManager, wineVersionSelected);
            loadBox64VersionSpinner(context, contentsManager, sBox64Version, wi.isArm64EC());
        };
        Runnable refreshFEXCore = () -> FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion,
                sFEXCoreVersion.getSelectedItem() != null ? sFEXCoreVersion.getSelectedItem().toString() : DefaultVersion.FEXCORE);
        btBox64VersionRemove.setOnClickListener(v -> removeSelectedContent(
                Collections.singletonList(getBox64LikeContentType(context)),
                () -> sBox64Version.getSelectedItem() != null ? sBox64Version.getSelectedItem().toString() : "",
                refreshBox64));
        btBox64VersionDownload.setOnClickListener(v -> showInstallChoicePopup(v,
                Collections.singletonList(getBox64LikeContentType(context)), refreshBox64));
        btFEXCoreVersionRemove.setOnClickListener(v -> removeSelectedContent(
                Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE),
                () -> sFEXCoreVersion.getSelectedItem() != null ? sFEXCoreVersion.getSelectedItem().toString() : "",
                refreshFEXCore));
        btFEXCoreVersionDownload.setOnClickListener(v -> showInstallChoicePopup(v,
                Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE), refreshFEXCore));

        final Spinner sFEXCorePreset = findViewById(R.id.SFEXCorePreset);
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset()));

        final Spinner sControlsProfile = findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, shortcut.getExtra("controlsProfile", "0"));

        final CheckBox cbDisabledXInput = findViewById(R.id.CBDisabledXInput);
        boolean isXInputDisabled = shortcut.getExtra("disableXinput", "0").equals("1");
        cbDisabledXInput.setChecked(isXInputDisabled);

        final CheckBox cbEnableRelativeMouse = findViewById(R.id.CBEnableRelativeMouse);
        cbEnableRelativeMouse.setChecked(shortcut.getExtra("enableRelativeMouse").equals("1"));

        final CheckBox cbDisableMouseShortcut = findViewById(R.id.CBDisableMouse);
        cbDisableMouseShortcut.setChecked(shortcut.getExtra("disableMouse").equals("1"));

        final CheckBox cbSimTouchScreen = findViewById(R.id.CBTouchscreenMode);
        String isTouchScreenMode = shortcut.getExtra("simTouchScreen");
        cbSimTouchScreen.setChecked(isTouchScreenMode.equals("1") ? true : false);

        ContainerDetailFragment.createWinComponentsTabFromShortcut(this, getContentView(),
                shortcut.getExtra("wincomponents", shortcut.container.getWinComponents()), isDarkMode);
        com.winlator.cmod.widget.CodecsSettingsView.attach(getContentView(),
                shortcut.getBuiltinDecoder(), shortcut.getSoftwareDecoding());

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
        sGraphicsDriver.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, sGraphicsItemsList));
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
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        sbSharpnessDenoise.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessDenoise", "100")));
        tvSharpnessDenoise.setText(shortcut.getExtra("sharpnessDenoise", "100") + "%");
        sbSharpnessDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessDenoise.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        final CPUListView cpuListView = findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(shortcut.getExtra("cpuList", shortcut.container.getCPUList(true)));

        final CheckBox cbSyncCpuTopology = findViewById(R.id.CBSyncCpuTopology);
        boolean syncCpuTopology = shortcut.getExtra("syncCpuTopology",
                shortcut.container.isSyncCpuTopology() ? "1" : "").equals("1");
        cbSyncCpuTopology.setChecked(syncCpuTopology);

        setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            boolean nameChanged = !shortcut.name.equals(name) && !name.isEmpty();

            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
            String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
            String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
            String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
            String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
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

            shortcut.putExtra("enableRelativeMouse", cbEnableRelativeMouse.isChecked() ? "1" : null);
            shortcut.putExtra("disableMouse", cbDisableMouseShortcut.isChecked() ? "1" : null);

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
            shortcut.setRendererNative(rendererNativeHolder[0]);
            shortcut.setUseDisplayX(useDisplayXHolder[0]);
            shortcut.setRendererPresentMode(rendererPresentModeHolder[0]);
            shortcut.setRendererDriverId(rendererDriverHolder[0]);
            shortcut.setRendererFilterMode(rendererFilterHolder[0]);
            shortcut.setRendererSwapRB(rendererSwapRBHolder[0]);
            shortcut.putExtra("emulator", emulator);
            shortcut.putExtra("midiSoundFont", midiSoundFont);
            shortcut.putExtra("lc_all", lc_all);

            shortcut.putExtra("fullscreenStretched", cbFullscreenStretched.isChecked() ? "1" : null);

            String wincomponents = containerDetailFragment.getWinComponents(getContentView());
            shortcut.putExtra("wincomponents", wincomponents);
            shortcut.setBuiltinDecoder(com.winlator.cmod.widget.CodecsSettingsView.decoder(getContentView()));
            shortcut.setSoftwareDecoding(com.winlator.cmod.widget.CodecsSettingsView.software(getContentView()));

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

            boolean syncCpuTopologyChecked = cbSyncCpuTopology.isChecked();
            shortcut.putExtra("syncCpuTopology",
                    syncCpuTopologyChecked != shortcut.container.isSyncCpuTopology()
                            ? (syncCpuTopologyChecked ? "1" : "0") : null);

            shortcut.saveData();

            if (nameChanged) {
                renameShortcut(name);
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
                text.equalsIgnoreCase("System") ||
                text.equalsIgnoreCase("vkBasalt");
    }

    public void onWinComponentsViewsAdded(boolean isDarkMode) {
        ViewGroup llContent = findViewById(R.id.LLContent);
        applyFieldSetLabelStylesDynamically(llContent, isDarkMode);
    }

    public static void loadScreenSizeSpinner(View view, String selectedValue, boolean isDarkMode) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);
        final Context context = view.getContext();

        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenWidth), isDarkMode);
        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenHeight), isDarkMode);

        ArrayList<String> items = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.screen_size_entries)));
        sScreenSize.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items));

        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String normalizedScreenSize = selectedValue.replaceAll("\\s*\\(.*\\)$", "").trim();
            String[] screenSize = normalizedScreenSize.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    private void applyDynamicStyles(View view, boolean isDarkMode) {
        applyDarkThemeToFormFields(view, isDarkMode);
        Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);
        Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);
        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        Spinner sEmulatorSpinner = view.findViewById(R.id.SEmulator);
        Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        Spinner sControlsProfile = view.findViewById(R.id.SControlsProfile);
        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        Spinner sBox64Version = view.findViewById(R.id.SBox64Version);
        Spinner sFEXCoreVersion = view.findViewById(R.id.SFEXCoreVersion);
        Spinner sFEXCorePreset = view.findViewById(R.id.SFEXCorePreset);
        Spinner sStartupSelection = findViewById(R.id.SStartupSelection);
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
        applyDarkThemeToFormFields(view, isDarkMode);

    }

    private void applyDarkThemeToFormFields(View view, boolean isDarkMode) {
        if (view instanceof EditText) {
            applyDarkThemeToEditText((EditText) view, isDarkMode);
        } else if (view instanceof TextView && isFieldSetLabel(((TextView) view).getText().toString())) {
            applyFieldSetLabelStyle((TextView) view, isDarkMode);
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                applyDarkThemeToFormFields(viewGroup.getChildAt(i), isDarkMode);
            }
        }
    }

    private int dp(float value) {
        return (int) (value * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        if (isDarkMode) {
            textView.setTextColor(Color.parseColor("#9699A3"));
            textView.setBackgroundColor(Color.TRANSPARENT);
        } else {
            textView.setTextColor(Color.parseColor("#6F727B"));
            textView.setBackgroundColor(Color.TRANSPARENT);
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
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
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

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
    }

    private void showInputWarning() {
        final Context context = fragment.getContext();
        ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
    }

    public static void loadBox64VersionSpinner(Context context, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        List<String> itemList;
        if (isArm64EC)
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.wowbox64_version_entries)));
        else
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.box64_version_entries)));
        if (!isArm64EC) {
            for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                itemList.add(entryName.substring(firstDashIndex + 1));
            }
        } else {
            for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                itemList.add(entryName.substring(firstDashIndex + 1));
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }

    private ContentProfile.ContentType getBox64LikeContentType(Context context) {
        WineInfo wi = WineInfo.fromIdentifier(context, contentsManager, shortcut.container.getWineVersion());
        return wi.isArm64EC() ? ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 : ContentProfile.ContentType.CONTENT_TYPE_BOX64;
    }

    @Override
    public void showInstallChoicePopup(View anchor, List<ContentProfile.ContentType> types, Runnable refreshAction) {
        showInstallChoiceMenu(anchor,
                () -> handleInstallChoice(0, types, refreshAction),
                () -> handleInstallChoice(1, types, refreshAction));
    }

    private void showInstallChoiceMenu(View anchor, Runnable openAction, Runnable downloadAction) {
        int width = dp(170);
        LinearLayout menu = new LinearLayout(getContext());
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(4), 0, dp(4));
        menu.setBackgroundResource(R.drawable.install_choice_popup_background);
        PopupWindow popupWindow = new PopupWindow(menu, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setOutsideTouchable(true);
        popupWindow.setElevation(dp(8));
        addInstallChoiceMenuRow(popupWindow, menu, R.drawable.icon_popup_menu_open, getContext().getString(R.string.open_file), openAction);
        addInstallChoiceMenuRow(popupWindow, menu, R.drawable.icon_popup_menu_download, getContext().getString(R.string.download_file), downloadAction);
        popupWindow.showAsDropDown(anchor, -width + anchor.getWidth(), dp(2));
    }

    private void addInstallChoiceMenuRow(PopupWindow popupWindow, LinearLayout menu, int iconResId, String text, Runnable action) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconResId);
        icon.setColorFilter(Color.parseColor("#0055ff"));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconParams);
        TextView titleView = new TextView(getContext());
        titleView.setText(text);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setSingleLine(true);
        row.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            popupWindow.dismiss();
            action.run();
        });
        menu.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
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

    @Override
    public void removeSelectedContent(List<ContentProfile.ContentType> types, Supplier<String> selectedValue, Runnable refreshAction) {
        ContentProfile profile = resolveProfile(types, selectedValue.get());
        if (profile == null || profile.remoteUrl != null) {
            Toast.makeText(getContext(), R.string.no_items_to_display, Toast.LENGTH_SHORT).show();
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
            for (ContentProfile.ContentType type : types) {
                for (ContentProfile profile : contentsManager.getProfiles(type)) {
                    if (profile.remoteUrl != null) candidates.add(profile);
                }
            }
            fragment.requireActivity().runOnUiThread(() -> {
                dialog.closeOnUiThread();
                if (candidates.isEmpty()) {
                    AppUtils.showToast(getContext(), R.string.no_items_to_display);
                    return;
                }
                String[] entries = new String[candidates.size()];
                for (int i = 0; i < candidates.size(); i++) entries[i] = candidates.get(i).verName;
                ContentDialog.showSingleChoiceList(fragment.requireContext(), R.string.install_content, entries, idx -> {
                    if (idx < 0 || idx >= candidates.size()) return;
                    downloadAndInstallProfile(candidates.get(idx), refreshAction);
                });
            });
        });
    }

    private void downloadAndInstallProfile(ContentProfile profile, Runnable refreshAction) {
        PreloaderDialog dialog = new PreloaderDialog(fragment.requireActivity());
        dialog.showOnUiThread(R.string.downloading_file);
        CONTENT_IO_EXECUTOR.execute(() -> {
            File output = new File(getContext().getCacheDir(), "content_" + System.currentTimeMillis());
            if (!Downloader.downloadFile(profile.remoteUrl, output)) {
                fragment.requireActivity().runOnUiThread(() -> {
                    dialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), R.string.unable_to_download_file);
                });
                return;
            }
            installImportedContent(Uri.fromFile(output), Collections.singletonList(profile.type), refreshAction, dialog);
        });
    }

    private void installImportedContent(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction) {
        installImportedContent(uri, expectedTypes, refreshAction, null);
    }

    private void installImportedContent(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction, @Nullable PreloaderDialog existingDialog) {
        PreloaderDialog dialog = existingDialog != null ? existingDialog : new PreloaderDialog(fragment.requireActivity());
        if (existingDialog == null) dialog.showOnUiThread(R.string.installing_content);
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
                    public void onSucceed(ContentProfile installedProfile) {
                        contentsManager.syncContents();
                        fragment.requireActivity().runOnUiThread(() -> {
                            dialog.closeOnUiThread();
                            refreshAction.run();
                        });
                    }
                });
            }
        }));
    }

    private void forceShowMenuIcons(PopupMenu popupMenu) {
        try {
            Field field = PopupMenu.class.getDeclaredField("mPopup");
            field.setAccessible(true);
            Object menuHelper = field.get(popupMenu);
            menuHelper.getClass().getDeclaredMethod("setForceShowIcon", boolean.class).invoke(menuHelper, true);
        } catch (Exception ignored) {
        }
    }

    private void setupDXWrapperSpinnerWithDialogHost(final Spinner sDXWrapper, final View vDXWrapperConfig, boolean isARM64EC) {
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.startsWith("dxvk"))
                    vDXWrapperConfig.setOnClickListener(v -> new DXVKConfigDialog(vDXWrapperConfig, isARM64EC, ShortcutSettingsDialog.this, contentsManager).show());
                else if (dxwrapper.equals("wined3d"))
                    vDXWrapperConfig.setOnClickListener(v -> new WineD3DConfigDialog(vDXWrapperConfig).show());
                vDXWrapperConfig.setVisibility(View.VISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        sDXWrapper.setOnItemSelectedListener(listener);
        int selectedPosition = sDXWrapper.getSelectedItemPosition();
        if (selectedPosition >= 0 && selectedPosition < sDXWrapper.getCount()) {
            listener.onItemSelected(sDXWrapper, sDXWrapper.getSelectedView(), selectedPosition, sDXWrapper.getSelectedItemId());
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
            sDXWrapper.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items.toArray(new String[0])));
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
}

