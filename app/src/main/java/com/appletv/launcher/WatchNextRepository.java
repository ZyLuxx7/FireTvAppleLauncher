package com.appletv.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads only standard Android TV Watch Next rows explicitly published by the focused app. */
final class WatchNextRepository {
    private static final String TAG = "AppleTv.WatchNext";
    private static final String ANDROID_PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA";
    private static final String AMAZON_PERMISSION = "com.amazon.providers.tv.permission.READ_EPG_DATA";
    private static final Uri AMAZON_WATCH_NEXT =
            Uri.parse("content://amazon.media.tv/watch_next_program");
    private static final int MAX_PROGRAMS = 8;
    private static final AtomicBoolean ANDROID_PERMISSION_WARNING = new AtomicBoolean();
    private static final AtomicBoolean AMAZON_PERMISSION_WARNING = new AtomicBoolean();

    private WatchNextRepository() {}

    static boolean hasReadPermission(Context context) {
        return context.checkSelfPermission(ANDROID_PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(AMAZON_PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static List<Program> load(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return Collections.emptyList();
        if (context.checkSelfPermission(ANDROID_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            List<Program> programs = query(context.getContentResolver(),
                    TvContract.WatchNextPrograms.CONTENT_URI, packageName, ANDROID_PERMISSION_WARNING);
            if (!programs.isEmpty()) return programs;
        } else if (ANDROID_PERMISSION_WARNING.compareAndSet(false, true)) {
            Log.w(TAG, "Android TV Watch Next access is not granted");
        }
        if (context.checkSelfPermission(AMAZON_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return query(context.getContentResolver(), AMAZON_WATCH_NEXT, packageName, AMAZON_PERMISSION_WARNING);
        }
        if (AMAZON_PERMISSION_WARNING.compareAndSet(false, true)) {
            Log.w(TAG, "Fire TV Watch Next access is not granted");
        }
        return Collections.emptyList();
    }

    private static List<Program> query(ContentResolver resolver, Uri uri, String packageName,
                                       AtomicBoolean accessWarning) {
        String[] projection = {
                TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
        };
        List<Program> programs = new ArrayList<>();
        try (Cursor cursor = resolver.query(uri, projection,
                TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME + " = ?",
                new String[]{packageName},
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cursor == null) return programs;
            int packageColumn = cursor.getColumnIndex(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME);
            int titleColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE);
            int episodeTitleColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
            int seasonColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
            int episodeColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
            int posterColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
            int intentColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
            int positionColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
            int durationColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
            int typeColumn = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
            while (cursor.moveToNext() && programs.size() < MAX_PROGRAMS) {
                String owner = value(cursor, packageColumn);
                if (!packageName.equals(owner)) continue;
                long type = number(cursor, typeColumn, -1);
                if (type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        && type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) continue;
                String title = value(cursor, titleColumn);
                if (title.isEmpty()) continue;
                String episodeTitle = value(cursor, episodeTitleColumn);
                String season = value(cursor, seasonColumn);
                String episode = value(cursor, episodeColumn);
                String subtitle = episodeTitle;
                if (subtitle.isEmpty() && (!season.isEmpty() || !episode.isEmpty())) {
                    subtitle = season.isEmpty() ? episode
                            : episode.isEmpty() ? season : season + " \u00B7 " + episode;
                }
                Uri poster = parseUri(value(cursor, posterColumn));
                Uri intent = parseUri(value(cursor, intentColumn));
                long duration = Math.max(0, number(cursor, durationColumn, 0));
                long position = Math.max(0, number(cursor, positionColumn, 0));
                int progress = duration <= 0 ? 0 : (int) Math.min(100,
                        Math.max(0, (position * 100L) / duration));
                long remainingMinutes = duration <= position ? 0
                        : Math.max(1, (duration - position + 59_999L) / 60_000L);
                programs.add(new Program(title, subtitle, poster, intent, progress, remainingMinutes));
            }
        } catch (SecurityException e) {
            if (accessWarning.compareAndSet(false, true)) {
                Log.w(TAG, "Watch Next access is not granted for " + uri.getAuthority(), e);
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Watch Next provider does not support the standard program schema: "
                    + uri.getAuthority(), e);
        }
        return programs;
    }

    private static String value(Cursor cursor, int column) {
        return column < 0 || cursor.isNull(column) ? "" : cursor.getString(column);
    }

    private static long number(Cursor cursor, int column, long fallback) {
        return column < 0 || cursor.isNull(column) ? fallback : cursor.getLong(column);
    }

    private static Uri parseUri(String value) {
        return value.isEmpty() ? null : Uri.parse(value);
    }

    static final class Program {
        final String title;
        final String subtitle;
        final Uri poster;
        final Uri intent;
        final int progress;
        final long remainingMinutes;

        Program(String title, String subtitle, Uri poster, Uri intent, int progress, long remainingMinutes) {
            this.title = title;
            this.subtitle = subtitle;
            this.poster = poster;
            this.intent = intent;
            this.progress = progress;
            this.remainingMinutes = remainingMinutes;
        }
    }
}
