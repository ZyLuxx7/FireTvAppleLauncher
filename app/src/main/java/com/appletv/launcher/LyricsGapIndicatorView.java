package com.appletv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class LyricsGapIndicatorView extends View {
    private static final int DOT_COUNT = 3;
    private static final long ANIMATION_END_TOLERANCE_MS = 250L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private ValueAnimator animator;
    private float progress;
    private long animationEndUptimeMs;

    LyricsGapIndicatorView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    void setWaitProgress(float progress, long remainingMs, boolean animate) {
        float boundedProgress = Math.max(0f, Math.min(1f, progress));
        if (!animate || remainingMs <= 0L) {
            stopAnimation();
            this.progress = boundedProgress;
            invalidate();
            return;
        }

        long endUptimeMs = SystemClock.uptimeMillis() + remainingMs;
        if (animator != null
                && Math.abs(endUptimeMs - animationEndUptimeMs) <= ANIMATION_END_TOLERANCE_MS) {
            return;
        }

        stopAnimation();
        this.progress = boundedProgress;
        animationEndUptimeMs = endUptimeMs;
        ValueAnimator next = ValueAnimator.ofFloat(boundedProgress, 1f);
        next.setDuration(remainingMs);
        next.setInterpolator(new LinearInterpolator());
        next.addUpdateListener(value -> {
            this.progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator = next;
        next.start();
    }

    private void stopAnimation() {
        ValueAnimator current = animator;
        animator = null;
        animationEndUptimeMs = 0L;
        if (current != null) current.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float spacing = 11f * density;
        float baseRadius = 2.8f * density;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float activePosition = progress * (DOT_COUNT - 1);

        for (int i = 0; i < DOT_COUNT; i++) {
            float distance = Math.min(1f, Math.abs(i - activePosition));
            float emphasis = 1f - distance;
            emphasis = emphasis * emphasis * (3f - 2f * emphasis);
            float radius = baseRadius * (0.72f + 0.55f * emphasis);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(75f + 180f * emphasis));
            canvas.drawCircle(centerX + (i - 1) * spacing, centerY, radius, paint);
        }
    }
}
