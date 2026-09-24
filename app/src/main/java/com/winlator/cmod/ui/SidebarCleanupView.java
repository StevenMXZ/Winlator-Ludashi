package com.winlator.cmod.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.WinlatorHUD;

import java.io.File;

public class SidebarCleanupView extends View implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG_HUD_OPTIONS = "winz-hud-options-v2";

    private int bindAttempts;
    private int hudBindAttempts;
    private boolean graphicsBound;
    private boolean hudExtrasBound;
    private boolean shortcutFileResolved;
    private File shortcutFile;

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
        postDelayed(this::bindGraphicsAutosave, 650);
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

        String storedMode = container.getExtra("hudMode");
        if (storedMode == null || storedMode.isEmpty()) {
            container.putExtra("hudMode", "2");
            container.saveData();
            if (style.getCount() > 1 && style.getSelectedItemPosition() != 1) {
                style.setSelection(1);
            }
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
            dualCell.setOnCheckedChangeListener((buttonView, isChecked) ->
                    hudPrefs.edit().putBoolean(WinlatorHUD.KEY_DUAL_CELL, isChecked).apply());
            metrics.addView(dualCell, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

            modernOptions.addView(metrics, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        hudExtrasBound = true;
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
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                WinlatorHUD.setOptionPreference(getContext(), bit, isChecked));
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
        View graph = root.findViewById(R.id.CBHudGraph);
        if (graph != null) graph.setVisibility(GONE);
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

    private void bindGraphicsAutosave() {
        if (graphicsBound) return;

        XServerDisplayActivity activity = findActivity();
        Container container = activity != null ? activity.getContainer() : null;
        if (activity == null || container == null) {
            retryBind();
            return;
        }

        View root = getRootView();
        Switch fsr = root.findViewById(R.id.SWEnableFSR);
        Spinner upscaler = root.findViewById(R.id.SPUpscalerMode);
        Spinner postFx = root.findViewById(R.id.SPPostFXMode);
        SeekBar sharpness = root.findViewById(R.id.SBSharpness);

        if (fsr == null || upscaler == null || postFx == null || sharpness == null
                || upscaler.getAdapter() == null || postFx.getAdapter() == null
                || upscaler.getOnItemSelectedListener() == null
                || postFx.getOnItemSelectedListener() == null) {
            retryBind();
            return;
        }

        resolveShortcutFile(activity);
        restoreShortcutGraphics(container, fsr, upscaler, postFx, sharpness);

        wrapSpinnerAutosave(upscaler);
        wrapSpinnerAutosave(postFx);

        fsr.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                postDelayed(this::saveGraphicsState, 40);
            }
            return false;
        });
        sharpness.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                postDelayed(this::saveGraphicsState, 40);
            }
            return false;
        });

        graphicsBound = true;
    }

    private void retryBind() {
        if (bindAttempts++ < 80) postDelayed(this::bindGraphicsAutosave, 100);
    }

    private void wrapSpinnerAutosave(Spinner spinner) {
        AdapterView.OnItemSelectedListener original = spinner.getOnItemSelectedListener();
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (original != null) original.onItemSelected(parent, view, position, id);
                postDelayed(SidebarCleanupView.this::saveGraphicsState, 25);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (original != null) original.onNothingSelected(parent);
            }
        });
    }

    private void restoreShortcutGraphics(Container container, Switch fsr, Spinner upscaler,
                                         Spinner postFx, SeekBar sharpness) {
        Shortcut store = openShortcutStore(container);
        if (store == null) return;

        String filterValue = store.getExtra("graphicsFilterMode", "");
        if (!filterValue.isEmpty()) {
            int filter = parseInt(filterValue, 0);
            if (filter > 0 && upscaler.getCount() > 0) {
                int position = Math.max(0, Math.min(upscaler.getCount() - 1, filter - 2));
                upscaler.setSelection(position, false);
                fsr.setChecked(true);
            } else {
                fsr.setChecked(false);
            }
        }

        String postValue = store.getExtra("graphicsPostFXMode", "");
        if (!postValue.isEmpty() && postFx.getCount() > 0) {
            int position = parseInt(postValue, 0);
            position = Math.max(0, Math.min(postFx.getCount() - 1, position));
            postFx.setSelection(position, false);
        }

        String sharpValue = store.getExtra("graphicsSharpness", "");
        if (!sharpValue.isEmpty()) {
            try {
                float value = Float.parseFloat(sharpValue);
                sharpness.setValue(Math.max(0f, Math.min(100f, value)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void saveGraphicsState() {
        if (!graphicsBound) return;

        XServerDisplayActivity activity = findActivity();
        Container container = activity != null ? activity.getContainer() : null;
        if (activity == null || container == null) return;

        View root = getRootView();
        Switch fsr = root.findViewById(R.id.SWEnableFSR);
        Spinner upscaler = root.findViewById(R.id.SPUpscalerMode);
        Spinner postFx = root.findViewById(R.id.SPPostFXMode);
        SeekBar sharpness = root.findViewById(R.id.SBSharpness);
        if (fsr == null || upscaler == null || postFx == null || sharpness == null) return;

        int upscalerPosition = Math.max(0, upscaler.getSelectedItemPosition());
        String filter = fsr.isChecked() ? String.valueOf(upscalerPosition + 2) : "0";
        String post = String.valueOf(Math.max(0, postFx.getSelectedItemPosition()));
        String sharp = String.valueOf(Math.round(sharpness.getValue()));

        Shortcut store = openShortcutStore(container);
        if (store != null) {
            store.putExtra("graphicsFilterMode", filter);
            store.putExtra("graphicsSharpness", sharp);
            store.putExtra("graphicsPostFXMode", post);
            store.putExtra("graphicsColorMode", "0");
            store.saveData();
        } else {
            container.putExtra("graphicsFilterMode", filter);
            container.putExtra("graphicsSharpness", sharp);
            container.putExtra("graphicsPostFXMode", post);
            container.putExtra("graphicsColorMode", "0");
            container.saveData();
        }
    }

    private void resolveShortcutFile(XServerDisplayActivity activity) {
        if (shortcutFileResolved) return;
        shortcutFileResolved = true;
        String path = activity.getIntent().getStringExtra("shortcut_path");
        if (path == null || path.isEmpty()) return;
        File candidate = new File(path);
        if (candidate.isFile()) shortcutFile = candidate;
    }

    private Shortcut openShortcutStore(Container container) {
        XServerDisplayActivity activity = findActivity();
        if (activity != null) resolveShortcutFile(activity);
        if (shortcutFile == null || !shortcutFile.isFile()) return null;
        return new Shortcut(container, shortcutFile);
    }

    private XServerDisplayActivity findActivity() {
        Context current = getContext();
        while (current instanceof ContextWrapper) {
            if (current instanceof XServerDisplayActivity) return (XServerDisplayActivity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof XServerDisplayActivity ? (XServerDisplayActivity) current : null;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
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
            updateHudSettingsRecursive(modernOptions);
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
