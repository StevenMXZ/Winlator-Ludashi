package com.winlator.cmod;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.ui.container.ContainerCreateComposeFragment;
import com.winlator.cmod.ui.containers.ContainerUiModel;
import com.winlator.cmod.ui.containers.ContainersCallbacks;
import com.winlator.cmod.ui.containers.ContainersComposeHost;

import java.util.ArrayList;

public class ContainersFragment extends Fragment {
    private ContainersComposeHost composeHost;
    private static final int MENU_ADD_CONTAINER = 0x434e01;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull android.view.Menu menu, @NonNull android.view.MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        if (getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            menu.add(android.view.Menu.NONE, MENU_ADD_CONTAINER, 0, R.string.add)
                    .setIcon(R.drawable.ui_ic_add)
                    .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == MENU_ADD_CONTAINER) {
            openEditor(new ContainerCreateComposeFragment());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        composeHost = new ContainersComposeHost(requireContext(), new ContainersCallbacks() {
            @Override
            public void onAdd() {
                openEditor(new ContainerCreateComposeFragment());
            }

            @Override
            public void onRun(int containerId) {
                if (!XrActivity.isEnabled(requireContext())) {
                    startActivity(new Intent(requireContext(), XServerDisplayActivity.class)
                            .putExtra("container_id", containerId));
                } else {
                    XrActivity.openIntent(requireActivity(), containerId, null);
                }
            }

            @Override
            public void onEdit(int containerId) {
                openEditor(ContainerCreateComposeFragment.forEdit(containerId));
            }

            @Override
            public void onDuplicate(int containerId) {
                ContainerManager manager = new ContainerManager(requireContext());
                Container container = manager.getContainerById(containerId);
                if (container == null) return;
                manager.duplicateContainerAsync(container, () -> {
                    if (!isAdded()) return;
                    refreshContainers();
                    Toast.makeText(requireContext(), com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_container_duplicated), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onRemove(int containerId) {
                ContainerManager manager = new ContainerManager(requireContext());
                Container container = manager.getContainerById(containerId);
                if (container == null) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_remove_container))
                        .setMessage(container.getName() + com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_and_its_files_will_be_deleted))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.remove), (dialog, which) ->
                                manager.removeContainerAsync(container, () -> {
                                    if (!isAdded()) return;
                                    refreshContainers();
                                    Toast.makeText(requireContext(), com.winlator.cmod.ui.UiStrings.get(com.winlator.cmod.R.string.ui_container_removed), Toast.LENGTH_SHORT).show();
                                }))
                        .show();
            }

            @Override
            public void onStorage(int containerId) {
                Container container = new ContainerManager(requireContext()).getContainerById(containerId);
                if (container != null) com.winlator.cmod.ui.containers.ContainerStorageDialog.show((AppCompatActivity) requireActivity(), container);
            }


        });
        return composeHost.createView();
    }

    private void openEditor(Fragment editor) {
        getParentFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.winz_screen_enter, R.anim.winz_screen_exit, R.anim.winz_screen_enter, R.anim.winz_screen_exit)
                .replace(R.id.FLFragmentContainer, editor)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDetailMode(false);
        }
        if (getActivity() instanceof AppCompatActivity &&
                ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.containers);
        }
        refreshContainers();
    }

    @Override
    public void onDestroyView() {
        composeHost = null;
        super.onDestroyView();
    }

    private void refreshContainers() {
        if (!isAdded() || composeHost == null) return;
        ContainerManager manager = new ContainerManager(requireContext());
        ArrayList<ContainerUiModel> items = new ArrayList<>();
        for (Container container : manager.getContainers()) {
            items.add(new ContainerUiModel(
                    container.id,
                    container.getName(),
                    container.getWineVersion(),
                    container.getScreenSize(),
                    container.getUseDisplayX() ? "DisplayX" : container.isRendererNative() ? "EGL" : "Vulkan",
                    container.getRootDir().getPath()));
        }
        composeHost.submitList(items);
    }
}
