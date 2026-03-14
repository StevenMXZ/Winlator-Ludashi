package com.winlator.cmod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.widget.Toast;

/* loaded from: classes8.dex */
public class ShortcutBroadcastReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "ShortcutBroadcastReceiver";

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null || !action.equals("com.winlator.SHORTCUT_ADDED")) {
            Log.d(LOG_TAG, "Unexpected broadcast action received.");
            return;
        }
        boolean isShortcutAdded = intent.getBooleanExtra("shortcut_added", false);
        if (isShortcutAdded) {
            Log.d(LOG_TAG, "Shortcut added successfully!");
            Toast.makeText(context, "Sorry, your device may not be supported", 0).show();
        } else {
            Log.d(LOG_TAG, "Shortcut addition failed.");
            Toast.makeText(context, "Failed to add shortcut.", 0).show();
            addShortcutToHomeScreen(context, intent);
        }
    }

    private void addShortcutToHomeScreen(Context context, Intent originalIntent) {
        String shortcutName = originalIntent.getStringExtra("shortcut_name");
        Bitmap shortcutIcon = (Bitmap) originalIntent.getParcelableExtra("shortcut_icon");
        Intent shortcutIntent = (Intent) originalIntent.getParcelableExtra("android.intent.extra.shortcut.INTENT");
        if (shortcutName == null || shortcutIcon == null || shortcutIntent == null) {
            Log.e(LOG_TAG, "Missing shortcut data, cannot add to home screen.");
            return;
        }
        ShortcutManager shortcutManager = (ShortcutManager) context.getSystemService(ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            ShortcutInfo pinShortcutInfo = new ShortcutInfo.Builder(context, shortcutName).setShortLabel(shortcutName).setIcon(Icon.createWithBitmap(shortcutIcon)).setIntent(shortcutIntent).build();
            Log.d(LOG_TAG, "Requesting pin shortcut from the BroadcastReceiver...");
            boolean result = shortcutManager.requestPinShortcut(pinShortcutInfo, null);
            Log.d(LOG_TAG, "Pin shortcut requested with result: " + result);
            if (!result) {
                Log.e(LOG_TAG, "Failed to add shortcut from BroadcastReceiver.");
            } else {
                Toast.makeText(context, "Shortcut added successfully from BroadcastReceiver!", 0).show();
            }
        }
    }
}
