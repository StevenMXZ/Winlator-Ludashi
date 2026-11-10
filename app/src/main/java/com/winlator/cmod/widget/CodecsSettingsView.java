package com.winlator.cmod.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.winlator.cmod.R;

/** Codec controls for the legacy container and shortcut dialogs. */
public final class CodecsSettingsView extends LinearLayout {
    private final Spinner decoder;
    private final CheckBox software;

    private CodecsSettingsView(Context context) {
        super(context);
        setId(R.id.codecs_settings);
        setOrientation(VERTICAL);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        TextView title = new TextView(context);
        title.setText(R.string.codecs); title.setTextSize(16); addView(title);
        TextView label = new TextView(context);
        label.setText(R.string.builtin_decoder); addView(label);
        decoder = new Spinner(context);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(context,
                R.array.builtin_decoder_entries, R.layout.spinner_item_amoled);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_amoled_compact);
        decoder.setAdapter(adapter);
        decoder.setPopupBackgroundResource(R.drawable.dialog_background_dark_blue);
        addView(decoder, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        software = new CheckBox(context);
        software.setText(R.string.software_decoding); addView(software);
    }

    public static void attach(View root, String decoder, boolean software) {
        CodecsSettingsView controls = root.findViewById(R.id.codecs_settings);
        if (controls == null) {
            controls = new CodecsSettingsView(root.getContext());
            ViewGroup parent = root.findViewById(R.id.LLTabWinComponents);
            parent.addView(controls);
        }
        controls.decoder.setSelection("ffmpeg".equals(decoder) ? 1 : 0);
        controls.software.setChecked(software);
    }

    public static String decoder(View root) {
        CodecsSettingsView controls = root.findViewById(R.id.codecs_settings);
        return controls != null && controls.decoder.getSelectedItemPosition() == 1 ? "ffmpeg" : "gstreamer";
    }

    public static boolean software(View root) {
        CodecsSettingsView controls = root.findViewById(R.id.codecs_settings);
        return controls != null && controls.software.isChecked();
    }
}
