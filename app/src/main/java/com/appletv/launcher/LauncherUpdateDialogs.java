package com.appletv.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.example.liquidglass.GlassMaterial;
import com.example.liquidglass.LiquidGlassView;

final class LauncherUpdateDialogs {
    private static final String TERMS_ACCEPTED_VERSION_KEY = "update_terms_accepted_version_code";
    private static final String DISMISSED_RELEASE_KEY = "launcher_update_dismissed_release";

    private LauncherUpdateDialogs() {
    }

    static boolean areCurrentTermsAccepted(Context context) {
        return preferences(context).getInt(TERMS_ACCEPTED_VERSION_KEY, 0)
                >= LauncherAppVersion.code(context);
    }

    static void showVersionNotice(MainActivity activity, Runnable onAccepted, Runnable onDeclined) {
        View content = inflate(activity, R.layout.dialog_update_version);
        TextView version = content.findViewById(R.id.updateNoticeVersion);
        TextView notes = content.findViewById(R.id.updateNoticeNotes);
        TextView readTerms = content.findViewById(R.id.updateNoticeReadTerms);
        CheckBox accepted = content.findViewById(R.id.updateNoticeAccepted);
        TextView continueButton = content.findViewById(R.id.updateNoticeContinue);
        TextView exitButton = content.findViewById(R.id.updateNoticeExit);

        version.setText(activity.getString(R.string.update_notice_version_format,
                LauncherAppVersion.name(activity)));
        notes.setText(R.string.update_release_notes_v1_0_1);
        continueButton.setEnabled(false);

        Dialog dialog = showGlassDialog(activity, content, 700, false);
        accepted.setOnCheckedChangeListener((button, checked) ->
                continueButton.setEnabled(checked));
        readTerms.setOnClickListener(view -> showTermsDialog(activity));
        continueButton.setOnClickListener(view -> {
            if (!accepted.isChecked()) return;
            preferences(activity).edit()
                    .putInt(TERMS_ACCEPTED_VERSION_KEY,
                            (int) LauncherAppVersion.code(activity))
                    .apply();
            dialog.dismiss();
            onAccepted.run();
        });
        exitButton.setOnClickListener(view -> {
            dialog.dismiss();
            onDeclined.run();
            activity.finish();
        });
        readTerms.post(readTerms::requestFocus);
    }

    static void showTermsDialog(Activity activity) {
        View content = inflate(activity, R.layout.dialog_update_terms);
        TextView terms = content.findViewById(R.id.updateTermsBody);
        TextView close = content.findViewById(R.id.updateTermsClose);
        terms.setText(R.string.update_terms_body);
        Dialog dialog = showGlassDialog(activity, content, 700, true);
        close.setOnClickListener(view -> dialog.dismiss());
        close.post(close::requestFocus);
    }

    static boolean showAvailableUpdate(MainActivity activity, LauncherReleaseChecker.Release release,
            Runnable onUpdate, Runnable onDismissed) {
        SharedPreferences preferences = preferences(activity);
        if (release.tagName.equals(preferences.getString(DISMISSED_RELEASE_KEY, ""))) return false;

        View content = inflate(activity, R.layout.dialog_update_available);
        TextView version = content.findViewById(R.id.updateAvailableVersion);
        TextView later = content.findViewById(R.id.updateAvailableLater);
        TextView update = content.findViewById(R.id.updateAvailableNow);
        version.setText(activity.getString(R.string.update_available_version_format,
                release.tagName));
        Dialog dialog = showGlassDialog(activity, content, 620, true);
        dialog.setOnDismissListener(ignored -> onDismissed.run());
        later.setOnClickListener(view -> {
            preferences.edit().putString(DISMISSED_RELEASE_KEY, release.tagName).apply();
            dialog.dismiss();
        });
        update.setOnClickListener(view -> {
            dialog.dismiss();
            onUpdate.run();
        });
        update.post(update::requestFocus);
        return true;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    private static View inflate(Context context, int layoutResource) {
        return LayoutInflater.from(context).inflate(layoutResource, null, false);
    }

    private static Dialog showGlassDialog(Activity activity, View content, int maxWidthDp,
            boolean cancelable) {
        LiquidGlassView glass = new LiquidGlassView(activity);
        glass.setMaterial(GlassMaterial.REGULAR);
        glass.setCornerRadius(dp(activity, 28));
        glass.setBlurAmount(0.08f);
        glass.setEnableDynamicBackground(false);
        View backdrop = activity.findViewById(android.R.id.content);
        if (backdrop != null) glass.setBackdropSource(backdrop);
        glass.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(glass);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.62f);
        }
        dialog.show();
        if (window != null) {
            int availableWidth = activity.getResources().getDisplayMetrics().widthPixels
                    - dp(activity, 64);
            window.setLayout(Math.min(dp(activity, maxWidthDp), availableWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
