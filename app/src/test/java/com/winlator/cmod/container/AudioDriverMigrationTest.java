package com.winlator.cmod.container;

import android.content.Context;
import com.winlator.cmod.core.GPUInformation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = AudioDriverMigrationTest.GpuShadow.class)
public class AudioDriverMigrationTest {
    @Implements(value = GPUInformation.class, isInAndroidSdk = false)
    public static class GpuShadow {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static String getRenderer(String driver, Context context) { return "Test GPU"; }
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void newContainersAndLegacyAlsaUseTheDirectPlugin() {
        Container container = new Container(1);
        assertEquals("alsa-oboe", container.getAudioDriver());
        for (String value : new String[]{null, "", "ALSA", "oboe", " Alsa-Oboe "}) {
            container.setAudioDriver(value);
            assertEquals("alsa-oboe", container.getAudioDriver());
            assertEquals("Alsa-Oboe", Container.getAudioDriverLabel(value));
        }
    }

    @Test public void retiredPulseAudioMigratesToGnAndGnIsPreserved() {
        Container container = new Container(1);
        for (String value : new String[]{"pulseaudio", "PulseAudio", "pulse-audio", "pulseaudio-gn", "pulse-audio-gn"}) {
            container.setAudioDriver(value);
            assertEquals("pulse-audio-gn", container.getAudioDriver());
            assertEquals("PulseAudio-GN", Container.getAudioDriverLabel(value));
        }
    }

    @Test public void gameOverridesMigrateWithoutLosingContainerInheritance() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        container.setAudioDriver("pulse-audio-gn");
        Shortcut game = new Shortcut(container, temporary.newFile("audio.desktop"));
        assertEquals("pulse-audio-gn", game.getAudioDriver());
        game.putExtra("audioDriver", "alsa");
        assertEquals("alsa-oboe", game.getAudioDriver());
        game.putExtra("audioDriver", "pulseaudio");
        assertEquals("pulse-audio-gn", game.getAudioDriver());
        game.putExtra("audioDriver", "");
        assertEquals("pulse-audio-gn", game.getAudioDriver());
    }
}
