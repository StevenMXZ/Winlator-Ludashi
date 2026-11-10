package com.winlator.cmod.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.widget.WinlatorHUD;

public class SidebarCleanupView extends View implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG_HUD_OPTIONS = "winz-hud-options-v2";

    private int hudBindAttempts;
    private boolean hudExtrasBound;
    private boolean syncingHudOptions;

    public SidebarCleanupView(Context context) {
        super(context);
    }

    public SidebarCleanupView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SidebarCleanupView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::polishUi);
        postDelayed(this::polishUi, 80);
        postDelayed(this::polishUi, 650);
        postDelayed(this::bindHudExtras, 650);
        getContext().getSharedPreferences(WinlatorHUD.PREFS, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getContext().getSharedPreferences(WinlatorHUD.PREFS, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    private void polishUi() {
        View root = getRootView();
        hideRenderingSectionLabels(root);

        View savePreset = root.findViewById(R.id.BTSaveGraphicsPreset);
        if (savePreset != null) savePreset.setVisibility(GONE);

        compactHudStyle(root);
        compactControlsSelector(root);
        tuneNeutralRail(root);
    }

    private void compactHudStyle(View root) {
        Spinner spinner = root.findViewById(R.id.SPHudStyle);
        if (spinner == null || !(spinner.getParent() instanceof LinearLayout)) return;

        LinearLayout row = (LinearLayout) spinner.getParent();
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView && child != spinner) {
                ViewGroup.LayoutParams raw = child.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    lp.weight = 0f;
                    child.setLayoutParams(lp);
                }
                break;
            }
        }

        ViewGroup.LayoutParams raw = spinner.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = dp(40);
            lp.weight = 0f;
            lp.setMarginStart(dp(12));
            spinner.setLayoutParams(lp);
        }
        spinner.setMinimumWidth(0);
        spinner.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        spinner.setPadding(dp(9), 0, dp(9), 0);
    }

    private void bindHudExtras() {
        if (hudExtrasBound) return;

        XServerDisplayActivity activity = findActivity();
        Container container = activity != null ? activity.getContainer() : null;
        View root = getRootView();
        Spinner style = root.findViewById(R.id.SPHudStyle);
        LinearLayout modernOptions = root.findViewById(R.id.LLModernHudOptions);

        if (activity == null || container == null || style == null || style.getAdapter() == null
                || modernOptions == null) {
            if (hudBindAttempts++ < 40) postDelayed(this::bindHudExtras, 100);
            return;
        }

        if (modernOptions.findViewWithTag(TAG_HUD_OPTIONS) == null) {
            hideLegacyHudRows(root);

            LinearLayout metrics = new LinearLayout(getContext());
            metrics.setTag(TAG_HUD_OPTIONS);
            metrics.setOrientation(LinearLayout.VERTICAL);

            addHudOptionRow(metrics,
                    "FPS", WinlatorHUD.SHOW_FPS,
                    "Renderer", WinlatorHUD.SHOW_RENDERER);
            addHudOptionRow(metrics,
                    "GPU Usage", WinlatorHUD.SHOW_GPU_USAGE,
                    "GPU Name", WinlatorHUD.SHOW_GPU_NAME);
            addHudOptionRow(metrics,
                    "CPU Usage", WinlatorHUD.SHOW_CPU_USAGE,
                    "CPU Temp", WinlatorHUD.SHOW_CPU_TEMP);
            addHudOptionRow(metrics,
                    "RAM", WinlatorHUD.SHOW_RAM,
                    "Power", WinlatorHUD.SHOW_POWER);
            addHudOptionRow(metrics,
                    "Battery Temp", WinlatorHUD.SHOW_BATTERY_TEMP,
                    "Charge State", WinlatorHUD.SHOW_CHARGE_STATE);

            SharedPreferences hudPrefs = getContext().getSharedPreferences(
                    WinlatorHUD.PREFS, Context.MODE_PRIVATE);
            Switch dualCell = new Switch(getContext());
            dualCell.setTag("dual_cell");
            dualCell.setText("Dual-cell correction");
            dualCell.setTextColor(resolveColor(R.attr.ingameSidebarOnSurface, 0xFFFFFFFF));
            dualCell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            dualCell.setGravity(Gravity.CENTER_VERTICAL);
            dualCell.setChecked(hudPrefs.getBoolean(WinlatorHUD.KEY_DUAL_CELL, false));
            dualCell.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!syncingHudOptions)
                    hudPrefs.edit().putBoolean(WinlatorHUD.KEY_DUAL_CELL, isChecked).apply();
            });
            metrics.addView(dualCell, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

            modernOptions.addView(metrics, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        hudExtrasBound = true;
        activity.refreshSidebarControls();
    }

    private void addHudOptionRow(LinearLayout parent, String leftLabel, int leftBit,
                                 String rightLabel, int rightBit) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(createHudMetricCheckBox(leftLabel, leftBit), weightedHudOptionParams());
        row.addView(createHudMetricCheckBox(rightLabel, rightBit), weightedHudOptionParams());
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private CheckBox createHudMetricCheckBox(String label, int bit) {
        CheckBox checkBox = new CheckBox(getContext());
        checkBox.setText(label);
        checkBox.setTextColor(resolveColor(R.attr.ingameSidebarOnSurface, 0xFFFFFFFF));
        checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        checkBox.setGravity(Gravity.CENTER_VERTICAL);
        checkBox.setPaddingRelative(0, 0, dp(4), 0);
        checkBox.setTag(bit);
        checkBox.setChecked(WinlatorHUD.isOptionEnabled(getContext(), bit));
        int primary = resolveColor(R.attr.ingameSidebarPrimary, 0xFFFFFFFF);
        int secondary = resolveColor(R.attr.ingameSidebarOnSurfaceVariant, 0xFFAAAAAA);
        checkBox.setButtonTintList(new android.content.res.ColorStateList(
                new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { primary, secondary }));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!syncingHudOptions) WinlatorHUD.setOptionPreference(getContext(), bit, isChecked);
        });
        return checkBox;
    }

    private LinearLayout.LayoutParams weightedHudOptionParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private void hideLegacyHudRows(View root) {
        hideParentRow(root.findViewById(R.id.CBHudFps));
        hideParentRow(root.findViewById(R.id.CBHudGpu));
        hideParentRow(root.findViewById(R.id.CBHudCpuRam));
        hideParentRow(root.findViewById(R.id.CBHudRam));
        hideParentRow(root.findViewById(R.id.CBHudBattTemp));
        hideParentRow(root.findViewById(R.id.CBHudRenderer));
    }

    private void hideParentRow(View child) {
        if (child == null) return;
        Object parent = child.getParent();
        if (parent instanceof View) ((View) parent).setVisibility(GONE);
        else child.setVisibility(GONE);
    }

    private void compactControlsSelector(View root) {
        Spinner spinner = root.findViewById(R.id.SPInputControlsProfile);
        if (spinner == null || !(spinner.getParent() instanceof LinearLayout)) return;

        LinearLayout row = (LinearLayout) spinner.getParent();
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        ViewGroup.LayoutParams raw = spinner.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.width = dp(142);
            lp.height = dp(40);
            lp.weight = 0f;
            spinner.setLayoutParams(lp);
        }
        spinner.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        spinner.setPaddingRelative(dp(9), 0, dp(8), 0);

        ImageView settings = root.findViewById(R.id.BTInputControlsSettings);
        if (settings != null) {
            ViewGroup.LayoutParams settingsRaw = settings.getLayoutParams();
            if (settingsRaw instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) settingsRaw;
                lp.setMarginStart(dp(8));
                settings.setLayoutParams(lp);
            }
        }
    }

    private void tuneNeutralRail(View root) {
        String theme = PreferenceManager.getDefaultSharedPreferences(
                getContext().getApplicationContext()).getString("winlator_ui_theme", "black");
        if (!"black".equals(theme) && !"amoled".equals(theme)) return;

        View rail = root.findViewById(R.id.IngameSidebarRail);
        if (rail == null) return;

        int surface = resolveColor(R.attr.ingameSidebarSurface,
                "amoled".equals(theme) ? 0xFF050505 : 0xFF121216);
        int edge = resolveColor(R.attr.ingameSidebarSurfaceVariant,
                "amoled".equals(theme) ? 0xFF0D0D0D : 0xFF1A1A20);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(surface);
        background.setStroke(dp(1), edge);
        background.setCornerRadius(dp(22));
        rail.setBackground(background);
        rail.setElevation(dp(8));
        rail.setTranslationZ(dp(2));
    }

    private XServerDisplayActivity findActivity() {
        Context current = getContext();
        while (current instanceof ContextWrapper) {
            if (current instanceof XServerDisplayActivity) return (XServerDisplayActivity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof XServerDisplayActivity ? (XServerDisplayActivity) current : null;
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }

    private void hideRenderingSectionLabels(View view) {
        Object tag = view.getTag();
        if (tag instanceof String) {
            String value = (String) tag;
            if ("winz-section-PERFORMANCE".equals(value)
                    || "winz-section-IMAGE QUALITY".equals(value)
                    || "winz-section-PRESETS".equals(value)) {
                view.setVisibility(GONE);
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideRenderingSectionLabels(group.getChildAt(i));
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (WinlatorHUD.KEY_SHOW.equals(key) || WinlatorHUD.KEY_DUAL_CELL.equals(key)) {
            post(this::syncHudCheckboxes);
        }
    }

    public void syncHudCheckboxes() {
        View root = getRootView();
        LinearLayout modernOptions = root.findViewById(R.id.LLModernHudOptions);
        if (modernOptions != null) {
            syncingHudOptions = true;
            try {
                updateHudSettingsRecursive(modernOptions);
            } finally {
                syncingHudOptions = false;
            }
            XServerDisplayActivity activity = findActivity();
            if (activity != null) activity.refreshSidebarControls();
        }
    }

    private void updateHudSettingsRecursive(ViewGroup parent) {
        SharedPreferences hudPrefs = getContext().getSharedPreferences(WinlatorHUD.PREFS, Context.MODE_PRIVATE);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof CheckBox && child.getTag() instanceof Integer) {
                int bit = (Integer) child.getTag();
                boolean shouldBeChecked = WinlatorHUD.isOptionEnabled(getContext(), bit);
                if (((CheckBox) child).isChecked() != shouldBeChecked) {
                    ((CheckBox) child).setChecked(shouldBeChecked);
                }
            } else if (child instanceof Switch && "dual_cell".equals(child.getTag())) {
                boolean enabled = hudPrefs.getBoolean(WinlatorHUD.KEY_DUAL_CELL, false);
                if (((Switch) child).isChecked() != enabled) {
                    ((Switch) child).setChecked(enabled);
                }
            } else if (child instanceof ViewGroup) {
                updateHudSettingsRecursive((ViewGroup) child);
            }
        }
    }
}
