package com.appletv.launcher;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/** Lets the user choose Fire TV Bluetooth audio or Spotify Connect. */
public final class AudioOutputActivity extends LauncherActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NowPlayingListener.Snapshot playbackBeforeBluetoothSettings;
    private boolean returningFromBluetoothSettings;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_audio_output);

        findViewById(R.id.audioOutputBack).setOnClickListener(v -> finish());
        findViewById(R.id.audioOutputFireTv).setOnClickListener(v -> openFireTvBluetoothSettings());
        findViewById(R.id.audioOutputSpotify).setOnClickListener(v ->
                startActivity(new Intent(this, SpotifyConnectActivity.class)));
        findViewById(R.id.audioOutputFireTv).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!returningFromBluetoothSettings) return;
        returningFromBluetoothSettings = false;
        NowPlayingListener.Snapshot snapshot = playbackBeforeBluetoothSettings;
        playbackBeforeBluetoothSettings = null;
        NowPlayingListener.beginAudioOutputSwitchGrace(snapshot);
        handler.postDelayed(() -> PlaybackResumeHelper.resumeIfPreviouslyPlaying(this, snapshot),
                800);
    }

    private void openFireTvBluetoothSettings() {
        playbackBeforeBluetoothSettings = NowPlayingListener.snapshot();
        returningFromBluetoothSettings = playbackBeforeBluetoothSettings != null
                && playbackBeforeBluetoothSettings.playing;
        try {
            startActivity(new Intent("android.settings.CONTROLLERS_SETTINGS"));
        } catch (ActivityNotFoundException | SecurityException exception) {
            playbackBeforeBluetoothSettings = null;
            returningFromBluetoothSettings = false;
            Log.e("AppleTv.AudioOutput", "Could not open Fire TV Bluetooth settings", exception);
            Toast.makeText(this, R.string.audio_output_settings_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }
}
