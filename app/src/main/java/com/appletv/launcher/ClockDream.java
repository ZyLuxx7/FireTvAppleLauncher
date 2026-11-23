package com.appletv.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Random;

public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    private static final long TRANSITION_MS = 700L;
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};
    private static final String[] LANDSCAPE_IDS = {
            HomeWallpaperCatalog.SCENIC, HomeWallpaperCatalog.SCENIC,
            "ocean", "sunset", "aurora"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View root;
    private View header;
    private View backgroundShade;
    private View ambient;
    private ImageView background;
    private TextView greeting;
    private GradientDrawable glow;
    private ValueAnimator glowAnimator;
    private int colorIndex;
    private int glowColor;
    private int landscapeIndex = -1;
    private boolean playerMode;
    private boolean homeTransition;
    private boolean dreaming;
    private boolean exiting;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!dreaming || exiting) return;
            if (!playerMode) {
                shiftGlow();
                cycleLandscape();
            }
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(false);
        Window window = getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        setContentView(R.layout.dream_screensaver);

        root = findViewById(R.id.dreamRoot);
        header = findViewById(R.id.dreamHeader);
        header.setAlpha(0f);
        background = findViewById(R.id.dreamBackground);
        backgroundShade = findViewById(R.id.dreamBackgroundShade);
        ambient = findViewById(R.id.dreamAmbient);
        greeting = findViewById(R.id.dreamGreeting);
        greeting.setText(getGreeting());
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) wakeAndRestore();
            return keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
                    && keyCode != KeyEvent.KEYCODE_VOLUME_MUTE;
        });
        root.requestFocus();

        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        playerMode = NowPlayingListener.shouldShowPlayerBannerInScreensaver(
                MainActivity.isHomePlayerExpandedForScreensaver(), snapshot);
        root.setBackgroundColor(playerMode ? Color.TRANSPARENT : Color.BLACK);
        if (playerMode) {
            background.setVisibility(View.GONE);
            backgroundShade.setVisibility(View.GONE);
            ambient.setVisibility(View.GONE);
        } else {
            background.setVisibility(View.VISIBLE);
            backgroundShade.setVisibility(View.VISIBLE);
            ambient.setVisibility(View.VISIBLE);
            background.setAlpha(1f);
            setRandomLandscape();
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        homeTransition = MainActivity.transitionHomeForScreensaver(true);
        root.post(this::animateIntoScreensaver);
        handler.postDelayed(tick, MOVE_MS);
    }

    private void animateIntoScreensaver() {
        if (!dreaming || exiting) return;
        header.animate().cancel();
        header.setTranslationX(MainActivity.screensaverHeaderStartOffset(header));
        header.animate().translationX(0f).alpha(1f)
                .setDuration(TRANSITION_MS)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
        if (playerMode) return;
        background.animate().cancel();
        background.setScaleX(1.04f);
        background.setScaleY(1.04f);
        background.animate().scaleX(1.12f).scaleY(1.12f)
                .setDuration(1400L).start();
        backgroundShade.animate().alpha(0.3f)
                .setDuration(1000L).start();
        ambient.animate().alpha(0.18f).setDuration(1200L).start();
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        handler.removeCallbacks(tick);
        if (homeTransition) {
            MainActivity.transitionHomeForScreensaver(false);
            homeTransition = false;
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        dreaming = false;
        handler.removeCallbacks(tick);
        if (glowAnimator != null) glowAnimator.cancel();
        if (homeTransition) {
            MainActivity.transitionHomeForScreensaver(false);
            homeTransition = false;
        }
        super.onDetachedFromWindow();
    }

    private void wakeAndRestore() {
        if (exiting) return;
        exiting = true;
        dreaming = false;
        handler.removeCallbacks(tick);
        if (homeTransition) {
            MainActivity.transitionHomeForScreensaver(false);
            homeTransition = false;
        }
        header.animate().cancel();
        header.animate()
                .translationX(MainActivity.screensaverHeaderStartOffset(header))
                .alpha(0f)
                .setDuration(TRANSITION_MS)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
        background.animate().cancel();
        background.animate().alpha(0f).setDuration(TRANSITION_MS).start();
        backgroundShade.animate().alpha(0f).setDuration(TRANSITION_MS).start();
        ambient.animate().alpha(0f).setDuration(TRANSITION_MS).start();
        handler.postDelayed(this::finish, TRANSITION_MS);
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        if (glowAnimator != null) glowAnimator.cancel();
        glowAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(), from, PALETTE[colorIndex]);
        glowAnimator.setDuration(8000L);
        glowAnimator.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnimator.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }

    private void setRandomLandscape() {
        landscapeIndex = landscapeIndex < 0 ? 0 : random.nextInt(LANDSCAPE_IDS.length);
        background.setImageResource(HomeWallpaperCatalog.drawable(
                LANDSCAPE_IDS[landscapeIndex]));
    }

    private void cycleLandscape() {
        int nextIndex = landscapeIndex;
        while (nextIndex == landscapeIndex && LANDSCAPE_IDS.length > 1) {
            nextIndex = random.nextInt(LANDSCAPE_IDS.length);
        }
        landscapeIndex = nextIndex;
        background.animate().cancel();
        background.animate().alpha(0.15f).setDuration(700L).withEndAction(() -> {
            if (!dreaming || exiting) return;
            background.setImageResource(HomeWallpaperCatalog.drawable(
                    LANDSCAPE_IDS[landscapeIndex]));
            background.setScaleX(1.04f);
            background.setScaleY(1.04f);
            background.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f)
                    .setDuration(MOVE_MS).start();
        }).start();
    }

    private int getGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 12) return R.string.good_morning;
        if (hour < 18) return R.string.good_afternoon;
        if (hour < 22) return R.string.good_evening;
        return R.string.good_night;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
