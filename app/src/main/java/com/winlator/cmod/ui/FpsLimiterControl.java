package com.winlator.cmod.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.widget.XServerRendererView;

import java.io.File;

public class FpsLimiterControl extends LinearLayout {
    private static final String EXTRA_ENABLED = "nativeFpsLimiterEnabled";
    private static final String EXTRA_LIMIT = "nativeFpsLimit";
    private static final int SLIDER_MAX_FPS = 120;
    private static final int STEP_FPS = 5;
    private static final int CUSTOM_POSITION = SLIDER_MAX_FPS / STEP_FPS;

    private final SeekBar slider;
    private final TextView valueLabel;
    private final TextView title;
    private final NumericEditText customValue;

    private boolean initializing = true;
    private boolean stateLoaded;
    private int bindAttempts;
    private boolean shortcutFileResolved;
    private File shortcutFile;

    public FpsLimiterControl(Context context) {
        this(context, null);
    }

    public FpsLimiterControl(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);

        final int primary = resolveColor(R.attr.ingameSidebarPrimary, 0xFFF4F4F6);
        final int onSurface = resolveColor(R.attr.ingameSidebarOnSurface, 0xFFF5F5F7);
        final int onSurfaceVariant = resolveColor(R.attr.ingameSidebarOnSurfaceVariant, 0xFFA8A9B1);
        final int surfaceVariant = resolveColor(R.attr.ingameSidebarSurfaceVariant, 0xFF1C1D23);

        LinearLayout sliderGroup = new LinearLayout(context);
        sliderGroup.setOrientation(VERTICAL);
        sliderGroup.setPadding(0, dp(2), 0, dp(2));
        sliderGroup.setBackground(null);
        addView(sliderGroup, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout valueRow = new LinearLayout(context);
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderGroup.addView(valueRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        title = new TextView(context);
        title.setText(R.string.sidebar_fps_limit);
        title.setTextColor(onSurface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueRow.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        valueLabel = new TextView(context);
        valueLabel.setTextColor(onSurfaceVariant);
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valueLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        // FPS numbers retain Latin digits; mode labels follow the app language.
        // Keep its text direction LTR while the row itself still mirrors normally in RTL.
        valueLabel.setTextDirection(View.TEXT_DIRECTION_LTR);
        valueLabel.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        valueLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueLabel.setMinWidth(dp(64));
        valueRow.addView(valueLabel, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        slider = new SeekBar(context);
        slider.setMax(CUSTOM_POSITION);
        slider.setProgressTintList(ColorStateList.valueOf(primary));
        slider.setThumbTintList(ColorStateList.valueOf(primary));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(surfaceVariant));
        LayoutParams sliderParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(38));
        sliderParams.topMargin = dp(7);
        sliderGroup.addView(slider, sliderParams);

        customValue = new NumericEditText(context);
        customValue.setSingleLine(true);
        customValue.setInputType(InputType.TYPE_CLASS_NUMBER);
        customValue.setImeOptions(EditorInfo.IME_ACTION_DONE);
        customValue.setHint(R.string.sidebar_enter_custom_fps);
        customValue.setTextColor(onSurface);
        customValue.setHintTextColor(onSurfaceVariant);
        customValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        customValue.setBackgroundResource(R.drawable.sidebar_spinner);
        customValue.setPadding(dp(12), 0, dp(12), 0);
        customValue.setFocusable(true);
        customValue.setFocusableInTouchMode(true);
        customValue.setClickable(true);
        customValue.setCursorVisible(true);
        customValue.setShowSoftInputOnFocus(true);
        customValue.setVisibility(GONE);
        LayoutParams customParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        customParams.topMargin = dp(8);
        sliderGroup.addView(customValue, customParams);

        customValue.setOnTouchListener((v, event) -> {
            disallowParentIntercept(v);
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                customValue.requestFocus();
                post(this::showCustomKeyboard);
            }
            return false;
        });
        customValue.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) post(this::showCustomKeyboard);
        });
        customValue.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveAndApply();
                hideCustomKeyboard();
                customValue.clearFocus();
                return true;
            }
            return false;
        });

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateLimitUi();
                if (fromUser && !initializing) applyCurrentLimit();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!initializing) saveAndApply();
                if (seekBar.getProgress() == CUSTOM_POSITION) {
                    customValue.requestFocus();
                    postDelayed(FpsLimiterControl.this::showCustomKeyboard, 80);
                } else {
                    hideCustomKeyboard();
                    customValue.clearFocus();
                }
            }
        });

        customValue.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!initializing) saveAndApply();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        updateLimitUi();
        post(this::bindWhenReady);
    }

    private void bindWhenReady() {
        XServerDisplayActivity activity = findActivity();
        if (activity == null) return;

        if (!stateLoaded && activity.getContainer() != null) {
            resolveShortcutFile(activity);
            loadState(activity.getContainer());
            stateLoaded = true;
        }

        XServerRendererView renderer = activity.getXServerView();
        if (stateLoaded && renderer != null) {
            applyEffectiveLimit(renderer);
            postDelayed(() -> {
                XServerDisplayActivity host = findActivity();
                if (host != null && host.getXServerView() != null) {
                    applyEffectiveLimit(host.getXServerView());
                }
            }, 600);
            return;
        }

        if (bindAttempts++ < 80) postDelayed(this::bindWhenReady, 100);
    }

    private void loadState(Container container) {
        initializing = true;

        String savedLimit;
        String oldPreset;
        String oldEnabled;

        Shortcut shortcutStore = openShortcutStore(container);
        if (shortcutStore != null) {
            savedLimit = shortcutStore.getExtra(EXTRA_LIMIT, "");
            oldPreset = shortcutStore.getExtra("graphicsFpsPreset", "");
            oldEnabled = shortcutStore.getExtra(EXTRA_ENABLED, "");

            if (savedLimit.isEmpty() && oldPreset.isEmpty() && oldEnabled.isEmpty()) {
                savedLimit = container.getExtra(EXTRA_LIMIT, "");
                oldPreset = container.getExtra("graphicsFpsPreset", "");
                oldEnabled = container.getExtra(EXTRA_ENABLED, "");
            }
        } else {
            savedLimit = container.getExtra(EXTRA_LIMIT, "");
            oldPreset = container.getExtra("graphicsFpsPreset", "");
            oldEnabled = container.getExtra(EXTRA_ENABLED, "");
        }

        int limit = parsePositiveOrZero(savedLimit);
        if (savedLimit.isEmpty()) {
            int oldIndex = parsePositiveOrZero(oldPreset);
            int[] oldValues = {0, 30, 60, 90, 120};
            limit = oldIndex >= 0 && oldIndex < oldValues.length ? oldValues[oldIndex] : 0;
        }

        if ("0".equals(oldEnabled)) limit = 0;

        if (limit >= SLIDER_MAX_FPS || (limit > 0 && limit % STEP_FPS != 0)) {
            slider.setProgress(CUSTOM_POSITION);
            customValue.setText(limit > 0 ? String.valueOf(limit) : "");
        } else {
            slider.setProgress(Math.max(0, limit) / STEP_FPS);
            customValue.setText("");
        }

        updateLimitUi();
        initializing = false;
    }

    private void saveAndApply() {
        XServerDisplayActivity activity = findActivity();
        if (activity == null) return;

        int chosen = getChosenLimit();
        Container container = activity.getContainer();
        if (container != null) {
            resolveShortcutFile(activity);
            Shortcut shortcutStore = openShortcutStore(container);
            if (shortcutStore != null) {
                shortcutStore.putExtra(EXTRA_ENABLED, null);
                shortcutStore.putExtra(EXTRA_LIMIT, String.valueOf(chosen));
                shortcutStore.saveData();
            } else {
                container.putExtra(EXTRA_ENABLED, null);
                container.putExtra(EXTRA_LIMIT, String.valueOf(chosen));
                container.saveData();
            }
        }

        XServerRendererView renderer = activity.getXServerView();
        if (renderer != null) renderer.setFpsLimit(chosen);
    }

    private void applyCurrentLimit() {
        XServerDisplayActivity activity = findActivity();
        if (activity != null && activity.getXServerView() != null) {
            activity.getXServerView().setFpsLimit(getChosenLimit());
        }
    }

    private void applyEffectiveLimit(XServerRendererView renderer) {
        renderer.setFpsLimit(getChosenLimit());
    }

    private int getChosenLimit() {
        int position = slider.getProgress();
        if (position <= 0) return 0;
        if (position < CUSTOM_POSITION) return position * STEP_FPS;

        int custom = parsePositiveOrZero(customValue.getText().toString());
        return custom > 0 ? custom : SLIDER_MAX_FPS;
    }

    public void refreshLocale() {
        title.setText(R.string.sidebar_fps_limit);
        customValue.setHint(R.string.sidebar_enter_custom_fps);
        updateLimitUi();
    }

    private void updateLimitUi() {
        int position = slider.getProgress();
        boolean customMode = position >= CUSTOM_POSITION;
        customValue.setVisibility(customMode ? VISIBLE : GONE);

        if (position <= 0) valueLabel.setText(R.string.ui_off);
        else if (customMode) valueLabel.setText(R.string.profile_custom);
        else valueLabel.setText((position * STEP_FPS) + " FPS");
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
        if (shortcutFile == null || !shortcutFile.isFile()) return null;
        return new Shortcut(container, shortcutFile);
    }

    private void showCustomKeyboard() {
        if (customValue.getVisibility() != VISIBLE) return;
        XServerDisplayActivity activity = findActivity();
        if (activity != null) {
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        customValue.requestFocus();
        customValue.setSelection(customValue.length());
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(customValue, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideCustomKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(customValue.getWindowToken(), 0);
    }

    private int parsePositiveOrZero(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }

    private XServerDisplayActivity findActivity() {
        Context current = getContext();
        while (current instanceof ContextWrapper) {
            if (current instanceof XServerDisplayActivity) return (XServerDisplayActivity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof XServerDisplayActivity ? (XServerDisplayActivity) current : null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void disallowParentIntercept(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
            parent = parent.getParent();
        }
    }

    private static final class NumericEditText extends EditText {
        NumericEditText(Context context) {
            super(context);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection base = super.onCreateInputConnection(outAttrs);
            if (base == null) return null;
            return new InputConnectionWrapper(base, true) {
                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int unicode = event.getUnicodeChar();
                        if (unicode >= '0' && unicode <= '9') {
                            replaceSelection(String.valueOf((char) unicode));
                            return true;
                        }
                        if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                            deleteBeforeCursor();
                            return true;
                        }
                    }
                    return super.sendKeyEvent(event);
                }
            };
        }

        private void replaceSelection(String text) {
            Editable editable = getText();
            int start = Math.max(0, getSelectionStart());
            int end = Math.max(0, getSelectionEnd());
            int min = Math.min(start, end);
            int max = Math.max(start, end);
            editable.replace(min, max, text);
            setSelection(Math.min(editable.length(), min + text.length()));
        }

        private void deleteBeforeCursor() {
            Editable editable = getText();
            int start = Math.max(0, getSelectionStart());
            int end = Math.max(0, getSelectionEnd());
            int min = Math.min(start, end);
            int max = Math.max(start, end);
            if (max > min) {
                editable.delete(min, max);
                setSelection(min);
            } else if (min > 0) {
                editable.delete(min - 1, min);
                setSelection(min - 1);
            }
        }
    }
}
