package com.winlator.cmod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.winlator.cmod.bigpicture.BigPictureAdapter;
import com.winlator.cmod.bigpicture.TiledBackgroundView;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridDBApi;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponse;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponseDeserializer;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridSearchResponse;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import kotlinx.coroutines.DebugKt;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/* loaded from: classes8.dex */
public class BigPictureActivity extends AppCompatActivity {
    private static String API_KEY = "0324c52513634547a7b32d6d323635d0";
    private static final String BASE_URL = "https://www.steamgriddb.com/api/v2/";
    private static final int REQUEST_CODE_SELECT_MP3 = 1070;
    private static final int REQUEST_CODE_SELECT_PNG_FOLDER = 1090;
    private static final int REQUEST_CODE_SELECT_WALLPAPER = 1080;
    private static final int REQUEST_CODE_UPLOAD_CUSTOM_COVER = 1069;
    public static final String SEEK_BAR_PROGRESS_KEY = "frame_duration_seekbar";
    private static final String WALLPAPER_DISPLAY_PREF_KEY = "wallpaper_display_mode";
    private static final String WALLPAPER_PREF_KEY = "custom_wallpaper_path";
    private BigPictureAdapter adapter;
    private TextView audioDriverView;
    private TextView box64PresetView;
    private ImageView coverArtView;
    private Shortcut currentShortcut;
    private TextView dxWrapperConfigView;
    private TextView dxWrapperView;
    private TextView emptyStateTextView;
    private TextView gameTitleView;
    private TextView graphicsDriverVersionView;
    private TextView graphicsDriverView;
    private int lastFocusedItemIndex = -1;
    private ContainerManager manager;
    private MediaPlayer mediaPlayer;
    private ImageButton playButton;
    private TextView playCountView;
    private TextView playtimeView;
    private RecyclerView recyclerView;
    private Uri selectedMp3Uri;
    private TextView uploadText;
    private WebView webView;

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onStart() {
        super.onStart();
        TiledBackgroundView backgroundView = (TiledBackgroundView) findViewById(com.ludashi.benchmark.R.id.parallaxBackgroundView);
        backgroundView.startAnimation();
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onStop() {
        super.onStop();
        TiledBackgroundView backgroundView = (TiledBackgroundView) findViewById(com.ludashi.benchmark.R.id.parallaxBackgroundView);
        backgroundView.stopAnimation();
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue
    java.lang.NullPointerException: Cannot invoke "java.util.List.iterator()" because the return value of "jadx.core.dex.visitors.regions.SwitchOverStringVisitor$SwitchData.getNewCases()" is null
    	at jadx.core.dex.visitors.regions.SwitchOverStringVisitor.restoreSwitchOverString(SwitchOverStringVisitor.java:109)
    	at jadx.core.dex.visitors.regions.SwitchOverStringVisitor.visitRegion(SwitchOverStringVisitor.java:66)
    	at jadx.core.dex.visitors.regions.DepthRegionTraversal.traverseIterativeStepInternal(DepthRegionTraversal.java:77)
    	at jadx.core.dex.visitors.regions.DepthRegionTraversal.traverseIterativeStepInternal(DepthRegionTraversal.java:82)
    	at jadx.core.dex.visitors.regions.DepthRegionTraversal.traverseIterative(DepthRegionTraversal.java:31)
    	at jadx.core.dex.visitors.regions.SwitchOverStringVisitor.visit(SwitchOverStringVisitor.java:60)
     */
    /* JADX WARN: Removed duplicated region for block: B:13:0x0161  */
    /* JADX WARN: Removed duplicated region for block: B:16:0x01a7  */
    /* JADX WARN: Removed duplicated region for block: B:27:0x01ee  */
    /* JADX WARN: Removed duplicated region for block: B:31:0x0210  */
    /* JADX WARN: Removed duplicated region for block: B:41:0x0305  */
    /* JADX WARN: Removed duplicated region for block: B:44:0x0344  */
    /* JADX WARN: Removed duplicated region for block: B:53:0x03cc  */
    /* JADX WARN: Removed duplicated region for block: B:56:0x03ec  */
    /* JADX WARN: Removed duplicated region for block: B:59:0x0492  */
    /* JADX WARN: Removed duplicated region for block: B:63:0x049e  */
    /* JADX WARN: Removed duplicated region for block: B:64:0x03fa  */
    /* JADX WARN: Removed duplicated region for block: B:77:0x03b0  */
    /* JADX WARN: Removed duplicated region for block: B:78:0x030c  */
    /* JADX WARN: Removed duplicated region for block: B:80:0x021e  */
    /* JADX WARN: Removed duplicated region for block: B:81:0x022c  */
    /* JADX WARN: Removed duplicated region for block: B:82:0x023a  */
    /* JADX WARN: Removed duplicated region for block: B:83:0x01f8  */
    /* JADX WARN: Removed duplicated region for block: B:86:0x0202  */
    /* JADX WARN: Removed duplicated region for block: B:89:0x020c  */
    /* JADX WARN: Removed duplicated region for block: B:90:0x01d1  */
    /* JADX WARN: Removed duplicated region for block: B:91:0x0165  */
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    protected void onCreate(android.os.Bundle r42) {
        /*
            Method dump skipped, instructions count: 1290
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.BigPictureActivity.onCreate(android.os.Bundle):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$0(View v) {
        selectPngFolder();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$1(SharedPreferences preferences, Button selectWallpaperButton, TiledBackgroundView backgroundView, RadioGroup group, int checkedId) {
        SharedPreferences.Editor editor = preferences.edit();
        if (checkedId == com.ludashi.benchmark.R.id.rbCustomWallpaper) {
            selectWallpaperButton.setVisibility(0);
            backgroundView.setVisibility(8);
            editor.putString("selected_animation", "custom_wallpaper");
        } else {
            selectWallpaperButton.setVisibility(8);
            backgroundView.setVisibility(0);
            if (checkedId == com.ludashi.benchmark.R.id.rbGearAnimation) {
                backgroundView.setAnimation("ab_gear");
                editor.putString("selected_animation", "ab_gear");
            } else if (checkedId == com.ludashi.benchmark.R.id.rbQuiltAnimation) {
                backgroundView.setAnimation("ab_quilt");
                editor.putString("selected_animation", "ab_quilt");
            } else if (checkedId == com.ludashi.benchmark.R.id.rbDefaultAnimation) {
                backgroundView.setAnimation("ab");
                editor.putString("selected_animation", "ab");
            } else if (checkedId == com.ludashi.benchmark.R.id.rbNoAnimation) {
                backgroundView.stopAnimation();
                backgroundView.setVisibility(8);
                editor.putString("selected_animation", Container.DEFAULT_DDRAWRAPPER);
            } else if (checkedId == com.ludashi.benchmark.R.id.rbFolderAnimation) {
                editor.putString("selected_animation", "folder");
                selectPngFolder();
            }
        }
        editor.apply();
        backgroundView.startAnimation();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$2(View v) {
        Intent intent = new Intent("android.intent.action.GET_CONTENT");
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_SELECT_WALLPAPER);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$3(SharedPreferences preferences, RadioGroup group, int checkedId) {
        String mode;
        switch (checkedId) {
            case com.ludashi.benchmark.R.id.rbParallaxFast /* 2131296994 */:
                mode = "fast";
                break;
            case com.ludashi.benchmark.R.id.rbParallaxOff /* 2131296995 */:
                mode = DebugKt.DEBUG_PROPERTY_VALUE_OFF;
                break;
            case com.ludashi.benchmark.R.id.rbParallaxSlow /* 2131296996 */:
                mode = "slow";
                break;
            default:
                mode = "default";
                break;
        }
        preferences.edit().putString("parallax_mode", mode).apply();
        applyParallaxMode(mode);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$4(SharedPreferences preferences, Button disableBgMusicButton, View v) {
        boolean currentBgMusicState = preferences.getBoolean("bg_music_enabled", true);
        boolean newBgMusicState = !currentBgMusicState;
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("bg_music_enabled", newBgMusicState);
        editor.apply();
        updateBgMusicButtonText(disableBgMusicButton, newBgMusicState);
        if (newBgMusicState) {
            onResume();
        } else {
            stopBackgroundMusic();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$5(View v) {
        Intent intent = new Intent("android.intent.action.GET_CONTENT");
        intent.setType("audio/mpeg");
        intent.addCategory("android.intent.category.OPENABLE");
        startActivityForResult(intent, REQUEST_CODE_SELECT_MP3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$6(SharedPreferences preferences, RadioButton mp3RadioButton, View v) {
        stopBackgroundMusic();
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove("selected_mp3_path");
        editor.putString("music_source", "mp3");
        editor.apply();
        mp3RadioButton.setChecked(true);
        playDefaultMp3FromAssets();
        Toast.makeText(this, "MP3 reset to default", 0).show();
    }

    static /* synthetic */ void lambda$onCreate$7(SharedPreferences preferences, RadioGroup group, int checkedId) {
        SharedPreferences.Editor editor = preferences.edit();
        if (checkedId == com.ludashi.benchmark.R.id.youtubeRadioButton) {
            editor.putString("music_source", "youtube");
        } else if (checkedId == com.ludashi.benchmark.R.id.mp3RadioButton) {
            editor.putString("music_source", "mp3");
        }
        editor.apply();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$8(EditText youtubeUrlInput, SharedPreferences preferences, View v) {
        String userUrl = youtubeUrlInput.getText().toString();
        if (userUrl != null && !userUrl.isEmpty()) {
            String videoId = extractYouTubeId(userUrl);
            if (videoId != null) {
                loadYouTubeVideo(videoId);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("saved_youtube_url", userUrl);
                editor.apply();
                return;
            }
            youtubeUrlInput.setError("Invalid YouTube URL");
            return;
        }
        loadYouTubeVideo("yNwKYgM6SkM");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$9(View v) {
        if (findViewById(com.ludashi.benchmark.R.id.settingsLayout).getVisibility() == 0) {
            hideSettingsView();
        } else {
            showSettingsView();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$10(View v) {
        if (findViewById(com.ludashi.benchmark.R.id.settingsLayout).getVisibility() == 0) {
            hideSettingsView();
        } else {
            showSettingsView();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$11(View v) {
        if (this.currentShortcut != null) {
            runFromShortcut(this.currentShortcut);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$12(View v) {
        if (this.currentShortcut != null) {
            if (this.currentShortcut.getCustomCoverArtPath() != null) {
                showCoverArtOptionsDialog();
            } else {
                promptForCustomCoverArtUpload();
            }
        }
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public void onBackPressed() {
        if (findViewById(com.ludashi.benchmark.R.id.settingsLayout).getVisibility() == 0) {
            hideSettingsView();
        } else {
            super.onBackPressed();
        }
    }

    private void updateBgMusicButtonText(Button button, boolean isEnabled) {
        if (isEnabled) {
            button.setText("Disable BG Music");
        } else {
            button.setText("Enable BG Music");
        }
    }

    private String extractYouTubeId(String youtubeUrl) {
        if (youtubeUrl.matches("^(https?://)?(www\\.)?(youtube\\.com|youtu\\.?be)/.+$")) {
            String[] splitUrl = youtubeUrl.split("v=");
            if (splitUrl.length > 1) {
                return splitUrl[1].split("&")[0];
            }
            if (youtubeUrl.contains("youtu.be/")) {
                return youtubeUrl.substring(youtubeUrl.lastIndexOf("/") + 1);
            }
            return null;
        }
        return null;
    }

    private void loadYouTubeVideo(String videoId) {
        String html = "<html><body><iframe id=\"player\" type=\"text/html\" width=\"100%\" height=\"100%\"src=\"https://www.youtube.com/embed/" + videoId + "?enablejsapi=1\"frameborder=\"0\" allowfullscreen></iframe></body></html>";
        this.webView.setWebViewClient(new WebViewClient() { // from class: com.winlator.cmod.BigPictureActivity.5
            @Override // android.webkit.WebViewClient
            public void onPageFinished(WebView view, String url) {
                BigPictureActivity.this.simulateTouchOnWebView(BigPictureActivity.this.webView);
                BigPictureActivity.this.webView.setVisibility(4);
            }
        });
        this.webView.loadData(html, "text/html", "UTF-8");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void simulateTouchOnWebView(final WebView webView) {
        new Handler().postDelayed(new Runnable() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                BigPictureActivity.lambda$simulateTouchOnWebView$13(webView);
            }
        }, 1000L);
    }

    static /* synthetic */ void lambda$simulateTouchOnWebView$13(WebView webView) {
        int webViewWidth = webView.getWidth();
        int webViewHeight = webView.getHeight();
        float x = webViewWidth / 2.0f;
        float y = webViewHeight / 2.0f;
        long downTime = System.currentTimeMillis();
        long eventTime = System.currentTimeMillis() + 100;
        MotionEvent touchDown = MotionEvent.obtain(downTime, eventTime, 0, x, y, 0);
        MotionEvent touchUp = MotionEvent.obtain(downTime, eventTime + 100, 1, x, y, 0);
        webView.dispatchTouchEvent(touchDown);
        webView.dispatchTouchEvent(touchUp);
        touchDown.recycle();
        touchUp.recycle();
    }

    private void showSettingsView() {
        final LinearLayout mainLayout = (LinearLayout) findViewById(com.ludashi.benchmark.R.id.mainLayout);
        final LinearLayout settingsLayout = (LinearLayout) findViewById(com.ludashi.benchmark.R.id.settingsLayout);
        settingsLayout.setVisibility(0);
        settingsLayout.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() { // from class: com.winlator.cmod.BigPictureActivity.6
            @Override // android.view.ViewTreeObserver.OnPreDrawListener
            public boolean onPreDraw() {
                settingsLayout.getViewTreeObserver().removeOnPreDrawListener(this);
                ObjectAnimator mainSlideOut = ObjectAnimator.ofFloat(mainLayout, "translationX", 0.0f, -mainLayout.getWidth());
                mainSlideOut.setInterpolator(new AccelerateDecelerateInterpolator());
                mainSlideOut.setDuration(500L);
                mainSlideOut.start();
                ObjectAnimator settingsSlideIn = ObjectAnimator.ofFloat(settingsLayout, "translationX", settingsLayout.getWidth(), 0.0f);
                settingsSlideIn.setInterpolator(new AccelerateDecelerateInterpolator());
                settingsSlideIn.setDuration(500L);
                settingsSlideIn.start();
                return true;
            }
        });
    }

    private void hideSettingsView() {
        LinearLayout mainLayout = (LinearLayout) findViewById(com.ludashi.benchmark.R.id.mainLayout);
        final LinearLayout settingsLayout = (LinearLayout) findViewById(com.ludashi.benchmark.R.id.settingsLayout);
        ObjectAnimator mainSlideIn = ObjectAnimator.ofFloat(mainLayout, "translationX", -mainLayout.getWidth(), 0.0f);
        mainSlideIn.setInterpolator(new AccelerateDecelerateInterpolator());
        mainSlideIn.setDuration(500L);
        mainSlideIn.start();
        ObjectAnimator settingsSlideOut = ObjectAnimator.ofFloat(settingsLayout, "translationX", 0.0f, settingsLayout.getWidth());
        settingsSlideOut.setInterpolator(new AccelerateDecelerateInterpolator());
        settingsSlideOut.setDuration(500L);
        settingsSlideOut.start();
        settingsSlideOut.addListener(new AnimatorListenerAdapter() { // from class: com.winlator.cmod.BigPictureActivity.7
            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationEnd(Animator animation) {
                settingsLayout.setVisibility(8);
            }
        });
    }

    private void showCoverArtOptionsDialog() {
        new AlertDialog.Builder(this).setTitle("Cover Art Options").setItems(new CharSequence[]{"Remove Custom Cover Art", "Upload New Cover Art"}, new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda12
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                BigPictureActivity.this.lambda$showCoverArtOptionsDialog$14(dialogInterface, i);
            }
        }).setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showCoverArtOptionsDialog$14(DialogInterface dialog, int which) {
        switch (which) {
            case 0:
                removeCustomCoverArt();
                break;
            case 1:
                promptForCustomCoverArtUpload();
                break;
        }
    }

    private void removeCustomCoverArt() {
        if (this.currentShortcut != null) {
            Log.d("BigPictureActivity", "Removing cover art for shortcut: " + this.currentShortcut.name);
            Log.d("BigPictureActivity", "Current custom cover art path: " + this.currentShortcut.getCustomCoverArtPath());
            this.currentShortcut.removeCustomCoverArt();
            File cachedFile = new File(getCacheDir(), "coverArtCache/" + this.currentShortcut.name + ".png");
            if (cachedFile.exists() && cachedFile.delete()) {
                Log.d("BigPictureActivity", "Cached cover art deleted successfully.");
            } else {
                Log.e("BigPictureActivity", "Failed to delete cached cover art or it doesn't exist.");
            }
            this.coverArtView.setImageResource(com.ludashi.benchmark.R.drawable.icon_action_bar_import);
            this.coverArtView.setBackgroundColor(Color.parseColor("#99000000"));
            Log.d("BigPictureActivity", "Custom cover art removed and data saved.");
            loadShortcutData(this.currentShortcut);
            Log.d("BigPictureActivity", "Shortcut data reloaded after removal.");
        }
    }

    private void promptForCustomCoverArtUpload() {
        Intent intent = new Intent("android.intent.action.GET_CONTENT");
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_UPLOAD_CUSTOM_COVER);
    }

    private void enableImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(5894);
    }

    private Shortcut getSelectedShortcut() {
        int position = getCenterItemPosition();
        if (position != -1) {
            return this.adapter.getItem(position);
        }
        return null;
    }

    private int getCenterItemPosition() {
        View itemView;
        LinearLayoutManager layoutManager = (LinearLayoutManager) this.recyclerView.getLayoutManager();
        int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();
        int lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition();
        int centerPosition = -1;
        float closestToCenter = Float.MAX_VALUE;
        int recyclerViewCenter = this.recyclerView.getWidth() / 2;
        for (int i = firstVisibleItemPosition; i <= lastVisibleItemPosition; i++) {
            if (i >= 0 && (itemView = layoutManager.findViewByPosition(i)) != null) {
                int itemCenter = (itemView.getLeft() + itemView.getRight()) / 2;
                float distanceFromCenter = Math.abs(recyclerViewCenter - itemCenter);
                if (distanceFromCenter < closestToCenter) {
                    closestToCenter = distanceFromCenter;
                    centerPosition = i;
                }
            }
        }
        return centerPosition;
    }

    private void loadShortcutsList() {
        List<Shortcut> shortcuts = this.manager.loadShortcuts();
        this.emptyStateTextView = (TextView) findViewById(com.ludashi.benchmark.R.id.TVEmptyState);
        if (shortcuts.isEmpty()) {
            this.recyclerView.setVisibility(8);
            this.playButton.setVisibility(8);
            this.emptyStateTextView.setVisibility(0);
        } else {
            this.recyclerView.setVisibility(0);
            this.emptyStateTextView.setVisibility(8);
            this.adapter = new BigPictureAdapter(shortcuts, this.recyclerView);
            this.recyclerView.setLayoutManager(new LinearLayoutManager(this, 0, false));
            this.recyclerView.setAdapter(this.adapter);
            loadShortcutData(shortcuts.get(0));
        }
    }

    public void loadShortcutData(Shortcut shortcut) {
        this.currentShortcut = shortcut;
        Log.d("BigPictureActivity", "Loaded cover art path: " + shortcut.getCustomCoverArtPath());
        this.gameTitleView.setText(shortcut.name);
        SharedPreferences playtimePrefs = getSharedPreferences("playtime_stats", 0);
        long totalPlaytime = playtimePrefs.getLong(shortcut.name + "_playtime", 0L);
        int playCount = playtimePrefs.getInt(shortcut.name + "_play_count", 0);
        this.playCountView.setText("Times Played: " + playCount);
        this.playtimeView.setText("Playtime: " + formatPlaytime(totalPlaytime));
        Container container = this.manager.getContainerForShortcut(shortcut);
        String graphicsDriver = shortcut.getExtra("graphicsDriver");
        setTextOrPlaceholder(this.graphicsDriverView, graphicsDriver, container.getGraphicsDriver());
        setTextOrPlaceholder(this.graphicsDriverVersionView, shortcut.getExtra("graphicsDroverConfig"), container.getGraphicsDriverConfig());
        setTextOrPlaceholder(this.dxWrapperView, shortcut.getExtra("dxwrapper"), container.getDXWrapper());
        setTextOrPlaceholder(this.dxWrapperConfigView, shortcut.getExtra("dxwrapperConfig"), container.getDXWrapperConfig());
        setTextOrPlaceholder(this.audioDriverView, shortcut.getExtra("audioDriver"), container.getAudioDriver());
        setTextOrPlaceholder(this.box64PresetView, shortcut.getExtra("box64Preset"), container.getBox64Preset());
        Bitmap coverArt = null;
        if (shortcut.getCustomCoverArtPath() != null && !shortcut.getCustomCoverArtPath().isEmpty()) {
            coverArt = BitmapFactory.decodeFile(shortcut.getCustomCoverArtPath());
        }
        if (coverArt == null) {
            coverArt = loadCachedCoverArt(shortcut.name);
        }
        if (coverArt != null) {
            this.coverArtView.setImageBitmap(coverArt);
        } else {
            this.coverArtView.setImageResource(com.ludashi.benchmark.R.drawable.cover_art_placeholder);
            fetchCoverArt(shortcut);
        }
        this.coverArtView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda6
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                BigPictureActivity.this.lambda$loadShortcutData$15(view);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$loadShortcutData$15(View v) {
        if (this.currentShortcut.getCustomCoverArtPath() != null) {
            showCoverArtOptionsDialog();
        } else {
            promptForCustomCoverArtUpload();
        }
    }

    private void runFromShortcut(Shortcut shortcut) {
        Intent intent = new Intent(this, (Class<?>) XServerDisplayActivity.class);
        intent.putExtra("container_id", shortcut.container.id);
        intent.putExtra("shortcut_path", shortcut.file.getPath());
        intent.putExtra("shortcut_name", shortcut.name);
        String disableXinputValue = shortcut.getExtra("disableXinput", "0");
        intent.putExtra("disableXinput", disableXinputValue);
        startActivity(intent);
    }

    private void setTextOrPlaceholder(TextView textView, String shortcutValue, String containerValue) {
        if (!shortcutValue.isEmpty()) {
            textView.setText(shortcutValue);
        } else if (!containerValue.isEmpty()) {
            textView.setText(containerValue);
        } else {
            textView.setText("Not Set");
        }
    }

    private void setTextFromContainer(TextView textView, String label, String shortcutValue, String containerValue) {
        if (!shortcutValue.isEmpty()) {
            textView.setText(label + shortcutValue);
        } else if (!containerValue.isEmpty()) {
            textView.setText(label + containerValue);
        } else {
            textView.setText(label + "Not Set");
        }
    }

    private void fetchCoverArt(final Shortcut shortcut) {
        Retrofit retrofit = new Retrofit.Builder().baseUrl(BASE_URL).client(new OkHttpClient()).addConverterFactory(GsonConverterFactory.create()).build();
        SteamGridDBApi api = (SteamGridDBApi) retrofit.create(SteamGridDBApi.class);
        Call<SteamGridSearchResponse> call = api.searchGame("Bearer " + API_KEY, shortcut.name);
        call.enqueue(new Callback<SteamGridSearchResponse>() { // from class: com.winlator.cmod.BigPictureActivity.8
            @Override // retrofit2.Callback
            public void onResponse(Call<SteamGridSearchResponse> call2, Response<SteamGridSearchResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<SteamGridSearchResponse.GameData> gameData = response.body().data;
                    if (gameData != null && !gameData.isEmpty()) {
                        BigPictureActivity.this.fetchGridsForGame(gameData.get(0).id, shortcut);
                        return;
                    } else {
                        BigPictureActivity.this.showCustomCoverArtUploadOption(shortcut);
                        return;
                    }
                }
                BigPictureActivity.this.showCustomCoverArtUploadOption(shortcut);
            }

            @Override // retrofit2.Callback
            public void onFailure(Call<SteamGridSearchResponse> call2, Throwable t) {
                Log.e("SteamGridDB", "Failed to fetch game ID", t);
                BigPictureActivity.this.showCustomCoverArtUploadOption(shortcut);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showCustomCoverArtUploadOption(final Shortcut shortcut) {
        runOnUiThread(new Runnable() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                BigPictureActivity.this.lambda$showCustomCoverArtUploadOption$17(shortcut);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showCustomCoverArtUploadOption$17(Shortcut shortcut) {
        ViewGroup parent;
        this.coverArtView.setImageResource(android.R.color.transparent);
        this.coverArtView.setBackgroundColor(Color.parseColor("#99000000"));
        this.coverArtView.setImageResource(com.ludashi.benchmark.R.drawable.cover_art_placeholder);
        this.coverArtView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda8
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                BigPictureActivity.this.lambda$showCustomCoverArtUploadOption$16(view);
            }
        });
        if (this.uploadText != null && (parent = (ViewGroup) this.uploadText.getParent()) != null) {
            parent.removeView(this.uploadText);
        }
        this.uploadText = new TextView(this);
        this.uploadText.setText("No suitable cover art found for " + shortcut.name + ". Click the image to upload custom cover art or rename the Shortcut to something SteamGrid can recognize.");
        this.uploadText.setTextColor(-1);
        this.uploadText.setTextSize(18.0f);
        this.uploadText.setPadding(20, 20, 20, 20);
        this.uploadText.setGravity(17);
        this.uploadText.setBackgroundColor(Color.parseColor("#99000000"));
        ((ViewGroup) this.coverArtView.getParent()).addView(this.uploadText);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showCustomCoverArtUploadOption$16(View v) {
        promptForCustomCoverArtUpload();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void fetchGridsForGame(int gameId, final Shortcut shortcut) {
        Gson gson = new GsonBuilder().registerTypeAdapter(SteamGridGridsResponse.class, new SteamGridGridsResponseDeserializer()).setPrettyPrinting().create();
        Retrofit retrofit = new Retrofit.Builder().baseUrl(BASE_URL).client(new OkHttpClient()).addConverterFactory(GsonConverterFactory.create(gson)).build();
        SteamGridDBApi api = (SteamGridDBApi) retrofit.create(SteamGridDBApi.class);
        Call<SteamGridGridsResponse> gridsCall = api.getGridsByGameId("Bearer " + API_KEY, gameId, "alternate", "600x900", "static");
        gridsCall.enqueue(new Callback<SteamGridGridsResponse>() { // from class: com.winlator.cmod.BigPictureActivity.9
            @Override // retrofit2.Callback
            public void onResponse(Call<SteamGridGridsResponse> call, Response<SteamGridGridsResponse> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().data.isEmpty()) {
                    BigPictureActivity.this.downloadCoverArt(response.body().data.get(0).url, shortcut);
                }
            }

            @Override // retrofit2.Callback
            public void onFailure(Call<SteamGridGridsResponse> call, Throwable t) {
                Log.e("SteamGridDB", "Failed to fetch cover art", t);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void downloadCoverArt(final String url, final Shortcut shortcut) {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                BigPictureActivity.this.lambda$downloadCoverArt$19(url, shortcut);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$downloadCoverArt$19(String url, Shortcut shortcut) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.connect();
            InputStream input = connection.getInputStream();
            final Bitmap coverArt = BitmapFactory.decodeStream(input);
            cacheCoverArt(coverArt, shortcut.name);
            shortcut.setCoverArt(coverArt);
            runOnUiThread(new Runnable() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda11
                @Override // java.lang.Runnable
                public final void run() {
                    BigPictureActivity.this.lambda$downloadCoverArt$18(coverArt);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$downloadCoverArt$18(Bitmap coverArt) {
        this.coverArtView.setImageBitmap(coverArt);
    }

    private void cacheCoverArt(Bitmap coverArt, String shortcutName) {
        try {
            File cacheDir = new File(getCacheDir(), "coverArtCache");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            File coverFile = new File(cacheDir, shortcutName + ".png");
            FileOutputStream outputStream = new FileOutputStream(coverFile);
            coverArt.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Bitmap loadCachedCoverArt(String shortcutName) {
        try {
            File cacheDir = new File(getCacheDir(), "coverArtCache");
            File coverFile = new File(cacheDir, shortcutName + ".png");
            if (coverFile.exists()) {
                return BitmapFactory.decodeFile(coverFile.getAbsolutePath());
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, android.app.Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SELECT_WALLPAPER && resultCode == -1 && data != null) {
            Uri selectedImageUri = data.getData();
            try {
                InputStream inputStream = getContentResolver().openInputStream(selectedImageUri);
                Bitmap wallpaper = BitmapFactory.decodeStream(inputStream);
                if (wallpaper != null) {
                    final File wallpaperFile = new File(getFilesDir(), "custom_bg.png");
                    FileOutputStream outputStream = new FileOutputStream(wallpaperFile);
                    wallpaper.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                    outputStream.flush();
                    outputStream.close();
                    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                    final SharedPreferences.Editor editor = preferences.edit();
                    editor.putString(WALLPAPER_PREF_KEY, wallpaperFile.getAbsolutePath());
                    editor.apply();
                    final String[] displayOptions = {"Center", "Stretch", "Tile"};
                    new AlertDialog.Builder(this).setTitle("Select Display Mode").setItems(displayOptions, new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda13
                        @Override // android.content.DialogInterface.OnClickListener
                        public final void onClick(DialogInterface dialogInterface, int i) {
                            BigPictureActivity.this.lambda$onActivityResult$20(editor, displayOptions, wallpaperFile, dialogInterface, i);
                        }
                    }).show();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (requestCode == REQUEST_CODE_SELECT_MP3 && resultCode == -1 && data != null && data.getData() != null) {
            this.selectedMp3Uri = data.getData();
            File appStorageDir = getFilesDir();
            File musicFile = new File(appStorageDir, "bigpicturemode_bgmusic.mp3");
            if (FileUtils.copy(this, this.selectedMp3Uri, musicFile, null)) {
                SharedPreferences preferences2 = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor editor2 = preferences2.edit();
                editor2.putString("selected_mp3_path", musicFile.getAbsolutePath());
                editor2.apply();
                playMp3(musicFile);
            } else {
                Log.e("BigPictureActivity", "Failed to copy the MP3 file.");
            }
        } else if (requestCode == REQUEST_CODE_UPLOAD_CUSTOM_COVER && data.getData() != null) {
            Uri selectedImageUri2 = data.getData();
            try {
                InputStream inputStream2 = getContentResolver().openInputStream(selectedImageUri2);
                Bitmap customCoverArt = BitmapFactory.decodeStream(inputStream2);
                if (this.currentShortcut != null) {
                    this.currentShortcut.saveCustomCoverArt(customCoverArt);
                    cacheCoverArt(customCoverArt, this.currentShortcut.name);
                    this.coverArtView.setImageBitmap(customCoverArt);
                    if (this.uploadText != null) {
                        this.uploadText.setVisibility(8);
                    }
                    this.coverArtView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda14
                        @Override // android.view.View.OnClickListener
                        public final void onClick(View view) {
                            BigPictureActivity.this.lambda$onActivityResult$21(view);
                        }
                    });
                }
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }
        if (requestCode == REQUEST_CODE_SELECT_PNG_FOLDER && resultCode == -1 && data != null) {
            Uri folderUri = data.getData();
            int takeFlags = 3 & data.getFlags();
            getContentResolver().takePersistableUriPermission(folderUri, takeFlags);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putString("png_folder_uri", folderUri.toString()).putString("selected_animation", "folder").apply();
            loadFramesFromFolder(folderUri);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onActivityResult$20(SharedPreferences.Editor editor, String[] displayOptions, File wallpaperFile, DialogInterface dialog, int which) {
        editor.putString(WALLPAPER_DISPLAY_PREF_KEY, displayOptions[which].toLowerCase());
        editor.apply();
        try {
            applyWallpaper(Uri.fromFile(wallpaperFile), displayOptions[which].toLowerCase());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onActivityResult$21(View v) {
        if (this.currentShortcut.getCustomCoverArtPath() != null) {
            showCoverArtOptionsDialog();
        } else {
            promptForCustomCoverArtUpload();
        }
    }

    private void applyWallpaper(Uri wallpaperUri, String mode) throws FileNotFoundException {
        TiledBackgroundView backgroundView = (TiledBackgroundView) findViewById(com.ludashi.benchmark.R.id.parallaxBackgroundView);
        if (backgroundView != null && wallpaperUri != null) {
            Bitmap wallpaper = BitmapFactory.decodeStream(getContentResolver().openInputStream(wallpaperUri));
            if (wallpaper != null) {
                backgroundView.setVisibility(0);
                backgroundView.setStaticWallpaper(wallpaper, mode);
            } else {
                Log.e("BigPictureActivity", "Invalid wallpaper dimensions.");
            }
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private void applyAnimationBasedOnState(TiledBackgroundView backgroundView, String animationState) {
        char c;
        if (backgroundView != null && animationState != null) {
            switch (animationState.hashCode()) {
                case -1268966290:
                    if (animationState.equals("folder")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case -1209896211:
                    if (animationState.equals("ab_gear")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 3387192:
                    if (animationState.equals(Container.DEFAULT_DDRAWRAPPER)) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case 1157642607:
                    if (animationState.equals("ab_quilt")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                default:
                    c = 65535;
                    break;
            }
            switch (c) {
                case 0:
                    backgroundView.setAnimation("ab_gear");
                    break;
                case 1:
                    backgroundView.setAnimation("ab_quilt");
                    break;
                case 2:
                    backgroundView.stopAnimation();
                    backgroundView.setVisibility(8);
                    return;
                case 3:
                    break;
                default:
                    if (!"folder".equals(animationState)) {
                        backgroundView.setAnimation("ab");
                        break;
                    }
                    break;
            }
            backgroundView.startAnimation();
        }
    }

    private void playMp3(File mp3File) {
        if (this.mediaPlayer != null) {
            this.mediaPlayer.release();
        }
        this.mediaPlayer = new MediaPlayer();
        try {
            FileInputStream fis = new FileInputStream(mp3File);
            this.mediaPlayer.setDataSource(fis.getFD());
            fis.close();
            this.mediaPlayer.prepare();
            this.mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { // from class: com.winlator.cmod.BigPictureActivity$$ExternalSyntheticLambda9
                @Override // android.media.MediaPlayer.OnPreparedListener
                public final void onPrepared(MediaPlayer mediaPlayer) {
                    BigPictureActivity.this.lambda$playMp3$22(mediaPlayer);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$playMp3$22(MediaPlayer mp) {
        this.mediaPlayer.start();
    }

    private String formatPlaytime(long playtimeInMillis) {
        long seconds = (playtimeInMillis / 1000) % 60;
        long minutes = (playtimeInMillis / 60000) % 60;
        long hours = (playtimeInMillis / 3600000) % 24;
        long days = playtimeInMillis / 86400000;
        return String.format("%dd %02dh %02dm %02ds", Long.valueOf(days), Long.valueOf(hours), Long.valueOf(minutes), Long.valueOf(seconds));
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.core.app.ComponentActivity, android.app.Activity, android.view.Window.Callback
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == 0) {
            View currentFocus = getCurrentFocus();
            switch (event.getKeyCode()) {
                case 19:
                    if (currentFocus == this.recyclerView) {
                        this.playButton.requestFocus();
                        return true;
                    }
                    if (currentFocus == this.playButton) {
                        this.graphicsDriverView.requestFocus();
                        return true;
                    }
                    if (currentFocus != this.coverArtView) {
                        this.playButton.requestFocus();
                        return true;
                    }
                    break;
                case 20:
                    if (currentFocus == this.playButton) {
                        focusClosestCarouselItem();
                        return true;
                    }
                    break;
                case 96:
                    if (currentFocus == this.playButton) {
                        this.playButton.performClick();
                        return true;
                    }
                    if (currentFocus == this.coverArtView) {
                        this.coverArtView.performClick();
                        return true;
                    }
                    break;
                case 103:
                case 105:
                    if (findViewById(com.ludashi.benchmark.R.id.settingsLayout).getVisibility() == 0) {
                        hideSettingsView();
                    } else {
                        showSettingsView();
                    }
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void focusClosestCarouselItem() {
        View itemView;
        LinearLayoutManager layoutManager = (LinearLayoutManager) this.recyclerView.getLayoutManager();
        int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();
        int lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition();
        int closestPosition = -1;
        float closestDistance = Float.MAX_VALUE;
        int recyclerViewCenter = this.recyclerView.getWidth() / 2;
        for (int i = firstVisibleItemPosition; i <= lastVisibleItemPosition; i++) {
            if (i >= 0 && (itemView = layoutManager.findViewByPosition(i)) != null) {
                int itemCenter = (itemView.getLeft() + itemView.getRight()) / 2;
                float distanceFromCenter = Math.abs(recyclerViewCenter - itemCenter);
                if (distanceFromCenter < closestDistance) {
                    closestDistance = distanceFromCenter;
                    closestPosition = i;
                }
            }
        }
        if (closestPosition != -1) {
            this.recyclerView.scrollToPosition(closestPosition);
            layoutManager.findViewByPosition(closestPosition).requestFocus();
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onResume() {
        super.onResume();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isBgMusicEnabled = preferences.getBoolean("bg_music_enabled", true);
        String musicSource = preferences.getString("music_source", "mp3");
        String selectedMp3Path = preferences.getString("selected_mp3_path", null);
        String savedAnimation = preferences.getString("selected_animation", "ab");
        if (savedAnimation.equals("custom_wallpaper")) {
            ((RadioButton) findViewById(com.ludashi.benchmark.R.id.rbCustomWallpaper)).setChecked(true);
        } else if (savedAnimation.equals("ab_gear")) {
            ((RadioButton) findViewById(com.ludashi.benchmark.R.id.rbGearAnimation)).setChecked(true);
        } else if (savedAnimation.equals("ab_quilt")) {
            ((RadioButton) findViewById(com.ludashi.benchmark.R.id.rbQuiltAnimation)).setChecked(true);
        } else if (savedAnimation.equals(Container.DEFAULT_DDRAWRAPPER)) {
            ((RadioButton) findViewById(com.ludashi.benchmark.R.id.rbNoAnimation)).setChecked(true);
        } else if (savedAnimation.equals("folder")) {
            ((RadioButton) findViewById(com.ludashi.benchmark.R.id.rbFolderAnimation)).setChecked(true);
        } else {
            ((RadioButton) findViewById(com.ludashi.benchmark.R.id.rbDefaultAnimation)).setChecked(true);
        }
        if (!isBgMusicEnabled) {
            this.webView.setVisibility(8);
            return;
        }
        if ("mp3".equals(musicSource)) {
            if (this.webView != null) {
                this.webView.setVisibility(4);
            }
            if (selectedMp3Path != null) {
                File mp3File = new File(selectedMp3Path);
                if (mp3File.exists()) {
                    if (this.mediaPlayer == null || !this.mediaPlayer.isPlaying()) {
                        playMp3(mp3File);
                        return;
                    }
                    return;
                }
                return;
            }
            return;
        }
        if ("youtube".equals(musicSource)) {
            if (this.webView != null) {
                this.webView.setVisibility(0);
            }
            String savedUrl = preferences.getString("saved_youtube_url", "");
            String videoId = savedUrl.isEmpty() ? "yNwKYgM6SkM" : extractYouTubeId(savedUrl);
            if (videoId != null) {
                loadYouTubeVideo(videoId);
            }
        }
    }

    private void playDefaultMp3FromAssets() {
        if (this.mediaPlayer != null) {
            this.mediaPlayer.release();
        }
        this.mediaPlayer = new MediaPlayer();
        try {
            AssetFileDescriptor afd = getAssets().openFd("default_music.mp3");
            this.mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            this.mediaPlayer.setLooping(true);
            this.mediaPlayer.prepare();
            this.mediaPlayer.start();
            afd.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopBackgroundMusic() {
        if (this.webView != null) {
            this.webView.loadUrl("about:blank");
        }
        if (this.mediaPlayer != null && this.mediaPlayer.isPlaying()) {
            this.mediaPlayer.stop();
            this.mediaPlayer.release();
            this.mediaPlayer = null;
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onPause() {
        super.onPause();
        stopBackgroundMusic();
    }

    private void selectPngFolder() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT_TREE");
        intent.addFlags(195);
        startActivityForResult(intent, REQUEST_CODE_SELECT_PNG_FOLDER);
    }

    private void loadFramesFromFolder(Uri folderUri) {
        DocumentFile docFolder = DocumentFile.fromTreeUri(this, folderUri);
        if (docFolder == null || !docFolder.isDirectory()) {
            Toast.makeText(this, "Invalid folder selected!", 0).show();
            return;
        }
        DocumentFile[] docFiles = docFolder.listFiles();
        if (docFiles == null || docFiles.length == 0) {
            Toast.makeText(this, "No files in folder!", 0).show();
            return;
        }
        List<Bitmap> bitmaps = new ArrayList<>();
        for (DocumentFile df : docFiles) {
            if (df != null && df.getName() != null && df.getName().toLowerCase().endsWith(".png")) {
                try {
                    InputStream is = getContentResolver().openInputStream(df.getUri());
                    try {
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            bitmaps.add(bmp);
                        }
                        if (is != null) {
                            is.close();
                        }
                    } catch (Throwable th) {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (Throwable th2) {
                                th.addSuppressed(th2);
                            }
                        }
                        throw th;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        if (bitmaps.isEmpty()) {
            Toast.makeText(this, "No PNG files found in this folder!", 0).show();
            return;
        }
        Log.d("AnimationCheck", "Loaded " + bitmaps.size() + " PNG frames from folder.");
        TiledBackgroundView backgroundView = (TiledBackgroundView) findViewById(com.ludashi.benchmark.R.id.parallaxBackgroundView);
        backgroundView.loadFramesFromBitmaps(bitmaps);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private void applyParallaxMode(String mode) {
        char c;
        TiledBackgroundView backgroundView = (TiledBackgroundView) findViewById(com.ludashi.benchmark.R.id.parallaxBackgroundView);
        if (backgroundView == null) {
        }
        switch (mode.hashCode()) {
            case 109935:
                if (mode.equals(DebugKt.DEBUG_PROPERTY_VALUE_OFF)) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case 3135580:
                if (mode.equals("fast")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 3533313:
                if (mode.equals("slow")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        switch (c) {
            case 0:
                backgroundView.setParallax(false, 0.0f, 0.0f);
                break;
            case 1:
                backgroundView.setParallax(true, 1.0f, 1.0f);
                break;
            case 2:
                backgroundView.setParallax(true, 5.0f, 5.0f);
                break;
            default:
                backgroundView.setParallax(true, 2.0f, 2.0f);
                break;
        }
    }
}
