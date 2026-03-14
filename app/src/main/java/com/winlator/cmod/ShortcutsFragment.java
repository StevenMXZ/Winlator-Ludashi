package com.winlator.cmod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GameImageFetcher;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/* loaded from: classes8.dex */
public class ShortcutsFragment extends Fragment {
    public static final int IMPORT_SHORTCUT = 1005;
    private DividerItemDecoration dividerItemDecoration;
    private TextView emptyTextView;
    private ActivityResultLauncher<String> iconPickerLauncher;
    private boolean isGridView = false;
    private ContainerManager manager;
    private SharedPreferences preferences;
    private RecyclerView recyclerView;
    private Shortcut shortcutForIconUpdate;

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        this.iconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), new ActivityResultCallback() { // from class: com.winlator.cmod.ShortcutsFragment$$ExternalSyntheticLambda0
            @Override // androidx.activity.result.ActivityResultCallback
            public final void onActivityResult(Object obj) {
                ShortcutsFragment.this.lambda$onCreate$0((Uri) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$0(Uri uri) {
        if (uri != null && this.shortcutForIconUpdate != null) {
            updateShortcutIcon(uri, this.shortcutForIconUpdate);
        }
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        this.manager = new ContainerManager(getContext());
        loadShortcutsList();
        if (getActivity() != null && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(com.ludashi.benchmark.R.string.shortcuts);
        }
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(com.ludashi.benchmark.R.layout.shortcuts_fragment, container, false);
        this.recyclerView = (RecyclerView) frameLayout.findViewById(com.ludashi.benchmark.R.id.RecyclerView);
        this.emptyTextView = (TextView) frameLayout.findViewById(com.ludashi.benchmark.R.id.TVEmptyText);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        this.isGridView = this.preferences.getBoolean("shortcuts_grid_view", true);
        updateLayoutManager();
        return frameLayout;
    }

    @Override // androidx.fragment.app.Fragment
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        MenuItem item = menu.add(0, 1, 0, this.isGridView ? "List View" : "Grid View");
        item.setIcon(this.isGridView ? android.R.drawable.ic_menu_agenda : android.R.drawable.ic_menu_gallery);
        item.setShowAsAction(2);
    }

    @Override // androidx.fragment.app.Fragment
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            this.isGridView = !this.isGridView;
            this.preferences.edit().putBoolean("shortcuts_grid_view", this.isGridView).apply();
            updateLayoutManager();
            getActivity().invalidateOptionsMenu();
            return true;
        }
        if (item.getItemId() == 2) {
            syncAllImages();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public File getImagesDir(boolean isCover) {
        File targetDir = new File(Environment.getExternalStorageDirectory(), isCover ? "Winlator/covers" : "Winlator/icons");
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File nomedia = new File(targetDir, ".nomedia");
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile();
            } catch (IOException e) {
            }
        }
        return targetDir;
    }

    private void syncAllImages() {
        Toast.makeText(getContext(), "Syncing images... Please wait.", 0).show();
        File targetDir = getImagesDir(this.isGridView);
        if (targetDir.exists()) {
            for (File file : targetDir.listFiles()) {
                if (file.getName().endsWith(".png")) {
                    file.delete();
                }
            }
        }
        if (this.recyclerView.getAdapter() != null) {
            this.recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    private void updateLayoutManager() {
        if (this.isGridView) {
            int orientation = getResources().getConfiguration().orientation;
            int spanCount = orientation == 2 ? 4 : 2;
            this.recyclerView.setLayoutManager(new GridLayoutManager(getContext(), spanCount));
            if (this.dividerItemDecoration != null) {
                this.recyclerView.removeItemDecoration(this.dividerItemDecoration);
                this.dividerItemDecoration = null;
            }
        } else {
            this.recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            if (this.dividerItemDecoration == null) {
                this.dividerItemDecoration = new DividerItemDecoration(getContext(), 1);
                this.recyclerView.addItemDecoration(this.dividerItemDecoration);
            }
        }
        if (this.recyclerView.getAdapter() != null) {
            this.recyclerView.setAdapter(this.recyclerView.getAdapter());
        }
    }

    public void loadShortcutsList() {
        ArrayList<Shortcut> shortcuts = this.manager.loadShortcuts();
        if (shortcuts != null) {
            shortcuts.removeIf(new Predicate() { // from class: com.winlator.cmod.ShortcutsFragment$$ExternalSyntheticLambda1
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return ShortcutsFragment.lambda$loadShortcutsList$1((Shortcut) obj);
                }
            });
            Bitmap defaultIcon = BitmapFactory.decodeResource(getResources(), com.ludashi.benchmark.R.drawable.icon_wine);
            Iterator<Shortcut> it = shortcuts.iterator();
            while (it.hasNext()) {
                Shortcut shortcut = it.next();
                if (shortcut.icon == null) {
                    shortcut.icon = defaultIcon;
                }
            }
            this.recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
            if (!shortcuts.isEmpty()) {
                this.emptyTextView.setVisibility(8);
            } else {
                this.emptyTextView.setVisibility(0);
            }
        }
    }

    static /* synthetic */ boolean lambda$loadShortcutsList$1(Shortcut shortcut) {
        return shortcut == null || shortcut.file == null || shortcut.file.getName().isEmpty();
    }

    private void updateShortcutIcon(Uri sourceUri, Shortcut shortcut) {
        try {
            File targetDir = getImagesDir(this.isGridView);
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File destFile = new File(targetDir, baseName + ".png");
            InputStream is = getContext().getContentResolver().openInputStream(sourceUri);
            try {
                OutputStream os = new FileOutputStream(destFile);
                try {
                    byte[] buffer = new byte[1024];
                    while (true) {
                        int length = is.read(buffer);
                        if (length <= 0) {
                            break;
                        } else {
                            os.write(buffer, 0, length);
                        }
                    }
                    os.close();
                    if (is != null) {
                        is.close();
                    }
                    Toast.makeText(getContext(), "Icon updated!", 0).show();
                    if (this.recyclerView.getAdapter() != null) {
                        this.recyclerView.getAdapter().notifyDataSetChanged();
                    }
                } finally {
                }
            } finally {
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Error saving icon", 0).show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getSmartGameName(Shortcut item) {
        String gameQueryName = item.name;
        boolean isShort = gameQueryName.length() < 6;
        boolean isAcronym = gameQueryName.matches("^[A-Z0-9\\s]{1,7}$");
        if (isShort || isAcronym) {
            try {
                String execPath = item.getExecutable();
                if (execPath != null && !execPath.isEmpty()) {
                    String cleanPath = execPath.replace("\"", "").replace("\\", "/");
                    String[] parts = cleanPath.split("/");
                    if (parts.length >= 2) {
                        String folderName = parts[parts.length - 2];
                        String lowerFolder = folderName.toLowerCase();
                        if ((lowerFolder.equals("bin") || lowerFolder.equals("bin32") || lowerFolder.equals("bin64") || lowerFolder.equals("win32") || lowerFolder.equals("win64") || lowerFolder.equals("system") || lowerFolder.equals("system32") || lowerFolder.equals("release") || lowerFolder.equals("retail") || lowerFolder.equals("game")) && parts.length >= 3) {
                            folderName = parts[parts.length - 3];
                        }
                        if (folderName == null) {
                            return gameQueryName;
                        }
                        if (folderName.length() > 2) {
                            return folderName;
                        }
                        return gameQueryName;
                    }
                    return gameQueryName;
                }
                return gameQueryName;
            } catch (Exception e) {
                return gameQueryName;
            }
        }
        return gameQueryName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    class ShortcutsAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<Shortcut> data;

        /* JADX INFO: Access modifiers changed from: private */
        class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView imageView;
            private final View innerArea;
            private final View menuButton;
            private final TextView subtitle;
            private final TextView title;

            private ViewHolder(View view) {
                super(view);
                this.imageView = (ImageView) view.findViewById(com.ludashi.benchmark.R.id.ImageView);
                this.title = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVTitle);
                this.subtitle = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVSubtitle);
                this.menuButton = view.findViewById(com.ludashi.benchmark.R.id.BTMenu);
                this.innerArea = view.findViewById(com.ludashi.benchmark.R.id.LLInnerArea);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemViewType(int i) {
            return ShortcutsFragment.this.isGridView ? 1 : 0;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            int layoutId = viewType == 1 ? com.ludashi.benchmark.R.layout.shortcut_grid_item : com.ludashi.benchmark.R.layout.shortcut_list_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false));
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onViewRecycled(ViewHolder holder) {
            if (holder.menuButton != null) {
                holder.menuButton.setOnClickListener(null);
            }
            holder.innerArea.setOnClickListener(null);
            holder.innerArea.setOnLongClickListener(null);
            super.onViewRecycled((ShortcutsAdapter) holder);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Shortcut item = this.data.get(position);
            holder.title.setText(item.name);
            holder.subtitle.setText(item.container.getName());
            holder.innerArea.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda4
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ShortcutsFragment.ShortcutsAdapter.this.lambda$onBindViewHolder$0(item, view);
                }
            });
            String gameQueryName = ShortcutsFragment.this.getSmartGameName(item);
            if (ShortcutsFragment.this.isGridView) {
                holder.innerArea.setOnLongClickListener(new View.OnLongClickListener() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda5
                    @Override // android.view.View.OnLongClickListener
                    public final boolean onLongClick(View view) {
                        boolean lambda$onBindViewHolder$1;
                        lambda$onBindViewHolder$1 = ShortcutsFragment.ShortcutsAdapter.this.lambda$onBindViewHolder$1(holder, item, view);
                        return lambda$onBindViewHolder$1;
                    }
                });
                if (holder.menuButton != null) {
                    holder.menuButton.setVisibility(8);
                }
                File targetDir = ShortcutsFragment.this.getImagesDir(true);
                final File imgFile = new File(targetDir, FileUtils.getBasename(item.file.getPath()) + ".png");
                if (imgFile.exists()) {
                    holder.imageView.setImageBitmap(BitmapFactory.decodeFile(imgFile.getPath()));
                    return;
                } else {
                    holder.imageView.setImageResource(com.ludashi.benchmark.R.drawable.icon_wine);
                    GameImageFetcher.fetchImage(gameQueryName, imgFile, true, new Runnable() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda6
                        @Override // java.lang.Runnable
                        public final void run() {
                            ShortcutsFragment.ShortcutsAdapter.this.lambda$onBindViewHolder$3(imgFile, holder);
                        }
                    });
                    return;
                }
            }
            holder.innerArea.setOnLongClickListener(null);
            if (holder.menuButton != null) {
                holder.menuButton.setVisibility(0);
                holder.menuButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda7
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        ShortcutsFragment.ShortcutsAdapter.this.lambda$onBindViewHolder$4(item, view);
                    }
                });
            }
            File targetDir2 = ShortcutsFragment.this.getImagesDir(false);
            final File customIcon = new File(targetDir2, FileUtils.getBasename(item.file.getPath()) + ".png");
            if (customIcon.exists()) {
                holder.imageView.setImageBitmap(BitmapFactory.decodeFile(customIcon.getPath()));
                return;
            }
            if (item.icon != null) {
                holder.imageView.setImageBitmap(item.icon);
            } else {
                holder.imageView.setImageResource(com.ludashi.benchmark.R.drawable.icon_wine);
            }
            GameImageFetcher.fetchImage(gameQueryName, customIcon, false, new Runnable() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda8
                @Override // java.lang.Runnable
                public final void run() {
                    ShortcutsFragment.ShortcutsAdapter.this.lambda$onBindViewHolder$6(customIcon, holder);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(Shortcut item, View v) {
            runFromShortcut(item);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ boolean lambda$onBindViewHolder$1(ViewHolder holder, Shortcut item, View v) {
            lambda$onBindViewHolder$4(holder.title, item);
            return true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$3(final File imgFile, final ViewHolder holder) {
            if (ShortcutsFragment.this.getActivity() != null) {
                ShortcutsFragment.this.getActivity().runOnUiThread(new Runnable() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda9
                    @Override // java.lang.Runnable
                    public final void run() {
                        ShortcutsFragment.ShortcutsAdapter.lambda$onBindViewHolder$2(imgFile, holder);
                    }
                });
            }
        }

        static /* synthetic */ void lambda$onBindViewHolder$2(File imgFile, ViewHolder holder) {
            if (imgFile.exists()) {
                holder.imageView.setImageBitmap(BitmapFactory.decodeFile(imgFile.getPath()));
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$6(final File customIcon, final ViewHolder holder) {
            if (ShortcutsFragment.this.getActivity() != null) {
                ShortcutsFragment.this.getActivity().runOnUiThread(new Runnable() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        ShortcutsFragment.ShortcutsAdapter.lambda$onBindViewHolder$5(customIcon, holder);
                    }
                });
            }
        }

        static /* synthetic */ void lambda$onBindViewHolder$5(File customIcon, ViewHolder holder) {
            if (customIcon.exists()) {
                holder.imageView.setImageBitmap(BitmapFactory.decodeFile(customIcon.getPath()));
            }
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public final int getItemCount() {
            return this.data.size();
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* renamed from: showListItemMenu, reason: merged with bridge method [inline-methods] */
        public void lambda$onBindViewHolder$4(View anchorView, final Shortcut shortcut) {
            final Context context = ShortcutsFragment.this.getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= 29) {
                listItemMenu.setForceShowIcon(true);
            }
            listItemMenu.inflate(com.ludashi.benchmark.R.menu.shortcut_popup_menu);
            listItemMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda3
                @Override // android.widget.PopupMenu.OnMenuItemClickListener
                public final boolean onMenuItemClick(MenuItem menuItem) {
                    boolean lambda$showListItemMenu$9;
                    lambda$showListItemMenu$9 = ShortcutsFragment.ShortcutsAdapter.this.lambda$showListItemMenu$9(shortcut, context, menuItem);
                    return lambda$showListItemMenu$9;
                }
            });
            listItemMenu.show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ boolean lambda$showListItemMenu$9(final Shortcut shortcut, final Context context, MenuItem menuItem) {
            int itemId = menuItem.getItemId();
            if (itemId == com.ludashi.benchmark.R.id.shortcut_settings) {
                new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut).show();
                return true;
            }
            if (itemId == com.ludashi.benchmark.R.id.shortcut_change_icon) {
                ShortcutsFragment.this.shortcutForIconUpdate = shortcut;
                ShortcutsFragment.this.iconPickerLauncher.launch("image/*");
                return true;
            }
            if (itemId == com.ludashi.benchmark.R.id.shortcut_remove) {
                ContentDialog.confirm(context, com.ludashi.benchmark.R.string.do_you_want_to_remove_this_shortcut, new Runnable() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        ShortcutsFragment.ShortcutsAdapter.this.lambda$showListItemMenu$7(shortcut, context);
                    }
                });
                return true;
            }
            if (itemId == com.ludashi.benchmark.R.id.shortcut_clone_to_container) {
                ContainerManager containerManager = new ContainerManager(context);
                final ArrayList<Container> containers = containerManager.getContainers();
                AlertDialog.Builder builder = new AlertDialog.Builder(ShortcutsFragment.this.getContext());
                builder.setTitle("Select a container");
                String[] containerNames = new String[containers.size()];
                for (int i = 0; i < containers.size(); i++) {
                    containerNames[i] = containers.get(i).getName();
                }
                builder.setItems(containerNames, new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.ShortcutsFragment$ShortcutsAdapter$$ExternalSyntheticLambda2
                    @Override // android.content.DialogInterface.OnClickListener
                    public final void onClick(DialogInterface dialogInterface, int i2) {
                        ShortcutsFragment.ShortcutsAdapter.this.lambda$showListItemMenu$8(shortcut, containers, context, dialogInterface, i2);
                    }
                });
                builder.show();
                return true;
            }
            if (itemId == com.ludashi.benchmark.R.id.shortcut_add_to_home_screen) {
                if (shortcut.getExtra("uuid").equals("")) {
                    shortcut.genUUID();
                }
                ShortcutsFragment.this.addShortcutToScreen(shortcut);
                return true;
            }
            if (itemId == com.ludashi.benchmark.R.id.shortcut_export) {
                exportShortcut(shortcut);
                return true;
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$showListItemMenu$7(Shortcut shortcut, Context context) {
            boolean fileDeleted = shortcut.file.delete();
            try {
                String basePath = shortcut.file.getPath().substring(0, shortcut.file.getPath().lastIndexOf("."));
                new File(basePath + ".lnk").delete();
                new File(basePath + ".bat").delete();
            } catch (Exception e) {
            }
            if (fileDeleted) {
                ShortcutsFragment.disableShortcutOnScreen(ShortcutsFragment.this.requireContext(), shortcut);
                ShortcutsFragment.this.loadShortcutsList();
                Toast.makeText(context, "Shortcut removed.", 0).show();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$showListItemMenu$8(Shortcut shortcut, ArrayList containers, Context context, DialogInterface dialog, int which) {
            if (shortcut.cloneToContainer((Container) containers.get(which))) {
                Toast.makeText(context, "Cloned successfully.", 0).show();
                ShortcutsFragment.this.loadShortcutsList();
            }
        }

        private void runFromShortcut(Shortcut shortcut) {
            Activity activity = ShortcutsFragment.this.getActivity();
            if (!XrActivity.isEnabled(ShortcutsFragment.this.getContext())) {
                Intent intent = new Intent(activity, (Class<?>) XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                intent.putExtra("shortcut_name", shortcut.name);
                intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
                intent.putExtra("native_rendering", shortcut.getNativeRendering());
                activity.startActivity(intent);
                return;
            }
            XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }

        private void exportShortcut(Shortcut shortcut) {
            File shortcutsDir;
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ShortcutsFragment.this.getContext());
            String uriString = sharedPreferences.getString("shortcuts_export_path_uri", null);
            if (uriString != null) {
                Uri folderUri = Uri.parse(uriString);
                DocumentFile pickedDir = DocumentFile.fromTreeUri(ShortcutsFragment.this.getContext(), folderUri);
                if (pickedDir == null || !pickedDir.canWrite()) {
                    return;
                } else {
                    shortcutsDir = new File(FileUtils.getFilePathFromUri(ShortcutsFragment.this.getContext(), folderUri));
                }
            } else {
                shortcutsDir = new File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH);
            }
            if (shortcutsDir.exists() || shortcutsDir.mkdirs()) {
                File exportFile = new File(shortcutsDir, shortcut.file.getName());
                boolean containerIdFound = false;
                try {
                    List<String> lines = new ArrayList<>();
                    BufferedReader reader = new BufferedReader(new FileReader(shortcut.file));
                    while (true) {
                        try {
                            String line = reader.readLine();
                            if (line == null) {
                                break;
                            }
                            if (line.startsWith("container_id:")) {
                                lines.add("container_id:" + shortcut.container.id);
                                containerIdFound = true;
                            } else {
                                lines.add(line);
                            }
                        } finally {
                        }
                    }
                    reader.close();
                    if (!containerIdFound) {
                        lines.add("container_id:" + shortcut.container.id);
                    }
                    FileWriter writer = new FileWriter(exportFile, false);
                    try {
                        Iterator<String> it = lines.iterator();
                        while (it.hasNext()) {
                            writer.write(it.next() + "\n");
                        }
                        writer.flush();
                        writer.close();
                        Toast.makeText(ShortcutsFragment.this.getContext(), exportFile.exists() ? "Shortcut Updated" : "Shortcut Exported", 1).show();
                    } finally {
                    }
                } catch (IOException e) {
                }
            }
        }
    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), (Class<?>) XServerDisplayActivity.class);
        intent.setAction("android.intent.action.VIEW");
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);
        return new ShortcutInfo.Builder(getActivity(), uuid).setShortLabel(shortLabel).setLongLabel(longLabel).setIcon(icon).setIntent(intent).build();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = (ShortcutManager) ContextCompat.getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            File targetDir = getImagesDir(this.isGridView);
            File imgFile = new File(targetDir, FileUtils.getBasename(shortcut.file.getPath()) + ".png");
            Bitmap bmp = imgFile.exists() ? BitmapFactory.decodeFile(imgFile.getPath()) : shortcut.icon;
            if (bmp == null) {
                bmp = BitmapFactory.decodeResource(getResources(), com.ludashi.benchmark.R.drawable.icon_wine);
            }
            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id, shortcut.file.getPath(), Icon.createWithBitmap(bmp), shortcut.getExtra("uuid")), null);
        }
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = (ShortcutManager) ContextCompat.getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")), context.getString(com.ludashi.benchmark.R.string.shortcut_not_available));
        } catch (Exception e) {
        }
    }

    public void updateShortcutOnScreen(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        ShortcutManager shortcutManager = (ShortcutManager) ContextCompat.getSystemService(requireContext(), ShortcutManager.class);
        try {
            for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
                if (shortcutInfo.getId().equals(uuid)) {
                    shortcutManager.updateShortcuts(Collections.singletonList(buildScreenShortCut(shortLabel, longLabel, containerId, shortcutPath, icon, uuid)));
                    return;
                }
            }
        } catch (Exception e) {
        }
    }
}
