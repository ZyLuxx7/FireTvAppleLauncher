package com.appletv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import java.io.File;

/**
 * How big a theme's wallpaper may be and what size it is decoded at. A 1 GB TV cannot afford a
 * picture decoded at full size: a 65535x2159 image is about 283 MB even in RGB_565.
 */
final class WallpaperSize {
    private WallpaperSize() { }

    /** 4K UHD (3840x2160, 8.3 M pixels) and a little more; anything bigger is refused. */
    static final long MAX_PIXELS = 8_400_000L;

    static boolean accepted(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    /** The largest power-of-two sample size that still covers the screen in both directions. */
    static int sampleSize(int width, int height, int screenW, int screenH) {
        int sample = 1;
        while (width / (sample * 2) >= screenW && height / (sample * 2) >= screenH) sample *= 2;
        return sample;
    }

    /**
     * {inDensity, inTargetDensity} that scale a (sampled) picture down to what CENTER_CROP shows: the
     * side that limits the cover lands on the screen size. Null when it is already that small.
     */
    static int[] coverScale(int width, int height, int screenW, int screenH) {
        if ((long) screenW * height >= (long) screenH * width) {   // width limits the cover
            return width > screenW ? new int[]{width, screenW} : null;
        }
        return height > screenH ? new int[]{height, screenH} : null;
    }

    static Bitmap decode(File file, DisplayMetrics displayMetrics) {
        if (file == null || displayMetrics == null || !file.isFile()) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        int width = options.outWidth;
        int height = options.outHeight;
        if (width <= 0 || height <= 0) return null;

        int sample = sampleSize(width, height, displayMetrics.widthPixels,
                displayMetrics.heightPixels);
        if (!accepted(width / sample, height / sample)) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        int[] scale = coverScale(width / sample, height / sample,
                displayMetrics.widthPixels, displayMetrics.heightPixels);
        if (scale != null) {
            options.inScaled = true;
            options.inDensity = scale[0];
            options.inTargetDensity = scale[1];
        }
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }
}
