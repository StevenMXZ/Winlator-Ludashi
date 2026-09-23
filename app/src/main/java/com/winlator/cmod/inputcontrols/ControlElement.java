package com.winlator.cmod.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.view.MotionEvent;

import androidx.core.graphics.ColorUtils;

import com.winlator.cmod.core.CubicBezierInterpolator;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 2.0f;
    public static final float STICK_CROSS_ZONE = 0.3f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }
    }
    public enum Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }
    }
    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);
        public final byte max;

        Range(int max) {
            this.max = (byte)max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }
    }
    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private short x;
    private short y;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    private float opacity = 1.0f;
    private int customColor = 0; 
    private ColorFilter customColorFilter = null;
    private ColorFilter themeColorFilter = null;
    private int themeColorFilterColor = 1; 
    private boolean mouseMoveMode = false;
    private String customIconPath = null;
    private Bitmap customIconBitmap = null;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";
    private byte iconId;
    private Range range;
    private byte orientation;
    private PointF currentPosition;
    private RangeScroller scroller;
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        setBinding(Binding.NONE);
        scroller = null;

        if (type == Type.STICK) {
            bindings[0] = Binding.KEY_W;
            bindings[1] = Binding.KEY_D;
            bindings[2] = Binding.KEY_S;
            bindings[3] = Binding.KEY_A;
        }
        else if(type == Type.D_PAD){
            bindings[0] = Binding.GAMEPAD_DPAD_UP;
            bindings[1] = Binding.GAMEPAD_DPAD_RIGHT;
            bindings[2] = Binding.GAMEPAD_DPAD_DOWN;
            bindings[3] = Binding.GAMEPAD_DPAD_LEFT;
        }
        else if (type == Type.TRACKPAD) {
            bindings[0] = Binding.GAMEPAD_RIGHT_THUMB_UP;
            bindings[1] = Binding.GAMEPAD_RIGHT_THUMB_RIGHT;
            bindings[2] = Binding.GAMEPAD_RIGHT_THUMB_DOWN;
            bindings[3] = Binding.GAMEPAD_RIGHT_THUMB_LEFT;
        }
        else if (type == Type.RANGE_BUTTON) {
            scroller = new RangeScroller(inputControlsView, this);
        }

        text = "";
        iconId = 0;
        range = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public int getBindingCount() {
        return bindings.length;
    }

    public int getFirstBindingIndex() {
        for (int i = 0; i < bindings.length; i++) if (bindings[i] != Binding.NONE) return i;
        return 0;
    }

    public int getLastBindingIndex() {
        int last = 0;
        for (int i = 0; i < bindings.length; i++) if (bindings[i] != Binding.NONE) last = i;
        return last;
    }

    public void setBindingCount(int bindingCount) {
        bindings = new Binding[bindingCount];
        setBinding(Binding.NONE);
        states = new boolean[bindingCount];
        boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    public boolean isMouseMoveMode() {
        return mouseMoveMode;
    }

    public void setMouseMoveMode(boolean mouseMoveMode) {
        this.mouseMoveMode = mouseMoveMode;
    }

    public String getCustomIconPath() {
        return customIconPath;
    }

    public void setCustomIconPath(String path) {
        if (customIconBitmap != null) {
            customIconBitmap.recycle();
            customIconBitmap = null;
        }
        this.customIconPath = path;
    }

    public Binding getBindingAt(int index) {
        return index < bindings.length ? bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= bindings.length) {
            int oldLength = bindings.length;
            bindings = Arrays.copyOf(bindings, index+1);
            Arrays.fill(bindings, oldLength, bindings.length, Binding.NONE);
            states = new boolean[bindings.length];
            boundingBoxNeedsUpdate = true;
        }
        bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(bindings, binding);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        boundingBoxNeedsUpdate = true;
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0f, Math.min(1f, opacity));
    }

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short)x;
        boundingBoxNeedsUpdate = true;
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short)y;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public byte getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte)iconId;
    }

    public int getCustomColor() {
        return customColor;
    }

    public void setCustomColor(int customColor) {
        this.customColor = customColor;
        this.customColorFilter = customColor != 0 ? new PorterDuffColorFilter(customColor, PorterDuff.Mode.SRC_IN) : null;
    }

    public boolean hasCustomColor() {
        return customColor != 0;
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 8;
                halfHeight = snappingSize * 8;
                break;
            }
            case TRACKPAD:
            case STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                halfWidth = snappingSize * ((bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
        }

        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        boundingBoxNeedsUpdate = false;
        return boundingBox;
    }

    private String getBindingTextAt(int index) {
        Binding binding = getBindingAt(index);
        String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
        if (text.length() > 7) {
            String[] parts = text.split(" ");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) sb.append(part.charAt(0));
            return (binding.isMouse() ? "M" : "")+ sb;
        }
        else return text;
    }

    private String getDisplayText() {
        if (text != null && !text.isEmpty()) {
            return text;
        }
        else if (type == Type.BUTTON) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bindings.length; i++) {
                if (bindings[i] != Binding.NONE) {
                    if (sb.length() > 0) sb.append("+");
                    sb.append(getBindingTextAt(i));
                }
            }
            return sb.length() > 0 ? sb.toString() : getBindingTextAt(0);
        }
        else return getBindingTextAt(0);
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        String text = "";
        switch (range) {
            case FROM_A_TO_Z:
                text = String.valueOf((char)(65 + index));
                break;
            case FROM_0_TO_9:
                text = String.valueOf((index + 1) % 10);
                break;
            case FROM_F1_TO_F12:
                text = "F"+(index + 1);
                break;
            case FROM_NP0_TO_NP9:
                text = "NP"+((index + 1) % 10);
                break;
        }
        return text;
    }

    private static Binding getRangeBindingForIndex(Range range, int index) {
        switch (range) {
            case FROM_A_TO_Z:
                return Binding.valueOf("KEY_"+((char)(65 + index)));
            case FROM_0_TO_9:
                return Binding.valueOf("KEY_"+((index + 1) % 10));
            case FROM_F1_TO_F12:
                return Binding.valueOf("KEY_F"+(index + 1));
            case FROM_NP0_TO_NP9:
                return Binding.valueOf("KEY_KP_"+((index + 1) % 10));
            default:
                return Binding.NONE;
        }
    }

    private static int rgba(int a, int r, int g, int b) {
        return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }

    
    
    
    private float getEffectiveOpacity() {
        float combined = opacity * inputControlsView.getOverlayOpacity();
        return inputControlsView.isEditMode() ? Math.max(0.35f, combined) : combined;
    }

    
    
    
    private int withAlpha(int color, int alpha) {
        int scaledAlpha = (int)(alpha * getEffectiveOpacity());
        if (scaledAlpha < 0) scaledAlpha = 0;
        if (scaledAlpha > 255) scaledAlpha = 255;
        return (color & 0x00ffffff) | ((scaledAlpha & 0xff) << 24);
    }

    private static final int WINLATOR_BLUE = 0xff2184ff;
    private static final int DARK_SURFACE = 0xff06111d;
    private static final int EDGE_SOFT = 0xff7fa8d8;

    
    
    private int getThemeColor() {
        if (customColor != 0) return customColor;
        ControlsProfile profile = inputControlsView.getProfile();
        int schemeColor = profile != null ? profile.getThemeColor() : 0;
        return schemeColor != 0 ? schemeColor : 0xffffffff;
    }

    private boolean isVisuallyPressed() {
        return selected || currentPointerId != -1;
    }

    private Rect expandedBounds(Rect rect, int padding) {
        return new Rect(rect.left - padding, rect.top - padding, rect.right + padding, rect.bottom + padding);
    }

    private void invalidateSelf() {
        Rect rect = getBoundingBox();
        int pad = Math.max(4, inputControlsView.getSnappingSize() * 2);
        if (type == Type.STICK) pad = Math.max(pad, rect.width() / 4);
        inputControlsView.invalidateElement(expandedBounds(rect, pad));
    }

    private void setupPaint(Paint paint, Paint.Style style, int color, float strokeWidth) {
        paint.setStyle(style);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColorFilter(null);
        paint.setAntiAlias(true);
    }

    private void drawCenteredText(Canvas canvas, String text, float cx, float cy, float maxWidth, float maxSize, int color) {
        Paint paint = inputControlsView.getPaint();
        int alpha = (color >>> 24) & 0xff;
        setupPaint(paint, Paint.Style.FILL, withAlpha(color, alpha), 0);
        paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, maxWidth), maxSize));
        canvas.drawText(text, cx, cy - ((paint.descent() + paint.ascent()) * 0.5f), paint);
    }

    private int colorForText(String text) {
        return getThemeColor();
    }

    private void drawSoftRoundRect(Canvas canvas, float left, float top, float right, float bottom, float radius, boolean active) {
        Paint paint = inputControlsView.getPaint();

        
        setupPaint(paint, Paint.Style.STROKE, withAlpha(getThemeColor(), active ? 64 : 16), Math.max(1f, inputControlsView.getSnappingSize() * 0.55f * scale));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

        setupPaint(paint, Paint.Style.STROKE, withAlpha(EDGE_SOFT, active ? 70 : 28), Math.max(1f, inputControlsView.getSnappingSize() * 0.22f * scale));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

        setupPaint(paint, Paint.Style.FILL, active ? withAlpha(getThemeColor(), 190) : withAlpha(DARK_SURFACE, 135), 0);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

        
        float inset = Math.max(1f, inputControlsView.getSnappingSize() * 0.35f * scale);
        setupPaint(paint, Paint.Style.STROKE, withAlpha(0xffffffff, active ? 58 : 24), Math.max(1f, inputControlsView.getSnappingSize() * 0.08f * scale));
        canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset, Math.max(0, radius - inset), Math.max(0, radius - inset), paint);
    }

    private void drawSoftCircle(Canvas canvas, float cx, float cy, float radius, boolean active, int accentColor, boolean fillActive) {
        Paint paint = inputControlsView.getPaint();

        setupPaint(paint, Paint.Style.STROKE, withAlpha(getThemeColor(), active ? 72 : 18), Math.max(1f, inputControlsView.getSnappingSize() * 0.55f * scale));
        canvas.drawCircle(cx, cy, radius, paint);

        setupPaint(paint, Paint.Style.STROKE, withAlpha(EDGE_SOFT, active ? 74 : 30), Math.max(1f, inputControlsView.getSnappingSize() * 0.20f * scale));
        canvas.drawCircle(cx, cy, radius, paint);

        setupPaint(paint, Paint.Style.FILL, active && fillActive ? withAlpha(accentColor, 190) : withAlpha(DARK_SURFACE, 140), 0);
        canvas.drawCircle(cx, cy, radius, paint);

        setupPaint(paint, Paint.Style.STROKE, withAlpha(0xffffffff, active ? 44 : 18), Math.max(1f, inputControlsView.getSnappingSize() * 0.08f * scale));
        canvas.drawCircle(cx, cy, radius * 0.88f, paint);
    }

    private void drawTriangle(Canvas canvas, float cx, float cy, float size, int direction, int color) {
        Paint paint = inputControlsView.getPaint();
        Path path = inputControlsView.getPath();
        path.reset();

        if (direction == 0) { 
            path.moveTo(cx, cy - size);
            path.lineTo(cx - size * 0.85f, cy + size * 0.65f);
            path.lineTo(cx + size * 0.85f, cy + size * 0.65f);
        }
        else if (direction == 1) { 
            path.moveTo(cx + size, cy);
            path.lineTo(cx - size * 0.65f, cy - size * 0.85f);
            path.lineTo(cx - size * 0.65f, cy + size * 0.85f);
        }
        else if (direction == 2) { 
            path.moveTo(cx, cy + size);
            path.lineTo(cx - size * 0.85f, cy - size * 0.65f);
            path.lineTo(cx + size * 0.85f, cy - size * 0.65f);
        }
        else { 
            path.moveTo(cx - size, cy);
            path.lineTo(cx + size * 0.65f, cy - size * 0.85f);
            path.lineTo(cx + size * 0.65f, cy + size * 0.85f);
        }
        path.close();

        setupPaint(paint, Paint.Style.FILL, color, 0);
        canvas.drawPath(path, paint);
    }

    private void drawDPadPiece(Canvas canvas, float cx, float cy, float size, int direction, boolean active) {
        float radius = size * 0.28f;
        drawSoftRoundRect(canvas, cx - size * 0.5f, cy - size * 0.5f, cx + size * 0.5f, cy + size * 0.5f, radius, active);
        drawTriangle(canvas, cx, cy, size * 0.22f, direction, active ? withAlpha(0xffffffff, 255) : withAlpha(getThemeColor(), 255));
    }

    private void drawMouseGlyph(Canvas canvas, float cx, float cy, float size, int color) {
        Paint paint = inputControlsView.getPaint();
        float w = size * 0.42f;
        float h = size * 0.62f;
        float r = w * 0.5f;
        setupPaint(paint, Paint.Style.STROKE, color, Math.max(1f, size * 0.035f));
        canvas.drawRoundRect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, r, r, paint);
        canvas.drawLine(cx, cy - h * 0.48f, cx, cy - h * 0.15f, paint);
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        Rect boundingBox = getBoundingBox();
        float strokeWidth = Math.max(1f, snappingSize * 0.16f * scale);
        boolean active = isVisuallyPressed();

        switch (type) {
            case BUTTON: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float w = boundingBox.width();
                float h = boundingBox.height();

                String label = getDisplayText();
                int accent = colorForText(label);
                boolean faceButton = "A".equalsIgnoreCase(label) || "B".equalsIgnoreCase(label) || "X".equalsIgnoreCase(label) || "Y".equalsIgnoreCase(label);

                switch (shape) {
                    case CIRCLE: {
                        
                        drawSoftCircle(canvas, cx, cy, w * 0.5f, active, accent, true);
                        break;
                    }
                    case RECT:
                    case ROUND_RECT: {
                        float radius = h * 0.45f;
                        drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);
                        break;
                    }
                    case SQUARE: {
                        float radius = snappingSize * 1.05f * scale;
                        drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);
                        break;
                    }
                }

                if (iconId > 0 || customIconPath != null) {
                    drawIcon(canvas, cx, cy, w, h, iconId);
                }
                else {
                    int textColor = active ? 0xffffffff : accent;
                    drawCenteredText(canvas, label, cx, cy, w * 0.55f, snappingSize * 2.1f * scale, textColor);
                }
                break;
            }

            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();

                
                
                
                
                float base = Math.min(boundingBox.width(), boundingBox.height());
                float piece = base * 0.30f;       
                float dist = piece * 1.15f;       
                float centerSize = piece * 0.26f; 

                
                setupPaint(paint, Paint.Style.FILL, withAlpha(DARK_SURFACE, active ? 72 : 42), 0);
                canvas.drawRoundRect(
                        cx - centerSize * 0.5f,
                        cy - centerSize * 0.5f,
                        cx + centerSize * 0.5f,
                        cy + centerSize * 0.5f,
                        centerSize * 0.22f,
                        centerSize * 0.22f,
                        paint
                );

                drawDPadPiece(canvas, cx, cy - dist, piece, 0, states.length > 0 && states[0]);
                drawDPadPiece(canvas, cx + dist, cy, piece, 1, states.length > 1 && states[1]);
                drawDPadPiece(canvas, cx, cy + dist, piece, 2, states.length > 2 && states[2]);
                drawDPadPiece(canvas, cx - dist, cy, piece, 3, states.length > 3 && states[3]);
                break;
            }

            case RANGE_BUTTON: {
                Range range = getRange();
                float radius = snappingSize * 0.9f * scale;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Path path = inputControlsView.getPath();
                path.reset();

                drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);

                canvas.save();
                path.addRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                canvas.clipPath(path);

                
                
                boolean isPressingSegment = currentPointerId != -1 && !scroller.isScrolling();
                Binding pressedBinding = scroller.getBinding();

                if (orientation == 0) {
                    float startX = boundingBox.left - scrollOffset % elementSize;
                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        String text = getRangeTextForIndex(range, index);
                        boolean segmentPressed = isPressingSegment && pressedBinding == getRangeBindingForIndex(range, index);
                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            if (segmentPressed) {
                                setupPaint(paint, Paint.Style.FILL, withAlpha(getThemeColor(), 150), 0);
                                canvas.drawRect(startX, boundingBox.top, startX + elementSize, boundingBox.bottom, paint);
                            }
                            drawCenteredText(canvas, text, startX + elementSize * 0.5f, y, elementSize - strokeWidth * 2, minTextSize, segmentPressed ? 0xffffffff : getThemeColor());
                        }
                        startX += elementSize;
                    }
                }
                else {
                    float startY = boundingBox.top - scrollOffset % elementSize;
                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        String text = getRangeTextForIndex(range, i);
                        boolean segmentPressed = isPressingSegment && pressedBinding == getRangeBindingForIndex(range, i);
                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            if (segmentPressed) {
                                setupPaint(paint, Paint.Style.FILL, withAlpha(getThemeColor(), 150), 0);
                                canvas.drawRect(boundingBox.left, startY, boundingBox.right, startY + elementSize, paint);
                            }
                            drawCenteredText(canvas, text, x, startY + elementSize * 0.5f, boundingBox.width() - strokeWidth * 2, minTextSize, segmentPressed ? 0xffffffff : getThemeColor());
                        }
                        startY += elementSize;
                    }
                }
                canvas.restore();
                break;
            }

            case STICK: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float outer = boundingBox.height() * 0.5f;
                float innerRing = outer * 0.52f;
                float thumbRadius = outer * 0.42f;

                
                setupPaint(paint, Paint.Style.FILL, withAlpha(DARK_SURFACE, active ? 155 : 118), 0);
                canvas.drawCircle(cx, cy, outer, paint);

                
                setupPaint(paint, Paint.Style.STROKE, withAlpha(EDGE_SOFT, active ? 62 : 26), Math.max(1f, snappingSize * 0.20f * scale));
                canvas.drawCircle(cx, cy, outer * 0.96f, paint);
                setupPaint(paint, Paint.Style.STROKE, withAlpha(getThemeColor(), active ? 72 : 20), Math.max(1f, snappingSize * 0.50f * scale));
                canvas.drawCircle(cx, cy, outer * 0.98f, paint);

                
                setupPaint(paint, Paint.Style.STROKE, withAlpha(getThemeColor(), active ? 235 : 205), Math.max(1f, snappingSize * 0.22f * scale));
                canvas.drawCircle(cx, cy, innerRing, paint);

                float thumbstickX = getCurrentPosition().x;
                float thumbstickY = getCurrentPosition().y;

                setupPaint(paint, Paint.Style.FILL, withAlpha(DARK_SURFACE, 190), 0);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                setupPaint(paint, Paint.Style.STROKE, withAlpha(EDGE_SOFT, 45), Math.max(1f, snappingSize * 0.12f * scale));
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius * 0.96f, paint);
                break;
            }

            case TRACKPAD: {
                
                float radius = boundingBox.height() * 0.18f;
                drawSoftRoundRect(canvas, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, active);
                drawMouseGlyph(canvas, boundingBox.centerX(), boundingBox.centerY(), Math.min(boundingBox.width(), boundingBox.height()) * 0.45f, withAlpha(getThemeColor(), active ? 220 : 95));
                break;
            }
        }
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        Paint paint = inputControlsView.getPaint();

        Bitmap icon = null;
        boolean isCustom = false;
        if (customIconPath != null) {
            if (customIconBitmap == null || customIconBitmap.isRecycled()) {
                try {
                    customIconBitmap = android.graphics.BitmapFactory.decodeFile(customIconPath);
                } catch (Exception ignored) {}
            }
            if (customIconBitmap != null) {
                icon = customIconBitmap;
                isCustom = true;
            }
        }
        if (icon == null) {
            icon = inputControlsView.getIcon((byte)iconId);
        }
        if (icon == null) return;

        paint.setColor(0xffffffff);
        int iconAlpha = (int)(230 * getEffectiveOpacity());
        paint.setAlpha(Math.max(0, Math.min(255, iconAlpha)));

        if (!isCustom) {
            if (customColorFilter != null) {
                paint.setColorFilter(customColorFilter);
            } else {
                int themeColor = getThemeColor();
                if (themeColorFilter == null || themeColorFilterColor != themeColor) {
                    themeColorFilter = new PorterDuffColorFilter(themeColor, PorterDuff.Mode.SRC_IN);
                    themeColorFilterColor = themeColor;
                }
                paint.setColorFilter(themeColorFilter);
            }
        }

        int margin = (int)(inputControlsView.getSnappingSize() * (shape == Shape.CIRCLE || shape == Shape.SQUARE ? 1.65f : 0.85f) * scale);
        int halfSize = (int)((Math.min(width, height) - margin) * 0.5f);

        Rect srcRect = new Rect(0, 0, icon.getWidth(), icon.getHeight());
        Rect dstRect = new Rect((int)(cx - halfSize), (int)(cy - halfSize), (int)(cx + halfSize), (int)(cy + halfSize));
        canvas.drawBitmap(icon, srcRect, dstRect, paint);
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            elementJSONObject.put("x", (float)x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float)y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", toggleSwitch);
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);
            if (opacity != 1.0f) elementJSONObject.put("opacity", opacity);
            if (customColor != 0) elementJSONObject.put("customColor", customColor);
            if (mouseMoveMode) elementJSONObject.put("mouseMoveMode", true);
            if (customIconPath != null) elementJSONObject.put("customIconPath", customIconPath);

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
            }
            return elementJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        return getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    
    
    private void setButtonBindingsActive(boolean active) {
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] != Binding.NONE) inputControlsView.handleInputEvent(bindings[i], active);
        }
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            invalidateSelf();
            if (type == Type.BUTTON) {
                if (mouseMoveMode) {
                    inputControlsView.getTouchpadView().mouseMove(x, y, MotionEvent.ACTION_DOWN);
                    invalidateSelf();
                    return true;
                }
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
                if (!toggleSwitch || !selected) {
                    setButtonBindingsActive(true);
                }
                invalidateSelf();
                return true;
            }
            else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchDown(x, y);
                invalidateSelf();
                return true;
            }
            else {
                if (type == Type.TRACKPAD) {
                    if (currentPosition == null) currentPosition = new PointF();
                    currentPosition.set(x, y);
                }
                return handleTouchMove(pointerId, x, y);
            }
        }
        else return false;
    }

    private boolean usesNativeGamepadDPad() {
        return type == Type.D_PAD &&
               getBindingAt(0) == Binding.GAMEPAD_DPAD_UP &&
               getBindingAt(1) == Binding.GAMEPAD_DPAD_RIGHT &&
               getBindingAt(2) == Binding.GAMEPAD_DPAD_DOWN &&
               getBindingAt(3) == Binding.GAMEPAD_DPAD_LEFT;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && type == Type.BUTTON && mouseMoveMode) {
            inputControlsView.getTouchpadView().mouseMove(x, y, MotionEvent.ACTION_MOVE);
            return true;
        }
        else if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
            float deltaX, deltaY;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            if (type == Type.TRACKPAD) {
                TouchpadView touchpadView = inputControlsView.getTouchpadView();
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            }
            else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;

                float distanceSquared = offsetX * offsetX + offsetY * offsetY;
                if (distanceSquared > radius * radius) {
                    float scale = radius / (float)Math.sqrt(distanceSquared);
                    offsetX *= scale;
                    offsetY *= scale;
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
            }

            if (type == Type.STICK) {
                if (currentPosition == null) currentPosition = new PointF();
                float visualX = boundingBox.left + deltaX * radius + radius;
                float visualY = boundingBox.top + deltaY * radius + radius;
                // Keep the analog values precise, but redraw only when the thumb
                // has moved by at least half a screen pixel.
                boolean redraw = Math.abs(currentPosition.x - visualX) >= 0.5f ||
                                 Math.abs(currentPosition.y - visualY) >= 0.5f;
                if (redraw) currentPosition.set(visualX, visualY);
                float adjDeltaX = (Math.abs(deltaX) < Math.abs(deltaY) * STICK_CROSS_ZONE) ? 0 : deltaX;
                float adjDeltaY = (Math.abs(deltaY) < Math.abs(deltaX) * STICK_CROSS_ZONE) ? 0 : deltaY;
                
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    float magnitude = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    float finalX = 0;
                    float finalY = 0;

                    if (magnitude > STICK_DEAD_ZONE) {
                        float normalizedX = deltaX / magnitude;
                        float normalizedY = deltaY / magnitude;
                        float scaledMagnitude = Math.max(0, magnitude - 0.01f) * STICK_SENSITIVITY;
                        scaledMagnitude = Math.min(scaledMagnitude, 1.0f);
                        finalX = normalizedX * scaledMagnitude;
                        finalY = normalizedY * scaledMagnitude;
                    }

                    inputControlsView.handleStickInput(firstBinding, finalX, finalY);
                    for (byte i = 0; i < 4; i++) {
                        this.states[i] = true;
                    }
                } else {
                    final boolean up = adjDeltaY <= -STICK_DEAD_ZONE;
                    final boolean right = adjDeltaX >= STICK_DEAD_ZONE;
                    final boolean down = adjDeltaY >= STICK_DEAD_ZONE;
                    final boolean left = adjDeltaX <= -STICK_DEAD_ZONE;
                    for (byte i = 0; i < 4; i++) {
                        float value = i == 1 || i == 3 ? adjDeltaX : adjDeltaY;
                        Binding binding = getBindingAt(i);
                        boolean direction = i == 0 ? up : i == 1 ? right : i == 2 ? down : left;
                        boolean opposite = i == 0 ? down : i == 1 ? left : i == 2 ? up : right;
                        boolean state = binding.isMouseMove() ? (direction || opposite) : direction;
                        if (binding.isMouseMove() || states[i] != state) {
                            inputControlsView.handleInputEvent(binding, state, value);
                        }
                        this.states[i] = state;
                    }
                }
                if (redraw) invalidateSelf();
                return true;
            }
            else if (type == Type.TRACKPAD) {
                
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    
                    if (interpolator == null) interpolator = new CubicBezierInterpolator();
                    interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                    
                    float valueX = deltaX;
                    float valueY = deltaY;
                    if (Math.abs(valueX) > TRACKPAD_ACCELERATION_THRESHOLD) valueX *= STICK_SENSITIVITY;
                    if (Math.abs(valueY) > TRACKPAD_ACCELERATION_THRESHOLD) valueY *= STICK_SENSITIVITY;
                    
                    float interpX = interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueX / TRACKPAD_MAX_SPEED)));
                    float interpY = interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueY / TRACKPAD_MAX_SPEED)));
                    
                    float finalX = Mathf.clamp(interpX * Mathf.sign(valueX), -1, 1);
                    float finalY = Mathf.clamp(interpY * Mathf.sign(valueY), -1, 1);
                    
                    
                    inputControlsView.handleStickInput(firstBinding, finalX, finalY);
                    
                    
                    for (byte i = 0; i < 4; i++) {
                        this.states[i] = true;
                    }
                } else {
                    
                    final boolean[] states = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                    int cursorDx = 0;
                    int cursorDy = 0;

                    for (byte i = 0; i < 4; i++) {
                        float value = (i == 1 || i == 3 ? deltaX : deltaY);
                        Binding binding = getBindingAt(i);
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorDx = Mathf.roundPoint(value);
                        }
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorDy = Mathf.roundPoint(value);
                        }
                        else {
                            inputControlsView.handleInputEvent(binding, states[i], value);
                            this.states[i] = states[i];
                        }
                    }

                    if (cursorDx != 0 || cursorDy != 0)  {
                        XServer xServer = inputControlsView.getXServer();
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, cursorDx, cursorDy, 0);
                        else
                            inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
                    }
                }
            }
            else {
                boolean dpadStateChanged = false;
                if (usesNativeGamepadDPad()) {
                    // Retain a direction until the finger moves slightly inside
                    // the threshold. This avoids rapid release/press oscillation.
                    final float releaseZone = DPAD_DEAD_ZONE - 0.05f;
                    final boolean up = deltaY <= -(states[0] ? releaseZone : DPAD_DEAD_ZONE);
                    final boolean right = deltaX >= (states[1] ? releaseZone : DPAD_DEAD_ZONE);
                    final boolean down = deltaY >= (states[2] ? releaseZone : DPAD_DEAD_ZONE);
                    final boolean left = deltaX <= -(states[3] ? releaseZone : DPAD_DEAD_ZONE);
                    if (states[0] == up && states[1] == right &&
                        states[2] == down && states[3] == left) return true;
                    inputControlsView.handleDPadInput(up, right, down, left);
                    this.states[0] = up;
                    this.states[1] = right;
                    this.states[2] = down;
                    this.states[3] = left;
                    dpadStateChanged = true;
                }
                else {
                    final boolean up = deltaY <= -DPAD_DEAD_ZONE;
                    final boolean right = deltaX >= DPAD_DEAD_ZONE;
                    final boolean down = deltaY >= DPAD_DEAD_ZONE;
                    final boolean left = deltaX <= -DPAD_DEAD_ZONE;
                    for (byte i = 0; i < 4; i++) {
                        float value = i == 1 || i == 3 ? deltaX : deltaY;
                        Binding binding = getBindingAt(i);
                        boolean state;
                        switch (i) {
                            case 0: state = up; break;
                            case 1: state = right; break;
                            case 2: state = down; break;
                            default: state = left; break;
                        }
                        if (binding.isMouseMove()) {
                            boolean opposite;
                            switch ((i + 2) % 4) {
                                case 0: opposite = up; break;
                                case 1: opposite = right; break;
                                case 2: opposite = down; break;
                                default: opposite = left; break;
                            }
                            state = state || opposite;
                        }
                        inputControlsView.handleInputEvent(binding, state, value);
                        dpadStateChanged |= this.states[i] != state;
                        this.states[i] = state;
                    }
                }
                if (dpadStateChanged) invalidateSelf();
                return true;
            }

            // The trackpad draws only its pressed state, updated on touch down/up.
            // Moving the finger changes the input, but not the trackpad artwork.
            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            invalidateSelf();
            return true;
        }
        else return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (pointerId == currentPointerId) {
            if (type == Type.BUTTON) {
                if (mouseMoveMode) {
                    inputControlsView.getTouchpadView().mouseMove(0, 0, MotionEvent.ACTION_UP);
                    invalidateSelf();
                }
                else if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                    selected = (System.currentTimeMillis() - (long)touchTime) > BUTTON_MIN_TIME_TO_KEEP_PRESSED;
                    if (!selected) {
                        setButtonBindingsActive(false);
                    }
                    touchTime = null;
                    invalidateSelf();
                }
                else if (!toggleSwitch || selected) {
                    setButtonBindingsActive(false);
                }

                if (!mouseMoveMode && toggleSwitch) {
                    selected = !selected;
                    invalidateSelf();
                }
            }
            else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD) {
                
                
                
                
                if ((type == Type.STICK || type == Type.TRACKPAD) && getBindingAt(0).isGamepad()) {
                    inputControlsView.handleStickInput(getBindingAt(0), 0f, 0f);
                    for (byte i = 0; i < states.length; i++) states[i] = false;
                }
                else if (usesNativeGamepadDPad()) {
                    inputControlsView.handleDPadInput(false, false, false, false);
                    for (byte i = 0; i < states.length; i++) states[i] = false;
                }
                else {
                    for (byte i = 0; i < states.length; i++) {
                        if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                        states[i] = false;
                    }
                }

                if (type == Type.RANGE_BUTTON) {
                    scroller.handleTouchUp();
                }
                else if (type == Type.STICK) {
                    invalidateSelf();
                }

                if (currentPosition != null) currentPosition = null;
            }
            currentPointerId = -1;
            invalidateSelf();
            return true;
        }
        return false;
    }

    public PointF getCurrentPosition() {
        if (currentPosition == null) {
            currentPosition = new PointF(x, y); 
        }
        return currentPosition;
    }

    
    public void setCurrentPosition(float x, float y) {
        if (currentPosition == null) {
            currentPosition = new PointF();
        }
        currentPosition.set(x, y);
        
        invalidateSelf();
    }
}
