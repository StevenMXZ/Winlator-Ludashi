package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.HttpUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes8.dex */
public class InputControlsFragment extends Fragment {
    private static final String INPUT_CONTROLS_URL = "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/%s";
    private ControlsProfile currentProfile;
    private Callback<ControlsProfile> importProfileCallback;
    private boolean isDarkMode;
    private int[] keycodes;
    private InputControlsManager manager;
    private SharedPreferences preferences;
    private final int selectedProfileId;
    private Runnable updateLayout;

    public InputControlsFragment(int selectedProfileId) {
        this.selectedProfileId = selectedProfileId;
    }

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        this.manager = new InputControlsManager(getContext());
        this.preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        this.isDarkMode = this.preferences.getBoolean("dark_mode", false);
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(com.ludashi.benchmark.R.string.input_controls);
    }

    @Override // androidx.fragment.app.Fragment
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 2 && resultCode == -1) {
            try {
                ControlsProfile importedProfile = this.manager.importProfile(new JSONObject(FileUtils.readString(getContext(), data.getData())));
                if (this.importProfileCallback != null) {
                    this.importProfileCallback.call(importedProfile);
                }
            } catch (Exception e) {
                AppUtils.showToast(getContext(), com.ludashi.benchmark.R.string.unable_to_import_profile);
            }
            this.importProfileCallback = null;
        }
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(com.ludashi.benchmark.R.layout.input_controls_fragment, container, false);
        final Context context = getContext();
        this.currentProfile = this.selectedProfileId > 0 ? this.manager.getProfile(this.selectedProfileId) : null;
        final Spinner sProfile = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SProfile);
        sProfile.setPopupBackgroundResource(this.isDarkMode ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        loadProfileSpinner(sProfile);
        this.updateLayout = new Runnable() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda22
            @Override // java.lang.Runnable
            public final void run() {
                InputControlsFragment.this.lambda$onCreateView$0(view);
            }
        };
        this.updateLayout.run();
        final TextView tvUiOpacity = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVUiOpacity);
        SeekBar sbUiOpacity = (SeekBar) view.findViewById(com.ludashi.benchmark.R.id.SBOverlayOpacity);
        sbUiOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: com.winlator.cmod.InputControlsFragment.1
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvUiOpacity.setText(progress + "%");
                if (fromUser) {
                    int progress2 = (int) Mathf.roundTo(progress, 5.0f);
                    seekBar.setProgress(progress2);
                    InputControlsFragment.this.preferences.edit().putFloat("overlay_opacity", progress2 / 100.0f).apply();
                }
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sbUiOpacity.setProgress((int) (this.preferences.getFloat("overlay_opacity", 0.4f) * 100.0f));
        view.findViewById(com.ludashi.benchmark.R.id.BTAddProfile).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                InputControlsFragment.this.lambda$onCreateView$2(context, sProfile, view2);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTEditProfile).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                InputControlsFragment.this.lambda$onCreateView$4(context, sProfile, view2);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTDuplicateProfile).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                InputControlsFragment.this.lambda$onCreateView$6(context, sProfile, view2);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTRemoveProfile).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                InputControlsFragment.this.lambda$onCreateView$8(context, sProfile, view2);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTImportProfile).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                InputControlsFragment.this.lambda$onCreateView$10(context, sProfile, view2);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTExportProfile).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda6
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                InputControlsFragment.this.lambda$onCreateView$11(context, view2);
            }
        });
        view.findViewById(com.ludashi.benchmark.R.id.BTControlsEditor).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda7
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                InputControlsFragment.this.lambda$onCreateView$12(context, view2);
            }
        });
        return view;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$2(Context context, final Spinner sProfile, View v) {
        ContentDialog.prompt(context, com.ludashi.benchmark.R.string.profile_name, null, new Callback() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda10
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                InputControlsFragment.this.lambda$onCreateView$1(sProfile, (String) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$1(Spinner sProfile, String name) {
        this.currentProfile = this.manager.createProfile(name);
        loadProfileSpinner(sProfile);
        this.updateLayout.run();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$4(Context context, final Spinner sProfile, View v) {
        if (this.currentProfile != null) {
            ContentDialog.prompt(context, com.ludashi.benchmark.R.string.profile_name, this.currentProfile.getName(), new Callback() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda11
                @Override // com.winlator.cmod.core.Callback
                public final void call(Object obj) {
                    InputControlsFragment.this.lambda$onCreateView$3(sProfile, (String) obj);
                }
            });
        } else {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.no_profile_selected);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$3(Spinner sProfile, String name) {
        this.currentProfile.setName(name);
        this.currentProfile.save();
        loadProfileSpinner(sProfile);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$6(Context context, final Spinner sProfile, View v) {
        if (this.currentProfile != null) {
            ContentDialog.confirm(context, com.ludashi.benchmark.R.string.do_you_want_to_duplicate_this_profile, new Runnable() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda17
                @Override // java.lang.Runnable
                public final void run() {
                    InputControlsFragment.this.lambda$onCreateView$5(sProfile);
                }
            });
        } else {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.no_profile_selected);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$5(Spinner sProfile) {
        this.currentProfile = this.manager.duplicateProfile(this.currentProfile);
        loadProfileSpinner(sProfile);
        this.updateLayout.run();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$8(Context context, final Spinner sProfile, View v) {
        if (this.currentProfile != null) {
            ContentDialog.confirm(context, com.ludashi.benchmark.R.string.do_you_want_to_remove_this_profile, new Runnable() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda15
                @Override // java.lang.Runnable
                public final void run() {
                    InputControlsFragment.this.lambda$onCreateView$7(sProfile);
                }
            });
        } else {
            AppUtils.showToast(context, com.ludashi.benchmark.R.string.no_profile_selected);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$7(Spinner sProfile) {
        this.manager.removeProfile(this.currentProfile);
        this.currentProfile = null;
        loadProfileSpinner(sProfile);
        this.updateLayout.run();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$10(Context context, final Spinner sProfile, View v) {
        PopupMenu popupMenu = new PopupMenu(context, v);
        if (Build.VERSION.SDK_INT >= 29) {
            popupMenu.setForceShowIcon(true);
        }
        popupMenu.inflate(com.ludashi.benchmark.R.menu.open_file_popup_menu);
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda19
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                boolean lambda$onCreateView$9;
                lambda$onCreateView$9 = InputControlsFragment.this.lambda$onCreateView$9(sProfile, menuItem);
                return lambda$onCreateView$9;
            }
        });
        popupMenu.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$onCreateView$9(Spinner sProfile, MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == com.ludashi.benchmark.R.id.open_file) {
            openProfileFile(sProfile);
            return true;
        }
        if (itemId == com.ludashi.benchmark.R.id.download_file) {
            downloadProfileList(sProfile);
            return true;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$11(Context context, View v) {
        if (this.currentProfile != null) {
            File exportedFile = this.manager.exportProfile(this.currentProfile);
            if (exportedFile != null) {
                String path = exportedFile.getPath();
                AppUtils.showToast(context, context.getString(com.ludashi.benchmark.R.string.profile_exported_to) + " " + path);
                return;
            }
            return;
        }
        AppUtils.showToast(context, com.ludashi.benchmark.R.string.no_profile_selected);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$12(Context context, View v) {
        if (this.currentProfile != null) {
            Intent intent = new Intent(context, (Class<?>) ControlsEditorActivity.class);
            intent.putExtra("profile_id", this.currentProfile.id);
            startActivity(intent);
            getActivity().overridePendingTransition(com.ludashi.benchmark.R.anim.slide_in_up, com.ludashi.benchmark.R.anim.slide_out_down);
            return;
        }
        AppUtils.showToast(context, com.ludashi.benchmark.R.string.no_profile_selected);
    }

    private void openProfileFile(final Spinner sProfile) {
        this.importProfileCallback = new Callback() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda16
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                InputControlsFragment.this.lambda$openProfileFile$13(sProfile, (ControlsProfile) obj);
            }
        };
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        getActivity().startActivityFromFragment(this, intent, 2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$openProfileFile$13(Spinner sProfile, ControlsProfile importedProfile) {
        this.currentProfile = importedProfile;
        loadProfileSpinner(sProfile);
        this.updateLayout.run();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void downloadSelectedProfiles(final Spinner sProfile, String[] items, final ArrayList<Integer> positions) {
        final MainActivity activity = (MainActivity) getActivity();
        activity.preloaderDialog.lambda$showOnUiThread$0(com.ludashi.benchmark.R.string.downloading_file);
        this.currentProfile = null;
        final AtomicInteger processedItemCount = new AtomicInteger();
        Iterator<Integer> it = positions.iterator();
        while (it.hasNext()) {
            int position = it.next().intValue();
            HttpUtils.download(String.format(INPUT_CONTROLS_URL, items[position]), new Callback() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda14
                @Override // com.winlator.cmod.core.Callback
                public final void call(Object obj) {
                    InputControlsFragment.this.lambda$downloadSelectedProfiles$15(processedItemCount, positions, activity, sProfile, (String) obj);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$downloadSelectedProfiles$15(AtomicInteger processedItemCount, ArrayList positions, final MainActivity activity, final Spinner sProfile, String content) {
        if (content != null) {
            try {
                this.manager.importProfile(new JSONObject(content));
            } catch (JSONException e) {
            }
        }
        if (processedItemCount.incrementAndGet() == positions.size()) {
            activity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda13
                @Override // java.lang.Runnable
                public final void run() {
                    InputControlsFragment.this.lambda$downloadSelectedProfiles$14(activity, sProfile);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$downloadSelectedProfiles$14(MainActivity activity, Spinner sProfile) {
        activity.preloaderDialog.close();
        loadProfileSpinner(sProfile);
        this.updateLayout.run();
    }

    private void downloadProfileList(final Spinner sProfile) {
        final MainActivity activity = (MainActivity) getActivity();
        activity.preloaderDialog.lambda$showOnUiThread$0(com.ludashi.benchmark.R.string.loading);
        HttpUtils.download(String.format(INPUT_CONTROLS_URL, "index.txt"), new Callback() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda9
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                InputControlsFragment.this.lambda$downloadProfileList$19(activity, sProfile, (String) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$downloadProfileList$19(final MainActivity activity, final Spinner sProfile, final String content) {
        activity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda12
            @Override // java.lang.Runnable
            public final void run() {
                InputControlsFragment.this.lambda$downloadProfileList$18(activity, content, sProfile);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$downloadProfileList$18(final MainActivity activity, String content, final Spinner sProfile) {
        activity.preloaderDialog.close();
        if (content != null) {
            final String[] items = content.split("\n");
            ContentDialog.showMultipleChoiceList(activity, com.ludashi.benchmark.R.string.import_profile, items, new Callback() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda8
                @Override // com.winlator.cmod.core.Callback
                public final void call(Object obj) {
                    InputControlsFragment.this.lambda$downloadProfileList$17(activity, sProfile, items, (ArrayList) obj);
                }
            });
        } else {
            AppUtils.showToast(activity, com.ludashi.benchmark.R.string.unable_to_load_profile_list);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$downloadProfileList$17(MainActivity activity, final Spinner sProfile, final String[] items, final ArrayList positions) {
        if (!positions.isEmpty()) {
            ContentDialog.confirm(activity, com.ludashi.benchmark.R.string.do_you_want_to_download_the_selected_profiles, new Runnable() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    InputControlsFragment.this.downloadSelectedProfiles(sProfile, items, positions);
                }
            });
        }
    }

    @Override // androidx.fragment.app.Fragment
    public void onStart() {
        super.onStart();
        if (this.updateLayout != null) {
            this.updateLayout.run();
        }
    }

    private void loadProfileSpinner(Spinner spinner) {
        final ArrayList<ControlsProfile> profiles = this.manager.getProfiles();
        ArrayList<String> values = new ArrayList<>();
        values.add("-- " + getString(com.ludashi.benchmark.R.string.select_profile) + " --");
        int selectedPosition = 0;
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile == this.currentProfile) {
                selectedPosition = i + 1;
            }
            values.add(profile.getName());
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(getContext(), android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.InputControlsFragment.2
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                InputControlsFragment.this.currentProfile = position > 0 ? (ControlsProfile) profiles.get(position - 1) : null;
                InputControlsFragment.this.updateLayout.run();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadExternalControllers(final View view) {
        LinearLayout container = (LinearLayout) view.findViewById(com.ludashi.benchmark.R.id.LLExternalControllers);
        container.removeAllViews();
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ArrayList<ExternalController> connectedControllers = ExternalController.getControllers();
        ArrayList<ExternalController> controllers = this.currentProfile != null ? this.currentProfile.loadControllers() : new ArrayList<>();
        Iterator<ExternalController> it = connectedControllers.iterator();
        while (it.hasNext()) {
            ExternalController controller = it.next();
            if (!controllers.contains(controller)) {
                controllers.add(controller);
            }
        }
        
        boolean isFirstItem = true;
        if (!controllers.isEmpty()) {
            view.findViewById(com.ludashi.benchmark.R.id.TVEmptyText).setVisibility(8);
            String bindingsText = context.getString(com.ludashi.benchmark.R.string.bindings);
            Iterator<ExternalController> it2 = controllers.iterator();
            while (it2.hasNext()) {
                final ExternalController controller2 = it2.next();
                View itemView = inflater.inflate(com.ludashi.benchmark.R.layout.external_controller_list_item, container, false);
                ((TextView) itemView.findViewById(com.ludashi.benchmark.R.id.TVTitle)).setText(controller2.getName());
                int controllerBindingCount = controller2.getControllerBindingCount();
                ((TextView) itemView.findViewById(com.ludashi.benchmark.R.id.TVSubtitle)).setText(controllerBindingCount + " " + bindingsText);
                ImageView imageView = (ImageView) itemView.findViewById(com.ludashi.benchmark.R.id.ImageView);
                int tintColor = controller2.isConnected() ? ContextCompat.getColor(context, com.ludashi.benchmark.R.color.colorAccent) : -1739917;
                ImageViewCompat.setImageTintList(imageView, ColorStateList.valueOf(tintColor));
                if (controllerBindingCount > 0) {
                    ImageButton imageButton = (ImageButton) itemView.findViewById(com.ludashi.benchmark.R.id.BTRemove);
                    imageButton.setVisibility(View.VISIBLE);
                    imageButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda20
                        @Override // android.view.View.OnClickListener
                        public final void onClick(View view2) {
                            InputControlsFragment.this.lambda$loadExternalControllers$21(controller2, view, view2);
                        }
                    });
                }
                itemView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda21
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view2) {
                        InputControlsFragment.this.lambda$loadExternalControllers$22(controller2, view2);
                    }
                });
                container.addView(itemView);
                isFirstItem = false;
            }
        } else {
            view.findViewById(com.ludashi.benchmark.R.id.TVEmptyText).setVisibility(0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadExternalControllers$21(final ExternalController controller, final View view, View v) {
        ContentDialog.confirm(getContext(), com.ludashi.benchmark.R.string.do_you_want_to_remove_this_controller, new Runnable() { // from class: com.winlator.cmod.InputControlsFragment$$ExternalSyntheticLambda18
            @Override // java.lang.Runnable
            public final void run() {
                InputControlsFragment.this.lambda$loadExternalControllers$20(controller, view);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadExternalControllers$20(ExternalController controller, View view) {
        this.currentProfile.removeController(controller);
        this.currentProfile.save();
        loadExternalControllers(view);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadExternalControllers$22(ExternalController controller, View v) {
        if (this.currentProfile != null) {
            Intent intent = new Intent(getContext(), (Class<?>) ExternalControllerBindingsActivity.class);
            intent.putExtra("profile_id", this.currentProfile.id);
            intent.putExtra("controller_id", controller.getId());
            startActivity(intent);
            getActivity().overridePendingTransition(com.ludashi.benchmark.R.anim.slide_in_up, com.ludashi.benchmark.R.anim.slide_out_down);
            return;
        }
        AppUtils.showToast(getContext(), com.ludashi.benchmark.R.string.no_profile_selected);
    }
}
