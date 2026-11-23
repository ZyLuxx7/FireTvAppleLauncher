package com.appletv.launcher;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.Locale;

final class MusixmatchLyricsClient {
    private static final String API_BASE = "https://api.musixmatch.com/ws/1.1/";
    private static final String TRACKING_HOST = "tracking.musixmatch.com";
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    Lyrics lookup(String title, String artist, String apiKey) throws IOException {
        if (title == null || title.trim().isEmpty()
                || artist == null || artist.trim().isEmpty()) {
            return Lyrics.unavailable(null);
        }

        JSONObject searchMessage = getMessage("track.search", apiKey,
                "q_track", title.trim(),
                "q_artist", artist.trim(),
                "f_has_lyrics", "1",
                "page_size", "10");
        JSONArray tracks = searchMessage.optJSONObject("body") == null
                ? null : searchMessage.optJSONObject("body").optJSONArray("track_list");
        JSONObject selectedTrack = findMatchingTrack(tracks, title, artist);
        if (selectedTrack == null) return Lyrics.unavailable(null);

        long commonTrackId = selectedTrack.optLong("commontrack_id", 0);
        if (commonTrackId <= 0) return Lyrics.unavailable(null);
        JSONObject lyricsMessage = getMessage("track.lyrics.get", apiKey,
                "commontrack_id", Long.toString(commonTrackId));
        JSONObject lyrics = lyricsMessage.optJSONObject("body") == null
                ? null : lyricsMessage.optJSONObject("body").optJSONObject("lyrics");
        if (lyrics == null) return Lyrics.unavailable(null);

        String copyright = lyrics.optString("lyrics_copyright", "").trim();
        String text = lyrics.optString("lyrics_body", "").trim();
        String pixelTrackingUrl = lyrics.optString("pixel_tracking_url", "").trim();
        if (text.isEmpty() || copyright.isEmpty() || !isValidTrackingUrl(pixelTrackingUrl)) {
            return Lyrics.unavailable(copyright);
        }
        return new Lyrics(text, copyright, pixelTrackingUrl);
    }

    void trackView(Lyrics lyrics) throws IOException {
        if (lyrics == null || !lyrics.isDisplayable()
                || !isValidTrackingUrl(lyrics.pixelTrackingUrl)) {
            throw new IOException("Musixmatch lyrics are missing required view tracking");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(lyrics.pixelTrackingUrl).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new ApiException(statusCode);
            }
            InputStream input = connection.getInputStream();
            try {
                byte[] buffer = new byte[1024];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 16_384) {
                        throw new IOException("Musixmatch view tracking response was too large");
                    }
                }
            } finally {
                input.close();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject getMessage(String operation, String apiKey, String... parameters)
            throws IOException {
        Uri.Builder uri = Uri.parse(API_BASE + operation).buildUpon()
                .appendQueryParameter("apikey", apiKey)
                .appendQueryParameter("format", "json");
        for (int i = 0; i + 1 < parameters.length; i += 2) {
            uri.appendQueryParameter(parameters[i], parameters[i + 1]);
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.build().toString()).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                InputStream error = connection.getErrorStream();
                if (error != null) error.close();
                throw new ApiException(statusCode);
            }
            String response = readResponse(connection.getInputStream());
            JSONObject message = new JSONObject(response).optJSONObject("message");
            if (message == null) throw new IOException("Musixmatch returned an invalid response");
            JSONObject header = message.optJSONObject("header");
            int apiStatus = header == null ? -1 : header.optInt("status_code", -1);
            if (apiStatus != 200) throw new ApiException(apiStatus);
            return message;
        } catch (JSONException exception) {
            throw new IOException("Musixmatch returned an invalid response", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject findMatchingTrack(JSONArray tracks, String title, String artist) {
        if (tracks == null) return null;
        String normalizedTitle = normalize(title);
        String normalizedArtist = normalize(artist);
        if (normalizedTitle.isEmpty() || normalizedArtist.isEmpty()) return null;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject item = tracks.optJSONObject(i);
            JSONObject track = item == null ? null : item.optJSONObject("track");
            if (track == null) continue;
            String trackTitle = normalize(track.optString("track_name", ""));
            String trackArtist = normalize(track.optString("artist_name", ""));
            if (normalizedTitle.equals(trackTitle)
                    && (normalizedArtist.equals(trackArtist)
                    || trackArtist.contains(normalizedArtist))) {
                return track;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static boolean isValidTrackingUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && TRACKING_HOST.equalsIgnoreCase(url.getHost())
                    && (url.getPort() == -1 || url.getPort() == 443);
        } catch (IOException exception) {
            return false;
        }
    }

    private static String readResponse(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Musixmatch response was too large");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static final class Lyrics {
        final String text;
        final String copyright;
        final String pixelTrackingUrl;

        Lyrics(String text, String copyright, String pixelTrackingUrl) {
            this.text = text;
            this.copyright = copyright;
            this.pixelTrackingUrl = pixelTrackingUrl;
        }

        static Lyrics unavailable(String copyright) {
            return new Lyrics(null, copyright, null);
        }

        boolean isDisplayable() {
            return text != null && !text.isEmpty()
                    && copyright != null && !copyright.isEmpty()
                    && pixelTrackingUrl != null && !pixelTrackingUrl.isEmpty();
        }
    }

    static final class ApiException extends IOException {
        final int statusCode;

        ApiException(int statusCode) {
            super("Musixmatch request failed (HTTP " + statusCode + ")");
            this.statusCode = statusCode;
        }
    }
}
