package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ContentInfoDialog;
import com.winlator.cmod.contentdialog.ContentUntrustedDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

/* loaded from: classes8.dex */
public class ContentsFragment extends Fragment {
    private ContentProfile.ContentType currentContentType = ContentProfile.ContentType.CONTENT_TYPE_WINE;
    private View emptyText;
    private boolean isDarkMode;
    private ContentsManager manager;
    private RecyclerView recyclerView;
    private Spinner sContentType;
    SharedPreferences sp;

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        this.manager = new ContentsManager(getContext());
        this.manager.syncContents();
        this.sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
        this.isDarkMode = PreferenceManager.getDefaultSharedPreferences(getContext()).getBoolean("dark_mode", false);
    }

    @Override // androidx.fragment.app.Fragment
    public void onDestroy() {
        FileUtils.clear(getContext().getCacheDir());
        super.onDestroy();
    }

    @Override // androidx.fragment.app.Fragment
    public void onResume() {
        super.onResume();
        new Thread(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                ContentsFragment.this.lambda$onResume$1();
            }
        }).start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onResume$1() {
        String contentsURL = this.sp.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
        final String json = Downloader.downloadString(contentsURL);
        if (json == null) {
            return;
        }
        getActivity().runOnUiThread(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                ContentsFragment.this.lambda$onResume$0(json);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onResume$0(String json) {
        this.manager.setRemoteProfiles(json);
        loadContentList();
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(com.ludashi.benchmark.R.string.contents);
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(com.ludashi.benchmark.R.layout.contents_fragment, container, false);
        this.sContentType = (Spinner) layout.findViewById(com.ludashi.benchmark.R.id.SContentType);
        updateContentTypeSpinner(this.sContentType);
        this.sContentType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ContentsFragment.1
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ContentsFragment.this.currentContentType = ContentProfile.ContentType.values()[position];
                ContentsFragment.this.loadContentList();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        this.emptyText = layout.findViewById(com.ludashi.benchmark.R.id.TVEmptyText);
        View btInstallContent = layout.findViewById(com.ludashi.benchmark.R.id.BTInstallContent);
        btInstallContent.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContentsFragment$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ContentsFragment.this.lambda$onCreateView$3(view);
            }
        });
        this.recyclerView = (RecyclerView) layout.findViewById(com.ludashi.benchmark.R.id.RecyclerView);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this.recyclerView.getContext()));
        this.recyclerView.addItemDecoration(new DividerItemDecoration(this.recyclerView.getContext(), 1));
        loadContentList();
        return layout;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$3(View v) {
        ContentDialog.confirm(getContext(), getString(com.ludashi.benchmark.R.string.do_you_want_to_install_content) + " " + getString(com.ludashi.benchmark.R.string.pls_make_sure_content_trustworthy) + " " + getString(com.ludashi.benchmark.R.string.content_suffix_is_wcp_packed_xz_zst) + '\n' + getString(com.ludashi.benchmark.R.string.get_more_contents_form_github), new Runnable() { // from class: com.winlator.cmod.ContentsFragment$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ContentsFragment.this.lambda$onCreateView$2();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$2() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        getActivity().startActivityFromFragment(this, intent, 2);
    }

    private void updateContentTypeSpinner(Spinner spinner) {
        List<String> typeList = new ArrayList<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            typeList.add(type.toString());
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(getContext(), android.R.layout.simple_spinner_dropdown_item, typeList));
        spinner.setPopupBackgroundResource(this.isDarkMode ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ContentsFragment.2
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ContentsFragment.this.currentContentType = ContentProfile.ContentType.values()[position];
                ContentsFragment.this.updateContentsListView();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateContentsListView() {
        List<ContentProfile> profiles = this.manager.getProfiles(this.currentContentType);
        if (profiles.isEmpty()) {
            this.recyclerView.setVisibility(8);
            this.emptyText.setVisibility(0);
        }
    }

    @Override // androidx.fragment.app.Fragment
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode == 2 && resultCode == -1) {
            PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
            preloaderDialog.showOnUiThread(com.ludashi.benchmark.R.string.installing_content);
            try {
                final ContentsManager.OnInstallFinishedCallback callback = new AnonymousClass3(preloaderDialog);
                Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$$ExternalSyntheticLambda3
                    @Override // java.lang.Runnable
                    public final void run() {
                        ContentsFragment.this.lambda$onActivityResult$4(data, callback);
                    }
                });
            } catch (Exception e) {
                preloaderDialog.closeOnUiThread();
                AppUtils.showToast(getContext(), com.ludashi.benchmark.R.string.unable_to_import_profile);
            }
        }
    }

    /* renamed from: com.winlator.cmod.ContentsFragment$3, reason: invalid class name */
    class AnonymousClass3 implements ContentsManager.OnInstallFinishedCallback {
        private boolean isExtracting = true;
        final /* synthetic */ PreloaderDialog val$preloaderDialog;

        AnonymousClass3(PreloaderDialog preloaderDialog) {
            this.val$preloaderDialog = preloaderDialog;
        }

        @Override // com.winlator.cmod.contents.ContentsManager.OnInstallFinishedCallback
        public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
            final int msgId;
            switch (AnonymousClass4.$SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason[reason.ordinal()]) {
                case 1:
                    msgId = com.ludashi.benchmark.R.string.file_cannot_be_recognied;
                    break;
                case 2:
                    msgId = com.ludashi.benchmark.R.string.profile_not_found_in_content;
                    break;
                case 3:
                    msgId = com.ludashi.benchmark.R.string.profile_cannot_be_recognized;
                    break;
                case 4:
                    msgId = com.ludashi.benchmark.R.string.content_already_exist;
                    break;
                case 5:
                    msgId = com.ludashi.benchmark.R.string.content_is_incomplete;
                    break;
                case 6:
                    msgId = com.ludashi.benchmark.R.string.content_cannot_be_trusted;
                    break;
                default:
                    msgId = com.ludashi.benchmark.R.string.unable_to_install_content;
                    break;
            }
            FragmentActivity requireActivity = ContentsFragment.this.requireActivity();
            final PreloaderDialog preloaderDialog = this.val$preloaderDialog;
            requireActivity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$3$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    ContentsFragment.AnonymousClass3.this.lambda$onFailed$0(msgId, preloaderDialog);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onFailed$0(int msgId, PreloaderDialog preloaderDialog) {
            Context context = ContentsFragment.this.getContext();
            String str = ContentsFragment.this.getString(com.ludashi.benchmark.R.string.install_failed) + ": " + ContentsFragment.this.getString(msgId);
            Objects.requireNonNull(preloaderDialog);
            ContentDialog.alert(context, str, new ClosePreloaderRunnable(preloaderDialog));
        }

        @Override // com.winlator.cmod.contents.ContentsManager.OnInstallFinishedCallback
        public void onSucceed(final ContentProfile profile) {
            if (this.isExtracting) {
                FragmentActivity requireActivity = ContentsFragment.this.requireActivity();
                final PreloaderDialog preloaderDialog = this.val$preloaderDialog;
                requireActivity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$3$$ExternalSyntheticLambda4
                    @Override // java.lang.Runnable
                    public final void run() {
                        ContentsFragment.AnonymousClass3.this.lambda$onSucceed$3(profile, preloaderDialog, this);
                    }
                });
            } else {
                this.val$preloaderDialog.closeOnUiThread();
                ContentsFragment.this.requireActivity().runOnUiThread(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$3$$ExternalSyntheticLambda5
                    @Override // java.lang.Runnable
                    public final void run() {
                        ContentsFragment.AnonymousClass3.this.lambda$onSucceed$4(profile);
                    }
                });
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onSucceed$3(final ContentProfile profile, final PreloaderDialog preloaderDialog, final ContentsManager.OnInstallFinishedCallback callback1) {
            ContentInfoDialog dialog = new ContentInfoDialog(ContentsFragment.this.getContext(), profile);
            ((TextView) dialog.findViewById(com.ludashi.benchmark.R.id.BTConfirm)).setText(com.ludashi.benchmark.R.string._continue);
            dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$3$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    ContentsFragment.AnonymousClass3.this.lambda$onSucceed$2(profile, preloaderDialog, callback1);
                }
            });
            Objects.requireNonNull(preloaderDialog);
            dialog.setOnCancelCallback(new ClosePreloaderRunnable(preloaderDialog));
            dialog.show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onSucceed$2(final ContentProfile profile, PreloaderDialog preloaderDialog, final ContentsManager.OnInstallFinishedCallback callback1) {
            this.isExtracting = false;
            List<ContentProfile.ContentFile> untrustedFiles = ContentsFragment.this.manager.getUnTrustedContentFiles(profile);
            if (!untrustedFiles.isEmpty()) {
                ContentUntrustedDialog untrustedDialog = new ContentUntrustedDialog(ContentsFragment.this.getContext(), untrustedFiles);
                Objects.requireNonNull(preloaderDialog);
                untrustedDialog.setOnCancelCallback(new ClosePreloaderRunnable(preloaderDialog));
                untrustedDialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$3$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        ContentsFragment.AnonymousClass3.this.lambda$onSucceed$1(profile, callback1);
                    }
                });
                untrustedDialog.show();
                return;
            }
            ContentsFragment.this.manager.finishInstallContent(profile, callback1);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onSucceed$1(ContentProfile profile, ContentsManager.OnInstallFinishedCallback callback1) {
            ContentsFragment.this.manager.finishInstallContent(profile, callback1);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onSucceed$4(ContentProfile profile) {
            ContentDialog.alert(ContentsFragment.this.getContext(), com.ludashi.benchmark.R.string.content_installed_success, (Runnable) null);
            ContentsFragment.this.manager.syncContents();
            boolean flashAfter = ContentsFragment.this.currentContentType == profile.type;
            ContentsFragment.this.currentContentType = profile.type;
            AppUtils.setSpinnerSelectionFromValue(ContentsFragment.this.sContentType, ContentsFragment.this.currentContentType.toString());
            if (flashAfter) {
                ContentsFragment.this.loadContentList();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onActivityResult$4(Intent data, ContentsManager.OnInstallFinishedCallback callback) {
        this.manager.extraContentFile(data.getData(), callback);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadContentList() {
        List<ContentProfile> profiles = this.manager.getProfiles(this.currentContentType);
        if (profiles.isEmpty()) {
            this.emptyText.setVisibility(0);
            this.recyclerView.setVisibility(8);
        } else {
            this.emptyText.setVisibility(8);
            this.recyclerView.setVisibility(0);
            this.recyclerView.setAdapter(new ContentItemAdapter(profiles));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    class ContentItemAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<ContentProfile> data;

        /* JADX INFO: Access modifiers changed from: private */
        static class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton ibDownload;
            private final ImageButton ibMenu;
            private final ImageView ivIcon;
            private final ProgressBar progressBar;
            private final TextView tvVersionCode;
            private final TextView tvVersionName;

            public ViewHolder(View view) {
                super(view);
                this.ivIcon = (ImageView) view.findViewById(com.ludashi.benchmark.R.id.IVIcon);
                this.tvVersionName = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVVersionName);
                this.tvVersionCode = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVVersionCode);
                this.ibMenu = (ImageButton) view.findViewById(com.ludashi.benchmark.R.id.BTMenu);
                this.ibDownload = (ImageButton) view.findViewById(com.ludashi.benchmark.R.id.BTDownload);
                this.progressBar = (ProgressBar) view.findViewById(com.ludashi.benchmark.R.id.Progress);
            }
        }

        public ContentItemAdapter(List<ContentProfile> data) {
            this.data = data;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(com.ludashi.benchmark.R.layout.content_list_item, parent, false));
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onViewRecycled(ViewHolder holder) {
            holder.ibMenu.setOnClickListener(null);
            super.onViewRecycled((ContentItemAdapter) holder);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final ContentProfile profile = this.data.get(position);
            int i = AnonymousClass4.$SwitchMap$com$winlator$cmod$contents$ContentProfile$ContentType[profile.type.ordinal()];
            int i2 = com.ludashi.benchmark.R.drawable.icon_wine;
            switch (i) {
                case 1:
                case 2:
                    break;
                default:
                    i2 = com.ludashi.benchmark.R.drawable.icon_settings;
                    break;
            }
            int iconId = i2;
            holder.ivIcon.setBackground(ContentsFragment.this.getContext().getDrawable(iconId));
            holder.tvVersionName.setText(ContentsFragment.this.getContext().getString(com.ludashi.benchmark.R.string.version) + ": " + profile.verName);
            holder.tvVersionCode.setText(ContentsFragment.this.getContext().getString(com.ludashi.benchmark.R.string.version_code) + ": " + profile.verCode);
            holder.ibMenu.setVisibility(profile.remoteUrl == null ? 0 : 8);
            holder.ibMenu.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContentsFragment$ContentItemAdapter$$ExternalSyntheticLambda1
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ContentsFragment.ContentItemAdapter.this.lambda$onBindViewHolder$2(holder, profile, view);
                }
            });
            holder.ibDownload.setVisibility((profile.remoteUrl == null || holder.progressBar.getVisibility() != 8) ? 8 : 0);
            holder.ibDownload.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContentsFragment$ContentItemAdapter$$ExternalSyntheticLambda2
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ContentsFragment.ContentItemAdapter.this.lambda$onBindViewHolder$5(holder, profile, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$2(ViewHolder holder, final ContentProfile profile, View v) {
            PopupMenu selectionMenu = new PopupMenu(ContentsFragment.this.getContext(), holder.ibMenu);
            if (Build.VERSION.SDK_INT >= 29) {
                selectionMenu.setForceShowIcon(true);
            }
            selectionMenu.inflate(com.ludashi.benchmark.R.menu.content_popup_menu);
            selectionMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.ContentsFragment$ContentItemAdapter$$ExternalSyntheticLambda0
                @Override // android.widget.PopupMenu.OnMenuItemClickListener
                public final boolean onMenuItemClick(MenuItem menuItem) {
                    boolean lambda$onBindViewHolder$1;
                    lambda$onBindViewHolder$1 = ContentsFragment.ContentItemAdapter.this.lambda$onBindViewHolder$1(profile, menuItem);
                    return lambda$onBindViewHolder$1;
                }
            });
            selectionMenu.show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ boolean lambda$onBindViewHolder$1(final ContentProfile profile, MenuItem item) {
            int itemId = item.getItemId();
            if (itemId == com.ludashi.benchmark.R.id.content_info) {
                new ContentInfoDialog(ContentsFragment.this.getContext(), profile).show();
                return true;
            }
            if (itemId == com.ludashi.benchmark.R.id.remove_content) {
                ContentDialog.confirm(ContentsFragment.this.getContext(), com.ludashi.benchmark.R.string.do_you_want_to_remove_this_content, new Runnable() { // from class: com.winlator.cmod.ContentsFragment$ContentItemAdapter$$ExternalSyntheticLambda4
                    @Override // java.lang.Runnable
                    public final void run() {
                        ContentsFragment.ContentItemAdapter.this.lambda$onBindViewHolder$0(profile);
                    }
                });
                return true;
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(ContentProfile profile) {
            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                ContainerManager containerManager = new ContainerManager(ContentsFragment.this.getContext());
                Iterator<Container> it = containerManager.getContainers().iterator();
                while (it.hasNext()) {
                    Container container = it.next();
                    if (container.getWineVersion().equals(ContentsManager.getEntryName(profile))) {
                        ContentDialog.alert(ContentsFragment.this.getContext(), String.format(ContentsFragment.this.getString(com.ludashi.benchmark.R.string.unable_to_remove_content_since_container_using), container.getName()), (Runnable) null);
                        return;
                    }
                }
            }
            ContentsFragment.this.manager.removeContent(profile);
            ContentsFragment.this.loadContentList();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$5(final ViewHolder holder, final ContentProfile profile, View v) {
            holder.ibDownload.setVisibility(8);
            holder.progressBar.setVisibility(0);
            final Intent intent = new Intent();
            intent.setData(Uri.parse(profile.remoteUrl));
            new Thread(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$ContentItemAdapter$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    ContentsFragment.ContentItemAdapter.this.lambda$onBindViewHolder$4(profile, intent, holder);
                }
            }).start();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$4(ContentProfile profile, final Intent intent, final ViewHolder holder) {
            long timestamp = System.currentTimeMillis();
            File output = new File(ContentsFragment.this.getContext().getCacheDir(), "temp_" + timestamp);
            if (Downloader.downloadFile(profile.remoteUrl, output)) {
                intent.setData(Uri.parse(output.getAbsolutePath()));
            }
            ContentsFragment.this.getActivity().runOnUiThread(new Runnable() { // from class: com.winlator.cmod.ContentsFragment$ContentItemAdapter$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    ContentsFragment.ContentItemAdapter.this.lambda$onBindViewHolder$3(holder, intent);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$3(ViewHolder holder, Intent intent) {
            holder.progressBar.setVisibility(8);
            holder.ibDownload.setVisibility(0);
            ContentsFragment.this.onActivityResult(2, -1, intent);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return this.data.size();
        }
    }

    /* renamed from: com.winlator.cmod.ContentsFragment$4, reason: invalid class name */
    static /* synthetic */ class AnonymousClass4 {
        static final /* synthetic */ int[] $SwitchMap$com$winlator$cmod$contents$ContentProfile$ContentType = new int[ContentProfile.ContentType.values().length];
        static final /* synthetic */ int[] $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason;

        static {
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentProfile$ContentType[ContentProfile.ContentType.CONTENT_TYPE_WINE.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentProfile$ContentType[ContentProfile.ContentType.CONTENT_TYPE_PROTON.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
            $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason = new int[ContentsManager.InstallFailedReason.values().length];
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason[ContentsManager.InstallFailedReason.ERROR_BADTAR.ordinal()] = 1;
            } catch (NoSuchFieldError e3) {
            }
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason[ContentsManager.InstallFailedReason.ERROR_NOPROFILE.ordinal()] = 2;
            } catch (NoSuchFieldError e4) {
            }
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason[ContentsManager.InstallFailedReason.ERROR_BADPROFILE.ordinal()] = 3;
            } catch (NoSuchFieldError e5) {
            }
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason[ContentsManager.InstallFailedReason.ERROR_EXIST.ordinal()] = 4;
            } catch (NoSuchFieldError e6) {
            }
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason[ContentsManager.InstallFailedReason.ERROR_MISSINGFILES.ordinal()] = 5;
            } catch (NoSuchFieldError e7) {
            }
            try {
                $SwitchMap$com$winlator$cmod$contents$ContentsManager$InstallFailedReason[ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE.ordinal()] = 6;
            } catch (NoSuchFieldError e8) {
            }
        }
    }

    // ========== الكلاس الداخلي المُدمج من ExternalSyntheticLambda0 ==========
    private static final class ClosePreloaderRunnable implements Runnable {
        private final PreloaderDialog preloaderDialog;

        public ClosePreloaderRunnable(PreloaderDialog preloaderDialog) {
            this.preloaderDialog = preloaderDialog;
        }

        @Override
        public void run() {
            this.preloaderDialog.closeOnUiThread();
        }
    }
}