package com.winlator.cmod;

import android.os.Looper;
import com.winlator.cmod.container.Shortcut;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShortcutsArtworkLifecycleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void detachedBannerFallbackFinishesOnceWithoutRequiringContext() throws Exception {
        ShortcutsFragment fragment = new ShortcutsFragment();
        AtomicInteger failures = new AtomicInteger();
        ReflectionHelpers.callInstanceMethod(fragment, "fetchBannerFromSteamGrid",
                ReflectionHelpers.ClassParameter.from(Shortcut.class, null),
                ReflectionHelpers.ClassParameter.from(File.class, new File(temporary.getRoot(), "banner.png")),
                ReflectionHelpers.ClassParameter.from(Runnable.class, () -> fail("Unexpected success")),
                ReflectionHelpers.ClassParameter.from(Runnable.class, failures::incrementAndGet));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, failures.get());
    }

    @Test public void queuedArtworkRefreshIsSafeAfterFragmentDetaches() {
        ShortcutsFragment fragment = new ShortcutsFragment();
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                ReflectionHelpers.callInstanceMethod(fragment, "refreshArtworkAndLauncherShortcuts",
                        ReflectionHelpers.ClassParameter.from(Shortcut.class, null)));
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(fragment.isAdded());
    }
}
