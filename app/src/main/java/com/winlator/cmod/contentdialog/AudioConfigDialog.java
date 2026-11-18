package com.winlator.cmod.contentdialog;

import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import com.winlator.cmod.R;

import android.content.Context;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;

public class AudioConfigDialog extends ContentDialog {
    public static String DEFAULT_CONFIG = "api=aaudio" + ",bufferSize=0" + ",latency=0";
    private Context context;
    private int bufferSize;
    private int latency;
    
    public AudioConfigDialog(View anchor, String audioDriver) {
        super(anchor.getContext(), R.layout.audio_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("Audio " + context.getString(R.string.configuration));
        
        final Spinner sAudioApi = findViewById(R.id.SAudioApi);
        final SeekBar sbBufferSize = findViewById(R.id.SBBufferSize);
        final TextView tvBufferSize = findViewById(R.id.TVBufferSize);
        final SeekBar sbLatency = findViewById(R.id.SBLatency);
        final TextView tvLatency = findViewById(R.id.TVLatency);
        
        final LinearLayout llBufferSize = findViewById(R.id.LLBufferSize);
        final LinearLayout llLatency = findViewById(R.id.LLatency);
        
        String tag = anchor.getTag().toString();
        KeyValueSet config = parseConfig(anchor.getTag());
        
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioApi, config.get("api"));
        
        sbBufferSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser)
                    return;
                    
                progress = progress / 512;
                progress = progress * 512;
                tvBufferSize.setText(progress + " B");
                bufferSize = progress;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        bufferSize = Integer.parseInt(config.get("bufferSize"));
        sbBufferSize.setProgress(bufferSize);
        tvBufferSize.setText(bufferSize + " B");
        
        sbLatency.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser)
                    return;
                    
                tvLatency.setText(progress + " ms");
                latency = progress;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        latency = Integer.parseInt(config.get("latency"));
        sbLatency.setProgress(latency);
        tvLatency.setText(latency + " ms");
        
        if (audioDriver.equals("pulseaudio")) {
            llBufferSize.setVisibility(View.GONE);
            llLatency.setVisibility(View.VISIBLE);
        }
        else {
            llBufferSize.setVisibility(View.VISIBLE);
            llLatency.setVisibility(View.GONE);
        }
            
        
        setOnConfirmCallback(() -> {
            config.put("api", StringUtils.parseIdentifier(sAudioApi.getSelectedItem().toString()));
            config.put("bufferSize", String.valueOf(bufferSize));
            config.put("latency", String.valueOf(latency));
            anchor.setTag(config.toString());
        });
    }
    
    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() :  DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String api = StringUtils.parseIdentifier(config.get("api"));
        if (api.equals("opensles"))
           envVars.put("ALSA_USE_SLES", "1");
           
        String bufferSize = config.get("bufferSize");
        if (!bufferSize.equals("0"))
            envVars.put("ALSA_BUFFER_SIZE", bufferSize);
    }   
}
