package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.view.InputDevice;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.widget.InputControlsView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.bouncycastle.i18n.TextBundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class ControlsProfile implements Comparable<ControlsProfile> {
    private final Context context;
    private GamepadState gamepadState;
    public final int id;
    private String name;
    private float cursorSpeed = 1.0f;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(this.elements);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean virtualGamepad = false;

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return this.cursorSpeed;
    }

    public void setCursorSpeed(float cursorSpeed) {
        this.cursorSpeed = cursorSpeed;
    }

    public boolean isVirtualGamepad() {
        return this.virtualGamepad;
    }

    public GamepadState getGamepadState() {
        if (this.gamepadState == null) {
            this.gamepadState = new GamepadState();
        }
        return this.gamepadState;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller == null) {
            ArrayList<ExternalController> arrayList = this.controllers;
            ExternalController controller2 = ExternalController.getController(id);
            controller = controller2;
            arrayList.add(controller2);
        }
        this.controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!this.controllersLoaded) {
            loadControllers();
        }
        this.controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!this.controllersLoaded) {
            loadControllers();
        }
        Iterator<ExternalController> it = this.controllers.iterator();
        while (it.hasNext()) {
            ExternalController controller = it.next();
            if (controller.getId().equals(id)) {
                return controller;
            }
        }
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!this.controllersLoaded) {
            loadControllers();
        }
        Iterator<ExternalController> it = this.controllers.iterator();
        while (it.hasNext()) {
            ExternalController controller = it.next();
            if (controller.getDeviceId() == deviceId) {
                return controller;
            }
        }
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device != null) {
            String descriptor = device.getDescriptor();
            Iterator<ExternalController> it2 = this.controllers.iterator();
            while (it2.hasNext()) {
                ExternalController controller2 = it2.next();
                if (controller2.getId().equals(descriptor)) {
                    return controller2;
                }
            }
            return null;
        }
        return null;
    }

    public String toString() {
        return this.name;
    }

    @Override // java.lang.Comparable
    public int compareTo(ControlsProfile o) {
        return Integer.compare(this.id, o.id);
    }

    public boolean isElementsLoaded() {
        return this.elementsLoaded;
    }

    public void save() {
        File file = getProfileFile(this.context, this.id);
        try {
            JSONObject data = new JSONObject();
            data.put("id", this.id);
            data.put("name", this.name);
            data.put("cursorSpeed", Float.valueOf(this.cursorSpeed));
            JSONArray elementsJSONArray = new JSONArray();
            if (!this.elementsLoaded && file.isFile()) {
                elementsJSONArray = new JSONObject(FileUtils.readString(file)).getJSONArray("elements");
            } else {
                Iterator<ControlElement> it = this.elements.iterator();
                while (it.hasNext()) {
                    ControlElement element = it.next();
                    elementsJSONArray.put(element.toJSONObject());
                }
            }
            data.put("elements", elementsJSONArray);
            JSONArray controllersJSONArray = new JSONArray();
            if (!this.controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("controllers")) {
                    controllersJSONArray = profileJSONObject.getJSONArray("controllers");
                }
            } else {
                Iterator<ExternalController> it2 = this.controllers.iterator();
                while (it2.hasNext()) {
                    ExternalController controller = it2.next();
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) {
                        controllersJSONArray.put(controllerJSONObject);
                    }
                }
            }
            if (controllersJSONArray.length() > 0) {
                data.put("controllers", controllersJSONArray);
            }
            FileUtils.writeString(file, data.toString());
        } catch (JSONException e) {
        }
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-" + id + ".icp");
    }

    public void addElement(ControlElement element) {
        this.elements.add(element);
        this.elementsLoaded = true;
    }

    public void removeElement(ControlElement element) {
        this.elements.remove(element);
        this.elementsLoaded = true;
    }

    public List<ControlElement> getElements() {
        return this.immutableElements;
    }

    public boolean isTemplate() {
        return this.name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        JSONObject profileJSONObject;
        this.controllers.clear();
        this.controllersLoaded = false;
        File file = getProfileFile(this.context, this.id);
        if (!file.isFile()) {
            return this.controllers;
        }
        try {
            profileJSONObject = new JSONObject(FileUtils.readString(file));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (!profileJSONObject.has("controllers")) {
            return this.controllers;
        }
        JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
        for (int i = 0; i < controllersJSONArray.length(); i++) {
            JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
            String id = controllerJSONObject.getString("id");
            ExternalController controller = new ExternalController();
            controller.setId(id);
            controller.setName(controllerJSONObject.getString("name"));
            JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
            for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                controller.addControllerBinding(controllerBinding);
            }
            this.controllers.add(controller);
        }
        this.controllersLoaded = true;
        return this.controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        this.elements.clear();
        this.elementsLoaded = false;
        this.virtualGamepad = false;
        File file = getProfileFile(this.context, this.id);
        if (file.isFile()) {
            try {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
                for (int i = 0; i < elementsJSONArray.length(); i++) {
                    JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
                    ControlElement element = new ControlElement(inputControlsView);
                    element.setType(ControlElement.Type.valueOf(elementJSONObject.getString(ContentProfile.MARK_TYPE)));
                    element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
                    element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
                    element.setX((int) (elementJSONObject.getDouble("x") * inputControlsView.getMaxWidth()));
                    element.setY((int) (elementJSONObject.getDouble("y") * inputControlsView.getMaxHeight()));
                    element.setScale((float) elementJSONObject.getDouble("scale"));
                    element.setText(elementJSONObject.getString(TextBundle.TEXT_ENTRY));
                    element.setIconId(elementJSONObject.getInt("iconId"));
                    if (elementJSONObject.has("range")) {
                        element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
                    }
                    if (elementJSONObject.has("orientation")) {
                        element.setOrientation((byte) elementJSONObject.getInt("orientation"));
                    }
                    boolean hasGamepadBinding = true;
                    JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
                    for (int j = 0; j < bindingsJSONArray.length(); j++) {
                        Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
                        element.setBindingAt(j, Binding.fromString(bindingsJSONArray.getString(j)));
                        if (!binding.isGamepad()) {
                            hasGamepadBinding = false;
                        }
                    }
                    if (!this.virtualGamepad && hasGamepadBinding) {
                        this.virtualGamepad = true;
                    }
                    this.elements.add(element);
                }
                this.elementsLoaded = true;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}
