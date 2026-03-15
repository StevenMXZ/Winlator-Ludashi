package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.ludashi.benchmark.R;
import com.winlator.cmod.contentdialog.DriverDownloadDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.Downloader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes4.dex */
public class DriverDownloadDialog {
    private final AdrenotoolsManager adrenotoolsManager;
    private final Context context;
    private AlertDialog dialog;
    private Runnable onDismissCallback;
    private RecyclerView recyclerView;
    private final String repoUrl;

    public DriverDownloadDialog(Context context, String repoUrl) {
        this.context = context;
        this.adrenotoolsManager = new AdrenotoolsManager(context);
        this.repoUrl = repoUrl;
    }

    public void setOnDismissCallback(Runnable callback) {
        this.onDismissCallback = callback;
    }

    public void show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this.context);
        builder.setTitle("Available Drivers");
        this.recyclerView = new RecyclerView(this.context);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this.context));
        this.recyclerView.addItemDecoration(new DividerItemDecoration(this.context, 1));
        builder.setView(this.recyclerView);
        builder.setNegativeButton("Back", (DialogInterface.OnClickListener) null);
        this.dialog = builder.create();
        this.dialog.show();
        fetchDrivers();
    }

    private void fetchDrivers() {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                DriverDownloadDialog.this.lambda$fetchDrivers$2();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$fetchDrivers$2() {
        String str;
        String jsonStr;
        JSONArray array;
        String str2 = "assets";
        String jsonStr2 = Downloader.downloadString(this.repoUrl);
        if (jsonStr2 == null) {
            runOnUi(new Runnable() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    DriverDownloadDialog.this.lambda$fetchDrivers$0();
                }
            });
            return;
        }
        final List<ReleaseItem> releases = new ArrayList<>();
        try {
            JSONArray array2 = new JSONArray(jsonStr2);
            int i = 0;
            while (i < array2.length()) {
                JSONObject releaseObj = array2.getJSONObject(i);
                String rawName = releaseObj.optString("name", releaseObj.optString("tag_name", "Unknown Driver"));
                String cleanName = cleanDriverName(rawName);
                String description = releaseObj.optString("body", "");
                List<DriverAsset> assets = new ArrayList<>();
                if (releaseObj.has(str2)) {
                    JSONArray assetsArr = releaseObj.getJSONArray(str2);
                    int j = 0;
                    while (true) {
                        str = str2;
                        if (j >= assetsArr.length()) {
                            break;
                        }
                        JSONObject asset = assetsArr.getJSONObject(j);
                        String jsonStr3 = jsonStr2;
                        try {
                            String url = asset.getString("browser_download_url");
                            JSONArray array3 = array2;
                            String filename = asset.optString("name", "driver.zip");
                            if (!url.endsWith(".zip") && !url.endsWith(".tzst")) {
                                j++;
                                str2 = str;
                                jsonStr2 = jsonStr3;
                                array2 = array3;
                            }
                            assets.add(new DriverAsset(filename, url));
                            j++;
                            str2 = str;
                            jsonStr2 = jsonStr3;
                            array2 = array3;
                        } catch (Exception e) {
                            e = e;
                            e.printStackTrace();
                            runOnUi(new Runnable() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda6
                                @Override // java.lang.Runnable
                                public final void run() {
                                    DriverDownloadDialog.this.lambda$fetchDrivers$1(releases);
                                }
                            });
                        }
                    }
                    jsonStr = jsonStr2;
                    array = array2;
                } else {
                    str = str2;
                    jsonStr = jsonStr2;
                    array = array2;
                    if (releaseObj.has("url")) {
                        assets.add(new DriverAsset(cleanName + ".zip", releaseObj.getString("url")));
                    }
                }
                if (!assets.isEmpty()) {
                    releases.add(new ReleaseItem(cleanName, description, assets));
                }
                i++;
                str2 = str;
                jsonStr2 = jsonStr;
                array2 = array;
            }
        } catch (Exception e2) {
            e = e2;
        }
        runOnUi(new Runnable() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                DriverDownloadDialog.this.lambda$fetchDrivers$1(releases);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$fetchDrivers$0() {
        Toast.makeText(this.context, "Connection failed!", 0).show();
    }

    private String cleanDriverName(String raw) {
        String clean = raw.replace("Mesa Turnip driver ", "").replace("Mesa Turnip ", "").replace("Qualcomm Driver ", "");
        return clean.trim();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onDownloadClick(final ReleaseItem item) {
        if (item.assets.isEmpty()) {
            return;
        }
        if (item.assets.size() == 1) {
            startDownload(item.assets.get(0));
            return;
        }
        String[] assetNames = new String[item.assets.size()];
        for (int i = 0; i < item.assets.size(); i++) {
            assetNames[i] = item.assets.get(i).name;
        }
        new AlertDialog.Builder(this.context).setTitle("Select Variant").setItems(assetNames, new DialogInterface.OnClickListener() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda1
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i2) {
                DriverDownloadDialog.this.lambda$onDownloadClick$3(item, dialogInterface, i2);
            }
        }).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onDownloadClick$3(ReleaseItem item, DialogInterface dialogInterface, int which) {
        startDownload(item.assets.get(which));
    }

    private void startDownload(final DriverAsset asset) {
        Toast.makeText(this.context, "Downloading " + asset.name + "...", 0).show();
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                DriverDownloadDialog.this.lambda$startDownload$6(asset);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startDownload$6(DriverAsset asset) {
        try {
            final File tmpFile = new File(this.context.getCacheDir(), "driver_temp.zip");
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            boolean success = Downloader.downloadFile(asset.url, tmpFile);
            if (success) {
                final Uri fileUri = Uri.fromFile(tmpFile);
                runOnUi(new Runnable() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda3
                    @Override // java.lang.Runnable
                    public final void run() {
                        DriverDownloadDialog.this.lambda$startDownload$4(fileUri, tmpFile);
                    }
                });
            } else {
                runOnUi(new Runnable() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$$ExternalSyntheticLambda4
                    @Override // java.lang.Runnable
                    public final void run() {
                        DriverDownloadDialog.this.lambda$startDownload$5();
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startDownload$4(Uri fileUri, File tmpFile) {
        String installedName = this.adrenotoolsManager.installDriver(fileUri);
        if (!installedName.isEmpty()) {
            Toast.makeText(this.context, "Installed: " + installedName, 0).show();
            if (this.onDismissCallback != null) {
                this.onDismissCallback.run();
            }
        } else {
            Toast.makeText(this.context, "Installation failed! Invalid ZIP.", 1).show();
        }
        tmpFile.delete();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startDownload$5() {
        Toast.makeText(this.context, "Download failed!", 0).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: setupAdapter, reason: merged with bridge method [inline-methods] */
    public void lambda$fetchDrivers$1(List<ReleaseItem> releases) {
        if (releases.isEmpty()) {
            Toast.makeText(this.context, "No drivers found.", 1).show();
        } else {
            this.recyclerView.setAdapter(new DriverAdapter(releases));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static class ReleaseItem {
        List<DriverAsset> assets;
        String description;
        String name;

        ReleaseItem(String n, String d, List<DriverAsset> a) {
            this.name = n;
            this.description = d;
            this.assets = a;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static class DriverAsset {
        String name;
        String url;

        DriverAsset(String n, String u) {
            this.name = n;
            this.url = u;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    class DriverAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<ReleaseItem> list;

        public DriverAdapter(List<ReleaseItem> list) {
            this.list = list;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.adrenotools_list_item, parent, false);
            return new ViewHolder(v);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ReleaseItem item = this.list.get(position);
            holder.title.setText(item.name);
            if (item.assets.size() > 1) {
                holder.subtitle.setText(item.assets.size() + " variants available (Click to choose)");
            } else {
                String shortDesc = item.description.replace("\n", " ").trim();
                if (shortDesc.length() > 50) {
                    shortDesc = shortDesc.substring(0, 50) + "...";
                }
                if (shortDesc.isEmpty()) {
                    shortDesc = "No description";
                }
                holder.subtitle.setText(shortDesc);
            }
            holder.actionButton.setImageResource(android.R.drawable.stat_sys_download);
            holder.actionButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.contentdialog.DriverDownloadDialog$DriverAdapter$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    DriverDownloadDialog.DriverAdapter.this.lambda$onBindViewHolder$0(item, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(ReleaseItem item, View v) {
            DriverDownloadDialog.this.onDownloadClick(item);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return this.list.size();
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

    private void runOnUi(Runnable action) {
        if (this.context instanceof Activity) {
            ((Activity) this.context).runOnUiThread(action);
        }
    }
}
