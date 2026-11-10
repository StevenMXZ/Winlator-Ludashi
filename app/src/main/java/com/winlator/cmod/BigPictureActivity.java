package com.winlator.cmod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.winlator.cmod.R;
import com.winlator.cmod.bigpicture.BigPictureAdapter;
import com.winlator.cmod.bigpicture.CarouselItemDecoration;
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

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.animation.ObjectAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

public class BigPictureActivity extends AppCompatActivity {
    private ImageView coverArtView;
    private TextView gameTitleView, graphicsDriverView, graphicsDriverVersionView, dxWrapperView, dxWrapperConfigView, audioDriverView, box64PresetView, playCountView, playtimeView;
    private RecyclerView recyclerView;
    private ContainerManager manager;
    private BigPictureAdapter adapter;
    private ImageButton playButton;

    private Shortcut currentShortcut;
    private int lastFocusedItemIndex = RecyclerView.NO_POSITION;

    private static String API_KEY = "0324c52513634547a7b32d6d323635d0";
    private static final String BASE_URL = "https://www.steamgriddb.com/api/v2/";

    private static final int REQUEST_CODE_UPLOAD_CUSTOM_COVER = 1069;
    private TextView uploadText; // Class-level variable

    private TextView emptyStateTextView;

    private WebView webView;

    private static final int REQUEST_CODE_SELECT_MP3 = 1070;
    private Uri selectedMp3Uri;

    private MediaPlayer mediaPlayer;

    private static final int REQUEST_CODE_SELECT_WALLPAPER = 1080;
    private static final String WALLPAPER_PREF_KEY = "custom_wallpaper_path";
    private static final String WALLPAPER_DISPLAY_PREF_KEY = "wallpaper_display_mode";

    public static final String SEEK_BAR_PROGRESS_KEY = "frame_duration_seekbar";


    @Override
    protected void onStart() {
        super.onStart();
        TiledBackgroundView backgroundView = findViewById(R.id.parallaxBackgroundView);
        backgroundView.startAnimation(); // Start animation
    }

    @Override
    protected void onStop() {
        super.onStop();
        TiledBackgroundView backgroundView = findViewById(R.id.parallaxBackgroundView);
        backgroundView.stopAnimation(); // Stop animation
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();  // Hide the action bar for full-screen mode
        setContentView(R.layout.big_picture_activity);




        TiledBackgroundView backgroundView = findViewById(R.id.parallaxBackgroundView);

        Button selectWallpaperButton = findViewById(R.id.selectWallpaperButton);
        RadioButton rbCustomWallpaper = findViewById(R.id.rbCustomWallpaper); // Get reference to the custom wallpaper button

        Button folderPickerButton = findViewById(R.id.selectPngFolderButton);
        folderPickerButton.setOnClickListener(v -> {
            selectPngFolder();
        });

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String savedFolderUri = prefs.getString("png_folder_uri", null);
        if (savedFolderUri != null) {
            Uri folderUri = Uri.parse(savedFolderUri);
            loadFramesFromFolder(folderUri);
        }

        int storedFrameDuration = prefs.getInt("frame_duration", 66);
        TiledBackgroundView bgView = findViewById(R.id.parallaxBackgroundView);
        if (bgView != null) {
            bgView.setFrameDuration(storedFrameDuration);
        }


        SeekBar frameSpeedSeekBar = findViewById(R.id.frameSpeedSeekBar);
        bgView = findViewById(R.id.parallaxBackgroundView);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        int storedSeekBarProgress = prefs.getInt(SEEK_BAR_PROGRESS_KEY, 66); // default 66
        frameSpeedSeekBar.setProgress(storedSeekBarProgress);

        int reversedProgress = frameSpeedSeekBar.getMax() - storedSeekBarProgress;
        bgView.setFrameDuration(reversedProgress);

        TiledBackgroundView finalBgView = bgView;
        SharedPreferences finalPrefs = prefs;
        frameSpeedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int reversed = seekBar.getMax() - progress;
                finalBgView.setFrameDuration(reversed);

                finalPrefs.edit().putInt(SEEK_BAR_PROGRESS_KEY, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });


        RadioGroup animationSelectorGroup = findViewById(R.id.animationSelectorGroup);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        animationSelectorGroup.setOnCheckedChangeListener(null);

        String savedAnimation = preferences.getString("selected_animation", "ab"); // Default to "ab" or your preferred default
        if (savedAnimation.equals("custom_wallpaper")) {
            ((RadioButton) findViewById(R.id.rbCustomWallpaper)).setChecked(true);
        } else if (savedAnimation.equals("ab_gear")) {
            ((RadioButton) findViewById(R.id.rbGearAnimation)).setChecked(true);
        } else if (savedAnimation.equals("ab_quilt")) {
            ((RadioButton) findViewById(R.id.rbQuiltAnimation)).setChecked(true);
        } else if (savedAnimation.equals("none")) {
            ((RadioButton) findViewById(R.id.rbNoAnimation)).setChecked(true);
        } else if (savedAnimation.equals("folder")) {
            ((RadioButton) findViewById(R.id.rbFolderAnimation)).setChecked(true);
            savedFolderUri = preferences.getString("png_folder_uri", null);
            if (savedFolderUri != null) {
                Uri folderUri = Uri.parse(savedFolderUri);
                loadFramesFromFolder(folderUri);
            }
        } else {
            ((RadioButton) findViewById(R.id.rbDefaultAnimation)).setChecked(true);
        }

        animationSelectorGroup.setOnCheckedChangeListener((group, checkedId) -> {
            SharedPreferences.Editor editor = preferences.edit();
            if (checkedId == R.id.rbCustomWallpaper) {
                selectWallpaperButton.setVisibility(View.VISIBLE);
                backgroundView.setVisibility(View.GONE);
                editor.putString("selected_animation", "custom_wallpaper");
            } else {
                selectWallpaperButton.setVisibility(View.GONE);
                backgroundView.setVisibility(View.VISIBLE);

                if (checkedId == R.id.rbGearAnimation) {
                    backgroundView.setAnimation("ab_gear");
                    editor.putString("selected_animation", "ab_gear");
                } else if (checkedId == R.id.rbQuiltAnimation) {
                    backgroundView.setAnimation("ab_quilt");
                    editor.putString("selected_animation", "ab_quilt");
                } else if (checkedId == R.id.rbDefaultAnimation) {
                    backgroundView.setAnimation("ab");
                    editor.putString("selected_animation", "ab");
                } else if (checkedId == R.id.rbNoAnimation) {
                    backgroundView.stopAnimation();
                    backgroundView.setVisibility(View.GONE);
                    editor.putString("selected_animation", "none");
                } else if (checkedId == R.id.rbFolderAnimation) {
                    editor.putString("selected_animation", "folder");
                    selectPngFolder();
                }
            }
            editor.apply();
            backgroundView.startAnimation();
        });

        if (savedAnimation.equals("ab_gear")) {
            backgroundView.setAnimation("ab_gear");
        } else if (savedAnimation.equals("ab_quilt")) {
            backgroundView.setAnimation("ab_quilt");
        } else if (savedAnimation.equals("none")) {
            backgroundView.stopAnimation();
            backgroundView.setVisibility(View.GONE); // Hide when animation stops
        } else if (savedAnimation.equals("folder")) {
            savedFolderUri = preferences.getString("png_folder_uri", null);
            if (savedFolderUri != null) {
                Uri folderUri = Uri.parse(savedFolderUri);
                loadFramesFromFolder(folderUri);
            }
        } else {
            backgroundView.setAnimation("ab");
        }
        backgroundView.startAnimation();

        selectWallpaperButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_CODE_SELECT_WALLPAPER);
        });


        if ("custom_wallpaper".equals(savedAnimation)) {
            String savedWallpaperPath = preferences.getString(WALLPAPER_PREF_KEY, null);
            if (savedWallpaperPath != null) {
                File wallpaperFile = new File(savedWallpaperPath);
                if (wallpaperFile.exists()) {
                    try {
                        applyWallpaper(Uri.fromFile(wallpaperFile), preferences.getString(WALLPAPER_DISPLAY_PREF_KEY, "center"));
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            applyAnimationBasedOnState(backgroundView, savedAnimation);
        }


        RadioGroup parallaxModeGroup = findViewById(R.id.parallaxModeGroup);

        String savedParallaxMode = preferences.getString("parallax_mode", "default");
        switch (savedParallaxMode) {
            case "off":
                ((RadioButton) findViewById(R.id.rbParallaxOff)).setChecked(true);
                break;
            case "slow":
                ((RadioButton) findViewById(R.id.rbParallaxSlow)).setChecked(true);
                break;
            case "fast":
                ((RadioButton) findViewById(R.id.rbParallaxFast)).setChecked(true);
                break;
            default:
                ((RadioButton) findViewById(R.id.rbParallaxDefault)).setChecked(true);
                break;
        }

        applyParallaxMode(savedParallaxMode);

        parallaxModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String mode;
            switch (checkedId) {
                case R.id.rbParallaxOff:
                    mode = "off";
                    break;
                case R.id.rbParallaxSlow:
                    mode = "slow";
                    break;
                case R.id.rbParallaxFast:
                    mode = "fast";
                    break;
                default:
                    mode = "default";
                    break;
            }
            preferences.edit().putString("parallax_mode", mode).apply();

            applyParallaxMode(mode);
        });



        EditText youtubeUrlInput = findViewById(R.id.youtubeUrlInput);
        Button loadVideoButton = findViewById(R.id.loadVideoButton);

        final String defaultVideoId = "yNwKYgM6SkM"; // Wii shop channel music extended


        LinearLayout settingsLayout = findViewById(R.id.settingsLayout);


        boolean isCustomApiKeyEnabled = preferences.getBoolean("enable_custom_api_key", false);
        if (isCustomApiKeyEnabled) {
            String customApiKey = preferences.getString("custom_api_key", "");
            if (customApiKey != null && !customApiKey.isEmpty()) {
                API_KEY = customApiKey;
            }
        }

        Button disableBgMusicButton = findViewById(R.id.disableBgMusicButton);

        boolean isBgMusicEnabled = preferences.getBoolean("bg_music_enabled", false);

        updateBgMusicButtonText(disableBgMusicButton, isBgMusicEnabled);

        disableBgMusicButton.setOnClickListener(v -> {
            boolean currentBgMusicState = preferences.getBoolean("bg_music_enabled", false);
            boolean newBgMusicState = !currentBgMusicState;

            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("bg_music_enabled", newBgMusicState);
            editor.apply();

            updateBgMusicButtonText(disableBgMusicButton, newBgMusicState);

            if (newBgMusicState) {
                onResume(); // Restart music based on the current selection (MP3 or YouTube)
            } else {
                stopBackgroundMusic(); // Stop both YouTube and MP3
            }
        });


        Button selectMp3Button = findViewById(R.id.selectMp3Button);
        selectMp3Button.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/mpeg");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_SELECT_MP3);
        });



        RadioGroup musicSourceGroup = findViewById(R.id.musicSourceGroup);
        RadioButton youtubeRadioButton = findViewById(R.id.youtubeRadioButton);
        RadioButton mp3RadioButton = findViewById(R.id.mp3RadioButton);

        Button resetMp3Button = findViewById(R.id.resetMp3Button);
        resetMp3Button.setOnClickListener(v -> {
            stopBackgroundMusic();

            SharedPreferences.Editor editor = preferences.edit();
            editor.remove("selected_mp3_path");
            editor.putString("music_source", "mp3"); // Ensure the music source is set to MP3
            editor.apply();

            mp3RadioButton.setChecked(true);

            playDefaultMp3FromAssets();

            Toast.makeText(this, "No bundled music; select an MP3 to play music.", Toast.LENGTH_SHORT).show();
        });


        String musicSource = preferences.getString("music_source", "mp3");

        if ("mp3".equals(musicSource)) {
            mp3RadioButton.setChecked(true);
        } else {
            youtubeRadioButton.setChecked(true);
        }

        musicSourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            SharedPreferences.Editor editor = preferences.edit();
            if (checkedId == R.id.youtubeRadioButton) {
                editor.putString("music_source", "youtube");
            } else if (checkedId == R.id.mp3RadioButton) {
                editor.putString("music_source", "mp3");
            }
            editor.apply();
        });


        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true); // Enable JavaScript
        webView.setWebViewClient(new WebViewClient()); // Prevent redirecting to external browser

        String selectedMp3Path = preferences.getString("selected_mp3_path", null);

        if (isBgMusicEnabled) {
            if ("mp3".equals(musicSource)) {
                if (selectedMp3Path != null) {
                    File mp3File = new File(selectedMp3Path);

                    if (mp3File.exists()) {
                        playMp3(mp3File);  // Pass the File object to playMp3 method
                    } else {
                        Log.e("BigPictureActivity", "MP3 file not found: " + selectedMp3Path);
                        playDefaultMp3FromAssets();
                    }
                } else {
                    playDefaultMp3FromAssets();
                }
            } else if ("youtube".equals(musicSource)) {
                String savedUrl = preferences.getString("saved_youtube_url", "");
                String videoId = savedUrl.isEmpty() ? defaultVideoId : extractYouTubeId(savedUrl);

                if (videoId != null) {
                    loadYouTubeVideo(videoId);
                    youtubeUrlInput.setText(savedUrl);  // Populate the input field with the saved URL
                } else {
                    loadYouTubeVideo(defaultVideoId);
                    youtubeUrlInput.setText("");  // Clear the input field if invalid or default video is used
                }
            }
        }




        loadVideoButton.setOnClickListener(v -> {
            String userUrl = youtubeUrlInput.getText().toString();
            if (userUrl != null && !userUrl.isEmpty()) {
                String videoId = extractYouTubeId(userUrl);
                if (videoId != null) {
                    loadYouTubeVideo(videoId);  // Load the user-specified video

                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putString("saved_youtube_url", userUrl);
                    editor.apply();
                } else {
                    youtubeUrlInput.setError("Invalid YouTube URL");
                }
            } else {
                loadYouTubeVideo(defaultVideoId);
            }
        });

        enableImmersiveMode();

        ImageButton settingsButton = findViewById(R.id.settingsButton);

        Drawable settingsIcon = settingsButton.getDrawable();
        if (settingsIcon != null) {
            settingsIcon.mutate();  // Ensure it doesn't affect other instances
            settingsIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);  // Apply the white color filter
        }

        settingsButton.setOnClickListener(v -> {
            if (findViewById(R.id.settingsLayout).getVisibility() == View.VISIBLE) {
                hideSettingsView();
            } else {
                showSettingsView();
            }
        });

        ImageButton backButton = findViewById(R.id.backButton);

        Drawable backIcon = backButton.getDrawable();
        if (backIcon != null) {
            backIcon.mutate();  // Ensure it doesn't affect other instances
            backIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);  // Apply the white color filter
        }

        backButton.setOnClickListener(v -> {
            if (findViewById(R.id.settingsLayout).getVisibility() == View.VISIBLE) {
                hideSettingsView();
            } else {
                showSettingsView();
            }
        });


        coverArtView = findViewById(R.id.IVCoverArt);
        gameTitleView = findViewById(R.id.TVGameTitle);
        graphicsDriverView = findViewById(R.id.TVGraphicsDriver);
        graphicsDriverVersionView = findViewById(R.id.TVGraphicsDriverVersion);
        dxWrapperView = findViewById(R.id.TVDXWrapper);
        dxWrapperConfigView = findViewById(R.id.TVDXWrapperConfig);
        audioDriverView = findViewById(R.id.TVAudioDriver);
        box64PresetView = findViewById(R.id.TVBox64Preset);
        playCountView = findViewById(R.id.TVPlayCount);
        playtimeView = findViewById(R.id.TVPlaytime);
        recyclerView = findViewById(R.id.RecyclerView);
        playButton = findViewById(R.id.playButton);

        Drawable playIcon = playButton.getDrawable();
        if (playIcon != null) {
            playIcon.mutate();  // Ensure it doesn't affect other instances
            playIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);  // Apply the white color filter
        }

        recyclerView.addItemDecoration(new CarouselItemDecoration(15));  // Reduced space between items

        manager = new ContainerManager(this);

        loadShortcutsList();

        SnapHelper snapHelper = new LinearSnapHelper();
        snapHelper.attachToRecyclerView(recyclerView);


        playButton.setOnClickListener(v -> {
            if (currentShortcut != null) {
                runFromShortcut(currentShortcut);  // Use the loaded shortcut
            }
        });

        coverArtView.setOnClickListener(v -> {
            if (currentShortcut != null) {
                if (currentShortcut.getCustomCoverArtPath() != null) {
                    showCoverArtOptionsDialog();
                } else {
                    promptForCustomCoverArtUpload();
                }
            }
        });

        playButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    playButton.requestFocus(); // Ensure playButton gets focus
                    playButton.performClick(); // Simulate a click immediately
                }
                return true; // Consumes the touch event so that it doesn't require another click
            }
        });

        settingsButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    settingsButton.requestFocus(); // Ensure settingsButton gets focus
                    settingsButton.performClick(); // Simulate a click immediately
                }
                return true; // Consumes the touch event
            }
        });

        backButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    backButton.requestFocus(); // Ensure backButton gets focus
                    backButton.performClick(); // Simulate a click immediately
                }
                return true; // Consumes the touch event
            }
        });

    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.settingsLayout).getVisibility() == View.VISIBLE) {
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


    /**
     * Extracts the YouTube video ID from a given URL.
     *
     * @param youtubeUrl The full YouTube URL.
     * @return The extracted video ID or null if the URL is invalid.
     */
    private String extractYouTubeId(String youtubeUrl) {
        String videoIdPattern = "^(https?://)?(www\\.)?(youtube\\.com|youtu\\.?be)/.+$";
        if (youtubeUrl.matches(videoIdPattern)) {
            String[] splitUrl = youtubeUrl.split("v=");
            if (splitUrl.length > 1) {
                return splitUrl[1].split("&")[0]; // Split off any extra parameters
            } else if (youtubeUrl.contains("youtu.be/")) {
                return youtubeUrl.substring(youtubeUrl.lastIndexOf("/") + 1);
            }
        }
        return null; // Invalid URL
    }

    private void loadYouTubeVideo(String videoId) {
        String html = "<html><body>" +
                "<iframe id=\"player\" type=\"text/html\" width=\"100%\" height=\"100%\"" +
                "src=\"https://www.youtube.com/embed/" + videoId + "?enablejsapi=1\"" +  // Removed autoplay and simplified script
                "frameborder=\"0\" allowfullscreen></iframe>" +
                "</body></html>";

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                simulateTouchOnWebView(webView);
                webView.setVisibility(View.INVISIBLE);
            }
        });

        webView.loadData(html, "text/html", "UTF-8");
    }



    private void simulateTouchOnWebView(WebView webView) {
        new Handler().postDelayed(() -> {
            int webViewWidth = webView.getWidth();
            int webViewHeight = webView.getHeight();

            float x = webViewWidth / 2f;
            float y = webViewHeight / 2f;

            long downTime = System.currentTimeMillis();
            long eventTime = System.currentTimeMillis() + 100;

            MotionEvent touchDown = MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0
            );

            MotionEvent touchUp = MotionEvent.obtain(
                    downTime, eventTime + 100, MotionEvent.ACTION_UP, x, y, 0
            );

            webView.dispatchTouchEvent(touchDown);
            webView.dispatchTouchEvent(touchUp);

            touchDown.recycle();
            touchUp.recycle();


        }, 1000);  // Delay of 1 second after WebView loads
    }

    private void showSettingsView() {
        final LinearLayout mainLayout = findViewById(R.id.mainLayout);
        final LinearLayout settingsLayout = findViewById(R.id.settingsLayout);

        settingsLayout.setVisibility(View.VISIBLE);

        settingsLayout.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                settingsLayout.getViewTreeObserver().removeOnPreDrawListener(this);
                ObjectAnimator mainSlideOut = ObjectAnimator.ofFloat(mainLayout, "translationX", 0f, -mainLayout.getWidth());
                mainSlideOut.setInterpolator(new AccelerateDecelerateInterpolator());
                mainSlideOut.setDuration(500);
                mainSlideOut.start();

                ObjectAnimator settingsSlideIn = ObjectAnimator.ofFloat(settingsLayout, "translationX", settingsLayout.getWidth(), 0f);
                settingsSlideIn.setInterpolator(new AccelerateDecelerateInterpolator());
                settingsSlideIn.setDuration(500);
                settingsSlideIn.start();
                return true;
            }
        });
    }

    private void hideSettingsView() {
        LinearLayout mainLayout = findViewById(R.id.mainLayout);
        LinearLayout settingsLayout = findViewById(R.id.settingsLayout);

        ObjectAnimator mainSlideIn = ObjectAnimator.ofFloat(mainLayout, "translationX", -mainLayout.getWidth(), 0f);
        mainSlideIn.setInterpolator(new AccelerateDecelerateInterpolator());
        mainSlideIn.setDuration(500);
        mainSlideIn.start();

        ObjectAnimator settingsSlideOut = ObjectAnimator.ofFloat(settingsLayout, "translationX", 0f, settingsLayout.getWidth());
        settingsSlideOut.setInterpolator(new AccelerateDecelerateInterpolator());
        settingsSlideOut.setDuration(500);
        settingsSlideOut.start();
        settingsSlideOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                settingsLayout.setVisibility(View.GONE);  // Hide after animation completes
            }
        });
    }


    private void showCoverArtOptionsDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cover Art Options")
                .setItems(new CharSequence[]{"Remove Custom Cover Art", "Upload New Cover Art"}, (dialog, which) -> {
                    switch (which) {
                        case 0: // Remove Custom Cover Art
                            removeCustomCoverArt();
                            break;
                        case 1: // Upload New Cover Art
                            promptForCustomCoverArtUpload();
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void removeCustomCoverArt() {
        if (currentShortcut != null) {
            Log.d("BigPictureActivity", "Removing cover art for shortcut: " + currentShortcut.name);
            Log.d("BigPictureActivity", "Current custom cover art path: " + currentShortcut.getCustomCoverArtPath());

            currentShortcut.removeCustomCoverArt();

            File cachedFile = new File(getCacheDir(), "coverArtCache/" + currentShortcut.name + ".png");
            if (cachedFile.exists() && cachedFile.delete()) {
                Log.d("BigPictureActivity", "Cached cover art deleted successfully.");
            } else {
                Log.e("BigPictureActivity", "Failed to delete cached cover art or it doesn't exist.");
            }

            coverArtView.setImageResource(R.drawable.icon_action_bar_import); // Default placeholder image
            coverArtView.setBackgroundColor(Color.parseColor("#99000000")); // Semi-transparent background

            Log.d("BigPictureActivity", "Custom cover art removed and data saved.");

            loadShortcutData(currentShortcut);
            Log.d("BigPictureActivity", "Shortcut data reloaded after removal.");
        }
    }



    private void promptForCustomCoverArtUpload() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_UPLOAD_CUSTOM_COVER);
    }

    private void enableImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private Shortcut getSelectedShortcut() {
        int position = getCenterItemPosition();
        if (position != RecyclerView.NO_POSITION) {
            return adapter.getItem(position);
        }
        return null;
    }

    private int getCenterItemPosition() {
        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();
        int lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition();

        int centerPosition = RecyclerView.NO_POSITION;
        float closestToCenter = Float.MAX_VALUE;
        int recyclerViewCenter = recyclerView.getWidth() / 2;

        for (int i = firstVisibleItemPosition; i <= lastVisibleItemPosition; i++) {
            if (i >= 0) {
                View itemView = layoutManager.findViewByPosition(i);
                if (itemView != null) {
                    int itemCenter = (itemView.getLeft() + itemView.getRight()) / 2;
                    float distanceFromCenter = Math.abs(recyclerViewCenter - itemCenter);

                    if (distanceFromCenter < closestToCenter) {
                        closestToCenter = distanceFromCenter;
                        centerPosition = i;
                    }
                }
            }
        }

        return centerPosition;
    }

    private void loadShortcutsList() {
        List<Shortcut> shortcuts = manager.loadShortcuts();
        emptyStateTextView = findViewById(R.id.TVEmptyState);

        if (shortcuts.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            playButton.setVisibility(View.GONE);
            emptyStateTextView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyStateTextView.setVisibility(View.GONE);

            adapter = new BigPictureAdapter(shortcuts, recyclerView); // Pass the RecyclerView reference
            recyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            recyclerView.setAdapter(adapter);

            loadShortcutData(shortcuts.get(0));
        }
    }


    public void loadShortcutData(Shortcut shortcut) {
        currentShortcut = shortcut;

        Log.d("BigPictureActivity", "Loaded cover art path: " + shortcut.getCustomCoverArtPath());

        gameTitleView.setText(shortcut.name);

        SharedPreferences playtimePrefs = getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);
        long totalPlaytime = playtimePrefs.getLong(shortcut.name + "_playtime", 0);
        int playCount = playtimePrefs.getInt(shortcut.name + "_play_count", 0);
        playCountView.setText("Times Played: " + playCount);
        playtimeView.setText("Playtime: " + formatPlaytime(totalPlaytime));

        Container container = manager.getContainerForShortcut(shortcut);
        String graphicsDriver = shortcut.getExtra("graphicsDriver");
        
        setTextOrPlaceholder(graphicsDriverView, graphicsDriver, container.getGraphicsDriver());
        setTextOrPlaceholder(graphicsDriverVersionView, shortcut.getExtra("graphicsDroverConfig"), container.getGraphicsDriverConfig());
        setTextOrPlaceholder(dxWrapperView, shortcut.getExtra("dxwrapper"), container.getDXWrapper());
        setTextOrPlaceholder(dxWrapperConfigView, shortcut.getExtra("dxwrapperConfig"), container.getDXWrapperConfig());
        setTextOrPlaceholder(audioDriverView, shortcut.getExtra("audioDriver"), container.getAudioDriver());
        setTextOrPlaceholder(box64PresetView, shortcut.getExtra("box64Preset"), container.getBox64Preset());

        Bitmap coverArt = null;
        if (shortcut.getCustomCoverArtPath() != null && !shortcut.getCustomCoverArtPath().isEmpty()) {
            coverArt = BitmapFactory.decodeFile(shortcut.getCustomCoverArtPath());
        }

        if (coverArt == null) {
            coverArt = loadCachedCoverArt(shortcut.name);
        }

        if (coverArt != null) {
            coverArtView.setImageBitmap(coverArt); // Set cover art from custom or cache
        } else {
            coverArtView.setImageResource(R.drawable.cover_art_placeholder); // Default icon or placeholder
            fetchCoverArt(shortcut); // Fetch from remote if not available locally
        }

        coverArtView.setOnClickListener(v -> {
            if (currentShortcut.getCustomCoverArtPath() != null) {
                showCoverArtOptionsDialog();
            } else {
                promptForCustomCoverArtUpload();
            }
        });
    }


    private void runFromShortcut(Shortcut shortcut) {
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", shortcut.container.id);
        intent.putExtra("shortcut_path", shortcut.file.getPath());
        intent.putExtra("shortcut_name", shortcut.name); // Pass the shortcut name for display
        String disableXinputValue = shortcut.getExtra("disableXinput", "0"); // Get value from shortcut or use "0" (false) by default
        intent.putExtra("disableXinput", disableXinputValue); // Use the actual value from the shortcut
        startActivity(intent);
    }


    private void setTextOrPlaceholder(TextView textView, String shortcutValue, String containerValue) {
        if (!shortcutValue.isEmpty()) {
            textView.setText(shortcutValue); // Use the value from the shortcut if available
        } else if (!containerValue.isEmpty()) {
            textView.setText(containerValue); // Fallback to the container's value
        } else {
            textView.setText("Not Set"); // Fallback if neither are available
        }
    }


    private void setTextFromContainer(TextView textView, String label, String shortcutValue, String containerValue) {
        if (!shortcutValue.isEmpty()) {
            textView.setText(label + shortcutValue); // Use the value from the shortcut if available
        } else if (!containerValue.isEmpty()) {
            textView.setText(label + containerValue); // Fallback to the container's value
        } else {
            textView.setText(label + "Not Set"); // Fallback if neither are available
        }
    }


    private void fetchCoverArt(Shortcut shortcut) {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(new OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        SteamGridDBApi api = retrofit.create(SteamGridDBApi.class);
        Call<SteamGridSearchResponse> call = api.searchGame("Bearer " + API_KEY, shortcut.name);

        call.enqueue(new Callback<SteamGridSearchResponse>() {
            @Override
            public void onResponse(Call<SteamGridSearchResponse> call, Response<SteamGridSearchResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<SteamGridSearchResponse.GameData> gameData = response.body().data;
                    if (gameData != null && !gameData.isEmpty()) {
                        fetchGridsForGame(gameData.get(0).id, shortcut);
                    } else {
                        showCustomCoverArtUploadOption(shortcut);
                    }
                } else {
                    showCustomCoverArtUploadOption(shortcut);
                }
            }

            @Override
            public void onFailure(Call<SteamGridSearchResponse> call, Throwable t) {
                Log.e("SteamGridDB", "Failed to fetch game ID", t);
                showCustomCoverArtUploadOption(shortcut);
            }
        });
    }

    private void showCustomCoverArtUploadOption(Shortcut shortcut) {
        runOnUiThread(() -> {
            coverArtView.setImageResource(android.R.color.transparent); // Remove existing placeholder
            coverArtView.setBackgroundColor(Color.parseColor("#99000000")); // Semi-transparent gray background

            coverArtView.setImageResource(R.drawable.cover_art_placeholder);
            coverArtView.setOnClickListener(v -> promptForCustomCoverArtUpload());

            if (uploadText != null) {
                ViewGroup parent = (ViewGroup) uploadText.getParent();
                if (parent != null) {
                    parent.removeView(uploadText);
                }
            }

            uploadText = new TextView(this); // Initialize the uploadText variable
            uploadText.setText("No suitable cover art found for " + shortcut.name + ". Click the image to upload custom cover art or rename the Shortcut to something SteamGrid can recognize.");
            uploadText.setTextColor(Color.WHITE);
            uploadText.setTextSize(18);
            uploadText.setPadding(20, 20, 20, 20);
            uploadText.setGravity(Gravity.CENTER);
            uploadText.setBackgroundColor(Color.parseColor("#99000000")); // Semi-transparent gray background

            ViewGroup parent = (ViewGroup) coverArtView.getParent();
            parent.addView(uploadText);
        });
    }




    private void fetchGridsForGame(int gameId, Shortcut shortcut) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SteamGridGridsResponse.class, new SteamGridGridsResponseDeserializer())
                .setPrettyPrinting()
                .create();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(new OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        SteamGridDBApi api = retrofit.create(SteamGridDBApi.class);

        Call<SteamGridGridsResponse> gridsCall = api.getGridsByGameId("Bearer " + API_KEY, gameId, "alternate", "600x900", "static");

        gridsCall.enqueue(new Callback<SteamGridGridsResponse>() {
            @Override
            public void onResponse(Call<SteamGridGridsResponse> call, Response<SteamGridGridsResponse> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().data.isEmpty()) {
                    downloadCoverArt(response.body().data.get(0).url, shortcut);
                }
            }

            @Override
            public void onFailure(Call<SteamGridGridsResponse> call, Throwable t) {
                Log.e("SteamGridDB", "Failed to fetch cover art", t);
            }
        });
    }

    private void downloadCoverArt(String url, Shortcut shortcut) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.connect();
                InputStream input = connection.getInputStream();
                Bitmap coverArt = BitmapFactory.decodeStream(input);

                cacheCoverArt(coverArt, shortcut.name);

                shortcut.setCoverArt(coverArt);
                runOnUiThread(() -> coverArtView.setImageBitmap(coverArt));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
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
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SELECT_WALLPAPER && resultCode == RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();

            try {
                InputStream inputStream = getContentResolver().openInputStream(selectedImageUri);
                Bitmap wallpaper = BitmapFactory.decodeStream(inputStream);
                if (wallpaper != null) {
                    File wallpaperFile = new File(getFilesDir(), "custom_bg.png");
                    FileOutputStream outputStream = new FileOutputStream(wallpaperFile);
                    wallpaper.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                    outputStream.flush();
                    outputStream.close();

                    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putString(WALLPAPER_PREF_KEY, wallpaperFile.getAbsolutePath());
                    editor.apply();

                    String[] displayOptions = {"Center", "Stretch", "Tile"};
                    new AlertDialog.Builder(this)
                            .setTitle("Select Display Mode")
                            .setItems(displayOptions, (dialog, which) -> {
                                editor.putString(WALLPAPER_DISPLAY_PREF_KEY, displayOptions[which].toLowerCase());
                                editor.apply();

                                try {
                                    applyWallpaper(Uri.fromFile(wallpaperFile), displayOptions[which].toLowerCase());
                                } catch (FileNotFoundException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .show();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        if (requestCode == REQUEST_CODE_SELECT_MP3 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedMp3Uri = data.getData();

            File appStorageDir = getFilesDir();
            File musicFile = new File(appStorageDir, "bigpicturemode_bgmusic.mp3");

            if (FileUtils.copy(this, selectedMp3Uri, musicFile, null)) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("selected_mp3_path", musicFile.getAbsolutePath()); // Store the file path instead of URI
                editor.apply();

                playMp3(musicFile);  // Pass the File object
            } else {
                Log.e("BigPictureActivity", "Failed to copy the MP3 file.");
            }
        } else if (requestCode == REQUEST_CODE_UPLOAD_CUSTOM_COVER && data.getData() != null) {
            Uri selectedImageUri = data.getData();
            try {
                InputStream inputStream = getContentResolver().openInputStream(selectedImageUri);
                Bitmap customCoverArt = BitmapFactory.decodeStream(inputStream);

                if (currentShortcut != null) {
                    currentShortcut.saveCustomCoverArt(customCoverArt);  // Save custom cover art

                    cacheCoverArt(customCoverArt, currentShortcut.name);

                    coverArtView.setImageBitmap(customCoverArt);

                    if (uploadText != null) {
                        uploadText.setVisibility(View.GONE);
                    }

                    coverArtView.setOnClickListener(v -> {
                        if (currentShortcut.getCustomCoverArtPath() != null) {
                            showCoverArtOptionsDialog();
                        } else {
                            promptForCustomCoverArtUpload();
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (requestCode == REQUEST_CODE_SELECT_PNG_FOLDER && resultCode == RESULT_OK) {
            if (data != null) {
                Uri folderUri = data.getData();
                final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                getContentResolver().takePersistableUriPermission(folderUri, takeFlags);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit()
                        .putString("png_folder_uri", folderUri.toString())
                        .putString("selected_animation", "folder")   // <-- store that we’re now using a folder-based animation
                        .apply();

                loadFramesFromFolder(folderUri);
            }
        }

    }


    private void applyWallpaper(Uri wallpaperUri, String mode) throws FileNotFoundException {
        TiledBackgroundView backgroundView = findViewById(R.id.parallaxBackgroundView);
        if (backgroundView != null && wallpaperUri != null) {
            Bitmap wallpaper = BitmapFactory.decodeStream(getContentResolver().openInputStream(wallpaperUri));
            if (wallpaper != null) {
                backgroundView.setVisibility(View.VISIBLE);
                backgroundView.setStaticWallpaper(wallpaper, mode);
            } else {
                Log.e("BigPictureActivity", "Invalid wallpaper dimensions.");
            }
        }
    }

    private void applyAnimationBasedOnState(TiledBackgroundView backgroundView, String animationState) {
        if (backgroundView != null && animationState != null) {
            switch (animationState) {
                case "ab_gear":
                    backgroundView.setAnimation("ab_gear");
                    break;
                case "ab_quilt":
                    backgroundView.setAnimation("ab_quilt");
                    break;
                case "none":
                    backgroundView.stopAnimation();
                    backgroundView.setVisibility(View.GONE); // Hide the view when animation is stopped
                    return; // Exit early since there's no animation to start
                case "folder":
                    break;
                default:
                    if (!"folder".equals(animationState)) {
                        backgroundView.setAnimation("ab");
                    }
                    break;
            }
            backgroundView.startAnimation();
        }
    }




    private void playMp3(File mp3File) {
        if (mediaPlayer != null) {
            mediaPlayer.release();  // Release any existing player
        }

        mediaPlayer = new MediaPlayer();
        try {
            FileInputStream fis = new FileInputStream(mp3File);
            mediaPlayer.setDataSource(fis.getFD());  // Use FileDescriptor for internal storage files
            fis.close();

            mediaPlayer.prepare();  // Prepare synchronously
            mediaPlayer.setOnPreparedListener(mp -> mediaPlayer.start());  // Start playback once prepared
        } catch (IOException e) {
            e.printStackTrace();
        }
    }





    private String formatPlaytime(long playtimeInMillis) {
        long seconds = (playtimeInMillis / 1000) % 60;
        long minutes = (playtimeInMillis / (1000 * 60)) % 60;
        long hours = (playtimeInMillis / (1000 * 60 * 60)) % 24;
        long days = (playtimeInMillis / (1000 * 60 * 60 * 24));

        return String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View currentFocus = getCurrentFocus();

            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (currentFocus == recyclerView) {
                        playButton.requestFocus(); // Move focus to playButton when UP is pressed from RecyclerView
                        return true;
                    } else if (currentFocus == playButton) {
                        graphicsDriverView.requestFocus();
                        return true;
                    } else if (currentFocus != coverArtView) {
                        playButton.requestFocus();
                        return true;
                    }
                    break;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (currentFocus == playButton) {
                        focusClosestCarouselItem();
                        return true;
                    }
                    break;

                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    break;

                case KeyEvent.KEYCODE_BUTTON_A:
                    if (currentFocus == playButton) {
                        playButton.performClick();
                        return true;
                    } else if (currentFocus == coverArtView) {
                        coverArtView.performClick();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_BUTTON_R1:  // RB button
                case KeyEvent.KEYCODE_BUTTON_R2:  // RT button
                    if (findViewById(R.id.settingsLayout).getVisibility() == View.VISIBLE) {
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
        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();
        int lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition();

        int closestPosition = RecyclerView.NO_POSITION;
        float closestDistance = Float.MAX_VALUE;

        int recyclerViewCenter = recyclerView.getWidth() / 2;

        for (int i = firstVisibleItemPosition; i <= lastVisibleItemPosition; i++) {
            if (i >= 0) {
                View itemView = layoutManager.findViewByPosition(i);
                if (itemView != null) {
                    int itemCenter = (itemView.getLeft() + itemView.getRight()) / 2;
                    float distanceFromCenter = Math.abs(recyclerViewCenter - itemCenter);

                    if (distanceFromCenter < closestDistance) {
                        closestDistance = distanceFromCenter;
                        closestPosition = i;
                    }
                }
            }
        }

        if (closestPosition != RecyclerView.NO_POSITION) {
            recyclerView.scrollToPosition(closestPosition);
            layoutManager.findViewByPosition(closestPosition).requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isBgMusicEnabled = preferences.getBoolean("bg_music_enabled", false);
        String musicSource = preferences.getString("music_source", "mp3"); // Default to "mp3"
        String selectedMp3Path = preferences.getString("selected_mp3_path", null); // Use the file path instead of URI

        String savedAnimation = preferences.getString("selected_animation", "ab");
        if (savedAnimation.equals("custom_wallpaper")) {
            ((RadioButton) findViewById(R.id.rbCustomWallpaper)).setChecked(true);
        } else if (savedAnimation.equals("ab_gear")) {
            ((RadioButton) findViewById(R.id.rbGearAnimation)).setChecked(true);
        } else if (savedAnimation.equals("ab_quilt")) {
            ((RadioButton) findViewById(R.id.rbQuiltAnimation)).setChecked(true);
        } else if (savedAnimation.equals("none")) {
            ((RadioButton) findViewById(R.id.rbNoAnimation)).setChecked(true);
        } else if (savedAnimation.equals("folder")) {
            ((RadioButton) findViewById(R.id.rbFolderAnimation)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.rbDefaultAnimation)).setChecked(true);
        }

        if (!isBgMusicEnabled) {
            webView.setVisibility(View.GONE);
            return; // Exit early if background music is disabled
        }

        if ("mp3".equals(musicSource)) {
            if (webView != null) {
                webView.setVisibility(View.INVISIBLE); // Hide WebView when MP3 is selected
            }

            if (selectedMp3Path != null) {
                File mp3File = new File(selectedMp3Path);
                if (mp3File.exists() && (mediaPlayer == null || !mediaPlayer.isPlaying())) {
                    playMp3(mp3File);  // Play the MP3 file
                }
            }
        } else if ("youtube".equals(musicSource)) {
            if (webView != null) {
                webView.setVisibility(View.VISIBLE); // Show WebView if YouTube is selected
            }

            String savedUrl = preferences.getString("saved_youtube_url", "");
            String videoId = savedUrl.isEmpty() ? "yNwKYgM6SkM" : extractYouTubeId(savedUrl);
            if (videoId != null) {
                loadYouTubeVideo(videoId);
            }
        }
    }

    private void playDefaultMp3FromAssets() {
        // No bundled MP3. Keep Big Picture silent until custom music is chosen.
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }


    private void stopBackgroundMusic() {
        if (webView != null) {
            webView.loadUrl("about:blank");
        }

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            mediaPlayer.release(); // Release resources
            mediaPlayer = null;
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        stopBackgroundMusic(); // Ensure MP3 or YouTube stops when activity is paused
    }

    private static final int REQUEST_CODE_SELECT_PNG_FOLDER = 1090;

    private void selectPngFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_SELECT_PNG_FOLDER);
    }

    private void loadFramesFromFolder(Uri folderUri) {
        DocumentFile docFolder = DocumentFile.fromTreeUri(this, folderUri);
        if (docFolder == null || !docFolder.isDirectory()) {
            Toast.makeText(this, "Invalid folder selected!", Toast.LENGTH_SHORT).show();
            return;
        }

        DocumentFile[] docFiles = docFolder.listFiles();
        if (docFiles == null || docFiles.length == 0) {
            Toast.makeText(this, "No files in folder!", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Bitmap> bitmaps = new ArrayList<>();
        for (DocumentFile df : docFiles) {
            if (df != null && df.getName() != null && df.getName().toLowerCase().endsWith(".png")) {
                try (InputStream is = getContentResolver().openInputStream(df.getUri())) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (bmp != null) {
                        bitmaps.add(bmp);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        if (bitmaps.isEmpty()) {
            Toast.makeText(this, "No PNG files found in this folder!", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d("AnimationCheck", "Loaded " + bitmaps.size() + " PNG frames from folder.");
        TiledBackgroundView backgroundView = findViewById(R.id.parallaxBackgroundView);
        backgroundView.loadFramesFromBitmaps(bitmaps);  // A new method we’ll create below
    }

    private void applyParallaxMode(String mode) {
        TiledBackgroundView backgroundView = findViewById(R.id.parallaxBackgroundView);
        if (backgroundView == null) return;

        switch (mode) {
            case "off":
                backgroundView.setParallax(false, 0f, 0f);
                break;
            case "slow":
                backgroundView.setParallax(true, 1.0f, 1.0f);
                break;
            case "fast":
                backgroundView.setParallax(true, 5.0f, 5.0f);
                break;
            default: // "default"
                backgroundView.setParallax(true, 2.0f, 2.0f);
                break;
        }
    }



}
