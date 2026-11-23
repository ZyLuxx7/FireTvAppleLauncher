package com.appletv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

final class NowPlayingAccessSettings {
    private static final String TAG = "NowPlayingAccess";

    private NowPlayingAccessSettings() { }

    static void open(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.w(TAG, "Notification access settings are unavailable", exception);
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.now_playing_title)
                    .setMessage(R.string.now_playing_settings_unavailable)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }
}
