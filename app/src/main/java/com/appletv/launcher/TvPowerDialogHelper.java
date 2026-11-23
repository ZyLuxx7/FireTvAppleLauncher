package com.appletv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

final class TvPowerDialogHelper {
    private TvPowerDialogHelper() {
    }

    static void request(Activity activity, Runnable openAccessibilitySettings,
                        Runnable openFallbackSettings) {
        if (!isServiceEnabled(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.tv_power_enable_title)
                    .setMessage(R.string.tv_power_enable_message)
                    .setPositiveButton(R.string.tv_power_enable_action, (dialog, which) -> {
                        Intent accessibilitySettings =
                                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        if (accessibilitySettings.resolveActivity(activity.getPackageManager()) != null) {
                            openAccessibilitySettings.run();
                        } else {
                            openFallbackSettings.run();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] resultReceived = {false};
        Runnable timeout = () -> {
            if (!resultReceived[0] && !activity.isFinishing()) {
                Toast.makeText(activity, R.string.tv_power_menu_unavailable,
                        Toast.LENGTH_LONG).show();
            }
        };
        ResultReceiver resultReceiver = new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                resultReceived[0] = true;
                handler.removeCallbacks(timeout);
                if (resultCode != Activity.RESULT_OK && !activity.isFinishing()) {
                    Toast.makeText(activity, R.string.tv_power_menu_unavailable,
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        Intent request = new Intent(TvPowerService.ACTION_REQUEST_POWER_DIALOG)
                .setPackage(activity.getPackageName())
                .putExtra(TvPowerService.EXTRA_RESULT_RECEIVER, resultReceiver);
        activity.sendBroadcast(request);
        handler.postDelayed(timeout, 2000);
    }

    private static boolean isServiceEnabled(Activity activity) {
        String enabledServices = Settings.Secure.getString(activity.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        ComponentName target = new ComponentName(activity, TvPowerService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (target.equals(enabled)) return true;
        }
        return false;
    }
}
