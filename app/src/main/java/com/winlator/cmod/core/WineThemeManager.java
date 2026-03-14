package com.winlator.cmod.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import com.ludashi.benchmark.R;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.ScreenInfo;
import java.io.File;

/* loaded from: classes10.dex */
public abstract class WineThemeManager {
    public static final String DEFAULT_DESKTOP_THEME = Theme.LIGHT + "," + BackgroundType.IMAGE + ",#0277bd";

    public enum BackgroundType {
        IMAGE,
        COLOR
    }

    public enum Theme {
        LIGHT,
        DARK
    }

    public static class ThemeInfo {
        public final int backgroundColor;
        public final BackgroundType backgroundType;
        public final Theme theme;

        public ThemeInfo(String value) {
            String[] values = value.split(",");
            this.theme = Theme.valueOf(values[0]);
            if (values.length < 3) {
                this.backgroundColor = Color.parseColor(values[1]);
                this.backgroundType = BackgroundType.IMAGE;
            } else {
                this.backgroundType = BackgroundType.valueOf(values[1]);
                this.backgroundColor = Color.parseColor(values[2]);
            }
        }
    }

    public static void apply(Context context, ThemeInfo themeInfo, ScreenInfo screenInfo) {
        Throwable th;
        File rootDir = ImageFs.find(context).getRootDir();
        File userRegFile = new File(rootDir, "/home/xuser/.wine/user.reg");
        String background = Color.red(themeInfo.backgroundColor) + " " + Color.green(themeInfo.backgroundColor) + " " + Color.blue(themeInfo.backgroundColor);
        if (themeInfo.backgroundType == BackgroundType.IMAGE) {
            createWallpaperBMPFile(context, screenInfo);
        }
        WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile);
        try {
            if (themeInfo.backgroundType == BackgroundType.IMAGE) {
                try {
                    registryEditor.setStringValue("Control Panel\\Desktop", "Wallpaper", "/home/xuser/.cache/wallpaper.bmp");
                } catch (Throwable th2) {
                    th = th2;
                    try {
                        registryEditor.close();
                        throw th;
                    } catch (Throwable th3) {
                        th.addSuppressed(th3);
                        throw th;
                    }
                }
            } else {
                registryEditor.removeValue("Control Panel\\Desktop", "Wallpaper");
            }
            try {
                if (themeInfo.theme == Theme.LIGHT) {
                    registryEditor.setStringValue("Control Panel\\Colors", "ActiveBorder", "245 245 245");
                    registryEditor.setStringValue("Control Panel\\Colors", "ActiveTitle", "96 125 139");
                    registryEditor.setStringValue("Control Panel\\Colors", "Background", background);
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonAlternateFace", "245 245 245");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonDkShadow", "158 158 158");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonFace", "245 245 245");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonHilight", "224 224 224");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonLight", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonShadow", "158 158 158");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonText", "0 0 0");
                    registryEditor.setStringValue("Control Panel\\Colors", "GradientActiveTitle", "96 125 139");
                    registryEditor.setStringValue("Control Panel\\Colors", "GradientInactiveTitle", "117 117 117");
                    registryEditor.setStringValue("Control Panel\\Colors", "GrayText", "158 158 158");
                    registryEditor.setStringValue("Control Panel\\Colors", "Hilight", "2 136 209");
                    registryEditor.setStringValue("Control Panel\\Colors", "HilightText", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "HotTrackingColor", "2 136 209");
                    registryEditor.setStringValue("Control Panel\\Colors", "InactiveBorder", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitle", "117 117 117");
                    registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitleText", "200 200 200");
                    registryEditor.setStringValue("Control Panel\\Colors", "InfoText", "0 0 0");
                    registryEditor.setStringValue("Control Panel\\Colors", "InfoWindow", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "Menu", "245 245 245");
                    registryEditor.setStringValue("Control Panel\\Colors", "MenuBar", "245 245 245");
                    registryEditor.setStringValue("Control Panel\\Colors", "MenuHilight", "2 136 209");
                    registryEditor.setStringValue("Control Panel\\Colors", "MenuText", "0 0 0");
                    registryEditor.setStringValue("Control Panel\\Colors", "Scrollbar", "245 245 245");
                    registryEditor.setStringValue("Control Panel\\Colors", "TitleText", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "Window", "245 245 245");
                    registryEditor.setStringValue("Control Panel\\Colors", "WindowFrame", "158 158 158");
                    registryEditor.setStringValue("Control Panel\\Colors", "WindowText", "0 0 0");
                } else if (themeInfo.theme == Theme.DARK) {
                    registryEditor.setStringValue("Control Panel\\Colors", "ActiveBorder", "48 48 48");
                    registryEditor.setStringValue("Control Panel\\Colors", "ActiveTitle", "33 33 33");
                    registryEditor.setStringValue("Control Panel\\Colors", "Background", background);
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonAlternateFace", "33 33 33");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonDkShadow", "0 0 0");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonFace", "33 33 33");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonHilight", "48 48 48");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonLight", "48 48 48");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonShadow", "0 0 0");
                    registryEditor.setStringValue("Control Panel\\Colors", "ButtonText", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "GradientActiveTitle", "33 33 33");
                    registryEditor.setStringValue("Control Panel\\Colors", "GradientInactiveTitle", "33 33 33");
                    registryEditor.setStringValue("Control Panel\\Colors", "GrayText", "117 117 117");
                    registryEditor.setStringValue("Control Panel\\Colors", "Hilight", "2 136 209");
                    registryEditor.setStringValue("Control Panel\\Colors", "HilightText", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "HotTrackingColor", "2 136 209");
                    registryEditor.setStringValue("Control Panel\\Colors", "InactiveBorder", "48 48 48");
                    registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitle", "33 33 33");
                    registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitleText", "117 117 117");
                    registryEditor.setStringValue("Control Panel\\Colors", "InfoText", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "InfoWindow", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "Menu", "33 33 33");
                    registryEditor.setStringValue("Control Panel\\Colors", "MenuBar", "48 48 48");
                    registryEditor.setStringValue("Control Panel\\Colors", "MenuHilight", "2 136 209");
                    registryEditor.setStringValue("Control Panel\\Colors", "MenuText", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "Scrollbar", "48 48 48");
                    registryEditor.setStringValue("Control Panel\\Colors", "TitleText", "255 255 255");
                    registryEditor.setStringValue("Control Panel\\Colors", "Window", "48 48 48");
                    registryEditor.setStringValue("Control Panel\\Colors", "WindowFrame", "0 0 0");
                    registryEditor.setStringValue("Control Panel\\Colors", "WindowText", "255 255 255");
                }
                registryEditor.close();
            } catch (Throwable th4) {
                th = th4;
                registryEditor.close();
                throw th;
            }
        } catch (Throwable th5) {
            th = th5;
        }
    }

    private static void createWallpaperBMPFile(Context context, ScreenInfo screenInfo) {
        int outputHeight = screenInfo.height;
        int outputWidth = screenInfo.width;
        Bitmap outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(1);
        Canvas canvas = new Canvas(outputBitmap);
        File userWallpaperFile = getUserWallpaperFile(context);
        if (userWallpaperFile.isFile()) {
            Bitmap image = BitmapFactory.decodeFile(userWallpaperFile.getPath());
            Rect srcRect = new Rect(0, 0, image.getWidth(), image.getHeight());
            Rect dstRect = new Rect(0, 0, outputWidth, outputHeight);
            canvas.drawBitmap(image, srcRect, dstRect, paint);
        } else {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inTargetDensity = 240;
            Bitmap wallpaperBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.wallpaper, options);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(-16689253);
            canvas.drawRect(0.0f, 0.0f, outputWidth, outputHeight * 0.5f, paint);
            paint.setColor(-16615491);
            canvas.drawRect(0.0f, outputHeight * 0.5f, outputWidth, outputHeight, paint);
            float targetSize = outputHeight * 0.6666667f;
            float centerX = (outputWidth - targetSize) * 0.5f;
            float centerY = (outputHeight - targetSize) * 0.5f;
            Rect srcRect2 = new Rect(0, 0, wallpaperBitmap.getWidth(), wallpaperBitmap.getHeight());
            RectF dstRect2 = new RectF(centerX, centerY, centerX + targetSize, centerY + targetSize);
            canvas.drawBitmap(wallpaperBitmap, srcRect2, dstRect2, paint);
        }
        ImageFs imageFs = ImageFs.find(context);
        MSBitmap.create(outputBitmap, new File(imageFs.getRootDir(), "/home/xuser/.cache/wallpaper.bmp"));
    }

    public static File getUserWallpaperFile(Context context) {
        return new File(ImageFs.find(context).getRootDir(), "/home/xuser/.config/user-wallpaper.png");
    }
}
