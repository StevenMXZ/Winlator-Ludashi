package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.box64.Box64Preset;
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
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRuntimeGuard;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.ColorPickerView;
import com.winlator.cmod.widget.EnvVarsView;
import com.winlator.cmod.widget.ImagePickerView;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.XKeycode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class ContainerDetailFragment extends Fragment implements DXVKConfigDialog.ContentInstallHost {

    private static final ExecutorService CONTENT_IO_EXECUTOR = Executors.newFixedThreadPool(2);

    private static final String TAG = "FileUtils";

    private ContainerManager manager;
    private ContentsManager contentsManager;
    private Context context;
    private final int containerId;
    private final int initialTab;
    private static Container container;
    private PreloaderDialog preloaderDialog;
    private JSONArray gpuCards;
    private Callback<String> openDirectoryCallback;

    private static boolean isDarkMode;

    private ImageFs imageFs;
    private List<ContentProfile.ContentType> pendingImportContentTypes;
    private Runnable pendingImportRefreshAction;

    public ContainerDetailFragment() {
        this(0, 0);
    }

    public ContainerDetailFragment(int containerId) {
        this(containerId, 0);
    }

    public ContainerDetailFragment(int containerId, int initialTab) {
        this.containerId = containerId;
        this.initialTab = initialTab;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        preloaderDialog = new PreloaderDialog(getActivity());

        try {
            gpuCards = new JSONArray(FileUtils.readString(getContext(), "gpu_cards.json"));
        } catch (JSONException e) {
        }
    }

    private static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {

        if (isDarkMode) {

            textView.setTextColor(Color.parseColor("#0055ff"));
            textView.setBackgroundResource(R.color.window_background_color_dark);
        } else {

            textView.setTextColor(Color.parseColor("#0055ff"));
            textView.setBackgroundResource(R.color.window_background_color);
        }
    }

    private void applyDynamicStyles(View view, boolean isDarkMode) {

        Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        sScreenSize.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sWineVersion = view.findViewById(R.id.SWineVersion);
        sWineVersion.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);
        sGraphicsDriver.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);
        sDXWrapper.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sRendererMode = view.findViewById(R.id.SPRendererMode);
        sRendererMode.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        sAudioDriver.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sEmulator64 = view.findViewById(R.id.SEmulator64);
        sEmulator64.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sEmulator = view.findViewById(R.id.SEmulator);
        sEmulator.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        sMIDISoundFont.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sDesktopTheme = view.findViewById(R.id.SDesktopTheme);
        sDesktopTheme.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sDesktopBackgroundType = view.findViewById(R.id.SDesktopBackgroundType);
        sDesktopBackgroundType.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sMouseWarpOverride = view.findViewById(R.id.SMouseWarpOverride);
        sMouseWarpOverride.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        sBox64Preset.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sBox64Version = view.findViewById(R.id.SBox64Version);
        sBox64Version.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sFEXCoreVersion = view.findViewById(R.id.SFEXCoreVersion);
        sFEXCoreVersion.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sFEXCorePreset = view.findViewById(R.id.SFEXCorePreset);
        sFEXCorePreset.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

        Spinner sStartupSelection = view.findViewById(R.id.SStartupSelection);
        sStartupSelection.setPopupBackgroundResource(
                isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);
    }

    private void applyDynamicStylesRecursively(View view, boolean isDarkMode) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                applyDynamicStylesRecursively(child, isDarkMode);
            }
        } else if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if ("desktop".equals(textView.getText().toString())) {
                textView.setTextAppearance(getContext(),
                        isDarkMode ? R.style.FieldSetLabel_Dark : R.style.FieldSetLabel);
            }
        }
    }

    private void styleContainerSectionLabels(View view) {
        styleSectionLabel(view.findViewById(R.id.TVWineVersionLabel), R.drawable.icon_wine);
        styleSectionLabel(view.findViewById(R.id.TVGraphicsDriverLabel), R.drawable.icon_menu_gpu);
        styleSectionLabel(view.findViewById(R.id.TVDXWrapperLabel), R.drawable.icon_monitor);
        styleSectionLabel(view.findViewById(R.id.TVRendererLabel), R.drawable.icon_monitor);
        styleSectionLabel(view.findViewById(R.id.TVAudioDriverLabel), R.drawable.icon_audio);
    }

    private void styleSectionLabel(TextView textView, int iconResId) {
        if (textView == null) return;
        Drawable drawable = textView.getContext().getDrawable(iconResId);
        if (drawable != null) {
            int size = Math.round(16 * textView.getResources().getDisplayMetrics().density);
            drawable.setBounds(0, 0, size, size);
            drawable.setTint(Color.parseColor("#0055ff"));
            textView.setCompoundDrawables(drawable, null, null, null);
            textView.setCompoundDrawablePadding(Math.round(6 * textView.getResources().getDisplayMetrics().density));
        }
        textView.setTextColor(Color.WHITE);
    }

    private void styleContainerTabs(TabLayout tabLayout) {
        int[][] states = new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}};
        int[] colors = new int[]{Color.parseColor("#0055ff"), Color.parseColor("#9CA8B8")};
        android.content.res.ColorStateList stateList = new android.content.res.ColorStateList(states, colors);
        for (int i = 0; i < tabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = tabLayout.getTabAt(i);
            if (tab != null) tab.setIcon(null);
        }
        tabLayout.setTabTextColors(stateList);
        tabLayout.setSelectedTabIndicatorColor(Color.parseColor("#0055ff"));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            if (pendingImportContentTypes != null) {
                installImportedContent(data.getData(), pendingImportContentTypes, pendingImportRefreshAction);
                pendingImportContentTypes = null;
                pendingImportRefreshAction = null;
                return;
            }
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == MainActivity.OPEN_DIRECTORY_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                Log.d(TAG, "URI obtained in onActivityResult: " + uri.toString());
                String path = FileUtils.getFilePathFromUri(getContext(), uri);
                Log.d(TAG, "File path in onActivityResult: " + path);
                if (path != null) {
                    if (openDirectoryCallback != null) {
                        openDirectoryCallback.call(path);
                    }
                } else {
                    Toast.makeText(getContext(), "Invalid directory selected", Toast.LENGTH_SHORT).show();
                }
            }
            openDirectoryCallback = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).setDetailMode(true);
        ((AppCompatActivity) getActivity()).getSupportActionBar()
                .setTitle(isEditMode() ? R.string.edit_container : R.string.new_container);
    }

    @Override
    public void onPause() {
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).setDetailMode(false);
        super.onPause();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar()
                .setTitle(isEditMode() ? R.string.edit_container : R.string.new_container);

        TextView desktopLabel = view.findViewById(R.id.TVDesktop);
        applyFieldSetLabelStyle(desktopLabel, isDarkMode);

        TextView registryKeysLabel = view.findViewById(R.id.TVDirectInput);
        applyFieldSetLabelStyle(registryKeysLabel, isDarkMode);

        TextView directXLabel = view.findViewById(R.id.TVDirectX);
        applyFieldSetLabelStyle(directXLabel, isDarkMode);

        TextView generalLabel = view.findViewById(R.id.TVGeneral);
        applyFieldSetLabelStyle(generalLabel, isDarkMode);

        TextView box64Label = view.findViewById(R.id.TVBox64);
        applyFieldSetLabelStyle(box64Label, isDarkMode);

        TextView fexCoreLabel = view.findViewById(R.id.TVFEXCore);
        applyFieldSetLabelStyle(fexCoreLabel, isDarkMode);

        TextView systemLabel = view.findViewById(R.id.TVSystem);
        applyFieldSetLabelStyle(systemLabel, isDarkMode);

        TextView gameControllerLabel = view.findViewById(R.id.TVGameController);
        applyFieldSetLabelStyle(gameControllerLabel, isDarkMode);

    }

    public boolean isEditMode() {
        return container != null;
    }

    @SuppressLint("SetTextI18n")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup root,
            @Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        this.context = context;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final View view = inflater.inflate(R.layout.container_detail_fragment, root, false);

        isDarkMode = preferences.getBoolean("dark_mode", true);

        applyDynamicStyles(view, isDarkMode);
        styleContainerSectionLabels(view);

        manager = new ContainerManager(context);
        container = containerId > 0 ? manager.getContainerById(containerId) : null;
        contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        final EditText etName = view.findViewById(R.id.ETName);

        final Spinner sWineVersion = view.findViewById(R.id.SWineVersion);

        final LinearLayout llWineVersion = view.findViewById(R.id.LLWineVersion);
        llWineVersion.setVisibility(View.VISIBLE);

        if (isEditMode()) {
            etName.setText(container.getName());
        } else {
            etName.setText(getString(R.string.container) + "-" + manager.getNextContainerId());
        }

        final Spinner sBox64Version = view.findViewById(R.id.SBox64Version);
        final View btBox64VersionRemove = view.findViewById(R.id.BTBox64VersionRemove);
        final View btBox64VersionDownload = view.findViewById(R.id.BTBox64VersionDownload);

        loadWineVersionSpinner(view, sWineVersion, sBox64Version);
        final View btWineVersionOptions = view.findViewById(R.id.BTWineVersionOptions);
        Runnable refreshWineVersion = () -> {
            contentsManager.syncContents();
            String selected = sWineVersion.getSelectedItem() != null ? sWineVersion.getSelectedItem().toString() : WineInfo.MAIN_WINE_VERSION.identifier();
            loadWineVersionSpinner(view, sWineVersion, sBox64Version);
            AppUtils.setSpinnerSelectionFromValue(sWineVersion, selected);
        };
        final LinearLayout llReleaseProtons = view.findViewById(R.id.LLReleaseProtons);
        populateReleaseProtonList(llReleaseProtons, sWineVersion);
        sWineVersion.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                populateReleaseProtonList(llReleaseProtons, sWineVersion);
                llReleaseProtons.setVisibility(llReleaseProtons.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
            return true;
        });
        if (btWineVersionOptions != null) btWineVersionOptions.setOnClickListener(v -> showWineVersionDownloadPopup(v, sWineVersion, refreshWineVersion));

        loadScreenSizeSpinner(view, isEditMode() ? container.getScreenSize() : Container.DEFAULT_SCREEN_SIZE);

        final Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);

        final Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);

        final View vDXWrapperConfig = view.findViewById(R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(isEditMode() ? container.getDXWrapperConfig() : Container.DEFAULT_DXWRAPPERCONFIG);

        final View vGraphicsDriverConfig = view.findViewById(R.id.BTGraphicsDriverConfig);
        vGraphicsDriverConfig
                .setTag(isEditMode() ? container.getGraphicsDriverConfig() : Container.DEFAULT_GRAPHICSDRIVERCONFIG);

        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig,
                isEditMode() ? container.getGraphicsDriver() : Container.DEFAULT_GRAPHICS_DRIVER,
                isEditMode() ? container.getDXWrapper() : Container.DEFAULT_DXWRAPPER);

        view.findViewById(R.id.BTHelpDXWrapper)
                .setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));

        final com.winlator.cmod.container.Container rendererCfgHolder = isEditMode() ? container
                : new com.winlator.cmod.container.Container(-1);
        final Spinner spRendererMode = view.findViewById(R.id.SPRendererMode);
        if (spRendererMode != null) {
            ArrayAdapter<String> rendererModeAdapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled,
                    new String[]{"Vulkan", "EGL"});
            rendererModeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
            spRendererMode.setAdapter(rendererModeAdapter);
            spRendererMode.setSelection(rendererCfgHolder.isRendererNative() ? 1 : 0);
            spRendererMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                    rendererCfgHolder.setRendererNative(position == 1);
                    if (isEditMode())
                        rendererCfgHolder.saveData();
                }
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }
        final Spinner spDisplayServer = view.findViewById(R.id.SPDisplayServer);
        ArrayAdapter<String> displayServers = new ArrayAdapter<>(context, R.layout.spinner_item_amoled,
                new String[]{"X11", "X++"});
        displayServers.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
        spDisplayServer.setAdapter(displayServers);
        spDisplayServer.setSelection("native".equals(rendererCfgHolder.getExtra("displayServer", "native")) ? 1 : 0);
        spDisplayServer.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View row, int pos, long id) {
                rendererCfgHolder.putExtra("displayServer", pos == 1 ? "native" : "java");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        View btRendererOptions = view.findViewById(R.id.BTRendererOptions);
        if (btRendererOptions != null) {
            btRendererOptions.setOnClickListener(v -> {
                new com.winlator.cmod.contentdialog.RendererOptionsDialog(v,
                        new com.winlator.cmod.contentdialog.RendererOptionsDialog.Config() {
                            public String getRendererPresentMode() {
                                return rendererCfgHolder.getRendererPresentMode();
                            }

                            public void setRendererPresentMode(String val) {
                                rendererCfgHolder.setRendererPresentMode(val);
                                if (isEditMode())
                                    rendererCfgHolder.saveData();
                            }

                            public String getRendererDriverId() {
                                return rendererCfgHolder.getRendererDriverId();
                            }

                            public void setRendererDriverId(String val) {
                                rendererCfgHolder.setRendererDriverId(val);
                                if (isEditMode())
                                    rendererCfgHolder.saveData();
                            }

                            public int getRendererFilterMode() {
                                return rendererCfgHolder.getRendererFilterMode();
                            }

                            public void setRendererFilterMode(int val) {
                                rendererCfgHolder.setRendererFilterMode(val);
                                if (isEditMode())
                                    rendererCfgHolder.saveData();
                            }

                            public boolean getRendererSwapRB() {
                                return rendererCfgHolder.getRendererSwapRB();
                            }

                            public void setRendererSwapRB(boolean val) {
                                rendererCfgHolder.setRendererSwapRB(val);
                                if (isEditMode())
                                    rendererCfgHolder.saveData();
                            }
                        }, rendererCfgHolder.isRendererNative()).show();
            });
        }

        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver,
                isEditMode() ? container.getAudioDriver() : Container.DEFAULT_AUDIO_DRIVER);

        Spinner sEmulator = view.findViewById(R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator,
                isEditMode() ? container.getEmulator() : Container.DEFAULT_EMULATOR);

        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, isEditMode() ? container.getMIDISoundFont() : "");

        Spinner sHudMode = view.findViewById(R.id.SHudMode);
        ArrayAdapter<String> hudAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item,
                new String[]{"Off", "Classic", "Modern"});
        hudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sHudMode.setAdapter(hudAdapter);

        int savedHudMode = 0;
        if (isEditMode()) {
            String hudModeExtra = container.getExtra("hudMode");
            if (!hudModeExtra.isEmpty()) {
                savedHudMode = Integer.parseInt(hudModeExtra);
            } else if (container.isShowFPS()) {
                savedHudMode = 1;
            }
        }
        sHudMode.setSelection(savedHudMode);

        final CheckBox cbFullscreenStretched = view.findViewById(R.id.CBFullscreenStretched);
        cbFullscreenStretched.setChecked(isEditMode() && container.isFullscreenStretched());

        final Runnable showInputWarning = () -> ContentDialog.alert(context,
                R.string.enable_xinput_and_dinput_same_time, null);
        final CheckBox cbEnableXInput = view.findViewById(R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = view.findViewById(R.id.CBEnableDInput);
        final CheckBox cbExclusiveXInput = view.findViewById(R.id.CBExclusiveXInput);
        final View btHelpXInput = view.findViewById(R.id.BTXInputHelp);
        final View btHelpDInput = view.findViewById(R.id.BTDInputHelp);
        final View btHelpExclusiveXInput = view.findViewById(R.id.BTExclusiveXInputHelp);

        int inputType = isEditMode() ? container.getInputType() : WinHandler.DEFAULT_INPUT_TYPE;

        cbEnableXInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_XINPUT) == WinHandler.FLAG_INPUT_TYPE_XINPUT);
        cbEnableDInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT);
        cbExclusiveXInput.setChecked(isEditMode() ? container.isExclusiveXInput() : true);

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
                if (cbEnableXInput.isChecked() && cbEnableDInput.isChecked())
                    cbEnableDInput.setChecked(false);
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

        final EditText etLC_ALL = view.findViewById(R.id.ETlcall);
        Locale systemLocal = Locale.getDefault();
        etLC_ALL.setText(isEditMode() ? container.getLC_ALL()
                : systemLocal.getLanguage() + '_' + systemLocal.getCountry() + ".UTF-8");

        final View btShowLCALL = view.findViewById(R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            String[] lcs = getResources().getStringArray(R.array.some_lc_all);
            for (int i = 0; i < lcs.length; i++)
                popupMenu.getMenu().add(Menu.NONE, i, Menu.NONE, lcs[i]);
            popupMenu.setOnMenuItemClickListener(item -> {
                etLC_ALL.setText(item.toString() + ".UTF-8");
                return true;
            });
            popupMenu.show();
        });

        final Spinner sStartupSelection = view.findViewById(R.id.SStartupSelection);
        byte previousStartupSelection = isEditMode() ? container.getStartupSelection() : -1;
        sStartupSelection.setSelection(
                previousStartupSelection != -1 ? previousStartupSelection : Container.STARTUP_SELECTION_ESSENTIAL);

        final Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        Box64PresetManager.loadSpinner("box64", sBox64Preset, isEditMode() ? container.getBox64Preset()
                : preferences.getString("box64_preset", Box64Preset.COMPATIBILITY));

        final Spinner sFEXCoreVersion = view.findViewById(R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion,
                isEditMode() ? container.getFEXCoreVersion() : DefaultVersion.FEXCORE);
        View btFEXCoreVersionRemove = view.findViewById(R.id.BTFEXCoreVersionRemove);
        View btFEXCoreVersionDownload = view.findViewById(R.id.BTFEXCoreVersionDownload);
        Runnable refreshBox64 = () -> {
            contentsManager.syncContents();
            String wineVersion = sWineVersion.getSelectedItem() != null ? sWineVersion.getSelectedItem().toString()
                    : (isEditMode() ? container.getWineVersion() : WineInfo.MAIN_WINE_VERSION.identifier());
            WineInfo wi = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
            loadBox64VersionSpinner(context, container, contentsManager, sBox64Version, wi.isArm64EC());
        };
        Runnable refreshFEXCore = () -> {
            contentsManager.syncContents();
            FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion,
                    sFEXCoreVersion.getSelectedItem() != null ? sFEXCoreVersion.getSelectedItem().toString() : DefaultVersion.FEXCORE);
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
                        refreshFEXCore));
        if (btFEXCoreVersionDownload != null) btFEXCoreVersionDownload.setOnClickListener(v ->
                showInstallChoicePopup(v, Collections.singletonList(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE), refreshFEXCore));

        final Spinner sFEXCorePreset = view.findViewById(R.id.SFEXCorePreset);
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, isEditMode() ? container.getFEXCorePreset()
                : preferences.getString("fexcore_preset", FEXCorePreset.INTERMEDIATE));

        String selectedDriver = sGraphicsDriver.getSelectedItem().toString();
        List<String> sGraphicsItemsList = new ArrayList<>(
                Arrays.asList(context.getResources().getStringArray(R.array.graphics_driver_entries)));
        ArrayAdapter<String> graphicsDriverAdapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, sGraphicsItemsList);
        graphicsDriverAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled);
        sGraphicsDriver.setAdapter(graphicsDriverAdapter);
        AppUtils.setSpinnerSelectionFromValue(sGraphicsDriver, selectedDriver);

        final CPUListView cpuListView = view.findViewById(R.id.CPUListView);
        final CPUListView cpuListViewWoW64 = view.findViewById(R.id.CPUListViewWoW64);
        final CheckBox cbSyncCpuTopology = view.findViewById(R.id.CBSyncCpuTopology);

        cpuListView.setCheckedCPUList(isEditMode() ? container.getCPUList(true) : Container.getFallbackCPUList());
        cpuListViewWoW64.setCheckedCPUList(
                isEditMode() ? container.getCPUListWoW64(true) : Container.getFallbackCPUListWoW64());

        boolean syncCpuTopology = isEditMode() && container.isSyncCpuTopology();
        cbSyncCpuTopology.setChecked(syncCpuTopology);

        final Spinner sPrimaryController = view.findViewById(R.id.SPrimaryController);
        sPrimaryController.setSelection(isEditMode() ? container.getPrimaryController() : 1);
        setControllerMapping(view.findViewById(R.id.SButtonA), Container.XrControllerMapping.BUTTON_A,
                XKeycode.KEY_A.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonB), Container.XrControllerMapping.BUTTON_B,
                XKeycode.KEY_B.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonX), Container.XrControllerMapping.BUTTON_X,
                XKeycode.KEY_X.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonY), Container.XrControllerMapping.BUTTON_Y,
                XKeycode.KEY_Y.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonGrip), Container.XrControllerMapping.BUTTON_GRIP,
                XKeycode.KEY_SPACE.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonTrigger), Container.XrControllerMapping.BUTTON_TRIGGER,
                XKeycode.KEY_ENTER.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickUp), Container.XrControllerMapping.THUMBSTICK_UP,
                XKeycode.KEY_UP.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickDown), Container.XrControllerMapping.THUMBSTICK_DOWN,
                XKeycode.KEY_DOWN.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickLeft), Container.XrControllerMapping.THUMBSTICK_LEFT,
                XKeycode.KEY_LEFT.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickRight), Container.XrControllerMapping.THUMBSTICK_RIGHT,
                XKeycode.KEY_RIGHT.ordinal());

        createWineConfigurationTab(view);
        final EnvVarsView envVarsView = createEnvVarsTab(view);
        createWinComponentsTab(view, isEditMode() ? container.getWinComponents() : Container.DEFAULT_WINCOMPONENTS);
        com.winlator.cmod.widget.CodecsSettingsView.attach(view,
                isEditMode() ? container.getBuiltinDecoder() : "gstreamer",
                isEditMode() && container.getSoftwareDecoding());
        createDrivesTab(view);
        getChildFragmentManager().beginTransaction().replace(R.id.LLTabDrivers, new AdrenotoolsFragment()).commit();

        AppUtils.setupTabLayout(view, R.id.TabLayout, R.id.LLTabWineConfiguration, R.id.LLTabEnvVars,
                R.id.LLTabDrivers, R.id.LLTabAdvanced, R.id.LLTabWinComponents);

        TabLayout tabLayout = view.findViewById(R.id.TabLayout);
        styleContainerTabs(tabLayout);
        if (initialTab >= 0 && initialTab < tabLayout.getTabCount()
                && tabLayout.getTabAt(initialTab) != null) {
            tabLayout.getTabAt(initialTab).select();
        }

        if (isDarkMode) {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background_dark);
        } else {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background);
        }

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            try {

                String name = etName.getText().toString();
                String screenSize = getScreenSize(view);
                String envVars = envVarsView.getEnvVars();
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
                HashMap<String, String> config = GraphicsDriverConfigDialog
                        .parseGraphicsDriverConfig(graphicsDriverConfig);
                if (config.get("version").isEmpty()) {
                    config.put("version",
                            GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)
                                    ? DefaultVersion.WRAPPER_ADRENO
                                    : DefaultVersion.WRAPPER);
                    graphicsDriverConfig = GraphicsDriverConfigDialog.toGraphicsDriverConfig(config);
                }
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
                String wincomponents = getWinComponents(view);
                String builtinDecoder = com.winlator.cmod.widget.CodecsSettingsView.decoder(view);
                boolean softwareDecoding = com.winlator.cmod.widget.CodecsSettingsView.software(view);
                String drives = getDrives(view);
                int hudMode = sHudMode.getSelectedItemPosition();
                boolean showFPS = hudMode != 0;
                boolean fullscreenStretched = cbFullscreenStretched.isChecked();
                boolean exclusiveXInput = cbExclusiveXInput.isChecked();
                String cpuList = cpuListView.getCheckedCPUListAsString();
                String cpuListWoW64 = cpuListViewWoW64.getCheckedCPUListAsString();
                boolean syncCpuTopologyChecked = cbSyncCpuTopology.isChecked();
                byte startupSelection = (byte) sStartupSelection.getSelectedItemPosition();
                String box64Version = sBox64Version.getSelectedItem().toString();
                String fexcoreVersion = sFEXCoreVersion.getSelectedItem().toString();
                String fexcorePreset = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
                String box64Preset = Box64PresetManager.getSpinnerSelectedId(sBox64Preset);
                String desktopTheme = getDesktopTheme(view);

                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? ""
                        : sMIDISoundFont.getSelectedItem().toString();
                String lc_all = etLC_ALL.getText().toString();
                int primaryController = sPrimaryController.getSelectedItemPosition();
                String controllerMapping = getControllerMapping(view);

                int finalInputType = 0;
                finalInputType |= cbEnableXInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_XINPUT : 0;
                finalInputType |= cbEnableDInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_DINPUT : 0;

                if (isEditMode()) {

                    container.setName(name);
                    container.setScreenSize(screenSize);
                    container.setEnvVars(envVars);
                    container.setCPUList(cpuList);
                    container.setCPUListWoW64(cpuListWoW64);
                    container.setSyncCpuTopology(syncCpuTopologyChecked);
                    container.setGraphicsDriver(graphicsDriver);
                    container.setGraphicsDriverConfig(graphicsDriverConfig);
                    container.setDXWrapper(dxwrapper);
                    container.putExtra("displayServer", rendererCfgHolder.getExtra("displayServer", "native"));
                    container.setRendererNative(rendererCfgHolder.isRendererNative());
                    container.setRendererPresentMode(rendererCfgHolder.getRendererPresentMode());
                    container.setRendererDriverId(rendererCfgHolder.getRendererDriverId());
                    container.setRendererFilterMode(rendererCfgHolder.getRendererFilterMode());
                    container.setRendererSwapRB(rendererCfgHolder.getRendererSwapRB());
                    container.setDXWrapperConfig(dxwrapperConfig);
                    container.setAudioDriver(audioDriver);
                    container.setEmulator(emulator);
                    container.setWinComponents(wincomponents);
                    container.setBuiltinDecoder(builtinDecoder);
                    container.setSoftwareDecoding(softwareDecoding);
                    container.setDrives(drives);
                    container.setShowFPS(showFPS);
                    container.putExtra("hudMode", String.valueOf(hudMode));
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
                } else {

                    JSONObject data = new JSONObject();
                    data.put("name", name);
                    data.put("screenSize", screenSize);
                    data.put("envVars", envVars);
                    data.put("cpuList", cpuList);
                    data.put("cpuListWoW64", cpuListWoW64);
                    if (syncCpuTopologyChecked) data.put("syncCpuTopology", true);
                    data.put("graphicsDriver", graphicsDriver);
                    data.put("graphicsDriverConfig", graphicsDriverConfig);
                    data.put("dxwrapper", dxwrapper);
                    data.put("rendererNative", rendererCfgHolder.isRendererNative());
                    data.put("rendererPresentMode", rendererCfgHolder.getRendererPresentMode());
                    if (!rendererCfgHolder.getRendererDriverId().isEmpty())
                        data.put("rendererDriverId", rendererCfgHolder.getRendererDriverId());
                    if (rendererCfgHolder.getRendererFilterMode() != 0)
                        data.put("rendererFilterMode", rendererCfgHolder.getRendererFilterMode());
                    if (rendererCfgHolder.getRendererSwapRB())
                        data.put("rendererSwapRB", true);
                    data.put("dxwrapperConfig", dxwrapperConfig);
                    data.put("audioDriver", audioDriver);
                    data.put("emulator", emulator);
                    data.put("wincomponents", wincomponents);
                    data.put("extraData", new JSONObject().put("builtinDecoder", builtinDecoder)
                            .put("softwareDecoding", softwareDecoding ? "1" : "0"));
                    data.put("drives", drives);
                    data.put("showFPS", showFPS);
                    data.put("hudMode", hudMode);
                    data.put("fullscreenStretched", fullscreenStretched);
                    data.put("exclusiveXInput", exclusiveXInput);
                    data.put("inputType", finalInputType);
                    data.put("startupSelection", startupSelection);
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

                    PreloaderDialog creationDialog = new PreloaderDialog(getActivity());
                    creationDialog.show(R.string.creating_container);

                    File imageFsRoot = new File(context.getFilesDir(), "imagefs");
                    imageFs = ImageFs.find(imageFsRoot);

                    manager.createContainerAsync(data, contentsManager, (container) -> {
                        if (container != null) {
                            this.container = container;
                            container.putExtra("displayServer", rendererCfgHolder.getExtra("displayServer", "native"));
                            container.saveData();
                            saveWineRegistryKeys(view);
                        }
                        creationDialog.close();
                        getActivity().onBackPressed();
                    });
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        return view;
    }

    private void saveWineRegistryKeys(View view) {
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            Spinner sMouseWarpOverride = view.findViewById(R.id.SMouseWarpOverride);
            registryEditor.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride",
                    sMouseWarpOverride.getSelectedItem().toString().toLowerCase(Locale.ENGLISH));
        }
    }

    private void createWineConfigurationTab(View view) {
        Context context = getContext();

        WineThemeManager.ThemeInfo desktopTheme = new WineThemeManager.ThemeInfo(
                isEditMode() ? container.getDesktopTheme() : WineThemeManager.DEFAULT_DESKTOP_THEME);
        Spinner sDesktopTheme = view.findViewById(R.id.SDesktopTheme);
        sDesktopTheme.setSelection(desktopTheme.theme.ordinal());
        final ImagePickerView ipvDesktopBackgroundImage = view.findViewById(R.id.IPVDesktopBackgroundImage);
        final ColorPickerView cpvDesktopBackgroundColor = view.findViewById(R.id.CPVDesktopBackgroundColor);
        cpvDesktopBackgroundColor.setColor(desktopTheme.backgroundColor);

        Spinner sDesktopBackgroundType = view.findViewById(R.id.SDesktopBackgroundType);
        sDesktopBackgroundType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[position];
                ipvDesktopBackgroundImage.setVisibility(View.GONE);
                cpvDesktopBackgroundColor.setVisibility(View.GONE);

                if (type == WineThemeManager.BackgroundType.IMAGE) {
                    ipvDesktopBackgroundImage.setVisibility(View.VISIBLE);
                } else if (type == WineThemeManager.BackgroundType.COLOR) {
                    cpvDesktopBackgroundColor.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        sDesktopBackgroundType.setSelection(desktopTheme.backgroundType.ordinal());

        File containerDir = isEditMode() ? container.getRootDir() : null;
        File userRegFile = new File(containerDir, ".wine/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            List<String> mouseWarpOverrideList = Arrays.asList(context.getString(R.string.disable),
                    context.getString(R.string.enable), context.getString(R.string.force));
            Spinner sMouseWarpOverride = view.findViewById(R.id.SMouseWarpOverride);
            sMouseWarpOverride.setAdapter(
                    new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, mouseWarpOverrideList));
            AppUtils.setSpinnerSelectionFromValue(sMouseWarpOverride,
                    registryEditor.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", "disable"));
        }
    }

    private void loadGPUNameSpinner(Spinner spinner, int selectedDeviceID) {
        List<String> values = new ArrayList<>();
        int selectedPosition = 0;

        try {
            for (int i = 0; i < gpuCards.length(); i++) {
                JSONObject item = gpuCards.getJSONObject(i);
                if (item.getInt("deviceID") == selectedDeviceID)
                    selectedPosition = i;
                values.add(item.getString("name"));
            }
        } catch (JSONException e) {
        }

        spinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition);
    }

    public static String getScreenSize(View view) {
        Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        String value = sScreenSize.getSelectedItem().toString();
        if (value.equalsIgnoreCase("custom")) {
            value = Container.DEFAULT_SCREEN_SIZE;
            String strWidth = ((EditText) view.findViewById(R.id.ETScreenWidth)).getText().toString().trim();
            String strHeight = ((EditText) view.findViewById(R.id.ETScreenHeight)).getText().toString().trim();
            if (strWidth.matches("[0-9]+") && strHeight.matches("[0-9]+")) {
                int width = Integer.parseInt(strWidth);
                int height = Integer.parseInt(strHeight);
                if ((width % 2) == 0 && (height % 2) == 0)
                    return width + "x" + height;
            }
        }
        return StringUtils.parseIdentifier(value);
    }

    private String getDesktopTheme(View view) {
        Spinner sDesktopBackgroundType = view.findViewById(R.id.SDesktopBackgroundType);
        WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[sDesktopBackgroundType
                .getSelectedItemPosition()];
        Spinner sDesktopTheme = view.findViewById(R.id.SDesktopTheme);
        ColorPickerView cpvDesktopBackground = view.findViewById(R.id.CPVDesktopBackgroundColor);
        WineThemeManager.Theme theme = WineThemeManager.Theme.values()[sDesktopTheme.getSelectedItemPosition()];

        String desktopTheme = theme + "," + type + "," + cpvDesktopBackground.getColorAsString();
        if (type == WineThemeManager.BackgroundType.IMAGE) {
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(getContext());
            desktopTheme += "," + (userWallpaperFile.isFile() ? userWallpaperFile.lastModified() : "0");
        }
        return desktopTheme;
    }

    public static String getDeviceScreenSize(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        return width + "x" + height;
    }

    public static void loadScreenSizeSpinner(View view, String selectedValue) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);
        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String normalizedScreenSize = selectedValue.replaceAll("\\s*\\(.*\\)$", "").trim();
            String[] screenSize = normalizedScreenSize.split("x");
            ((EditText) view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText) view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper,
            final View vGraphicsDriverConfig, String selectedGraphicsDriver, String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();

        updateGraphicsDriverSpinner(context, sGraphicsDriver);

        Runnable update = () -> {
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());

            ArrayList<String> items = new ArrayList<>();
            for (String value : context.getResources().getStringArray(R.array.dxwrapper_entries)) {
                items.add(value);
            }
            ArrayAdapter<String> dxWrapperAdapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, items.toArray(new String[0]));
            dxWrapperAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled);
            sDXWrapper.setAdapter(dxWrapperAdapter);
            AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);

            vGraphicsDriverConfig.setOnClickListener((v) -> {
                new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver, null).show();
            });
            vGraphicsDriverConfig.setVisibility(View.VISIBLE);
        };

        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }

    public static void setupDXWrapperSpinner(final Spinner sDXWrapper, final View vDXWrapperConfig, boolean isARM64EC) {
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.contains("dxvk")) {
                    vDXWrapperConfig
                            .setOnClickListener((v) -> (new DXVKConfigDialog(vDXWrapperConfig, isARM64EC)).show());
                } else {
                    vDXWrapperConfig.setOnClickListener((v) -> (new WineD3DConfigDialog(vDXWrapperConfig)).show());
                }
                vDXWrapperConfig.setVisibility(View.VISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };

        sDXWrapper.setOnItemSelectedListener(listener);

        int selectedPosition = sDXWrapper.getSelectedItemPosition();
        if (selectedPosition >= 0) {
            listener.onItemSelected(
                    sDXWrapper,
                    sDXWrapper.getSelectedView(),
                    selectedPosition,
                    sDXWrapper.getSelectedItemId());
        }
    }

    public void setupDXWrapperSpinnerWithFragment(final Spinner sDXWrapper, final View vDXWrapperConfig, boolean isARM64EC) {
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.contains("dxvk")) {
                    vDXWrapperConfig.setOnClickListener((v) ->
                            (new DXVKConfigDialog(vDXWrapperConfig, isARM64EC, ContainerDetailFragment.this, contentsManager)).show());
                } else {
                    vDXWrapperConfig.setOnClickListener((v) -> (new WineD3DConfigDialog(vDXWrapperConfig)).show());
                }
                vDXWrapperConfig.setVisibility(View.VISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };

        sDXWrapper.setOnItemSelectedListener(listener);

        int selectedPosition = sDXWrapper.getSelectedItemPosition();
        if (selectedPosition >= 0) {
            listener.onItemSelected(
                    sDXWrapper,
                    sDXWrapper.getSelectedView(),
                    selectedPosition,
                    sDXWrapper.getSelectedItemId());
        }
    }

    public static String getWinComponents(View view) {
        ViewGroup parent = view.findViewById(R.id.LLTabWinComponents);
        ArrayList<View> views = new ArrayList<>();
        AppUtils.findViewsWithClass(parent, Spinner.class, views);
        ArrayList<String> wincomponents = new ArrayList<>();

        for (View item : views) {
            Spinner spinner = (Spinner) item;
            // Codec settings are saved separately and are not component DLL overrides.
            if (spinner.getTag() instanceof String)
                wincomponents.add(spinner.getTag() + "=" + spinner.getSelectedItemPosition());
        }
        return String.join(",", wincomponents);
    }

    public static void createWinComponentsTab(View view, String wincomponents) {
        Context context = view.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup tabView = view.findViewById(R.id.LLTabWinComponents);
        ViewGroup directxSectionView = tabView.findViewById(R.id.LLWinComponentsDirectX);
        ViewGroup generalSectionView = tabView.findViewById(R.id.LLWinComponentsGeneral);

        for (String[] wincomponent : new KeyValueSet(wincomponents)) {
            ViewGroup parent = wincomponent[0].startsWith("direct") ? directxSectionView : generalSectionView;
            View itemView = inflater.inflate(R.layout.wincomponent_list_item, parent, false);
            ((TextView) itemView.findViewById(R.id.TextView)).setText(StringUtils.getString(context, wincomponent[0]));
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            applyWinComponentSpinnerAdapter(context, spinner);
            spinner.setSelection(Integer.parseInt(wincomponent[1]), false);
            spinner.setTag(wincomponent[0]);

            spinner.setPopupBackgroundResource(
                    isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

            parent.addView(itemView);

        }
    }

    public static void createWinComponentsTabFromShortcut(ShortcutSettingsDialog dialog, View view,
            String wincomponents, boolean isDarkMode) {
        Context context = dialog.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup tabView = view.findViewById(R.id.LLTabWinComponents);
        ViewGroup directxSectionView = tabView.findViewById(R.id.LLWinComponentsDirectX);
        ViewGroup generalSectionView = tabView.findViewById(R.id.LLWinComponentsGeneral);

        for (String[] wincomponent : new KeyValueSet(wincomponents)) {
            ViewGroup parent = wincomponent[0].startsWith("direct") ? directxSectionView : generalSectionView;
            View itemView = inflater.inflate(R.layout.wincomponent_list_item, parent, false);
            ((TextView) itemView.findViewById(R.id.TextView)).setText(StringUtils.getString(context, wincomponent[0]));
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            applyWinComponentSpinnerAdapter(context, spinner);
            spinner.setSelection(Integer.parseInt(wincomponent[1]), false);
            spinner.setTag(wincomponent[0]);

            spinner.setPopupBackgroundResource(
                    isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

            parent.addView(itemView);
        }

        dialog.onWinComponentsViewsAdded(isDarkMode);
    }

    private static void applyWinComponentSpinnerAdapter(Context context, Spinner spinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                context, R.array.wincomponent_entries, R.layout.spinner_item_amoled);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
        spinner.setAdapter(adapter);
        spinner.setPopupBackgroundResource(R.drawable.dialog_background_dark_blue);
    }

    private EnvVarsView createEnvVarsTab(final View view) {
        final Context context = view.getContext();
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);

        envVarsView.setDarkMode(isDarkMode);

        envVarsView.setEnvVars(new EnvVars(isEditMode() ? container.getEnvVars() : Container.DEFAULT_ENV_VARS));
        view.findViewById(R.id.BTAddEnvVar)
                .setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());
        return envVarsView;
    }

    private String getDrives(View view) {
        LinearLayout parent = view.findViewById(R.id.LLDrives);
        String drives = "";

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            Spinner spinner = child.findViewById(R.id.Spinner);
            EditText editText = child.findViewById(R.id.EditText);
            String path = editText.getText().toString().trim();
            if (!path.isEmpty())
                drives += spinner.getSelectedItem() + path;
        }
        return drives;
    }

    private void createDrivesTab(View view) {
        final Context context = getContext();

        final LinearLayout parent = view.findViewById(R.id.LLDrives);
        final View emptyTextView = view.findViewById(R.id.TVDrivesEmptyText);
        LayoutInflater inflater = LayoutInflater.from(context);
        final String drives = isEditMode() ? container.getDrives() : Container.DEFAULT_DRIVES;
        final String[] driveLetters = new String[Container.MAX_DRIVE_LETTERS];
        for (int i = 0; i < driveLetters.length; i++)
            driveLetters[i] = ((char) (i + 68)) + ":";

        Callback<String[]> addItem = (drive) -> {
            final View itemView = inflater.inflate(R.layout.drive_list_item, parent, false);
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            spinner.setAdapter(
                    new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, driveLetters));
            AppUtils.setSpinnerSelectionFromValue(spinner, drive[0] + ":");

            spinner.setPopupBackgroundResource(
                    isDarkMode ? R.drawable.dialog_background_dark_blue : R.drawable.content_dialog_background);

            final EditText editText = itemView.findViewById(R.id.EditText);
            editText.setText(drive[1]);

            applyDarkThemeToEditText(editText);

            View btSearch = itemView.findViewById(R.id.BTSearch);
            applyDarkThemeToButton(btSearch);

            itemView.findViewById(R.id.BTSearch).setOnClickListener((v) -> {
                openDirectoryCallback = (path) -> {
                    drive[1] = path;
                    editText.setText(path);
                };
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.fromFile(Environment.getExternalStorageDirectory()));
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_DIRECTORY_REQUEST_CODE);
            });

            itemView.findViewById(R.id.BTRemove).setOnClickListener((v) -> {
                parent.removeView(itemView);
                if (parent.getChildCount() == 0)
                    emptyTextView.setVisibility(View.VISIBLE);
            });
            parent.addView(itemView);

            emptyTextView.setVisibility(View.GONE);
        };
        for (String[] drive : Container.drivesIterator(drives))
            addItem.call(drive);

        view.findViewById(R.id.BTAddDrive).setOnClickListener((v) -> {
            if (parent.getChildCount() >= Container.MAX_DRIVE_LETTERS)
                return;
            final String nextDriveLetter = String.valueOf(driveLetters[parent.getChildCount()].charAt(0));
            addItem.call(new String[] { nextDriveLetter, "" });
        });

        if (drives.isEmpty())
            emptyTextView.setVisibility(View.VISIBLE);
    }

    private static void applyDarkThemeToEditText(EditText editText) {
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

    private void applyDarkThemeToButton(View button) {

    }

    private void loadWineVersionSpinner(final View view, Spinner sWineVersion, Spinner sBox64Version) {
        final Context context = getContext();

        sWineVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                FrameLayout fexcoreFL = view.findViewById(R.id.fexcoreFrame);
                Spinner sEmulator = view.findViewById(R.id.SEmulator);
                Spinner sEmulator64 = view.findViewById(R.id.SEmulator64);
                Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);
                View vDXWrapperConfig = view.findViewById(R.id.BTDXWrapperConfig);
                sEmulator64.setEnabled(false);
                String wineVersion = sWineVersion.getSelectedItem().toString();
                WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
                if (wineInfo.isArm64EC()) {
                    fexcoreFL.setVisibility(View.VISIBLE);
                    sEmulator.setEnabled(true);
                    sEmulator64.setSelection(0);
                    if (!isEditMode())
                        sEmulator.setSelection(0);
                } else {
                    fexcoreFL.setVisibility(View.GONE);
                    sEmulator.setEnabled(false);
                    sEmulator.setSelection(1);
                    sEmulator64.setSelection(1);
                }
                loadBox64VersionSpinner(context, container, contentsManager, sBox64Version, wineInfo.isArm64EC());
                setupDXWrapperSpinnerWithFragment(sDXWrapper, vDXWrapperConfig, wineInfo.isArm64EC());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        view.findViewById(R.id.LLWineVersion).setVisibility(View.VISIBLE);
        String[] versions = getResources().getStringArray(R.array.wine_entries);
        ArrayList<String> wineVersions = new ArrayList<>();
        wineVersions.addAll(Arrays.asList(versions));
        for (String identifier : ProtonPackageManager.getInstalledIdentifiers(context))
            if (!wineVersions.contains(identifier)) wineVersions.add(identifier);
        for (ContentProfile profile : contentsManager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE))
            wineVersions.add(ContentsManager.getEntryName(profile));
        for (ContentProfile profile : contentsManager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON))
            wineVersions.add(ContentsManager.getEntryName(profile));
        ArrayAdapter<String> wineVersionAdapter = new ArrayAdapter<>(context, R.layout.spinner_item_amoled, wineVersions);
        wineVersionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled);
        sWineVersion.setAdapter(wineVersionAdapter);
        sWineVersion.setPopupBackgroundResource(R.drawable.dialog_background_dark_blue);
        if (isEditMode()) {
            AppUtils.setSpinnerSelectionFromValue(sWineVersion, container.getWineVersion());
        } else if (!WineRuntimeGuard.isBundledMainInstalled(context)) {
            // Existing users may have Proton 9 installed but not yet Proton 11.
            // Prefer an installed runtime instead of a new default whose files are missing.
            for (String identifier : ProtonPackageManager.getInstalledIdentifiers(context)) {
                if (!WineInfo.MAIN_WINE_VERSION.identifier().equals(identifier)) {
                    AppUtils.setSpinnerSelectionFromValue(sWineVersion, identifier);
                    break;
                }
            }
        }
    }

    private void showWineVersionDownloadPopup(View anchor, Spinner sWineVersion, Runnable refreshAction) {
        List<ContentProfile.ContentType> types = Arrays.asList(ContentProfile.ContentType.CONTENT_TYPE_PROTON, ContentProfile.ContentType.CONTENT_TYPE_WINE);
        showInstallChoiceMenu(anchor, () -> downloadContentForTypes(types, refreshAction), () -> {
            pendingImportContentTypes = new ArrayList<>(types);
            pendingImportRefreshAction = refreshAction;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        });
    }

    private ContentDialog createProtonDialog(int titleResId) {
        ContentDialog dialog = new ContentDialog(context, R.layout.proton_options_dialog);
        dialog.getContentView().setBackgroundResource(R.drawable.dialog_background_dark_blue);
        dialog.setTitle(titleResId);
        dialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        View frameLayout = dialog.findViewById(R.id.FrameLayout);
        ViewGroup.LayoutParams layoutParams = frameLayout.getLayoutParams();
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        frameLayout.setLayoutParams(layoutParams);
        dialog.setOnShowListener(d -> {
            int maxHeight = (int) (AppUtils.getScreenHeight() * 0.72f);
            if (frameLayout.getHeight() > maxHeight) {
                ViewGroup.LayoutParams cappedParams = frameLayout.getLayoutParams();
                cappedParams.height = maxHeight;
                frameLayout.setLayoutParams(cappedParams);
                if (frameLayout instanceof ViewGroup && ((ViewGroup) frameLayout).getChildCount() > 0) {
                    View scrollContent = ((ViewGroup) frameLayout).getChildAt(0);
                    ViewGroup.LayoutParams scrollParams = scrollContent.getLayoutParams();
                    scrollParams.height = maxHeight;
                    scrollContent.setLayoutParams(scrollParams);
                }
            }
        });
        return dialog;
    }

    private void addProtonDialogRow(ContentDialog dialog, LinearLayout content, int iconResId, String title, String subtitle, Runnable action) {
        int accentColor = Color.parseColor("#0055ff");
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundResource(R.drawable.proton_dialog_row_background);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(4), 0, dp(4));
        content.addView(row, rowParams);
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconResId);
        icon.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconParams);
        LinearLayout textPanel = new LinearLayout(context);
        textPanel.setOrientation(LinearLayout.VERTICAL);
        row.addView(textPanel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textPanel.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(Color.parseColor("#b0b0b0"));
        subtitleView.setTextSize(12);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textPanel.addView(subtitleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void populateReleaseProtonList(LinearLayout list, Spinner sWineVersion) {
        if (list == null) return;
        list.removeAllViews();
        TextView titleView = new TextView(context);
        titleView.setText(R.string.available_protons);
        titleView.setTextColor(Color.parseColor("#0055FF"));
        titleView.setTextSize(13);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(dp(2), 0, 0, dp(4));
        list.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (ProtonPackageManager.PackageInfo packageInfo : ProtonPackageManager.getPackages()) {
            boolean installed = ProtonPackageManager.isInstalled(context, packageInfo.identifier);
            addReleaseProtonRow(list, sWineVersion, packageInfo, installed, () -> {
                if (installed && isEditMode()) {
                    AppUtils.showToast(getContext(), R.string.wine_version_locked_for_existing_container);
                    list.setVisibility(View.GONE);
                    return;
                }
                downloadReleaseProton(packageInfo, sWineVersion, () -> {
                    populateReleaseProtonList(list, sWineVersion);
                    list.setVisibility(View.GONE);
                });
            });
        }
        if (sWineVersion == null) return;
        HashSet<String> listedVersions = new HashSet<>();
        for (String builtInVersion : getResources().getStringArray(R.array.wine_entries)) listedVersions.add(builtInVersion);
        for (ContentProfile.ContentType type : Arrays.asList(ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            for (ContentProfile profile : contentsManager.getInstalledProfiles(type)) {
                String version = ContentsManager.getEntryName(profile);
                if (!listedVersions.add(version)) continue;
                addInstalledWineVersionRow(list, version, () -> {
                    if (isEditMode()) {
                        AppUtils.showToast(getContext(), R.string.wine_version_locked_for_existing_container);
                    } else {
                        AppUtils.setSpinnerSelectionFromValue(sWineVersion, version);
                    }
                    list.setVisibility(View.GONE);
                }, () -> removeWineVersion(version, sWineVersion, () -> populateReleaseProtonList(list, sWineVersion)));
            }
        }
    }

    private String displayWineVersionTitle(String version) {
        if (version == null) return "";
        return version.replace("ARM64EC", "arm64ec");
    }

    private void addInstalledWineVersionRow(LinearLayout list, String version, Runnable action, Runnable deleteAction) {
        addInlineWineRow(list, displayWineVersionTitle(version), getString(R.string.proton_package_installed), R.drawable.icon_confirm, R.string.select, action, deleteAction);
    }

    private void addReleaseProtonRow(LinearLayout list, Spinner sWineVersion, ProtonPackageManager.PackageInfo packageInfo, boolean installed, Runnable action) {
        Runnable deleteAction = installed && !ProtonPackageManager.DEFAULT_IDENTIFIER.equals(packageInfo.identifier)
                ? () -> removeWineVersion(packageInfo.identifier, sWineVersion, () -> populateReleaseProtonList(list, sWineVersion))
                : null;
        addInlineWineRow(list, displayWineVersionTitle(packageInfo.title), getString(installed ? R.string.proton_package_installed : R.string.proton_package_not_installed),
                installed ? R.drawable.icon_confirm : R.drawable.icon_popup_menu_download, installed ? R.string.select : R.string.install, action, deleteAction);
    }

    private void addInlineWineRow(LinearLayout list, String title, String subtitle, int iconResId, int actionTextResId, Runnable action, Runnable deleteAction) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackgroundResource(R.drawable.proton_inline_row_background);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(4), 0, dp(4));
        list.addView(row, rowParams);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconResId);
        icon.setColorFilter(Color.parseColor("#0055FF"), PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.setMargins(0, 0, dp(10), 0);
        row.addView(icon, iconParams);

        LinearLayout textPanel = new LinearLayout(context);
        textPanel.setOrientation(LinearLayout.VERTICAL);
        row.addView(textPanel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView packageTitleView = new TextView(context);
        packageTitleView.setText(title);
        packageTitleView.setTextColor(Color.WHITE);
        packageTitleView.setTextSize(14);
        packageTitleView.setSingleLine(true);
        packageTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textPanel.addView(packageTitleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(Color.parseColor("#9CA8B8"));
        subtitleView.setTextSize(12);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textPanel.addView(subtitleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView actionView = new TextView(context);
        actionView.setText(actionTextResId);
        actionView.setTextColor(Color.parseColor("#0055FF"));
        actionView.setTextSize(13);
        actionView.setTypeface(null, android.graphics.Typeface.BOLD);
        actionView.setPadding(dp(10), dp(6), dp(2), dp(6));
        row.addView(actionView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (deleteAction != null) {
            ImageView deleteView = new ImageView(context);
            deleteView.setImageResource(R.drawable.icon_popup_menu_remove);
            deleteView.setColorFilter(Color.parseColor("#0055FF"), PorterDuff.Mode.SRC_IN);
            deleteView.setPadding(dp(6), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            deleteParams.setMargins(dp(6), 0, 0, 0);
            row.addView(deleteView, deleteParams);
            deleteView.setOnClickListener(v -> {
                deleteAction.run();
            });
            deleteView.setFocusable(true);
        }

        row.setOnClickListener(v -> action.run());
    }

    private void downloadReleaseProton(ProtonPackageManager.PackageInfo packageInfo, Spinner sWineVersion, Runnable onFinished) {
        if (ProtonPackageManager.isInstalled(context, packageInfo.identifier)) {
            if (!isEditMode()) refreshWineVersionSpinner(sWineVersion, packageInfo.identifier);
            onFinished.run();
            return;
        }
        DownloadProgressDialog dialog = new DownloadProgressDialog(getActivity());
        dialog.show(R.string.downloading_proton);
        CONTENT_IO_EXECUTOR.execute(() -> {
            File output = new File(getContext().getCacheDir(), packageInfo.identifier + ".tar.zst");
            boolean downloaded = ProtonPackageManager.downloadPackage(packageInfo, output, progress -> requireActivity().runOnUiThread(() -> dialog.setProgress(progress)));
            boolean installed = downloaded && ProtonPackageManager.installPackage(getContext(), packageInfo.identifier, output);
            requireActivity().runOnUiThread(() -> {
                dialog.closeOnUiThread();
                if (installed) {
                    if (!isEditMode()) refreshWineVersionSpinner(sWineVersion, packageInfo.identifier);
                } else AppUtils.showToast(getContext(), R.string.unable_to_install_proton);
                onFinished.run();
            });
        });
    }

    private void removeWineVersion(String selected, Spinner sWineVersion, Runnable refreshAction) {
        if (selected == null || selected.isEmpty() || selected.equals(ProtonPackageManager.DEFAULT_IDENTIFIER)) {
            AppUtils.showToast(getContext(), R.string.no_protons_to_delete);
            return;
        }
        for (Container existingContainer : manager.getContainers()) {
            if (selected.equals(existingContainer.getWineVersion())) {
                ContentDialog.alert(getContext(), String.format(getString(R.string.unable_to_remove_content_since_container_using), existingContainer.getName()), null);
                return;
            }
        }
        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_content, () -> {
            ContentProfile profile = contentsManager.getProfileByEntryName(selected);
            if (profile != null) contentsManager.removeContent(profile);
            else if (ProtonPackageManager.isKnownPackage(selected)) ProtonPackageManager.deletePackage(getContext(), selected);
            contentsManager.syncContents();
            if (sWineVersion != null) refreshWineVersionSpinner(sWineVersion, WineInfo.MAIN_WINE_VERSION.identifier());
            if (refreshAction != null) refreshAction.run();
        });
    }

    private void refreshWineVersionSpinner(Spinner sWineVersion, String selection) {
        loadWineVersionSpinner(requireView(), sWineVersion, requireView().findViewById(R.id.SBox64Version));
        AppUtils.setSpinnerSelectionFromValue(sWineVersion, selection);
    }

    public String getControllerMapping(View view) {

        int[] ids = {
                R.id.SButtonA, R.id.SButtonB, R.id.SButtonX, R.id.SButtonY, R.id.SButtonGrip, R.id.SButtonTrigger,
                R.id.SThumbstickUp, R.id.SThumbstickDown, R.id.SThumbstickLeft, R.id.SThumbstickRight
        };
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
        ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(),
                android.R.layout.simple_spinner_dropdown_item, array);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        byte keycode = isEditMode() ? container.getControllerMapping(mapping) : (byte) defaultValue;
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].id == keycode) {
                index = i;
                break;
            }
        }
        spinner.setSelection(isEditMode() && (index != 0) ? index : defaultValue);
    }

    public static void updateGraphicsDriverSpinner(Context context, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.graphics_driver_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }

    public static void loadBox64VersionSpinner(Context context, Container container, ContentsManager manager,
            Spinner spinner, boolean isArm64EC) {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        versions.add(isArm64EC ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
        if (container != null && container.getBox64Version() != null && !container.getBox64Version().isEmpty()) {
            versions.add(container.getBox64Version());
        }
        if (!isArm64EC) {
            for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                versions.add(entryName.substring(firstDashIndex + 1));
            }
        } else {
            for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                versions.add(entryName.substring(firstDashIndex + 1));
            }
        }
        List<String> itemList = new ArrayList<>(versions);
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        if (container != null)
            AppUtils.setSpinnerSelectionFromValue(spinner, container.getBox64Version());
        else
            AppUtils.setSpinnerSelectionFromValue(spinner,
                    (isArm64EC) ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
    }

    private ContentProfile.ContentType getBox64LikeContentType(Context context, Spinner sWineVersion) {
        String wineVersion = sWineVersion.getSelectedItem() != null ? sWineVersion.getSelectedItem().toString()
                : (isEditMode() ? container.getWineVersion() : WineInfo.MAIN_WINE_VERSION.identifier());
        WineInfo wi = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        return wi.isArm64EC() ? ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 : ContentProfile.ContentType.CONTENT_TYPE_BOX64;
    }

    @Override
    public void showInstallChoicePopup(View anchor, List<ContentProfile.ContentType> types, Runnable refreshAction) {
        showInstallChoiceMenu(anchor, () -> handleInstallChoice(1, types, refreshAction), () -> handleInstallChoice(0, types, refreshAction));
    }

    private void showInstallChoiceMenu(View anchor, Runnable downloadAction, Runnable openAction) {
        int width = dp(170);
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(4), 0, dp(4));
        menu.setBackgroundResource(R.drawable.install_choice_popup_background);
        PopupWindow popupWindow = new PopupWindow(menu, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setOutsideTouchable(true);
        popupWindow.setElevation(dp(8));
        addInstallChoiceMenuRow(popupWindow, menu, R.drawable.icon_popup_menu_open, getString(R.string.open_file), openAction);
        addInstallChoiceMenuRow(popupWindow, menu, R.drawable.icon_popup_menu_download, getString(R.string.download_file), downloadAction);
        popupWindow.showAsDropDown(anchor, -width + anchor.getWidth(), dp(2));
    }

    private void addInstallChoiceMenuRow(PopupWindow popupWindow, LinearLayout menu, int iconResId, String text, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconResId);
        icon.setColorFilter(Color.parseColor("#0055ff"), PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconParams);
        TextView titleView = new TextView(context);
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

    private void forceShowMenuIcons(PopupMenu popupMenu) {
        try {
            java.lang.reflect.Field field = PopupMenu.class.getDeclaredField("mPopup");
            field.setAccessible(true);
            Object menuHelper = field.get(popupMenu);
            menuHelper.getClass().getDeclaredMethod("setForceShowIcon", boolean.class).invoke(menuHelper, true);
        } catch (Exception ignored) {
        }
    }

    private void handleInstallChoice(int idx, List<ContentProfile.ContentType> types, Runnable refreshAction) {
        if (idx == 0) {
            pendingImportContentTypes = new ArrayList<>(types);
            pendingImportRefreshAction = refreshAction;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
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
        DownloadProgressDialog dialog = new DownloadProgressDialog(getActivity());
        dialog.show(R.string.loading);
        dialog.setProgress(10);
        CONTENT_IO_EXECUTOR.execute(() -> {
            String contentsURL = PreferenceManager.getDefaultSharedPreferences(getContext())
                    .getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
            String json = Downloader.downloadString(contentsURL);
            requireActivity().runOnUiThread(() -> dialog.setProgress(65));
            if (json != null) contentsManager.setRemoteProfiles(json);
            List<ContentProfile> candidates = new ArrayList<>();
            for (ContentProfile.ContentType type : types) {
                for (ContentProfile profile : contentsManager.getProfiles(type)) {
                    if (profile.remoteUrl != null) candidates.add(profile);
                }
            }
            requireActivity().runOnUiThread(() -> {
                dialog.setProgress(100);
                dialog.closeOnUiThread();
                if (candidates.isEmpty()) {
                    AppUtils.showToast(getContext(), R.string.no_items_to_display);
                    return;
                }
                String[] entries = new String[candidates.size()];
                for (int i = 0; i < candidates.size(); i++) entries[i] = candidates.get(i).verName;
                ContentDialog.showSingleChoiceList(requireContext(), R.string.install_content, entries, idx -> {
                    if (idx < 0 || idx >= candidates.size()) return;
                    downloadAndInstallProfile(candidates.get(idx), refreshAction);
                });
            });
        });
    }

    private void downloadAndInstallProfile(ContentProfile profile, Runnable refreshAction) {
        DownloadProgressDialog dialog = new DownloadProgressDialog(getActivity());
        dialog.show(R.string.downloading_file);
        CONTENT_IO_EXECUTOR.execute(() -> {
            File output = new File(getContext().getCacheDir(), "content_" + System.currentTimeMillis());
            if (!Downloader.downloadFile(profile.remoteUrl, output, progress -> requireActivity().runOnUiThread(() -> dialog.setProgress(progress)))) {
                requireActivity().runOnUiThread(() -> {
                    dialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), R.string.unable_to_download_file);
                });
                return;
            }
            installImportedContent(Uri.fromFile(output), Collections.singletonList(profile.type), refreshAction, dialog);
        });
    }

    private void installImportedContent(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction) {
        PreloaderDialog dialog = new PreloaderDialog(getActivity());
        dialog.showOnUiThread(R.string.installing_content);
        installImportedContent(uri, expectedTypes, refreshAction, dialog);
    }

    private void installImportedContent(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction, @Nullable PreloaderDialog existingDialog) {
        installImportedContentInternal(uri, expectedTypes, refreshAction, existingDialog::closeOnUiThread);
    }

    private void installImportedContent(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction, @Nullable DownloadProgressDialog existingDialog) {
        installImportedContentInternal(uri, expectedTypes, refreshAction, existingDialog::closeOnUiThread);
    }

    private void installImportedContentInternal(Uri uri, List<ContentProfile.ContentType> expectedTypes, Runnable refreshAction, Runnable closeDialog) {
        CONTENT_IO_EXECUTOR.execute(() -> contentsManager.extraContentFile(uri, new ContentsManager.OnInstallFinishedCallback() {
            @Override
            public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                requireActivity().runOnUiThread(() -> {
                    closeDialog.run();
                    AppUtils.showToast(getContext(), R.string.install_failed);
                });
            }

            @Override
            public void onSucceed(ContentProfile profile) {
                if (!expectedTypes.contains(profile.type)) {
                    requireActivity().runOnUiThread(() -> {
                        closeDialog.run();
                        ContentDialog.alert(getContext(), R.string.profile_cannot_be_recognized, null);
                    });
                    return;
                }
                contentsManager.finishInstallContent(profile, new ContentsManager.OnInstallFinishedCallback() {
                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        requireActivity().runOnUiThread(() -> {
                            closeDialog.run();
                            AppUtils.showToast(getContext(), R.string.install_failed);
                        });
                    }

                    @Override
                    public void onSucceed(ContentProfile installedProfile) {
                        contentsManager.syncContents();
                        requireActivity().runOnUiThread(() -> {
                            closeDialog.run();
                            refreshAction.run();
                            selectInstalledWineContent(installedProfile);
                        });
                    }
                });
            }
        }));
    }

    private void selectInstalledWineContent(ContentProfile installedProfile) {
        if (isEditMode()) return;
        if (installedProfile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE && installedProfile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) return;
        Spinner sWineVersion = requireView().findViewById(R.id.SWineVersion);
        if (sWineVersion != null) AppUtils.setSpinnerSelectionFromValue(sWineVersion, ContentsManager.getEntryName(installedProfile));
    }

}

