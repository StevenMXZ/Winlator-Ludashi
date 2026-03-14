package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.collection.ArrayMap;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.box64.Box64EditPresetDialog;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ArrayUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.fexcore.FEXCoreEditPresetDialog;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;

/* loaded from: classes8.dex */
public class SettingsFragment extends Fragment {
    public static final String DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme";
    private static final int REQUEST_CODE_IMPORT_BOX64_PRESET = 1004;
    private static final int REQUEST_CODE_IMPORT_FEXCORE_PRESET = 1005;
    private static final int REQUEST_CODE_INSTALL_SOUNDFONT = 1001;
    private static final int REQUEST_CODE_SHORTCUT_EXPORT_PATH = 1003;
    private static final int REQUEST_CODE_WINLATOR_PATH = 1002;
    private CheckBox cbCursorLock;
    private CheckBox cbDarkMode;
    private CheckBox cbEnableBigPictureMode;
    private CheckBox cbEnableCustomApiKey;
    private CheckBox cbXinputToggle;
    private EditText etCustomApiKey;
    private Callback<Uri> installSoundFontCallback;
    boolean isDarkMode;
    private SharedPreferences preferences;
    private PreloaderDialog preloaderDialog;
    public static final String DEFAULT_WINLATOR_PATH = Environment.getExternalStorageDirectory().getPath() + "/Winlator";
    public static final String DEFAULT_SHORTCUT_EXPORT_PATH = DEFAULT_WINLATOR_PATH + "/Shortcuts";

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        this.preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applyDynamicStylesRecursively(view);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(com.ludashi.benchmark.R.string.settings);
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(com.ludashi.benchmark.R.layout.settings_fragment, container, false);
        final Context context = getContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.isDarkMode = this.preferences.getBoolean("dark_mode", false);
        applyDynamicStyles(view, this.isDarkMode);
        this.cbDarkMode = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBDarkMode);
        this.cbDarkMode.setChecked(this.preferences.getBoolean("dark_mode", false));
        this.cbDarkMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda44
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                SettingsFragment.this.lambda$onCreateView$0(compoundButton, z);
            }
        });
        this.cbEnableBigPictureMode = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableBigPictureMode);
        this.cbEnableBigPictureMode.setChecked(this.preferences.getBoolean("enable_big_picture_mode", false));
        initCustomApiKeySettings(view);
        this.cbCursorLock = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBCursorLock);
        this.cbCursorLock.setChecked(this.preferences.getBoolean("cursor_lock", false));
        this.cbXinputToggle = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBXinputToggle);
        this.cbXinputToggle.setChecked(this.preferences.getBoolean("xinput_toggle", false));
        Button btnChooseWinlatorPath = (Button) view.findViewById(com.ludashi.benchmark.R.id.BTChooseWinlatorPath);
        TextView tvWinlatorPath = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVWinlatorPath);
        String savedUriString = this.preferences.getString("winlator_path_uri", null);
        if (savedUriString == null) {
            tvWinlatorPath.setText(DEFAULT_WINLATOR_PATH);
        } else {
            Uri savedUri = Uri.parse(savedUriString);
            String displayPath = FileUtils.getFilePathFromUri(getContext(), savedUri);
            tvWinlatorPath.setText(displayPath != null ? displayPath : savedUriString);
        }
        btnChooseWinlatorPath.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda49
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$onCreateView$1(view2);
            }
        });
        Button btChooseShortcutExportPath = (Button) view.findViewById(com.ludashi.benchmark.R.id.BTChooseShortcutExportPath);
        TextView tvShortcutExportPath = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVShortcutExportPath);
        String savedUriString2 = this.preferences.getString("shortcuts_export_path_uri", null);
        if (savedUriString2 != null) {
            Uri savedUri2 = Uri.parse(savedUriString2);
            String displayPath2 = FileUtils.getFilePathFromUri(context, savedUri2);
            tvShortcutExportPath.setText(displayPath2 != null ? displayPath2 : savedUriString2);
        } else {
            tvShortcutExportPath.setText(DEFAULT_SHORTCUT_EXPORT_PATH);
        }
        btChooseShortcutExportPath.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda50
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$onCreateView$2(view2);
            }
        });
        final Spinner sBox64Preset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBox64Preset);
        loadBox64PresetSpinners(view, sBox64Preset);
        final Spinner sFEXCorePreset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SFEXCorePreset);
        loadFEXCorePresetSpinners(view, sFEXCorePreset);
        final Spinner sMIDISoundFont = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SMIDISoundFont);
        sMIDISoundFont.setPopupBackgroundResource(this.isDarkMode ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        View btInstallSF = view.findViewById(com.ludashi.benchmark.R.id.BTInstallSF);
        View btRemoveSF = view.findViewById(com.ludashi.benchmark.R.id.BTRemoveSF);
        MidiManager.loadSFSpinnerWithoutDisabled(sMIDISoundFont);
        btInstallSF.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda51
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$onCreateView$4(context, sMIDISoundFont, view2);
            }
        });
        btRemoveSF.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda52
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.lambda$onCreateView$6(sMIDISoundFont, context, view2);
            }
        });
        final CheckBox cbUseDRI3 = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBUseDRI3);
        cbUseDRI3.setChecked(this.preferences.getBoolean("use_dri3", true));
        final CheckBox cbUseXR = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBUseXR);
        cbUseXR.setChecked(this.preferences.getBoolean("use_xr", true));
        if (!XrActivity.isSupported()) {
            cbUseXR.setVisibility(8);
        }
        final CheckBox cbEnableWineDebug = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableWineDebug);
        cbEnableWineDebug.setChecked(this.preferences.getBoolean("enable_wine_debug", false));
        final ArrayList<String> wineDebugChannels = new ArrayList<>(Arrays.asList(this.preferences.getString("wine_debug_channels", DEFAULT_WINE_DEBUG_CHANNELS).split(",")));
        loadWineDebugChannels(view, wineDebugChannels);
        final CheckBox cbEnableBox64Logs = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableBox64Logs);
        cbEnableBox64Logs.setChecked(this.preferences.getBoolean("enable_box64_logs", false));
        final TextView tvCursorSpeed = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVCursorSpeed);
        final SeekBar sbCursorSpeed = (SeekBar) view.findViewById(com.ludashi.benchmark.R.id.SBCursorSpeed);
        sbCursorSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: com.winlator.cmod.SettingsFragment.2
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvCursorSpeed.setText(progress + "%");
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sbCursorSpeed.setProgress((int) (this.preferences.getFloat("cursor_speed", 1.0f) * 100.0f));
        final CheckBox cbEnableFileProvider = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableFileProvider);
        View btHelpFileProvider = view.findViewById(com.ludashi.benchmark.R.id.BTHelpFileProvider);
        cbEnableFileProvider.setChecked(this.preferences.getBoolean("enable_file_provider", true));
        cbEnableFileProvider.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda53
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                AppUtils.showToast(context, com.ludashi.benchmark.R.string.take_effect_next_startup);
            }
        });
        btHelpFileProvider.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                AppUtils.showHelpBox(context, view2, com.ludashi.benchmark.R.string.help_file_provider);
            }
        });
        final CheckBox cbOpenInBrowser = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBOpenWithAndroidBrowser);
        cbOpenInBrowser.setChecked(this.preferences.getBoolean("open_with_android_browser", false));
        final CheckBox cbShareClipboard = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBShareAndroidClipboard);
        cbShareClipboard.setChecked(this.preferences.getBoolean("share_android_clipboard", false));
        final EditText etDownloadableContentsURL = (EditText) view.findViewById(com.ludashi.benchmark.R.id.ETDownloadableContentsURL);
        etDownloadableContentsURL.setText(this.preferences.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES));
        view.findViewById(com.ludashi.benchmark.R.id.BTReInstallImagefs).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$onCreateView$10(context, view2);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTConfirm).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$onCreateView$11(sBox64Preset, sFEXCorePreset, cbUseDRI3, cbUseXR, sbCursorSpeed, cbEnableWineDebug, cbEnableBox64Logs, cbEnableFileProvider, cbOpenInBrowser, cbShareClipboard, etDownloadableContentsURL, wineDebugChannels, view, view2);
            }
        });
        return view;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$0(CompoundButton buttonView, boolean isChecked) {
        SharedPreferences.Editor editor = this.preferences.edit();
        editor.putBoolean("dark_mode", isChecked);
        editor.apply();
        updateTheme(isChecked);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$1(View v) {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT_TREE");
        startActivityForResult(intent, 1002);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$2(View v) {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT_TREE");
        startActivityForResult(intent, 1003);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$4(final Context context, final Spinner sMIDISoundFont, View v) {
        this.installSoundFontCallback = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda26
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$onCreateView$3(context, sMIDISoundFont, (Uri) obj);
            }
        };
        openFile(1001);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$3(Context context, Spinner sMIDISoundFont, Uri uri) {
        PreloaderDialog dialog = new PreloaderDialog(requireActivity());
        dialog.showOnUiThread(com.ludashi.benchmark.R.string.installing_content);
        MidiManager.installSF2File(context, uri, new AnonymousClass1(dialog, context, sMIDISoundFont));
    }

    /* renamed from: com.winlator.cmod.SettingsFragment$1, reason: invalid class name */
    class AnonymousClass1 implements MidiManager.OnSoundFontInstalledCallback {
        final /* synthetic */ Context val$context;
        final /* synthetic */ PreloaderDialog val$dialog;
        final /* synthetic */ Spinner val$sMIDISoundFont;

        AnonymousClass1(PreloaderDialog preloaderDialog, Context context, Spinner spinner) {
            this.val$dialog = preloaderDialog;
            this.val$context = context;
            this.val$sMIDISoundFont = spinner;
        }

        @Override // com.winlator.cmod.midi.MidiManager.OnSoundFontInstalledCallback
        public void onSuccess() {
            this.val$dialog.closeOnUiThread();
            FragmentActivity requireActivity = SettingsFragment.this.requireActivity();
            final Context context = this.val$context;
            final Spinner spinner = this.val$sMIDISoundFont;
            requireActivity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$1$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    SettingsFragment.AnonymousClass1.lambda$onSuccess$0(context, spinner);
                }
            });
        }

        static /* synthetic */ void lambda$onSuccess$0(Context context, Spinner sMIDISoundFont) {
            ContentDialog.alert(context, com.ludashi.benchmark.R.string.sound_font_installed_success, (Runnable) null);
            MidiManager.loadSFSpinnerWithoutDisabled(sMIDISoundFont);
        }

        @Override // com.winlator.cmod.midi.MidiManager.OnSoundFontInstalledCallback
        public void onFailed(int reason) {
            final int resId;
            this.val$dialog.closeOnUiThread();
            switch (reason) {
                case 1:
                    resId = com.ludashi.benchmark.R.string.sound_font_already_exist;
                    break;
                case 2:
                    resId = com.ludashi.benchmark.R.string.sound_font_bad_format;
                    break;
                default:
                    resId = com.ludashi.benchmark.R.string.sound_font_installed_failed;
                    break;
            }
            FragmentActivity requireActivity = SettingsFragment.this.requireActivity();
            final Context context = this.val$context;
            requireActivity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$1$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    ContentDialog.alert(context, resId, (Runnable) null);
                }
            });
        }
    }

    static /* synthetic */ void lambda$onCreateView$6(final Spinner sMIDISoundFont, final Context context, View v) {
        if (sMIDISoundFont.getSelectedItemPosition() != 0) {
            ContentDialog.confirm(context, com.ludashi.benchmark.R.string.do_you_want_to_remove_this_sound_font, new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda41
                @Override // java.lang.Runnable
                public final void run() {
                    SettingsFragment.lambda$onCreateView$5(context, sMIDISoundFont);
                }
            });
        } else {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.cannot_remove_default_sound_font);
        }
    }

    static /* synthetic */ void lambda$onCreateView$5(Context context, Spinner sMIDISoundFont) {
        if (MidiManager.removeSF2File(context, sMIDISoundFont.getSelectedItem().toString())) {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.sound_font_removed_success);
            MidiManager.loadSFSpinnerWithoutDisabled(sMIDISoundFont);
        } else {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.sound_font_removed_failed);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$10(Context context, View v) {
        ContentDialog.confirm(context, com.ludashi.benchmark.R.string.do_you_want_to_reinstall_imagefs, new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda43
            @Override // java.lang.Runnable
            public final void run() {
                SettingsFragment.this.lambda$onCreateView$9();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$9() {
        ImageFsInstaller.installFromAssets((MainActivity) getActivity());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$11(Spinner sBox64Preset, Spinner sFEXCorePreset, CheckBox cbUseDRI3, CheckBox cbUseXR, SeekBar sbCursorSpeed, CheckBox cbEnableWineDebug, CheckBox cbEnableBox64Logs, CheckBox cbEnableFileProvider, CheckBox cbOpenInBrowser, CheckBox cbShareClipboard, EditText etDownloadableContentsURL, ArrayList wineDebugChannels, View view, View v) {
        SharedPreferences.Editor editor = this.preferences.edit();
        editor.putBoolean("dark_mode", this.cbDarkMode.isChecked());
        editor.putString("box64_preset", Box64PresetManager.getSpinnerSelectedId(sBox64Preset));
        editor.putString("fexcore_preset", FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset));
        editor.putBoolean("use_dri3", cbUseDRI3.isChecked());
        editor.putBoolean("use_xr", cbUseXR.isChecked());
        editor.putFloat("cursor_speed", sbCursorSpeed.getProgress() / 100.0f);
        editor.putBoolean("enable_wine_debug", cbEnableWineDebug.isChecked());
        editor.putBoolean("enable_box64_logs", cbEnableBox64Logs.isChecked());
        editor.putBoolean("cursor_lock", this.cbCursorLock.isChecked());
        editor.putBoolean("xinput_toggle", this.cbXinputToggle.isChecked());
        editor.putBoolean("enable_file_provider", cbEnableFileProvider.isChecked());
        editor.putBoolean("open_with_android_browser", cbOpenInBrowser.isChecked());
        editor.putBoolean("share_android_clipboard", cbShareClipboard.isChecked());
        editor.putString("downloadable_contents_url", etDownloadableContentsURL.getText().toString());
        if (wineDebugChannels.isEmpty()) {
            if (this.preferences.contains("wine_debug_channels")) {
                editor.remove("wine_debug_channels");
            } else if (this.preferences.contains("wine_debug_channels")) {
                editor.remove("wine_debug_channels");
            }
        } else {
            editor.putString("wine_debug_channels", String.join(",", wineDebugChannels));
        }
        editor.putBoolean("enable_big_picture_mode", ((CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableBigPictureMode)).isChecked());
        saveCustomApiKeySettings(editor);
        if (editor.commit()) {
            NavigationView navigationView = (NavigationView) getActivity().findViewById(com.ludashi.benchmark.R.id.NavigationView);
            navigationView.setCheckedItem(com.ludashi.benchmark.R.id.main_menu_containers);
            FragmentManager fragmentManager = getParentFragmentManager();
            fragmentManager.beginTransaction().replace(com.ludashi.benchmark.R.id.FLFragmentContainer, new ContainersFragment()).commit();
        }
    }

    private void updateTheme(boolean isDarkMode) {
        if (isDarkMode) {
            getActivity().setTheme(2131820553);
        } else {
            getActivity().setTheme(com.ludashi.benchmark.R.style.AppTheme);
        }
        getActivity().recreate();
    }

    private void applyDynamicStyles(View view, boolean isDarkMode) {
        Spinner sBox64Preset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBox64Preset);
        int i = com.ludashi.benchmark.R.drawable.content_dialog_background_dark;
        sBox64Preset.setPopupBackgroundResource(isDarkMode ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        Spinner sFEXCorePreset = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SFEXCorePreset);
        if (!isDarkMode) {
            i = com.ludashi.benchmark.R.drawable.content_dialog_background;
        }
        sFEXCorePreset.setPopupBackgroundResource(i);
    }

    private void applyDynamicStylesRecursively(View view) {
        TextView box64Label = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVBox64);
        applyFieldSetLabelStyle(box64Label, this.isDarkMode);
        TextView fexcoreLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVFEXCore);
        applyFieldSetLabelStyle(fexcoreLabel, this.isDarkMode);
        TextView soundLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVSound);
        applyFieldSetLabelStyle(soundLabel, this.isDarkMode);
        TextView themeLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVTheme);
        applyFieldSetLabelStyle(themeLabel, this.isDarkMode);
        TextView shortcutSettingsLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVShortcutSettings);
        applyFieldSetLabelStyle(shortcutSettingsLabel, this.isDarkMode);
        TextView bigPictureModeLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVBigPictureMode);
        applyFieldSetLabelStyle(bigPictureModeLabel, this.isDarkMode);
        TextView tvCustomApiKey = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVCustomApiKey);
        applyFieldSetLabelStyle(tvCustomApiKey, this.isDarkMode);
        TextView xServerLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVXServer);
        applyFieldSetLabelStyle(xServerLabel, this.isDarkMode);
        TextView logsLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVLogs);
        applyFieldSetLabelStyle(logsLabel, this.isDarkMode);
        TextView experimentalLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVExperimental);
        applyFieldSetLabelStyle(experimentalLabel, this.isDarkMode);
        TextView ImageFsLabel = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVImageFs);
        applyFieldSetLabelStyle(ImageFsLabel, this.isDarkMode);
    }

    private void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        if (isDarkMode) {
            textView.setTextColor(Color.parseColor("#cccccc"));
            textView.setBackgroundResource(com.ludashi.benchmark.R.color.window_background_color_dark);
        } else {
            textView.setTextColor(Color.parseColor("#bdbdbd"));
            textView.setBackgroundResource(com.ludashi.benchmark.R.color.window_background_color);
        }
    }

    private void initCustomApiKeySettings(View view) {
        this.cbEnableCustomApiKey = (CheckBox) view.findViewById(com.ludashi.benchmark.R.id.CBEnableCustomApiKey);
        this.etCustomApiKey = (EditText) view.findViewById(com.ludashi.benchmark.R.id.ETCustomApiKey);
        boolean isCustomApiKeyEnabled = this.preferences.getBoolean("enable_custom_api_key", false);
        String customApiKey = this.preferences.getString("custom_api_key", "");
        this.cbEnableCustomApiKey.setChecked(isCustomApiKeyEnabled);
        this.etCustomApiKey.setText(customApiKey);
        this.etCustomApiKey.setVisibility(isCustomApiKeyEnabled ? 0 : 8);
        this.cbEnableCustomApiKey.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda4
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                SettingsFragment.this.lambda$initCustomApiKeySettings$12(compoundButton, z);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTHelpApiKey).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$initCustomApiKeySettings$13(view2);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$initCustomApiKeySettings$12(CompoundButton buttonView, boolean isChecked) {
        this.etCustomApiKey.setVisibility(isChecked ? 0 : 8);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$initCustomApiKeySettings$13(View v) {
        Intent intent = new Intent("android.intent.action.VIEW", Uri.parse("https://www.steamgriddb.com/profile/preferences/api"));
        startActivity(intent);
    }

    private void saveCustomApiKeySettings(SharedPreferences.Editor editor) {
        boolean isCustomApiKeyEnabled = this.cbEnableCustomApiKey.isChecked();
        editor.putBoolean("enable_custom_api_key", isCustomApiKeyEnabled);
        if (isCustomApiKeyEnabled) {
            String customApiKey = this.etCustomApiKey.getText().toString().trim();
            editor.putString("custom_api_key", customApiKey);
        } else {
            editor.remove("custom_api_key");
        }
    }

    private void loadBox64PresetSpinners(View view, final Spinner sBox64Preset) {
        final ArrayMap<String, Spinner> spinners = new ArrayMap<String, Spinner>() { // from class: com.winlator.cmod.SettingsFragment.3
            {
                put("box64", sBox64Preset);
            }
        };
        final Context context = getContext();
        final Callback<String> updateSpinner = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda27
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$loadBox64PresetSpinners$14(spinners, (String) obj);
            }
        };
        final Callback<String> onAddPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda31
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.lambda$loadBox64PresetSpinners$16(context, updateSpinner, (String) obj);
            }
        };
        final Callback<String> onEditPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda32
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.lambda$loadBox64PresetSpinners$18(context, spinners, updateSpinner, (String) obj);
            }
        };
        final Callback<String> onDuplicatePreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda34
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                ContentDialog.confirm(r0, com.ludashi.benchmark.R.string.do_you_want_to_duplicate_this_preset, new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda45
                    @Override // java.lang.Runnable
                    public final void run() {
                        SettingsFragment.lambda$loadBox64PresetSpinners$19(ArrayMap.this, r2, r3, r4);
                    }
                });
            }
        };
        final Callback<String> onRemovePreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda35
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.lambda$loadBox64PresetSpinners$22(ArrayMap.this, context, updateSpinner, (String) obj);
            }
        };
        final Callback<String> onExportPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda36
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$loadBox64PresetSpinners$24(spinners, context, (String) obj);
            }
        };
        final Callback<String> onImportPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda37
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$loadBox64PresetSpinners$25((String) obj);
            }
        };
        updateSpinner.call("box64");
        view.findViewById(com.ludashi.benchmark.R.id.BTAddBox64Preset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda38
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("box64");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTEditBox64Preset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda39
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("box64");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTDuplicateBox64Preset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda40
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("box64");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTRemoveBox64Preset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda28
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("box64");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTExportBox64Preset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda29
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("box64");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTImportBox64Preset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda30
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("box64");
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    public /* synthetic */ void lambda$loadBox64PresetSpinners$14(ArrayMap spinners, String prefix) {
        Box64PresetManager.loadSpinner(prefix, (Spinner) spinners.get(prefix), this.preferences.getString(prefix + "_preset", "COMPATIBILITY"));
    }

    static /* synthetic */ void lambda$loadBox64PresetSpinners$16(Context context, final Callback updateSpinner, final String prefix) {
        Box64EditPresetDialog dialog = new Box64EditPresetDialog(context, prefix, null);
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda42
            @Override // java.lang.Runnable
            public final void run() {
                Callback.this.call(prefix);
            }
        });
        dialog.show();
    }

    /* JADX WARN: Multi-variable type inference failed */
    static /* synthetic */ void lambda$loadBox64PresetSpinners$18(Context context, ArrayMap spinners, final Callback updateSpinner, final String prefix) {
        Box64EditPresetDialog dialog = new Box64EditPresetDialog(context, prefix, Box64PresetManager.getSpinnerSelectedId((Spinner) spinners.get(prefix)));
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                Callback.this.call(prefix);
            }
        });
        dialog.show();
    }

    /* JADX WARN: Multi-variable type inference failed */
    static /* synthetic */ void lambda$loadBox64PresetSpinners$19(ArrayMap spinners, String prefix, Context context, Callback updateSpinner) {
        Spinner spinner = (Spinner) spinners.get(prefix);
        Box64PresetManager.duplicatePreset(prefix, context, Box64PresetManager.getSpinnerSelectedId(spinner));
        updateSpinner.call(prefix);
        spinner.setSelection(spinner.getCount() - 1);
    }

    /* JADX WARN: Multi-variable type inference failed */
    static /* synthetic */ void lambda$loadBox64PresetSpinners$22(ArrayMap spinners, final Context context, final Callback updateSpinner, final String prefix) {
        final String presetId = Box64PresetManager.getSpinnerSelectedId((Spinner) spinners.get(prefix));
        if (!presetId.startsWith("CUSTOM")) {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.you_cannot_remove_this_preset);
        } else {
            ContentDialog.confirm(context, com.ludashi.benchmark.R.string.do_you_want_to_remove_this_preset, new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda25
                @Override // java.lang.Runnable
                public final void run() {
                    SettingsFragment.lambda$loadBox64PresetSpinners$21(prefix, context, presetId, updateSpinner);
                }
            });
        }
    }

    static /* synthetic */ void lambda$loadBox64PresetSpinners$21(String prefix, Context context, String presetId, Callback updateSpinner) {
        Box64PresetManager.removePreset(prefix, context, presetId);
        updateSpinner.call(prefix);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    public /* synthetic */ void lambda$loadBox64PresetSpinners$24(ArrayMap spinners, final Context context, final String prefix) {
        final String presetId = Box64PresetManager.getSpinnerSelectedId((Spinner) spinners.get(prefix));
        if (!presetId.startsWith("CUSTOM")) {
            AppUtils.showToast(context, "Cannot export this preset");
        } else {
            getActivity().runOnUiThread(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda9
                @Override // java.lang.Runnable
                public final void run() {
                    Box64PresetManager.exportPreset(prefix, context, presetId);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadBox64PresetSpinners$25(String prefix) {
        openFile(1004);
    }

    private void loadFEXCorePresetSpinners(View view, final Spinner sFEXCorePreset) {
        final Context context = getContext();
        final Callback<String> updateSpinner = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda10
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$loadFEXCorePresetSpinners$32(sFEXCorePreset, (String) obj);
            }
        };
        final Callback<String> onAddPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda15
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.lambda$loadFEXCorePresetSpinners$34(context, updateSpinner, (String) obj);
            }
        };
        final Callback<String> onEditPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda16
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.lambda$loadFEXCorePresetSpinners$36(context, sFEXCorePreset, updateSpinner, (String) obj);
            }
        };
        final Callback<String> onDuplicatePreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda17
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                ContentDialog.confirm(r0, com.ludashi.benchmark.R.string.do_you_want_to_duplicate_this_preset, new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda46
                    @Override // java.lang.Runnable
                    public final void run() {
                        SettingsFragment.lambda$loadFEXCorePresetSpinners$37(r1, r2, r3, r4);
                    }
                });
            }
        };
        final Callback<String> onRemovePreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda18
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.lambda$loadFEXCorePresetSpinners$40(sFEXCorePreset, context, updateSpinner, (String) obj);
            }
        };
        final Callback<String> onExportPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda19
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$loadFEXCorePresetSpinners$42(sFEXCorePreset, context, (String) obj);
            }
        };
        final Callback<String> onImportPreset = new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda20
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$loadFEXCorePresetSpinners$43((String) obj);
            }
        };
        updateSpinner.call("fexcore");
        view.findViewById(com.ludashi.benchmark.R.id.BTAddFEXCorePreset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda21
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("fexcore");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTEditFEXCorePreset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda23
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("fexcore");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTDuplicateFEXCorePreset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda24
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("fexcore");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTRemoveFEXCorePreset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda12
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("fexcore");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTExportFEXCorePreset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda13
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("fexcore");
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTImportFEXCorePreset).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda14
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                Callback.this.call("fexcore");
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadFEXCorePresetSpinners$32(Spinner sFEXCorePreset, String prefix) {
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, this.preferences.getString(prefix + "_preset", "COMPATIBILITY"));
    }

    static /* synthetic */ void lambda$loadFEXCorePresetSpinners$34(Context context, final Callback updateSpinner, final String prefix) {
        FEXCoreEditPresetDialog dialog = new FEXCoreEditPresetDialog(context, null);
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                Callback.this.call(prefix);
            }
        });
        dialog.show();
    }

    static /* synthetic */ void lambda$loadFEXCorePresetSpinners$36(Context context, Spinner sFEXCorePreset, final Callback updateSpinner, final String prefix) {
        FEXCoreEditPresetDialog dialog = new FEXCoreEditPresetDialog(context, FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset));
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                Callback.this.call(prefix);
            }
        });
        dialog.show();
    }

    static /* synthetic */ void lambda$loadFEXCorePresetSpinners$37(Context context, Spinner sFEXCorePreset, Callback updateSpinner, String prefix) {
        FEXCorePresetManager.duplicatePreset(context, FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset));
        updateSpinner.call(prefix);
        sFEXCorePreset.setSelection(sFEXCorePreset.getCount() - 1);
    }

    static /* synthetic */ void lambda$loadFEXCorePresetSpinners$40(Spinner sFEXCorePreset, final Context context, final Callback updateSpinner, final String prefix) {
        final String presetId = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
        if (!presetId.startsWith("CUSTOM")) {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.you_cannot_remove_this_preset);
        } else {
            ContentDialog.confirm(context, com.ludashi.benchmark.R.string.do_you_want_to_remove_this_preset, new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda6
                @Override // java.lang.Runnable
                public final void run() {
                    SettingsFragment.lambda$loadFEXCorePresetSpinners$39(context, presetId, updateSpinner, prefix);
                }
            });
        }
    }

    static /* synthetic */ void lambda$loadFEXCorePresetSpinners$39(Context context, String presetId, Callback updateSpinner, String prefix) {
        FEXCorePresetManager.removePreset(context, presetId);
        updateSpinner.call(prefix);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadFEXCorePresetSpinners$42(Spinner sFEXCorePreset, final Context context, String prefix) {
        final String presetId = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
        if (!presetId.startsWith("CUSTOM")) {
            AppUtils.showToast(context, "Cannot export this preset");
        } else {
            getActivity().runOnUiThread(new Runnable() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda47
                @Override // java.lang.Runnable
                public final void run() {
                    FEXCorePresetManager.exportPreset(context, presetId);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadFEXCorePresetSpinners$43(String prefix) {
        openFile(1005);
    }

    private void openFile(int requestCode) {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        getActivity().startActivityFromFragment(this, intent, requestCode);
    }

    private void loadWineDebugChannels(final View view, final ArrayList<String> debugChannels) {
        final Context context = getContext();
        LinearLayout container = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLWineDebugChannels);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        int i = com.ludashi.benchmark.R.layout.wine_debug_channel_list_item;
        boolean z = false;
        View itemView = inflater.inflate(com.ludashi.benchmark.R.layout.wine_debug_channel_list_item, (ViewGroup) container, false);
        itemView.findViewById(com.ludashi.benchmark.R.id.TextView).setVisibility(8);
        itemView.findViewById(com.ludashi.benchmark.R.id.BTRemove).setVisibility(8);
        View addButton = itemView.findViewById(com.ludashi.benchmark.R.id.BTAdd);
        addButton.setVisibility(0);
        addButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda11
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$loadWineDebugChannels$51(context, debugChannels, view, view2);
            }
        });
        View resetButton = itemView.findViewById(com.ludashi.benchmark.R.id.BTReset);
        resetButton.setVisibility(0);
        resetButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda22
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SettingsFragment.this.lambda$loadWineDebugChannels$52(debugChannels, view, view2);
            }
        });
        container.addView(itemView);
        int i2 = 0;
        while (i2 < debugChannels.size()) {
            View itemView2 = inflater.inflate(i, container, z);
            TextView textView = (TextView) itemView2.findViewById(com.ludashi.benchmark.R.id.TextView);
            textView.setText(debugChannels.get(i2));
            final int index = i2;
            itemView2.findViewById(com.ludashi.benchmark.R.id.BTRemove).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda33
                @Override // android.view.View.OnClickListener
                public final void onClick(View view2) {
                    SettingsFragment.this.lambda$loadWineDebugChannels$53(debugChannels, index, view, view2);
                }
            });
            container.addView(itemView2);
            i2++;
            i = com.ludashi.benchmark.R.layout.wine_debug_channel_list_item;
            z = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadWineDebugChannels$51(Context context, final ArrayList debugChannels, final View view, View v) {
        JSONArray jsonArray = null;
        try {
            jsonArray = new JSONArray(FileUtils.readString(context, "wine_debug_channels.json"));
        } catch (JSONException e) {
        }
        final String[] items = ArrayUtils.toStringArray(jsonArray);
        ContentDialog.showMultipleChoiceList(context, com.ludashi.benchmark.R.string.wine_debug_channel, items, new Callback() { // from class: com.winlator.cmod.SettingsFragment$$ExternalSyntheticLambda48
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                SettingsFragment.this.lambda$loadWineDebugChannels$50(debugChannels, items, view, (ArrayList) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadWineDebugChannels$50(ArrayList debugChannels, String[] items, View view, ArrayList selectedPositions) {
        Iterator it = selectedPositions.iterator();
        while (it.hasNext()) {
            int selectedPosition = ((Integer) it.next()).intValue();
            if (!debugChannels.contains(items[selectedPosition])) {
                debugChannels.add(items[selectedPosition]);
            }
        }
        loadWineDebugChannels(view, debugChannels);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadWineDebugChannels$52(ArrayList debugChannels, View view, View v) {
        debugChannels.clear();
        debugChannels.addAll(Arrays.asList(DEFAULT_WINE_DEBUG_CHANNELS.split(",")));
        loadWineDebugChannels(view, debugChannels);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadWineDebugChannels$53(ArrayList debugChannels, int index, View view, View v) {
        debugChannels.remove(index);
        loadWineDebugChannels(view, debugChannels);
    }

    public static void resetEmulatorsVersion(AppCompatActivity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove("current_box64_version");
        editor.remove("current_wowbox64_version");
        editor.remove("current_fexcore_version");
        editor.apply();
    }

    @Override // androidx.fragment.app.Fragment
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Uri uri;
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == -1 && data != null && (uri = data.getData()) != null) {
            SharedPreferences.Editor editor = this.preferences.edit();
            switch (requestCode) {
                case 1001:
                    break;
                case 1002:
                    editor.putString("winlator_path_uri", uri.toString());
                    editor.apply();
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(uri, 3);
                    } catch (SecurityException e) {
                        AppUtils.showToast(getContext(), "Unable to take persistable permissions: " + e.getMessage());
                    }
                    String fullPath = FileUtils.getFilePathFromUri(getContext(), uri);
                    TextView tvWinlatorPath = (TextView) getView().findViewById(com.ludashi.benchmark.R.id.TVWinlatorPath);
                    tvWinlatorPath.setText(fullPath != null ? fullPath : uri.toString());
                    return;
                case 1003:
                    editor.putString("shortcuts_export_path_uri", uri.toString());
                    editor.apply();
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(uri, 3);
                    } catch (SecurityException e2) {
                        AppUtils.showToast(getContext(), "Unable to take persistable permissions: " + e2.getMessage());
                    }
                    String path = FileUtils.getFilePathFromUri(getContext(), uri);
                    TextView tvShortcutExportPath = (TextView) getView().findViewById(com.ludashi.benchmark.R.id.TVShortcutExportPath);
                    tvShortcutExportPath.setText(path != null ? path : uri.toString());
                    break;
                case 1004:
                    try {
                        Spinner sBox64Preset = (Spinner) getView().findViewById(com.ludashi.benchmark.R.id.SBox64Preset);
                        InputStream is = getActivity().getContentResolver().openInputStream(uri);
                        Box64PresetManager.importPreset("box64", getContext(), is);
                        Box64PresetManager.loadSpinner("box64", sBox64Preset, this.preferences.getString("box64_preset", "COMPATIBILITY"));
                        return;
                    } catch (FileNotFoundException e3) {
                        return;
                    }
                case 1005:
                    try {
                        Spinner sFEXCorePreset = (Spinner) getView().findViewById(com.ludashi.benchmark.R.id.SFEXCorePreset);
                        InputStream is2 = getActivity().getContentResolver().openInputStream(uri);
                        FEXCorePresetManager.importPreset(getContext(), is2);
                        FEXCorePresetManager.loadSpinner(sFEXCorePreset, this.preferences.getString("fexcore_preset", "INTERMEDIATE"));
                        return;
                    } catch (FileNotFoundException e4) {
                        return;
                    }
                default:
                    return;
            }
            if (this.installSoundFontCallback != null) {
                try {
                    try {
                        this.installSoundFontCallback.call(uri);
                    } catch (Exception e5) {
                        AppUtils.showToast(getContext(), com.ludashi.benchmark.R.string.unable_to_install_soundfont);
                    }
                } finally {
                    this.installSoundFontCallback = null;
                }
            }
        }
    }

    private void moveFiles(File sourceDir, File targetDir) throws IOException {
        File[] files = sourceDir.listFiles();
        if (files != null) {
            for (File file : files) {
                File targetFile = new File(targetDir, file.getName());
                if (file.isDirectory()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs();
                    }
                    moveFiles(file, targetFile);
                } else if (!file.renameTo(targetFile)) {
                    throw new IOException("Failed to move file: " + file.getAbsolutePath());
                }
            }
        }
        FileUtils.clear(sourceDir);
    }
}
