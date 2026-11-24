package com.appletv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LauncherReleaseChecker {
    private static final String TAG = "LauncherReleaseChecker";
    private static final String API_URL =
            "https://api.github.com/repos/ZyLuxx7/FireTvAppleLauncher/releases?per_page=10";
    private static final String LAST_CHECK_KEY = "launcher_release_last_check_ms";
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$", Pattern.CASE_INSENSITIVE);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "launcher-release-check");
        thread.setDaemon(true);
        return thread;
    });

    interface Callback {
        void onComplete(Release release);
    }

    private LauncherReleaseChecker() {
    }

    static void checkIfDue(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = appContext.getSharedPreferences(
                Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastCheck = preferences.getLong(LAST_CHECK_KEY, 0);
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            postToMain(() -> callback.onComplete(null));
            return;
        }
        preferences.edit().putLong(LAST_CHECK_KEY, now).apply();
        EXECUTOR.execute(() -> {
            Release latest = null;
            try {
                latest = findLatestRelease();
                if (latest != null
                        && !isNewerVersion(LauncherAppVersion.name(appContext), latest.tagName)) {
                    latest = null;
                }
            } catch (IOException | JSONException exception) {
                Log.w(TAG, "Could not check GitHub for launcher updates", exception);
            }
            Release result = latest;
            postToMain(() -> callback.onComplete(result));
        });
    }

    private static void postToMain(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    static boolean isNewerVersion(String currentVersion, String candidateVersion) {
        Version current = parseVersion(currentVersion);
        Version candidate = parseVersion(candidateVersion);
        return current != null && candidate != null && compare(candidate, current) > 0;
    }

    private static Release findLatestRelease() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "FireTvAppleLauncher");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub release check returned HTTP " + responseCode + ".");
            }
            JSONArray releases = new JSONArray(readResponse(connection.getInputStream()));
            Release latest = null;
            Version latestVersion = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject item = releases.optJSONObject(i);
                if (item == null || item.optBoolean("draft", false)) continue;
                String tagName = item.optString("tag_name", "").trim();
                Version version = parseVersion(tagName);
                String apkUrl = launcherAssetUrl(item.optJSONArray("assets"));
                if (version == null || apkUrl == null) continue;
                if (latestVersion == null || compare(version, latestVersion) > 0) {
                    latestVersion = version;
                    latest = new Release(tagName,
                            item.optString("name", tagName),
                            item.optString("html_url", ""),
                            apkUrl,
                            item.optString("body", ""));
                }
            }
            return latest;
        } finally {
            connection.disconnect();
        }
    }

    private static String launcherAssetUrl(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !"AppleTVLauncher.apk".equals(
                    asset.optString("name", ""))) continue;
            String url = asset.optString("browser_download_url", "").trim();
            if (!url.startsWith("https://github.com/ZyLuxx7/FireTvAppleLauncher/")) return null;
            return url;
        }
        return null;
    }

    private static String readResponse(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new IOException("GitHub release response exceeded the size limit.");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Version parseVersion(String value) {
        if (value == null) return null;
        Matcher matcher = VERSION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            return new Version(Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)), Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int compare(Version left, Version right) {
        if (left.major != right.major) return Long.compare(left.major, right.major);
        if (left.minor != right.minor) return Long.compare(left.minor, right.minor);
        return Long.compare(left.patch, right.patch);
    }

    static final class Release {
        final String tagName;
        final String name;
        final String pageUrl;
        final String apkUrl;
        final String body;

        Release(String tagName, String name, String pageUrl, String apkUrl, String body) {
            this.tagName = tagName;
            this.name = name;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.body = body;
        }
    }

    private static final class Version {
        final long major;
        final long minor;
        final long patch;

        Version(long major, long minor, long patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }
    }
}
