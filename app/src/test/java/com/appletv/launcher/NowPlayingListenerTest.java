package com.appletv.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NowPlayingListenerTest {
    @Test
    public void screensaverPlayerRequiresExpandedPlayingSpotifySession() {
        NowPlayingListener.Snapshot spotify = snapshot(
                "com.spotify.tv.android", true, true, false);
        NowPlayingListener.Snapshot netflix = snapshot(
                "com.netflix.ninja", true, true, false);
        NowPlayingListener.Snapshot pausedSpotify = snapshot(
                "com.spotify.tv.android", false, true, false);

        assertTrue(NowPlayingListener.shouldShowPlayerBannerInScreensaver(true, spotify));
        assertFalse(NowPlayingListener.shouldShowPlayerBannerInScreensaver(true, netflix));
        assertFalse(NowPlayingListener.shouldShowPlayerBannerInScreensaver(true, pausedSpotify));
        assertFalse(NowPlayingListener.shouldShowPlayerBannerInScreensaver(false, spotify));
    }

    @Test
    public void positionOnlyUpdatesDoNotChangeVisibleMediaContent() {
        NowPlayingListener.Snapshot first = snapshot(
                "spotify-connect", true, true, true, 10_000L);
        NowPlayingListener.Snapshot positionUpdate = snapshot(
                "spotify-connect", true, true, true, 20_000L);

        assertTrue(NowPlayingListener.sameVisibleContent(first, positionUpdate));
        assertFalse(NowPlayingListener.sameVisibleContent(first,
                snapshot("spotify-connect", false, true, true, 20_000L)));
    }

    private static NowPlayingListener.Snapshot snapshot(
            String packageName, boolean playing, boolean active, boolean spotifyRemote) {
        return snapshot(packageName, playing, active, spotifyRemote, 10_000L);
    }

    private static NowPlayingListener.Snapshot snapshot(
            String packageName, boolean playing, boolean active,
            boolean spotifyRemote, long positionMs) {
        return new NowPlayingListener.Snapshot(
                "Track", "Artist", "Album", null, packageName, null,
                playing, active, 180_000L, positionMs, 0L, 1f, null,
                spotifyRemote, null, null);
    }
}
