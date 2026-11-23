package com.appletv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Optional draw-only outline prototype for visible controls in other apps. */
public final class LiquidGlassOverlayService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final long UPDATE_DELAY_MS = 100L;
    private static final int MAX_TARGETS = 80;
    private static final int MAX_DEPTH = 24;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshOverlay;
    private SharedPreferences preferences;
    private WindowManager windowManager;
    private BoundsOverlay overlay;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayAttached;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) throw new IllegalStateException("Window manager is unavailable");
        overlay = new BoundsOverlay(this);
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        scheduleRefresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null) scheduleRefresh();
    }

    @Override
    public void onInterrupt() {
        removeOverlay();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refresh);
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(this);
        }
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (!"liquid_glass_enabled".equals(key)) return;
        if (isGlassEnabled()) scheduleRefresh();
        else {
            handler.removeCallbacks(refresh);
            removeOverlay();
        }
    }

    private void scheduleRefresh() {
        if (!isGlassEnabled()) {
            handler.removeCallbacks(refresh);
            removeOverlay();
            return;
        }
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, UPDATE_DELAY_MS);
    }

    private boolean isGlassEnabled() {
        return preferences != null && preferences.getBoolean("liquid_glass_enabled", false);
    }

    private void refreshOverlay() {
        if (!isGlassEnabled()) {
            removeOverlay();
            return;
        }
        AccessibilityNodeInfo activeWindow = getRootInActiveWindow();
        if (activeWindow == null) {
            removeOverlay();
            return;
        }

        List<Rect> targets = new ArrayList<>();
        try {
            CharSequence packageName = activeWindow.getPackageName();
            if (packageName == null || getPackageName().contentEquals(packageName)) {
                removeOverlay();
                return;
            }
            collectClickableBounds(activeWindow, targets, 0);
        } finally {
            activeWindow.recycle();
        }

        if (targets.isEmpty()) {
            removeOverlay();
            return;
        }
        overlay.setBounds(targets);
        if (!overlayAttached) {
            windowManager.addView(overlay, overlayParams);
            overlayAttached = true;
        } else {
            overlay.invalidate();
        }
    }

    private void collectClickableBounds(AccessibilityNodeInfo node, List<Rect> targets, int depth) {
        if (targets.size() >= MAX_TARGETS || depth >= MAX_DEPTH) return;
        if (node.isVisibleToUser() && node.isClickable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            Rect screen = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
            if (bounds.intersect(screen)
                    && !(bounds.width() >= metrics.widthPixels * 0.9f
                    && bounds.height() >= metrics.heightPixels * 0.8f)
                    && !containsBounds(targets, bounds)) {
                targets.add(bounds);
            }
        }
        for (int i = 0; i < node.getChildCount() && targets.size() < MAX_TARGETS; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectClickableBounds(child, targets, depth + 1);
            } finally {
                child.recycle();
            }
        }
    }

    private boolean containsBounds(List<Rect> existingBounds, Rect bounds) {
        for (Rect existing : existingBounds) {
            if (existing.equals(bounds)) return true;
        }
        return false;
    }

    private void removeOverlay() {
        if (!overlayAttached) return;
        windowManager.removeView(overlay);
        overlayAttached = false;
    }

    private static final class BoundsOverlay extends View {
        private final List<Rect> bounds = new ArrayList<>();
        private final Paint outerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        BoundsOverlay(LiquidGlassOverlayService context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            outerStroke.setStyle(Paint.Style.STROKE);
            outerStroke.setStrokeWidth(2f * density);
            outerStroke.setColor(0xBFFFFFFF);
            innerStroke.setStyle(Paint.Style.STROKE);
            innerStroke.setStrokeWidth(density);
            innerStroke.setColor(0x559CEEFF);
        }

        void setBounds(List<Rect> nextBounds) {
            bounds.clear();
            for (Rect rect : nextBounds) bounds.add(new Rect(rect));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (Rect rect : bounds) {
                RectF outline = new RectF(rect);
                outline.inset(outerStroke.getStrokeWidth() / 2f, outerStroke.getStrokeWidth() / 2f);
                float radius = Math.min(12f * density,
                        Math.min(outline.width(), outline.height()) / 2f);
                canvas.drawRoundRect(outline, radius, radius, outerStroke);
                outline.inset(2f * density, 2f * density);
                if (!outline.isEmpty()) {
                    canvas.drawRoundRect(outline, Math.max(0f, radius - 2f * density),
                            Math.max(0f, radius - 2f * density), innerStroke);
                }
            }
        }
    }
}
