package com.winlator.cmod.contentdialog;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import java.util.ArrayList;

/* loaded from: classes4.dex */
public class ContentDialog extends Dialog {
    private final View contentView;
    private View inflatedLayout;
    private boolean isDarkMode;
    private Runnable onCancelCallback;
    public Runnable onConfirmCallback;

    public ContentDialog(Context context) {
        this(context, 0);
    }

    public ContentDialog(Context context, int layoutResId) {
        super(context, R.style.ContentDialog);
        this.contentView = LayoutInflater.from(context).inflate(R.layout.content_dialog, (ViewGroup) null);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.isDarkMode = sharedPreferences.getBoolean("dark_mode", false);
        if (this.isDarkMode) {
            getContext().setTheme(2131820792);
        }
        if (layoutResId > 0) {
            FrameLayout frameLayout = (FrameLayout) this.contentView.findViewById(R.id.FrameLayout);
            frameLayout.setVisibility(0);
            View view = LayoutInflater.from(context).inflate(layoutResId, (ViewGroup) frameLayout, false);
            frameLayout.addView(view);
        }
        View confirmButton = this.contentView.findViewById(R.id.BTConfirm);
        confirmButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ContentDialog$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                ContentDialog.this.lambda$new$0(view2);
            }
        });
        View cancelButton = this.contentView.findViewById(R.id.BTCancel);
        cancelButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.ContentDialog$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                ContentDialog.this.lambda$new$1(view2);
            }
        });
        setContentView(this.contentView);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$0(View v) {
        if (this.onConfirmCallback != null) {
            this.onConfirmCallback.run();
        }
        dismiss();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$1(View v) {
        if (this.onCancelCallback != null) {
            this.onCancelCallback.run();
        }
        dismiss();
    }

    public View getInflatedLayout() {
        return this.inflatedLayout;
    }

    public View getContentView() {
        return this.contentView;
    }

    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    public void setOnCancelCallback(Runnable onCancelCallback) {
        this.onCancelCallback = onCancelCallback;
    }

    @Override // android.app.Dialog
    public void setTitle(int titleResId) {
        setTitle(getContext().getString(titleResId));
    }

    public void setIcon(int iconResId) {
        ImageView imageView = (ImageView) findViewById(R.id.IVIcon);
        imageView.setImageResource(iconResId);
        imageView.setVisibility(0);
    }

    public void setTitle(String title) {
        LinearLayout titleBar = (LinearLayout) findViewById(R.id.LLTitleBar);
        TextView tvTitle = (TextView) findViewById(R.id.TVTitle);
        if (title != null && !title.isEmpty()) {
            tvTitle.setText(title);
            titleBar.setVisibility(0);
        } else {
            tvTitle.setText("");
            titleBar.setVisibility(8);
        }
    }

    public void setBottomBarText(String bottomBarText) {
        TextView tvBottomBarText = (TextView) findViewById(R.id.TVBottomBarText);
        if (bottomBarText != null && !bottomBarText.isEmpty()) {
            tvBottomBarText.setText(bottomBarText);
            tvBottomBarText.setVisibility(0);
        } else {
            tvBottomBarText.setText("");
            tvBottomBarText.setVisibility(8);
        }
    }

    public void setMessage(int msgResId) {
        setMessage(getContext().getString(msgResId));
    }

    public void setMessage(String message) {
        TextView tvMessage = (TextView) findViewById(R.id.TVMessage);
        if (message != null && !message.isEmpty()) {
            tvMessage.setText(message);
            tvMessage.setVisibility(0);
        } else {
            tvMessage.setText("");
            tvMessage.setVisibility(8);
        }
    }

    public static void alert(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(8);
        dialog.show();
    }

    public static void alert(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(8);
        dialog.show();
    }

    public static void confirm(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void confirm(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void prompt(Context context, int titleResId, String defaultText, final Callback<String> callback) {
        ContentDialog dialog = new ContentDialog(context);
        final EditText editText = (EditText) dialog.findViewById(R.id.EditText);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = sharedPreferences.getBoolean("dark_mode", false);
        applyDarkThemeToEditText(editText, isDarkMode);
        editText.setHint(R.string.untitled);
        if (defaultText != null) {
            editText.setText(defaultText);
        }
        editText.setVisibility(0);
        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.ContentDialog$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ContentDialog.lambda$prompt$2(editText, callback);
            }
        });
        dialog.show();
    }

    static /* synthetic */ void lambda$prompt$2(EditText editText, Callback callback) {
        String text = editText.getText().toString().trim();
        if (!text.isEmpty()) {
            callback.call(text);
        }
    }

    private static void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {
        if (isDarkMode) {
            editText.setTextColor(-1);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(R.drawable.edit_text_dark);
        } else {
            editText.setTextColor(ViewCompat.MEASURED_STATE_MASK);
            editText.setHintTextColor(-7829368);
            editText.setBackgroundResource(R.drawable.edit_text);
        }
    }

    public static void showMultipleChoiceList(Context context, int titleResId, String[] items, final Callback<ArrayList<Integer>> callback) {
        ContentDialog dialog = new ContentDialog(context);
        final ListView listView = (ListView) dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(2);
        listView.setAdapter((ListAdapter) new ArrayAdapter(context, android.R.layout.simple_list_item_multiple_choice, items));
        listView.setVisibility(0);
        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.ContentDialog$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                ContentDialog.lambda$showMultipleChoiceList$3(listView, callback);
            }
        });
        dialog.show();
    }

    static /* synthetic */ void lambda$showMultipleChoiceList$3(ListView listView, Callback callback) {
        ArrayList<Integer> result = new ArrayList<>();
        SparseBooleanArray checkedItemPositions = listView.getCheckedItemPositions();
        for (int i = 0; i < checkedItemPositions.size(); i++) {
            if (checkedItemPositions.valueAt(i)) {
                result.add(Integer.valueOf(checkedItemPositions.keyAt(i)));
            }
        }
        callback.call(result);
    }

    public static void showSingleChoiceList(Context context, int titleResId, String[] items, final Callback<Integer> callback) {
        final ContentDialog dialog = new ContentDialog(context);
        dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(8);
        ListView listView = (ListView) dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(0);
        listView.setAdapter((ListAdapter) new ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, items));
        listView.setVisibility(0);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() { // from class: com.winlator.cmod.contentdialog.ContentDialog$$ExternalSyntheticLambda4
            @Override // android.widget.AdapterView.OnItemClickListener
            public final void onItemClick(AdapterView adapterView, View view, int i, long j) {
                ContentDialog.lambda$showSingleChoiceList$4(Callback.this, dialog, adapterView, view, i, j);
            }
        });
        dialog.setTitle(titleResId);
        dialog.show();
    }

    static /* synthetic */ void lambda$showSingleChoiceList$4(Callback callback, ContentDialog dialog, AdapterView parent, View view, int position, long id) {
        callback.call(Integer.valueOf(position));
        dialog.dismiss();
    }
}
