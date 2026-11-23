package com.appletv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

final class HomeWallpaperCatalog {
    static final String BANNER = "banner";
    static final String SCENIC = "scenic";

    private static final String[] IDS = {BANNER, SCENIC, "ocean", "sunset", "aurora"};

    private HomeWallpaperCatalog() {
    }

    static String[] ids() {
        return IDS.clone();
    }

    static String selectedId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String saved = preferences.getString("home_wallpaper_id", null);
        if (saved == null) {
            return preferences.getBoolean("home_wallpaper_enabled", false) ? SCENIC : BANNER;
        }
        for (String id : IDS) {
            if (id.equals(saved)) return saved;
        }
        return BANNER;
    }

    static boolean isBanner(String id) {
        return BANNER.equals(id);
    }

    static boolean usesForeground(String id) {
        return SCENIC.equals(id);
    }

    static int drawable(String id) {
        switch (id) {
            case "ocean":
                return R.drawable.home_wallpaper_ocean;
            case "sunset":
                return R.drawable.home_wallpaper_sunset;
            case "aurora":
                return R.drawable.home_wallpaper_aurora;
            case SCENIC:
                return R.drawable.home_wallpaper;
            default:
                return R.drawable.home_backdrop;
        }
    }

    static int previewDrawable(String id) {
        return drawable(id);
    }

    static int label(String id) {
        switch (id) {
            case "ocean":
                return R.string.wallpaper_ocean;
            case "sunset":
                return R.string.wallpaper_sunset;
            case "aurora":
                return R.string.wallpaper_aurora;
            case SCENIC:
                return R.string.wallpaper_scenic;
            default:
                return R.string.wallpaper_banner;
        }
    }
}
