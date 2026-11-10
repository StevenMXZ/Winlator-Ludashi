package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.ui.theme.WinlatorLegacyTheme;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PreloaderDialog {
    private static final String TAG = "PreloaderDialog";
    private static final String THEGAMESDB_SEARCH = "https://thegamesdb.net/search.php?name=%s&platform_id%%5B%%5D=1";
    private static final String THEGAMESDB_CDN = "https://cdn.thegamesdb.net/images/original/";
    private static final Pattern THEGAMESDB_GAME_ID = Pattern.compile("game\\.php\\?id=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final ExecutorService ARTWORK_EXECUTOR = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private Dialog dialog;
    private Bitmap artworkBitmap;
    private volatile String theGamesDbRequestKey;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.WinZDialogAnimation);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public synchronized void show(int textResId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (isShowing()) return;
        close();
        if (dialog == null) create();

        if (textResId == R.string.starting_up) {
            configureLaunchScreen(textResId);
        } else {
            configureStandardPreloader(textResId);
        }

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            applyImmersiveFullscreen(window);
        }
    }

    private void applyImmersiveFullscreen(Window window) {
        if (window == null) return;

        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat insetsController =
                new WindowInsetsControllerCompat(window, window.getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.systemBars());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }

        WindowManager.LayoutParams attrs = window.getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }

        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void configureLaunchScreen(int textResId) {
        View root = dialog.findViewById(R.id.PreloaderRoot);
        View launchScrim = dialog.findViewById(R.id.LaunchScrim);
        View launchInfo = dialog.findViewById(R.id.LaunchInfo);
        View loadingPanel = dialog.findViewById(R.id.LoadingPanel);
        ImageView artworkView = dialog.findViewById(R.id.LaunchArtwork);
        TextView launchTitle = dialog.findViewById(R.id.LaunchTitle);
        TextView launchStatus = dialog.findViewById(R.id.LaunchStatus);
        LinearProgressIndicator launchProgress = dialog.findViewById(R.id.LaunchProgress);

        root.setBackgroundColor(WinlatorLegacyTheme.background(activity));
        loadingPanel.setVisibility(View.GONE);
        launchInfo.setVisibility(View.VISIBLE);
        launchProgress.setVisibility(View.VISIBLE);
        launchProgress.setIndicatorColor(WinlatorLegacyTheme.primary(activity));
        launchProgress.setTrackColor(withAlpha(WinlatorLegacyTheme.primary(activity), 0x35));

        String launchTitleText = resolveLaunchTitle();
        launchTitle.setText(launchTitleText);
        launchStatus.setText(textResId);

        releaseArtwork();
        File userArtwork = getLaunchUserArtworkFile();
        boolean hasUserArtwork = isUsableImageFile(userArtwork);
        File banner = getLaunchBannerFile();
        boolean hasBanner = isUsableImageFile(banner);
        File artwork = hasUserArtwork ? userArtwork
                : hasBanner ? banner : getLaunchCoverFile();
        if (isUsableImageFile(artwork)) artworkBitmap = decodeArtwork(artwork);

        boolean hasArtwork = artworkBitmap != null;
        if (hasArtwork) {
            artworkView.setImageBitmap(artworkBitmap);
            artworkView.setVisibility(View.VISIBLE);
        } else {
            artworkView.setImageDrawable(null);
            artworkView.setVisibility(View.GONE);
        }
        launchScrim.setVisibility(hasArtwork ? View.VISIBLE : View.GONE);
        applyLaunchTextColors(hasArtwork);

        if (!hasUserArtwork && !hasBanner && banner != null) {
            requestTheGamesDbBanner(launchTitleText, banner);
        }
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private void applyLaunchTextColors(boolean overArtwork) {
        if (dialog == null) return;
        TextView launchTitle = dialog.findViewById(R.id.LaunchTitle);
        TextView launchStatus = dialog.findViewById(R.id.LaunchStatus);
        if (overArtwork) {
            launchTitle.setTextColor(Color.WHITE);
            launchStatus.setTextColor(0xD9FFFFFF);
        } else {
            launchTitle.setTextColor(WinlatorLegacyTheme.onBackground(activity));
            launchStatus.setTextColor(WinlatorLegacyTheme.onSurfaceVariant(activity));
        }
    }

    private void configureStandardPreloader(int textResId) {
        View root = dialog.findViewById(R.id.PreloaderRoot);
        View launchScrim = dialog.findViewById(R.id.LaunchScrim);
        View launchInfo = dialog.findViewById(R.id.LaunchInfo);
        View loadingPanel = dialog.findViewById(R.id.LoadingPanel);
        View launchProgress = dialog.findViewById(R.id.LaunchProgress);
        ImageView artworkView = dialog.findViewById(R.id.LaunchArtwork);
        TextView textView = dialog.findViewById(R.id.TextView);

        releaseArtwork();
        artworkView.setImageDrawable(null);
        artworkView.setVisibility(View.GONE);
        launchScrim.setVisibility(View.GONE);
        launchInfo.setVisibility(View.GONE);
        launchProgress.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.VISIBLE);
        root.setBackgroundColor(0xB3000000);
        textView.setText(textResId);
    }

    private String resolveLaunchTitle() {
        Intent intent = activity.getIntent();
        String title = intent != null ? intent.getStringExtra("shortcut_name") : null;
        String shortcutPath = intent != null ? intent.getStringExtra("shortcut_path") : null;

        if (TextUtils.isEmpty(title) && !TextUtils.isEmpty(shortcutPath)) {
            title = FileUtils.getBasename(shortcutPath);
        }

        if (TextUtils.isEmpty(title) && intent != null) {
            int containerId = intent.getIntExtra("container_id", 0);
            if (containerId > 0) {
                try {
                    Container container = new ContainerManager(activity).getContainerById(containerId);
                    if (container != null && !TextUtils.isEmpty(container.getName())) title = container.getName();
                } catch (Exception ignored) {}
            }
        }

        return TextUtils.isEmpty(title) ? activity.getString(R.string.app_name) : com.winlator.cmod.steam.SteamGames.launchTitle(activity, shortcutPath, title);
    }

    private String resolveLaunchBaseName() {
        Intent intent = activity.getIntent();
        if (intent == null) return null;
        String shortcutPath = intent.getStringExtra("shortcut_path");
        if (TextUtils.isEmpty(shortcutPath)) return null;
        String baseName = FileUtils.getBasename(shortcutPath);
        return TextUtils.isEmpty(baseName) ? null : baseName;
    }

    private File getLaunchUserArtworkFile() {
        String baseName = resolveLaunchBaseName();
        if (TextUtils.isEmpty(baseName)) return null;
        return new File(new File(Environment.getExternalStorageDirectory(), "Winlator/icons"),
                baseName + ".user.png");
    }

    private File getLaunchBannerFile() {
        String baseName = resolveLaunchBaseName();
        if (TextUtils.isEmpty(baseName)) return null;
        File dir = new File(Environment.getExternalStorageDirectory(), "Winlator/banners");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, baseName + ".png");
    }

    private File getLaunchCoverFile() {
        String baseName = resolveLaunchBaseName();
        if (TextUtils.isEmpty(baseName)) return null;
        return new File(new File(Environment.getExternalStorageDirectory(), "Winlator/covers"), baseName + ".png");
    }

    private boolean isUsableImageFile(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private void requestTheGamesDbBanner(String title, File destination) {
        if (TextUtils.isEmpty(title) || destination == null || isUsableImageFile(destination)) return;
        final String requestKey = destination.getAbsolutePath() + "|" + title;
        if (requestKey.equals(theGamesDbRequestKey)) return;
        theGamesDbRequestKey = requestKey;

        ARTWORK_EXECUTOR.execute(() -> {
            boolean saved = false;
            try {
                int gameId = findTheGamesDbGameId(title);
                if (gameId > 0) saved = downloadTheGamesDbHorizontalArtwork(gameId, destination);
            } catch (Throwable error) {
                Log.d(TAG, "TheGamesDB fallback failed: " + error.getMessage());
            }

            final boolean downloaded = saved;
            activity.runOnUiThread(() -> {
                if (requestKey.equals(theGamesDbRequestKey)) theGamesDbRequestKey = null;
                if (downloaded) applyDownloadedLaunchArtwork(destination);
            });
        });
    }

    private int findTheGamesDbGameId(String title) throws Exception {
        String encoded = URLEncoder.encode(title, "UTF-8");
        String searchUrl = String.format(THEGAMESDB_SEARCH, encoded);
        String html = downloadText(searchUrl);
        Matcher matcher = THEGAMESDB_GAME_ID.matcher(html);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String downloadText(String urlString) throws Exception {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 WinZ/1.0");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) throw new IllegalStateException("HTTP " + responseCode);

            reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && builder.length() < 2_000_000) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    private boolean downloadTheGamesDbHorizontalArtwork(int gameId, File destination) {
        String[] categories = {"fanart", "screenshot", "screenshots"};
        for (String category : categories) {
            for (int index = 1; index <= 3; index++) {
                String url = THEGAMESDB_CDN + category + "/" + gameId + "-" + index + ".jpg";
                if (downloadImageAsPng(url, destination)) {
                    Log.d(TAG, "TheGamesDB " + category + " saved for game " + gameId);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean downloadImageAsPng(String urlString, File destination) {
        HttpURLConnection connection = null;
        Bitmap bitmap = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 WinZ/1.0");
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) return false;

            bitmap = BitmapFactory.decodeStream(connection.getInputStream());
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return false;
            if (bitmap.getWidth() < bitmap.getHeight()) return false;

            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(destination)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return false;
                output.flush();
            }
            return destination.isFile() && destination.length() > 0;
        } catch (Throwable ignored) {
            if (destination.exists() && destination.length() == 0) destination.delete();
            return false;
        } finally {
            if (bitmap != null) {
                try { if (!bitmap.isRecycled()) bitmap.recycle(); } catch (Exception ignored) {}
            }
            if (connection != null) connection.disconnect();
        }
    }

    private void applyDownloadedLaunchArtwork(File artworkFile) {
        if (!isShowing() || dialog == null || !isUsableImageFile(artworkFile)) return;
        Bitmap replacement = decodeArtwork(artworkFile);
        if (replacement == null) return;

        releaseArtwork();
        artworkBitmap = replacement;
        ImageView artworkView = dialog.findViewById(R.id.LaunchArtwork);
        View launchScrim = dialog.findViewById(R.id.LaunchScrim);
        artworkView.setImageBitmap(artworkBitmap);
        artworkView.setVisibility(View.VISIBLE);
        launchScrim.setVisibility(View.VISIBLE);
        applyLaunchTextColors(true);
    }

    private Bitmap decodeArtwork(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int targetWidth = Math.max(1, activity.getResources().getDisplayMetrics().widthPixels);
            int targetHeight = Math.max(1, activity.getResources().getDisplayMetrics().heightPixels);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth
                    && bounds.outHeight / (sample * 2) >= targetHeight) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void showOnUiThread(final int textResId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                ImageView artworkView = dialog.findViewById(R.id.LaunchArtwork);
                if (artworkView != null) artworkView.setImageDrawable(null);
                dialog.dismiss();
            }
        } catch (Exception ignored) {}
        theGamesDbRequestKey = null;
        releaseArtwork();
    }

    private void releaseArtwork() {
        if (artworkBitmap != null) {
            try {
                if (!artworkBitmap.isRecycled()) artworkBitmap.recycle();
            } catch (Exception ignored) {}
            artworkBitmap = null;
        }
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}

