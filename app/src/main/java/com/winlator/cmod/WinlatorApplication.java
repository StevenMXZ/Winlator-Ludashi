package com.winlator.cmod;

import android.app.Application;
import android.content.res.Configuration;
import android.os.LocaleList;
import com.winlator.cmod.ui.UiStrings;

public final class WinlatorApplication extends Application {
    private LocaleList locales;
    @Override
    public void onCreate() {
        super.onCreate();
        UiStrings.install(this);
        locales = getResources().getConfiguration().getLocales();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (!configuration.getLocales().equals(locales)) {
            locales = configuration.getLocales();
            UiStrings.invalidate();
        }
    }
}
