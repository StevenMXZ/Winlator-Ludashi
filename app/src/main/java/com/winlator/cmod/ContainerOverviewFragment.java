package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.ui.container.ContainerAdvancedComposeDialog;
import com.winlator.cmod.ui.container.ContainerOverviewCallbacks;
import com.winlator.cmod.ui.container.ContainerOverviewComposeHost;
import com.winlator.cmod.ui.container.ContainerOverviewLandscapeComposeHost;
import com.winlator.cmod.ui.container.ContainerOverviewModel;

public class ContainerOverviewFragment extends Fragment {
    private final int containerId;
    private Container container;
    private FrameLayout overviewRoot;
    private ContainerOverviewCallbacks callbacks;

    public ContainerOverviewFragment() {
        this(0);
    }

    public ContainerOverviewFragment(int containerId) {
        this.containerId = containerId;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        container = new ContainerManager(requireContext()).getContainerById(containerId);
        if (container == null) {
            getParentFragmentManager().popBackStack();
            return new View(requireContext());
        }
        callbacks = createCallbacks();
        overviewRoot = new FrameLayout(requireContext());
        renderOverview();
        return overviewRoot;
    }

    private ContainerOverviewCallbacks createCallbacks() {
        return new ContainerOverviewCallbacks() {
            @NonNull
            @Override
            public View createEmbeddedSection(@NonNull String section) {
                final int sectionId = ContainerOverviewComposeHost.SECTION_VIDEO.equals(section)
                        ? ContainerSectionFragment.VIDEO
                        : ContainerSectionFragment.AUDIO;
                final String tag = "container-inline-" + section;
                FrameLayout host = new FrameLayout(requireContext());
                int hostId = View.generateViewId();
                host.setId(hostId);
                host.post(() -> {
                    if (!isAdded()) return;
                    Fragment old = getChildFragmentManager().findFragmentByTag(tag);
                    if (old != null) {
                        getChildFragmentManager().beginTransaction().remove(old).commitNowAllowingStateLoss();
                    }
                    getChildFragmentManager().beginTransaction()
                            .replace(hostId, new EmbeddedContainerSectionFragment(container.id, sectionId), tag)
                            .commitNowAllowingStateLoss();
                });
                return host;
            }

            @Override
            public void onAdvanced() {
                ContainerAdvancedComposeDialog.show(requireContext(), container.id, () -> {
                    container = new ContainerManager(requireContext()).getContainerById(containerId);
                });
            }

            @Override
            public void onManageComponents() {
                Intent intent = new Intent(requireContext(), OnboardingActivity.class);
                intent.putExtra(OnboardingActivity.EXTRA_COMPONENT_MANAGER, true);
                startActivity(intent);
            }

            @Override
            public void onInstallComponent(@NonNull String type, @NonNull String version) {
                Intent intent = new Intent(requireContext(), OnboardingActivity.class);
                intent.putExtra(OnboardingActivity.EXTRA_COMPONENT_MANAGER, true);
                intent.putExtra(OnboardingActivity.EXTRA_AUTO_INSTALL_TYPE, type);
                intent.putExtra(OnboardingActivity.EXTRA_AUTO_INSTALL_VERSION, version);
                startActivity(intent);
            }

            @Override
            public void onSaved() {
                container = new ContainerManager(requireContext()).getContainerById(containerId);
            }

            @Override
            public void onLaunch() {
                runContainer();
            }
        };
    }

    private void renderOverview() {
        if (!isAdded() || overviewRoot == null || callbacks == null) return;
        container = new ContainerManager(requireContext()).getContainerById(containerId);
        if (container == null) return;

        ContainerOverviewModel model = new ContainerOverviewModel(
                container.id,
                container.getName(),
                container.getWineVersion(),
                container.isRendererNative() ? "EGL" : "Vulkan",
                container.getScreenSize(),
                Container.getAudioDriverLabel(container.getAudioDriver()),
                container.getDXWrapper(),
                container.getGraphicsDriver(),
                container.getEmulator(),
                container.getRootDir().getPath()
        );

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        View content = landscape
                ? ContainerOverviewLandscapeComposeHost.create(requireContext(), model, callbacks)
                : ContainerOverviewComposeHost.create(requireContext(), model, callbacks);
        overviewRoot.removeAllViews();
        overviewRoot.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overviewRoot != null) overviewRoot.post(this::renderOverview);
    }

    private void runContainer() {
        Activity activity = requireActivity();
        if (!XrActivity.isEnabled(requireContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, container.id, null);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDetailMode(true);
        }
        if (((AppCompatActivity) requireActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.configure_container);
        }
    }
}
