package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.ui.library.GameDetailCallbacks;
import com.winlator.cmod.ui.library.GameDetailComposeHost;
import com.winlator.cmod.ui.library.GameSavesComposeDialog;
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog;

import java.io.File;

public class GameDetailFragment extends Fragment {
    private final String shortcutPath;
    private Shortcut shortcut;

    public GameDetailFragment() {
        this("");
    }

    public GameDetailFragment(String shortcutPath) {
        this.shortcutPath = shortcutPath;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        ContainerManager manager = new ContainerManager(requireContext());
        File requestedShortcut = new File(shortcutPath);
        if (requestedShortcut.isFile()) {
            for (com.winlator.cmod.container.Container container : manager.getContainers()) {
                if (container.getDesktopDir().equals(requestedShortcut.getParentFile())) {
                    shortcut = new Shortcut(container, requestedShortcut);
                    break;
                }
            }
        }

        if (shortcut == null) {
            getParentFragmentManager().popBackStack();
            return new FrameLayout(requireContext());
        }

        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(shortcut.name);
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File userArtwork = new File(Environment.getExternalStorageDirectory(),
                "Winlator/icons/" + baseName + ".user.png");
        File banner = new File(Environment.getExternalStorageDirectory(),
                "Winlator/banners/" + baseName + ".png");
        File cover = new File(Environment.getExternalStorageDirectory(),
                "Winlator/covers/" + baseName + ".png");
        String artworkPath = userArtwork.isFile() ? userArtwork.getPath()
                : banner.isFile() ? banner.getPath()
                : cover.isFile() ? cover.getPath() : null;
        Bitmap fallback = shortcut.icon;

        View content = GameDetailComposeHost.create(
                requireContext(),
                shortcut.name,
                buildEnvironmentSubtitle(),
                artworkPath,
                fallback,
                "1".equals(shortcut.getExtra("favorite", "0")),
                shortcut,
                new GameDetailCallbacks() {
                    @Override
                    public void onPlay() {
                        runShortcut();
                    }

                    @Override
                    public void onConfigure() {
                        ShortcutSettingsComposeDialog.show(GameDetailFragment.this, shortcut);
                    }

                    @Override
                    public void onArguments() {
                        runContainer();
                    }

                    @Override
                    public void onSaves() {
                        GameSavesComposeDialog.show(GameDetailFragment.this, shortcut);
                    }

                    @Override
                    public void onFavorite(boolean favorite) {
                        shortcut.putExtra("favorite", favorite ? "1" : "0");
                        shortcut.saveData();
                    }

                    @Override
                    public void onHome() {
                        com.winlator.cmod.ui.library.HomeShortcuts.pin(requireActivity(), shortcut);
                    }

                    @Override
                    public void onRemove() {
                        com.winlator.cmod.ui.ThemedAlertHost.confirm((androidx.appcompat.app.AppCompatActivity) requireActivity(), getString(R.string.ui_remove_shortcut_title), getString(R.string.do_you_want_to_remove_this_shortcut), getString(R.string.remove), () -> {
                            if (shortcut.file.delete()) getParentFragmentManager().popBackStack();
                        }, true);
                    }
                }
        );
        content.post(this::applyDetailChrome);
        return content;
    }

    private String buildEnvironmentSubtitle() {
        String runtime = shortcut.container.getWineVersion();
        try {
            ContentsManager contents = new ContentsManager(requireContext());
            contents.syncContents();
            WineInfo info = WineInfo.fromIdentifier(requireContext(), contents, runtime);
            String version = info.fullVersion();
            if (version.endsWith(".0")) version = version.substring(0, version.length() - 2);
            runtime = ("proton".equalsIgnoreCase(info.type) ? "Proton " : "Wine ") + version + " " + info.getArch();
        } catch (Exception ignored) {}
        String renderer = shortcut.getUseDisplayX() ? "DisplayX" : shortcut.getRendererNative() ? "EGL" : "Vulkan";
        return runtime + "  •  " + renderer;
    }

    private void runShortcut() {
        Activity activity = requireActivity();
        if (!XrActivity.isEnabled(requireContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            intent.putExtra("shortcut_path", shortcut.file.getPath());
            intent.putExtra("shortcut_name", shortcut.name);
            intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
            intent.putExtra("native_rendering", shortcut.getRendererNative());
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }
    }

    private void runContainer() {
        Activity activity = requireActivity();
        if (!XrActivity.isEnabled(requireContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, shortcut.container.id, null);
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applyDetailChrome() {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) getActivity();
        activity.setDetailMode(true);
        activity.setBottomNavigationVisible(false);
        activity.setMainToolbarVisible(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        applyDetailChrome();
    }

    @Override
    public void onPause() {
        if (!isLandscape() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDetailMode(false);
            ((MainActivity) getActivity()).setBottomNavigationVisible(true);
            ((MainActivity) getActivity()).setMainToolbarVisible(true);
        }
        super.onPause();
    }
}

