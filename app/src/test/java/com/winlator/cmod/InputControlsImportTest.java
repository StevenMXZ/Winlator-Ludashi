package com.winlator.cmod;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentActivity;

import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class InputControlsImportTest {
    public static class HeadlessFragment extends InputControlsFragment {
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
            return null;
        }
    }

    private ActivityController<FragmentActivity> activity;
    private HeadlessFragment fragment;
    private InputControlsManager manager;

    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(FragmentActivity.class).setup();
        fragment = new HeadlessFragment();
        activity.get().getSupportFragmentManager().beginTransaction().add(fragment, "profiles").commitNow();
        manager = (InputControlsManager) field("manager").get(fragment);
        manager.getProfiles().clear();
    }

    @After public void teardown() {
        activity.pause().stop().destroy();
    }

    @Test public void clipDataWithoutSingleUriImportsEveryProfileAndSelectsById() throws Exception {
        Uri zebra = document("zebra", profile("Zebra"));
        Uri alpha = document("alpha", profile("alpha"));
        ClipData clip = ClipData.newRawUri("profiles", zebra);
        clip.addItem(new ClipData.Item(alpha));

        Intent result = new Intent();
        result.setClipData(clip);
        fragment.onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_OK, result);

        assertEquals(2, manager.getProfiles().size());
        assertEquals("alpha", manager.getProfiles().get(0).getName());
        assertEquals("Zebra", manager.getProfiles().get(1).getName());
        ControlsProfile selected = (ControlsProfile) field("currentProfile").get(fragment);
        assertSame(manager.getProfile(selected.id), selected);
        assertEquals("alpha", selected.getName());
    }

    @Test public void failedDocumentsDoNotDiscardSuccessfulImports() throws Exception {
        Uri valid = document("valid", profile("Working"));
        Uri invalid = document("invalid", "not JSON");
        ClipData clip = ClipData.newRawUri("profiles", valid);
        clip.addItem(new ClipData.Item(invalid));

        Intent result = new Intent();
        result.setClipData(clip);
        fragment.onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_OK, result);

        assertEquals(1, manager.getProfiles().size());
        assertEquals("Working", ((ControlsProfile) field("currentProfile").get(fragment)).getName());
    }

    @Test public void cancellationAndOtherRequestsDoNotImportFiles() {
        Intent result = new Intent().setData(document("ignored", profile("Ignored")));
        fragment.onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_CANCELED, result);
        fragment.onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE + 1, Activity.RESULT_OK, result);
        assertTrue(manager.getProfiles().isEmpty());
    }

    @Test public void createRenameAndDuplicateKeepAlphabeticalOrderAndStableIds() {
        ControlsProfile zebra = manager.createProfile("Zebra");
        ControlsProfile beta = manager.createProfile("beta");
        int selectedId = zebra.id;
        assertSame(beta, manager.getProfiles().get(0));

        manager.renameProfile(zebra, "Alpha");
        assertSame(zebra, manager.getProfiles().get(0));
        assertSame(zebra, manager.getProfile(selectedId));
        ControlsProfile duplicate = manager.duplicateProfile(zebra);
        assertEquals("Alpha (1)", duplicate.getName());
        assertSame(duplicate, manager.getProfiles().get(1));
        assertSame(beta, manager.getProfiles().get(2));
    }

    private Uri document(String name, String content) {
        Uri uri = Uri.parse("content://profiles/" + name);
        shadowOf(activity.get().getContentResolver()).registerInputStream(uri,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return uri;
    }

    private static String profile(String name) {
        return "{\"id\":1,\"name\":\"" + name + "\",\"cursorSpeed\":1,\"elements\":[]}";
    }

    private static Field field(String name) throws Exception {
        Field field = InputControlsFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
