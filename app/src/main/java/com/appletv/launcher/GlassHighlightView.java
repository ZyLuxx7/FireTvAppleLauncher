package com.appletv.launcher;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.os.Handler;
import android.os.Looper;

import com.example.liquidglass.LiquidGlassView;

/** Switchable Home focus highlight with the existing glass slab as a safe fallback. */
public final class GlassHighlightView extends FrameLayout {
    private static final float CONTENT_INSET_DP = 6f;
    private static final float MAX_TINT_STRENGTH = 0.22f;
    private static final long BACKDROP_REFRESH_DELAY_MS = 80L;

    private final GlassView fallback;
    private final float density;
    private final Handler backdropRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable backdropRefresh = this::invalidateLiquidGlass;
    private LiquidGlassView liquidGlass;
    private View backdropSource;
    private float radius;
    private int effectStrength = 65;
    private int accentColor = Color.TRANSPARENT;
    private boolean reflectionsEnabled = true;

    public GlassHighlightView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        fallback = new GlassView(context, null);
        fallback.setClickable(false);
        fallback.setFocusable(false);
        addView(fallback, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setLiquidGlassEnabled(boolean enabled, View backdropSource) {
        this.backdropSource = backdropSource;
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            backdropRefreshHandler.removeCallbacks(backdropRefresh);
            if (liquidGlass != null) {
                removeView(liquidGlass);
                liquidGlass = null;
            }
            fallback.setVisibility(VISIBLE);
            return;
        }

        if (liquidGlass == null) {
            liquidGlass = createLiquidGlassView();
            int inset = Math.round(CONTENT_INSET_DP * density);
            LayoutParams params = new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            params.setMargins(inset, inset, inset, inset);
            addView(liquidGlass, params);
        } else {
            liquidGlass.setBackdropSource(backdropSource);
        }
        fallback.setVisibility(GONE);
        liquidGlass.setVisibility(VISIBLE);
    }

    public void setEffectStrength(int strength) {
        effectStrength = Math.max(0, Math.min(100, strength));
        fallback.setEffectStrength(effectStrength);
        updateLiquidTint();
    }

    public void setReflectionsEnabled(boolean enabled) {
        reflectionsEnabled = enabled;
        fallback.setReflectionsEnabled(enabled);
        if (liquidGlass != null) liquidGlass.setEnableEdgeHighlight(enabled);
    }

    public void setAccent(int argb) {
        accentColor = argb;
        fallback.setAccent(argb);
        updateLiquidTint();
    }

    public void setRadius(float px) {
        radius = px;
        fallback.setRadius(px);
        if (liquidGlass != null) liquidGlass.setCornerRadius(px);
    }

    @Override
    public void setTranslationX(float translationX) {
        super.setTranslationX(translationX);
        scheduleBackdropRefresh();
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        scheduleBackdropRefresh();
    }

    private LiquidGlassView createLiquidGlassView() {
        LiquidGlassView view = new LiquidGlassView(getContext());
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setEnablePressEffect(false);
        view.setEnableChromaticAberration(false);
        view.setEnableChromaticDispersion(false);
        view.setEnableShadow(false);
        view.setEnableAdaptiveTint(false);
        view.setEnableOptimizedCapture(true);
        view.setEnableEdgeHighlight(reflectionsEnabled);
        view.setGlobalDownsampleFactor(0.5f);
        view.setBlurAmount(0.0625f);
        view.setCornerRadius(radius);
        view.setBackdropSource(backdropSource);
        updateLiquidTint(view);
        return view;
    }

    private void updateLiquidTint() {
        if (liquidGlass != null) updateLiquidTint(liquidGlass);
    }

    private void updateLiquidTint(LiquidGlassView view) {
        int tint = accentColor == Color.TRANSPARENT ? Color.WHITE : accentColor;
        float strength = effectStrength / 100f * MAX_TINT_STRENGTH;
        view.setGlassTint(tint, strength);
    }

    private void invalidateLiquidGlass() {
        if (liquidGlass != null && liquidGlass.getVisibility() == VISIBLE) {
            liquidGlass.invalidate();
        }
    }

    private void scheduleBackdropRefresh() {
        if (liquidGlass == null || liquidGlass.getVisibility() != VISIBLE) return;
        backdropRefreshHandler.removeCallbacks(backdropRefresh);
        backdropRefreshHandler.postDelayed(backdropRefresh, BACKDROP_REFRESH_DELAY_MS);
    }
}
