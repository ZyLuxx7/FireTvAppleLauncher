package com.appletv.launcher;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Compact TV control center opened by the remote's Settings button. */
public final class QuickSettingsActivity extends LauncherActivity {
    private static final String SYSTEM_SETTINGS_HANDOFF =
            "de.gabri.firetvhomehelper.SYSTEM_SETTINGS_HANDOFF";
    private static final String TAG = "AppleTv.QuickSettings";
    private static final String HIDDEN_CONTROLS_KEY = Config.QUICK_PANEL_HIDDEN_CONTROLS_KEY;
    private static final String[] CONTROL_KEYS = {
            "now_playing", "wifi", "audio", "clock", "wallpaper", "memory"
    };
    private static final int[] CONTROL_LABELS = {
            R.string.quick_large_player, R.string.quick_wifi, R.string.quick_audio,
            R.string.quick_clock, R.string.quick_wallpaper, R.string.quick_control_memory
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService remoteControlExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver nowPlayingUpdates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNowPlaying();
        }
    };
    private final Runnable playbackProgressTicker = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress(NowPlayingListener.snapshot());
            if (!isFinishing() && !isDestroyed()) {
                handler.postDelayed(this, 1000);
            }
        }
    };
    private FocusGlassController focusGlass;
    private boolean lightAppearance;
    private boolean receiverRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        MainActivity.hideHomeHeaderForQuickPanel();
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        setContentView(R.layout.activity_quick_settings);
        overridePendingTransition(0, 0);
        focusGlass = new FocusGlassController(this);
        lightAppearance = LauncherUiSettings.isLight(this);
        MainActivity.copyHomeGreeting(
                (TextView) findViewById(R.id.quickHeaderGreeting));
        TextView shamsiDate = findViewById(R.id.quickHeaderShamsiDate);
        if (Config.load(this).shamsiDate) {
            shamsiDate.setText(Shamsi.today());
            shamsiDate.setVisibility(View.VISIBLE);
        }
        applyPanelTheme();
        applyQuickControlVisibility();
        setQuickPanelPage(false, false);
        View panel = findViewById(R.id.panel);
        View pageRail = findViewById(R.id.quickPageRail);
        float panelScale = Math.max(0.8f, Math.min(1.2f,
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                        .getInt("quick_panel_scale", 100) / 100f));
        panel.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            panel.setPivotX(panel.getWidth());
            panel.setPivotY(0f);
            panel.setScaleX(panelScale);
            panel.setScaleY(panelScale);
            panel.setAlpha(0f);
            pageRail.setPivotX(pageRail.getWidth());
            pageRail.setPivotY(0f);
            pageRail.setScaleX(panelScale);
            pageRail.setScaleY(panelScale);
            float startOffset = -10f * getResources().getDisplayMetrics().density;
            panel.setTranslationY(startOffset);
            pageRail.setAlpha(0f);
            pageRail.setTranslationY(startOffset);
            android.view.animation.Interpolator interpolator =
                    new android.view.animation.DecelerateInterpolator(1.4f);
            panel.animate().alpha(1f).translationY(0f)
                    .setDuration(200).setInterpolator(interpolator).start();
            pageRail.animate().alpha(1f).translationY(0f)
                    .setDuration(200).setInterpolator(interpolator).start();
        });

        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        findViewById(R.id.panel).setOnClickListener(v -> { });
        findViewById(R.id.panel).setOnLongClickListener(v -> {
            showQuickControlsEditor();
            return true;
        });
        findViewById(R.id.quickStore).setOnClickListener(v -> openStore());
        findViewById(R.id.quickRecent).setOnClickListener(v ->
                open(new Intent(this, RecentAppsActivity.class)));
        findViewById(R.id.quickSystemSettings).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        findViewById(R.id.quickPower).setOnClickListener(v ->
                TvPowerDialogHelper.request(this,
                        () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                        () -> open(new Intent(Settings.ACTION_SETTINGS))));
        findViewById(R.id.quickPageHeart).setOnClickListener(v ->
                setQuickPanelPage(false, true));
        findViewById(R.id.quickPageDot).setOnClickListener(v ->
                setQuickPanelPage(true, true));
        findViewById(R.id.quickAddControl).setOnClickListener(v ->
                showQuickControlsEditor());
        View.OnLongClickListener editControls = v -> {
            showQuickControlsEditor();
            return true;
        };
        for (int id : new int[]{
                R.id.quickControlsPage, R.id.quickPlayerPage, R.id.nowPlayingTile,
                R.id.wifiTile, R.id.audioTile, R.id.clockTile, R.id.wallpaperTile,
                R.id.quickMemoryStatus, R.id.quickLargePlayer
        }) {
            findViewById(id).setOnLongClickListener(editControls);
        }
        findViewById(R.id.nowPlayingTile).setOnClickListener(v ->
                open(new Intent(this, NowPlayingActivity.class)));
        findViewById(R.id.quickTrackTitle).setOnClickListener(v ->
                open(new Intent(this, NowPlayingActivity.class)));
        findViewById(R.id.nowPlayingExpand).setOnClickListener(v -> expandHomePlayer());
        findViewById(R.id.nowPlayingPrevious).setOnClickListener(v ->
                sendTransport(SpotifyConnectClient.PlaybackCommand.PREVIOUS,
                        (controls, playing) -> controls.skipToPrevious()));
        findViewById(R.id.nowPlayingPlayPause).setOnClickListener(v -> togglePlayback());
        findViewById(R.id.nowPlayingNext).setOnClickListener(v ->
                sendTransport(SpotifyConnectClient.PlaybackCommand.NEXT,
                        (controls, playing) -> controls.skipToNext()));
        findViewById(R.id.quickLargePrevious).setOnClickListener(v ->
                sendTransport(SpotifyConnectClient.PlaybackCommand.PREVIOUS,
                        (controls, playing) -> controls.skipToPrevious()));
        findViewById(R.id.quickLargePlayPause).setOnClickListener(v -> togglePlayback());
        findViewById(R.id.quickLargeNext).setOnClickListener(v ->
                sendTransport(SpotifyConnectClient.PlaybackCommand.NEXT,
                        (controls, playing) -> controls.skipToNext()));
        findViewById(R.id.wifiTile).setOnClickListener(v ->
                open(new Intent(this, NetworkSettingsActivity.class)));
        findViewById(R.id.audioTile).setOnClickListener(v ->
                open(new Intent(this, AudioOutputActivity.class)));
        findViewById(R.id.clockTile).setOnClickListener(v ->
                open(new Intent(this, ClockPreviewActivity.class)));
        findViewById(R.id.wallpaperTile).setOnClickListener(v ->
                open(new Intent(this, LauncherSettingsActivity.class)
                        .putExtra(LauncherSettingsActivity.EXTRA_OPEN_WALLPAPER_PICKER, true)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nowPlayingUpdates,
                    new IntentFilter(NowPlayingListener.ACTION_UPDATED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(nowPlayingUpdates, new IntentFilter(NowPlayingListener.ACTION_UPDATED));
        }
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateNowPlaying();
        handler.removeCallbacks(playbackProgressTicker);
        handler.post(playbackProgressTicker);
        requestPageFocus();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen closes the overlay. */
    @Override
    protected void onPause() {
        handler.removeCallbacks(playbackProgressTicker);
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(playbackProgressTicker);
        if (receiverRegistered) unregisterReceiver(nowPlayingUpdates);
        remoteControlExecutor.shutdownNow();
        if (focusGlass != null) focusGlass.close();
        super.onDestroy();
    }

    private void applyPanelTheme() {
        int tileBackground = lightAppearance
                ? R.drawable.quick_control_button_bg_light : R.drawable.quick_control_button_bg;
        ColorStateList primaryText = getColorStateList(lightAppearance
                ? R.color.quick_control_text_light : R.color.quick_control_text_dark);
        ColorStateList secondaryText = getColorStateList(lightAppearance
                ? R.color.quick_control_subtitle_light : R.color.quick_control_subtitle_dark);
        ColorStateList lightText = getColorStateList(R.color.quick_control_text_light);

        int[] darkTiles = {
                R.id.nowPlayingTile, R.id.wifiTile,
                R.id.clockTile, R.id.wallpaperTile
        };
        for (int id : darkTiles) findViewById(id).setBackgroundResource(tileBackground);
        findViewById(R.id.quickLargePlayer).setBackgroundResource(lightAppearance
                ? R.drawable.quick_control_button_bg_light : R.drawable.quick_control_player_bg);
        findViewById(R.id.audioTile).setBackgroundResource(lightAppearance
                ? R.drawable.quick_control_button_bg_light : R.drawable.quick_control_audio_bg);

        int[] lightSurfaceText = {
                R.id.audioTileTitle, R.id.audioTileStatus
        };
        int[] primaryTextIds = {
                R.id.quickTrackTitle,
                R.id.wifiTileTitle, R.id.clockTileTitle, R.id.wallpaperTileTitle,
                R.id.quickLargePlayerLabel, R.id.quickLargeTrackTitle
        };
        int[] secondaryTextIds = {
                R.id.quickTrackArtist, R.id.wifiTileStatus, R.id.clockTileStatus,
                R.id.wallpaperTileStatus, R.id.quickMemoryStatus, R.id.quickLargeTrackArtist,
                R.id.quickLargeElapsed, R.id.quickLargeDuration
        };
        for (int id : primaryTextIds) ((TextView) findViewById(id)).setTextColor(primaryText);
        for (int id : secondaryTextIds) ((TextView) findViewById(id)).setTextColor(secondaryText);
        for (int id : lightSurfaceText) ((TextView) findViewById(id)).setTextColor(lightText);
        for (int id : new int[]{
                R.id.nowPlayingPrevious, R.id.nowPlayingPlayPause,
                R.id.nowPlayingNext, R.id.nowPlayingExpand,
                R.id.quickPageHeart, R.id.quickPageDot, R.id.quickAddControl,
                R.id.audioTileIcon,
                R.id.clockTileIcon, R.id.wallpaperTileIcon, R.id.quickLargePrevious,
                R.id.quickLargePlayPause, R.id.quickLargeNext
        }) {
            ImageView icon = findViewById(id);
            icon.setImageTintList(primaryText);
        }
        ((ImageView) findViewById(R.id.wifiTileIcon)).setImageTintList(
                ColorStateList.valueOf(0xFF40B5FF));
        ((ImageView) findViewById(R.id.audioTileIcon)).setImageTintList(
                lightAppearance ? primaryText : lightText);
        findViewById(R.id.quickMemoryStatus).setBackgroundResource(lightAppearance
                ? R.drawable.quick_control_footer_bg_light : R.drawable.quick_control_footer_bg);
        ProgressBar progress = findViewById(R.id.quickLargeProgress);
        progress.setProgressTintList(primaryText);
        progress.setProgressBackgroundTintList(secondaryText);
    }

    private void setQuickPanelPage(boolean playerPage, boolean requestFocus) {
        boolean showPlayer = playerPage
                && findViewById(R.id.quickPageDot).getVisibility() == View.VISIBLE;
        findViewById(R.id.quickControlsPage)
                .setVisibility(showPlayer ? View.GONE : View.VISIBLE);
        findViewById(R.id.quickPlayerPage)
                .setVisibility(showPlayer ? View.VISIBLE : View.GONE);

        ImageButton controlsTab = findViewById(R.id.quickPageHeart);
        ImageButton playerTab = findViewById(R.id.quickPageDot);
        controlsTab.setBackgroundResource(showPlayer
                ? R.drawable.quick_control_circle_bg : R.drawable.quick_control_circle_bg_light);
        playerTab.setBackgroundResource(showPlayer
                ? R.drawable.quick_control_circle_bg_light : R.drawable.quick_control_circle_bg);
        ColorStateList selectedTint = ColorStateList.valueOf(0xFF1C1C1E);
        ColorStateList idleTint = ColorStateList.valueOf(
                lightAppearance ? 0xFF1C1C1E : 0xFFFFFFFF);
        controlsTab.setImageTintList(showPlayer ? idleTint : selectedTint);
        playerTab.setImageTintList(showPlayer ? selectedTint : idleTint);
        findViewById(R.id.quickSettingsScroll).scrollTo(0, 0);
        if (requestFocus) requestPageFocus();
    }

    private void requestPageFocus() {
        boolean playerPage = findViewById(R.id.quickPlayerPage).getVisibility() == View.VISIBLE;
        int[] candidates = playerPage
                ? new int[]{R.id.quickLargePlayer, R.id.quickLargePrevious}
                : new int[]{R.id.nowPlayingTile, R.id.wifiTile, R.id.audioTile,
                        R.id.clockTile, R.id.wallpaperTile, R.id.quickMemoryStatus};
        for (int id : candidates) {
            View target = findViewById(id);
            if (target.getVisibility() == View.VISIBLE && target.isFocusable()) {
                target.requestFocus();
                return;
            }
        }
        findViewById(R.id.quickAddControl).requestFocus();
    }

    private void applyQuickControlVisibility() {
        Set<String> hidden = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getStringSet(HIDDEN_CONTROLS_KEY, Collections.emptySet());
        boolean showNowPlaying = !hidden.contains(CONTROL_KEYS[0]);
        boolean showWifi = !hidden.contains(CONTROL_KEYS[1]);
        boolean showAudio = !hidden.contains(CONTROL_KEYS[2]);
        boolean showClock = !hidden.contains(CONTROL_KEYS[3]);
        boolean showWallpaper = !hidden.contains(CONTROL_KEYS[4]);
        boolean showMemory = !hidden.contains(CONTROL_KEYS[5]);

        findViewById(R.id.nowPlayingTile).setVisibility(showNowPlaying ? View.VISIBLE : View.GONE);
        findViewById(R.id.quickLargePlayer).setVisibility(showNowPlaying ? View.VISIBLE : View.GONE);
        findViewById(R.id.quickPageDot).setVisibility(showNowPlaying ? View.VISIBLE : View.GONE);
        findViewById(R.id.wifiTile).setVisibility(showWifi ? View.VISIBLE : View.GONE);
        findViewById(R.id.audioTile).setVisibility(showAudio ? View.VISIBLE : View.GONE);
        findViewById(R.id.quickRightColumn).setVisibility(
                showWifi || showAudio ? View.VISIBLE : View.GONE);
        findViewById(R.id.quickMainRow).setVisibility(
                showNowPlaying || showWifi || showAudio ? View.VISIBLE : View.GONE);
        findViewById(R.id.clockTile).setVisibility(showClock ? View.VISIBLE : View.GONE);
        findViewById(R.id.wallpaperTile).setVisibility(showWallpaper ? View.VISIBLE : View.GONE);
        findViewById(R.id.quickUtilityRow).setVisibility(
                showClock || showWallpaper ? View.VISIBLE : View.GONE);
        findViewById(R.id.quickMemoryStatus).setVisibility(showMemory ? View.VISIBLE : View.GONE);
        if (!showNowPlaying
                && findViewById(R.id.quickPlayerPage).getVisibility() == View.VISIBLE) {
            setQuickPanelPage(false, false);
        }
    }

    private void showQuickControlsEditor() {
        Set<String> hidden = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getStringSet(HIDDEN_CONTROLS_KEY, Collections.emptySet());
        String[] labels = new String[CONTROL_LABELS.length];
        boolean[] checked = new boolean[CONTROL_KEYS.length];
        for (int i = 0; i < CONTROL_KEYS.length; i++) {
            labels[i] = getString(CONTROL_LABELS[i]);
            checked[i] = !hidden.contains(CONTROL_KEYS[i]);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.quick_edit_title)
                .setMultiChoiceItems(labels, checked,
                        (ignored, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.quick_edit_done, (ignored, which) ->
                        saveQuickControls(checked))
                .setNeutralButton(R.string.quick_edit_reset, (ignored, which) -> {
                    getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                            .remove(HIDDEN_CONTROLS_KEY).apply();
                    applyQuickControlVisibility();
                    setQuickPanelPage(false, false);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(lightAppearance
                        ? R.drawable.quick_glass_panel_light : R.drawable.quick_glass_panel);
            }
        });
        dialog.show();
    }

    private void saveQuickControls(boolean[] visibleControls) {
        Set<String> hidden = new HashSet<>();
        for (int i = 0; i < visibleControls.length; i++) {
            if (!visibleControls[i]) hidden.add(CONTROL_KEYS[i]);
        }
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                .putStringSet(HIDDEN_CONTROLS_KEY, hidden).apply();
        applyQuickControlVisibility();
        if (findViewById(R.id.quickPlayerPage).getVisibility() == View.VISIBLE
                && !visibleControls[0]) {
            setQuickPanelPage(false, true);
        }
    }

    private void updateStatus() {
        String wifi = wifiStatus();
        ((TextView) findViewById(R.id.wifiTileStatus)).setText(wifi);
        ((TextView) findViewById(R.id.audioTileStatus)).setText(audioStatus());

        String wallpaperId = HomeWallpaperCatalog.selectedId(this);
        int wallpaperLabel = HomeWallpaperCatalog.isBanner(wallpaperId)
                ? R.string.quick_wallpaper_dynamic : HomeWallpaperCatalog.label(wallpaperId);
        ((TextView) findViewById(R.id.wallpaperTileStatus)).setText(getString(wallpaperLabel));

        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) {
            ((TextView) findViewById(R.id.quickMemoryStatus))
                    .setText(R.string.quick_memory_unavailable);
            return;
        }
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        long availableStorage = new StatFs(Environment.getDataDirectory().getAbsolutePath())
                .getAvailableBytes();
        ((TextView) findViewById(R.id.quickMemoryStatus)).setText(getString(
                R.string.quick_memory_status,
                Formatter.formatShortFileSize(this, memory.availMem),
                Formatter.formatShortFileSize(this, availableStorage)));
    }

    private void updateNowPlaying() {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        TextView title = findViewById(R.id.quickTrackTitle);
        TextView artist = findViewById(R.id.quickTrackArtist);
        ImageView artwork = findViewById(R.id.nowPlayingArtwork);
        TextView largeTitle = findViewById(R.id.quickLargeTrackTitle);
        TextView largeArtist = findViewById(R.id.quickLargeTrackArtist);
        ImageView largeArtwork = findViewById(R.id.quickLargeArtwork);
        ImageButton previous = findViewById(R.id.nowPlayingPrevious);
        ImageButton playPause = findViewById(R.id.nowPlayingPlayPause);
        ImageButton next = findViewById(R.id.nowPlayingNext);
        ImageButton largePrevious = findViewById(R.id.quickLargePrevious);
        ImageButton largePlayPause = findViewById(R.id.quickLargePlayPause);
        ImageButton largeNext = findViewById(R.id.quickLargeNext);

        if (snapshot == null) {
            title.setText(R.string.now_playing_nothing);
            artist.setText("");
            largeTitle.setText(R.string.now_playing_nothing);
            largeArtist.setText("");
            setNowPlayingArtwork(artwork, null);
            setNowPlayingArtwork(largeArtwork, null);
            previous.setEnabled(false);
            playPause.setEnabled(false);
            next.setEnabled(false);
            largePrevious.setEnabled(false);
            largePlayPause.setEnabled(false);
            largeNext.setEnabled(false);
            findViewById(R.id.nowPlayingExpand).setEnabled(false);
            playPause.setImageResource(R.drawable.ic_media_play);
            playPause.setContentDescription(getString(R.string.now_playing_play));
            largePlayPause.setImageResource(R.drawable.ic_media_play);
            largePlayPause.setContentDescription(getString(R.string.now_playing_play));
            updatePlaybackProgress(null);
            return;
        }

        title.setText(nonEmpty(snapshot.title, R.string.now_playing_unknown_title));
        artist.setText(nonEmpty(snapshot.artist, R.string.now_playing_unknown_artist));
        largeTitle.setText(nonEmpty(snapshot.title, R.string.now_playing_unknown_title));
        largeArtist.setText(nonEmpty(snapshot.artist, R.string.now_playing_unknown_artist));
        setNowPlayingArtwork(artwork, snapshot);
        setNowPlayingArtwork(largeArtwork, snapshot);
        boolean canControl = snapshot.controller != null || snapshot.spotifyRemote;
        previous.setEnabled(canControl);
        playPause.setEnabled(canControl);
        next.setEnabled(canControl);
        largePrevious.setEnabled(canControl);
        largePlayPause.setEnabled(canControl);
        largeNext.setEnabled(canControl);
        findViewById(R.id.nowPlayingExpand).setEnabled(true);
        playPause.setImageResource(snapshot.playing
                ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        playPause.setContentDescription(getString(snapshot.playing
                ? R.string.now_playing_pause : R.string.now_playing_play));
        largePlayPause.setImageResource(snapshot.playing
                ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        largePlayPause.setContentDescription(getString(snapshot.playing
                ? R.string.now_playing_pause : R.string.now_playing_play));
        updatePlaybackProgress(snapshot);
    }

    private void setNowPlayingArtwork(
            ImageView artwork, NowPlayingListener.Snapshot snapshot) {
        if (snapshot == null || snapshot.artwork == null) {
            artwork.setImageResource(R.drawable.ic_music_note);
            artwork.setImageTintList(ColorStateList.valueOf(
                    lightAppearance ? 0xFF6E6E73 : 0xBFFFFFFF));
        } else {
            artwork.setImageBitmap(snapshot.artwork);
            artwork.setImageTintList(null);
        }
    }

    private void updatePlaybackProgress(NowPlayingListener.Snapshot snapshot) {
        ProgressBar progress = findViewById(R.id.quickLargeProgress);
        TextView elapsed = findViewById(R.id.quickLargeElapsed);
        TextView duration = findViewById(R.id.quickLargeDuration);
        long durationMs = snapshot == null ? 0 : Math.max(0, snapshot.durationMs);
        long positionMs = snapshot == null ? 0 : Math.max(0, snapshot.positionMs);
        if (snapshot != null && snapshot.playing && snapshot.positionUpdatedAtMs > 0) {
            long sinceUpdate = Math.max(0,
                    SystemClock.elapsedRealtime() - snapshot.positionUpdatedAtMs);
            positionMs += (long) (sinceUpdate * Math.max(0, snapshot.playbackSpeed));
        }
        if (durationMs > 0) positionMs = Math.min(positionMs, durationMs);
        progress.setProgress(durationMs == 0 ? 0
                : (int) Math.round((double) positionMs * 1000 / durationMs));
        elapsed.setText(DateUtils.formatElapsedTime(positionMs / 1000));
        duration.setText(DateUtils.formatElapsedTime(durationMs / 1000));
    }

    private String wifiStatus() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network activeNetwork = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = activeNetwork == null || manager == null
                ? null : manager.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) return getString(R.string.quick_wifi_offline);
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return getString(R.string.quick_network_wired);
        }
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return getString(R.string.quick_wifi_connected);
        }

        try {
            WifiManager wifiManager = getSystemService(WifiManager.class);
            WifiInfo info = wifiManager == null ? null : wifiManager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && ssid.length() >= 2 && ssid.charAt(0) == '"'
                    && ssid.charAt(ssid.length() - 1) == '"') {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid != null && !ssid.trim().isEmpty()
                    && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                return ssid;
            }
        } catch (SecurityException ignored) {
            return getString(R.string.quick_wifi_connected);
        }
        return getString(R.string.quick_wifi_connected);
    }

    private String audioStatus() {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        if (snapshot != null && snapshot.spotifyRemote) {
            return getString(R.string.quick_audio_spotify);
        }
        AudioManager manager = getSystemService(AudioManager.class);
        if (manager != null) {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                    return getString(R.string.quick_audio_bluetooth);
                }
            }
        }
        return getString(R.string.quick_audio_tv);
    }

    private CharSequence nonEmpty(String value, int fallback) {
        return value == null || value.trim().isEmpty() ? getText(fallback) : value;
    }

    private void togglePlayback() {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        if (snapshot == null) return;
        sendTransport(snapshot.playing ? SpotifyConnectClient.PlaybackCommand.PAUSE
                : SpotifyConnectClient.PlaybackCommand.PLAY, (controls, playing) -> {
            if (playing) controls.pause();
            else controls.play();
        });
    }

    private void sendTransport(
            SpotifyConnectClient.PlaybackCommand command, LocalTransportAction localAction) {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        if (snapshot == null) return;
        if (snapshot.spotifyRemote) {
            sendRemoteCommand(command, snapshot);
        } else if (snapshot.controller != null) {
            localAction.run(snapshot.controller.getTransportControls(), snapshot.playing);
        }
    }

    private void sendRemoteCommand(
            SpotifyConnectClient.PlaybackCommand command, NowPlayingListener.Snapshot previous) {
        if (remoteControlExecutor.isShutdown()) return;
        remoteControlExecutor.execute(() -> {
            try {
                SpotifyRemotePlayback remote = new SpotifyRemotePlayback(getApplicationContext());
                remote.controlPlayback(command, previous.spotifyDeviceId);
                SpotifyConnectClient.Playback playback = remote.currentPlayback();
                Bitmap artwork = null;
                if (playback != null && playback.artworkUrl != null) {
                    if (playback.artworkUrl.equals(previous.spotifyArtworkUrl)) {
                        artwork = previous.artwork;
                    } else {
                        try {
                            artwork = SpotifyConnectClient.downloadArtwork(playback.artworkUrl);
                        } catch (IOException exception) {
                            Log.w(TAG, "Could not load Spotify album artwork", exception);
                        }
                    }
                }
                NowPlayingListener.setSpotifyRemoteSnapshot(getApplicationContext(),
                        playback == null ? null : SpotifyRemotePlayback.snapshot(playback, artwork));
            } catch (GeneralSecurityException | JSONException | IOException exception) {
                Log.w(TAG, "Spotify playback control failed", exception);
                handler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.spotify_remote_control_error,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void openStore() {
        Intent launch = AppStoreLauncher.launchIntent(this);
        if (launch != null) {
            open(launch);
        } else {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent intent) {
        try {
            if (Settings.ACTION_SETTINGS.equals(intent.getAction())) {
                sendBroadcast(new Intent(SYSTEM_SETTINGS_HANDOFF)
                        .setPackage("de.gabri.firetvhomehelper"));
            }

            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void expandHomePlayer() {
        open(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_EXPAND_HOME_PLAYER, true));
    }

    private interface LocalTransportAction {
        void run(MediaController.TransportControls controls, boolean playing);
    }
}
