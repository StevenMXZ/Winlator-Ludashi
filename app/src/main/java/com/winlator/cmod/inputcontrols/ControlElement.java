package com.winlator.cmod.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import androidx.core.graphics.ColorUtils;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.core.CubicBezierInterpolator;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.xserver.XServer;
import java.util.Arrays;
import org.bouncycastle.i18n.TextBundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class ControlElement {
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float STICK_SENSITIVITY = 2.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    private PointF currentPosition;
    private byte iconId;
    private final InputControlsView inputControlsView;
    private CubicBezierInterpolator interpolator;
    private byte orientation;
    private Range range;
    private RangeScroller scroller;
    private Object touchTime;
    private short x;
    private short y;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";

    public enum Type {
        BUTTON,
        D_PAD,
        RANGE_BUTTON,
        STICK,
        TRACKPAD;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) {
                names[i] = types[i].name().replace("_", "-");
            }
            return names;
        }
    }

    public enum Shape {
        CIRCLE,
        RECT,
        ROUND_RECT,
        SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) {
                names[i] = shapes[i].name().replace("_", " ");
            }
            return names;
        }
    }

    public enum Range {
        FROM_A_TO_Z(26),
        FROM_0_TO_9(10),
        FROM_F1_TO_F12(12),
        FROM_NP0_TO_NP9(10);

        public final byte max;

        Range(int max) {
            this.max = (byte) max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) {
                names[i] = ranges[i].name().replace("_", " ");
            }
            return names;
        }
    }

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        setBinding(Binding.NONE);
        this.scroller = null;
        if (this.type == Type.STICK) {
            this.bindings[0] = Binding.KEY_W;
            this.bindings[1] = Binding.KEY_D;
            this.bindings[2] = Binding.KEY_S;
            this.bindings[3] = Binding.KEY_A;
        } else if (this.type == Type.D_PAD) {
            this.bindings[0] = Binding.GAMEPAD_DPAD_UP;
            this.bindings[1] = Binding.GAMEPAD_DPAD_RIGHT;
            this.bindings[2] = Binding.GAMEPAD_DPAD_DOWN;
            this.bindings[3] = Binding.GAMEPAD_DPAD_LEFT;
        } else if (this.type == Type.TRACKPAD) {
            this.bindings[0] = Binding.GAMEPAD_RIGHT_THUMB_UP;
            this.bindings[1] = Binding.GAMEPAD_RIGHT_THUMB_RIGHT;
            this.bindings[2] = Binding.GAMEPAD_RIGHT_THUMB_DOWN;
            this.bindings[3] = Binding.GAMEPAD_RIGHT_THUMB_LEFT;
        } else if (this.type == Type.RANGE_BUTTON) {
            this.scroller = new RangeScroller(this.inputControlsView, this);
        }
        this.text = "";
        this.iconId = (byte) 0;
        this.range = null;
        this.boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return this.type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public int getBindingCount() {
        return this.bindings.length;
    }

    public void setBindingCount(int bindingCount) {
        this.bindings = new Binding[bindingCount];
        setBinding(Binding.NONE);
        this.states = new boolean[bindingCount];
        this.boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return this.shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        this.boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return this.range != null ? this.range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return this.orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        this.boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return this.toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    public Binding getBindingAt(int index) {
        return index < this.bindings.length ? this.bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= this.bindings.length) {
            int oldLength = this.bindings.length;
            this.bindings = (Binding[]) Arrays.copyOf(this.bindings, index + 1);
            Arrays.fill(this.bindings, oldLength - 1, this.bindings.length, Binding.NONE);
            this.states = new boolean[this.bindings.length];
            this.boundingBoxNeedsUpdate = true;
        }
        this.bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(this.bindings, binding);
    }

    public float getScale() {
        return this.scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        this.boundingBoxNeedsUpdate = true;
    }

    public short getX() {
        return this.x;
    }

    public void setX(int x) {
        this.x = (short) x;
        this.boundingBoxNeedsUpdate = true;
    }

    public short getY() {
        return this.y;
    }

    public void setY(int y) {
        this.y = (short) y;
        this.boundingBoxNeedsUpdate = true;
    }

    public boolean isSelected() {
        return this.selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public byte getIconId() {
        return this.iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte) iconId;
    }

    public Rect getBoundingBox() {
        if (this.boundingBoxNeedsUpdate) {
            computeBoundingBox();
        }
        return this.boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = this.inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;
        switch (this.type) {
            case BUTTON:
                switch (this.shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int) (snappingSize * 2.5f);
                        halfHeight = (int) (snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
            case D_PAD:
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            case TRACKPAD:
            case STICK:
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            case RANGE_BUTTON:
                halfWidth = snappingSize * ((this.bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;
                if (this.orientation == 1) {
                    halfWidth = halfHeight;
                    halfHeight = halfWidth;
                    break;
                }
                break;
        }
        int halfWidth2 = (int) (halfWidth * this.scale);
        int halfHeight2 = (int) (halfHeight * this.scale);
        this.boundingBox.set(this.x - halfWidth2, this.y - halfHeight2, this.x + halfWidth2, this.y + halfHeight2);
        this.boundingBoxNeedsUpdate = false;
        return this.boundingBox;
    }

    private String getDisplayText() {
        if (this.text != null && !this.text.isEmpty()) {
            return this.text;
        }
        Binding binding = getBindingAt(0);
        String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
        if (text.length() > 7) {
            String[] parts = text.split(" ");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                sb.append(part.charAt(0));
            }
            return (binding.isMouse() ? "M" : "") + ((Object) sb);
        }
        return text;
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        paint.setTextSize(48.0f);
        return (48.0f * desiredWidth) / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        switch (range) {
            case FROM_A_TO_Z:
                String text = String.valueOf((char) (index + 65));
                return text;
            case FROM_0_TO_9:
                String text2 = String.valueOf((index + 1) % 10);
                return text2;
            case FROM_F1_TO_F12:
                String text3 = "F" + (index + 1);
                return text3;
            case FROM_NP0_TO_NP9:
                String text4 = "NP" + ((index + 1) % 10);
                return text4;
            default:
                return "";
        }
    }

    private boolean isEngaged() {
        return this.currentPointerId != -1 || (this.toggleSwitch && this.selected);
    }

    public void draw(Canvas canvas) {
        int fillColor;
        float cy;
        int i;
        int i2;
        Paint paint;
        ControlElement controlElement;
        Rect boundingBox;
        int primaryColor;
        float strokeWidth;
        int fillColor2;
        float minTextSize;
        int primaryColor2;
        int fillColor3;
        float strokeWidth2;
        Rect boundingBox2;
        Range range;
        Path path;
        float strokeWidth3;
        int primaryColor3;
        Rect boundingBox3;
        ControlElement controlElement2;
        Paint paint2;
        float minTextSize2;
        int snappingSize;
        float startX;
        Path path2;
        float minTextSize3;
        int oldColor;
        Range range2;
        Rect boundingBox4;
        float strokeWidth4;
        int fillColor4;
        int primaryColor4;
        Paint paint3;
        Path path3;
        int snappingSize2;
        Range range3;
        Paint paint4;
        float minTextSize4;
        int snappingSize3 = this.inputControlsView.getSnappingSize();
        Paint paint5 = this.inputControlsView.getPaint();
        int primaryColor5 = this.inputControlsView.getPrimaryColor();
        int fillColor5 = ColorUtils.setAlphaComponent(primaryColor5, 70);
        paint5.setColor(this.selected ? this.inputControlsView.getSecondaryColor() : primaryColor5);
        paint5.setStyle(Paint.Style.STROKE);
        float strokeWidth5 = 0.25f * snappingSize3;
        paint5.setStrokeWidth(strokeWidth5);
        Rect boundingBox5 = getBoundingBox();
        switch (this.type) {
            case BUTTON:
                float cx = boundingBox5.centerX();
                float cy2 = boundingBox5.centerY();
                if (!isEngaged()) {
                    fillColor = fillColor5;
                    cy = cy2;
                } else {
                    paint5.setStyle(Paint.Style.FILL);
                    paint5.setColor(fillColor5);
                    switch (this.shape) {
                        case RECT:
                            fillColor = fillColor5;
                            cy = cy2;
                            canvas.drawRect(boundingBox5, paint5);
                            break;
                        case ROUND_RECT:
                            fillColor = fillColor5;
                            cy = cy2;
                            float r = boundingBox5.height() * 0.5f;
                            canvas.drawRoundRect(boundingBox5.left, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, r, r, paint5);
                            break;
                        case SQUARE:
                            float r2 = snappingSize3 * 0.75f * this.scale;
                            fillColor = fillColor5;
                            cy = cy2;
                            canvas.drawRoundRect(boundingBox5.left, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, r2, r2, paint5);
                            break;
                        case CIRCLE:
                            canvas.drawCircle(cx, cy2, boundingBox5.width() * 0.5f, paint5);
                            fillColor = fillColor5;
                            cy = cy2;
                            break;
                        default:
                            fillColor = fillColor5;
                            cy = cy2;
                            break;
                    }
                }
                paint5.setStyle(Paint.Style.STROKE);
                if (this.selected) {
                    i = this.inputControlsView.getSecondaryColor();
                } else {
                    i = primaryColor5;
                }
                paint5.setColor(i);
                paint5.setStrokeWidth(strokeWidth5);
                switch (this.shape) {
                    case RECT:
                        canvas.drawRect(boundingBox5, paint5);
                        break;
                    case ROUND_RECT:
                        float radius = boundingBox5.height() * 0.5f;
                        canvas.drawRoundRect(boundingBox5.left, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, radius, radius, paint5);
                        break;
                    case SQUARE:
                        float radius2 = snappingSize3 * 0.75f * this.scale;
                        canvas.drawRoundRect(boundingBox5.left, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, radius2, radius2, paint5);
                        break;
                    case CIRCLE:
                        canvas.drawCircle(cx, cy, boundingBox5.width() * 0.5f, paint5);
                        break;
                }
                if (this.iconId > 0) {
                    drawIcon(canvas, cx, cy, boundingBox5.width(), boundingBox5.height(), this.iconId);
                    break;
                } else {
                    String text = getDisplayText();
                    paint5.setTextSize(Math.min(getTextSizeForWidth(paint5, text, boundingBox5.width() - (strokeWidth5 * 2.0f)), snappingSize3 * 2 * this.scale));
                    paint5.setTextAlign(Paint.Align.CENTER);
                    paint5.setStyle(Paint.Style.FILL);
                    paint5.setColor(primaryColor5);
                    canvas.drawText(text, this.x, this.y - ((paint5.descent() + paint5.ascent()) * 0.5f), paint5);
                    break;
                }
            case D_PAD:
                float cx2 = boundingBox5.centerX();
                float cy3 = boundingBox5.centerY();
                float offsetX = snappingSize3 * 2 * this.scale;
                float offsetY = snappingSize3 * 3 * this.scale;
                float start = snappingSize3 * this.scale;
                Path path4 = this.inputControlsView.getPath();
                path4.reset();
                path4.moveTo(cx2, cy3 - start);
                path4.lineTo(cx2 - offsetX, cy3 - offsetY);
                path4.lineTo(cx2 - offsetX, boundingBox5.top);
                path4.lineTo(cx2 + offsetX, boundingBox5.top);
                path4.lineTo(cx2 + offsetX, cy3 - offsetY);
                path4.close();
                path4.moveTo(cx2 - start, cy3);
                path4.lineTo(cx2 - offsetY, cy3 - offsetX);
                path4.lineTo(boundingBox5.left, cy3 - offsetX);
                path4.lineTo(boundingBox5.left, cy3 + offsetX);
                path4.lineTo(cx2 - offsetY, cy3 + offsetX);
                path4.close();
                path4.moveTo(cx2, cy3 + start);
                path4.lineTo(cx2 - offsetX, cy3 + offsetY);
                path4.lineTo(cx2 - offsetX, boundingBox5.bottom);
                path4.lineTo(cx2 + offsetX, boundingBox5.bottom);
                path4.lineTo(cx2 + offsetX, cy3 + offsetY);
                path4.close();
                path4.moveTo(cx2 + start, cy3);
                path4.lineTo(cx2 + offsetY, cy3 - offsetX);
                path4.lineTo(boundingBox5.right, cy3 - offsetX);
                path4.lineTo(boundingBox5.right, cy3 + offsetX);
                path4.lineTo(cx2 + offsetY, cy3 + offsetX);
                path4.close();
                boolean anyActive = false;
                boolean[] zArr = this.states;
                int length = zArr.length;
                int i3 = 0;
                while (true) {
                    if (i3 < length) {
                        boolean s = zArr[i3];
                        if (!s) {
                            i3++;
                        } else {
                            anyActive = true;
                        }
                    }
                }
                if (anyActive) {
                    paint5.setStyle(Paint.Style.FILL);
                    paint5.setColor(fillColor5);
                    canvas.drawPath(path4, paint5);
                }
                paint5.setStyle(Paint.Style.STROKE);
                if (this.selected) {
                    i2 = this.inputControlsView.getSecondaryColor();
                } else {
                    i2 = primaryColor5;
                }
                paint5.setColor(i2);
                paint5.setStrokeWidth(strokeWidth5);
                canvas.drawPath(path4, paint5);
                break;
            case TRACKPAD:
                float radius3 = boundingBox5.height() * 0.15f;
                if (!isEngaged()) {
                    paint = paint5;
                    controlElement = this;
                    boundingBox = boundingBox5;
                    primaryColor = primaryColor5;
                    strokeWidth = strokeWidth5;
                    fillColor2 = fillColor5;
                } else {
                    paint5.setStyle(Paint.Style.FILL);
                    fillColor2 = fillColor5;
                    paint5.setColor(fillColor2);
                    paint = paint5;
                    controlElement = this;
                    boundingBox = boundingBox5;
                    primaryColor = primaryColor5;
                    strokeWidth = strokeWidth5;
                    canvas.drawRoundRect(boundingBox5.left, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, radius3, radius3, paint);
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(controlElement.selected ? controlElement.inputControlsView.getSecondaryColor() : primaryColor);
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius3, radius3, paint);
                float offset = strokeWidth * 2.5f;
                float innerStrokeWidth = strokeWidth * 2.0f;
                float innerHeight = boundingBox.height() - (2.0f * offset);
                float radius4 = ((innerHeight / boundingBox.height()) * radius3) - ((innerStrokeWidth * 0.5f) + (strokeWidth * 0.5f));
                paint.setStrokeWidth(innerStrokeWidth);
                canvas.drawRoundRect(boundingBox.left + offset, boundingBox.top + offset, boundingBox.right - offset, boundingBox.bottom - offset, radius4, radius4, paint);
                break;
            case STICK:
                int cx3 = boundingBox5.centerX();
                int cy4 = boundingBox5.centerY();
                int oldColor2 = paint5.getColor();
                paint5.setStyle(Paint.Style.STROKE);
                paint5.setColor(this.selected ? this.inputControlsView.getSecondaryColor() : primaryColor5);
                canvas.drawCircle(cx3, cy4, boundingBox5.height() * 0.5f, paint5);
                float thumbstickX = getCurrentPosition().x;
                float thumbstickY = getCurrentPosition().y;
                short thumbRadius = (short) (snappingSize3 * 3.5f * this.scale);
                int engagedAlpha = isEngaged() ? 120 : 50;
                paint5.setStyle(Paint.Style.FILL);
                paint5.setColor(ColorUtils.setAlphaComponent(primaryColor5, engagedAlpha));
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint5);
                paint5.setStyle(Paint.Style.STROKE);
                paint5.setColor(oldColor2);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius + (strokeWidth5 * 0.5f), paint5);
                break;
            case RANGE_BUTTON:
                Range range4 = getRange();
                int oldColor3 = paint5.getColor();
                float radius5 = snappingSize3 * 0.75f * this.scale;
                float elementSize = this.scroller.getElementSize();
                float minTextSize5 = snappingSize3 * 2 * this.scale;
                float scrollOffset = this.scroller.getScrollOffset();
                byte[] rangeIndex = this.scroller.getRangeIndex();
                Path path5 = this.inputControlsView.getPath();
                path5.reset();
                if (this.orientation != 0) {
                    Paint paint6 = paint5;
                    ControlElement controlElement3 = this;
                    float lineLeft = boundingBox5.left + (strokeWidth5 * 0.5f);
                    float lineRight = boundingBox5.right - (strokeWidth5 * 0.5f);
                    float startY = boundingBox5.top;
                    if (!isEngaged()) {
                        minTextSize = minTextSize5;
                        primaryColor2 = primaryColor5;
                        fillColor3 = fillColor5;
                        strokeWidth2 = strokeWidth5;
                        boundingBox2 = boundingBox5;
                        range = range4;
                        path = path5;
                    } else {
                        paint6.setStyle(Paint.Style.FILL);
                        paint6.setColor(fillColor5);
                        primaryColor2 = primaryColor5;
                        fillColor3 = fillColor5;
                        strokeWidth2 = strokeWidth5;
                        minTextSize = minTextSize5;
                        boundingBox2 = boundingBox5;
                        range = range4;
                        path = path5;
                        canvas.drawRoundRect(boundingBox5.left, startY, boundingBox5.right, boundingBox5.bottom, radius5, radius5, paint6);
                    }
                    paint6.setStyle(Paint.Style.STROKE);
                    paint6.setColor(oldColor3);
                    canvas.drawRoundRect(boundingBox2.left, startY, boundingBox2.right, boundingBox2.bottom, radius5, radius5, paint6);
                    canvas.save();
                    path.addRoundRect(boundingBox2.left, startY, boundingBox2.right, boundingBox2.bottom, radius5, radius5, Path.Direction.CW);
                    canvas.clipPath(controlElement3.inputControlsView.getPath());
                    byte i4 = rangeIndex[0];
                    float startY2 = startY - (scrollOffset % elementSize);
                    while (i4 < rangeIndex[1]) {
                        paint6.setStyle(Paint.Style.STROKE);
                        paint6.setColor(oldColor3);
                        if (startY2 <= boundingBox2.top || startY2 >= boundingBox2.bottom) {
                            strokeWidth3 = strokeWidth2;
                            primaryColor3 = primaryColor2;
                            boundingBox3 = boundingBox2;
                            controlElement2 = controlElement3;
                            paint2 = paint6;
                            minTextSize2 = minTextSize;
                        } else {
                            strokeWidth3 = strokeWidth2;
                            primaryColor3 = primaryColor2;
                            boundingBox3 = boundingBox2;
                            minTextSize2 = minTextSize;
                            controlElement2 = controlElement3;
                            paint2 = paint6;
                            canvas.drawLine(lineLeft, startY2, lineRight, startY2, paint6);
                        }
                        Range range5 = range;
                        String text2 = getRangeTextForIndex(range5, i4);
                        if (startY2 < boundingBox3.bottom && startY2 + elementSize > boundingBox3.top) {
                            paint2.setStyle(Paint.Style.FILL);
                            paint2.setColor(primaryColor3);
                            paint2.setTextSize(Math.min(getTextSizeForWidth(paint2, text2, boundingBox3.width() - (strokeWidth3 * 2.0f)), minTextSize2));
                            paint2.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text2, controlElement2.x, ((elementSize * 0.5f) + startY2) - ((paint2.descent() + paint2.ascent()) * 0.5f), paint2);
                        }
                        startY2 += elementSize;
                        i4 = (byte) (i4 + 1);
                        range = range5;
                        minTextSize = minTextSize2;
                        paint6 = paint2;
                        controlElement3 = controlElement2;
                        boundingBox2 = boundingBox3;
                        primaryColor2 = primaryColor3;
                        strokeWidth2 = strokeWidth3;
                    }
                    Paint paint7 = paint6;
                    paint7.setStyle(Paint.Style.STROKE);
                    paint7.setColor(oldColor3);
                    canvas.restore();
                    break;
                } else {
                    float lineTop = boundingBox5.top + (strokeWidth5 * 0.5f);
                    float lineBottom = boundingBox5.bottom - (strokeWidth5 * 0.5f);
                    float startX2 = boundingBox5.left;
                    if (!isEngaged()) {
                        snappingSize = snappingSize3;
                        startX = startX2;
                        path2 = path5;
                        minTextSize3 = minTextSize5;
                        oldColor = oldColor3;
                        range2 = range4;
                    } else {
                        paint5.setStyle(Paint.Style.FILL);
                        paint5.setColor(fillColor5);
                        startX = startX2;
                        path2 = path5;
                        minTextSize3 = minTextSize5;
                        oldColor = oldColor3;
                        snappingSize = snappingSize3;
                        range2 = range4;
                        canvas.drawRoundRect(startX2, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, radius5, radius5, paint5);
                    }
                    paint5.setStyle(Paint.Style.STROKE);
                    paint5.setColor(oldColor);
                    canvas.drawRoundRect(startX, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, radius5, radius5, paint5);
                    canvas.save();
                    path2.addRoundRect(startX, boundingBox5.top, boundingBox5.right, boundingBox5.bottom, radius5, radius5, Path.Direction.CW);
                    Path path6 = path2;
                    canvas.clipPath(path6);
                    float startX3 = startX - (scrollOffset % elementSize);
                    byte i5 = rangeIndex[0];
                    while (i5 < rangeIndex[1]) {
                        int index = i5 % range2.max;
                        paint5.setStyle(Paint.Style.STROKE);
                        paint5.setColor(oldColor);
                        if (startX3 <= boundingBox5.left || startX3 >= boundingBox5.right) {
                            boundingBox4 = boundingBox5;
                            strokeWidth4 = strokeWidth5;
                            fillColor4 = fillColor5;
                            primaryColor4 = primaryColor5;
                            paint3 = paint5;
                            path3 = path6;
                            snappingSize2 = snappingSize;
                            range3 = range2;
                        } else {
                            boundingBox4 = boundingBox5;
                            strokeWidth4 = strokeWidth5;
                            fillColor4 = fillColor5;
                            primaryColor4 = primaryColor5;
                            paint3 = paint5;
                            path3 = path6;
                            snappingSize2 = snappingSize;
                            range3 = range2;
                            canvas.drawLine(startX3, lineTop, startX3, lineBottom, paint3);
                        }
                        String text3 = getRangeTextForIndex(range3, index);
                        if (startX3 >= boundingBox4.right || startX3 + elementSize <= boundingBox4.left) {
                            paint4 = paint3;
                            minTextSize4 = minTextSize3;
                        } else {
                            paint4 = paint3;
                            paint4.setStyle(Paint.Style.FILL);
                            paint4.setColor(primaryColor4);
                            float minTextSize6 = minTextSize3;
                            paint4.setTextSize(Math.min(getTextSizeForWidth(paint4, text3, elementSize - (strokeWidth4 * 2.0f)), minTextSize6));
                            paint4.setTextAlign(Paint.Align.CENTER);
                            minTextSize4 = minTextSize6;
                            canvas.drawText(text3, (elementSize * 0.5f) + startX3, this.y - ((paint4.descent() + paint4.ascent()) * 0.5f), paint4);
                        }
                        startX3 += elementSize;
                        i5 = (byte) (i5 + 1);
                        minTextSize3 = minTextSize4;
                        paint5 = paint4;
                        primaryColor5 = primaryColor4;
                        fillColor5 = fillColor4;
                        strokeWidth5 = strokeWidth4;
                        boundingBox5 = boundingBox4;
                        range2 = range3;
                        path6 = path3;
                        snappingSize = snappingSize2;
                    }
                    Paint paint8 = paint5;
                    paint8.setStyle(Paint.Style.STROKE);
                    paint8.setColor(oldColor);
                    canvas.restore();
                    break;
                }
                break;
        }
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        Paint paint = this.inputControlsView.getPaint();
        Bitmap icon = this.inputControlsView.getIcon((byte) iconId);
        if (icon == null) {
            return;
        }
        paint.setColorFilter(this.inputControlsView.getColorFilter());
        int margin = (int) (this.inputControlsView.getSnappingSize() * ((this.shape == Shape.CIRCLE || this.shape == Shape.SQUARE) ? 2.0f : 1.0f) * this.scale);
        int halfSize = (int) ((Math.min(width, height) - margin) * 0.5f);
        Rect srcRect = new Rect(0, 0, icon.getWidth(), icon.getHeight());
        Rect dstRect = new Rect((int) (cx - halfSize), (int) (cy - halfSize), (int) (halfSize + cx), (int) (halfSize + cy));
        canvas.drawBitmap(icon, srcRect, dstRect, paint);
        paint.setColorFilter(null);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put(ContentProfile.MARK_TYPE, this.type.name());
            elementJSONObject.put("shape", this.shape.name());
            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : this.bindings) {
                bindingsJSONArray.put(binding.name());
            }
            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(this.scale));
            elementJSONObject.put("x", this.x / this.inputControlsView.getMaxWidth());
            elementJSONObject.put("y", this.y / this.inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", this.toggleSwitch);
            elementJSONObject.put(TextBundle.TEXT_ENTRY, this.text);
            elementJSONObject.put("iconId", this.iconId);
            if (this.type == Type.RANGE_BUTTON && this.range != null) {
                elementJSONObject.put("range", this.range.name());
                if (this.orientation != 0) {
                    elementJSONObject.put("orientation", this.orientation);
                }
            }
            return elementJSONObject;
        } catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        return getBoundingBox().contains((int) (x + 0.5f), (int) (0.5f + y));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        if (this.toggleSwitch) {
            return false;
        }
        return binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3;
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (this.currentPointerId != -1 || !containsPoint(x, y)) {
            return false;
        }
        this.currentPointerId = pointerId;
        if (this.type == Type.BUTTON) {
            if (isKeepButtonPressedAfterMinTime()) {
                this.touchTime = Long.valueOf(System.currentTimeMillis());
            }
            if (!this.toggleSwitch || !this.selected) {
                this.inputControlsView.handleInputEvent(getBindingAt(0), true);
            }
            this.inputControlsView.invalidate();
            return true;
        }
        if (this.type == Type.RANGE_BUTTON) {
            this.scroller.handleTouchDown(x, y);
            this.inputControlsView.invalidate();
            return true;
        }
        if (this.type == Type.TRACKPAD) {
            if (this.currentPosition == null) {
                this.currentPosition = new PointF();
            }
            this.currentPosition.set(x, y);
        }
        return handleTouchMove(pointerId, x, y);
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        float localY;
        float offsetX;
        byte b;
        boolean state;
        float f;
        if (pointerId == this.currentPointerId && this.type == Type.BUTTON) {
            if (!containsPoint(x, y)) {
                handleTouchUp(pointerId);
            }
            return true;
        }
        if (pointerId == this.currentPointerId && (this.type == Type.D_PAD || this.type == Type.STICK || this.type == Type.TRACKPAD)) {
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            TouchpadView touchpadView = this.inputControlsView.getTouchpadView();
            if (this.type == Type.TRACKPAD) {
                if (this.currentPosition == null) {
                    this.currentPosition = new PointF();
                }
                float[] deltaPoint = touchpadView.computeDeltaPoint(this.currentPosition.x, this.currentPosition.y, x, y);
                localY = deltaPoint[0];
                offsetX = deltaPoint[1];
                this.currentPosition.set(x, y);
            } else {
                float localX = x - boundingBox.left;
                float localY2 = y - boundingBox.top;
                float offsetX2 = localX - radius;
                float offsetY = localY2 - radius;
                float distance = Mathf.lengthSq(radius - localX, radius - localY2);
                if (distance > radius * radius) {
                    float angle = (float) Math.atan2(offsetY, offsetX2);
                    radius = radius;
                    offsetX2 = (float) (Math.cos(angle) * radius);
                    offsetY = (float) (Math.sin(angle) * radius);
                }
                float deltaX = Mathf.clamp(offsetX2 / radius, -1.0f, 1.0f);
                localY = deltaX;
                offsetX = Mathf.clamp(offsetY / radius, -1.0f, 1.0f);
            }
            if (this.type == Type.STICK) {
                if (this.currentPosition == null) {
                    this.currentPosition = new PointF();
                }
                this.currentPosition.x = boundingBox.left + (localY * radius) + radius;
                this.currentPosition.y = boundingBox.top + (offsetX * radius) + radius;
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    float magnitude = (float) Math.sqrt((localY * localY) + (offsetX * offsetX));
                    float finalX = 0.0f;
                    float finalY = 0.0f;
                    if (magnitude > 0.15f) {
                        float normalizedX = localY / magnitude;
                        float normalizedY = offsetX / magnitude;
                        float scaledMagnitude = Math.min(Math.max(0.0f, magnitude - 0.01f) * 2.0f, 1.0f);
                        finalX = normalizedX * scaledMagnitude;
                        finalY = normalizedY * scaledMagnitude;
                    }
                    this.inputControlsView.handleStickInput(firstBinding, finalX, finalY);
                    for (byte i = 0; i < 4; i = (byte) (i + 1)) {
                        this.states[i] = true;
                    }
                } else {
                    byte b2 = 1;
                    byte b3 = 3;
                    boolean[] states = {offsetX <= -0.15f, localY >= 0.15f, offsetX >= 0.15f, localY <= -0.15f};
                    byte i2 = 0;
                    for (byte b4 = 4; i2 < b4; b4 = 4) {
                        float value = (i2 == b2 || i2 == b3) ? localY : offsetX;
                        Binding binding = getBindingAt(i2);
                        boolean state2 = binding.isMouseMove() ? states[i2] || states[(i2 + 2) % 4] : states[i2];
                        this.inputControlsView.handleInputEvent(binding, state2, value);
                        this.states[i2] = state2;
                        i2 = (byte) (i2 + 1);
                        b2 = 1;
                        b3 = 3;
                    }
                }
                this.inputControlsView.invalidate();
                return true;
            }
            if (this.type == Type.TRACKPAD) {
                Binding firstBinding2 = getBindingAt(0);
                if (firstBinding2.isGamepad()) {
                    if (this.interpolator == null) {
                        this.interpolator = new CubicBezierInterpolator();
                    }
                    this.interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                    float valueX = localY;
                    float valueY = offsetX;
                    if (Math.abs(valueX) > 4.0f) {
                        f = 2.0f;
                        valueX *= 2.0f;
                    } else {
                        f = 2.0f;
                    }
                    if (Math.abs(valueY) > 4.0f) {
                        valueY *= f;
                    }
                    float interpX = this.interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueX / 20.0f)));
                    float interpY = this.interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueY / 20.0f)));
                    float finalX2 = Mathf.clamp(Mathf.sign(valueX) * interpX, -1.0f, 1.0f);
                    float finalY2 = Mathf.clamp(Mathf.sign(valueY) * interpY, -1.0f, 1.0f);
                    this.inputControlsView.handleStickInput(firstBinding2, finalX2, finalY2);
                    for (byte i3 = 0; i3 < 4; i3 = (byte) (i3 + 1)) {
                        this.states[i3] = true;
                    }
                    return true;
                }
                boolean[] states2 = {offsetX <= -0.8f, localY >= 0.8f, offsetX >= 0.8f, localY <= -0.8f};
                int cursorDx = 0;
                int cursorDy = 0;
                byte i4 = 0;
                while (i4 < 4) {
                    float value2 = (i4 == 1 || i4 == 3) ? localY : offsetX;
                    Binding binding2 = getBindingAt(i4);
                    if (Math.abs(value2) > 6.0f) {
                        value2 *= 1.25f;
                    }
                    if (binding2 == Binding.MOUSE_MOVE_LEFT || binding2 == Binding.MOUSE_MOVE_RIGHT) {
                        cursorDx = Mathf.roundPoint(value2);
                    } else if (binding2 == Binding.MOUSE_MOVE_UP || binding2 == Binding.MOUSE_MOVE_DOWN) {
                        cursorDy = Mathf.roundPoint(value2);
                    } else {
                        this.inputControlsView.handleInputEvent(binding2, states2[i4], value2);
                        this.states[i4] = states2[i4];
                    }
                    i4 = (byte) (i4 + 1);
                }
                if (cursorDx != 0 || cursorDy != 0) {
                    XServer xServer = this.inputControlsView.getXServer();
                    if (xServer.isRelativeMouseMovement()) {
                        xServer.getWinHandler().mouseEvent(1, cursorDx, cursorDy, 0);
                        return true;
                    }
                    this.inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
                    return true;
                }
                return true;
            }
            byte b5 = 4;
            byte b6 = 1;
            boolean[] states3 = {offsetX <= -0.3f, localY >= 0.3f, offsetX >= 0.3f, localY <= -0.3f};
            byte i5 = 0;
            while (i5 < b5) {
                float value3 = (i5 == b6 || i5 == 3) ? localY : offsetX;
                Binding binding3 = getBindingAt(i5);
                if (binding3.isMouseMove()) {
                    if (states3[i5]) {
                        b = 4;
                    } else {
                        b = 4;
                        if (!states3[(i5 + 2) % 4]) {
                            state = false;
                        }
                    }
                    state = true;
                } else {
                    b = 4;
                    state = states3[i5];
                }
                this.inputControlsView.handleInputEvent(binding3, state, value3);
                this.states[i5] = state;
                i5 = (byte) (i5 + 1);
                b5 = b;
                b6 = 1;
            }
            return true;
        }
        if (pointerId == this.currentPointerId && this.type == Type.RANGE_BUTTON) {
            this.scroller.handleTouchMove(x, y);
            return true;
        }
        return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (pointerId != this.currentPointerId) {
            return false;
        }
        if (this.type == Type.BUTTON) {
            final Binding binding = getBindingAt(0);
            long now = System.currentTimeMillis();
            if (isKeepButtonPressedAfterMinTime() && this.touchTime != null) {
                long held = now - ((Long) this.touchTime).longValue();
                long delay = Math.max(0L, 300 - held);
                this.inputControlsView.postDelayed(new Runnable() { // from class: com.winlator.cmod.inputcontrols.ControlElement$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        ControlElement.this.lambda$handleTouchUp$0(binding);
                    }
                }, delay);
                this.touchTime = null;
                this.inputControlsView.invalidate();
            } else {
                if (!this.toggleSwitch || this.selected) {
                    this.inputControlsView.handleInputEvent(binding, false);
                }
                if (this.toggleSwitch) {
                    this.selected = !this.selected;
                }
            }
            this.inputControlsView.invalidate();
        } else if (this.type == Type.RANGE_BUTTON || this.type == Type.D_PAD || this.type == Type.STICK || this.type == Type.TRACKPAD) {
            for (byte i = 0; i < this.states.length; i = (byte) (i + 1)) {
                if (this.states[i]) {
                    this.inputControlsView.handleInputEvent(getBindingAt(i), false);
                }
                this.states[i] = false;
            }
            if (this.type == Type.RANGE_BUTTON) {
                this.scroller.handleTouchUp();
            }
            if (this.type == Type.STICK) {
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    this.inputControlsView.handleStickInput(firstBinding, 0.0f, 0.0f);
                }
                this.currentPosition = null;
            }
            if (this.type == Type.TRACKPAD) {
                Binding firstBinding2 = getBindingAt(0);
                if (firstBinding2.isGamepad()) {
                    this.inputControlsView.handleStickInput(firstBinding2, 0.0f, 0.0f);
                }
                this.currentPosition = null;
            }
            this.inputControlsView.invalidate();
        }
        this.currentPointerId = -1;
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$handleTouchUp$0(Binding binding) {
        this.inputControlsView.handleInputEvent(binding, false);
        this.inputControlsView.invalidate();
    }

    public PointF getCurrentPosition() {
        if (this.currentPosition == null) {
            this.currentPosition = new PointF(this.x, this.y);
        }
        return this.currentPosition;
    }

    public void setCurrentPosition(float x, float y) {
        if (this.currentPosition == null) {
            this.currentPosition = new PointF();
        }
        this.currentPosition.set(x, y);
        this.inputControlsView.invalidate();
    }
}
