package com.winlator.cmod;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;
import java.io.File;
import java.util.List;

/* loaded from: classes8.dex */
public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte OPEN_IMAGE_REQUEST_CODE = 5;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    private ContainerManager containerManager;
    private DrawerLayout drawerLayout;
    private boolean isDarkMode;
    private int selectedProfileId;
    private SharedPreferences sharedPreferences;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isBigPictureModeEnabled = sharedPreferences.getBoolean("enable_big_picture_mode", false);
        if (isBigPictureModeEnabled) {
            startActivity(new Intent(this, (Class<?>) BigPictureActivity.class));
        }
        SharedPreferences sharedPreferences2 = PreferenceManager.getDefaultSharedPreferences(this);
        this.isDarkMode = sharedPreferences2.getBoolean("dark_mode", false);
        if (this.isDarkMode) {
            setTheme(2131820553);
        } else {
            setTheme(com.ludashi.benchmark.R.style.AppTheme);
        }
        setContentView(com.ludashi.benchmark.R.layout.main_activity);
        this.drawerLayout = (DrawerLayout) findViewById(com.ludashi.benchmark.R.id.DrawerLayout);
        NavigationView navigationView = (NavigationView) findViewById(com.ludashi.benchmark.R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);
        setSupportActionBar((Toolbar) findViewById(com.ludashi.benchmark.R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(com.ludashi.benchmark.R.drawable.icon_action_bar_menu);
        }
        int textColor = this.isDarkMode ? -1 : ViewCompat.MEASURED_STATE_MASK;
        setNavigationViewItemTextColor(navigationView, textColor);
        File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
        if (!winlatorDir.exists()) {
            winlatorDir.mkdirs();
        }
        this.containerManager = new ContainerManager(this);
        Intent intent = getIntent();
        this.editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (this.editInputControls) {
            this.selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            actionBar.setHomeAsUpIndicator(com.ludashi.benchmark.R.drawable.icon_action_bar_back);
            onNavigationItemSelected(navigationView.getMenu().findItem(com.ludashi.benchmark.R.id.main_menu_input_controls));
            navigationView.setCheckedItem(com.ludashi.benchmark.R.id.main_menu_input_controls);
            return;
        }
        int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
        int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : com.ludashi.benchmark.R.id.main_menu_containers;
        actionBar.setHomeAsUpIndicator(com.ludashi.benchmark.R.drawable.icon_action_bar_menu);
        onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
        navigationView.setCheckedItem(menuItemId);
        if (!requestAppPermissions()) {
            ImageFsInstaller.installIfNeeded(this);
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            showAllFilesAccessDialog();
        }
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        }
    }

    private void showAllFilesAccessDialog() {
        new AlertDialog.Builder(this).setTitle("All Files Access Required").setMessage("In order to grant access to additional storage devices such as USB storage device, the All Files Access permission must be granted. Press Okay to grant All Files Access in your Android Settings.").setPositiveButton("Okay", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.MainActivity$$ExternalSyntheticLambda0
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.lambda$showAllFilesAccessDialog$0(dialogInterface, i);
            }
        }).setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showAllFilesAccessDialog$0(DialogInterface dialog, int which) {
        Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, android.app.Activity
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == 0) {
                ImageFsInstaller.installIfNeeded(this);
            } else {
                finish();
            }
        }
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        List<Fragment> fragments = fragmentManager.getFragments();
        for (Fragment fragment : fragments) {
            if ((fragment instanceof ContainersFragment) && fragment.isVisible()) {
                finish();
                return;
            }
        }
        if (!this.editInputControls) {
            show(new ContainersFragment(), true);
        } else {
            super.onBackPressed();
        }
    }

    private boolean requestAppPermissions() {
        boolean hasWritePermission = ContextCompat.checkSelfPermission(this, "android.permission.WRITE_EXTERNAL_STORAGE") == 0;
        boolean hasReadPermission = ContextCompat.checkSelfPermission(this, "android.permission.READ_EXTERNAL_STORAGE") == 0;
        boolean hasManageStoragePermission = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        if (hasWritePermission && hasReadPermission && hasManageStoragePermission) {
            return false;
        }
        if (!hasWritePermission || !hasReadPermission) {
            String[] permissions = {"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"};
            ActivityCompat.requestPermissions(this, permissions, 1);
        }
        return true;
    }

    @Override // android.app.Activity
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == 16908332) {
            if (this.editInputControls) {
                onBackPressed();
                return true;
            }
            if (this.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                this.drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                this.drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    public void toggleDrawer() {
        if (this.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            this.drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            this.drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:14:0x005d, code lost:
    
        return true;
     */
    @Override // com.google.android.material.navigation.NavigationView.OnNavigationItemSelectedListener
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean onNavigationItemSelected(android.view.MenuItem r6) {
        /*
            r5 = this;
            androidx.fragment.app.FragmentManager r0 = r5.getSupportFragmentManager()
            int r1 = r0.getBackStackEntryCount()
            r2 = 1
            if (r1 <= 0) goto Lf
            r1 = 0
            r0.popBackStack(r1, r2)
        Lf:
            int r1 = r6.getItemId()
            r3 = 0
            switch(r1) {
                case 2131296876: goto L59;
                case 2131296877: goto L50;
                case 2131296878: goto L47;
                case 2131296879: goto L3e;
                case 2131296880: goto L35;
                case 2131296881: goto L2a;
                case 2131296882: goto L21;
                case 2131296883: goto L18;
                default: goto L17;
            }
        L17:
            goto L5d
        L18:
            com.winlator.cmod.ShortcutsFragment r1 = new com.winlator.cmod.ShortcutsFragment
            r1.<init>()
            r5.show(r1, r3)
            goto L5d
        L21:
            com.winlator.cmod.SettingsFragment r1 = new com.winlator.cmod.SettingsFragment
            r1.<init>()
            r5.show(r1, r3)
            goto L5d
        L2a:
            com.winlator.cmod.InputControlsFragment r1 = new com.winlator.cmod.InputControlsFragment
            int r4 = r5.selectedProfileId
            r1.<init>(r4)
            r5.show(r1, r3)
            goto L5d
        L35:
            com.winlator.cmod.FileManagerFragment r1 = new com.winlator.cmod.FileManagerFragment
            r1.<init>()
            r5.show(r1, r3)
            goto L5d
        L3e:
            com.winlator.cmod.ContentsFragment r1 = new com.winlator.cmod.ContentsFragment
            r1.<init>()
            r5.show(r1, r3)
            goto L5d
        L47:
            com.winlator.cmod.ContainersFragment r1 = new com.winlator.cmod.ContainersFragment
            r1.<init>()
            r5.show(r1, r3)
            goto L5d
        L50:
            com.winlator.cmod.AdrenotoolsFragment r1 = new com.winlator.cmod.AdrenotoolsFragment
            r1.<init>()
            r5.show(r1, r3)
            goto L5d
        L59:
            r5.showAboutDialog()
        L5d:
            return r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.MainActivity.onNavigationItemSelected(android.view.MenuItem):boolean");
    }

    private void show(Fragment fragment, boolean reverse) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (reverse) {
            fragmentManager.beginTransaction().setCustomAnimations(com.ludashi.benchmark.R.anim.slide_in_down, com.ludashi.benchmark.R.anim.slide_out_up).replace(com.ludashi.benchmark.R.id.FLFragmentContainer, fragment).commit();
        } else {
            fragmentManager.beginTransaction().setCustomAnimations(com.ludashi.benchmark.R.anim.slide_in_up, com.ludashi.benchmark.R.anim.slide_out_down).replace(com.ludashi.benchmark.R.id.FLFragmentContainer, fragment).commit();
        }
        this.drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void showAboutDialog() {
        ContentDialog dialog = new ContentDialog(this, com.ludashi.benchmark.R.layout.about_dialog);
        dialog.findViewById(com.ludashi.benchmark.R.id.LLBottomBar).setVisibility(8);
        if (this.isDarkMode) {
            dialog.getWindow().setBackgroundDrawableResource(com.ludashi.benchmark.R.drawable.content_dialog_background_dark);
        } else {
            dialog.getWindow().setBackgroundDrawableResource(com.ludashi.benchmark.R.drawable.content_dialog_background);
        }
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView tvWebpage = (TextView) dialog.findViewById(com.ludashi.benchmark.R.id.TVWebpage);
            tvWebpage.setText(Html.fromHtml("<a href=\"https://www.winlator.org\">winlator.org</a>", 0));
            tvWebpage.setMovementMethod(LinkMovementMethod.getInstance());
            ((TextView) dialog.findViewById(com.ludashi.benchmark.R.id.TVAppVersion)).setText(getString(com.ludashi.benchmark.R.string.version) + " " + pInfo.versionName);
            String creditsAndThirdPartyAppsHTML = String.join("<br />", "Winlator Cmod by coffincolors, me (<a href=\"https://github.com/coffincolors/winlator\">Fork</a>, <a href=\"https://github.com/Pipetto-crypto/winlator\">Fork</a>)", "Big Picture Mode Music by", "Dale Melvin Blevens III (Fumer)", "---", "Termux Package(<a href=\"https://github.com/termux/termux-packages\">github.com/termux/termux-package</a>)", "Wine (<a href=\"https://www.winehq.org\">winehq.org</a>)", "Box64 (<a href=\"https://github.com/ptitSeb/box64\">github.com/ptitSeb/box64</a>)", "Mesa (Turnip/Zink/Wrapper) (<a href=\"https://github.com/xMeM/mesa/tree/wrapper\">github.com/xMeM/mesa</a>)", "DXVK (<a href=\"https://github.com/doitsujin/dxvk\">github.com/doitsujin/dxvk</a>)", "VKD3D (<a href=\"https://gitlab.winehq.org/wine/vkd3d\">gitlab.winehq.org/wine/vkd3d</a>)", "D8VK (<a href=\"https://github.com/AlpyneDreams/d8vk\">github.com/AlpyneDreams/d8vk</a>)", "CNC DDraw (<a href=\"https://github.com/FunkyFr3sh/cnc-ddraw\">github.com/FunkyFr3sh/cnc-ddraw</a>)", "dxwrapper (<a href=\"https://github.com/elishacloud/dxwrapper\">github.com/elishacloud/dxwrapper</a>)", "FEX-Emu (<a href=\"https://github.com/FEX-Emu/FEX\">github.com/FEX-Emu/FEX</a>)", "libadrenotools (<a href=\"https://github.com/bylaws/libadrenotools\">github.com/bylaws/libadrenotools</a>)");
            TextView tvCreditsAndThirdPartyApps = (TextView) dialog.findViewById(com.ludashi.benchmark.R.id.TVCreditsAndThirdPartyApps);
            tvCreditsAndThirdPartyApps.setText(Html.fromHtml(creditsAndThirdPartyAppsHTML, 0));
            tvCreditsAndThirdPartyApps.setMovementMethod(LinkMovementMethod.getInstance());
            String glibcExpVersionForkHTML = String.join("<br />", "longjunyu2's <a href=\"https://github.com/longjunyu2/winlator/tree/use-glibc-instead-of-proot\">(GLIBC Fork)</a>");
            TextView tvGlibcExpVersionFork = (TextView) dialog.findViewById(com.ludashi.benchmark.R.id.TVGlibcExpVersionFork);
            tvGlibcExpVersionFork.setText(Html.fromHtml(glibcExpVersionForkHTML, 0));
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

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, android.app.Activity
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Bitmap bitmap;
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 5 && resultCode == -1 && (bitmap = ImageUtils.getBitmapFromUri(this, data.getData(), 1280)) != null) {
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(this);
            ImageUtils.save(bitmap, userWallpaperFile, Bitmap.CompressFormat.PNG, 100);
        }
    }
}
