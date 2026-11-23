package com.appletv.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

final class FocusGlassController implements ViewTreeObserver.OnGlobalFocusChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int FOCUS_INSET_DP = 6;

    private final Activity activity;
    private final FrameLayout root;
    private final GlassHighlightView glass;
    private final SharedPreferences preferences;
    private final float density;
    private ViewTreeObserver.OnGlobalLayoutListener initialLayoutListener;

    FocusGlassController(Activity activity) {
        this.activity = activity;
        root = activity.findViewById(android.R.id.content);
        density = activity.getResources().getDisplayMetrics().density;
        preferences = activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
        glass = new GlassHighlightView(activity, null);
        glass.setVisibility(View.GONE);
        glass.setAlpha(0f);
        root.addView(glass, new FrameLayout.LayoutParams(1, 1));
        glass.setElevation(24 * density);
        ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(this);
        initialLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                View focused = activity.getCurrentFocus();
                if (focused == null || focused.getWidth() == 0 || focused.getHeight() == 0) return;
                onGlobalFocusChanged(null, focused);
                ViewTreeObserver currentObserver = root.getViewTreeObserver();
                if (currentObserver.isAlive()) currentObserver.removeOnGlobalLayoutListener(this);
                initialLayoutListener = null;
            }
        };
        observer.addOnGlobalLayoutListener(initialLayoutListener);
        preferences.registerOnSharedPreferenceChangeListener(this);
        applyPreferences();
        root.post(() -> onGlobalFocusChanged(null, activity.getCurrentFocus()));
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (newFocus == null || !preferences.getBoolean("liquid_glass_enabled", false)
                || !isDescendant(newFocus)) {
            glass.setVisibility(View.GONE);
            return;
        }

        Rect bounds = new Rect(0, 0, newFocus.getWidth(), newFocus.getHeight());
        if (bounds.isEmpty()) {
            glass.setVisibility(View.GONE);
            return;
        }
        root.offsetDescendantRectToMyCoords(newFocus, bounds);
        int inset = Math.round(FOCUS_INSET_DP * density);
        int width = bounds.width() + inset * 2;
        int height = bounds.height() + inset * 2;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) glass.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            glass.setLayoutParams(params);
        }
        glass.setRadius(Math.min(width, height) * 0.14f);
        glass.setTranslationX(bounds.left - inset);
        glass.setTranslationY(bounds.top - inset);
        glass.setAlpha(0.32f);
        glass.setVisibility(View.VISIBLE);
        glass.bringToFront();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("liquid_glass_enabled".equals(key) || "glass_strength".equals(key)
                || "glass_reflections".equals(key)) {
            applyPreferences();
            if (!preferences.getBoolean("liquid_glass_enabled", false)) {
                glass.setVisibility(View.GONE);
            } else {
                onGlobalFocusChanged(null, activity.getCurrentFocus());
            }
        }
    }

    void close() {
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        ViewTreeObserver currentObserver = root.getViewTreeObserver();
        if (currentObserver.isAlive()) {
            currentObserver.removeOnGlobalFocusChangeListener(this);
            if (initialLayoutListener != null) {
                currentObserver.removeOnGlobalLayoutListener(initialLayoutListener);
                initialLayoutListener = null;
            }
        }
        root.removeView(glass);
    }

    private void applyPreferences() {
        glass.setEffectStrength(preferences.getInt("glass_strength", 65));
        glass.setReflectionsEnabled(preferences.getBoolean("glass_reflections", true));
        glass.setLiquidGlassEnabled(
                preferences.getBoolean("liquid_glass_enabled", false), root);
    }

    private boolean isDescendant(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent == root) return true;
            parent = parent.getParent();
        }
        return false;
    }
}
