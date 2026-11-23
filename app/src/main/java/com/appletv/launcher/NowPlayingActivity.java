package com.appletv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, remote-friendly view backed by the current Android media session. */
public final class NowPlayingActivity extends LauncherActivity {
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    private View accessPanel;
    private View playerPanel;
    private ImageView artwork;
    private TextView title;
    private TextView artist;
    private TextView source;
    private TextView state;
    private TextView playPause;
    private ExecutorService remoteControlExecutor;
    private boolean receiverRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_now_playing);
        remoteControlExecutor = Executors.newSingleThreadExecutor();
        accessPanel = findViewById(R.id.nowPlayingAccessPanel);
        playerPanel = findViewById(R.id.nowPlayingPlayerPanel);
        artwork = findViewById(R.id.nowPlayingArtwork);
        title = findViewById(R.id.nowPlayingTrack);
        artist = findViewById(R.id.nowPlayingArtist);
        source = findViewById(R.id.nowPlayingSource);
        this.state = findViewById(R.id.nowPlayingState);
        playPause = findViewById(R.id.nowPlayingPlayPause);

        findViewById(R.id.nowPlayingBack).setOnClickListener(v -> finish());
        findViewById(R.id.nowPlayingEnableAccess).setOnClickListener(v -> openAccessSettings());
        findViewById(R.id.nowPlayingPrevious).setOnClickListener(v -> sendTransport(
                SpotifyConnectClient.PlaybackCommand.PREVIOUS,
                MediaController.TransportControls::skipToPrevious));
        findViewById(R.id.nowPlayingNext).setOnClickListener(v -> sendTransport(
                SpotifyConnectClient.PlaybackCommand.NEXT,
                MediaController.TransportControls::skipToNext));
        findViewById(R.id.nowPlayingAudioOutput).setOnClickListener(v ->
                startActivity(new Intent(this, AudioOutputActivity.class)));
        playPause.setOnClickListener(v -> {
            NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
            if (snapshot == null) return;
            if (snapshot.spotifyRemote) {
                sendRemoteCommand(snapshot.playing
                        ? SpotifyConnectClient.PlaybackCommand.PAUSE
                        : SpotifyConnectClient.PlaybackCommand.PLAY, snapshot);
            } else if (snapshot.controller != null) {
                if (snapshot.playing) snapshot.controller.getTransportControls().pause();
                else snapshot.controller.getTransportControls().play();
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updates, new IntentFilter(NowPlayingListener.ACTION_UPDATED),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updates, new IntentFilter(NowPlayingListener.ACTION_UPDATED));
        }
        receiverRegistered = true;
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(updates);
        if (remoteControlExecutor != null) remoteControlExecutor.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        boolean access = NowPlayingListener.hasAccess(this)
                || (snapshot != null && snapshot.spotifyRemote);
        accessPanel.setVisibility(access ? View.GONE : View.VISIBLE);
        playerPanel.setVisibility(access ? View.VISIBLE : View.GONE);
        if (!access) return;

        if (snapshot == null) {
            title.setText(R.string.now_playing_nothing);
            artist.setText("");
            source.setText("");
            state.setText("");
            artwork.setImageDrawable(null);
            playPause.setEnabled(false);
            findViewById(R.id.nowPlayingPrevious).setEnabled(false);
            findViewById(R.id.nowPlayingNext).setEnabled(false);
            return;
        }
        title.setText(nonEmpty(snapshot.title, R.string.now_playing_unknown_title));
        artist.setText(nonEmpty(snapshot.artist, R.string.now_playing_unknown_artist));
        if (snapshot.spotifyRemote) {
            source.setText(R.string.spotify_connect_title);
        } else {
            try {
                CharSequence appName = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(snapshot.packageName, 0));
                source.setText(appName);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                source.setText(snapshot.packageName);
            }
        }
        state.setText(snapshot.playing ? R.string.now_playing_playing : R.string.now_playing_paused);
        Bitmap image = snapshot.artwork;
        if (image == null) artwork.setImageDrawable(null);
        else artwork.setImageBitmap(image);
        playPause.setText(snapshot.playing ? R.string.now_playing_pause : R.string.now_playing_play);
        boolean canControl = snapshot.controller != null || snapshot.spotifyRemote;
        playPause.setEnabled(canControl);
        findViewById(R.id.nowPlayingPrevious).setEnabled(canControl);
        findViewById(R.id.nowPlayingNext).setEnabled(canControl);
    }

    private CharSequence nonEmpty(String value, int fallback) {
        return value == null || value.trim().isEmpty() ? getText(fallback) : value;
    }

    private interface TransportAction {
        void run(MediaController.TransportControls controls);
    }

    private void sendTransport(
            SpotifyConnectClient.PlaybackCommand command, TransportAction action) {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        if (snapshot == null) return;
        if (snapshot.spotifyRemote) {
            sendRemoteCommand(command, snapshot);
        } else if (snapshot.controller != null) {
            action.run(snapshot.controller.getTransportControls());
        }
    }

    private void sendRemoteCommand(
            SpotifyConnectClient.PlaybackCommand command, NowPlayingListener.Snapshot previous) {
        if (remoteControlExecutor == null || remoteControlExecutor.isShutdown()) return;
        remoteControlExecutor.execute(() -> {
            try {
                SpotifyRemotePlayback remote = new SpotifyRemotePlayback(getApplicationContext());
                remote.controlPlayback(command, previous.spotifyDeviceId);
                SpotifyConnectClient.Playback playback = remote.currentPlayback();
                Bitmap image = null;
                if (playback != null && playback.artworkUrl != null) {
                    if (playback.artworkUrl.equals(previous.spotifyArtworkUrl)) {
                        image = previous.artwork;
                    } else {
                        try {
                            image = SpotifyConnectClient.downloadArtwork(playback.artworkUrl);
                        } catch (IOException exception) {
                            android.util.Log.w("AppleTv.NowPlaying",
                                    "Could not load Spotify album artwork", exception);
                        }
                    }
                }
                NowPlayingListener.setSpotifyRemoteSnapshot(getApplicationContext(),
                        playback == null ? null : SpotifyRemotePlayback.snapshot(playback, image));
            } catch (GeneralSecurityException | org.json.JSONException | IOException exception) {
                android.util.Log.w("AppleTv.NowPlaying", "Spotify playback control failed", exception);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.spotify_remote_control_error,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void openAccessSettings() {
        NowPlayingAccessSettings.open(this);
    }
}
