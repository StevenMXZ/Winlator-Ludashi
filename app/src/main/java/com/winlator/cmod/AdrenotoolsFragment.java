package com.winlator.cmod;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.AdrenotoolsFragment;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.RepositoryManagerDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import java.util.ArrayList;

/* loaded from: classes8.dex */
public class AdrenotoolsFragment extends Fragment {
    private AdrenotoolsManager adrenotoolsManager;
    private RecyclerView recyclerView;

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        this.adrenotoolsManager = new AdrenotoolsManager(getActivity());
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(com.ludashi.benchmark.R.layout.adrenotools_fragment, container, false);
        this.recyclerView = (RecyclerView) layout.findViewById(com.ludashi.benchmark.R.id.RecyclerView);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this.recyclerView.getContext()));
        this.recyclerView.addItemDecoration(new DividerItemDecoration(this.recyclerView.getContext(), 1));
        this.recyclerView.setAdapter(new DriversAdapter(this.adrenotoolsManager.enumarateInstalledDrivers()));
        View btInstallDriver = layout.findViewById(com.ludashi.benchmark.R.id.BTInstallDriver);
        btInstallDriver.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.AdrenotoolsFragment$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                AdrenotoolsFragment.this.lambda$onCreateView$1(view);
            }
        });
        return layout;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$1(View v) {
        ContentDialog.confirm(getContext(), getString(com.ludashi.benchmark.R.string.install_drivers_message) + " " + getString(com.ludashi.benchmark.R.string.install_drivers_warning), new Runnable() { // from class: com.winlator.cmod.AdrenotoolsFragment$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                AdrenotoolsFragment.this.lambda$onCreateView$0();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreateView$0() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        getActivity().startActivityFromFragment(this, intent, 2);
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(com.ludashi.benchmark.R.string.adrenotools_gpu_drivers);
    }

    @Override // androidx.fragment.app.Fragment
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 2 && resultCode == -1) {
            Uri uri = data.getData();
            String driver = this.adrenotoolsManager.installDriver(uri);
            if (!driver.isEmpty()) {
                ((DriversAdapter) this.recyclerView.getAdapter()).addItem(driver);
            }
        }
    }

    @Override // androidx.fragment.app.Fragment
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        MenuItem item = menu.add(0, 1, 0, "Baixar");
        item.setIcon(android.R.drawable.stat_sys_download);
        item.setShowAsAction(2);
    }

    @Override // androidx.fragment.app.Fragment
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            RepositoryManagerDialog repoDialog = new RepositoryManagerDialog(getContext());
            repoDialog.setOnDismissCallback(new Runnable() { // from class: com.winlator.cmod.AdrenotoolsFragment$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    AdrenotoolsFragment.this.lambda$onOptionsItemSelected$2();
                }
            });
            repoDialog.show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onOptionsItemSelected$2() {
        if (this.recyclerView != null && (this.recyclerView.getAdapter() instanceof DriversAdapter)) {
            ((DriversAdapter) this.recyclerView.getAdapter()).reloadList();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    class DriversAdapter extends RecyclerView.Adapter<ViewHolder> {
        private ArrayList<String> driversList;

        public class ViewHolder extends RecyclerView.ViewHolder {
            private ImageButton btMenu;
            private TextView tvName;
            private TextView tvVersion;

            public ViewHolder(View v) {
                super(v);
                this.tvName = (TextView) v.findViewById(com.ludashi.benchmark.R.id.TVName);
                this.tvVersion = (TextView) v.findViewById(com.ludashi.benchmark.R.id.TVVersion);
                this.btMenu = (ImageButton) v.findViewById(com.ludashi.benchmark.R.id.BTMenu);
            }
        }

        public DriversAdapter(ArrayList<String> driversList) {
            this.driversList = driversList;
        }

        public void reloadList() {
            this.driversList = AdrenotoolsFragment.this.adrenotoolsManager.enumarateInstalledDrivers();
            notifyDataSetChanged();
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(com.ludashi.benchmark.R.layout.adrenotools_list_item, viewGroup, false);
            return new ViewHolder(view);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(ViewHolder viewHolder, final int position) {
            viewHolder.tvName.setText(AdrenotoolsFragment.this.adrenotoolsManager.getDriverName(this.driversList.get(position)));
            viewHolder.tvVersion.setText(AdrenotoolsFragment.this.adrenotoolsManager.getDriverVersion(this.driversList.get(position)));
            viewHolder.btMenu.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.AdrenotoolsFragment$DriversAdapter$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    AdrenotoolsFragment.DriversAdapter.this.lambda$onBindViewHolder$0(position, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(int position, View v) {
            removeAtIndex(position);
        }

        public void addItem(String item) {
            this.driversList.add(item);
            notifyItemInserted(getItemCount() - 1);
        }

        public void removeAtIndex(int index) {
            String deletedDriver = this.driversList.remove(index);
            AdrenotoolsFragment.this.adrenotoolsManager.removeDriver(deletedDriver);
            notifyItemRemoved(index);
            notifyItemRangeChanged(index, getItemCount());
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return this.driversList.size();
        }
    }
}
