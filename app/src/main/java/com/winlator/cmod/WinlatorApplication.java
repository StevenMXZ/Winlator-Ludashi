package com.winlator.cmod;

import android.app.Application;
import android.content.res.Configuration;
import com.winlator.cmod.ui.UiStrings;

public final class WinlatorApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UiStrings.install(this);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        UiStrings.invalidate();
    }
}
