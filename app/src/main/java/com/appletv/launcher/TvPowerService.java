package com.appletv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public final class TvPowerService extends AccessibilityService {
    static final String ACTION_REQUEST_POWER_DIALOG =
            "com.appletv.launcher.action.REQUEST_POWER_DIALOG";
    static final String EXTRA_RESULT_RECEIVER =
            "com.appletv.launcher.extra.POWER_DIALOG_RESULT_RECEIVER";
    private static final String TAG = "AppleTv.Power";

    private boolean receiverRegistered;
    private final BroadcastReceiver powerDialogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_REQUEST_POWER_DIALOG.equals(intent.getAction())) return;
            boolean shown = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            ResultReceiver resultReceiver;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resultReceiver = intent.getParcelableExtra(
                        EXTRA_RESULT_RECEIVER, ResultReceiver.class);
            } else {
                resultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
            }
            if (resultReceiver != null) {
                resultReceiver.send(shown ? Activity.RESULT_OK : Activity.RESULT_CANCELED, null);
            }
            if (!shown) Log.w(TAG, "The system did not open the TV power menu");
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_REQUEST_POWER_DIALOG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerDialogReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(powerDialogReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) unregisterReceiver(powerDialogReceiver);
        super.onDestroy();
    }
}
