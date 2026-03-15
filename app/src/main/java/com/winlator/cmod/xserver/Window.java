package com.winlator.cmod.xserver;

import android.util.SparseArray;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.PropertyNotify;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

/* loaded from: classes11.dex */
public class Window extends XResource {
    public static final int FLAG_BORDER_WIDTH = 16;
    public static final int FLAG_HEIGHT = 8;
    public static final int FLAG_SIBLING = 32;
    public static final int FLAG_STACK_MODE = 64;
    public static final int FLAG_WIDTH = 4;
    public static final int FLAG_X = 1;
    public static final int FLAG_Y = 2;
    public final WindowAttributes attributes;
    private short borderWidth;
    private final ArrayList<Window> children;
    private Drawable content;
    private final ArrayList<EventListener> eventListeners;
    private short height;
    private final List<Window> immutableChildren;
    public final XClient originClient;
    private Window parent;
    private final SparseArray<Property> properties;
    private short width;
    private short x;
    private short y;

    public enum MapState {
        UNMAPPED,
        UNVIEWABLE,
        VIEWABLE
    }

    public enum StackMode {
        ABOVE,
        BELOW,
        TOP_IF,
        BOTTOM_IF,
        OPPOSITE
    }

    public enum WMHints {
        FLAGS,
        INPUT,
        INITIAL_STATE,
        ICON_PIXMAP,
        ICON_WINDOW,
        ICON_X,
        ICON_Y,
        ICON_MASK,
        WINDOW_GROUP
    }

    public Window(int id, Drawable content, int x, int y, int width, int height, XClient originClient) {
        super(id);
        this.attributes = new WindowAttributes(this);
        this.properties = new SparseArray<>();
        this.children = new ArrayList<>();
        this.immutableChildren = Collections.unmodifiableList(this.children);
        this.eventListeners = new ArrayList<>();
        this.content = content;
        this.x = (short) x;
        this.y = (short) y;
        this.width = (short) width;
        this.height = (short) height;
        this.originClient = originClient;
    }

    public short getX() {
        return this.x;
    }

    public void setX(short x) {
        this.x = x;
    }

    public short getY() {
        return this.y;
    }

    public void setY(short y) {
        this.y = y;
    }

    public short getWidth() {
        return this.width;
    }

    public void setWidth(short width) {
        this.width = width;
    }

    public short getHeight() {
        return this.height;
    }

    public void setHeight(short height) {
        this.height = height;
    }

    public short getBorderWidth() {
        return this.borderWidth;
    }

    public void setBorderWidth(short borderWidth) {
        this.borderWidth = borderWidth;
    }

    public Drawable getContent() {
        return this.content;
    }

    public void setContent(Drawable content) {
        this.content = content;
    }

    public Window getParent() {
        return this.parent;
    }

    public void setParent(Window parent) {
        this.parent = parent;
    }

    public Property getProperty(int id) {
        return this.properties.get(id);
    }

    public void addProperty(Property property) {
        this.properties.put(property.name, property);
    }

    public void removeProperty(int id) {
        this.properties.remove(id);
        sendEvent(4194304, new PropertyNotify(this, id, true));
    }

    public Property modifyProperty(int atom, int type, Property.Format format, Property.Mode mode, byte[] data) {
        Property property = getProperty(atom);
        boolean modified = false;
        if (property == null) {
            Property property2 = new Property(atom, type, format, data);
            property = property2;
            addProperty(property2);
            modified = true;
        } else if (mode == Property.Mode.REPLACE) {
            if (property.format == format) {
                property.replace(data);
            } else {
                this.properties.put(atom, new Property(atom, type, format, data));
            }
            modified = true;
        } else if (property.format == format && property.type == type) {
            if (mode == Property.Mode.PREPEND) {
                property.prepend(data);
            } else if (mode == Property.Mode.APPEND) {
                property.append(data);
            }
            modified = true;
        }
        if (modified) {
            sendEvent(4194304, new PropertyNotify(this, atom, false));
            return property;
        }
        return null;
    }

    public String getName() {
        Property property = getProperty(Atom.getId("WM_NAME"));
        return property != null ? property.toString() : "";
    }

    public String getClassName() {
        Property property = getProperty(Atom.getId("WM_CLASS"));
        return property != null ? property.toString() : "";
    }

    public int getWMHintsValue(WMHints wmHints) {
        Property property = getProperty(Atom.getId("WM_HINTS"));
        if (property != null) {
            return property.getInt(wmHints.ordinal());
        }
        return 0;
    }

    public int getProcessId() {
        Property property = getProperty(Atom.getId("_NET_WM_PID"));
        if (property != null) {
            return property.getInt(0);
        }
        return 0;
    }

    public boolean isWoW64() {
        Property property = getProperty(Atom.getId("_NET_WM_WOW64"));
        return property != null && property.data.get(0) == 1;
    }

    public long getHandle() {
        Property property = getProperty(Atom.getId("_NET_WM_HWND"));
        if (property != null) {
            return property.getLong(0);
        }
        return 0L;
    }

    public boolean isApplicationWindow() {
        int windowGroup = getWMHintsValue(WMHints.WINDOW_GROUP);
        return this.attributes.isMapped() && windowGroup == this.id && this.width > 1 && this.height > 1;
    }

    public boolean isInputOutput() {
        return this.content != null;
    }

    public void addChild(Window child) {
        if (child == null || child.parent == this) {
            return;
        }
        child.parent = this;
        this.children.add(child);
    }

    public void removeChild(Window child) {
        if (child == null || child.parent != this) {
            return;
        }
        child.parent = null;
        this.children.remove(child);
    }

    public Window previousSibling() {
        int index;
        if (this.parent != null && (index = this.parent.children.indexOf(this)) > 0) {
            return this.parent.children.get(index - 1);
        }
        return null;
    }

    public void moveChildAbove(Window child, Window sibling) {
        this.children.remove(child);
        if (sibling != null && this.children.contains(sibling)) {
            this.children.add(this.children.indexOf(sibling) + 1, child);
        } else {
            this.children.add(child);
        }
    }

    public void moveChildBelow(Window child, Window sibling) {
        this.children.remove(child);
        if (sibling != null && this.children.contains(sibling)) {
            this.children.add(this.children.indexOf(sibling), child);
        } else {
            this.children.add(0, child);
        }
    }

    public List<Window> getChildren() {
        return this.immutableChildren;
    }

    public int getChildCount() {
        return this.children.size();
    }

    public void addEventListener(EventListener eventListener) {
        this.eventListeners.add(eventListener);
    }

    public void removeEventListener(EventListener eventListener) {
        this.eventListeners.remove(eventListener);
    }

    public boolean hasEventListenerFor(int eventId) {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            if (eventListener.isInterestedIn(eventId)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasEventListenerFor(Bitmask mask) {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            if (eventListener.isInterestedIn(mask)) {
                return true;
            }
        }
        return false;
    }

    public void sendEvent(int eventId, Event event) {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            if (eventListener.isInterestedIn(eventId)) {
                eventListener.sendEvent(event);
            }
        }
    }

    public void sendEvent(Bitmask eventMask, Event event) {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            if (eventListener.isInterestedIn(eventMask)) {
                eventListener.sendEvent(event);
            }
        }
    }

    public void sendEvent(int eventId, Event event, XClient client) {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            if (eventListener.isInterestedIn(eventId) && eventListener.client == client) {
                eventListener.sendEvent(event);
            }
        }
    }

    public void sendEvent(Bitmask eventMask, Event event, XClient client) {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            if (eventListener.isInterestedIn(eventMask) && eventListener.client == client) {
                eventListener.sendEvent(event);
            }
        }
    }

    public void sendEvent(Event event) {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            eventListener.sendEvent(event);
        }
    }

    public boolean containsPoint(short rootX, short rootY) {
        short[] localPoint = rootPointToLocal(rootX, rootY);
        return localPoint[0] >= 0 && localPoint[1] >= 0 && localPoint[0] < this.width && localPoint[1] < this.height;
    }

    public short[] rootPointToLocal(short x, short y) {
        for (Window window = this; window != null; window = window.parent) {
            x = (short) (x - window.x);
            y = (short) (y - window.y);
        }
        return new short[]{x, y};
    }

    public short[] localPointToRoot(short x, short y) {
        for (Window window = this; window != null; window = window.parent) {
            x = (short) (window.x + x);
            y = (short) (window.y + y);
        }
        return new short[]{x, y};
    }

    public short getRootX() {
        short rootX = this.x;
        for (Window window = this.parent; window != null; window = window.parent) {
            rootX = (short) (window.x + rootX);
        }
        return rootX;
    }

    public short getRootY() {
        short rootY = this.y;
        for (Window window = this.parent; window != null; window = window.parent) {
            rootY = (short) (window.y + rootY);
        }
        return rootY;
    }

    public Window getAncestorWithEventMask(Bitmask eventMask) {
        for (Window window = this; window != null; window = window.parent) {
            if (window.hasEventListenerFor(eventMask)) {
                return window;
            }
            if (window.attributes.getDoNotPropagateMask().intersects(eventMask)) {
                return null;
            }
        }
        return null;
    }

    public Window getAncestorWithEventId(int eventId) {
        return getAncestorWithEventId(eventId, null);
    }

    public Window getAncestorWithEventId(int eventId, Window endWindow) {
        for (Window window = this; window != null; window = window.parent) {
            if (window.hasEventListenerFor(eventId)) {
                return window;
            }
            if (window == endWindow || window.attributes.getDoNotPropagateMask().isSet(eventId)) {
                return null;
            }
        }
        return null;
    }

    public boolean isAncestorOf(Window window) {
        if (window == this) {
            return false;
        }
        while (window != null) {
            if (window == this) {
                return true;
            }
            window = window.parent;
        }
        return false;
    }

    public Window getChildByCoords(short x, short y) {
        for (int i = this.children.size() - 1; i >= 0; i--) {
            Window child = this.children.get(i);
            if (child.attributes.isMapped() && child.containsPoint(x, y)) {
                return child;
            }
        }
        return null;
    }

    public MapState getMapState() {
        if (!this.attributes.isMapped()) {
            return MapState.UNMAPPED;
        }
        Window window = this;
        do {
            window = window.parent;
            if (window == null) {
                return MapState.VIEWABLE;
            }
        } while (window.attributes.isMapped());
        return MapState.UNVIEWABLE;
    }

    public Bitmask getAllEventMasks() {
        Bitmask eventMask = new Bitmask();
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            eventMask.join(eventListener.eventMask);
        }
        return eventMask;
    }

    public EventListener getButtonPressListener() {
        Iterator<EventListener> it = this.eventListeners.iterator();
        while (it.hasNext()) {
            EventListener eventListener = it.next();
            if (eventListener.isInterestedIn(4)) {
                return eventListener;
            }
        }
        return null;
    }

    public void disableAllDescendants() {
        Stack<Window> stack = new Stack<>();
        stack.push(this);
        while (!stack.isEmpty()) {
            Window window = stack.pop();
            window.attributes.setEnabled(false);
            stack.addAll(window.children);
        }
    }

    public String serializeProperties() {
        String result = "";
        for (int i = 0; i < this.properties.size(); i++) {
            Property property = this.properties.valueAt(i);
            result = result + property.nameAsString() + "=" + property + "\n";
        }
        return result;
    }
}
