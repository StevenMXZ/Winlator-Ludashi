package com.winlator.cmod.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/* loaded from: classes10.dex */
public abstract class ImageUtils {
    private static int calculateInSampleSize(BitmapFactory.Options options, int maxSize) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        int reqWidth = width >= height ? maxSize : 0;
        int reqHeight = height >= width ? maxSize : 0;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static Bitmap getBitmapFromUri(Context context, Uri uri, BitmapFactory.Options options) {
        InputStream is;
        InputStream is2 = null;
        Bitmap bitmap = null;
        try {
            try {
                try {
                    is = context.getContentResolver().openInputStream(uri);
                    if (options != null) {
                        bitmap = BitmapFactory.decodeStream(is, null, options);
                    } else {
                        bitmap = BitmapFactory.decodeStream(is);
                    }
                } catch (Throwable th) {
                    if (0 != 0) {
                        try {
                            is2.close();
                        } catch (IOException e) {
                        }
                    }
                    throw th;
                }
            } catch (IOException e2) {
                e2.printStackTrace();
                if (0 != 0) {
                    is2.close();
                }
            }
            if (is != null) {
                is.close();
            }
        } catch (IOException e3) {
        }
        return bitmap;
    }

    public static Bitmap getBitmapFromUri(Context context, Uri uri) {
        return getBitmapFromUri(context, uri, (BitmapFactory.Options) null);
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:17:0x0025 -> B:6:0x0034). Please report as a decompilation issue!!! */
    public static Bitmap getBitmapFromUri(Context context, Uri uri, int maxSize) {
        InputStream is = null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        try {
            try {
                try {
                    is = context.getContentResolver().openInputStream(uri);
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(is, null, options);
                    int inSampleSize = calculateInSampleSize(options, maxSize);
                    options.inJustDecodeBounds = false;
                    options.inSampleSize = inSampleSize;
                    if (is != null) {
                        is.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    if (is != null) {
                        is.close();
                    }
                }
            } catch (IOException e2) {
            }
            return getBitmapFromUri(context, uri, options);
        } catch (Throwable th) {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e3) {
                }
            }
            throw th;
        }
    }

    public static boolean save(Bitmap bitmap, File output, Bitmap.CompressFormat compressFormat, int quality) {
        FileOutputStream fos = null;
        try {
            try {
                fos = new FileOutputStream(output);
                boolean compress = bitmap.compress(compressFormat, quality, fos);
                try {
                    fos.flush();
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return compress;
            } catch (Exception e2) {
                e2.printStackTrace();
                if (fos != null) {
                    try {
                        fos.flush();
                        fos.close();
                        return false;
                    } catch (IOException e3) {
                        e3.printStackTrace();
                        return false;
                    }
                }
                return false;
            }
        } catch (Throwable th) {
            if (fos != null) {
                try {
                    fos.flush();
                    fos.close();
                } catch (IOException e4) {
                    e4.printStackTrace();
                }
            }
            throw th;
        }
    }
}
