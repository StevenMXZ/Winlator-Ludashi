package com.winlator.cmod.widget;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import com.winlator.cmod.R;
import com.winlator.cmod.ContainerDetailFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CodecsSettingsViewTest {
    @Test public void legacyDialogKeepsCodecControlsOutOfDllOverrides() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        LinearLayout root = new LinearLayout(context); root.setId(R.id.LLTabWinComponents);
        Spinner component = new Spinner(context);
        component.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                new String[]{"Builtin", "Native"}));
        component.setTag("direct3d"); component.setSelection(1); root.addView(component);
        CodecsSettingsView.attach(root, "ffmpeg", true);
        assertEquals("ffmpeg", CodecsSettingsView.decoder(root)); assertTrue(CodecsSettingsView.software(root));
        assertEquals("direct3d=1", ContainerDetailFragment.getWinComponents(root));
        CodecsSettingsView.attach(root, "gstreamer", false);
        assertEquals(2, root.getChildCount());
        assertEquals("gstreamer", CodecsSettingsView.decoder(root)); assertFalse(CodecsSettingsView.software(root));
    }
}
