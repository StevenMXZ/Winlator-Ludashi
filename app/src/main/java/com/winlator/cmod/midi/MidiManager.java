package com.winlator.cmod.midi;

import android.content.Context;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import cn.sherlock.com.sun.media.sound.SF2Soundbank;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.FileUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/* loaded from: classes5.dex */
public class MidiManager {
    public static final String DEFAULT_SF2_FILE = "wt_210k_G.sf2";
    public static final int ERROR_BADFORMAT = 2;
    public static final int ERROR_EXIST = 1;
    public static final int ERROR_UNKNOWN = 0;
    public static final String SF2_ASSETS_DIR = "soundfonts";
    public static final String SF_DIR = "soundfonts";

    public interface OnMidiLoadedCallback {
        void onFailed(Exception exc);

        void onSuccess(SF2Soundbank sF2Soundbank);
    }

    public interface OnSoundFontInstalledCallback {
        void onFailed(int i);

        void onSuccess();
    }

    public static void load(final File file, final OnMidiLoadedCallback callback) {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.midi.MidiManager$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                MidiManager.lambda$load$0(file, callback);
            }
        });
    }

    static /* synthetic */ void lambda$load$0(File file, OnMidiLoadedCallback callback) {
        try {
            SF2Soundbank soundBank = new SF2Soundbank(file);
            callback.onSuccess(soundBank);
        } catch (Exception e) {
            callback.onFailed(e);
        }
    }

    public static void load(final InputStream in, final OnMidiLoadedCallback callback) throws IOException {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.midi.MidiManager$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                MidiManager.lambda$load$1(in, callback);
            }
        });
    }

    static /* synthetic */ void lambda$load$1(InputStream in, OnMidiLoadedCallback callback) {
        try {
            SF2Soundbank soundBank = new SF2Soundbank(in);
            callback.onSuccess(soundBank);
        } catch (Exception e) {
            callback.onFailed(e);
        }
    }

    private static List<File> getSF2Files(Context context) {
        try {
            return Arrays.asList(new File(context.getFilesDir(), "soundfonts").listFiles());
        } catch (Exception e) {
            return new ArrayList();
        }
    }

    public static File getSoundFontDir(Context context) {
        return new File(context.getFilesDir(), "soundfonts");
    }

    public static boolean removeSF2File(Context context, String fileName) {
        return new File(getSoundFontDir(context), fileName).delete();
    }

    public static void installSF2File(final Context context, final Uri uri, final OnSoundFontInstalledCallback callback) {
        File sfDir = getSoundFontDir(context);
        if (!sfDir.exists()) {
            sfDir.mkdirs();
        }
        String fileName = FileUtils.getUriFileName(context, uri);
        if (fileName == null) {
            callback.onFailed(0);
            return;
        }
        final File dest = new File(sfDir, fileName);
        if (dest.exists()) {
            callback.onFailed(1);
        } else {
            Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.midi.MidiManager$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    MidiManager.lambda$installSF2File$2(context, uri, dest, callback);
                }
            });
        }
    }

    static /* synthetic */ void lambda$installSF2File$2(Context context, Uri uri, File dest, OnSoundFontInstalledCallback callback) {
        if (FileUtils.copy(context, uri, dest)) {
            try {
                new SF2Soundbank(dest);
                callback.onSuccess();
                return;
            } catch (Exception e) {
                dest.delete();
                callback.onFailed(2);
                return;
            }
        }
        callback.onFailed(0);
    }

    public static void loadSFSpinner(Spinner spinner) {
        Context context = spinner.getContext();
        List<String> filesName = new ArrayList<>();
        List<File> sfFiles = getSF2Files(spinner.getContext());
        filesName.add("-- " + context.getString(R.string.disabled) + " --");
        filesName.add(DEFAULT_SF2_FILE);
        for (File file : sfFiles) {
            filesName.add(file.getName());
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(spinner.getContext(), android.R.layout.simple_spinner_dropdown_item, filesName));
    }

    public static void loadSFSpinnerWithoutDisabled(Spinner spinner) {
        spinner.getContext();
        List<String> filesName = new ArrayList<>();
        List<File> sfFiles = getSF2Files(spinner.getContext());
        filesName.add(DEFAULT_SF2_FILE);
        for (File file : sfFiles) {
            filesName.add(file.getName());
        }
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter(spinner.getContext(), android.R.layout.simple_spinner_dropdown_item, filesName));
    }
}
