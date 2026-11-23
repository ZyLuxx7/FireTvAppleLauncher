package com.appletv.launcher;

import android.content.SharedPreferences;

final class LyricsProvider {
    static final String PREFERENCE_KEY = "lyrics_provider";
    static final String LRCLIB = "lrclib";
    static final String MUSIXMATCH = "musixmatch";
    static final String GENIUS = "genius";
    static final String PLAYER = "player";

    private LyricsProvider() {
    }

    static String selected(SharedPreferences preferences) {
        String value = preferences.getString(PREFERENCE_KEY, LRCLIB);
        if (LRCLIB.equals(value) || MUSIXMATCH.equals(value)
                || GENIUS.equals(value) || PLAYER.equals(value)) return value;
        return LRCLIB;
    }
}
