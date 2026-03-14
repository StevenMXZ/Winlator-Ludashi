package com.winlator.cmod.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupWindow;
import com.ludashi.benchmark.R;
import com.winlator.cmod.MainActivity;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.core.WineThemeManager;
import java.io.File;

/* loaded from: classes14.dex */
public class ImagePickerView extends View implements View.OnClickListener {
    private final Bitmap icon;

    public ImagePickerView(Context context) {
        this(context, null);
    }

    public ImagePickerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImagePickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.icon = BitmapFactory.decodeResource(context.getResources(), R.drawable.icon_image_picker);
        setBackgroundResource(R.drawable.combo_box);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(this);
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        float rectSize = height - UnitUtils.dpToPx(12.0f);
        float startX = ((width - rectSize) * 0.5f) - UnitUtils.dpToPx(16.0f);
        float startY = (height - rectSize) * 0.5f;
        Paint paint = new Paint(1);
        Rect srcRect = new Rect(0, 0, this.icon.getWidth(), this.icon.getHeight());
        RectF dstRect = new RectF(startX, startY, startX + rectSize, startY + rectSize);
        canvas.drawBitmap(this.icon, srcRect, dstRect, paint);
    }

    @Override // android.view.View.OnClickListener
    public void onClick(View anchor) {
        final Context context = getContext();
        final File userWallpaperFile = WineThemeManager.getUserWallpaperFile(context);
        View view = LayoutInflater.from(context).inflate(R.layout.image_picker_view, (ViewGroup) null);
        ImageView imageView = (ImageView) view.findViewById(R.id.ImageView);
        if (userWallpaperFile.isFile()) {
            imageView.setImageBitmap(BitmapFactory.decodeFile(userWallpaperFile.getPath()));
        } else {
            imageView.setImageResource(R.drawable.wallpaper);
        }
        final PopupWindow[] popupWindow = {null};
        View browseButton = view.findViewById(R.id.BTBrowse);
        browseButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.widget.ImagePickerView$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                ImagePickerView.lambda$onClick$0(context, popupWindow, view2);
            }
        });
        View removeButton = view.findViewById(R.id.BTRemove);
        if (userWallpaperFile.isFile()) {
            removeButton.setVisibility(0);
            removeButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.widget.ImagePickerView$$ExternalSyntheticLambda1
                @Override // android.view.View.OnClickListener
                public final void onClick(View view2) {
                    ImagePickerView.lambda$onClick$1(userWallpaperFile, popupWindow, view2);
                }
            });
        }
        popupWindow[0] = AppUtils.showPopupWindow(anchor, view, 200, 240);
    }

    static /* synthetic */ void lambda$onClick$0(Context context, PopupWindow[] popupWindow, View v) {
        MainActivity activity = (MainActivity) context;
        Intent intent = new Intent("android.intent.action.PICK");
        intent.setType("image/*");
        popupWindow[0].dismiss();
        activity.startActivityForResult(intent, 5);
    }

    static /* synthetic */ void lambda$onClick$1(File userWallpaperFile, PopupWindow[] popupWindow, View v) {
        FileUtils.delete(userWallpaperFile);
        popupWindow[0].dismiss();
    }
}
