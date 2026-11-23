package com.appletv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public final class ApplePlaybackProgressView extends View {
    private static final int MAX_PROGRESS = 1000;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float trackHeight;
    private final float thumbRadius;
    private float progress;
    private ValueAnimator progressAnimator;

    public ApplePlaybackProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        trackHeight = 3 * density;
        thumbRadius = 4 * density;
        trackPaint.setColor(0x66FFFFFF);
        playedPaint.setColor(0xFFFFFFFF);
        thumbPaint.setColor(0xFFFFFFFF);
        focusPaint.setColor(0xBFFFFFFF);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(1.5f * density);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setWillNotDraw(false);
    }

    public void setProgress(int progress) {
        int boundedProgress = Math.max(0, Math.min(MAX_PROGRESS, progress));
        cancelProgressAnimation();
        setProgressValue(boundedProgress);
    }

    public void setProgress(int progress, boolean smooth) {
        int boundedProgress = Math.max(0, Math.min(MAX_PROGRESS, progress));
        if (!smooth) {
            setProgress(boundedProgress);
            return;
        }
        if (this.progress == boundedProgress) {
            cancelProgressAnimation();
            return;
        }
        cancelProgressAnimation();
        ValueAnimator animator = ValueAnimator.ofFloat(this.progress, boundedProgress);
        animator.setDuration(1000L);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation ->
                setProgressValue((float) animation.getAnimatedValue()));
        progressAnimator = animator;
        animator.start();
    }

    private void setProgressValue(float progress) {
        this.progress = Math.max(0f, Math.min(MAX_PROGRESS, progress));
        invalidate();
    }

    private void cancelProgressAnimation() {
        ValueAnimator animator = progressAnimator;
        progressAnimator = null;
        if (animator != null) animator.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelProgressAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerY = getHeight() / 2f;
        float left = thumbRadius;
        float right = Math.max(left, getWidth() - thumbRadius);
        float playedRight = left + (right - left) * progress / MAX_PROGRESS;
        float trackTop = centerY - trackHeight / 2f;
        float trackBottom = centerY + trackHeight / 2f;

        canvas.drawRoundRect(new RectF(left, trackTop, right, trackBottom),
                trackHeight / 2f, trackHeight / 2f, trackPaint);
        if (playedRight > left) {
            canvas.drawRoundRect(new RectF(left, trackTop, playedRight, trackBottom),
                    trackHeight / 2f, trackHeight / 2f, playedPaint);
        }
        canvas.drawCircle(playedRight, centerY, thumbRadius, thumbPaint);
        if (hasFocus()) {
            canvas.drawCircle(playedRight, centerY, thumbRadius + 3 * focusPaint.getStrokeWidth(),
                    focusPaint);
        }
    }
}
