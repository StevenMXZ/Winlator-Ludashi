package com.winlator.cmod.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.winlator.cmod.R;

public class RenderingSidebarPolisherView extends View {
    private static final String[] VULKAN_UPSCALERS = {
            "SGSR", "FSR", "Lanczos 2", "Color Boost"
    };

    private boolean applied;

    public RenderingSidebarPolisherView(Context context) {
        super(context);
    }

    public RenderingSidebarPolisherView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RenderingSidebarPolisherView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::applyOnce);
    }

    private void applyOnce() {
        if (applied) return;
        applied = true;

        View root = getRootView();
        int onSurface = resolveColor(R.attr.ingameSidebarOnSurface, Color.WHITE);
        int onSurfaceVariant = resolveColor(R.attr.ingameSidebarOnSurfaceVariant, Color.LTGRAY);
        int primary = resolveColor(R.attr.ingameSidebarPrimary, Color.WHITE);
        int surfaceVariant = resolveColor(R.attr.ingameSidebarSurfaceVariant, 0xFF1A1A20);

        View standard = root.findViewById(R.id.LLStandardOptions);
        restoreCard(standard, 12);

        View frameGen = root.findViewById(R.id.LLFrameGenOptions);
        restoreCard(frameGen, 12);

        Switch superResolution = root.findViewById(R.id.SWEnableFSR);
        if (superResolution != null) {
            superResolution.setBackground(null);
            superResolution.setPadding(0, 0, 0, 0);
            superResolution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            superResolution.setTextColor(onSurface);
            ViewGroup.LayoutParams lp = superResolution.getLayoutParams();
            if (lp != null) {
                lp.height = dp(48);
                superResolution.setLayoutParams(lp);
            }

            int[][] states = new int[][] {
                    new int[] { android.R.attr.state_checked },
                    new int[] { }
            };
            superResolution.setThumbTintList(new ColorStateList(states,
                    new int[] { primary, onSurfaceVariant }));
            superResolution.setTrackTintList(new ColorStateList(states,
                    new int[] { primary, surfaceVariant }));
        }

        styleSpinnerRow(root.findViewById(R.id.SPUpscalerMode), onSurface);
        styleSpinnerRow(root.findViewById(R.id.SPPostFXMode), onSurface);

        restoreCard(root.findViewById(R.id.LLFrameGenOptions), 12);
        styleSwitch(root.findViewById(R.id.SWTuningFrameGen), onSurface, onSurfaceVariant, primary, surfaceVariant);
        styleSpinnerRow(root.findViewById(R.id.SPFrameGenEngine), onSurface);
        styleSpinnerRow(root.findViewById(R.id.SPTuningFrameGenMode), onSurface);
        styleSpinnerRow(root.findViewById(R.id.SPTuningFrameGenMultiplier), onSurface);
        styleSpinnerRow(root.findViewById(R.id.SPTuningFrameGenQuality), onSurface);
        TextView frameGenStats = root.findViewById(R.id.TVTuningFrameGenStats);
        if (frameGenStats != null) frameGenStats.setTextColor(onSurfaceVariant);
        postDelayed(() -> ensureVulkanUpscalers(getRootView(), 0), 100);

        TextView sharpnessHeader = root.findViewById(R.id.LBLSharpnessHeader);
        if (sharpnessHeader != null) {
            sharpnessHeader.setTextColor(onSurfaceVariant);
            sharpnessHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sharpnessHeader.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            ViewGroup.LayoutParams raw = sharpnessHeader.getLayoutParams();
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
                lp.topMargin = dp(8);
                sharpnessHeader.setLayoutParams(lp);
            }
        }

        if (standard instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) standard;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp != null && lp.height == 1) child.setAlpha(0.65f);
            }
        }
    }

    private void ensureVulkanUpscalers(View root, int attempt) {
        Spinner spinner = root.findViewById(R.id.SPUpscalerMode);
        if (spinner == null || spinner.getAdapter() == null || spinner.getAdapter().getCount() < 2) {
            if (attempt < 8) {
                postDelayed(() -> ensureVulkanUpscalers(getRootView(), attempt + 1), 75);
            }
            return;
        }

        if (spinner.getAdapter().getCount() < VULKAN_UPSCALERS.length) {
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                    getContext(), android.R.layout.simple_spinner_item, VULKAN_UPSCALERS) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    TextView view = (TextView) super.getView(position, convertView, parent);
                    view.setTextColor(Color.parseColor("#EEF7FF"));
                    view.setTextSize(14);
                    view.setSingleLine(true);
                    return view;
                }

                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                    view.setTextColor(Color.parseColor("#EEF7FF"));
                    view.setBackgroundColor(Color.parseColor("#0E2231"));
                    view.setTextSize(14);
                    return view;
                }
            };
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
        }

    }

    private void styleSwitch(Switch sw, int onSurface, int onSurfaceVariant, int primary, int surfaceVariant) {
        if (sw == null) return;
        sw.setBackground(null);
        sw.setPadding(0, 0, 0, 0);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setTextColor(onSurface);
        ViewGroup.LayoutParams lp = sw.getLayoutParams();
        if (lp != null) {
            lp.height = dp(48);
            sw.setLayoutParams(lp);
        }

        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] { }
        };
        sw.setThumbTintList(new ColorStateList(states, new int[] { primary, onSurfaceVariant }));
        sw.setTrackTintList(new ColorStateList(states, new int[] { primary, surfaceVariant }));
    }

    private void restoreCard(View view, int paddingDp) {
        if (!(view instanceof ViewGroup)) return;
        view.setBackgroundResource(R.drawable.sidebar_card);
        int p = dp(paddingDp);
        view.setPadding(p, p, p, p);
    }

    private void styleSpinnerRow(Spinner spinner, int onSurface) {
        if (spinner == null) return;
        spinner.setBackgroundColor(Color.TRANSPARENT);
        spinner.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        spinner.setPaddingRelative(dp(8), 0, 0, 0);

        View parent = spinner.getParent() instanceof View ? (View) spinner.getParent() : null;
        if (parent instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) parent;
            row.setBackground(null);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 0, 0, 0);
            ViewGroup.LayoutParams rowLp = row.getLayoutParams();
            if (rowLp != null) {
                rowLp.height = dp(48);
                row.setLayoutParams(rowLp);
            }

            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child instanceof TextView && child != spinner) {
                    TextView label = (TextView) child;
                    label.setTextColor(onSurface);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                }
            }
        }
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

