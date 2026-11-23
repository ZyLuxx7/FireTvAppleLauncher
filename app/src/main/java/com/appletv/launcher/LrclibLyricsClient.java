package com.appletv.launcher;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LrclibLyricsClient {
    private static final String API_BASE = "https://lrclib.net/api";
    private static final String API_GET = API_BASE + "/get";
    private static final String API_SEARCH = API_BASE + "/search";
    private static final String USER_AGENT =
            "AppleTVLauncher/1.0.0-firetv (com.appletv.launcher.firetv)";
    private static final long SEARCH_FALLBACK_DELAY_MS = 250L;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\[(\\d+):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
    private static final Pattern TRACK_VERSION_SUFFIX = Pattern.compile(
            "\\s*(?:[-–—:]\\s*|\\(\\s*)(?:\\d{4}\\s*)?"
                    + "(?:remaster(?:ed)?|master(?:ed)?)"
                    + "(?:\\s*\\d{4})?\\)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    static final class LyricLine {
        final long positionMs;
        final String text;

        LyricLine(long positionMs, String text) {
            this.positionMs = positionMs;
            this.text = text;
        }
    }

    static final class Lyrics {
        final List<LyricLine> syncedLines;
        final List<String> syncedTextLines;
        final boolean instrumental;

        Lyrics(List<LyricLine> syncedLines, boolean instrumental) {
            this.syncedLines = syncedLines;
            this.instrumental = instrumental;
            List<String> textLines = new ArrayList<>(syncedLines.size());
            for (LyricLine line : syncedLines) textLines.add(line.text);
            this.syncedTextLines = Collections.unmodifiableList(textLines);
        }

        boolean isDisplayable() {
            return instrumental || !syncedLines.isEmpty();
        }
    }

    static final class SearchCandidate {
        final String trackName;
        final String artistName;
        final String albumName;
        final long durationSeconds;
        final Lyrics lyrics;

        SearchCandidate(String trackName, String artistName, String albumName,
                        long durationSeconds, Lyrics lyrics) {
            this.trackName = trackName;
            this.artistName = artistName;
            this.albumName = albumName;
            this.durationSeconds = durationSeconds;
            this.lyrics = lyrics;
        }
    }

    Lyrics lookup(String title, String artist, String album, long durationMs) throws IOException {
        if (isBlank(title) || isBlank(artist)) {
            throw new IllegalArgumentException("Track title and artist are required");
        }

        Uri.Builder uri = Uri.parse(API_GET).buildUpon()
                .appendQueryParameter("track_name", title.trim())
                .appendQueryParameter("artist_name", artist.trim());
        if (!isBlank(album)) uri.appendQueryParameter("album_name", album.trim());
        long durationSeconds = Math.max(0, (durationMs + 500L) / 1000L);
        if (durationSeconds >= 1 && durationSeconds <= 3600) {
            uri.appendQueryParameter("duration", Long.toString(durationSeconds));
        }

        Lyrics exactMatch = parseLyricsRecord(getResponse(uri.build().toString()));
        if (exactMatch != null && exactMatch.isDisplayable()) return exactMatch;

        waitBetweenRequests();
        return search(title, artist, album, durationSeconds);
    }

    private Lyrics search(String title, String artist, String album, long durationSeconds)
            throws IOException {
        String titleForSearch = stripTrackVersionSuffix(title);
        Uri.Builder uri = Uri.parse(API_SEARCH).buildUpon()
                .appendQueryParameter("track_name", titleForSearch)
                .appendQueryParameter("artist_name", artist.trim());
        if (!isBlank(album)) uri.appendQueryParameter("album_name", album.trim());
        String response = getResponse(uri.build().toString());
        if (response == null) return null;

        JSONArray records;
        try {
            records = new JSONArray(response);
        } catch (JSONException exception) {
            throw new IOException("LRCLIB returned an invalid search response", exception);
        }

        List<SearchCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            candidates.add(new SearchCandidate(
                    record.optString("trackName", record.optString("name", "")),
                    record.optString("artistName", ""),
                    record.optString("albumName", ""),
                    Math.round(record.optDouble("duration", 0)),
                    parseLyricsRecord(record)));
        }
        return selectBestMatch(candidates, titleForSearch, artist, album, durationSeconds);
    }

    static Lyrics selectBestMatch(List<SearchCandidate> candidates, String title, String artist,
                                  String album, long durationSeconds) {
        String normalizedTitle = normalize(stripTrackVersionSuffix(title));
        String normalizedArtist = normalize(artist);
        String normalizedAlbum = normalize(album);
        if (normalizedTitle.isEmpty() || normalizedArtist.isEmpty()) return null;

        long bestScore = Long.MAX_VALUE;
        Lyrics bestMatch = null;
        for (int i = 0; i < candidates.size(); i++) {
            SearchCandidate candidate = candidates.get(i);
            if (!normalizedTitle.equals(normalize(stripTrackVersionSuffix(candidate.trackName)))
                    || !normalizedArtist.equals(normalize(candidate.artistName))
                    || candidate.lyrics == null || !candidate.lyrics.isDisplayable()) {
                continue;
            }

            long durationDifference = durationSeconds > 0 && candidate.durationSeconds > 0
                    ? Math.abs(durationSeconds - candidate.durationSeconds) : 0;
            long allowedDifference = Math.max(12L, Math.round(durationSeconds * 0.04d));
            if (durationDifference > allowedDifference) continue;

            long score = candidate.lyrics.syncedLines.isEmpty() ? 1_000_000L : 0L;
            if (!normalizedAlbum.isEmpty()
                    && normalizedAlbum.equals(normalize(candidate.albumName))) {
                score -= 100_000L;
            }
            score += durationDifference * 100L + i;
            if (score < bestScore) {
                bestScore = score;
                bestMatch = candidate.lyrics;
            }
        }
        return bestMatch;
    }

    private static Lyrics parseLyricsRecord(String response) throws IOException {
        if (response == null) return null;
        try {
            return parseLyricsRecord(new JSONObject(response));
        } catch (JSONException exception) {
            throw new IOException("LRCLIB returned an invalid lyrics response", exception);
        }
    }

    private static Lyrics parseLyricsRecord(JSONObject record) {
        if (record == null) return null;
        List<LyricLine> syncedLines = parseSyncedLyrics(record.optString("syncedLyrics", ""));
        return new Lyrics(syncedLines, record.optBoolean("instrumental", false));
    }

    private static String getResponse(String requestUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(6_000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Lrclib-Client", "AppleTVLauncher/1.0.0-firetv");

            int statusCode = connection.getResponseCode();
            if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (statusCode == 429 || statusCode == HttpURLConnection.HTTP_UNAVAILABLE) {
                throw new ApiException(statusCode, retryAfterMillis(connection));
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new ApiException(statusCode, 0);
            }
            return readResponse(connection.getInputStream());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void waitBetweenRequests() throws IOException {
        try {
            Thread.sleep(SEARCH_FALLBACK_DELAY_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("LRCLIB lookup was interrupted", exception);
        }
    }

    private static String normalize(String value) {
        if (isBlank(value)) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char character = decomposed.charAt(i);
            if (Character.isLetterOrDigit(character)) normalized.append(character);
        }
        return normalized.toString();
    }

    private static String stripTrackVersionSuffix(String title) {
        if (isBlank(title)) return "";
        Matcher suffix = TRACK_VERSION_SUFFIX.matcher(title.trim());
        return suffix.find() ? title.substring(0, suffix.start()).trim() : title.trim();
    }

    static List<LyricLine> parseSyncedLyrics(String lyrics) {
        if (isBlank(lyrics)) return Collections.emptyList();

        List<LyricLine> lines = new ArrayList<>();
        for (String line : lyrics.split("\\r?\\n")) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(line);
            List<Long> timestamps = new ArrayList<>();
            int contentStart = -1;
            while (matcher.find()) {
                try {
                    int minutes = Integer.parseInt(matcher.group(1));
                    int seconds = Integer.parseInt(matcher.group(2));
                    if (seconds > 59) continue;
                    String fraction = matcher.group(3);
                    int millis = fraction == null ? 0
                            : Integer.parseInt((fraction + "00").substring(0, 3));
                    timestamps.add(minutes * 60_000L + seconds * 1_000L + millis);
                    contentStart = matcher.end();
                } catch (NumberFormatException ignored) {
                    timestamps.clear();
                    contentStart = -1;
                    break;
                }
            }
            if (contentStart < 0) continue;

            String text = line.substring(contentStart).trim();
            for (long timestamp : timestamps) lines.add(new LyricLine(timestamp, text));
        }
        lines.sort(Comparator.comparingLong(line -> line.positionMs));
        return Collections.unmodifiableList(lines);
    }

    static String lineAt(List<LyricLine> lines, long positionMs) {
        int index = lineIndexAt(lines, positionMs);
        return index < 0 ? "" : lines.get(index).text;
    }

    static int lineIndexAt(List<LyricLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int low = 0;
        int high = lines.size() - 1;
        int active = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).positionMs <= positionMs) {
                active = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return active;
    }

    private static long retryAfterMillis(HttpURLConnection connection) {
        String header = connection.getHeaderField("Retry-After");
        if (header != null) {
            try {
                long seconds = Long.parseLong(header.trim());
                if (seconds > 0) return TimeUnit.SECONDS.toMillis(seconds);
            } catch (NumberFormatException ignored) {
                // Use a conservative cooldown when the server's value is malformed.
            }
        }
        return TimeUnit.MINUTES.toMillis(1);
    }

    private static String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = input) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("LRCLIB response exceeded the size limit");
                }
                output.write(buffer, 0, count);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static final class ApiException extends IOException {
        final int statusCode;
        final long retryAfterMs;

        ApiException(int statusCode, long retryAfterMs) {
            super("LRCLIB request failed (HTTP " + statusCode + ")");
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
        }
    }
}
