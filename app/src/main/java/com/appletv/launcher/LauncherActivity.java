package com.appletv.launcher;

import android.app.Activity;
import android.content.Context;

/** Applies the launcher's optional language override consistently on Android 9 through current Android. */
public abstract class LauncherActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LauncherUiSettings.applyLocale(newBase));
    }
}
