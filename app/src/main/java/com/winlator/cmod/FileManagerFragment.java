package com.winlator.cmod;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.winlator.cmod.ui.filemanager.FileManagerController;
import com.winlator.cmod.ui.filemanager.FileManagerFragmentHost;
import com.winlator.cmod.ui.filemanager.FileManagerViewState;

// WinLite's Compose presentation, hosted by our existing fragment navigation.
// State belongs to the fragment; rotating the screen does not replace the file manager.
public class FileManagerFragment extends Fragment {
    private FileManagerController controller;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle saved) {
        MainActivity activity = (MainActivity) requireActivity();
        FileManagerViewState state = new FileManagerViewState();
        controller = new FileManagerController(activity, state::setModel);
        Bundle location = saved != null ? saved : getArguments();
        if (location != null) controller.openAt(location.getString("start_path"), location.getString("drive_root"));
        controller.setActive(true);
        controller.refresh();
        if (activity.getSupportActionBar() != null) activity.getSupportActionBar().setTitle(R.string.ui_file_manager);
        return FileManagerFragmentHost.create(activity, controller, state);
    }
    @Override public void onResume() {
        super.onResume();
        if (controller != null) { controller.setActive(true); controller.refresh(); }
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (controller != null) {
            out.putString("start_path", controller.getCurrentPath());
            out.putString("drive_root", controller.getDriveRootPath());
        }
    }
    @Override public void onDestroyView() {
        if (controller != null) controller.setActive(false);
        super.onDestroyView();
    }
}
