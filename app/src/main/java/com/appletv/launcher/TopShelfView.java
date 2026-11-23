package com.appletv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Crossfades app-provided banner art, with a lightweight app-icon/wave fallback for sparse banners. */
public final class TopShelfView extends View {
    private final Paint artworkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable previousArt;
    private Drawable currentArt;
    private String previousPackage = "";
    private String currentPackage = "";
    private int previousColor;
    private int currentColor;
    private float crossfade = 1f;
    private float slideProgress = 1f;
    private int slideX;
    private int slideY;
    private ValueAnimator animator;
    private LinearGradient vignette;
    private RadialGradient iconFade;
    private float density;

    public TopShelfView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
    }

    void show(String packageName, Drawable banner, Drawable icon, int accent, boolean animate,
              int directionX, int directionY) {
        if (packageName.equals(currentPackage) && currentArt != null) return;
        if (animator != null) {
            ValueAnimator previous = animator;
            animator = null;
            previous.cancel();
        }
        animate().cancel();
        setAlpha(1f);
        previousArt = currentArt;
        previousPackage = currentPackage;
        previousColor = currentColor;
        currentPackage = packageName;
        slideX = Integer.compare(directionX, 0);
        slideY = Integer.compare(directionY, 0);
        Drawable candidate = copy(banner);
        if (isSuitableBanner(candidate)) {
            currentArt = candidate;
        } else {
            currentArt = copy(icon);
        }
        if (currentArt instanceof BitmapDrawable) {
            ((BitmapDrawable) currentArt).setFilterBitmap(true);
        }
        currentColor = accent == 0 ? 0xFF303746 : accent;
        crossfade = animate && previousArt != null ? 0f : 1f;
        slideProgress = crossfade;
        setVisibility(VISIBLE);
        if (crossfade < 1f) {
            ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
            animator = next;
            next.setDuration(180);
            next.setInterpolator(new DecelerateInterpolator());
            next.addUpdateListener(value -> {
                crossfade = (float) value.getAnimatedValue();
                slideProgress = crossfade;
                invalidate();
            });
            next.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (animator != animation) return;
                    previousArt = null;
                    previousPackage = "";
                    previousColor = currentColor;
                    crossfade = 1f;
                    slideProgress = 1f;
                    animator = null;
                    invalidate();
                }
            });
            next.start();
        } else {
            previousArt = null;
            previousPackage = "";
            invalidate();
        }
    }

    void hide(boolean animate) {
        if (animator != null) animator.cancel();
        if (!animate || getVisibility() != VISIBLE) {
            clear();
            return;
        }
        animate().alpha(0f).setDuration(180).withEndAction(this::clear).start();
    }

    private void clear() {
        setAlpha(1f);
        setVisibility(GONE);
        previousArt = null;
        currentArt = null;
        previousPackage = "";
        currentPackage = "";
    }

    private Drawable copy(Drawable source) {
        if (source == null) return null;
        Drawable.ConstantState state = source.getConstantState();
        return state == null ? source : state.newDrawable(getResources()).mutate();
    }

    private boolean isSuitableBanner(Drawable drawable) {
        return isLandscapeBanner(drawable);
    }

    boolean hasFlatBackdrop(Drawable drawable) {
        return hasFlatBackdrop(drawable, getResources());
    }

    static Drawable removeWhiteBackdrop(Drawable drawable, android.content.res.Resources resources) {
        if (drawable == null) return null;
        int width = drawable instanceof BitmapDrawable
                ? ((BitmapDrawable) drawable).getBitmap().getWidth()
                : Math.max(1, drawable.getIntrinsicWidth());
        int height = drawable instanceof BitmapDrawable
                ? ((BitmapDrawable) drawable).getBitmap().getHeight()
                : Math.max(1, drawable.getIntrinsicHeight());
        if (width <= 1 || height <= 1 || width > 2048 || height > 2048) return drawable;

        Bitmap source = drawable instanceof BitmapDrawable
                ? ((BitmapDrawable) drawable).getBitmap() : null;
        if (source == null && drawable.getConstantState() == null) return drawable;
        Bitmap bitmap = source == null ? Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                : source.copy(Bitmap.Config.ARGB_8888, true);
        if (bitmap == null) return drawable;
        if (source == null) {
            Canvas canvas = new Canvas(bitmap);
            Drawable copy = drawable.getConstantState().newDrawable(resources).mutate();
            copy.setBounds(0, 0, width, height);
            copy.draw(canvas);
        }

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        if (!whiteBackdropCorners(pixels, width, height)) {
            bitmap.recycle();
            return drawable;
        }
        boolean[] visited = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        int head = 0, tail = 0;
        for (int x = 0; x < width; x++) {
            tail = enqueueWhiteEdge(pixels, visited, queue, tail, x, width);
            tail = enqueueWhiteEdge(pixels, visited, queue, tail, (height - 1) * width + x, width);
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueueWhiteEdge(pixels, visited, queue, tail, y * width, width);
            tail = enqueueWhiteEdge(pixels, visited, queue, tail, y * width + width - 1, width);
        }
        while (head < tail) {
            int index = queue[head++];
            pixels[index] = Color.TRANSPARENT;
            int x = index % width, y = index / width;
            if (x > 0) tail = enqueueWhiteEdge(pixels, visited, queue, tail, index - 1, width);
            if (x + 1 < width) tail = enqueueWhiteEdge(pixels, visited, queue, tail, index + 1, width);
            if (y > 0) tail = enqueueWhiteEdge(pixels, visited, queue, tail, index - width, width);
            if (y + 1 < height) tail = enqueueWhiteEdge(pixels, visited, queue, tail, index + width, width);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        BitmapDrawable result = new BitmapDrawable(resources, bitmap);
        result.setFilterBitmap(true);
        return result;
    }

    private static boolean whiteBackdropCorners(int[] pixels, int width, int height) {
        return isWhiteBackdropPixel(pixels[0])
                && isWhiteBackdropPixel(pixels[width - 1])
                && isWhiteBackdropPixel(pixels[(height - 1) * width])
                && isWhiteBackdropPixel(pixels[height * width - 1]);
    }

    private static boolean isWhiteBackdropPixel(int color) {
        int minimum = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
        int maximum = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
        return Color.alpha(color) > 240 && minimum >= 220 && maximum - minimum <= 38;
    }

    private static int enqueueWhiteEdge(int[] pixels, boolean[] visited, int[] queue, int tail,
                                        int index, int width) {
        if (visited[index]) return tail;
        visited[index] = true;
        int color = pixels[index];
        int minimum = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
        int maximum = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
        if (Color.alpha(color) > 240 && minimum >= 220 && maximum - minimum <= 38) {
            queue[tail++] = index;
        }
        return tail;
    }

    static boolean hasFlatBackdrop(Drawable drawable, android.content.res.Resources resources) {
        if (drawable == null) return false;
        Drawable.ConstantState state = drawable.getConstantState();
        if (state == null) return false;
        Drawable sample = state.newDrawable(resources).mutate();
        int width = 32, height = 18;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        sample.setBounds(0, 0, width, height);
        sample.draw(canvas);

        int topLeft = bitmap.getPixel(1, 1);
        int topRight = bitmap.getPixel(width - 2, 1);
        int bottomLeft = bitmap.getPixel(1, height - 2);
        int bottomRight = bitmap.getPixel(width - 2, height - 2);
        int referenceRed = (Color.red(topLeft) + Color.red(topRight)
                + Color.red(bottomLeft) + Color.red(bottomRight)) / 4;
        int referenceGreen = (Color.green(topLeft) + Color.green(topRight)
                + Color.green(bottomLeft) + Color.green(bottomRight)) / 4;
        int referenceBlue = (Color.blue(topLeft) + Color.blue(topRight)
                + Color.blue(bottomLeft) + Color.blue(bottomRight)) / 4;
        int backgroundPixels = 0, opaquePixels = 0;
        int totalPixels = width * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) < 128) continue;
                opaquePixels++;
                if (Math.abs(Color.red(color) - referenceRed) <= 58
                        && Math.abs(Color.green(color) - referenceGreen) <= 58
                        && Math.abs(Color.blue(color) - referenceBlue) <= 58) {
                    backgroundPixels++;
                }
            }
        }
        boolean opaqueCorners = Color.alpha(topLeft) >= 128 && Color.alpha(topRight) >= 128
                && Color.alpha(bottomLeft) >= 128 && Color.alpha(bottomRight) >= 128;
        boolean matchingCorners = opaqueCorners && colorDistance(topLeft, topRight) <= 14_700
                && colorDistance(topLeft, bottomLeft) <= 14_700
                && colorDistance(topLeft, bottomRight) <= 14_700;
        bitmap.recycle();
        return matchingCorners && opaquePixels > totalPixels * 0.4f
                && backgroundPixels > opaquePixels * 0.58f;
    }

    private static int colorDistance(int first, int second) {
        int red = Color.red(first) - Color.red(second);
        int green = Color.green(first) - Color.green(second);
        int blue = Color.blue(first) - Color.blue(second);
        return red * red + green * green + blue * blue;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float shelfBottom = height * 0.63f;
        boolean darkAppearance = !LauncherUiSettings.isLight(getContext());
        vignette = new LinearGradient(0, 0, 0, shelfBottom,
                new int[]{darkAppearance ? 0x00000000 : 0x00000000,
                        darkAppearance ? 0x10000000 : 0x08000000, 0xE8000000},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
        overlayPaint.setShader(vignette);
        Shader horizontalFade = new LinearGradient(0, 0, width, 0,
                new int[]{0x00FFFFFF, Color.WHITE, Color.WHITE, 0x00FFFFFF},
                new float[]{0f, 0.13f, 0.87f, 1f}, Shader.TileMode.CLAMP);
        Shader verticalFade = new LinearGradient(0, 0, 0, shelfBottom,
                new int[]{Color.WHITE, Color.WHITE, 0xAAFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP);
        bannerMaskPaint.setShader(new ComposeShader(horizontalFade, verticalFade, PorterDuff.Mode.MULTIPLY));
        bannerMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        float iconSize = Math.min(shelfBottom * 0.34f, width * 0.15f);
        iconFade = new RadialGradient(width * 0.77f, shelfBottom * 0.48f,
                Math.max(1f, iconSize * 0.68f),
                new int[]{Color.WHITE, Color.WHITE, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.42f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        iconMaskPaint.setShader(iconFade);
        iconMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float bottom = getHeight() * 0.63f;
        if (previousArt != null && crossfade < 1f) {
            int previousSave = canvas.save();
            canvas.translate(-slideX * getWidth() * slideProgress,
                    -slideY * getHeight() * slideProgress);
            drawFallback(canvas, previousColor, previousArt, previousPackage, 1f - crossfade);
            canvas.restoreToCount(previousSave);
            int currentSave = canvas.save();
            canvas.translate(slideX * getWidth() * (1f - slideProgress),
                    slideY * getHeight() * (1f - slideProgress));
            drawFallback(canvas, currentColor, currentArt, currentPackage, crossfade);
            canvas.restoreToCount(currentSave);
        } else {
            drawFallback(canvas, currentColor, currentArt, currentPackage, 1f);
        }
        canvas.drawRect(0, 0, getWidth(), bottom, overlayPaint);
    }

    private void drawFallback(Canvas canvas, int color, Drawable art, String pkg, float alpha) {
        if (alpha <= 0f) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int save = canvas.save();
        float bottom = getHeight() * 0.63f;
        canvas.clipRect(0, 0, getWidth(), bottom);
        int dark = blend(color, Color.BLACK, 0.72f);
        canvas.drawColor((Math.round(alpha * 255f) << 24) | (dark & 0x00FFFFFF));
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(getWidth() * 0.72f, bottom * 0.42f,
                getWidth() * 0.75f, new int[]{(color & 0x00FFFFFF) | 0x85000000,
                (color & 0x00FFFFFF) | 0x00000000}, null, Shader.TileMode.CLAMP));
        glow.setAlpha(Math.round(alpha * 255));
        canvas.drawRect(0, 0, getWidth(), bottom, glow);

        if (art != null && isLandscapeBanner(art)) {
            drawBanner(canvas, art, alpha);
        } else {
            if ("com.spotify.tv.android".equals(pkg)) drawSpotifyWaves(canvas, alpha);
            if (art != null) {
                float size = Math.min(bottom * 0.34f, getWidth() * 0.15f);
                float centerX = getWidth() * 0.77f;
                float centerY = bottom * 0.48f;
                float left = centerX - size / 2f, top = centerY - size / 2f;
                int iconLayer = canvas.saveLayer(left, top, left + size, top + size, null);
                drawDrawable(canvas, art, left, top, left + size, top + size,
                        Math.round(alpha * 235));
                canvas.drawRect(left, top, left + size, top + size, iconMaskPaint);
                canvas.restoreToCount(iconLayer);
            }
        }
        canvas.restoreToCount(save);
    }

    private boolean isLandscapeBanner(Drawable drawable) {
        if (drawable == null) return false;
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        float ratio = width / (float) Math.max(1, height);
        return width > 0 && height > 0 && ratio >= 1.25f && ratio <= 2.4f;
    }

    private void drawBanner(Canvas canvas, Drawable drawable, float alpha) {
        int sourceWidth = Math.max(1, drawable.getIntrinsicWidth());
        int sourceHeight = Math.max(1, drawable.getIntrinsicHeight());
        float shelfHeight = getHeight() * 0.63f;
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            sourceWidth = bitmap.getWidth();
            sourceHeight = bitmap.getHeight();
        }
        float scale = Math.max(getWidth() / (float) sourceWidth, shelfHeight / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        artworkPaint.setAlpha(Math.round(alpha * 255));
        int layer = canvas.saveLayer(0, 0, getWidth(), shelfHeight, null);
        drawDrawable(canvas, drawable, (getWidth() - width) / 2f,
                (shelfHeight - height) / 2f, (getWidth() + width) / 2f,
                (shelfHeight + height) / 2f, artworkPaint.getAlpha());
        canvas.drawRect(0, 0, getWidth(), shelfHeight, bannerMaskPaint);
        canvas.restoreToCount(layer);
    }

    private void drawDrawable(Canvas canvas, Drawable drawable, float left, float top,
                              float right, float bottom, int alpha) {
        if (drawable == null) return;
        drawable.setBounds(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
        int oldAlpha = drawable.getAlpha();
        android.graphics.ColorFilter oldFilter = drawable.getColorFilter();
        drawable.setAlpha(alpha);
        drawable.draw(canvas);
        drawable.setColorFilter(oldFilter);
        drawable.setAlpha(oldAlpha);
    }

    private void drawSpotifyWaves(Canvas canvas, float alpha) {
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(Math.max(1.4f, 1.8f * density));
        wavePaint.setAlpha(Math.round(alpha * 190));
        for (int i = 0; i < 19; i++) {
            float offset = i * 11f * density;
            wavePaint.setColor(Color.HSVToColor(Math.round(alpha * 185),
                    new float[]{(105 + i * 9) % 360, 0.72f, 1f}));
            Path wave = new Path();
            wave.moveTo(-20 * density, getHeight() * 0.32f + offset);
            wave.cubicTo(getWidth() * 0.19f, getHeight() * 0.12f + offset,
                    getWidth() * 0.25f, getHeight() * 0.57f + offset,
                    getWidth() * 0.46f, getHeight() * 0.34f + offset);
            wave.cubicTo(getWidth() * 0.69f, getHeight() * 0.07f + offset,
                    getWidth() * 0.76f, getHeight() * 0.58f + offset,
                    getWidth() + 20 * density, getHeight() * 0.25f + offset);
            canvas.drawPath(wave, wavePaint);
        }
        wavePaint.setStyle(Paint.Style.FILL);
    }

    private int blend(int from, int to, float fraction) {
        float inverse = 1f - fraction;
        int red = Math.round(Color.red(from) * inverse + Color.red(to) * fraction);
        int green = Math.round(Color.green(from) * inverse + Color.green(to) * fraction);
        int blue = Math.round(Color.blue(from) * inverse + Color.blue(to) * fraction);
        return Color.rgb(red, green, blue);
    }
}
