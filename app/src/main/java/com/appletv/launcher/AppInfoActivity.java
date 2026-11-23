package com.appletv.launcher;

import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class AppInfoActivity extends LauncherActivity {
    static final String EXTRA_PACKAGE = "package_name";

    private String packageName;
    private boolean light;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (packageName == null || packageName.trim().isEmpty()) {
            finish();
            return;
        }
        light = LauncherUiSettings.isLight(this);
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo application = pm.getApplicationInfo(packageName, 0);
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            setContentView(buildContent(pm, application, info));
        } catch (PackageManager.NameNotFoundException exception) {
            Toast.makeText(this, R.string.app_info_unavailable, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private View buildContent(PackageManager pm, ApplicationInfo application, PackageInfo info) {
        int primary = light ? 0xFF1C1C1E : Color.WHITE;
        int secondary = light ? 0xFF6E6E73 : 0xFFB8B8BE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(48), dp(30), dp(48), dp(30));
        root.setBackgroundResource(light ? R.drawable.settings_backdrop_light
                : R.drawable.settings_backdrop);

        TextView title = new TextView(this);
        title.setText(R.string.app_info);
        title.setTextColor(primary);
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(22);
        root.addView(scroll, scrollParams);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(30), dp(26), dp(30), dp(26));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(light ? 0xFFFFFFFF : 0xE61C1D21);
        panelBackground.setCornerRadius(dp(24));
        panelBackground.setStroke(dp(1), light ? 0xFFD8D8DE : 0x33FFFFFF);
        panel.setBackground(panelBackground);
        scroll.addView(panel);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(application.loadIcon(pm));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        panel.addView(icon, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView appName = new TextView(this);
        appName.setText(pm.getApplicationLabel(application));
        appName.setTextColor(primary);
        appName.setTextSize(24);
        appName.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        appName.setGravity(Gravity.CENTER);
        appName.setPadding(0, dp(16), 0, dp(8));
        panel.addView(appName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addDetail(panel, getString(R.string.app_info_version, info.versionName == null
                ? getString(R.string.app_info_unknown) : info.versionName), secondary);
        addDetail(panel, getString(R.string.app_info_package, packageName), secondary);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(22);
        panel.addView(actions, actionParams);
        addAction(actions, R.string.app_info_open, this::openApp);
        addAction(actions, R.string.app_info_stop, this::stopApp);
        addAction(actions, R.string.app_info_uninstall, this::uninstallApp);
        return root;
    }

    private void addDetail(LinearLayout panel, String text, int color) {
        TextView detail = new TextView(this);
        detail.setText(text);
        detail.setTextColor(color);
        detail.setTextSize(15);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(10), dp(6), dp(10), dp(6));
        panel.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addAction(LinearLayout parent, int label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(getResources().getColorStateList(light
                ? R.color.pill_text_light : R.color.pill_text_dark));
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackgroundResource(light ? R.drawable.settings_choice_bg_light
                : R.drawable.settings_choice_bg);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1f);
        params.setMargins(dp(5), 0, dp(5), 0);
        parent.addView(button, params);
    }

    private void openApp() {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null) launch = pm.getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Toast.makeText(this, R.string.app_info_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(launch);
        overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        finish();
    }

    private void stopApp() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) {
            Toast.makeText(this, R.string.app_info_stop_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        manager.killBackgroundProcesses(packageName);
        Toast.makeText(this, R.string.app_info_stopped, Toast.LENGTH_SHORT).show();
    }

    private void uninstallApp() {
        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
        try {
            startActivity(uninstall);
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.app_info_uninstall_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
