package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.widget.InputControlsView;

import java.io.File;
import java.io.InputStream;

public class ControlsEditorActivity extends AppCompatActivity {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private ControlElement pendingIconElement = null;
    private int pendingBuiltinOverrideId = -1;
    private static final int PICK_ICON_REQUEST = 7001;
    private View editorView;

    private static File getCustomIconsDir() {
        return new File(Environment.getExternalStorageDirectory(), "winlator/custom_icons");
    }

    static File getBuiltinOverridePath(int iconId) {
        return new File(getCustomIconsDir(), "override_" + iconId + ".png");
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(1.0f);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        if (profile == null) {
            Log.e("ControlsEditor", "Profile not found for id=" + getIntent().getIntExtra("profile_id", 0));
            AppUtils.showToast(this, R.string.no_profile_selected);
            finish();
            return;
        }
        inputControlsView.setProfile(profile);
        editorView = com.winlator.cmod.ui.inputcontrols.ControlsEditorComposeHost.create(
                this, profile, inputControlsView, (element, overrideId) -> {
                    pendingIconElement = element;
                    pendingBuiltinOverrideId = overrideId;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, PICK_ICON_REQUEST);
                });
        setContentView(editorView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (!prefs.getBoolean("mix_warning_shown_v4", false)) {
                ContentDialog.alert(this, R.string.warning_gamepad_mouse_mix, () -> {
                    prefs.edit().putBoolean("mix_warning_shown_v4", true).apply();
                });
            }
        }, 500);
    }

    private Bitmap loadAndScaleBitmap(String path) {
        final int MAX_DIM = 256;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w("Icons", "Cannot decode dimensions of " + path + " — file may be empty, corrupt, or not a supported image format");
                return null;
            }
            if (opts.outWidth <= MAX_DIM && opts.outHeight <= MAX_DIM) {
                bmp = BitmapFactory.decodeFile(path);
            } else {
                int sampleSize = 1;
                int w = opts.outWidth, h = opts.outHeight;
                while (w / 2 >= MAX_DIM || h / 2 >= MAX_DIM) { sampleSize *= 2; w /= 2; h /= 2; }
                opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize;
                Bitmap sampled = BitmapFactory.decodeFile(path, opts);
                if (sampled == null) {
                    Log.w("Icons", "Failed to decode " + path + " even with sample size " + sampleSize);
                    return null;
                }
                float scale = Math.min((float) MAX_DIM / sampled.getWidth(), (float) MAX_DIM / sampled.getHeight());
                bmp = Bitmap.createScaledBitmap(sampled, (int)(sampled.getWidth() * scale), (int)(sampled.getHeight() * scale), true);
                if (!sampled.isRecycled()) sampled.recycle();
                Log.i("Icons", "Auto-scaled " + new File(path).getName() +
                    " from " + opts.outWidth + "x" + opts.outHeight + " to " + bmp.getWidth() + "x" + bmp.getHeight());
            }
        } catch (Exception e) {
            Log.e("Icons", "Exception loading " + path + ": " + e.getMessage());
        }
        if (bmp == null) Log.w("Icons", "Final decode returned null for " + path);
        return bmp;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ICON_REQUEST && resultCode == Activity.RESULT_OK
                && data != null) {
            Uri uri = data.getData();
            try {
                File iconsDir = getCustomIconsDir();
                if (!iconsDir.exists()) iconsDir.mkdirs();

                File dest;
                if (pendingBuiltinOverrideId >= 0) {
                    dest = getBuiltinOverridePath(pendingBuiltinOverrideId);
                    pendingBuiltinOverrideId = -1;
                } else {
                    dest = new File(iconsDir, "icon_" + System.currentTimeMillis() + ".png");
                }

                try (InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }

                Bitmap loaded = loadAndScaleBitmap(dest.getAbsolutePath());
                if (loaded == null) {
                    dest.delete();
                    Log.w("Icons", "Picked file is not a valid image or could not be decoded: " + uri);
                    AppUtils.showToast(this, R.string.unable_to_set_icon);
                    pendingIconElement = null;
                    return;
                }
                if (loaded.getWidth() != -1) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                        loaded.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                }
                loaded.recycle();

                inputControlsView.invalidateIconCache();

                if (pendingIconElement != null) {
                    pendingIconElement.setCustomIconPath(dest.getAbsolutePath());
                    pendingIconElement.setIconId(0);
                    pendingIconElement = null;
                    profile.save();
                }
                inputControlsView.invalidate();

                com.winlator.cmod.ui.inputcontrols.ControlsEditorComposeHost.refresh(editorView);
            } catch (Exception e) {
                Log.e("Icons", "Error saving picked icon: " + e.getMessage());
                pendingIconElement = null;
                pendingBuiltinOverrideId = -1;
                AppUtils.showToast(this, R.string.unable_to_set_icon);
            }
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_down, R.anim.slide_out_up);
    }
}
