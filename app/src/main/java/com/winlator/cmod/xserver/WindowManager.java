package com.winlator.cmod.xserver;

import android.util.SparseArray;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.ConfigureNotify;
import com.winlator.cmod.xserver.events.ConfigureRequest;
import com.winlator.cmod.xserver.events.DestroyNotify;
import com.winlator.cmod.xserver.events.Expose;
import com.winlator.cmod.xserver.events.MapNotify;
import com.winlator.cmod.xserver.events.MapRequest;
import com.winlator.cmod.xserver.events.ResizeRequest;
import com.winlator.cmod.xserver.events.UnmapNotify;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* loaded from: classes11.dex */
public class WindowManager extends XResourceManager {
    public final DrawableManager drawableManager;
    private Window focusedWindow;
    public final Window rootWindow;
    private final SparseArray<Window> windows = new SparseArray<>();
    private FocusRevertTo focusRevertTo = FocusRevertTo.NONE;
    private final ArrayList<OnWindowModificationListener> onWindowModificationListeners = new ArrayList<>();

    public enum FocusRevertTo {
        NONE,
        POINTER_ROOT,
        PARENT
    }

    public interface OnWindowModificationListener {
        default void onMapWindow(Window window) {
        }

        default void onUnmapWindow(Window window) {
        }

        default void onChangeWindowZOrder(Window window) {
        }

        default void onUpdateWindowContent(Window window) {
        }

        default void onUpdateWindowGeometry(Window window, boolean resized) {
        }

        default void onUpdateWindowAttributes(Window window, Bitmask mask) {
        }

        default void onModifyWindowProperty(Window window, Property property) {
        }
    }

    public WindowManager(ScreenInfo screenInfo, DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
        int id = IDGenerator.generate();
        Drawable drawable = drawableManager.createDrawable(id, screenInfo.width, screenInfo.height, drawableManager.getVisual());
        this.rootWindow = new Window(id, drawable, 0, 0, screenInfo.width, screenInfo.height, null);
        this.rootWindow.attributes.setMapped(true);
        this.windows.put(id, this.rootWindow);
    }

    public Window getWindow(int id) {
        return this.windows.get(id);
    }

    public Window findWindowWithProcessId(int processId) {
        for (int i = 0; i < this.windows.size(); i++) {
            Window window = this.windows.valueAt(i);
            if (window != null && window.getProcessId() == processId) {
                return window;
            }
        }
        return null;
    }

    public void destroyWindow(int id) {
        Window window = getWindow(id);
        if (window != null && this.rootWindow.id != id) {
            unmapWindow(window);
            removeAllSubwindowsAndWindow(window);
        }
    }

    private void removeAllSubwindowsAndWindow(Window window) {
        List<Window> children = new ArrayList<>(window.getChildren());
        for (Window child : children) {
            removeAllSubwindowsAndWindow(child);
        }
        Window parent = window.getParent();
        window.sendEvent(131072, new DestroyNotify(window, window));
        parent.sendEvent(524288, new DestroyNotify(parent, window));
        this.windows.remove(window.id);
        if (window.isInputOutput()) {
            this.drawableManager.removeDrawable(window.getContent().id);
        }
        triggerOnFreeResourceListener(window);
        if (window == this.focusedWindow) {
            revertFocus();
        }
        parent.removeChild(window);
    }

    public void mapWindow(Window window) {
        if (!window.attributes.isMapped()) {
            Window parent = window.getParent();
            if (parent.hasEventListenerFor(1048576) && !window.attributes.isOverrideRedirect()) {
                parent.sendEvent(1048576, new MapRequest(parent, window));
                return;
            }
            window.attributes.setMapped(true);
            window.sendEvent(131072, new MapNotify(window, window));
            parent.sendEvent(524288, new MapNotify(parent, window));
            window.sendEvent(32768, new Expose(window));
            triggerOnMapWindow(window);
        }
    }

    public void unmapWindow(Window window) {
        if (this.rootWindow.id != window.id && window.attributes.isMapped()) {
            window.attributes.setMapped(false);
            Window parent = window.getParent();
            window.sendEvent(131072, new UnmapNotify(window, window));
            parent.sendEvent(524288, new UnmapNotify(parent, window));
            if (window == this.focusedWindow) {
                revertFocus();
            }
            triggerOnUnmapWindow(window);
        }
    }

    public Window getFocusedWindow() {
        return this.focusedWindow;
    }

    public void revertFocus() {
        switch (this.focusRevertTo) {
            case NONE:
                this.focusedWindow = null;
                break;
            case POINTER_ROOT:
                this.focusedWindow = this.rootWindow;
                break;
            case PARENT:
                if (this.focusedWindow.getParent() != null) {
                    this.focusedWindow = this.focusedWindow.getParent();
                    break;
                }
                break;
        }
    }

    public void setFocus(Window focusedWindow, FocusRevertTo focusRevertTo) {
        this.focusedWindow = focusedWindow;
        this.focusRevertTo = focusRevertTo;
    }

    public FocusRevertTo getFocusRevertTo() {
        return this.focusRevertTo;
    }

    public Window createWindow(int id, Window parent, short x, short y, short width, short height, WindowAttributes.WindowClass windowClass, Visual visual, byte depth, XClient client) throws XRequestError {
        boolean isInputOutput;
        byte depth2;
        Visual visual2;
        Drawable drawable;
        if (this.windows.indexOfKey(id) >= 0) {
            throw new BadIdChoice(id);
        }
        switch (windowClass) {
            case COPY_FROM_PARENT:
                byte depth3 = (depth == 0 && parent.isInputOutput()) ? parent.getContent().visual.depth : depth;
                boolean isInputOutput2 = parent.isInputOutput();
                isInputOutput = isInputOutput2;
                depth2 = depth3;
                break;
            case INPUT_OUTPUT:
                if (parent.isInputOutput()) {
                    isInputOutput = true;
                    depth2 = depth == 0 ? parent.getContent().visual.depth : depth;
                    break;
                } else {
                    throw new BadMatch();
                }
            case INPUT_ONLY:
                depth2 = depth;
                isInputOutput = false;
                break;
            default:
                depth2 = depth;
                isInputOutput = false;
                break;
        }
        if (!isInputOutput) {
            visual2 = visual;
        } else {
            Visual visual3 = visual == null ? parent.getContent().visual : visual;
            if (depth2 != visual3.depth) {
                throw new BadMatch();
            }
            visual2 = visual3;
        }
        if (!isInputOutput) {
            drawable = null;
        } else {
            Drawable drawable2 = this.drawableManager.createDrawable(id, width, height, visual2);
            if (drawable2 == null) {
                throw new BadIdChoice(id);
            }
            drawable = drawable2;
        }
        final Window window = new Window(id, drawable, x, y, width, height, client);
        window.attributes.setWindowClass(windowClass);
        if (drawable != null) {
            drawable.setOnDrawListener(new Runnable() { // from class: com.winlator.cmod.xserver.WindowManager$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    WindowManager.this.lambda$createWindow$0(window);
                }
            });
        }
        this.windows.put(id, window);
        parent.addChild(window);
        triggerOnCreateResourceListener(window);
        return window;
    }

    private void changeWindowGeometry(final Window window, short x, short y, short width, short height) {
        boolean resized = (window.getWidth() == width && window.getHeight() == height) ? false : true;
        if (resized && window.hasEventListenerFor(262144)) {
            window.sendEvent(1048576, new ResizeRequest(window, width, height));
            width = window.getWidth();
            height = window.getHeight();
            resized = false;
        }
        if (resized && window.isInputOutput()) {
            Drawable oldContent = window.getContent();
            this.drawableManager.removeDrawable(oldContent.id);
            Drawable newContent = this.drawableManager.createDrawable(oldContent.id, width, height, oldContent.visual);
            newContent.setOnDrawListener(new Runnable() { // from class: com.winlator.cmod.xserver.WindowManager$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    WindowManager.this.lambda$changeWindowGeometry$1(window);
                }
            });
            window.setContent(newContent);
        }
        if (resized || window.getX() != x || window.getY() != y) {
            window.setX(x);
            window.setY(y);
            window.setWidth(width);
            window.setHeight(height);
            triggerOnUpdateWindowGeometry(window, resized);
        }
        if (resized && window.isInputOutput() && window.attributes.isMapped()) {
            window.sendEvent(new Expose(window));
        }
    }

    private void changeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        switch (stackMode) {
            case ABOVE:
                parent.moveChildAbove(window, sibling);
                break;
            case BELOW:
                parent.moveChildBelow(window, sibling);
                break;
        }
        triggerOnChangeWindowZOrder(window);
    }

    public void configureWindow(Window window, Bitmask valueMask, XInputStream inputStream) {
        Window sibling;
        short x = window.getX();
        short y = window.getY();
        short width = window.getWidth();
        short height = window.getHeight();
        short borderWidth = window.getBorderWidth();
        Iterator<Integer> it = valueMask.iterator();
        short x2 = x;
        short y2 = y;
        short width2 = width;
        short height2 = height;
        Window sibling2 = null;
        Window.StackMode stackMode = null;
        while (it.hasNext()) {
            int index = it.next().intValue();
            switch (index) {
                case 1:
                    short x3 = (short) inputStream.readInt();
                    x2 = x3;
                    break;
                case 2:
                    short y3 = (short) inputStream.readInt();
                    y2 = y3;
                    break;
                case 4:
                    short width3 = (short) inputStream.readInt();
                    width2 = width3;
                    break;
                case 8:
                    short height3 = (short) inputStream.readInt();
                    height2 = height3;
                    break;
                case 16:
                    short borderWidth2 = (short) inputStream.readInt();
                    borderWidth = borderWidth2;
                    break;
                case 32:
                    Window sibling3 = getWindow(inputStream.readInt());
                    sibling2 = sibling3;
                    break;
                case 64:
                    stackMode = Window.StackMode.values()[inputStream.readInt()];
                    break;
            }
        }
        Window parent = window.getParent();
        boolean overrideRedirect = window.attributes.isOverrideRedirect();
        if (!parent.hasEventListenerFor(1048576)) {
            sibling = sibling2;
        } else {
            if (!overrideRedirect) {
                parent.sendEvent(1048576, new ConfigureRequest(parent, window, window.previousSibling(), x2, y2, width2, height2, borderWidth, stackMode, valueMask));
                return;
            }
            sibling = sibling2;
        }
        Window.StackMode stackMode2 = stackMode;
        Window sibling4 = sibling;
        short borderWidth3 = borderWidth;
        short borderWidth4 = width2;
        changeWindowGeometry(window, x2, y2, borderWidth4, height2);
        window.setBorderWidth(borderWidth3);
        if (stackMode2 != null) {
            changeWindowZOrder(stackMode2, window, sibling4);
        }
        Window previousSibling = window.previousSibling();
        short s = x2;
        short borderWidth5 = y2;
        short s2 = width2;
        short s3 = height2;
        window.sendEvent(131072, new ConfigureNotify(window, window, previousSibling, s, borderWidth5, s2, s3, borderWidth3, overrideRedirect));
        parent.sendEvent(524288, new ConfigureNotify(parent, window, previousSibling, s, borderWidth5, s2, s3, borderWidth3, overrideRedirect));
    }

    public void reparentWindow(Window window, Window newParent) {
        Window oldParent = window.getParent();
        if (oldParent != null) {
            oldParent.removeChild(window);
        }
        newParent.addChild(window);
    }

    public Window findPointWindow(short rootX, short rootY) {
        return findPointWindow(this.rootWindow, rootX, rootY);
    }

    private Window findPointWindow(Window window, short rootX, short rootY) {
        if (!window.attributes.isMapped() || !window.containsPoint(rootX, rootY)) {
            return null;
        }
        Window child = window.getChildByCoords(rootX, rootY);
        return child != null ? findPointWindow(child, rootX, rootY) : window;
    }

    public void addOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        this.onWindowModificationListeners.add(onWindowModificationListener);
    }

    public void removeOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        this.onWindowModificationListeners.remove(onWindowModificationListener);
    }

    private void triggerOnMapWindow(Window window) {
        for (int i = this.onWindowModificationListeners.size() - 1; i >= 0; i--) {
            this.onWindowModificationListeners.get(i).onMapWindow(window);
        }
    }

    private void triggerOnUnmapWindow(Window window) {
        for (int i = this.onWindowModificationListeners.size() - 1; i >= 0; i--) {
            this.onWindowModificationListeners.get(i).onUnmapWindow(window);
        }
    }

    private void triggerOnChangeWindowZOrder(Window window) {
        for (int i = this.onWindowModificationListeners.size() - 1; i >= 0; i--) {
            this.onWindowModificationListeners.get(i).onChangeWindowZOrder(window);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* renamed from: triggerOnUpdateWindowContent, reason: merged with bridge method [inline-methods] and merged with bridge method [inline-methods] */
    public void lambda$createWindow$0(Window window) {
        for (int i = this.onWindowModificationListeners.size() - 1; i >= 0; i--) {
            this.onWindowModificationListeners.get(i).onUpdateWindowContent(window);
        }
    }

    protected void triggerOnUpdateWindowGeometry(Window window, boolean resized) {
        for (int i = this.onWindowModificationListeners.size() - 1; i >= 0; i--) {
            this.onWindowModificationListeners.get(i).onUpdateWindowGeometry(window, resized);
        }
    }

    public void triggerOnUpdateWindowAttributes(Window window, Bitmask mask) {
        for (int i = this.onWindowModificationListeners.size() - 1; i >= 0; i--) {
            this.onWindowModificationListeners.get(i).onUpdateWindowAttributes(window, mask);
        }
    }

    public void triggerOnModifyWindowProperty(Window window, Property property) {
        for (int i = this.onWindowModificationListeners.size() - 1; i >= 0; i--) {
            this.onWindowModificationListeners.get(i).onModifyWindowProperty(window, property);
        }
    }
}
