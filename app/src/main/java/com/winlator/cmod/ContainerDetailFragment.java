package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
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
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.AddEnvVarDialog;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.contentdialog.WineD3DConfigDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.ColorPickerView;
import com.winlator.cmod.widget.EnvVarsView;
import com.winlator.cmod.widget.ImagePickerView;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.XKeycode;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes8.dex */
public class ContainerDetailFragment extends Fragment {
    private static final String TAG = "FileUtils";
    private static Container container;
    private static boolean isDarkMode;
    private final int containerId;
    private ContentsManager contentsManager;
    private JSONArray gpuCards;
    private ImageFs imageFs;
    private ContainerManager manager;
    private Callback<String> openDirectoryCallback;
    private PreloaderDialog preloaderDialog;

    public ContainerDetailFragment() {
        this(0);
    }

    public ContainerDetailFragment(int containerId) {
        this.containerId = containerId;
    }

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        this.preloaderDialog = new PreloaderDialog(getActivity());
        try {
            this.gpuCards = new JSONArray(FileUtils.readString(getContext(), "gpu_cards.json"));
        } catch (JSONException e) {
        }
    }

    private static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode2) {
        if (isDarkMode2) {
            textView.setTextColor(Color.parseColor("#cccccc"));
            textView.setBackgroundResource(com.ludashi.benchmark.R.color.window_background_color_dark);
        } else {
            textView.setTextColor(Color.parseColor("#bdbdbd"));
            textView.setBackgroundResource(com.ludashi.benchmark.R.color.window_background_color);
        }
    }

    private void applyDynamicStyles(View view, boolean isDarkMode2) {
        Spinner sScreenSize = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SScreenSize);
        sScreenSize.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sWineVersion = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SWineVersion);
        sWineVersion.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sGraphicsDriver = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SGraphicsDriver);
        sGraphicsDriver.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sDXWrapper = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDXWrapper);
        sDXWrapper.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sAudioDriver = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SAudioDriver);
        sAudioDriver.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sEmulator64 = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SEmulator64);
        sEmulator64.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sEmulator = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SEmulator);
        sEmulator.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sMIDISoundFont = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SMIDISoundFont);
        sMIDISoundFont.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sDesktopTheme = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDesktopTheme);
        sDesktopTheme.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sDesktopBackgroundType = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDesktopBackgroundType);
        sDesktopBackgroundType.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sMouseWarpOverride = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SMouseWarpOverride);
        sMouseWarpOverride.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sBox64Preset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBox64Preset);
        sBox64Preset.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sBox64Version = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBox64Version);
        sBox64Version.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sFEXCoreVersion = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SFEXCoreVersion);
        sFEXCoreVersion.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sFEXCorePreset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SFEXCorePreset);
        sFEXCorePreset.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sStartupSelection = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SStartupSelection);
        sStartupSelection.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
    }

    private void applyDynamicStylesRecursively(View view, boolean isDarkMode2) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                applyDynamicStylesRecursively(child, isDarkMode2);
            }
            return;
        }
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if ("desktop".equals(textView.getText().toString())) {
                textView.setTextAppearance(getContext(), isDarkMode2 ? 2131820801 : com.ludashi.benchmark.R.style.FieldSetLabel);
            }
        }
    }

    @Override // androidx.fragment.app.Fragment
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 4 && resultCode == -1) {
            if (data != null) {
                Uri uri = data.getData();
                Log.d(TAG, "URI obtained in onActivityResult: " + uri.toString());
                String path = FileUtils.getFilePathFromUri(getContext(), uri);
                Log.d(TAG, "File path in onActivityResult: " + path);
                if (path != null) {
                    if (this.openDirectoryCallback != null) {
                        this.openDirectoryCallback.call(path);
                    }
                } else {
                    Toast.makeText(getContext(), "Invalid directory selected", 0).show();
                }
            }
            this.openDirectoryCallback = null;
        }
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(isEditMode() ? com.ludashi.benchmark.R.string.edit_container : com.ludashi.benchmark.R.string.new_container);
        TextView desktopLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVDesktop);
        applyFieldSetLabelStyle(desktopLabel, isDarkMode);
        TextView registryKeysLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVDirectInput);
        applyFieldSetLabelStyle(registryKeysLabel, isDarkMode);
        TextView directXLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVDirectX);
        applyFieldSetLabelStyle(directXLabel, isDarkMode);
        TextView generalLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVGeneral);
        applyFieldSetLabelStyle(generalLabel, isDarkMode);
        TextView box64Label = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVBox64);
        applyFieldSetLabelStyle(box64Label, isDarkMode);
        TextView fexCoreLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVFEXCore);
        applyFieldSetLabelStyle(fexCoreLabel, isDarkMode);
        TextView systemLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVSystem);
        applyFieldSetLabelStyle(systemLabel, isDarkMode);
        TextView gameControllerLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVGameController);
        applyFieldSetLabelStyle(gameControllerLabel, isDarkMode);
    }

    public boolean isEditMode() {
        return container != null;
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup root, Bundle savedInstanceState) {
        CheckBox cbEnableXInput;
        String str;
        Spinner sStartupSelection;
        View btHelpDInput;
        String string;
        Spinner sFEXCoreVersion;
        String string2;
        String selectedDriver;
        List<String> sGraphicsItemsList;
        String selectedDriver2;
        int i;
        String fallbackCPUListWoW64;
        final Context context = getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final View view = inflater.inflate(com.ludashi.benchmark.R.layout.container_detail_fragment, root, false);
        isDarkMode = preferences.getBoolean("dark_mode", true);
        applyDynamicStyles(view, isDarkMode);
        this.manager = new ContainerManager(context);
        container = this.containerId > 0 ? this.manager.getContainerById(this.containerId) : null;
        this.contentsManager = new ContentsManager(context);
        this.contentsManager.syncContents();
        final EditText etName = (EditText) view.findViewById(com.ludashi.benchmark.R.id.ETName);
        final Spinner sWineVersion = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SWineVersion);
        LinearLayout llWineVersion = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLWineVersion);
        llWineVersion.setVisibility(0);
        if (!isEditMode()) {
            etName.setText(getString(com.ludashi.benchmark.R.string.container) + "-" + this.manager.getNextContainerId());
        } else {
            etName.setText(container.getName());
        }
        final Spinner sBox64Version = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBox64Version);
        loadWineVersionSpinner(view, sWineVersion, sBox64Version);
        loadScreenSizeSpinner(view, isEditMode() ? container.getScreenSize() : Container.DEFAULT_SCREEN_SIZE);
        final Spinner sGraphicsDriver = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SGraphicsDriver);
        final Spinner sDXWrapper = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDXWrapper);
        final View vDXWrapperConfig = view.findViewById(com.ludashi.benchmark.R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(isEditMode() ? container.getDXWrapperConfig() : Container.DEFAULT_DXWRAPPERCONFIG);
        final View vGraphicsDriverConfig = view.findViewById(com.ludashi.benchmark.R.id.BTGraphicsDriverConfig);
        vGraphicsDriverConfig.setTag(isEditMode() ? container.getGraphicsDriverConfig() : Container.DEFAULT_GRAPHICSDRIVERCONFIG);
        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig, isEditMode() ? container.getGraphicsDriver() : Container.DEFAULT_GRAPHICS_DRIVER, isEditMode() ? container.getDXWrapper() : Container.DEFAULT_DXWRAPPER);
        view.findViewById(com.ludashi.benchmark.R.id.BTHelpDXWrapper).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda14
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                AppUtils.showHelpBox(context, view2, com.ludashi.benchmark.R.string.dxwrapper_help_content);
            }
        });
        final Spinner sAudioDriver = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, isEditMode() ? container.getAudioDriver() : Container.DEFAULT_AUDIO_DRIVER);
        final Spinner sEmulator = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator, isEditMode() ? container.getEmulator() : Container.DEFAULT_EMULATOR);
        final Spinner sMIDISoundFont = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, isEditMode() ? container.getMIDISoundFont() : "");
        final CheckBox cbShowFPS = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBShowFPS);
        cbShowFPS.setChecked(!isEditMode() || container.isShowFPS());
        final CheckBox cbFullscreenStretched = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBFullscreenStretched);
        cbFullscreenStretched.setChecked(isEditMode() && container.isFullscreenStretched());
        new Runnable() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda15
            @Override // java.lang.Runnable
            public final void run() {
                ContentDialog.alert(context, com.ludashi.benchmark.R.string.enable_xinput_and_dinput_same_time, (Runnable) null);
            }
        };
        final CheckBox cbEnableXInput2 = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableDInput);
        final CheckBox cbExclusiveXInput = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBExclusiveXInput);
        View btHelpXInput = view.findViewById(com.ludashi.benchmark.R.id.BTXInputHelp);
        View btHelpDInput2 = view.findViewById(com.ludashi.benchmark.R.id.BTDInputHelp);
        View btHelpExclusiveXInput = view.findViewById(com.ludashi.benchmark.R.id.BTExclusiveXInputHelp);
        int inputType = isEditMode() ? container.getInputType() : 4;
        cbEnableXInput2.setChecked((inputType & 4) == 4);
        cbEnableDInput.setChecked((inputType & 8) == 8);
        cbExclusiveXInput.setChecked(isEditMode() ? container.isExclusiveXInput() : true);
        cbEnableDInput.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda16
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                ContainerDetailFragment.lambda$onCreateView$2(cbExclusiveXInput, cbEnableXInput2, compoundButton, z);
            }
        });
        cbEnableXInput2.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda17
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                ContainerDetailFragment.lambda$onCreateView$3(cbExclusiveXInput, cbEnableDInput, compoundButton, z);
            }
        });
        cbExclusiveXInput.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda18
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                ContainerDetailFragment.lambda$onCreateView$4(cbEnableXInput2, cbEnableDInput, compoundButton, z);
            }
        });
        if (!cbExclusiveXInput.isChecked()) {
            cbEnableXInput2.setChecked(true);
            cbEnableDInput.setChecked(true);
            cbEnableXInput2.setEnabled(false);
            cbEnableDInput.setEnabled(false);
        }
        btHelpXInput.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda19
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                AppUtils.showHelpBox(context, view2, com.ludashi.benchmark.R.string.help_xinput);
            }
        });
        btHelpDInput2.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                AppUtils.showHelpBox(context, view2, com.ludashi.benchmark.R.string.help_dinput);
            }
        });
        btHelpExclusiveXInput.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                AppUtils.showHelpBox(context, view2, com.ludashi.benchmark.R.string.help_exclusive_xinput);
            }
        });
        final EditText etLC_ALL = (EditText) view.findViewById(com.ludashi.benchmark.R.id.ETlcall);
        Locale systemLocal = Locale.getDefault();
        if (isEditMode()) {
            str = container.getLC_ALL();
            cbEnableXInput = cbEnableXInput2;
        } else {
            cbEnableXInput = cbEnableXInput2;
            str = systemLocal.getLanguage() + '_' + systemLocal.getCountry() + ".UTF-8";
        }
        etLC_ALL.setText(str);
        View btShowLCALL = view.findViewById(com.ludashi.benchmark.R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                ContainerDetailFragment.this.lambda$onCreateView$9(context, etLC_ALL, view2);
            }
        });
        Spinner sStartupSelection2 = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SStartupSelection);
        byte previousStartupSelection = isEditMode() ? container.getStartupSelection() : (byte) -1;
        sStartupSelection2.setSelection(previousStartupSelection != -1 ? previousStartupSelection : (byte) 1);
        final Spinner sBox64Preset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBox64Preset);
        if (isEditMode()) {
            btHelpDInput = btHelpDInput2;
            sStartupSelection = sStartupSelection2;
            string = container.getBox64Preset();
        } else {
            sStartupSelection = sStartupSelection2;
            btHelpDInput = btHelpDInput2;
            string = preferences.getString("box64_preset", "COMPATIBILITY");
        }
        Box64PresetManager.loadSpinner("box64", sBox64Preset, string);
        Spinner sFEXCoreVersion2 = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, this.contentsManager, sFEXCoreVersion2, isEditMode() ? container.getFEXCoreVersion() : DefaultVersion.FEXCORE);
        final Spinner sFEXCorePreset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SFEXCorePreset);
        if (isEditMode()) {
            string2 = container.getFEXCorePreset();
            sFEXCoreVersion = sFEXCoreVersion2;
        } else {
            sFEXCoreVersion = sFEXCoreVersion2;
            string2 = preferences.getString("fexcore_preset", "INTERMEDIATE");
        }
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, string2);
        String selectedDriver3 = sGraphicsDriver.getSelectedItem().toString();
        List<String> sGraphicsItemsList2 = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(com.ludashi.benchmark.R.array.graphics_driver_entries)));
        sGraphicsDriver.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sGraphicsItemsList2));
        AppUtils.setSpinnerSelectionFromValue(sGraphicsDriver, selectedDriver3);
        final CPUListView cpuListView = (CPUListView) view.findViewById(com.ludashi.benchmark.R.id.CPUListView);
        final CPUListView cpuListViewWoW64 = (CPUListView) view.findViewById(com.ludashi.benchmark.R.id.CPUListViewWoW64);
        if (isEditMode()) {
            selectedDriver = selectedDriver3;
            sGraphicsItemsList = sGraphicsItemsList2;
            selectedDriver2 = container.getCPUList(true);
        } else {
            selectedDriver = selectedDriver3;
            sGraphicsItemsList = sGraphicsItemsList2;
            selectedDriver2 = Container.getFallbackCPUList();
        }
        cpuListView.setCheckedCPUList(selectedDriver2);
        if (isEditMode()) {
            i = 1;
            fallbackCPUListWoW64 = container.getCPUListWoW64(true);
        } else {
            i = 1;
            fallbackCPUListWoW64 = Container.getFallbackCPUListWoW64();
        }
        cpuListViewWoW64.setCheckedCPUList(fallbackCPUListWoW64);
        final Spinner sPrimaryController = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SPrimaryController);
        if (isEditMode()) {
            i = container.getPrimaryController();
        }
        sPrimaryController.setSelection(i);
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SButtonA), Container.XrControllerMapping.BUTTON_A, XKeycode.KEY_A.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SButtonB), Container.XrControllerMapping.BUTTON_B, XKeycode.KEY_B.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SButtonX), Container.XrControllerMapping.BUTTON_X, XKeycode.KEY_X.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SButtonY), Container.XrControllerMapping.BUTTON_Y, XKeycode.KEY_Y.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SButtonGrip), Container.XrControllerMapping.BUTTON_GRIP, XKeycode.KEY_SPACE.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SButtonTrigger), Container.XrControllerMapping.BUTTON_TRIGGER, XKeycode.KEY_ENTER.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SThumbstickUp), Container.XrControllerMapping.THUMBSTICK_UP, XKeycode.KEY_UP.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SThumbstickDown), Container.XrControllerMapping.THUMBSTICK_DOWN, XKeycode.KEY_DOWN.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SThumbstickLeft), Container.XrControllerMapping.THUMBSTICK_LEFT, XKeycode.KEY_LEFT.ordinal());
        setControllerMapping((Spinner) view.findViewById(com.ludashi.benchmark.R.id.SThumbstickRight), Container.XrControllerMapping.THUMBSTICK_RIGHT, XKeycode.KEY_RIGHT.ordinal());
        createWineConfigurationTab(view);
        final EnvVarsView envVarsView = createEnvVarsTab(view);
        createWinComponentsTab(view, isEditMode() ? container.getWinComponents() : Container.DEFAULT_WINCOMPONENTS);
        createDrivesTab(view);
        AppUtils.setupTabLayout(view, com.ludashi.benchmark.R.id.TabLayout, com.ludashi.benchmark.R.id.LLTabWineConfiguration, com.ludashi.benchmark.R.id.LLTabWinComponents, com.ludashi.benchmark.R.id.LLTabEnvVars, com.ludashi.benchmark.R.id.LLTabDrives, com.ludashi.benchmark.R.id.LLTabAdvanced, com.ludashi.benchmark.R.id.LLTabXR);
        TabLayout tabLayout = (TabLayout) view.findViewById(com.ludashi.benchmark.R.id.TabLayout);
        if (isDarkMode) {
            tabLayout.setBackgroundResource(com.ludashi.benchmark.R.drawable.tab_layout_background_dark);
        } else {
            tabLayout.setBackgroundResource(com.ludashi.benchmark.R.drawable.tab_layout_background);
        }
        final CheckBox cbEnableXInput3 = cbEnableXInput;
        final Spinner sStartupSelection3 = sStartupSelection;
        final Spinner sFEXCoreVersion3 = sFEXCoreVersion;
        view.findViewById(com.ludashi.benchmark.R.id.BTConfirm).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                ContainerDetailFragment.this.lambda$onCreateView$11(etName, view, envVarsView, sGraphicsDriver, vGraphicsDriverConfig, context, sDXWrapper, vDXWrapperConfig, sAudioDriver, sEmulator, cbShowFPS, cbFullscreenStretched, cbExclusiveXInput, cpuListView, cpuListViewWoW64, sStartupSelection3, sBox64Version, sFEXCoreVersion3, sFEXCorePreset, sBox64Preset, sMIDISoundFont, etLC_ALL, sPrimaryController, cbEnableXInput3, cbEnableDInput, sWineVersion, view2);
            }
        });
        return view;
    }

    static /* synthetic */ void lambda$onCreateView$2(CheckBox cbExclusiveXInput, CheckBox cbEnableXInput, CompoundButton buttonView, boolean isChecked) {
        if (cbExclusiveXInput.isChecked() && isChecked && cbEnableXInput.isChecked()) {
            cbEnableXInput.setChecked(false);
        }
    }

    static /* synthetic */ void lambda$onCreateView$3(CheckBox cbExclusiveXInput, CheckBox cbEnableDInput, CompoundButton buttonView, boolean isChecked) {
        if (cbExclusiveXInput.isChecked() && isChecked && cbEnableDInput.isChecked()) {
            cbEnableDInput.setChecked(false);
        }
    }

    static /* synthetic */ void lambda$onCreateView$4(CheckBox cbEnableXInput, CheckBox cbEnableDInput, CompoundButton buttonView, boolean isChecked) {
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

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$9(Context context, final EditText etLC_ALL, View v) {
        PopupMenu popupMenu = new PopupMenu(context, v);
        String[] lcs = getResources().getStringArray(com.ludashi.benchmark.R.array.some_lc_all);
        for (int i = 0; i < lcs.length; i++) {
            popupMenu.getMenu().add(0, i, 0, lcs[i]);
        }
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda5
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                return ContainerDetailFragment.lambda$onCreateView$8(etLC_ALL, menuItem);
            }
        });
        popupMenu.show();
    }

    static /* synthetic */ boolean lambda$onCreateView$8(EditText etLC_ALL, MenuItem item) {
        etLC_ALL.setText(item.toString() + ".UTF-8");
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$11(EditText etName, final View view, EnvVarsView envVarsView, Spinner sGraphicsDriver, View vGraphicsDriverConfig, Context context, Spinner sDXWrapper, View vDXWrapperConfig, Spinner sAudioDriver, Spinner sEmulator, CheckBox cbShowFPS, CheckBox cbFullscreenStretched, CheckBox cbExclusiveXInput, CPUListView cpuListView, CPUListView cpuListViewWoW64, Spinner sStartupSelection, Spinner sBox64Version, Spinner sFEXCoreVersion, Spinner sFEXCorePreset, Spinner sBox64Preset, Spinner sMIDISoundFont, EditText etLC_ALL, Spinner sPrimaryController, CheckBox cbEnableXInput, CheckBox cbEnableDInput, Spinner sWineVersion, View v) {
        String str = DefaultVersion.WRAPPER_ADRENO;
        try {
            String name = etName.getText().toString();
            String screenSize = getScreenSize(view);
            String envVars = envVarsView.getEnvVars();
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
            String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
            if (config.get("version").isEmpty()) {
                if (!GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)) {
                    str = DefaultVersion.WRAPPER;
                }
                config.put("version", str);
                graphicsDriverConfig = GraphicsDriverConfigDialog.toGraphicsDriverConfig(config);
            }
            String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
            String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
            String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
            String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
            String wincomponents = getWinComponents(view);
            String drives = getDrives(view);
            boolean showFPS = cbShowFPS.isChecked();
            boolean fullscreenStretched = cbFullscreenStretched.isChecked();
            boolean exclusiveXInput = cbExclusiveXInput.isChecked();
            String cpuList = cpuListView.getCheckedCPUListAsString();
            String cpuListWoW64 = cpuListViewWoW64.getCheckedCPUListAsString();
            byte startupSelection = (byte) sStartupSelection.getSelectedItemPosition();
            String box64Version = sBox64Version.getSelectedItem().toString();
            String fexcoreVersion = sFEXCoreVersion.getSelectedItem().toString();
            String fexcorePreset = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
            String box64Preset = Box64PresetManager.getSpinnerSelectedId(sBox64Preset);
            String desktopTheme = getDesktopTheme(view);
            String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
            String lc_all = etLC_ALL.getText().toString();
            int primaryController = sPrimaryController.getSelectedItemPosition();
            String controllerMapping = getControllerMapping(view);
            int finalInputType = 0 | (cbEnableXInput.isChecked() ? 4 : 0) | (cbEnableDInput.isChecked() ? 8 : 0);
            if (isEditMode()) {
                try {
                    container.setName(name);
                    container.setScreenSize(screenSize);
                    container.setEnvVars(envVars);
                    container.setCPUList(cpuList);
                    container.setCPUListWoW64(cpuListWoW64);
                    container.setGraphicsDriver(graphicsDriver);
                    container.setGraphicsDriverConfig(graphicsDriverConfig);
                    container.setDXWrapper(dxwrapper);
                    container.setDXWrapperConfig(dxwrapperConfig);
                    container.setAudioDriver(audioDriver);
                    container.setEmulator(emulator);
                    container.setWinComponents(wincomponents);
                    container.setDrives(drives);
                    container.setShowFPS(showFPS);
                    container.setFullscreenStretched(fullscreenStretched);
                    container.setExclusiveXInput(exclusiveXInput);
                    container.setInputType(finalInputType);
                    container.setStartupSelection(startupSelection);
                    container.setBox64Version(box64Version);
                    container.setBox64Preset(box64Preset);
                    container.setFEXCoreVersion(fexcoreVersion);
                    container.setFEXCorePreset(fexcorePreset);
                    container.setDesktopTheme(desktopTheme);
                    container.setMidiSoundFont(midiSoundFont);
                    container.setLC_ALL(lc_all);
                    container.setPrimaryController(primaryController);
                    container.setControllerMapping(controllerMapping);
                    container.saveData();
                    saveWineRegistryKeys(view);
                    getActivity().onBackPressed();
                    return;
                } catch (JSONException e) {
                    e = e;
                    e.printStackTrace();
                }
            }
            JSONObject data = new JSONObject();
            try {
                data.put("name", name);
                data.put("screenSize", screenSize);
                data.put("envVars", envVars);
                data.put("cpuList", cpuList);
                data.put("cpuListWoW64", cpuListWoW64);
                data.put("graphicsDriver", graphicsDriver);
                data.put("graphicsDriverConfig", graphicsDriverConfig);
                data.put("dxwrapper", dxwrapper);
                data.put("dxwrapperConfig", dxwrapperConfig);
                data.put("audioDriver", audioDriver);
                data.put("emulator", emulator);
                data.put("wincomponents", wincomponents);
                data.put("drives", drives);
                data.put("showFPS", showFPS);
                data.put("fullscreenStretched", fullscreenStretched);
                data.put("exclusiveXInput", exclusiveXInput);
                data.put("inputType", finalInputType);
                data.put("startupSelection", (int) startupSelection);
                data.put("box64Version", box64Version);
                data.put("box64Preset", box64Preset);
                data.put("fexcoreVersion", fexcoreVersion);
                data.put("fexcorePreset", fexcorePreset);
                data.put("desktopTheme", desktopTheme);
                data.put("wineVersion", sWineVersion.getSelectedItem().toString());
                data.put("midiSoundFont", midiSoundFont);
                data.put("lc_all", lc_all);
                data.put("primaryController", primaryController);
                data.put("controllerMapping", controllerMapping);
            } catch (JSONException e2) {
                e = e2;
            }
            try {
                this.preloaderDialog.lambda$showOnUiThread$0(com.ludashi.benchmark.R.string.creating_container);
                File imageFsRoot = new File(context.getFilesDir(), "imagefs");
                this.imageFs = ImageFs.find(imageFsRoot);
            } catch (JSONException e3) {
                e = e3;
                e.printStackTrace();
            }
            try {
                this.manager.createContainerAsync(data, this.contentsManager, new Callback() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda7
                    @Override // com.winlator.cmod.core.Callback
                    public final void call(Object obj) {
                        ContainerDetailFragment.this.lambda$onCreateView$10(view, (Container) obj);
                    }
                });
            } catch (JSONException e4) {
                e = e4;
                e.printStackTrace();
            }
        } catch (JSONException e5) {
            e = e5;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$10(View view, Container container2) {
        if (container2 != null) {
            container = container2;
            saveWineRegistryKeys(view);
        }
        this.preloaderDialog.close();
        getActivity().onBackPressed();
    }

    private void saveWineRegistryKeys(View view) {
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile);
        try {
            Spinner sMouseWarpOverride = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SMouseWarpOverride);
            registryEditor.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", sMouseWarpOverride.getSelectedItem().toString().toLowerCase(Locale.ENGLISH));
            registryEditor.close();
        } catch (Throwable th) {
            try {
                registryEditor.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private void createWineConfigurationTab(View view) {
        Context context = getContext();
        WineThemeManager.ThemeInfo desktopTheme = new WineThemeManager.ThemeInfo(isEditMode() ? container.getDesktopTheme() : WineThemeManager.DEFAULT_DESKTOP_THEME);
        Spinner sDesktopTheme = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDesktopTheme);
        sDesktopTheme.setSelection(desktopTheme.theme.ordinal());
        final ImagePickerView ipvDesktopBackgroundImage = (ImagePickerView) view.findViewById(com.ludashi.benchmark.R.id.IPVDesktopBackgroundImage);
        final ColorPickerView cpvDesktopBackgroundColor = (ColorPickerView) view.findViewById(com.ludashi.benchmark.R.id.CPVDesktopBackgroundColor);
        cpvDesktopBackgroundColor.setColor(desktopTheme.backgroundColor);
        Spinner sDesktopBackgroundType = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDesktopBackgroundType);
        sDesktopBackgroundType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ContainerDetailFragment.1
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view2, int position, long id) {
                WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[position];
                ipvDesktopBackgroundImage.setVisibility(8);
                cpvDesktopBackgroundColor.setVisibility(8);
                if (type == WineThemeManager.BackgroundType.IMAGE) {
                    ipvDesktopBackgroundImage.setVisibility(0);
                } else if (type == WineThemeManager.BackgroundType.COLOR) {
                    cpvDesktopBackgroundColor.setVisibility(0);
                }
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        sDesktopBackgroundType.setSelection(desktopTheme.backgroundType.ordinal());
        File containerDir = isEditMode() ? container.getRootDir() : null;
        File userRegFile = new File(containerDir, ".wine/user.reg");
        WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile);
        try {
            List<String> mouseWarpOverrideList = Arrays.asList(context.getString(com.ludashi.benchmark.R.string.disable), context.getString(com.ludashi.benchmark.R.string.enable), context.getString(com.ludashi.benchmark.R.string.force));
            Spinner sMouseWarpOverride = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SMouseWarpOverride);
            sMouseWarpOverride.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, mouseWarpOverrideList));
            AppUtils.setSpinnerSelectionFromValue(sMouseWarpOverride, registryEditor.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", "disable"));
            registryEditor.close();
        } catch (Throwable th) {
            try {
                registryEditor.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private void loadGPUNameSpinner(Spinner spinner, int selectedDeviceID) {
        List<String> values = new ArrayList<>();
        int selectedPosition = 0;
        for (int i = 0; i < this.gpuCards.length(); i++) {
            try {
                JSONObject item = this.gpuCards.getJSONObject(i);
                if (item.getInt("deviceID") == selectedDeviceID) {
                    selectedPosition = i;
                }
                values.add(item.getString("name"));
            } catch (JSONException e) {
            }
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(getContext(), android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition);
    }

    public static String getScreenSize(View view) {
        Spinner sScreenSize = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SScreenSize);
        String value = sScreenSize.getSelectedItem().toString();
        if (value.equalsIgnoreCase("custom")) {
            value = Container.DEFAULT_SCREEN_SIZE;
            String strWidth = ((EditText) view.findViewById(com.ludashi.benchmark.R.id.ETScreenWidth)).getText().toString().trim();
            String strHeight = ((EditText) view.findViewById(com.ludashi.benchmark.R.id.ETScreenHeight)).getText().toString().trim();
            if (strWidth.matches("[0-9]+") && strHeight.matches("[0-9]+")) {
                int width = Integer.parseInt(strWidth);
                int height = Integer.parseInt(strHeight);
                if (width % 2 == 0 && height % 2 == 0) {
                    return width + "x" + height;
                }
            }
        }
        return StringUtils.parseIdentifier(value);
    }

    private String getDesktopTheme(View view) {
        Spinner sDesktopBackgroundType = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDesktopBackgroundType);
        WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[sDesktopBackgroundType.getSelectedItemPosition()];
        Spinner sDesktopTheme = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDesktopTheme);
        ColorPickerView cpvDesktopBackground = (ColorPickerView) view.findViewById(com.ludashi.benchmark.R.id.CPVDesktopBackgroundColor);
        WineThemeManager.Theme theme = WineThemeManager.Theme.values()[sDesktopTheme.getSelectedItemPosition()];
        String desktopTheme = theme + "," + type + "," + cpvDesktopBackground.getColorAsString();
        if (type == WineThemeManager.BackgroundType.IMAGE) {
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(getContext());
            return desktopTheme + "," + (userWallpaperFile.isFile() ? Long.valueOf(userWallpaperFile.lastModified()) : "0");
        }
        return desktopTheme;
    }

    public static void loadScreenSizeSpinner(View view, String selectedValue) {
        final Spinner sScreenSize = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SScreenSize);
        final LinearLayout llCustomScreenSize = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLCustomScreenSize);
        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ContainerDetailFragment.2
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
            ((EditText) view.findViewById(com.ludashi.benchmark.R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText) view.findViewById(com.ludashi.benchmark.R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, final String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();
        updateGraphicsDriverSpinner(context, sGraphicsDriver);
        final Runnable update = new Runnable() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                ContainerDetailFragment.lambda$loadGraphicsDriverSpinner$13(sGraphicsDriver, context, sDXWrapper, selectedDXWrapper, vGraphicsDriverConfig);
            }
        };
        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ContainerDetailFragment.3
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

    static /* synthetic */ void lambda$loadGraphicsDriverSpinner$13(Spinner sGraphicsDriver, Context context, Spinner sDXWrapper, String selectedDXWrapper, final View vGraphicsDriverConfig) {
        final String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
        ArrayList<String> items = new ArrayList<>();
        for (String value : context.getResources().getStringArray(com.ludashi.benchmark.R.array.dxwrapper_entries)) {
            items.add(value);
        }
        sDXWrapper.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items.toArray()));
        AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);
        vGraphicsDriverConfig.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda13
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver, null).show();
            }
        });
        vGraphicsDriverConfig.setVisibility(0);
    }

    /* renamed from: com.winlator.cmod.ContainerDetailFragment$4, reason: invalid class name */
    class AnonymousClass4 implements AdapterView.OnItemSelectedListener {
        final /* synthetic */ boolean val$isARM64EC;
        final /* synthetic */ Spinner val$sDXWrapper;
        final /* synthetic */ View val$vDXWrapperConfig;

        AnonymousClass4(Spinner spinner, View view, boolean z) {
            this.val$sDXWrapper = spinner;
            this.val$vDXWrapperConfig = view;
            this.val$isARM64EC = z;
        }

        @Override // android.widget.AdapterView.OnItemSelectedListener
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String dxwrapper = StringUtils.parseIdentifier(this.val$sDXWrapper.getSelectedItem());
            if (dxwrapper.contains("dxvk")) {
                View view2 = this.val$vDXWrapperConfig;
                final View view3 = this.val$vDXWrapperConfig;
                final boolean z = this.val$isARM64EC;
                view2.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$4$$ExternalSyntheticLambda0
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view4) {
                        new DXVKConfigDialog(view3, z).show();
                    }
                });
            } else {
                View view4 = this.val$vDXWrapperConfig;
                final View view5 = this.val$vDXWrapperConfig;
                view4.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$4$$ExternalSyntheticLambda1
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view6) {
                        new WineD3DConfigDialog(view5).show();
                    }
                });
            }
            this.val$vDXWrapperConfig.setVisibility(0);
        }

        @Override // android.widget.AdapterView.OnItemSelectedListener
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    public static void setupDXWrapperSpinner(Spinner sDXWrapper, View vDXWrapperConfig, boolean isARM64EC) {
        AdapterView.OnItemSelectedListener listener = new AnonymousClass4(sDXWrapper, vDXWrapperConfig, isARM64EC);
        sDXWrapper.setOnItemSelectedListener(listener);
        int selectedPosition = sDXWrapper.getSelectedItemPosition();
        if (selectedPosition >= 0) {
            listener.onItemSelected(sDXWrapper, sDXWrapper.getSelectedView(), selectedPosition, sDXWrapper.getSelectedItemId());
        }
    }

    public static String getWinComponents(View view) {
        ViewGroup parent = (ViewGroup) view.findViewById(com.ludashi.benchmark.R.id.LLTabWinComponents);
        ArrayList<View> views = new ArrayList<>();
        AppUtils.findViewsWithClass(parent, Spinner.class, views);
        String[] wincomponents = new String[views.size()];
        for (int i = 0; i < views.size(); i++) {
            Spinner spinner = (Spinner) views.get(i);
            wincomponents[i] = spinner.getTag() + "=" + spinner.getSelectedItemPosition();
        }
        return String.join(",", wincomponents);
    }

    public static void createWinComponentsTab(View view, String wincomponents) {
        Context context = view.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup tabView = (ViewGroup) view.findViewById(com.ludashi.benchmark.R.id.LLTabWinComponents);
        ViewGroup directxSectionView = (ViewGroup) tabView.findViewById(com.ludashi.benchmark.R.id.LLWinComponentsDirectX);
        ViewGroup generalSectionView = (ViewGroup) tabView.findViewById(com.ludashi.benchmark.R.id.LLWinComponentsGeneral);
        Iterator<String[]> it = new KeyValueSet(wincomponents).iterator();
        while (it.hasNext()) {
            String[] wincomponent = it.next();
            ViewGroup parent = wincomponent[0].startsWith("direct") ? directxSectionView : generalSectionView;
            View itemView = inflater.inflate(com.ludashi.benchmark.R.layout.wincomponent_list_item, parent, false);
            ((TextView) itemView.findViewById(com.ludashi.benchmark.R.id.TextView)).setText(StringUtils.getString(context, wincomponent[0]));
            Spinner spinner = (Spinner) itemView.findViewById(com.ludashi.benchmark.R.id.Spinner);
            spinner.setSelection(Integer.parseInt(wincomponent[1]), false);
            spinner.setTag(wincomponent[0]);
            spinner.setPopupBackgroundResource(isDarkMode ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
            parent.addView(itemView);
        }
    }

    public static void createWinComponentsTabFromShortcut(ShortcutSettingsDialog dialog, View view, String wincomponents, boolean isDarkMode2) {
        Context context = dialog.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup tabView = (ViewGroup) view.findViewById(com.ludashi.benchmark.R.id.LLTabWinComponents);
        ViewGroup directxSectionView = (ViewGroup) tabView.findViewById(com.ludashi.benchmark.R.id.LLWinComponentsDirectX);
        ViewGroup generalSectionView = (ViewGroup) tabView.findViewById(com.ludashi.benchmark.R.id.LLWinComponentsGeneral);
        Iterator<String[]> it = new KeyValueSet(wincomponents).iterator();
        while (it.hasNext()) {
            String[] wincomponent = it.next();
            ViewGroup parent = wincomponent[0].startsWith("direct") ? directxSectionView : generalSectionView;
            View itemView = inflater.inflate(com.ludashi.benchmark.R.layout.wincomponent_list_item, parent, false);
            ((TextView) itemView.findViewById(com.ludashi.benchmark.R.id.TextView)).setText(StringUtils.getString(context, wincomponent[0]));
            Spinner spinner = (Spinner) itemView.findViewById(com.ludashi.benchmark.R.id.Spinner);
            spinner.setSelection(Integer.parseInt(wincomponent[1]), false);
            spinner.setTag(wincomponent[0]);
            spinner.setPopupBackgroundResource(isDarkMode2 ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
            parent.addView(itemView);
        }
        dialog.onWinComponentsViewsAdded(isDarkMode2);
    }

    private EnvVarsView createEnvVarsTab(View view) {
        final Context context = view.getContext();
        final EnvVarsView envVarsView = (EnvVarsView) view.findViewById(com.ludashi.benchmark.R.id.EnvVarsView);
        envVarsView.setDarkMode(isDarkMode);
        envVarsView.setEnvVars(new EnvVars(isEditMode() ? container.getEnvVars() : Container.DEFAULT_ENV_VARS));
        view.findViewById(com.ludashi.benchmark.R.id.BTAddEnvVar).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                new AddEnvVarDialog(context, envVarsView).show();
            }
        });
        return envVarsView;
    }

    private String getDrives(View view) {
        LinearLayout parent = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLDrives);
        String drives = "";
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            Spinner spinner = (Spinner) child.findViewById(com.ludashi.benchmark.R.id.Spinner);
            EditText editText = (EditText) child.findViewById(com.ludashi.benchmark.R.id.EditText);
            String path = editText.getText().toString().trim();
            if (!path.isEmpty()) {
                drives = drives + spinner.getSelectedItem() + path;
            }
        }
        return drives;
    }

    private void createDrivesTab(View view) {
        final Context context = getContext();
        final LinearLayout parent = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLDrives);
        final View emptyTextView = view.findViewById(com.ludashi.benchmark.R.id.TVDrivesEmptyText);
        final LayoutInflater inflater = LayoutInflater.from(context);
        String drives = isEditMode() ? container.getDrives() : Container.DEFAULT_DRIVES;
        final String[] driveLetters = new String[26];
        for (int i = 0; i < driveLetters.length; i++) {
            driveLetters[i] = ((char) (i + 68)) + ":";
        }
        final Callback<String[]> addItem = new Callback() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda9
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                ContainerDetailFragment.this.lambda$createDrivesTab$18(inflater, parent, context, driveLetters, emptyTextView, (String[]) obj);
            }
        };
        for (String[] drive : Container.drivesIterator(drives)) {
            addItem.call(drive);
        }
        view.findViewById(com.ludashi.benchmark.R.id.BTAddDrive).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda10
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                ContainerDetailFragment.lambda$createDrivesTab$19(parent, driveLetters, addItem, view2);
            }
        });
        if (drives.isEmpty()) {
            emptyTextView.setVisibility(0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$createDrivesTab$18(LayoutInflater inflater, final LinearLayout parent, Context context, String[] driveLetters, final View emptyTextView, final String[] drive) {
        final View itemView = inflater.inflate(com.ludashi.benchmark.R.layout.drive_list_item, (ViewGroup) parent, false);
        Spinner spinner = (Spinner) itemView.findViewById(com.ludashi.benchmark.R.id.Spinner);
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, driveLetters));
        AppUtils.setSpinnerSelectionFromValue(spinner, drive[0] + ":");
        spinner.setPopupBackgroundResource(isDarkMode ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        final EditText editText = (EditText) itemView.findViewById(com.ludashi.benchmark.R.id.EditText);
        editText.setText(drive[1]);
        applyDarkThemeToEditText(editText);
        View btSearch = itemView.findViewById(com.ludashi.benchmark.R.id.BTSearch);
        applyDarkThemeToButton(btSearch);
        itemView.findViewById(com.ludashi.benchmark.R.id.BTSearch).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda11
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ContainerDetailFragment.this.lambda$createDrivesTab$16(drive, editText, view);
            }
        });
        itemView.findViewById(com.ludashi.benchmark.R.id.BTRemove).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda12
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ContainerDetailFragment.lambda$createDrivesTab$17(parent, itemView, emptyTextView, view);
            }
        });
        parent.addView(itemView);
        emptyTextView.setVisibility(8);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$createDrivesTab$16(final String[] drive, final EditText editText, View v) {
        this.openDirectoryCallback = new Callback() { // from class: com.winlator.cmod.ContainerDetailFragment$$ExternalSyntheticLambda6
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                ContainerDetailFragment.lambda$createDrivesTab$15(drive, editText, (String) obj);
            }
        };
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT_TREE");
        intent.putExtra("android.provider.extra.INITIAL_URI", Uri.fromFile(Environment.getExternalStorageDirectory()));
        getActivity().startActivityFromFragment(this, intent, 4);
    }

    static /* synthetic */ void lambda$createDrivesTab$15(String[] drive, EditText editText, String path) {
        drive[1] = path;
        editText.setText(path);
    }

    static /* synthetic */ void lambda$createDrivesTab$17(LinearLayout parent, View itemView, View emptyTextView, View v) {
        parent.removeView(itemView);
        if (parent.getChildCount() == 0) {
            emptyTextView.setVisibility(0);
        }
    }

    static /* synthetic */ void lambda$createDrivesTab$19(LinearLayout parent, String[] driveLetters, Callback addItem, View v) {
        if (parent.getChildCount() >= 26) {
            return;
        }
        String nextDriveLetter = String.valueOf(driveLetters[parent.getChildCount()].charAt(0));
        addItem.call(new String[]{nextDriveLetter, ""});
    }

    private static void applyDarkThemeToEditText(EditText editText) {
        if (isDarkMode) {
            editText.setTextColor(-1);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(com.ludashi.benchmark.R.drawable.edit_text_dark);
        } else {
            editText.setTextColor(ViewCompat.MEASURED_STATE_MASK);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(com.ludashi.benchmark.R.drawable.edit_text);
        }
    }

    private void applyDarkThemeToButton(View button) {
    }

    private void loadWineVersionSpinner(final View view, final Spinner sWineVersion, final Spinner sBox64Version) {
        final Context context = getContext();
        sWineVersion.setEnabled(!isEditMode());
        sWineVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ContainerDetailFragment.5
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                FrameLayout fexcoreFL = (FrameLayout) view.findViewById(com.ludashi.benchmark.R.id.fexcoreFrame);
                Spinner sEmulator = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SEmulator);
                Spinner sEmulator64 = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SEmulator64);
                Spinner sDXWrapper = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SDXWrapper);
                View vDXWrapperConfig = view.findViewById(com.ludashi.benchmark.R.id.BTDXWrapperConfig);
                sEmulator64.setEnabled(false);
                String wineVersion = sWineVersion.getSelectedItem().toString();
                WineInfo wineInfo = WineInfo.fromIdentifier(context, ContainerDetailFragment.this.contentsManager, wineVersion);
                if (wineInfo.isArm64EC()) {
                    fexcoreFL.setVisibility(0);
                    sEmulator.setEnabled(true);
                    sEmulator64.setSelection(0);
                    if (!ContainerDetailFragment.this.isEditMode()) {
                        sEmulator.setSelection(0);
                    }
                } else {
                    fexcoreFL.setVisibility(8);
                    sEmulator.setEnabled(false);
                    sEmulator.setSelection(1);
                    sEmulator64.setSelection(1);
                }
                ContainerDetailFragment.loadBox64VersionSpinner(context, ContainerDetailFragment.container, ContainerDetailFragment.this.contentsManager, sBox64Version, wineInfo.isArm64EC());
                ContainerDetailFragment.setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig, wineInfo.isArm64EC());
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.LLWineVersion).setVisibility(0);
        String[] versions = getResources().getStringArray(com.ludashi.benchmark.R.array.wine_entries);
        ArrayList<String> wineVersions = new ArrayList<>();
        wineVersions.addAll(Arrays.asList(versions));
        for (ContentProfile profile : this.contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)) {
            wineVersions.add(ContentsManager.getEntryName(profile));
        }
        for (ContentProfile profile2 : this.contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            wineVersions.add(ContentsManager.getEntryName(profile2));
        }
        sWineVersion.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, wineVersions));
        if (isEditMode()) {
            AppUtils.setSpinnerSelectionFromValue(sWineVersion, container.getWineVersion());
        }
    }

    public String getControllerMapping(View view) {
        int[] ids = {com.ludashi.benchmark.R.id.SButtonA, com.ludashi.benchmark.R.id.SButtonB, com.ludashi.benchmark.R.id.SButtonX, com.ludashi.benchmark.R.id.SButtonY, com.ludashi.benchmark.R.id.SButtonGrip, com.ludashi.benchmark.R.id.SButtonTrigger, com.ludashi.benchmark.R.id.SThumbstickUp, com.ludashi.benchmark.R.id.SThumbstickDown, com.ludashi.benchmark.R.id.SThumbstickLeft, com.ludashi.benchmark.R.id.SThumbstickRight};
        byte[] controllerMapping = new byte[ids.length];
        for (int i = 0; i < ids.length; i++) {
            int index = ((Spinner) view.findViewById(ids[i])).getSelectedItemPosition();
            byte value = XKeycode.values()[index].id;
            controllerMapping[i] = value;
        }
        return new String(controllerMapping);
    }

    public void setControllerMapping(Spinner spinner, Container.XrControllerMapping mapping, int defaultValue) {
        XKeycode[] values = XKeycode.values();
        ArrayList<String> array = new ArrayList<>();
        for (XKeycode value : values) {
            array.add(value.name());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_dropdown_item, array);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter((SpinnerAdapter) adapter);
        byte keycode = isEditMode() ? container.getControllerMapping(mapping) : (byte) defaultValue;
        int index = 0;
        int i = 0;
        while (true) {
            if (i >= values.length) {
                break;
            }
            if (values[i].id != keycode) {
                i++;
            } else {
                index = i;
                break;
            }
        }
        spinner.setSelection((!isEditMode() || index == 0) ? defaultValue : index);
    }

    public static void updateGraphicsDriverSpinner(Context context, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(com.ludashi.benchmark.R.array.graphics_driver_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }

    public static void loadBox64VersionSpinner(Context context, Container container2, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        List<String> itemList;
        if (isArm64EC) {
            String[] originalItems = context.getResources().getStringArray(com.ludashi.benchmark.R.array.wowbox64_version_entries);
            itemList = new ArrayList<>(Arrays.asList(originalItems));
        } else {
            String[] originalItems2 = context.getResources().getStringArray(com.ludashi.benchmark.R.array.box64_version_entries);
            itemList = new ArrayList<>(Arrays.asList(originalItems2));
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
        if (container2 != null) {
            AppUtils.setSpinnerSelectionFromValue(spinner, container2.getBox64Version());
        } else {
            AppUtils.setSpinnerSelectionFromValue(spinner, "0.4.1");
        }
    }
}
