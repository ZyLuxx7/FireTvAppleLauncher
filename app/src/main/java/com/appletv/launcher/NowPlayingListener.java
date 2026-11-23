package com.appletv.launcher;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads active media-session metadata only after the user grants notification-listener access. */
public final class NowPlayingListener extends NotificationListenerService {
    static final String ACTION_UPDATED = "com.appletv.launcher.action.NOW_PLAYING_UPDATED";

    static final class Snapshot {
        final String title;
        final String artist;
        final String album;
        final String lyrics;
        final String packageName;
        final Bitmap artwork;
        final boolean playing;
        final boolean active;
        final long durationMs;
        final long positionMs;
        final long positionUpdatedAtMs;
        final float playbackSpeed;
        final MediaController controller;
        final boolean spotifyRemote;
        final String spotifyDeviceId;
        final String spotifyArtworkUrl;
        final String spotifyTrackUri;

        Snapshot(String title, String artist, String album, String lyrics, String packageName,
                 Bitmap artwork, boolean playing, boolean active, long durationMs, long positionMs,
                 long positionUpdatedAtMs, float playbackSpeed, MediaController controller) {
            this(title, artist, album, lyrics, packageName, artwork, playing, active, durationMs,
                    positionMs, positionUpdatedAtMs, playbackSpeed, controller,
                    false, null, null, null);
        }

        Snapshot(String title, String artist, String album, String lyrics, String packageName,
                 Bitmap artwork, boolean playing, boolean active, long durationMs, long positionMs,
                 long positionUpdatedAtMs, float playbackSpeed, MediaController controller,
                 boolean spotifyRemote, String spotifyDeviceId, String spotifyArtworkUrl) {
            this(title, artist, album, lyrics, packageName, artwork, playing, active, durationMs,
                    positionMs, positionUpdatedAtMs, playbackSpeed, controller,
                    spotifyRemote, spotifyDeviceId, spotifyArtworkUrl, null);
        }

        Snapshot(String title, String artist, String album, String lyrics, String packageName,
                 Bitmap artwork, boolean playing, boolean active, long durationMs, long positionMs,
                 long positionUpdatedAtMs, float playbackSpeed, MediaController controller,
                 boolean spotifyRemote, String spotifyDeviceId, String spotifyArtworkUrl,
                 String spotifyTrackUri) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.lyrics = lyrics;
            this.packageName = packageName;
            this.artwork = artwork;
            this.playing = playing;
            this.active = active;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.positionUpdatedAtMs = positionUpdatedAtMs;
            this.playbackSpeed = playbackSpeed;
            this.controller = controller;
            this.spotifyRemote = spotifyRemote;
            this.spotifyDeviceId = spotifyDeviceId;
            this.spotifyArtworkUrl = spotifyArtworkUrl;
            this.spotifyTrackUri = spotifyTrackUri;
        }
    }

    private static volatile Snapshot latestLocal;
    private static volatile Snapshot latestSpotifyRemote;
    private static final AudioOutputSwitchGrace AUDIO_OUTPUT_SWITCH_GRACE =
            new AudioOutputSwitchGrace();
    private final List<MediaController> controllers = new ArrayList<>();
    private MediaSessionManager sessionManager;
    private boolean connected;
    private String cachedArtworkKey;
    private Bitmap cachedArtwork;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refreshSnapshot();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refreshSnapshot();
        }
    };

    static boolean hasAccess(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationListenerAccessGranted(
                new ComponentName(context, NowPlayingListener.class));
    }

    static Snapshot snapshot() {
        Snapshot local = latestLocal;
        Snapshot remote = latestSpotifyRemote;
        if (local != null && local.active && local.playing) return local;
        if (remote != null && remote.active) return remote;
        return local;
    }

    static boolean isSpotifyPlayback(Snapshot snapshot) {
        return snapshot != null && (snapshot.spotifyRemote
                || (snapshot.packageName != null && snapshot.packageName.startsWith("com.spotify.")));
    }

    static void beginAudioOutputSwitchGrace(Snapshot snapshot) {
        if (isSpotifyPlayback(snapshot)) {
            AUDIO_OUTPUT_SWITCH_GRACE.begin(android.os.SystemClock.elapsedRealtime());
        }
    }

    static long audioOutputSwitchGraceRemainingMs() {
        return AUDIO_OUTPUT_SWITCH_GRACE.remainingMs(
                android.os.SystemClock.elapsedRealtime());
    }

    static boolean expireAudioOutputSwitchGrace(Context context) {
        if (!AUDIO_OUTPUT_SWITCH_GRACE.expire(android.os.SystemClock.elapsedRealtime())) {
            return false;
        }
        latestSpotifyRemote = null;
        context.getApplicationContext().sendBroadcast(
                new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
        return true;
    }

    static boolean sameVisibleContent(Snapshot first, Snapshot second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return Objects.equals(first.title, second.title)
                && Objects.equals(first.artist, second.artist)
                && Objects.equals(first.album, second.album)
                && Objects.equals(first.lyrics, second.lyrics)
                && Objects.equals(first.packageName, second.packageName)
                && Objects.equals(first.spotifyDeviceId, second.spotifyDeviceId)
                && Objects.equals(first.spotifyArtworkUrl, second.spotifyArtworkUrl)
                && Objects.equals(first.spotifyTrackUri, second.spotifyTrackUri)
                && first.durationMs == second.durationMs
                && Float.compare(first.playbackSpeed, second.playbackSpeed) == 0
                && first.playing == second.playing
                && first.active == second.active
                && first.spotifyRemote == second.spotifyRemote
                && first.artwork == second.artwork;
    }

    static boolean shouldShowPlayerBannerInScreensaver(boolean expanded, Snapshot snapshot) {
        return expanded && snapshot != null && snapshot.active && snapshot.playing
                && isSpotifyPlayback(snapshot);
    }

    static void setSpotifyRemoteSnapshot(Context context, Snapshot snapshot) {
        AUDIO_OUTPUT_SWITCH_GRACE.complete();
        latestSpotifyRemote = snapshot;
        context.getApplicationContext().sendBroadcast(
                new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sessionManager = getSystemService(MediaSessionManager.class);
        connected = true;
        if (sessionManager != null) {
            sessionManager.addOnActiveSessionsChangedListener(activeSessionsListener,
                    new ComponentName(this, NowPlayingListener.class));
            refreshControllers();
        }
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        unregisterControllers();
        latestLocal = null;
        synchronized (this) {
            cachedArtworkKey = null;
            cachedArtwork = null;
        }
        announceUpdate();
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        refreshSnapshot();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        refreshSnapshot();
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (sessionManager != null) {
            sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener);
        }
        unregisterControllers();
        super.onDestroy();
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsListener =
            this::replaceControllers;

    private void refreshControllers() {
        if (!connected || sessionManager == null) return;
        replaceControllers(sessionManager.getActiveSessions(
                new ComponentName(this, NowPlayingListener.class)));
    }

    private void replaceControllers(List<MediaController> active) {
        unregisterControllers();
        if (active != null) {
            for (MediaController controller : active) {
                controller.registerCallback(controllerCallback);
                controllers.add(controller);
            }
        }
        refreshSnapshot();
    }

    private void unregisterControllers() {
        for (MediaController controller : controllers) {
            controller.unregisterCallback(controllerCallback);
        }
        controllers.clear();
    }

    private void refreshSnapshot() {
        MediaController selected = null;
        MediaController active = null;
        MediaController fallback = null;
        for (MediaController controller : controllers) {
            MediaMetadata metadata = controller.getMetadata();
            if (!hasDisplayableMetadata(metadata)) continue;
            PlaybackState state = controller.getPlaybackState();
            if (fallback == null) fallback = controller;
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                selected = controller;
                break;
            }
            if (active == null && state != null && isActivePlaybackState(state.getState())) {
                active = controller;
            }
        }
        if (selected == null) selected = active == null ? fallback : active;
        latestLocal = selected == null ? null : snapshotOf(selected);
        announceUpdate();
    }

    private static boolean hasDisplayableMetadata(MediaMetadata metadata) {
        if (metadata == null) return false;
        return hasText(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                || hasText(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                || hasText(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
                || hasText(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
                || hasText(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
                || hasText(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
                || metadata.containsKey(MediaMetadata.METADATA_KEY_ART)
                || metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART)
                || metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isActivePlaybackState(int state) {
        return state != PlaybackState.STATE_NONE
                && state != PlaybackState.STATE_STOPPED
                && state != PlaybackState.STATE_ERROR;
    }

    private Snapshot snapshotOf(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        String title = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        if (metadata != null) {
            if (title == null) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            if (artist == null) artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (album == null) {
                String description = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
                if (description != null && !description.equals(title) && !description.equals(artist)) {
                    album = description;
                }
            }
        }
        String lyrics = null;
        String mediaId = null;
        if (metadata != null) {
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            lyrics = metadata.getString("android.media.metadata.LYRICS");
            if (lyrics == null || lyrics.trim().isEmpty()) {
                lyrics = metadata.getString("android.media.metadata.LYRIC");
            }
        }
        String artworkUri = metadata == null ? null
                : metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (artworkUri == null && metadata != null) {
            artworkUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        }
        String artworkKey = controller.getPackageName() + "\n" + mediaId + "\n"
                + title + "\n" + artist + "\n" + album + "\n" + artworkUri;
        Bitmap artwork = artworkFor(metadata, artworkKey);
        long durationMs = metadata == null ? 0
                : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long positionMs = state == null ? 0 : state.getPosition();
        long positionUpdatedAtMs = state == null ? 0 : state.getLastPositionUpdateTime();
        float playbackSpeed = state == null ? 0f : state.getPlaybackSpeed();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        boolean active = state == null ? metadata != null : isActivePlaybackState(state.getState());
        return new Snapshot(title, artist, album, lyrics, controller.getPackageName(), artwork,
                playing, active, durationMs, positionMs, positionUpdatedAtMs, playbackSpeed,
                controller, false, null, null,
                SpotifyConnectClient.spotifyTrackUriForMediaId(controller.getPackageName(), mediaId));
    }

    private synchronized Bitmap artworkFor(MediaMetadata metadata, String key) {
        if (key.equals(cachedArtworkKey) && cachedArtwork != null) return cachedArtwork;
        Bitmap artwork = metadata == null ? null
                : metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (artwork == null && metadata != null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        }
        if (artwork == null && metadata != null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (artwork != null && Math.max(artwork.getWidth(), artwork.getHeight()) > 720) {
            float ratio = 720f / Math.max(artwork.getWidth(), artwork.getHeight());
            artwork = Bitmap.createScaledBitmap(artwork,
                    Math.max(1, Math.round(artwork.getWidth() * ratio)),
                    Math.max(1, Math.round(artwork.getHeight() * ratio)), true);
        }
        cachedArtworkKey = key;
        cachedArtwork = artwork;
        return artwork;
    }

    private void announceUpdate() {
        sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
    }
}
