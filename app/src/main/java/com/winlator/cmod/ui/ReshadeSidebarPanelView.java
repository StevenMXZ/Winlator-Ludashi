package com.winlator.cmod.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.widget.VulkanRendererControls;

public class ReshadeSidebarPanelView extends FrameLayout {
    private static final String[] EFFECTS = {
            "Off",
            "Game Clarity",
            "Cinematic",
            "Vivid",
            "Competitive",
            "Adaptive Sharpen",
            "Filmic",
            "Arcade",
            "Retro CRT",
            "Upscale Sharp",
            "Pixel Clean",
            "Anime Edge"
    };

    private static final int[] EFFECT_MODES = {
            0, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20
    };

    private boolean wired;
    private int selectedEffect;
    private float strength = 0.65f;
    private TextView strengthValue;

    public ReshadeSidebarPanelView(Context context) {
        super(context);
    }

    public ReshadeSidebarPanelView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }

    public ReshadeSidebarPanelView(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::wireOnce);
    }

    private void wireOnce() {
        if (wired) return;
        wired = true;

        final View root = getRootView();
        Spinner spinner = findViewById(R.id.SPReshadeEffect);
        if (spinner != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    getContext(), android.R.layout.simple_spinner_item, EFFECTS);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setSelection(0, false);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedEffect = position;
                    applyEffect(root);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        strengthValue = findViewById(R.id.TVReshadeStrengthValue);
        android.widget.SeekBar strengthBar = findViewById(R.id.SBReshadeStrength);
        if (strengthBar != null) {
            int primary = resolveColor(R.attr.ingameSidebarPrimary, 0xFFF2F2F4);
            int surfaceVariant = resolveColor(R.attr.ingameSidebarSurfaceVariant, 0xFF1A1A20);
            strengthBar.setProgressTintList(ColorStateList.valueOf(primary));
            strengthBar.setThumbTintList(ColorStateList.valueOf(primary));
            strengthBar.setProgressBackgroundTintList(ColorStateList.valueOf(surfaceVariant));
            strengthBar.setMax(100);
            strengthBar.setProgress(65);
            updateStrengthLabel(65);
            strengthBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    strength = progress / 100.0f;
                    updateStrengthLabel(progress);
                    VulkanRendererControls renderer = findVulkanRenderer(root);
                    if (renderer != null && selectedEffect > 0) renderer.setSharpness(strength);
                }

                @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });
        }
    }

    private void updateStrengthLabel(int progress) {
        if (strengthValue != null) strengthValue.setText(progress + "%");
    }

    private void applyEffect(View root) {
        VulkanRendererControls renderer = findVulkanRenderer(root);
        if (renderer == null) return;

        if (selectedEffect <= 0 || selectedEffect >= EFFECT_MODES.length) {
            renderer.setPostFXMode(0);
            return;
        }

        View fsr = root.findViewById(R.id.SWEnableFSR);
        if (fsr instanceof Switch && ((Switch) fsr).isChecked()) {
            ((Switch) fsr).setChecked(false);
        }

        renderer.setFilterMode(0);
        renderer.setSharpness(strength);
        renderer.setPostFXMode(EFFECT_MODES[selectedEffect]);
    }

    private VulkanRendererControls findVulkanRenderer(View view) {
        if (view instanceof VulkanRendererControls) return (VulkanRendererControls) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                VulkanRendererControls found = findVulkanRenderer(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }
}
