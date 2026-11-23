package com.appletv.launcher;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;

final class AppStoreLauncher {
    private static final String[] STORE_PACKAGES = {
            "com.amazon.venezia", "com.amazon.tv.store"
    };

    private AppStoreLauncher() {
    }

    static Intent launchIntent(Context context) {
        Intent amazonStore = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(
                        "com.amazon.venezia",
                        "com.amazon.venezia.grid.AppsGridLauncherActivity"));
        if (context.getPackageManager().resolveActivity(amazonStore, 0) != null) {
            return amazonStore;
        }

        for (String packageName : STORE_PACKAGES) {
            Intent launch = context.getPackageManager()
                    .getLeanbackLaunchIntentForPackage(packageName);
            if (launch == null) {
                launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            }
            if (launch != null) return launch;
        }
        return null;
    }
}
