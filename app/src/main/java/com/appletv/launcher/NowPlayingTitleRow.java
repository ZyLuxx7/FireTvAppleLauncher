package com.appletv.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

public final class NowPlayingTitleRow extends ViewGroup {
    static final String SCROLL_SPEED_PREFERENCE = "player_title_scroll_speed";
    static final int MIN_SCROLL_SPEED_PERCENT = 1;
    static final int MAX_SCROLL_SPEED_PERCENT = 100;
    static final int DEFAULT_SCROLL_SPEED_PERCENT = 25;

    private static final float MIN_SPEED_DP_PER_SECOND = 7f;
    private static final float MAX_SPEED_DP_PER_SECOND = 72f;
    private static final int INDICATOR_GAP_DP = 8;
    private static final int INDICATOR_WIDTH_DP = 28;
    private static final int INDICATOR_HEIGHT_DP = 24;
    private static final long INITIAL_PAUSE_MS = 850L;
    private static final long END_PAUSE_MS = 650L;
    private static final long MIN_LEG_DURATION_MS = 500L;

    private final TextView titleView;
    private final NowPlayingIndicatorView indicatorView;
    private final float density;
    private ValueAnimator marqueeAnimator;
    private Runnable pendingMarqueeStart;
    private int titleWidth;
    private int indicatorWidth;
    private int indicatorGap;
    private int overflowPx;
    private int nextMarqueePosition;
    private int scrollSpeedPercent = DEFAULT_SCROLL_SPEED_PERCENT;
    private long marqueeGeneration;
    private boolean marqueeEnabled;
    private boolean marqueeStarted;

    public NowPlayingTitleRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClipChildren(true);
        setClipToPadding(true);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        titleView.setIncludeFontPadding(false);
        titleView.setSingleLine(true);
        titleView.setHorizontallyScrolling(true);
        titleView.setEllipsize(null);
        titleView.setPadding(0, 0, 0, 0);
        titleView.setFocusable(false);
        titleView.setClickable(false);
        addView(titleView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        indicatorView = new NowPlayingIndicatorView(context, null);
        indicatorView.setVisibility(INVISIBLE);
        addView(indicatorView, new LayoutParams(
                dp(INDICATOR_WIDTH_DP), dp(INDICATOR_HEIGHT_DP)));
    }

    void setTitle(CharSequence title) {
        if (TextUtils.equals(titleView.getText(), title)) return;
        stopMarquee();
        titleView.setText(title);
        titleView.scrollTo(0, 0);
        marqueeStarted = false;
        requestLayout();
    }

    void setScrollSpeedPercent(int percent) {
        int boundedPercent = Math.max(MIN_SCROLL_SPEED_PERCENT,
                Math.min(MAX_SCROLL_SPEED_PERCENT, percent));
        if (scrollSpeedPercent == boundedPercent) return;
        scrollSpeedPercent = boundedPercent;
        if (marqueeAnimator != null) {
            stopMarquee();
            if (marqueeStarted && canAnimateMarquee()) scheduleMarqueeLeg(0L);
        }
    }

    void setMarqueeEnabled(boolean enabled) {
        if (marqueeEnabled == enabled) return;
        marqueeEnabled = enabled;
        if (!enabled) {
            stopMarquee();
            titleView.scrollTo(0, 0);
            marqueeStarted = false;
        } else {
            updateMarquee();
        }
    }

    void setIndicatorPlaying(boolean playing, boolean motionEnabled) {
        indicatorView.setVisibility(playing ? VISIBLE : INVISIBLE);
        indicatorView.setPlaying(playing, motionEnabled);
    }

    void setAudioCaptureAllowed(boolean allowed) {
        indicatorView.setAudioCaptureAllowed(allowed);
    }

    void setOnCaptureUnavailableListener(Runnable listener) {
        indicatorView.setOnCaptureUnavailableListener(listener);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private int measuredTitleTextWidth() {
        CharSequence text = titleView.getText();
        if (TextUtils.isEmpty(text)) return 0;
        float width = titleView.getPaint().measureText(text.toString());
        return (int) Math.ceil(width)
                + titleView.getCompoundPaddingLeft()
                + titleView.getCompoundPaddingRight();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = measuredTitleTextWidth()
                + dp(INDICATOR_GAP_DP) + dp(INDICATOR_WIDTH_DP)
                + getPaddingLeft() + getPaddingRight();
        int measuredWidth = resolveSize(desiredWidth, widthMeasureSpec);
        int availableWidth = Math.max(0,
                measuredWidth - getPaddingLeft() - getPaddingRight());
        indicatorWidth = Math.min(dp(INDICATOR_WIDTH_DP), availableWidth);
        indicatorGap = Math.min(dp(INDICATOR_GAP_DP),
                Math.max(0, availableWidth - indicatorWidth));
        int availableTitleWidth = Math.max(0, availableWidth - indicatorWidth - indicatorGap);
        titleWidth = Math.min(measuredTitleTextWidth(), availableTitleWidth);

        int childHeightSpec = getChildMeasureSpec(heightMeasureSpec,
                getPaddingTop() + getPaddingBottom(), LayoutParams.WRAP_CONTENT);
        titleView.measure(MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.EXACTLY),
                childHeightSpec);
        indicatorView.measure(MeasureSpec.makeMeasureSpec(indicatorWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(INDICATOR_HEIGHT_DP), MeasureSpec.EXACTLY));

        int desiredHeight = Math.max(titleView.getMeasuredHeight(), indicatorView.getMeasuredHeight())
                + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int contentHeight = Math.max(0, bottom - top - getPaddingTop() - getPaddingBottom());
        int titleTop = getPaddingTop()
                + (contentHeight - titleView.getMeasuredHeight()) / 2;
        int titleLeft = getPaddingLeft();
        titleView.layout(titleLeft, titleTop, titleLeft + titleWidth,
                titleTop + titleView.getMeasuredHeight());

        int indicatorLeft = titleLeft + titleWidth + indicatorGap;
        int indicatorTop = getPaddingTop()
                + (contentHeight - indicatorView.getMeasuredHeight()) / 2;
        indicatorView.layout(indicatorLeft, indicatorTop,
                indicatorLeft + indicatorWidth,
                indicatorTop + indicatorView.getMeasuredHeight());

        int nextOverflow = Math.max(0, measuredTitleTextWidth() - titleWidth);
        if (overflowPx != nextOverflow) {
            overflowPx = nextOverflow;
            stopMarquee();
            marqueeStarted = false;
            titleView.scrollTo(0, 0);
        }
        updateMarquee();
    }

    private void updateMarquee() {
        if (overflowPx <= 0 || !marqueeEnabled) {
            stopMarquee();
            titleView.scrollTo(0, 0);
            marqueeStarted = false;
            return;
        }
        if (!canAnimateMarquee()) {
            stopMarquee();
            return;
        }
        if (!marqueeStarted) {
            marqueeStarted = true;
            titleView.scrollTo(overflowPx, 0);
            nextMarqueePosition = 0;
            scheduleMarqueeLeg(INITIAL_PAUSE_MS);
        } else if (marqueeAnimator == null && pendingMarqueeStart == null) {
            scheduleMarqueeLeg(0L);
        }
    }

    private boolean canAnimateMarquee() {
        return marqueeEnabled && overflowPx > 0 && isShown()
                && getWindowVisibility() == VISIBLE;
    }

    private void scheduleMarqueeLeg(long delayMs) {
        if (!canAnimateMarquee()) return;
        if (pendingMarqueeStart != null) removeCallbacks(pendingMarqueeStart);
        long generation = marqueeGeneration;
        pendingMarqueeStart = () -> {
            if (generation != marqueeGeneration) return;
            pendingMarqueeStart = null;
            startMarqueeLeg();
        };
        postDelayed(pendingMarqueeStart, delayMs);
    }

    private void startMarqueeLeg() {
        if (!canAnimateMarquee()) return;
        int start = Math.max(0, Math.min(titleView.getScrollX(), overflowPx));
        int target = Math.max(0, Math.min(nextMarqueePosition, overflowPx));
        if (start == target) target = target == 0 ? overflowPx : 0;
        final int endPosition = target;

        ValueAnimator next = ValueAnimator.ofInt(start, endPosition);
        next.setDuration(durationForScrollPx(
                Math.abs(endPosition - start), density, scrollSpeedPercent));
        next.setInterpolator(new LinearInterpolator());
        next.addUpdateListener(value ->
                titleView.scrollTo((Integer) value.getAnimatedValue(), 0));
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (marqueeAnimator != next) return;
                marqueeAnimator = null;
                titleView.scrollTo(endPosition, 0);
                nextMarqueePosition = endPosition == 0 ? overflowPx : 0;
                scheduleMarqueeLeg(END_PAUSE_MS);
            }
        });
        marqueeAnimator = next;
        next.start();
    }

    private void stopMarquee() {
        marqueeGeneration++;
        if (pendingMarqueeStart != null) {
            removeCallbacks(pendingMarqueeStart);
            pendingMarqueeStart = null;
        }
        ValueAnimator current = marqueeAnimator;
        marqueeAnimator = null;
        if (current != null) current.cancel();
    }

    static float scrollSpeedDpPerSecond(int percent) {
        int boundedPercent = Math.max(MIN_SCROLL_SPEED_PERCENT,
                Math.min(MAX_SCROLL_SPEED_PERCENT, percent));
        return MIN_SPEED_DP_PER_SECOND
                + (MAX_SPEED_DP_PER_SECOND - MIN_SPEED_DP_PER_SECOND) * boundedPercent / 100f;
    }

    static long durationForScrollPx(int distancePx, float density, int speedPercent) {
        if (distancePx <= 0) return 0L;
        float safeDensity = density > 0f ? density : 1f;
        double durationMs = distancePx * 1000d
                / (scrollSpeedDpPerSecond(speedPercent) * safeDensity);
        return Math.max(MIN_LEG_DURATION_MS, (long) Math.ceil(durationMs));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::updateMarquee);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMarquee();
        marqueeStarted = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            post(this::updateMarquee);
        } else {
            stopMarquee();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            post(this::updateMarquee);
        } else {
            stopMarquee();
        }
    }
}
