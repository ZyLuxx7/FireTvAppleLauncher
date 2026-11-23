package com.appletv.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

final class LauncherUiSettings {
    private LauncherUiSettings() {}

    static boolean isLight(Context context) {
        String mode = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString("appearance", "auto");
        if ("light".equals(mode)) return true;
        if ("dark".equals(mode)) return false;
        int current = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return current == Configuration.UI_MODE_NIGHT_NO;
    }

    static Context applyLocale(Context base) {
        String language = base.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString("language", "");
        if (language == null || language.isEmpty()) {
            Locale.setDefault(Resources.getSystem().getConfiguration().getLocales().get(0));
            return base;
        }
        Locale locale = Locale.forLanguageTag(language);
        Locale.setDefault(locale);
        Resources resources = base.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }
}
