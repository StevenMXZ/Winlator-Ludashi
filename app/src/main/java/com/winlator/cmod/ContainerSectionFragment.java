package com.winlator.cmod;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.ui.container.ContainerSectionCallbacks;
import com.winlator.cmod.ui.container.ContainerSectionComposeHost;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ContainerSectionFragment extends Fragment {
    public static final int VIDEO = 1;
    public static final int AUDIO = 2;
    public static final int COMPATIBILITY = 3;

    private final int containerId;
    private final int section;
    private Container container;
    private final OkHttpClient http = new OkHttpClient();
    private final ArrayList<String> remoteGraphicsVersions = new ArrayList<>();

    public ContainerSectionFragment() {
        this(0, VIDEO);
    }

    public ContainerSectionFragment(int containerId, int section) {
        this.containerId = containerId;
        this.section = section;
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

        FrameLayout root = new FrameLayout(requireContext());
        ContentsManager contentsManager = new ContentsManager(requireContext());
        contentsManager.syncContents();
        root.addView(buildSectionView(contentsManager), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        loadRemoteProfiles(contentsManager, root);
        return root;
    }

    private View buildSectionView(ContentsManager contentsManager) {
        String[] rendererEntries = new String[]{"Vulkan", "EGL", "DisplayX"};
        String[] screenEntries = getResources().getStringArray(R.array.screen_size_entries);
        String[] graphicsEntries = getResources().getStringArray(R.array.graphics_driver_entries);
        String[] audioEntries = getResources().getStringArray(R.array.audio_driver_entries);
        String[] wrapperEntries = getResources().getStringArray(R.array.dxwrapper_entries);
        String[] emulatorEntries = getResources().getStringArray(R.array.emulator_entries);
        String[] dxvkEntries = collectComponentVersions(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                getResources().getStringArray(R.array.dxvk_version_entries),
                getConfigValue(container.getDXWrapperConfig(), "version")
        );
        String[] installedDxvkEntries = collectInstalledComponentVersions(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                getResources().getStringArray(R.array.dxvk_version_entries),
                getConfigValue(container.getDXWrapperConfig(), "version")
        );
        String[] vkd3dEntries = collectComponentVersions(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                getResources().getStringArray(R.array.vkd3d_version_entries),
                getConfigValue(container.getDXWrapperConfig(), "vkd3dVersion")
        );
        String[] installedVkd3dEntries = collectInstalledComponentVersions(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                getResources().getStringArray(R.array.vkd3d_version_entries),
                getConfigValue(container.getDXWrapperConfig(), "vkd3dVersion")
        );
        LinkedHashSet<String> graphicsVersions = new LinkedHashSet<>(Arrays.asList(
                getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries)
        ));
        LinkedHashSet<String> installedGraphicsVersions = new LinkedHashSet<>(graphicsVersions);
        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(requireContext());
        ArrayList<String> rendererDriverLabels = new ArrayList<>();
        ArrayList<String> rendererDriverIds = new ArrayList<>();
        rendererDriverLabels.add("System");
        rendererDriverIds.add("system");
        for (String id : adrenotoolsManager.enumarateInstalledDrivers()) {
            String label = adrenotoolsManager.getDriverName(id) + " " + adrenotoolsManager.getDriverVersion(id);
            rendererDriverLabels.add(label.trim());
            rendererDriverIds.add(id);
            graphicsVersions.add(id);
            installedGraphicsVersions.add(id);
        }
        synchronized (remoteGraphicsVersions) {
            graphicsVersions.addAll(remoteGraphicsVersions);
        }
        String selectedGraphicsVersion = getSemicolonConfigValue(container.getGraphicsDriverConfig(), "version");
        if (!selectedGraphicsVersion.isEmpty()) graphicsVersions.add(selectedGraphicsVersion);

        boolean arm64EcWine = WineInfo.fromIdentifier(
                requireContext(), contentsManager, container.getWineVersion()
        ).isArm64EC();
        ContentProfile.ContentType box64LikeType = arm64EcWine
                ? ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
                : ContentProfile.ContentType.CONTENT_TYPE_BOX64;
        String defaultBox64Version = arm64EcWine ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64;
        String[] box64Versions = collectComponentVersions(
                contentsManager,
                box64LikeType,
                new String[]{defaultBox64Version},
                container.getBox64Version()
        );
        String[] installedBox64Versions = collectInstalledComponentVersions(
                contentsManager,
                box64LikeType,
                new String[]{defaultBox64Version},
                container.getBox64Version()
        );
        String[] fexcoreVersions = collectComponentVersions(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                new String[]{DefaultVersion.FEXCORE},
                container.getFEXCoreVersion()
        );
        String[] installedFexcoreVersions = collectInstalledComponentVersions(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                new String[]{DefaultVersion.FEXCORE},
                container.getFEXCoreVersion()
        );
        ArrayList<String> box64PresetEntries = new ArrayList<>();
        ArrayList<String> box64PresetIds = new ArrayList<>();
        for (Box64Preset preset : Box64PresetManager.getPresets("box64", requireContext())) {
            box64PresetEntries.add(preset.name);
            box64PresetIds.add(preset.id);
        }
        ArrayList<String> fexcorePresetEntries = new ArrayList<>();
        ArrayList<String> fexcorePresetIds = new ArrayList<>();
        for (FEXCorePreset preset : FEXCorePresetManager.getPresets(requireContext())) {
            fexcorePresetEntries.add(preset.name);
            fexcorePresetIds.add(preset.id);
        }

        String description;
        if (section == VIDEO) {
            description = "Renderer, graphics driver and container resolution";
        }
        else if (section == AUDIO) {
            description = "Select the Wine audio backend";
        }
        else {
            description = "DirectX wrapper and DXVK/VKD3D options";
        }

        String defaultGraphicsVersion = GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, requireContext())
                ? DefaultVersion.WRAPPER_ADRENO
                : DefaultVersion.WRAPPER;
        if (!graphicsVersions.contains(defaultGraphicsVersion)) graphicsVersions.add(defaultGraphicsVersion);
        installedGraphicsVersions.add(defaultGraphicsVersion);

        final View[] host = new View[1];
        host[0] = ContainerSectionComposeHost.create(
                requireContext(),
                container,
                section,
                description,
                rendererEntries,
                container.getUseDisplayX() ? "DisplayX" : container.isRendererNative() ? "EGL" : "Vulkan",
                "native".equals(container.getExtra("displayServer", "java"))
                        ? "X++" : "Java X11",
                screenEntries,
                findScreenEntry(screenEntries, container.getScreenSize()),
                container.getScreenSize(),
                graphicsEntries,
                findIdentifierEntry(graphicsEntries, container.getGraphicsDriver()),
                graphicsVersions.toArray(new String[0]),
                installedGraphicsVersions.toArray(new String[0]),
                container.getGraphicsDriverConfig(),
                defaultGraphicsVersion,
                audioEntries,
                findIdentifierEntry(audioEntries, container.getAudioDriver()),
                wrapperEntries,
                findIdentifierEntry(wrapperEntries, container.getDXWrapper()),
                dxvkEntries,
                installedDxvkEntries,
                vkd3dEntries,
                installedVkd3dEntries,
                container.getDXWrapperConfig(),
                container.getRendererPresentMode(),
                rendererDriverLabels.toArray(new String[0]),
                rendererDriverIds.toArray(new String[0]),
                container.getRendererDriverId(),
                container.getRendererFilterMode(),
                container.getRendererSwapRB(),
                arm64EcWine,
                emulatorEntries,
                findIdentifierEntry(emulatorEntries, container.getEmulator()),
                box64Versions,
                installedBox64Versions,
                container.getBox64Version(),
                box64PresetEntries.toArray(new String[0]),
                box64PresetIds.toArray(new String[0]),
                container.getBox64Preset(),
                fexcoreVersions,
                installedFexcoreVersions,
                container.getFEXCoreVersion(),
                fexcorePresetEntries.toArray(new String[0]),
                fexcorePresetIds.toArray(new String[0]),
                container.getFEXCorePreset(),
                new ContainerSectionCallbacks() {
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
                    public void onSave(
                            @NonNull String renderer,
                            @NonNull String displayServer,
                            @NonNull String screenSize,
                            @NonNull String graphicsDriver,
                            @NonNull String graphicsDriverConfig,
                            @NonNull String audioDriver,
                            @NonNull String wrapper,
                            @NonNull String rendererPresentMode,
                            @NonNull String rendererDriverId,
                            int rendererFilterMode,
                            boolean rendererSwapRB,
                            @NonNull String wrapperConfig,
                            @NonNull String emulator,
                            @NonNull String box64Version,
                            @NonNull String box64Preset,
                            @NonNull String fexcoreVersion,
                            @NonNull String fexcorePreset
                    ) {
                        if (section == VIDEO) {
                            container.putExtra("displayServer", !displayServer.equals("Java X11") ? "native" : "java");
                            container.setScreenSize(normalizeScreenSize(screenSize));
                            container.setGraphicsDriver(StringUtils.parseIdentifier(graphicsDriver));
                            container.setGraphicsDriverConfig(graphicsDriverConfig);
                            container.setRendererNative(renderer.equalsIgnoreCase("EGL"));
                            container.setUseDisplayX(renderer.equalsIgnoreCase("DisplayX"));
                            container.setRendererPresentMode(rendererPresentMode);
                            container.setRendererDriverId(rendererDriverId);
                            container.setRendererFilterMode(rendererFilterMode);
                            container.setRendererSwapRB(rendererSwapRB);
                        }
                        else if (section == AUDIO) {
                            container.setAudioDriver(StringUtils.parseIdentifier(audioDriver));
                        }
                        else {
                            container.setDXWrapper(StringUtils.parseIdentifier(wrapper));
                            container.setDXWrapperConfig(wrapperConfig);
                            container.setEmulator(StringUtils.parseIdentifier(emulator));
                            container.setBox64Version(box64Version);
                            container.setBox64Preset(box64Preset);
                            container.setFEXCoreVersion(fexcoreVersion);
                            container.setFEXCorePreset(fexcorePreset);
                        }
                        container.saveData();
                        getParentFragmentManager().popBackStack();
                    }
                }
        );
        return host[0];
    }

    private void loadRemoteProfiles(ContentsManager contentsManager, FrameLayout root) {
        android.content.Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            ArrayList<String> loaded = new ArrayList<>();
            for (RemoteDriverCatalog.Entry entry : RemoteDriverCatalog.load(appContext)) {
                loaded.add(entry.name);
            }
            synchronized (remoteGraphicsVersions) {
                remoteGraphicsVersions.clear();
                remoteGraphicsVersions.addAll(loaded);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> refreshSection(contentsManager, root));
        }, "winz-driver-catalog").start();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String url = preferences.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
        http.newCall(new Request.Builder().url(url).build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException error) {
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) return;
                    contentsManager.setRemoteProfiles(closeable.body().string());
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        refreshSection(contentsManager, root);
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void refreshSection(ContentsManager contentsManager, FrameLayout root) {
        if (!isAdded()) return;
        View refreshed = buildSectionView(contentsManager);
        root.removeAllViews();
        root.addView(refreshed, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private String findScreenEntry(String[] entries, String selected) {
        String normalized = normalizeScreenSize(selected);
        for (String entry : entries) {
            if (normalizeScreenSize(entry).equalsIgnoreCase(normalized)) return entry;
        }
        for (String entry : entries) {
            if (entry.equalsIgnoreCase("Custom")) return entry;
        }
        return selected;
    }

    private String findIdentifierEntry(String[] entries, String selected) {
        for (String entry : entries) {
            if (StringUtils.parseIdentifier(entry).equalsIgnoreCase(selected)) return entry;
        }
        return entries.length > 0 ? entries[0] : selected;
    }

    private String normalizeScreenSize(String value) {
        return value.replaceAll("\\s*\\(.*\\)$", "").trim();
    }

    private String[] collectComponentVersions(
            ContentsManager manager,
            ContentProfile.ContentType type,
            String[] bundled,
            String selected
    ) {
        LinkedHashSet<String> versions = new LinkedHashSet<>(Arrays.asList(bundled));
        for (ContentProfile profile : manager.getProfiles(type)) {
            versions.add(profile.verName);
        }
        if (selected != null && !selected.isEmpty()) versions.add(selected);
        if (type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D) versions.add("None");
        return versions.toArray(new String[0]);
    }

    private String[] collectInstalledComponentVersions(
            ContentsManager manager,
            ContentProfile.ContentType type,
            String[] bundled,
            String selected
    ) {
        LinkedHashSet<String> versions = new LinkedHashSet<>(Arrays.asList(bundled));
        for (ContentProfile profile : manager.getInstalledProfiles(type)) {
            versions.add(profile.verName);
        }
        if (selected != null && !selected.isEmpty()) versions.add(selected);
        if (type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D) versions.add("None");
        return versions.toArray(new String[0]);
    }

    private String getConfigValue(String config, String key) {
        if (config == null || config.isEmpty()) return "";
        for (String item : config.split(",")) {
            int separator = item.indexOf('=');
            if (separator > 0 && item.substring(0, separator).equals(key)) {
                return item.substring(separator + 1);
            }
        }
        return "";
    }

    private String getSemicolonConfigValue(String config, String key) {
        if (config == null || config.isEmpty()) return "";
        for (String item : config.split(";")) {
            int separator = item.indexOf('=');
            if (separator > 0 && item.substring(0, separator).equals(key)) {
                return item.substring(separator + 1);
            }
        }
        return "";
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDetailMode(true);
        }
        int title = section == VIDEO ? R.string.video
                : section == AUDIO ? R.string.audio_driver : R.string.compatibility;
        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(title);
    }
}

