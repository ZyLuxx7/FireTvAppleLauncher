package com.appletv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Routes Home keys directly when accessible and redirects OEM launchers that consume HOME before
 * accessibility key filtering. Other keys pass through untouched unless explicitly mapped below.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.appletv.launcher/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {
    private static final long HOME_DOUBLE_PRESS_TIMEOUT_MS = 1200L;
    private static final String LOG_TAG = "AppleTv.HomeKey";
    private static final String AMAZON_HOME_PACKAGE = "com.amazon.tv.launcher";
    private static long lastHomeKeyUp;
    private static long lastHomeWindowTransition;
    private boolean consumeHomeSequence;
    private boolean homeKeyHandled;
    private String homePackageName;
    private String lastWindowPackageName;

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;

        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo home = getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        homePackageName = home == null || home.activityInfo == null ? null : home.activityInfo.packageName;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.isCanceled()) {
                consumeHomeSequence = false;
                boolean handled = homeKeyHandled;
                homeKeyHandled = false;
                return handled;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() > 0) return homeKeyHandled;
                long now = SystemClock.uptimeMillis();
                consumeHomeSequence = lastHomeKeyUp > 0
                        && now - lastHomeKeyUp < HOME_DOUBLE_PRESS_TIMEOUT_MS;
                if (consumeHomeSequence) {
                    homeKeyHandled = true;
                    return true;
                }
                homeKeyHandled = startAppleLauncher();
                return homeKeyHandled;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (consumeHomeSequence) {
                    lastHomeKeyUp = 0;
                    consumeHomeSequence = false;
                    homeKeyHandled = false;
                    openRecentApps();
                    return true;
                }
                lastHomeKeyUp = SystemClock.uptimeMillis();
                boolean handled = homeKeyHandled;
                homeKeyHandled = false;
                return handled;
            }
            return homeKeyHandled;
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (!MainActivity.routeMediaMenuKey()) {
                    Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                            : new Intent(Settings.ACTION_SETTINGS);
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_APP_SWITCH
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_2
                || event.getScanCode() == 150 || event.getScanCode() == 580) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (!MainActivity.routeMediaMenuKey()) openRecentApps();
            }
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    private void openRecentApps() {
        lastHomeWindowTransition = 0;
        Intent open = new Intent(this, RecentAppsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(open);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(LOG_TAG, "Unable to open the Apple app switcher", exception);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;

        String currentPackage = packageName.toString();
        String previousPackage = lastWindowPackageName;
        lastWindowPackageName = currentPackage;
        boolean amazonHome = AMAZON_HOME_PACKAGE.equals(currentPackage);
        if (getPackageName().equals(currentPackage) || currentPackage.equals(previousPackage)
                || (!amazonHome && (homePackageName == null || !homePackageName.equals(currentPackage)))) {
            return;
        }
        if (amazonHome && isHomeRedirectHelperEnabled()) {
            lastHomeWindowTransition = 0;
            return;
        }

        Log.i(LOG_TAG, "Redirecting Home transition from " + currentPackage);
        long now = SystemClock.uptimeMillis();
        boolean doubleHome = lastHomeWindowTransition > 0
                && now - lastHomeWindowTransition < HOME_DOUBLE_PRESS_TIMEOUT_MS;
        lastHomeWindowTransition = doubleHome ? 0 : now;
        if (doubleHome) {
            openRecentApps();
            return;
        }

        Intent home = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .putExtra(MainActivity.EXTRA_HOME_ACCESSIBILITY_REDIRECT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(home);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(LOG_TAG, "Unable to redirect the Home button to Apple Launcher", exception);
        }
    }

    private boolean isHomeRedirectHelperEnabled() {
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        ComponentName redirect = new ComponentName("de.gabri.firetvhomehelper",
                "de.gabri.firetvhomehelper.AmazonHomeAccessibilityService");
        for (String flattened : enabledServices.split(":")) {
            if (redirect.equals(ComponentName.unflattenFromString(flattened))) return true;
        }
        return false;
    }

    private boolean startAppleLauncher() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(home);
            Log.i(LOG_TAG, "Handled Home key directly; keeping Fire OS Home out of the foreground");
            return true;
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(LOG_TAG, "Unable to open Apple Launcher for the Home key", exception);
            return false;
        }
    }

    @Override
    public void onInterrupt() {
    }

}
