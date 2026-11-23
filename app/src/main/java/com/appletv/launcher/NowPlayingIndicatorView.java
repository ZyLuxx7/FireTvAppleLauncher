package com.appletv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.audiofx.Visualizer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;

public final class NowPlayingIndicatorView extends View {
    private static final String TAG = "AppleTv.Equalizer";
    private static final float[] STATIC_LEVELS = {0.15f, 0.48f, 0.9f, 0.62f, 0.24f};
    private static final float[] BAND_MIN_HZ = {80f, 250f, 600f, 1400f, 3200f};
    private static final float[] BAND_MAX_HZ = {250f, 600f, 1400f, 3200f, 9000f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float[] audioLevels = new float[STATIC_LEVELS.length];
    private ValueAnimator animator;
    private Visualizer visualizer;
    private Runnable onCaptureUnavailableListener;
    private float phase;
    private boolean playing;
    private boolean motionEnabled;
    private boolean audioCaptureAllowed;
    private boolean audioReactive;
    private boolean captureUnavailable;
    private boolean captureFailureReported;

    public NowPlayingIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        paint.setColor(0xBFFFFFFF);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    public void setPlaying(boolean playing, boolean motionEnabled) {
        if (this.playing == playing && this.motionEnabled == motionEnabled) return;
        this.playing = playing;
        this.motionEnabled = motionEnabled;
        updatePlayback();
        invalidate();
    }

    public void setAudioCaptureAllowed(boolean allowed) {
        if (audioCaptureAllowed == allowed) return;
        audioCaptureAllowed = allowed;
        captureUnavailable = false;
        captureFailureReported = false;
        updatePlayback();
        invalidate();
    }

    public void setOnCaptureUnavailableListener(Runnable listener) {
        onCaptureUnavailableListener = listener;
        if (captureFailureReported && listener != null) post(listener);
    }

    private void updatePlayback() {
        if (playing && audioCaptureAllowed && isAttachedToWindow()) {
            if (visualizer == null && !captureUnavailable) startVisualizer();
        } else {
            stopVisualizer();
        }
        updateAnimation();
    }

    private void startVisualizer() {
        Visualizer candidate = null;
        try {
            candidate = new Visualizer(0);
            int[] captureRange = Visualizer.getCaptureSizeRange();
            if (captureRange == null || captureRange.length < 2
                    || captureRange[0] <= 0 || captureRange[1] < captureRange[0]) {
                throw new IllegalStateException("Invalid audio visualizer capture-size range");
            }
            int captureSize = Integer.highestOneBit(Math.min(captureRange[1], 1024));
            if (captureSize < captureRange[0]) {
                throw new IllegalStateException("No supported audio visualizer capture size");
            }
            int status = candidate.setCaptureSize(captureSize);
            if (status != Visualizer.SUCCESS) {
                throw new IllegalStateException(
                        "Could not set audio visualizer capture size: " + status);
            }

            int captureRate = Math.min(20_000, Visualizer.getMaxCaptureRate());
            if (captureRate <= 0) {
                throw new IllegalStateException("Invalid audio visualizer capture rate");
            }
            status = candidate.setDataCaptureListener(
                    new Visualizer.OnDataCaptureListener() {
                        @Override
                        public void onWaveFormDataCapture(
                                Visualizer effect, byte[] waveform, int samplingRate) {
                        }

                        @Override
                        public void onFftDataCapture(
                                Visualizer effect, byte[] fft, int samplingRate) {
                            float[] capturedLevels = levelsFromFft(fft, samplingRate);
                            post(() -> applyAudioLevels(effect, capturedLevels));
                        }
                    }, captureRate, false, true);
            if (status != Visualizer.SUCCESS) {
                throw new IllegalStateException("Could not register audio visualizer listener");
            }
            status = candidate.setEnabled(true);
            if (status != Visualizer.SUCCESS) {
                throw new IllegalStateException("Could not enable audio visualizer: " + status);
            }
            visualizer = candidate;
            audioReactive = true;
            stopAnimation();
        } catch (IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException | SecurityException exception) {
            if (candidate != null) candidate.release();
            captureUnavailable = true;
            audioReactive = false;
            Log.w(TAG, "Fire TV audio output is unavailable to the equalizer", exception);
            reportCaptureUnavailable();
        }
    }

    static float[] levelsFromFft(byte[] fft, int samplingRateMilliHz) {
        float[] levels = new float[BAND_MIN_HZ.length];
        if (fft == null || fft.length < 4 || samplingRateMilliHz <= 0) return levels;

        double samplingRateHz = samplingRateMilliHz / 1000.0;
        int maxBin = fft.length / 2 - 1;
        for (int band = 0; band < levels.length; band++) {
            int firstBin = Math.max(1, (int) Math.ceil(
                    BAND_MIN_HZ[band] * fft.length / samplingRateHz));
            int endBin = Math.min(maxBin + 1, (int) Math.ceil(
                    BAND_MAX_HZ[band] * fft.length / samplingRateHz));
            if (firstBin >= endBin) continue;

            double energy = 0d;
            int binCount = 0;
            for (int bin = firstBin; bin < endBin; bin++) {
                int real = fft[bin * 2];
                int imaginary = fft[bin * 2 + 1];
                energy += real * real + imaginary * imaginary;
                binCount++;
            }
            if (binCount == 0) continue;

            double rms = Math.sqrt(energy / binCount);
            double decibels = 20d * Math.log10(Math.max(rms, 0.5d) / 128d);
            levels[band] = Math.max(0f, Math.min(1f, (float) ((decibels + 45d) / 35d)));
        }
        return levels;
    }

    private void applyAudioLevels(Visualizer source, float[] capturedLevels) {
        if (source != visualizer || !audioReactive) return;
        for (int i = 0; i < audioLevels.length; i++) {
            float measured = capturedLevels[i];
            float smoothing = measured > audioLevels[i] ? 0.68f : 0.32f;
            audioLevels[i] += (measured - audioLevels[i]) * smoothing;
        }
        invalidate();
    }

    private void reportCaptureUnavailable() {
        if (captureFailureReported) return;
        captureFailureReported = true;
        if (onCaptureUnavailableListener != null) post(onCaptureUnavailableListener);
    }

    private void stopVisualizer() {
        Visualizer current = visualizer;
        visualizer = null;
        audioReactive = false;
        if (current == null) return;
        try {
            int status = current.setEnabled(false);
            if (status != Visualizer.SUCCESS) {
                Log.w(TAG, "Could not disable audio visualizer: " + status);
            }
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Audio visualizer was already unavailable while stopping", exception);
        } finally {
            current.release();
        }
    }

    private void updateAnimation() {
        if (playing && motionEnabled && isAttachedToWindow()) {
            if (animator != null) return;
            ValueAnimator next = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            next.setDuration(850L);
            next.setRepeatCount(ValueAnimator.INFINITE);
            next.setInterpolator(new LinearInterpolator());
            next.addUpdateListener(value -> {
                phase = (float) value.getAnimatedValue();
                invalidate();
            });
            animator = next;
            next.start();
        } else {
            stopAnimation();
        }
    }

    private void stopAnimation() {
        ValueAnimator current = animator;
        animator = null;
        if (current != null) current.cancel();
        setTranslationX(0f);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updatePlayback();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopVisualizer();
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!playing) return;

        float barWidth = 3f * density;
        float gap = 2f * density;
        float totalWidth = STATIC_LEVELS.length * barWidth
                + (STATIC_LEVELS.length - 1) * gap;
        float left = (getWidth() - totalWidth) / 2f;
        float centerY = getHeight() / 2f;
        for (int i = 0; i < STATIC_LEVELS.length; i++) {
            float level = audioReactive ? audioLevels[i]
                    : motionEnabled ? 0.5f + 0.5f * (float) Math.sin(phase + i * 0.9f)
                    : STATIC_LEVELS[i];
            float height = (3f + 14f * level) * density;
            float barLeft = left + i * (barWidth + gap);
            float top = centerY - height / 2f;
            canvas.drawRoundRect(barLeft, top, barLeft + barWidth, top + height,
                    barWidth / 2f, barWidth / 2f, paint);
        }
    }
}
