package com.winlator.cmod;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.bigpicture.steamgrid.SteamGridDBApi;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponse;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponseDeserializer;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridSearchResponse;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.ui.library.LibraryCallbacks;
import com.winlator.cmod.ui.library.LibraryComposeBinding;
import com.winlator.cmod.ui.library.LibraryComposeController;
import com.winlator.cmod.ui.library.LibraryComposeHost;
import com.winlator.cmod.ui.library.LibraryItem;
import com.winlator.cmod.ui.library.LibraryImageCache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ShortcutsFragment extends Fragment {
    private static final String TAG = "ShortcutsFragment";
    private static final int MENU_STEAM = 11;
    private static final int MENU_IMPORT_SHORTCUT = 10;
    private static final int MENU_VIEW_MODE = 1;
    private static final int MENU_SEARCH = 2;
    private static final int MENU_FILE_MANAGER = 3;
    private static final int MENU_MORE = 4;
    private static final int MENU_LOCK_ORIENTATION = 5;
    private static final int MENU_VERTICAL_MODE = 6;
    private static final int MENU_HORIZONTAL_MODE = 7;
    private static final int MENU_GROUP_LOCK = 8;
    private static final int MENU_GROUP_ORIENTATION_MODE = 9;
    private static final String STEAMGRID_BASE_URL = "https://www.steamgriddb.com/api/v2/";
    private static String STEAMGRID_API_KEY = "0324c52513634547a7b32d6d323635d0";
    private static final ExecutorService DYNAMIC_SHORTCUT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicInteger DYNAMIC_SHORTCUT_SYNC_GENERATION = new AtomicInteger();
    private static volatile String lastDynamicShortcutSignature;

    private static final class DynamicShortcutEntry {
        final String id;
        final String name;
        final String shortcutPath;
        final int containerId;
        final boolean favorite;
        final long lastRunAt;
        final String userIconPath;
        final String coverPath;
        final String bannerPath;
        final String autoIconPath;
        final Bitmap fallbackIcon;

        DynamicShortcutEntry(String id, String name, String shortcutPath, int containerId,
                             boolean favorite, long lastRunAt, String userIconPath,
                             String coverPath, String bannerPath, String autoIconPath,
                             Bitmap fallbackIcon) {
            this.id = id;
            this.name = name;
            this.shortcutPath = shortcutPath;
            this.containerId = containerId;
            this.favorite = favorite;
            this.lastRunAt = lastRunAt;
            this.userIconPath = userIconPath;
            this.coverPath = coverPath;
            this.bannerPath = bannerPath;
            this.autoIconPath = autoIconPath;
            this.fallbackIcon = fallbackIcon;
        }
    }

    private ContainerManager manager;
    private SharedPreferences preferences;
    private LibraryComposeController libraryController;
    
    private boolean isGridView = false;
    private final ArrayList<Shortcut> allShortcuts = new ArrayList<>();
    private final Set<String> artworkRequests = Collections.synchronizedSet(new HashSet<>());

    private Shortcut shortcutForIconUpdate;
    private ActivityResultLauncher<String> shortcutImportLauncher;
    private ActivityResultLauncher<String> iconPickerLauncher;
    private ActivityResultLauncher<String> contentPickerLauncher;
    private com.winlator.cmod.core.Callback<Uri> pendingContentPickerCallback;

    public static final int IMPORT_SHORTCUT = 1005;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        shortcutImportLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) chooseImportContainer(uri);
        });
        iconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && shortcutForIconUpdate != null) {
                updateShortcutIcon(uri, shortcutForIconUpdate);
            }
        });
        contentPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            com.winlator.cmod.core.Callback<Uri> cb = pendingContentPickerCallback;
            pendingContentPickerCallback = null;
            if (cb != null) cb.call(uri);
        });
    }

    public void pickContentArchive(com.winlator.cmod.core.Callback<Uri> callback) {
        pendingContentPickerCallback = callback;
        contentPickerLauncher.launch("*/*");
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        if (getActivity() != null && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.library);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().invalidateOptionsMenu();
        if (manager != null && libraryController != null) loadShortcutsList();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (!preferences.getBoolean("enhanced_library_migrated", false)) {
            isGridView = false;
            preferences.edit()
                    .putBoolean("shortcuts_grid_view", false)
                    .putBoolean("enhanced_library_migrated", true)
                    .apply();
        } else {
            isGridView = preferences.getBoolean("shortcuts_grid_view", false);
        }

        LibraryComposeBinding binding = LibraryComposeHost.create(
                requireContext(),
                isGridView,
                new LibraryCallbacks() {
                    @Override
                    public void onOpen(@NonNull String shortcutPath) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) openGameDetails(shortcut);
                    }

                    @Override
                    public void onRun(@NonNull String shortcutPath) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) runFromShortcut(shortcut);
                    }

                    @Override
                    public void onGridViewChanged(boolean gridView) {
                        setGridView(gridView);
                    }

                    @Override
                    public void onAction(@NonNull String shortcutPath, @NonNull String action) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) handleShortcutAction(shortcut, action);
                    }

                    @Override
                    public void onArtworkNeeded(@NonNull String shortcutPath, @NonNull String kind) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) requestArtwork(shortcut, kind);
                    }
                }
        );
        libraryController = binding.getController();
        return binding.getView();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        MenuItem steamItem = menu.add(0, MENU_STEAM, 0, "Steam account");
        steamItem.setVisible(com.winlator.cmod.steam.SteamSettings.enabled(requireContext()));
        steamItem.setIcon(R.drawable.ic_steam);
        steamItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        MenuItem viewItem = menu.add(0, MENU_VIEW_MODE, 0, isGridView ? com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_list_view) : com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_grid_view));
        viewItem.setIcon(R.drawable.ui_ic_view);
        viewItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        MenuItem searchItem = menu.add(0, MENU_SEARCH, 1, "Search");
        searchItem.setIcon(R.drawable.ui_ic_search);
        searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS |
                MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        SearchView searchView = new SearchView(requireContext());
        ((android.widget.ImageView) searchView.findViewById(androidx.appcompat.R.id.search_button)).setImageResource(R.drawable.ui_ic_search);
        searchView.setQueryHint(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_search_games));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (libraryController != null) libraryController.setSearchQuery(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                if (libraryController != null) libraryController.setSearchQuery(query);
                return true;
            }
        });
        searchItem.setActionView(searchView);

        MenuItem addItem = menu.add(0, MENU_FILE_MANAGER, 2, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_open_file_manager));
        addItem.setIcon(R.drawable.ui_ic_add);
        addItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        MainActivity activity = (MainActivity) requireActivity();
        SubMenu moreMenu = menu.addSubMenu(0, MENU_MORE, 3, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_more));
        MenuItem moreItem = moreMenu.getItem();
        moreItem.setIcon(R.drawable.ic_orientation);
        moreItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        moreMenu.add(0, MENU_IMPORT_SHORTCUT, 0, com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_import_shortcut));
        moreMenu.add(MENU_GROUP_LOCK, MENU_LOCK_ORIENTATION, 1, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_lock_screen_orientation))
                .setCheckable(true)
                .setChecked(activity.isOrientationLocked());
        moreMenu.add(MENU_GROUP_ORIENTATION_MODE, MENU_VERTICAL_MODE, 1, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_vertical_mode))
                .setCheckable(true)
                .setChecked(activity.isVerticalModeEnabled());
        moreMenu.add(MENU_GROUP_ORIENTATION_MODE, MENU_HORIZONTAL_MODE, 2, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_horizontal_mode))
                .setCheckable(true)
                .setChecked(activity.isHorizontalModeEnabled());
        moreMenu.setGroupCheckable(MENU_GROUP_LOCK, true, false);
        moreMenu.setGroupCheckable(MENU_GROUP_ORIENTATION_MODE, true, true);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_STEAM) {
            com.winlator.cmod.steam.SteamLibrary.showMenu(requireContext());
            return true;
        }
        if (item.getItemId() == MENU_IMPORT_SHORTCUT) {
            shortcutImportLauncher.launch("*/*");
            return true;
        }
        if (item.getItemId() == MENU_VIEW_MODE) {
            setGridView(!isGridView);
            return true;
        }
        if (item.getItemId() == MENU_FILE_MANAGER) {
            getParentFragmentManager().beginTransaction()
                    .setCustomAnimations(R.anim.winz_screen_enter, R.anim.winz_screen_exit, R.anim.winz_screen_enter, R.anim.winz_screen_exit)
                    .addToBackStack(null)
                    .replace(R.id.FLFragmentContainer, new FileManagerFragment())
                    .commit();
            return true;
        }
        MainActivity activity = (MainActivity) requireActivity();
        if (item.getItemId() == MENU_LOCK_ORIENTATION) {
            activity.toggleOrientationLock();
            return true;
        }
        if (item.getItemId() == MENU_VERTICAL_MODE) {
            activity.toggleVerticalMode();
            return true;
        }
        if (item.getItemId() == MENU_HORIZONTAL_MODE) {
            activity.toggleHorizontalMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setGridView(boolean gridView) {
        isGridView = gridView;
        preferences.edit().putBoolean("shortcuts_grid_view", isGridView).apply();
        if (libraryController != null) libraryController.setGridView(isGridView);
        requireActivity().invalidateOptionsMenu();
    }

    private static void dispatchArtworkResult(Runnable callback) {
        if (callback != null) new Handler(Looper.getMainLooper()).post(callback);
    }

    private void fetchCoverFromSteamGrid(Shortcut shortcut, File destFile,
                                          Runnable onSuccess, Runnable onFail) {
        fetchArtworkFromSteamGrid(shortcut, destFile, "600x900", onSuccess, onFail);
    }

    private void fetchBannerFromSteamGrid(Shortcut shortcut, File destFile,
                                           Runnable onSuccess, Runnable onFail) {
        fetchArtworkFromSteamGrid(shortcut, destFile, "920x430", onSuccess, () -> {
            if (destFile.isFile()) {
                // Keep the legacy banner when no larger version exists.
                if (onFail != null) onFail.run();
            } else {
                // Some games only have a 460x215 grid on SteamGridDB.
                fetchArtworkFromSteamGrid(shortcut, destFile, "460x215", onSuccess, onFail);
            }
        });
    }

    private void fetchArtworkFromSteamGrid(Shortcut shortcut, File destFile, String dimensions,
                                            Runnable onSuccess, Runnable onFail) {
        Context context = getContext();
        if (context == null) {
            dispatchArtworkResult(onFail);
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        if (!prefs.getBoolean("auto_download_game_artwork", true)) {
            dispatchArtworkResult(onFail);
            return;
        }
        if (prefs.getBoolean("enable_custom_api_key", false)) {
            String custom = prefs.getString("custom_api_key", "");
            if (custom != null && !custom.isEmpty()) STEAMGRID_API_KEY = custom;
        }
        if (STEAMGRID_API_KEY.isEmpty()) {
            dispatchArtworkResult(onFail);
            return;
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(STEAMGRID_BASE_URL)
                .client(new OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        SteamGridDBApi api = retrofit.create(SteamGridDBApi.class);
        api.searchGame("Bearer " + STEAMGRID_API_KEY, shortcut.name)
                .enqueue(new Callback<SteamGridSearchResponse>() {
                    @Override
                    public void onResponse(Call<SteamGridSearchResponse> call,
                                           Response<SteamGridSearchResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().data != null
                                && !response.body().data.isEmpty()) {
                            fetchSteamGridArtwork(response.body().data.get(0).id, destFile,
                                    dimensions, onSuccess, onFail);
                        } else {
                            dispatchArtworkResult(onFail);
                        }
                    }

                    @Override
                    public void onFailure(Call<SteamGridSearchResponse> call, Throwable error) {
                        Log.e(TAG, "SteamGridDB search failed: " + error.getMessage());
                        dispatchArtworkResult(onFail);
                    }
                });
    }

    private void fetchSteamGridArtwork(int gameId, File destFile, String dimensions,
                                        Runnable onSuccess, Runnable onFail) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SteamGridGridsResponse.class,
                        new SteamGridGridsResponseDeserializer())
                .create();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(STEAMGRID_BASE_URL)
                .client(new OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        SteamGridDBApi api = retrofit.create(SteamGridDBApi.class);
        api.getGridsByGameId("Bearer " + STEAMGRID_API_KEY, gameId,
                "alternate", dimensions, "static")
                .enqueue(new Callback<SteamGridGridsResponse>() {
                    @Override
                    public void onResponse(Call<SteamGridGridsResponse> call,
                                           Response<SteamGridGridsResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().data != null
                                && !response.body().data.isEmpty()) {
                            downloadAndSaveCover(response.body().data.get(0).url,
                                    destFile, onSuccess, onFail);
                        } else {
                            dispatchArtworkResult(onFail);
                        }
                    }

                    @Override
                    public void onFailure(Call<SteamGridGridsResponse> call, Throwable error) {
                        Log.e(TAG, "SteamGridDB artwork failed: " + error.getMessage());
                        dispatchArtworkResult(onFail);
                    }
                });
    }

    private void downloadAndSaveCover(String url, File destFile,
                                       Runnable onSuccess, Runnable onFail) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.connect();
                Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                if (bmp == null) { dispatchArtworkResult(onFail); return; }

                if (destFile.getParentFile() != null) destFile.getParentFile().mkdirs();
                // Keep the previous artwork visible until the new download is complete.
                File pending = new File(destFile.getParentFile(), destFile.getName() + ".download");
                try {
                    try (FileOutputStream fos = new FileOutputStream(pending)) {
                        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                            throw new IOException("Could not encode artwork");
                        }
                    }
                    if (!pending.renameTo(destFile)) {
                        throw new IOException("Could not replace artwork");
                    }
                } finally {
                    bmp.recycle();
                    if (pending.exists()) pending.delete();
                }
                Log.d(TAG, "SteamGridDB cover salvo: " + destFile.getAbsolutePath());
                dispatchArtworkResult(onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "Falha ao baixar cover do SteamGridDB: " + e.getMessage());
                dispatchArtworkResult(onFail);
            }
        });
    }

    private File getImagesDir(boolean isCover) {
        File targetDir = new File(Environment.getExternalStorageDirectory(), isCover ? "Winlator/covers" : "Winlator/icons");
        if (!targetDir.exists()) targetDir.mkdirs();
        
        File nomedia = new File(targetDir, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); } catch (IOException e) {}
        }
        return targetDir;
    }

    private File getBannerDir() {
        File targetDir = new File(Environment.getExternalStorageDirectory(), "Winlator/banners");
        if (!targetDir.exists()) targetDir.mkdirs();
        File nomedia = new File(targetDir, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); } catch (IOException ignored) {}
        }
        return targetDir;
    }

    public void loadShortcutsList() {
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts();
        allShortcuts.clear();
        if (shortcuts != null) {
            shortcuts.removeIf(shortcut -> shortcut == null || shortcut.file == null || shortcut.file.getName().isEmpty());
            Bitmap defaultIcon = BitmapFactory.decodeResource(getResources(), R.drawable.icon_wine);
            for (Shortcut shortcut : shortcuts) {
                if (shortcut.icon == null) shortcut.icon = defaultIcon;
            }
            allShortcuts.addAll(shortcuts);
        }
        publishLibraryItems();
        syncDynamicAppShortcuts();
    }

    private Shortcut findShortcut(String shortcutPath) {
        for (Shortcut shortcut : allShortcuts) {
            if (shortcut.file != null && shortcut.file.getPath().equals(shortcutPath)) return shortcut;
        }
        return null;
    }

    private void publishLibraryItems() {
        if (libraryController == null) return;
        ArrayList<LibraryItem> items = new ArrayList<>();
        for (Shortcut shortcut : allShortcuts) {
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File userIcon = new File(getImagesDir(false), baseName + ".user.png");
            File autoIcon = new File(getImagesDir(false), baseName + ".png");
            File cover = new File(getImagesDir(true), baseName + ".png");
            File banner = new File(getBannerDir(), baseName + ".png");
            String iconPath = userIcon.exists() ? userIcon.getPath() :
                    (autoIcon.exists() ? autoIcon.getPath() : null);

            items.add(new LibraryItem(
                    shortcut.file.getPath(),
                    shortcut.file.getPath(),
                    shortcut.name,
                    shortcut.container != null ? shortcut.container.getName() : "",
                    cover.exists() ? cover.getPath() : null,
                    banner.exists() ? banner.getPath() : null,
                    iconPath,
                    shortcut.icon,
                    "1".equals(shortcut.getExtra("favorite", "0")),
                    parseLastRunAt(shortcut)
            ));
        }
        libraryController.setItems(items);
    }

    private long parseLastRunAt(Shortcut shortcut) {
        try {
            return Long.parseLong(shortcut.getExtra("lastRunAt", "0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void requestArtwork(Shortcut shortcut, String kind) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File autoIcon = new File(getImagesDir(false), baseName + ".png");
        File cover = new File(getImagesDir(true), baseName + ".png");
        File banner = new File(getBannerDir(), baseName + ".png");

        if ("cover".equals(kind)) {
            requestCover(shortcut, cover, autoIcon);
        } else if ("banner".equals(kind)) {
            requestBanner(shortcut, banner);
        }
    }

    private void requestCover(Shortcut shortcut, File cover, File autoIcon) {
        final String coverKey = "cover:" + shortcut.file.getPath();
        if (!cover.exists() && artworkRequests.add(coverKey)) {
            fetchCoverFromSteamGrid(shortcut, cover, () -> {
                artworkRequests.remove(coverKey);
                new Handler(Looper.getMainLooper()).post(() -> refreshArtworkAndLauncherShortcuts(shortcut));
            }, () -> {
                artworkRequests.remove(coverKey);
                File exeFile = resolveExeFile(shortcut);
                if (exeFile != null && !autoIcon.exists()) {
                    ExeIconExtractor.extractAsync(exeFile, autoIcon, false, () -> {
                        new Handler(Looper.getMainLooper()).post(() -> refreshArtworkAndLauncherShortcuts(shortcut));
                    });
                }
            });
        }
    }

    private static boolean isLegacyLowResolutionBanner(File banner) {
        if (!banner.isFile()) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(banner.getPath(), bounds);
        // SteamGridDB's old automatic banner size. Preserve other sizes/custom artwork.
        return bounds.outWidth == 460 && bounds.outHeight == 215;
    }

    private void requestBanner(Shortcut shortcut, File banner) {
        final String bannerKey = "banner:" + shortcut.file.getPath();
        if ((!banner.isFile() || isLegacyLowResolutionBanner(banner))
                && artworkRequests.add(bannerKey)) {
            fetchBannerFromSteamGrid(shortcut, banner, () -> {
                artworkRequests.remove(bannerKey);
                // The library fragment can be on the back stack while details are visible.
                new Handler(Looper.getMainLooper()).post(() -> {
                    LibraryImageCache.notifyArtworkChanged();
                    if (isAdded()) refreshArtworkAndLauncherShortcuts(shortcut);
                });
            }, () -> artworkRequests.remove(bannerKey));
        }
    }

    private void openGameDetails(Shortcut shortcut) {
        if (!shortcut.getExtra("steamAppId").isEmpty()) {
            com.winlator.cmod.steam.SteamLibrary.INSTANCE.open(requireContext(), "steam://" + shortcut.getExtra("steamAppId"));
            return;
        }
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File banner = new File(getBannerDir(), baseName + ".png");
        if (isLegacyLowResolutionBanner(banner)) requestBanner(shortcut, banner);
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.winz_screen_enter, R.anim.winz_screen_exit, R.anim.winz_screen_enter, R.anim.winz_screen_exit)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new GameDetailFragment(shortcut.file.getPath()))
                .commit();
    }

    private void updateShortcutIcon(Uri sourceUri, Shortcut shortcut) {
        try {
            File targetDir = getImagesDir(false);
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File destFile = new File(targetDir, baseName + ".user.png");

            try (InputStream is = getContext().getContentResolver().openInputStream(sourceUri);
                 OutputStream os = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
            }

            Bitmap selectedIcon = BitmapFactory.decodeFile(destFile.getPath());
            if (selectedIcon != null) {
                shortcut.icon = selectedIcon;
                String uuid = shortcut.getExtra("uuid");
                if (!uuid.isEmpty()) {
                    updateShortcutOnScreen(shortcut.name, shortcut.name, shortcut.container.id,
                            shortcut.file.getPath(), Icon.createWithBitmap(selectedIcon), uuid);
                }
            }

            Toast.makeText(getContext(), com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_icon_updated), Toast.LENGTH_SHORT).show();
            loadShortcutsList();

        } catch (Exception e) {
            Toast.makeText(getContext(), com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_error_saving_icon), Toast.LENGTH_SHORT).show();
        }
    }

    
    private File resolveExeFile(Shortcut item) {
        if (item.path == null || item.path.isEmpty()) return null;

        String path = item.path.replace("\\", "/").trim();

        if (path.startsWith("\"") && path.endsWith("\""))
            path = path.substring(1, path.length() - 1);

        if (path.startsWith("/")) {
            File f = new File(path);
            if (f.exists()) return f;
        }

        if (path.length() >= 3 && path.charAt(1) == ':' && path.charAt(2) == '/') {
            String drive    = path.substring(0, 1).toLowerCase();
            String relative = path.substring(3);

            if (item.container != null) {
                for (String[] entry : item.container.drivesIterator()) {
                    if (entry == null || entry.length < 2 || entry[0] == null || entry[1] == null) continue;
                    if (entry[0].replace(":", "").trim().equalsIgnoreCase(drive)) {
                        File f = new File(entry[1], relative);
                        if (f.exists()) return f;
                    }
                }
            }

            switch (drive) {
                case "c": {
                    File root = item.container != null ? item.container.getRootDir() : null;
                    if (root != null) {
                        File f = new File(root, ".wine/drive_c/" + relative);
                        if (f.exists()) return f;
                    }
                    break;
                }
                case "d": {
                    File f = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), relative);
                    if (f.exists()) return f;
                    f = new File(Environment.getExternalStorageDirectory(), relative);
                    if (f.exists()) return f;
                    break;
                }
                case "z": {
                    File f = new File("/" + relative);
                    if (f.exists()) return f;
                    break;
                }
            }
        }

        return null;
    }

    private void runFromShortcut(Shortcut shortcut) {
        Activity activity = getActivity();
        if (activity == null) return;
        shortcut.putExtra("lastRunAt", String.valueOf(System.currentTimeMillis()));
        shortcut.saveData();
        syncDynamicAppShortcuts();
        if (libraryController != null) {
            libraryController.setSelectedShortcutPath(shortcut.file.getPath());
            publishLibraryItems();
        }
        if (!XrActivity.isEnabled(getContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            intent.putExtra("shortcut_path", shortcut.file.getPath());
            intent.putExtra("shortcut_name", shortcut.name);
            intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
            intent.putExtra("native_rendering", shortcut.getRendererNative());
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }
    }

    private void handleShortcutAction(Shortcut shortcut, String action) {
        Context context = getContext();
        if (context == null) return;

        if (LibraryComposeHost.ACTION_FAVORITE.equals(action)) {
            boolean favorite = "1".equals(shortcut.getExtra("favorite", "0"));
            shortcut.putExtra("favorite", favorite ? "0" : "1");
            shortcut.saveData();
            loadShortcutsList();
        }
        else if (LibraryComposeHost.ACTION_SETTINGS.equals(action)) {
            ShortcutSettingsComposeDialog.show(this, shortcut);
        }
        else if (LibraryComposeHost.ACTION_ICON.equals(action)) {
            shortcutForIconUpdate = shortcut;
            iconPickerLauncher.launch("image/*");
        }
        else if (LibraryComposeHost.ACTION_REMOVE.equals(action)) {
            com.winlator.cmod.ui.ThemedAlertHost.confirm((androidx.appcompat.app.AppCompatActivity) requireActivity(), getString(R.string.ui_remove_shortcut_title), getString(R.string.do_you_want_to_remove_this_shortcut), getString(R.string.remove), () -> {
                boolean fileDeleted = shortcut.file.delete();
                try {
                    String basePath = shortcut.file.getPath().substring(0, shortcut.file.getPath().lastIndexOf("."));
                    new File(basePath + ".lnk").delete();
                    new File(basePath + ".bat").delete();
                } catch (Exception ignored) {}

                if (fileDeleted) {
                    disableShortcutOnScreen(requireContext(), shortcut);
                    loadShortcutsList();
                    Toast.makeText(context, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_shortcut_removed), Toast.LENGTH_SHORT).show();
                }
            }, true);
        }
        else if (LibraryComposeHost.ACTION_CLONE.equals(action)) {
            ContainerManager containerManager = new ContainerManager(context);
            ArrayList<Container> containers = containerManager.getContainers();
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_select_a_container));
            String[] containerNames = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) {
                containerNames[i] = containers.get(i).getName();
            }
            builder.setItems(containerNames, (dialog, which) -> {
                if (shortcut.cloneToContainer(containers.get(which))) {
                    Toast.makeText(context, com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_cloned_successfully), Toast.LENGTH_SHORT).show();
                    loadShortcutsList();
                }
            });
            builder.show();
        }
        else if (LibraryComposeHost.ACTION_HOME.equals(action)) {
            if (shortcut.getExtra("uuid").equals("")) shortcut.genUUID();
            addShortcutToScreen(shortcut);
        }
        else if (LibraryComposeHost.ACTION_EXPORT.equals(action)) {
            exportShortcut(shortcut);
        }
    }

    private void chooseImportContainer(Uri uri) {
        List<Container> containers = new ContainerManager(requireContext()).getContainers();
        if (containers.isEmpty()) {
            Toast.makeText(requireContext(), com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_create_container_first), Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) names[i] = containers.get(i).getName();
        new AlertDialog.Builder(requireContext())
                .setTitle(com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_choose_container))
                .setItems(names, (dialog, which) -> importDesktopShortcut(uri, containers.get(which)))
                .setNegativeButton(com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_cancel), null)
                .show();
    }

    private void importDesktopShortcut(Uri uri, Container container) {
        try {
            byte[] bytes;
            try (InputStream stream = requireContext().getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (stream == null) throw new IOException(com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_unable_to_open_file));
                byte[] buffer = new byte[4096];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (output.size() + n > 256 * 1024) throw new IOException(com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_shortcut_too_large));
                    output.write(buffer, 0, n);
                }
                bytes = output.toByteArray();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.contains("[Desktop Entry]") || !text.matches("(?s).*?\\bExec=.*"))
                throw new IOException(com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_invalid_desktop));
            // The exported container_id is only metadata, never a destination override.
            text = text.replaceAll("(?m)^container_id:.*(?:\\r?\\n|$)", "");
            File desktopDir = container.getDesktopDir();
            if (!desktopDir.isDirectory() && !desktopDir.mkdirs())
                throw new IOException(com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_cannot_access_desktop));
            String name = com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_imported_shortcut_name);
            for (String line : text.split("\\r?\\n")) {
                if (line.startsWith("Name=")) { name = line.substring(5); break; }
            }
            name = name.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
            if (name.isEmpty()) name = com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_imported_shortcut_name);
            if (name.length() > 80) name = name.substring(0, 80);
            File target = new File(desktopDir, name + ".desktop");
            for (int i = 2; target.exists(); i++) {
                target = new File(desktopDir, name + " (" + i + ").desktop");
            }
            try (FileOutputStream stream = new FileOutputStream(target)) {
                stream.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(requireContext(), com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_shortcut_imported), Toast.LENGTH_SHORT).show();
            loadShortcutsList();
        } catch (Exception e) {
            Toast.makeText(requireContext(), com.winlator.cmod.ui.UiStrings.get(R.string.ui_preset_import_failed, e.getMessage() != null ? e.getMessage() : ""), Toast.LENGTH_LONG).show();
        }
    }

    private void exportShortcut(Shortcut shortcut) {
        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(getContext());
        String uriString = sharedPreferences.getString("shortcuts_export_path_uri", null);
        File shortcutsDir;

        if (uriString != null) {
            Uri folderUri = Uri.parse(uriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(requireContext(), folderUri);
            if (pickedDir == null || !pickedDir.canWrite()) return;
            shortcutsDir = new File(FileUtils.getFilePathFromUri(requireContext(), folderUri));
        } else {
            shortcutsDir = new File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH);
        }

        if (!shortcutsDir.exists() && !shortcutsDir.mkdirs()) return;
        File exportFile = new File(shortcutsDir, shortcut.file.getName());
        boolean containerIdFound = false;

        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(shortcut.file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id:")) {
                        lines.add("container_id:" + shortcut.container.id);
                        containerIdFound = true;
                    } else {
                        lines.add(line);
                    }
                }
            }
            if (!containerIdFound) lines.add("container_id:" + shortcut.container.id);
            try (FileWriter writer = new FileWriter(exportFile, false)) {
                for (String line : lines) writer.write(line + "\n");
                writer.flush();
            }
            Toast.makeText(getContext(), exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException ignored) {}
    }

    private static Bitmap centerCropSquare(Bitmap source) {
        if (source == null) return null;
        int size = Math.min(source.getWidth(), source.getHeight());
        int x = Math.max(0, (source.getWidth() - size) / 2);
        int y = Math.max(0, (source.getHeight() - size) / 2);
        if (x == 0 && y == 0 && source.getWidth() == source.getHeight()) return source;
        return Bitmap.createBitmap(source, x, y, size, size);
    }

    private Bitmap getLauncherShortcutBitmap(Shortcut shortcut, ShortcutManager shortcutManager) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File userIconFile = new File(getImagesDir(false), baseName + ".user.png");
        File coverFile = new File(getImagesDir(true), baseName + ".png");
        File bannerFile = new File(getBannerDir(), baseName + ".png");
        File autoIconFile = new File(getImagesDir(false), baseName + ".png");

        Bitmap bitmap = null;
        boolean artwork = false;

        if (userIconFile.isFile()) {
            bitmap = BitmapFactory.decodeFile(userIconFile.getPath());
        }
        if (bitmap == null && coverFile.isFile()) {
            bitmap = BitmapFactory.decodeFile(coverFile.getPath());
            artwork = bitmap != null;
        }
        if (bitmap == null && bannerFile.isFile()) {
            bitmap = BitmapFactory.decodeFile(bannerFile.getPath());
            artwork = bitmap != null;
        }
        if (bitmap == null && autoIconFile.isFile()) {
            bitmap = BitmapFactory.decodeFile(autoIconFile.getPath());
        }
        if (bitmap == null) bitmap = shortcut.icon;
        if (bitmap == null) bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon_wine);
        if (bitmap == null) return null;

        if (artwork) bitmap = centerCropSquare(bitmap);

        int maxWidth = Math.max(1, shortcutManager.getIconMaxWidth());
        int maxHeight = Math.max(1, shortcutManager.getIconMaxHeight());
        if (bitmap.getWidth() <= maxWidth && bitmap.getHeight() <= maxHeight) return bitmap;

        float scale = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
        int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private void refreshArtworkAndLauncherShortcuts(Shortcut shortcut) {
        if (!isAdded()) return;
        publishLibraryItems();
        syncDynamicAppShortcuts();

        if (shortcut == null || shortcut.container == null) return;
        String uuid = shortcut.getExtra("uuid");
        if (uuid.isEmpty()) return;

        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager == null) return;

        Bitmap bitmap = getLauncherShortcutBitmap(shortcut, shortcutManager);
        if (bitmap == null) return;

        updateShortcutOnScreen(shortcut.name, shortcut.name, shortcut.container.id,
                shortcut.file.getPath(), Icon.createWithBitmap(bitmap), uuid);
    }

    private ArrayList<DynamicShortcutEntry> snapshotDynamicShortcuts() {
        ArrayList<DynamicShortcutEntry> snapshot = new ArrayList<>();
        File winlatorDir = new File(Environment.getExternalStorageDirectory(), "Winlator");
        File iconDir = new File(winlatorDir, "icons");
        File coverDir = new File(winlatorDir, "covers");
        File bannerDir = new File(winlatorDir, "banners");

        for (Shortcut shortcut : allShortcuts) {
            if (shortcut == null || shortcut.file == null || shortcut.container == null) continue;

            String shortcutPath = shortcut.file.getPath();
            String storedUuid = shortcut.getExtra("uuid");
            String dynamicId = storedUuid.isEmpty()
                    ? "game-" + UUID.nameUUIDFromBytes(shortcutPath.getBytes(StandardCharsets.UTF_8))
                    : storedUuid;
            String baseName = FileUtils.getBasename(shortcutPath);

            snapshot.add(new DynamicShortcutEntry(
                    dynamicId,
                    shortcut.name,
                    shortcutPath,
                    shortcut.container.id,
                    "1".equals(shortcut.getExtra("favorite", "0")),
                    parseLastRunAt(shortcut),
                    new File(iconDir, baseName + ".user.png").getPath(),
                    new File(coverDir, baseName + ".png").getPath(),
                    new File(bannerDir, baseName + ".png").getPath(),
                    new File(iconDir, baseName + ".png").getPath(),
                    shortcut.icon
            ));
        }
        return snapshot;
    }

    private static String buildDynamicShortcutSignature(List<DynamicShortcutEntry> entries) {
        StringBuilder signature = new StringBuilder();
        for (DynamicShortcutEntry entry : entries) {
            File userIcon = new File(entry.userIconPath);
            File cover = new File(entry.coverPath);
            File banner = new File(entry.bannerPath);
            File autoIcon = new File(entry.autoIconPath);
            signature.append(entry.id).append('|')
                    .append(entry.name).append('|')
                    .append(entry.favorite).append('|')
                    .append(entry.lastRunAt).append('|')
                    .append(userIcon.lastModified()).append(':').append(userIcon.length()).append('|')
                    .append(cover.lastModified()).append(':').append(cover.length()).append('|')
                    .append(banner.lastModified()).append(':').append(banner.length()).append('|')
                    .append(autoIcon.lastModified()).append(':').append(autoIcon.length()).append(';');
        }
        return signature.toString();
    }

    private static Bitmap getDynamicShortcutBitmap(DynamicShortcutEntry entry,
                                                   ShortcutManager shortcutManager,
                                                   Context context) {
        Bitmap bitmap = null;
        boolean artwork = false;

        File userIcon = new File(entry.userIconPath);
        File cover = new File(entry.coverPath);
        File banner = new File(entry.bannerPath);
        File autoIcon = new File(entry.autoIconPath);

        if (userIcon.isFile()) bitmap = BitmapFactory.decodeFile(userIcon.getPath());
        if (bitmap == null && cover.isFile()) {
            bitmap = BitmapFactory.decodeFile(cover.getPath());
            artwork = bitmap != null;
        }
        if (bitmap == null && banner.isFile()) {
            bitmap = BitmapFactory.decodeFile(banner.getPath());
            artwork = bitmap != null;
        }
        if (bitmap == null && autoIcon.isFile()) bitmap = BitmapFactory.decodeFile(autoIcon.getPath());
        if (bitmap == null) bitmap = entry.fallbackIcon;
        if (bitmap == null) bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.icon_wine);
        if (bitmap == null) return null;

        if (artwork) bitmap = centerCropSquare(bitmap);

        int maxWidth = Math.max(1, shortcutManager.getIconMaxWidth());
        int maxHeight = Math.max(1, shortcutManager.getIconMaxHeight());
        if (bitmap.getWidth() <= maxWidth && bitmap.getHeight() <= maxHeight) return bitmap;

        float scale = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
        int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private void syncDynamicAppShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;

        Context context = getContext();
        if (context == null) return;

        Context appContext = context.getApplicationContext();
        ArrayList<DynamicShortcutEntry> snapshot = snapshotDynamicShortcuts();
        int generation = DYNAMIC_SHORTCUT_SYNC_GENERATION.incrementAndGet();

        DYNAMIC_SHORTCUT_EXECUTOR.execute(() -> {
            if (generation != DYNAMIC_SHORTCUT_SYNC_GENERATION.get()) return;

            ShortcutManager shortcutManager = appContext.getSystemService(ShortcutManager.class);
            if (shortcutManager == null) return;

            try {
                String signature = buildDynamicShortcutSignature(snapshot);
                if (signature.equals(lastDynamicShortcutSignature)) return;

                int maxCount = shortcutManager.getMaxShortcutCountPerActivity();
                if (maxCount <= 0 || snapshot.isEmpty()) {
                    if (generation != DYNAMIC_SHORTCUT_SYNC_GENERATION.get()) return;
                    shortcutManager.removeAllDynamicShortcuts();
                    lastDynamicShortcutSignature = signature;
                    return;
                }

                snapshot.sort((first, second) -> {
                    if (first.favorite != second.favorite) return first.favorite ? -1 : 1;
                    int recent = Long.compare(second.lastRunAt, first.lastRunAt);
                    if (recent != 0) return recent;
                    return first.name.compareToIgnoreCase(second.name);
                });

                ArrayList<ShortcutInfo> dynamicShortcuts = new ArrayList<>();
                int count = Math.min(maxCount, snapshot.size());
                for (int i = 0; i < count; i++) {
                    if (generation != DYNAMIC_SHORTCUT_SYNC_GENERATION.get()) return;

                    DynamicShortcutEntry entry = snapshot.get(i);
                    Bitmap bitmap = getDynamicShortcutBitmap(entry, shortcutManager, appContext);
                    if (bitmap == null) continue;

                    Intent intent = new Intent(appContext, XServerDisplayActivity.class);
                    intent.setAction(Intent.ACTION_VIEW);
                    intent.putExtra("container_id", entry.containerId);
                    intent.putExtra("shortcut_path", entry.shortcutPath);

                    dynamicShortcuts.add(new ShortcutInfo.Builder(appContext, entry.id)
                            .setShortLabel(entry.name)
                            .setLongLabel(entry.name)
                            .setIcon(Icon.createWithBitmap(bitmap))
                            .setIntent(intent)
                            .setRank(dynamicShortcuts.size())
                            .build());
                }

                if (generation != DYNAMIC_SHORTCUT_SYNC_GENERATION.get()) return;
                shortcutManager.setDynamicShortcuts(dynamicShortcuts);
                lastDynamicShortcutSignature = signature;
            } catch (Exception e) {
                Log.w(TAG, "Unable to sync dynamic app shortcuts", e);
            }
        });
    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);
        return new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            Bitmap bmp = getLauncherShortcutBitmap(shortcut, shortcutManager);
            if (bmp == null) return;

            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id,
                    shortcut.file.getPath(), Icon.createWithBitmap(bmp), shortcut.getExtra("uuid")), null);
        }
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")), context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        try {
            for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
                if (shortcutInfo.getId().equals(uuid)) {
                    shortcutManager.updateShortcuts(Collections.singletonList(
                            buildScreenShortCut(shortLabel, longLabel, containerId, shortcutPath, icon, uuid)));
                    break;
                }
            }
        } catch (Exception e) {}
    }
}
