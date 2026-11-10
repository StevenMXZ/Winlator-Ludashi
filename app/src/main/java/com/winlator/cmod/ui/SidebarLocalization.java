package com.winlator.cmod.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;

/** Refresh captions without rebuilding the sidebar's renderer/input bindings. */
public final class SidebarLocalization {
    private SidebarLocalization() {}

    public static void refresh(View view) {
        if (view == null) return;
        Object tag = view.getTag();
        if (view instanceof TextView && tag instanceof String && ((String) tag).startsWith("i18n/")) {
            int text = view.getResources().getIdentifier(((String) tag).substring(5),
                    "string", view.getContext().getPackageName());
            if (text != 0) ((TextView) view).setText(text);
        }
        if (view instanceof FpsLimiterControl) ((FpsLimiterControl) view).refreshLocale();
        else if (tag instanceof FpsLimiterControl) ((FpsLimiterControl) tag).refreshLocale();
        if (view instanceof Spinner) {
            Spinner spinner = (Spinner) view;
            View selected = spinner.getSelectedView();
            int position = spinner.getSelectedItemPosition();
            if (selected instanceof TextView && position >= 0 && spinner.getAdapter() != null)
                ((TextView) selected).setText(String.valueOf(spinner.getAdapter().getItem(position)));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) refresh(group.getChildAt(i));
        }
    }
}
