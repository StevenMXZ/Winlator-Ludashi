package com.winlator.cmod.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.XmlResourceParser;
import android.widget.LinearLayout;
import android.widget.Switch;
import com.winlator.cmod.R;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SidebarLocaleResourcesTest {
    private Context portuguese() {
        Context app = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag("pt-BR"));
        return app.createConfigurationContext(configuration);
    }

    private String sidebarText(Context context, int id) throws Exception {
        String android = "http://schemas.android.com/apk/res/android";
        try (XmlResourceParser parser = context.getResources().getLayout(R.layout.left_sidebar_original)) {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                if (parser.getAttributeResourceValue(android, "id", 0) != id) continue;
                int text = parser.getAttributeResourceValue(android, "text", 0);
                assertTrue("Sidebar caption must use a string resource", text != 0);
                return context.getString(text);
            }
        }
        throw new AssertionError("Sidebar control was not found");
    }

    @Test public void legacyBindingsUsedByComposeExposePortugueseCaptions() throws Exception {
        Context context = portuguese();
        assertEquals("Geração de quadros LSFG", sidebarText(context, R.id.SWEnableFrameGen));
        assertEquals("Super-resolução", sidebarText(context, R.id.SWEnableFSR));
    }

    @Test public void localizedStatusKeepsRendererNamesAndNumbers() {
        Context context = portuguese();
        assertEquals("Vulkan ativo", context.getString(R.string.sidebar_1_s_active, "Vulkan"));
        assertEquals("Adaptativo: 60 FPS", context.getString(R.string.sidebar_adaptive_1_d_fps, 60));
        assertEquals("Desativado · Box64", context.getString(R.string.sidebar_off_1_s, "Box64"));
    }

    @Test public void captionRefreshPreservesControlStateAndDoesNotInvokeItsBinding() {
        Context context = portuguese();
        LinearLayout root = new LinearLayout(context);
        Switch toggle = new Switch(context);
        toggle.setTag("i18n/sidebar_lsfg_frame_generation");
        toggle.setText("LSFG Frame Generation");
        toggle.setChecked(true);
        int[] changes = {0};
        toggle.setOnCheckedChangeListener((button, checked) -> changes[0]++);
        root.addView(toggle);
        SidebarLocalization.refresh(root);
        assertEquals("Geração de quadros LSFG", toggle.getText().toString());
        assertTrue(toggle.isChecked());
        assertEquals(0, changes[0]);

        Configuration english = new Configuration(context.getResources().getConfiguration());
        english.setLocale(Locale.ENGLISH);
        context.getResources().updateConfiguration(english, context.getResources().getDisplayMetrics());
        SidebarLocalization.refresh(root);
        assertEquals("LSFG Frame Generation", toggle.getText().toString());
        assertTrue(toggle.isChecked());
        assertEquals(0, changes[0]);
    }
}
