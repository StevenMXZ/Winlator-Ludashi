package com.winlator.cmod.ui;

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.view.menu.ActionMenuItemView;
import androidx.appcompat.widget.ActionMenuView;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.ShortcutsFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class LibraryToolbarActions {
    private static final int MENU_VIEW_MODE = 1;
    private static final int MENU_SEARCH = 2;
    private static final int MENU_FILE_MANAGER = 3;
    private static final int MENU_MORE = 4;
    private static final int[] DESIRED_ORDER = {
            MENU_SEARCH,
            MENU_FILE_MANAGER,
            MENU_VIEW_MODE,
            MENU_MORE
    };

    private static final Map<Toolbar, View.OnLayoutChangeListener> installed = new WeakHashMap<>();

    private LibraryToolbarActions() {}

    public static void install(MainActivity activity) {
        if (activity == null) return;
        Toolbar toolbar = activity.findViewById(R.id.Toolbar);
        if (toolbar == null) return;
        synchronized (installed) {
            if (installed.containsKey(toolbar)) {
                toolbar.post(() -> apply(activity, toolbar));
                return;
            }
            View.OnLayoutChangeListener listener = (v, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) ->
                    apply(activity, toolbar);
            installed.put(toolbar, listener);
            toolbar.addOnLayoutChangeListener(listener);
        }
        toolbar.post(() -> apply(activity, toolbar));
    }

    private static void apply(MainActivity activity, Toolbar toolbar) {
        Fragment current = activity.getSupportFragmentManager()
                .findFragmentById(R.id.FLFragmentContainer);
        if (!(current instanceof ShortcutsFragment)) return;

        Menu menu = toolbar.getMenu();
        MenuItem search = menu.findItem(MENU_SEARCH);
        MenuItem add = menu.findItem(MENU_FILE_MANAGER);
        MenuItem view = menu.findItem(MENU_VIEW_MODE);
        MenuItem more = menu.findItem(MENU_MORE);
        if (search == null || add == null || view == null || more == null) return;

        if (!(more.getActionView() instanceof OrientationOverflowView)) {
            more.setActionView(new OrientationOverflowView(activity));
            more.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            toolbar.post(() -> reorder(toolbar));
        } else {
            reorder(toolbar);
        }
    }

    private static void reorder(Toolbar toolbar) {
        ActionMenuView actionMenu = null;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof ActionMenuView) {
                actionMenu = (ActionMenuView) child;
                break;
            }
        }
        if (actionMenu == null) return;

        List<View> targetViews = new ArrayList<>(DESIRED_ORDER.length);
        for (int wantedId : DESIRED_ORDER) {
            View match = findActionView(actionMenu, wantedId);
            if (match == null) return;
            targetViews.add(match);
        }

        boolean alreadyOrdered = true;
        int lastIndex = -1;
        for (View view : targetViews) {
            int index = actionMenu.indexOfChild(view);
            if (index <= lastIndex) {
                alreadyOrdered = false;
                break;
            }
            lastIndex = index;
        }
        if (alreadyOrdered) return;

        List<ViewGroup.LayoutParams> params = new ArrayList<>(targetViews.size());
        for (View view : targetViews) {
            params.add(view.getLayoutParams());
            actionMenu.removeView(view);
        }
        for (int i = 0; i < targetViews.size(); i++) {
            actionMenu.addView(targetViews.get(i), i, params.get(i));
        }
    }

    private static View findActionView(ActionMenuView actionMenu, int itemId) {
        for (int i = 0; i < actionMenu.getChildCount(); i++) {
            View child = actionMenu.getChildAt(i);
            if (itemId == MENU_MORE && child instanceof OrientationOverflowView) return child;
            if (child instanceof ActionMenuItemView) {
                ActionMenuItemView itemView = (ActionMenuItemView) child;
                if (itemView.getItemData() != null && itemView.getItemData().getItemId() == itemId) {
                    return child;
                }
            }
        }
        return null;
    }
}
