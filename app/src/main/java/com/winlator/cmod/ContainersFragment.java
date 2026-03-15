package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.ContainersFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.StorageInfoDialog;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.xenvironment.ImageFs;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* loaded from: classes8.dex */
public class ContainersFragment extends Fragment {
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;
    private RecyclerView recyclerView;

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        this.preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        this.manager = new ContainerManager(getContext());
        loadContainersList();
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(com.ludashi.benchmark.R.string.containers);
    }

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(com.ludashi.benchmark.R.layout.containers_fragment, container, false);
        this.recyclerView = (RecyclerView) frameLayout.findViewById(com.ludashi.benchmark.R.id.RecyclerView);
        this.emptyTextView = (TextView) frameLayout.findViewById(com.ludashi.benchmark.R.id.TVEmptyText);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this.recyclerView.getContext()));
        this.recyclerView.addItemDecoration(new DividerItemDecoration(this.recyclerView.getContext(), 1));
        return frameLayout;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadContainersList() {
        ArrayList<Container> containers = this.manager.getContainers();
        this.recyclerView.setAdapter(new ContainersAdapter(containers));
        if (containers.isEmpty()) {
            this.emptyTextView.setVisibility(0);
        }
    }

    @Override // androidx.fragment.app.Fragment
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menu.clear();
        menuInflater.inflate(com.ludashi.benchmark.R.menu.containers_menu, menu);
        MenuItem bigPictureItem = menu.findItem(com.ludashi.benchmark.R.id.action_big_picture_mode);
        Drawable icon = bigPictureItem.getIcon();
        if (icon != null) {
            icon.mutate();
            icon.setColorFilter(-1, PorterDuff.Mode.SRC_IN);
        }
    }

    @Override // androidx.fragment.app.Fragment
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case com.ludashi.benchmark.R.id.action_big_picture_mode /* 2131296696 */:
                toggleBigPictureMode();
                return true;
            case com.ludashi.benchmark.R.id.containers_menu_add /* 2131296766 */:
                if (!ImageFs.find(getContext()).isValid()) {
                    return false;
                }
                FragmentManager fragmentManager = getParentFragmentManager();
                fragmentManager.beginTransaction().setCustomAnimations(com.ludashi.benchmark.R.anim.slide_in_up, com.ludashi.benchmark.R.anim.slide_out_down, com.ludashi.benchmark.R.anim.slide_in_down, com.ludashi.benchmark.R.anim.slide_out_up).addToBackStack(null).replace(com.ludashi.benchmark.R.id.FLFragmentContainer, new ContainerDetailFragment()).commit();
                return true;
            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    private void toggleBigPictureMode() {
        Intent intent = new Intent(getContext(), (Class<?>) BigPictureActivity.class);
        startActivity(intent);
        getActivity().overridePendingTransition(com.ludashi.benchmark.R.anim.fade_in, com.ludashi.benchmark.R.anim.fade_out);
    }

    /* JADX INFO: Access modifiers changed from: private */
    class ContainersAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView imageView;
            private final ImageView menuButton;
            private final ImageView runButton;
            private final TextView title;

            private ViewHolder(View view) {
                super(view);
                this.runButton = (ImageView) view.findViewById(com.ludashi.benchmark.R.id.BTRun);
                this.imageView = (ImageView) view.findViewById(com.ludashi.benchmark.R.id.ImageView);
                this.title = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVTitle);
                this.menuButton = (ImageView) view.findViewById(com.ludashi.benchmark.R.id.BTMenu);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(com.ludashi.benchmark.R.layout.container_list_item, parent, false));
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onViewRecycled(ViewHolder holder) {
            holder.runButton.setOnClickListener(null);
            holder.menuButton.setOnClickListener(null);
            super.onViewRecycled((ContainersAdapter) holder);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(ViewHolder holder, int position) {
            final Container item = this.data.get(position);
            holder.imageView.setImageResource(com.ludashi.benchmark.R.drawable.icon_container);
            holder.title.setText(item.getName());
            holder.runButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainersFragment$ContainersAdapter$$ExternalSyntheticLambda5
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ContainersFragment.ContainersAdapter.this.lambda$onBindViewHolder$0(item, view);
                }
            });
            holder.menuButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ContainersFragment$ContainersAdapter$$ExternalSyntheticLambda6
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ContainersFragment.ContainersAdapter.this.lambda$onBindViewHolder$1(item, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(Container item, View view) {
            runContainer(item);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public final int getItemCount() {
            return this.data.size();
        }

        private void runContainer(Container container) {
            Context context = ContainersFragment.this.getContext();
            if (!XrActivity.isEnabled(ContainersFragment.this.getContext())) {
                Intent intent = new Intent(context, (Class<?>) XServerDisplayActivity.class);
                intent.putExtra("container_id", container.id);
                ContainersFragment.this.requireActivity().startActivity(intent);
                return;
            }
            XrActivity.openIntent(ContainersFragment.this.getActivity(), container.id, null);
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* renamed from: showListItemMenu, reason: merged with bridge method [inline-methods] */
        public void lambda$onBindViewHolder$1(View anchorView, final Container container) {
            final Context context = ContainersFragment.this.getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            listItemMenu.inflate(com.ludashi.benchmark.R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= 29) {
                listItemMenu.setForceShowIcon(true);
            }
            listItemMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.winlator.cmod.ContainersFragment$ContainersAdapter$$ExternalSyntheticLambda1
                @Override // android.widget.PopupMenu.OnMenuItemClickListener
                public final boolean onMenuItemClick(MenuItem menuItem) {
                    boolean lambda$showListItemMenu$6;
                    lambda$showListItemMenu$6 = ContainersFragment.ContainersAdapter.this.lambda$showListItemMenu$6(container, context, menuItem);
                    return lambda$showListItemMenu$6;
                }
            });
            listItemMenu.show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ boolean lambda$showListItemMenu$6(final Container container, final Context context, MenuItem menuItem) {
            switch (menuItem.getItemId()) {
                case com.ludashi.benchmark.R.id.container_duplicate /* 2131296762 */:
                    ContentDialog.confirm(ContainersFragment.this.getContext(), com.ludashi.benchmark.R.string.do_you_want_to_duplicate_this_container, new Runnable() { // from class: com.winlator.cmod.ContainersFragment$ContainersAdapter$$ExternalSyntheticLambda2
                        @Override // java.lang.Runnable
                        public final void run() {
                            ContainersFragment.ContainersAdapter.this.lambda$showListItemMenu$3(container);
                        }
                    });
                    break;
                case com.ludashi.benchmark.R.id.container_edit /* 2131296763 */:
                    FragmentManager fragmentManager = ContainersFragment.this.getParentFragmentManager();
                    fragmentManager.beginTransaction().setCustomAnimations(com.ludashi.benchmark.R.anim.slide_in_up, com.ludashi.benchmark.R.anim.slide_out_down, com.ludashi.benchmark.R.anim.slide_in_down, com.ludashi.benchmark.R.anim.slide_out_up).addToBackStack(null).replace(com.ludashi.benchmark.R.id.FLFragmentContainer, new ContainerDetailFragment(container.id)).commit();
                    break;
                case com.ludashi.benchmark.R.id.container_info /* 2131296764 */:
                    new StorageInfoDialog(ContainersFragment.this.getActivity(), container).show();
                    break;
                case com.ludashi.benchmark.R.id.container_remove /* 2131296765 */:
                    ContentDialog.confirm(ContainersFragment.this.getContext(), com.ludashi.benchmark.R.string.do_you_want_to_remove_this_container, new Runnable() { // from class: com.winlator.cmod.ContainersFragment$ContainersAdapter$$ExternalSyntheticLambda3
                        @Override // java.lang.Runnable
                        public final void run() {
                            ContainersFragment.ContainersAdapter.this.lambda$showListItemMenu$5(container, context);
                        }
                    });
                    break;
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$showListItemMenu$3(Container container) {
            ContainersFragment.this.preloaderDialog.lambda$showOnUiThread$0(com.ludashi.benchmark.R.string.duplicating_container);
            ContainersFragment.this.manager.duplicateContainerAsync(container, new Runnable() { // from class: com.winlator.cmod.ContainersFragment$ContainersAdapter$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    ContainersFragment.ContainersAdapter.this.lambda$showListItemMenu$2();
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$showListItemMenu$2() {
            ContainersFragment.this.preloaderDialog.close();
            ContainersFragment.this.loadContainersList();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$showListItemMenu$5(Container container, Context context) {
            ContainersFragment.this.preloaderDialog.lambda$showOnUiThread$0(com.ludashi.benchmark.R.string.removing_container);
            Iterator<Shortcut> it = ContainersFragment.this.manager.loadShortcuts().iterator();
            while (it.hasNext()) {
                Shortcut shortcut = it.next();
                if (shortcut.container == container) {
                    ShortcutsFragment.disableShortcutOnScreen(context, shortcut);
                }
            }
            ContainersFragment.this.manager.removeContainerAsync(container, new Runnable() { // from class: com.winlator.cmod.ContainersFragment$ContainersAdapter$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    ContainersFragment.ContainersAdapter.this.lambda$showListItemMenu$4();
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$showListItemMenu$4() {
            ContainersFragment.this.preloaderDialog.close();
            ContainersFragment.this.loadContainersList();
        }
    }
}
