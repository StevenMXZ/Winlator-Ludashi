package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.widget.EnvVarsView;

/* loaded from: classes4.dex */
public class AddEnvVarDialog extends ContentDialog {
    public AddEnvVarDialog(final Context context, final EnvVarsView envVarsView) {
        super(context, R.layout.add_env_var_dialog);
        final EditText etName = (EditText) findViewById(R.id.ETName);
        final EditText etValue = (EditText) findViewById(R.id.ETValue);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        applyDarkThemeToEditText(etName, isDarkMode);
        applyDarkThemeToEditText(etValue, isDarkMode);
        setTitle(context.getString(R.string.new_environment_variable));
        setIcon(R.drawable.icon_env_var);
        findViewById(R.id.BTMenu).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.AddEnvVarDialog$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                AddEnvVarDialog.lambda$new$1(context, etName, view);
            }
        });
        setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.contentdialog.AddEnvVarDialog$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                AddEnvVarDialog.lambda$new$2(etName, etValue, envVarsView);
            }
        });
    }

    static /* synthetic */ void lambda$new$1(Context context, final EditText etName, View v) {
        PopupMenu popupMenu = new PopupMenu(context, v);
        Menu menu = popupMenu.getMenu();
        for (String[] knownEnvVar : EnvVarsView.knownEnvVars) {
            menu.add(knownEnvVar[0]);
        }
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.contentdialog.AddEnvVarDialog$$ExternalSyntheticLambda2
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public final boolean onMenuItemClick(MenuItem menuItem) {
                return AddEnvVarDialog.lambda$new$0(etName, menuItem);
            }
        });
        popupMenu.show();
    }

    static /* synthetic */ boolean lambda$new$0(EditText etName, MenuItem menuItem) {
        etName.setText(menuItem.getTitle());
        return true;
    }

    static /* synthetic */ void lambda$new$2(EditText etName, EditText etValue, EnvVarsView envVarsView) {
        String name = etName.getText().toString().trim().replace(" ", "");
        String value = etValue.getText().toString().trim().replace(" ", "");
        if (!name.isEmpty() && !envVarsView.containsName(name)) {
            envVarsView.add(name, value);
        }
    }

    private void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {
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
}
