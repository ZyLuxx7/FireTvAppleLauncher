package com.appletv.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PlaybackResumeHelper {
    private static final String TAG = "AppleTv.AudioOutput";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Launcher-AudioResume");
        thread.setDaemon(true);
        return thread;
    });

    private PlaybackResumeHelper() {
    }

    static void resumeIfPreviouslyPlaying(Context context, NowPlayingListener.Snapshot snapshot) {
        if (!shouldResume(snapshot, NowPlayingListener.snapshot())) return;
        if (snapshot.spotifyRemote) {
            Context appContext = context.getApplicationContext();
            EXECUTOR.execute(() -> {
                if (!shouldResume(snapshot, NowPlayingListener.snapshot())) return;
                try {
                    new SpotifyRemotePlayback(appContext).controlPlayback(
                            SpotifyConnectClient.PlaybackCommand.PLAY, snapshot.spotifyDeviceId);
                } catch (GeneralSecurityException | JSONException | IOException exception) {
                    Log.w(TAG, "Could not resume Spotify playback after changing audio output",
                            exception);
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(appContext, R.string.spotify_remote_control_error,
                                    Toast.LENGTH_SHORT).show());
                }
            });
        } else if (snapshot.controller != null) {
            try {
                snapshot.controller.getTransportControls().play();
            } catch (SecurityException exception) {
                Log.w(TAG, "Could not resume media playback after changing audio output",
                        exception);
                Toast.makeText(context, R.string.now_playing_resume_error,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    static boolean shouldResume(NowPlayingListener.Snapshot previous,
                                NowPlayingListener.Snapshot current) {
        if (previous == null || !previous.playing) return false;
        if (!previous.spotifyRemote && NowPlayingListener.isSpotifyPlayback(previous)) return false;
        if (current == null || !current.active) return true;
        if (current.playing || !samePlaybackTarget(previous, current)) return false;
        return true;
    }

    private static boolean samePlaybackTarget(NowPlayingListener.Snapshot first,
                                              NowPlayingListener.Snapshot second) {
        if (first.spotifyRemote != second.spotifyRemote) return false;
        if (first.spotifyRemote) {
            return Objects.equals(first.spotifyDeviceId, second.spotifyDeviceId)
                    && Objects.equals(first.spotifyTrackUri, second.spotifyTrackUri)
                    && Objects.equals(first.title, second.title)
                    && Objects.equals(first.artist, second.artist);
        }
        return Objects.equals(first.packageName, second.packageName)
                && Objects.equals(first.title, second.title)
                && Objects.equals(first.artist, second.artist);
    }
}
