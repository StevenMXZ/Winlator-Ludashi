package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.ludashi.benchmark.R;
import com.winlator.cmod.contentdialog.RepositoryManagerDialog;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/* loaded from: classes4.dex */
public class RepositoryManagerDialog {
    private RepoAdapter adapter;
    private final Context context;
    private AlertDialog dialog;
    private Runnable onGlobalDismissCallback;
    private RecyclerView recyclerView;
    private final List<DriverRepo> repos = new ArrayList();

    public RepositoryManagerDialog(Context context) {
        this.context = context;
        loadRepos();
    }

    public void setOnDismissCallback(Runnable callback) {
        this.onGlobalDismissCallback = callback;
    }

    public void show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this.context);
        builder.setTitle("Driver Sources");
        this.recyclerView = new RecyclerView(this.context);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this.context));
        this.recyclerView.addItemDecoration(new DividerItemDecoration(this.context, 1));
        this.recyclerView.setPadding(0, 10, 0, 10);
        this.adapter = new RepoAdapter();
        this.recyclerView.setAdapter(this.adapter);
        builder.setView(this.recyclerView);
        builder.setPositiveButton("Add Source", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.contentdialog.RepositoryManagerDialog$$ExternalSyntheticLambda0
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                RepositoryManagerDialog.this.lambda$show$0(dialogInterface, i);
            }
        });
        builder.setNegativeButton("Close", (DialogInterface.OnClickListener) null);
        this.dialog = builder.create();
        this.dialog.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$show$0(DialogInterface d, int w) {
        showRepoDialog(null, -1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showRepoDialog(final DriverRepo repoToEdit, final int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this.context);
        builder.setTitle(repoToEdit == null ? "Add Repository" : "Edit Repository");
        final EditText inputName = new EditText(this.context);
        inputName.setHint("Name (e.g. Kimchi Turnip)");
        if (repoToEdit != null) {
            inputName.setText(repoToEdit.name);
        }
        final EditText inputUrl = new EditText(this.context);
        inputUrl.setHint("GitHub API URL");
        if (repoToEdit != null) {
            inputUrl.setText(repoToEdit.apiUrl);
        }
        LinearLayout layout = new LinearLayout(this.context);
        layout.setOrientation(1);
        layout.setPadding(50, 30, 50, 30);
        layout.addView(inputName);
        layout.addView(inputUrl);
        builder.setView(layout);
        builder.setPositiveButton("Save", new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.contentdialog.RepositoryManagerDialog$$ExternalSyntheticLambda1
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                RepositoryManagerDialog.this.lambda$showRepoDialog$1(inputName, inputUrl, repoToEdit, position, dialogInterface, i);
            }
        });
        builder.setNegativeButton("Cancel", (DialogInterface.OnClickListener) null);
        builder.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showRepoDialog$1(EditText inputName, EditText inputUrl, DriverRepo repoToEdit, int position, DialogInterface d, int w) {
        String name = inputName.getText().toString().trim();
        String url = inputUrl.getText().toString().trim();
        if (url.startsWith("https://github.com/") && !url.contains("api.github.com")) {
            url = url.replace("https://github.com/", "https://api.github.com/repos/");
            if (!url.endsWith("/releases")) {
                url = url + "/releases";
            }
        }
        if (!name.isEmpty() && !url.isEmpty()) {
            if (repoToEdit == null) {
                this.repos.add(new DriverRepo(name, url));
            } else {
                repoToEdit.name = name;
                repoToEdit.apiUrl = url;
                this.repos.set(position, repoToEdit);
            }
            saveRepos();
            this.adapter.notifyDataSetChanged();
        }
    }

    private void loadRepos() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        String jsonStr = prefs.getString("custom_driver_repos", "");
        this.repos.clear();
        if (jsonStr.isEmpty()) {
            this.repos.add(new DriverRepo("K11MCH1 Turnip Drivers", "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases"));
            this.repos.add(new DriverRepo("StevenMX Turnip Drivers", "https://api.github.com/repos/StevenMXZ/freedreno_turnip-CI/releases"));
            this.repos.add(new DriverRepo("Snapdragon Elite Drivers", "https://api.github.com/repos/StevenMXZ/Adrenotools-Drivers/releases"));
            this.repos.add(new DriverRepo("Weab-Chan Turnip Drivers", "https://api.github.com/repos/Weab-chan/freedreno_turnip-CI/releases"));
            return;
        }
        try {
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                this.repos.add(DriverRepo.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveRepos() {
        try {
            JSONArray array = new JSONArray();
            for (DriverRepo repo : this.repos) {
                array.put(repo.toJson());
            }
            PreferenceManager.getDefaultSharedPreferences(this.context).edit().putString("custom_driver_repos", array.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    class RepoAdapter extends RecyclerView.Adapter<ViewHolder> {
        private RepoAdapter() {
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.adrenotools_list_item, parent, false);
            return new ViewHolder(v);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(final ViewHolder holder, final int position) {
            final DriverRepo repo = (DriverRepo) RepositoryManagerDialog.this.repos.get(position);
            holder.title.setText(repo.name);
            holder.subtitle.setText(repo.apiUrl);
            holder.actionButton.setImageResource(R.drawable.icon_settings);
            holder.itemView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.RepositoryManagerDialog$RepoAdapter$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    RepositoryManagerDialog.RepoAdapter.this.lambda$onBindViewHolder$0(repo, view);
                }
            });
            holder.actionButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.RepositoryManagerDialog$RepoAdapter$$ExternalSyntheticLambda1
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    RepositoryManagerDialog.RepoAdapter.this.lambda$onBindViewHolder$2(holder, repo, position, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(DriverRepo repo, View v) {
            DriverDownloadDialog driverDialog = new DriverDownloadDialog(RepositoryManagerDialog.this.context, repo.apiUrl);
            driverDialog.setOnDismissCallback(RepositoryManagerDialog.this.onGlobalDismissCallback);
            driverDialog.show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$2(ViewHolder holder, final DriverRepo repo, final int position, View v) {
            PopupMenu popup = new PopupMenu(RepositoryManagerDialog.this.context, holder.actionButton);
            popup.getMenu().add("Edit");
            popup.getMenu().add("Delete");
            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.contentdialog.RepositoryManagerDialog$RepoAdapter$$ExternalSyntheticLambda2
                @Override // android.widget.PopupMenu.OnMenuItemClickListener
                public final boolean onMenuItemClick(MenuItem menuItem) {
                    boolean lambda$onBindViewHolder$1;
                    lambda$onBindViewHolder$1 = RepositoryManagerDialog.RepoAdapter.this.lambda$onBindViewHolder$1(repo, position, menuItem);
                    return lambda$onBindViewHolder$1;
                }
            });
            popup.show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ boolean lambda$onBindViewHolder$1(DriverRepo repo, int position, MenuItem item) {
            if (item.getTitle().equals("Edit")) {
                RepositoryManagerDialog.this.showRepoDialog(repo, position);
                return true;
            }
            if (item.getTitle().equals("Delete")) {
                RepositoryManagerDialog.this.repos.remove(position);
                RepositoryManagerDialog.this.saveRepos();
                notifyDataSetChanged();
                return true;
            }
            return true;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return RepositoryManagerDialog.this.repos.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageButton actionButton;
            TextView subtitle;
            TextView title;

            ViewHolder(View v) {
                super(v);
                this.title = (TextView) v.findViewById(R.id.TVName);
                this.subtitle = (TextView) v.findViewById(R.id.TVVersion);
                this.actionButton = (ImageButton) v.findViewById(R.id.BTMenu);
            }
        }
    }
}
