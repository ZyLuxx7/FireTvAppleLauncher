package com.appletv.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

final class LauncherAppVersion {
    private LauncherAppVersion() {
    }

    static String name(Context context) {
        String versionName = packageInfo(context).versionName;
        if (versionName == null || versionName.trim().isEmpty()) {
            throw new IllegalStateException("The launcher package has no version name.");
        }
        return versionName;
    }

    static long code(Context context) {
        return packageInfo(context).getLongVersionCode();
    }

    private static PackageInfo packageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IllegalStateException("The installed launcher package could not be found.",
                    exception);
        }
    }
}
