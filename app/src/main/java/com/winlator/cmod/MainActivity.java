package com.winlator.cmod;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Html;
import android.text.format.Formatter;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.winlator.cmod.FileManagerFragment;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.ui.container.ContainerCreateComposeFragment;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;
import com.winlator.cmod.services.NotificationService;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final int PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 500;
    public static final int PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE = 501;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    public static final byte OPEN_IMAGE_REQUEST_CODE = 5;
    public static final String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static final int NOTIFICATION_ID = 100;
    private static final String ORIENTATION_MODE_AUTO = "auto";
    private static final String ORIENTATION_MODE_VERTICAL = "vertical";
    private static final String ORIENTATION_MODE_HORIZONTAL = "horizontal";
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private BottomNavigationView bottomNavigation;
    private View mainToolbar;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private Intent notificationService;
    private SharedPreferences sharedPreferences;
    private ContainerManager containerManager;
    private boolean isDarkMode;
    private boolean syncingBottomNavigation;
    private boolean orientationLocked;
    private String orientationMode = ORIENTATION_MODE_AUTO;
    private static final String PREF_ORIENTATION_LOCKED = "library_orientation_locked";
    private static final String PREF_ORIENTATION_MODE = "library_orientation_mode";

    private void createNotificationChannel() {
        String name = "Winlator";
        String description = com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_winlator_xserver_messages);
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Persist the default value on first run so all other components
        // (dialogs, fragments) read the correct value instead of their own default
        if (!sharedPreferences.contains("dark_mode")) {
            sharedPreferences.edit().putBoolean("dark_mode", true).apply();
        }

        isDarkMode = sharedPreferences.getBoolean("dark_mode", true);

        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            setTheme(R.style.AppTheme_Dark);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            setTheme(R.style.AppTheme);
        }

        super.onCreate(savedInstanceState);
        com.winlator.cmod.ui.UiStrings.install(this);
        com.winlator.cmod.ui.toast.ForegroundActivityTracker.install(getApplication());
        orientationLocked = sharedPreferences.getBoolean(PREF_ORIENTATION_LOCKED, false);
        orientationMode = sharedPreferences.getString(PREF_ORIENTATION_MODE, ORIENTATION_MODE_AUTO);
        if (!ORIENTATION_MODE_VERTICAL.equals(orientationMode) && !ORIENTATION_MODE_HORIZONTAL.equals(orientationMode)) {
            orientationMode = ORIENTATION_MODE_AUTO;
        }
        if (orientationLocked || !ORIENTATION_MODE_AUTO.equals(orientationMode)) applyOrientationMode();
        applyImmersiveMode();

        if (!sharedPreferences.getBoolean(OnboardingActivity.PREF_ONBOARDING_COMPLETE, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        notificationService = new Intent(this, NotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
            createNotificationChannel();

        boolean isBigPictureModeEnabled = sharedPreferences.getBoolean("enable_big_picture_mode", false);

        if (isBigPictureModeEnabled) {
            Intent intent = new Intent(MainActivity.this, BigPictureActivity.class);
            startActivity(intent);
        }

        setContentView(R.layout.main_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);
        navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);
        navigationView.setBackgroundColor(Color.parseColor("#0B0D12"));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        bottomNavigation = findViewById(R.id.BottomNavigation);
        bottomNavigation.setOnItemSelectedListener(item -> {
            if (syncingBottomNavigation) return true;
            int target;
            if (item.getItemId() == R.id.bottom_nav_library) target = R.id.main_menu_shortcuts;
            else if (item.getItemId() == R.id.bottom_nav_containers) target = R.id.main_menu_containers;
            else if (item.getItemId() == R.id.bottom_nav_controls) target = R.id.main_menu_input_controls;
            else if (item.getItemId() == R.id.bottom_nav_settings) target = R.id.main_menu_settings;
            else return false;
            MenuItem destination = navigationView.getMenu().findItem(target);
            navigationView.setCheckedItem(target);
            return onNavigationItemSelected(destination);
        });
        updateStorageFooter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        mainToolbar = findViewById(R.id.Toolbar);
        setSupportActionBar((androidx.appcompat.widget.Toolbar) mainToolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
        }

        setNavigationViewItemTextColor(navigationView, Color.WHITE);

        File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
        if (!winlatorDir.exists())
            winlatorDir.mkdirs();

        containerManager = new ContainerManager(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setHomeAsUpIndicator(R.drawable.ui_ic_back);
            }
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.main_menu_input_controls));
            navigationView.setCheckedItem(R.id.main_menu_input_controls);
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId;
            if (selectedMenuItemId > 0) {
                menuItemId = selectedMenuItemId;
            } else {
                List<Shortcut> shortcuts = containerManager.loadShortcuts();
                menuItemId = (shortcuts != null && !shortcuts.isEmpty())
                        ? R.id.main_menu_shortcuts
                        : R.id.main_menu_containers;
            }

            if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(false);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);
            selectBottomDestination(menuItemId);

            if (!ImageFsInstaller.installIfNeeded(this, () -> requestAppPermissions())) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                    startForegroundService(notificationService);
            }
        }
    }

    private void updateStorageFooter() {
        TextView storageView = findViewById(R.id.TVStorageUsage);
        ProgressBar storageProgress = findViewById(R.id.PBStorageUsage);
        if (storageView == null || storageProgress == null) return;

        File storageRoot = Environment.getExternalStorageDirectory();
        StatFs statFs = new StatFs(storageRoot.getPath());
        long totalBytes = statFs.getTotalBytes();
        long freeBytes = statFs.getAvailableBytes();
        long usedBytes = Math.max(0, totalBytes - freeBytes);
        int usedPercent = totalBytes > 0 ? Math.min(100, Math.round((usedBytes * 100f) / totalBytes)) : 0;

        storageView.setText(Formatter.formatShortFileSize(this, usedBytes) + " / " + Formatter.formatShortFileSize(this, totalBytes));
        storageProgress.setProgress(usedPercent);
    }

    private void showAllFilesAccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_all_files_access_required))
                .setMessage(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_in_order_to_grant_access_to_additional_storage_devices_such_as_us))
                .setPositiveButton(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_okay), (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.cancel), null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                startForegroundService(notificationService);
        } else if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                requestAppPermissions();
            else
                finish();
        }
    }

    @Override
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
            return;
        }
        if (editInputControls) {
            super.onBackPressed();
            return;
        }
        finish();
    }

    private void requestAppPermissions() {
        boolean hasWritePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasReadPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasManageStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        boolean hasPostNotificationPermission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

        if (!hasWritePermission || !hasReadPermission) {
            String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
            return;
        }

        if (!hasPostNotificationPermission) {
            createNotificationChannel();
            String[] permissions = new String[]{Manifest.permission.POST_NOTIFICATIONS};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE);
        }

        if (!hasManageStoragePermission) {
            showAllFilesAccessDialog();
        }
    }

    public boolean isOrientationLocked() {
        return orientationLocked;
    }

    public boolean isVerticalModeEnabled() {
        return ORIENTATION_MODE_VERTICAL.equals(orientationMode);
    }

    public boolean isHorizontalModeEnabled() {
        return ORIENTATION_MODE_HORIZONTAL.equals(orientationMode);
    }

    public boolean toggleOrientationLock() {
        orientationLocked = !orientationLocked;
        if (orientationLocked) orientationMode = ORIENTATION_MODE_AUTO;
        persistOrientationPreferences();
        applyOrientationMode();
        return orientationLocked;
    }

    public boolean toggleVerticalMode() {
        orientationMode = isVerticalModeEnabled() ? ORIENTATION_MODE_AUTO : ORIENTATION_MODE_VERTICAL;
        orientationLocked = false;
        persistOrientationPreferences();
        applyOrientationMode();
        return isVerticalModeEnabled();
    }

    public boolean toggleHorizontalMode() {
        orientationMode = isHorizontalModeEnabled() ? ORIENTATION_MODE_AUTO : ORIENTATION_MODE_HORIZONTAL;
        orientationLocked = false;
        persistOrientationPreferences();
        applyOrientationMode();
        return isHorizontalModeEnabled();
    }

    private void persistOrientationPreferences() {
        sharedPreferences.edit()
                .putBoolean(PREF_ORIENTATION_LOCKED, orientationLocked)
                .putString(PREF_ORIENTATION_MODE, orientationMode)
                .apply();
    }

    private void applyOrientationMode() {
        switch (orientationMode) {
            case ORIENTATION_MODE_VERTICAL:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case ORIENTATION_MODE_HORIZONTAL:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                setRequestedOrientation(orientationLocked
                        ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                break;
        }
        invalidateOptionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
            if (current instanceof GameDetailFragment
                    || current instanceof ContainerOverviewFragment
                    || current instanceof ContainerDetailFragment
                    || current instanceof ContainerSectionFragment
                    || current instanceof ContainerCreateComposeFragment) {
                onBackPressed();
                return true;
            }
            if (editInputControls) {
                onBackPressed();
                return true;
            }

            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }

    public void toggleDrawer() {
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        setDetailMode(false);
        selectBottomDestination(item.getItemId());
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.main_menu_steam:
                startActivity(new Intent(this, com.winlator.cmod.steam.SteamActivity.class));
                break;
            case R.id.main_menu_shortcuts:
                show(new ShortcutsFragment(), false);
                break;
            case R.id.main_menu_containers:
                show(new ContainersFragment(), false);
                break;
            case R.id.main_menu_input_controls:
                show(InputControlsFragment.newInstance(selectedProfileId), false);
                break;
            case R.id.main_menu_file_manager:
                show(new FileManagerFragment(), false);
                break;
            case R.id.main_menu_settings:
                show(new SettingsFragment(), false);
                break;
            case R.id.main_menu_about:
                showAboutDialog();
                break;
        }
        return true;
    }


    private void selectBottomDestination(int menuItemId) {
        if (bottomNavigation == null) return;
        int bottomId = 0;
        if (menuItemId == R.id.main_menu_shortcuts) bottomId = R.id.bottom_nav_library;
        else if (menuItemId == R.id.main_menu_containers) bottomId = R.id.bottom_nav_containers;
        else if (menuItemId == R.id.main_menu_input_controls) bottomId = R.id.bottom_nav_controls;
        else if (menuItemId == R.id.main_menu_settings) bottomId = R.id.bottom_nav_settings;
        if (bottomId != 0 && bottomNavigation.getSelectedItemId() != bottomId) {
            syncingBottomNavigation = true;
            bottomNavigation.setSelectedItemId(bottomId);
            syncingBottomNavigation = false;
        }
    }

    private boolean isLandscapeOrientation() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    public void setBottomNavigationVisible(boolean visible) {
        if (bottomNavigation == null) return;
        bottomNavigation.setVisibility((visible && !isLandscapeOrientation()) ? View.VISIBLE : View.GONE);
    }

    public void setMainToolbarVisible(boolean visible) {
        if (mainToolbar == null) return;
        mainToolbar.setVisibility((visible && !isLandscapeOrientation()) ? View.VISIBLE : View.GONE);
    }

    public void navigateToMainDestination(int menuItemId) {
        if (navigationView == null) return;
        MenuItem destination = navigationView.getMenu().findItem(menuItemId);
        if (destination == null) return;
        navigationView.setCheckedItem(menuItemId);
        onNavigationItemSelected(destination);
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    public void setDetailMode(boolean detail) {
        setBottomNavigationVisible(!detail);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(detail);
            if (detail) actionBar.setHomeAsUpIndicator(R.drawable.ui_ic_back);
        }
    }

    private void show(Fragment fragment, boolean reverse) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (reverse) {
            fragmentManager.beginTransaction().setReorderingAllowed(true)
                    .setCustomAnimations(R.anim.slide_in_down, R.anim.slide_out_up)
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        } else {
            fragmentManager.beginTransaction().setReorderingAllowed(true)
                    .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        }

        drawerLayout.closeDrawer(GravityCompat.START);
    }

    public void showAboutDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.about_dialog);
        dialog.findViewById(R.id.LLBottomBar).setVisibility(View.GONE);

        if (isDarkMode) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background_dark);
        } else {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background);
        }

        try {
            final PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);

            TextView tvWebpage = dialog.findViewById(R.id.TVWebpage);
            tvWebpage.setText(Html.fromHtml("<a href=\"https://www.winlator.org\">winlator.org</a>", Html.FROM_HTML_MODE_LEGACY));
            tvWebpage.setMovementMethod(LinkMovementMethod.getInstance());

            ((TextView) dialog.findViewById(R.id.TVAppVersion)).setText(getString(R.string.version) + " " + pInfo.versionName);

            String creditsAndThirdPartyAppsHTML = String.join("<br />",
                    "Winlator Ludashi by StevenMX, pipetto-crypto (<a href=\"https://github.com/StevenMXZ/Winlator-Ludashi\">Fork</a>, <a href=\"https://github.com/Pipetto-crypto/winlator\">Fork</a>)",
                    com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_big_picture_mode_music_by),
                    "Dale Melvin Blevens III (Fumer)",
                    "---",
                    "Termux Package(<a href=\"https://github.com/termux/termux-packages\">github.com/termux/termux-package</a>)",
                    "Wine (<a href=\"https://www.winehq.org\">winehq.org</a>)",
                    "Box64 (<a href=\"https://github.com/ptitSeb/box64\">github.com/ptitSeb/box64</a>)",
                    "Mesa (Turnip/Zink/Wrapper) (<a href=\"https://github.com/xMeM/mesa/tree/wrapper\">github.com/xMeM/mesa</a>)",
                    "DXVK (<a href=\"https://github.com/doitsujin/dxvk\">github.com/doitsujin/dxvk</a>)",
                    "VKD3D (<a href=\"https://gitlab.winehq.org/wine/vkd3d\">gitlab.winehq.org/wine/vkd3d</a>)",
                    "D8VK (<a href=\"https://github.com/AlpyneDreams/d8vk\">github.com/AlpyneDreams/d8vk</a>)",
                    "CNC DDraw (<a href=\"https://github.com/FunkyFr3sh/cnc-ddraw\">github.com/FunkyFr3sh/cnc-ddraw</a>)",
                    "dxwrapper (<a href=\"https://github.com/elishacloud/dxwrapper\">github.com/elishacloud/dxwrapper</a>)",
                    "FEX-Emu (<a href=\"https://github.com/FEX-Emu/FEX\">github.com/FEX-Emu/FEX</a>)",
                    "libadrenotools (<a href=\"https://github.com/bylaws/libadrenotools\">github.com/bylaws/libadrenotools</a>)"
            );

            TextView tvCreditsAndThirdPartyApps = dialog.findViewById(R.id.TVCreditsAndThirdPartyApps);
            tvCreditsAndThirdPartyApps.setText(Html.fromHtml(creditsAndThirdPartyAppsHTML, Html.FROM_HTML_MODE_LEGACY));
            tvCreditsAndThirdPartyApps.setMovementMethod(LinkMovementMethod.getInstance());

            String glibcExpVersionForkHTML = String.join("<br />",
                    "longjunyu2's <a href=\"https://github.com/longjunyu2/winlator/tree/use-glibc-instead-of-proot\">(GLIBC Fork)</a>");
            TextView tvGlibcExpVersionFork = dialog.findViewById(R.id.TVGlibcExpVersionFork);
            tvGlibcExpVersionFork.setText(Html.fromHtml(glibcExpVersionForkHTML, Html.FROM_HTML_MODE_LEGACY));
            tvGlibcExpVersionFork.setMovementMethod(LinkMovementMethod.getInstance());
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        dialog.show();
    }

    private void setNavigationViewItemTextColor(NavigationView navigationView, int color) {
        for (int i = 0; i < navigationView.getMenu().size(); i++) {
            MenuItem menuItem = navigationView.getMenu().getItem(i);
            setMenuItemTextColor(menuItem, color);

            if (menuItem.hasSubMenu()) {
                for (int j = 0; j < menuItem.getSubMenu().size(); j++) {
                    MenuItem subMenuItem = menuItem.getSubMenu().getItem(j);
                    setMenuItemTextColor(subMenuItem, color);
                }
            }
        }
    }

    private void setMenuItemTextColor(MenuItem menuItem, int color) {
        SpannableString spanString = new SpannableString(menuItem.getTitle());
        spanString.setSpan(new ForegroundColorSpan(color), 0, spanString.length(), 0);
        menuItem.setTitle(spanString);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            Bitmap bitmap = ImageUtils.getBitmapFromUri(this, data.getData(), 1280);
            if (bitmap == null) return;
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(this);
            ImageUtils.save(bitmap, userWallpaperFile, Bitmap.CompressFormat.PNG, 100);
        }
    }
}
