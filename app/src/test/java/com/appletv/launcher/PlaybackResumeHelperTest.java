package com.appletv.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackResumeHelperTest {
    @Test
    public void doesNotSendAnotherPlayWhenPlaybackAlreadyContinued() {
        assertFalse(PlaybackResumeHelper.shouldResume(
                snapshot("Track", true), snapshot("Track", true)));
    }

    @Test
    public void resumesTheSameTrackIfTheOutputSwitchPausedIt() {
        assertTrue(PlaybackResumeHelper.shouldResume(
                snapshot("com.netflix.ninja", "Track", true),
                snapshot("com.netflix.ninja", "Track", false)));
    }

    @Test
    public void doesNotResumeTheLocalSpotifyAppAfterAnOutputSwitch() {
        assertFalse(PlaybackResumeHelper.shouldResume(
                snapshot("com.spotify.tv.android", "Track", true),
                snapshot("com.spotify.tv.android", "Track", false)));
    }

    @Test
    public void keepsSpotifyConnectRemoteResumeAvailable() {
        assertTrue(PlaybackResumeHelper.shouldResume(
                spotifyRemoteSnapshot("Track", true),
                spotifyRemoteSnapshot("Track", false)));
    }

    @Test
    public void doesNotResumeOverAChangedTrack() {
        assertFalse(PlaybackResumeHelper.shouldResume(
                snapshot("Track", true), snapshot("Different track", false)));
    }

    @Test
    public void doesNotResumeIfPlaybackWasAlreadyPaused() {
        assertFalse(PlaybackResumeHelper.shouldResume(
                snapshot("Track", false), snapshot("Track", false)));
    }

    private static NowPlayingListener.Snapshot snapshot(String title, boolean playing) {
        return snapshot("com.netflix.ninja", title, playing);
    }

    private static NowPlayingListener.Snapshot snapshot(
            String packageName, String title, boolean playing) {
        return new NowPlayingListener.Snapshot(title, "Artist", "Album", null,
                packageName, null, playing, true,
                180_000L, 12_000L, 0L, 1f, null);
    }

    private static NowPlayingListener.Snapshot spotifyRemoteSnapshot(
            String title, boolean playing) {
        return new NowPlayingListener.Snapshot(title, "Artist", "Album", null,
                "spotify-connect", null, playing, true, 180_000L, 12_000L, 0L,
                1f, null, true, "device-id", null, "spotify:track:track-id");
    }
}
