package com.winlator.cmod.contentdialog;

import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;
import com.winlator.cmod.R;
import android.content.Context;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.KeyValueSet;

public class EGLConfigDialog extends ContentDialog {
    public static String DEFAULT_CONFIG = "surfaceFormat=bgra8" + ",textureFilter=linear";
    private Context context;
    
    public EGLConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.egl_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("EGL " + context.getString(R.string.configuration));
       
        final Spinner sSurfaceFormat = findViewById(R.id.SSurfaceFormat);
        final Spinner sTextureFilter = findViewById(R.id.STextureFilter);
        
        String tag = anchor.getTag().toString();
        KeyValueSet config = parseConfig(anchor.getTag());
        
        AppUtils.setSpinnerSelectionFromIdentifier(sSurfaceFormat, config.get("surfaceFormat"));
        AppUtils.setSpinnerSelectionFromIdentifier(sTextureFilter, config.get("textureFilter"));
        
        setOnConfirmCallback(() -> {
            config.put("surfaceFormat", sSurfaceFormat.getSelectedItem().toString());
            config.put("textureFilter", sTextureFilter.getSelectedItem().toString());
            anchor.setTag(config.toString());
        });
    }
    
    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() :  DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }
}
