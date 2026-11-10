package com.winlator.cmod.ui.settings;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Build;
import android.os.Looper;
import java.util.Locale;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.winlator.cmod.R;
import com.winlator.cmod.WinlatorApplication;
import com.winlator.cmod.ui.UiStrings;
import com.winlator.cmod.ui.UiOptionLabelsKt;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 32, 33}, application = WinlatorApplication.class, qualifiers = "en-rUS")
public class AppLanguageSettingsTest {
    public static class LocaleActivity extends AppCompatActivity {
        @Override public void onCreate(Bundle state) {
            setTheme(R.style.AppTheme);
            super.onCreate(state);
        }
    }

    private ActivityController<LocaleActivity> controller;

    @Before public void setup() {
        ComponentName name = new ComponentName(RuntimeEnvironment.getApplication(), LocaleActivity.class);
        ActivityInfo info = Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .addActivityIfNotPresent(name);
        info.configChanges |= ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_LAYOUT_DIRECTION;
        controller = Robolectric.buildActivity(LocaleActivity.class).setup();
        UiStrings.install(RuntimeEnvironment.getApplication());
        select("");
    }

    private void select(String tags) {
        AppLanguageSettings.INSTANCE.select(tags);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void cleanup() {
        select("");
        controller.pause().stop().destroy();
    }

    @Test public void portugueseOverrideAlsoTranslatesApplicationContextHelpers() {
        select("pt-BR");
        // Robolectric stores Android 13 app locales but does not dispatch its resource update.
        if (Build.VERSION.SDK_INT < 33) assertEquals("Executar", controller.get().getString(R.string.run));
        assertEquals("Executar", UiStrings.get(R.string.run));
        assertEquals("Desativado", UiOptionLabelsKt.uiOptionLabel("Off"));
        assertEquals("mailbox", UiOptionLabelsKt.uiOptionLabel("mailbox"));
        assertEquals("Custom Game Name", UiOptionLabelsKt.uiOptionLabel("Custom Game Name"));
        assertEquals("pt-BR", AppCompatDelegate.getApplicationLocales().toLanguageTags());
    }

    @Test public void canReturnToSystemLanguageAndExplicitEnglish() {
        select("pt-BR");
        select("en");
        assertEquals("Run", UiStrings.get(R.string.run));
        if (Build.VERSION.SDK_INT < 33) assertEquals("Run", controller.get().getString(R.string.run));
        select("");
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty());
        assertEquals("Run", UiStrings.get(R.string.run));
    }

    @Test @Config(qualifiers = "pt-rBR")
    public void followsBrazilianAndroidLocaleWithoutAnOverride() {
        assertEquals("Executar", UiStrings.get(R.string.run));
        select("en");
        assertEquals("Run", UiStrings.get(R.string.run));
        select("");
        assertEquals("Executar", UiStrings.get(R.string.run));
    }

    @Test public void textReadsKeepTheResolvedLanguageUntilAnExplicitRefresh() {
        assertEquals("Run", UiStrings.get(R.string.run));
        Resources system = Resources.getSystem();
        Configuration changed = new Configuration(system.getConfiguration());
        changed.setLocale(Locale.forLanguageTag("pt-BR"));
        system.updateConfiguration(changed, system.getDisplayMetrics());
        assertEquals("Run", UiStrings.get(R.string.run));
        select("");
        assertEquals("Executar", UiStrings.get(R.string.run));
    }

    @Test public void portugueseFormattingPreservesDynamicValues() {
        select("pt-BR");
        assertEquals("Falha no backup: disk full", UiStrings.get(R.string.ui_backup_failed_1_s, "disk full"));
        assertEquals("Locais de saves detectados: 2.", UiStrings.get(R.string.ui_detected_1_s_save_location_2_s, 2));
    }

    @Test @Config(qualifiers = "de-rDE")
    public void unsupportedAndroidLanguageFallsBackToEnglish() {
        assertEquals("Run", UiStrings.get(R.string.run));
    }
}
