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
                    Toast.makeText(requireContext(), "Container duplicated", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onRemove(int containerId) {
                ContainerManager manager = new ContainerManager(requireContext());
                Container container = manager.getContainerById(containerId);
                if (container == null) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle("Remove container?")
                        .setMessage(container.getName() + " and its files will be deleted.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Remove", (dialog, which) ->
                                manager.removeContainerAsync(container, () -> {
                                    if (!isAdded()) return;
                                    refreshContainers();
                                    Toast.makeText(requireContext(), "Container removed", Toast.LENGTH_SHORT).show();
                                }))
                        .show();
            }

            @Override
            public void onInfo(int containerId) {
                getParentFragmentManager().beginTransaction()
                        .replace(R.id.FLFragmentContainer, new ContainerOverviewFragment(containerId))
                        .addToBackStack(null)
                        .commit();
            }
        });
        return composeHost.createView();
    }

    private void openEditor(Fragment editor) {
        getParentFragmentManager().beginTransaction()
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
                    container.getScreenSize()));
        }
        composeHost.submitList(items);
    }
}
