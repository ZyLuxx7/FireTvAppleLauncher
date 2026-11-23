package com.appletv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SpotifyConnectClient {
    static final String REDIRECT_URI = "com.appletv.launcher.firetv://spotify-callback";
    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";
    private static final String PLAYER_ENDPOINT = "https://api.spotify.com/v1/me/player";
    private static final String DEVICES_ENDPOINT = "https://api.spotify.com/v1/me/player/devices";
    private static final String PLAYLISTS_ENDPOINT = "https://api.spotify.com/v1/me/playlists";
    private static final String PLAYLIST_ENDPOINT = "https://api.spotify.com/v1/playlists/%s";
    private static final String PLAYLIST_ITEMS_ENDPOINT =
            "https://api.spotify.com/v1/playlists/%s/items";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state "
            + "playlist-read-private playlist-read-collaborative "
            + "playlist-modify-private playlist-modify-public";
    private static final int PLAYLIST_PAGE_SIZE = 20;
    private static final int PLAYLIST_ITEMS_PAGE_SIZE = 100;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_ARTWORK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ARTWORK_DIMENSION = 720;

    private SpotifyConnectClient() {
    }

    static String createCodeVerifier() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    static String createState() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    static String createCodeChallenge(String verifier) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    static boolean stateMatches(String expected, String actual) {
        return expected != null && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8));
    }

    static Uri authorizationUri(String clientId, String state, String codeChallenge) {
        return Uri.parse(AUTHORIZATION_ENDPOINT).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("state", state)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", codeChallenge)
                .build();
    }

    static Token exchangeAuthorizationCode(
            String clientId, String code, String verifier) throws IOException, JSONException {
        JSONObject response = postForm(TOKEN_ENDPOINT,
                "grant_type", "authorization_code",
                "client_id", clientId,
                "code", code,
                "redirect_uri", REDIRECT_URI,
                "code_verifier", verifier);
        return tokenFromResponse(response, null);
    }

    static Token refreshAccessToken(
            String clientId, String refreshToken) throws IOException, JSONException {
        JSONObject response = postForm(TOKEN_ENDPOINT,
                "grant_type", "refresh_token",
                "client_id", clientId,
                "refresh_token", refreshToken);
        return tokenFromResponse(response, refreshToken);
    }

    static List<Device> getAvailableDevices(String accessToken)
            throws IOException, JSONException {
        JSONObject response = new JSONObject(request(
                DEVICES_ENDPOINT, "GET", accessToken, null, null));
        JSONArray jsonDevices = response.getJSONArray("devices");
        List<Device> devices = new ArrayList<>(jsonDevices.length());
        for (int i = 0; i < jsonDevices.length(); i++) {
            JSONObject item = jsonDevices.getJSONObject(i);
            String id = item.isNull("id") ? null : item.optString("id", null);
            devices.add(new Device(id,
                    item.optString("name", "Spotify device"),
                    item.optString("type", "device"),
                    item.optBoolean("is_active", false),
                    item.optBoolean("is_restricted", false)));
        }
        return devices;
    }

    static PlaylistPage getPlaylists(String accessToken, int offset)
            throws IOException, JSONException {
        if (offset < 0) throw new IllegalArgumentException("Playlist offset cannot be negative");
        Uri endpoint = Uri.parse(PLAYLISTS_ENDPOINT).buildUpon()
                .appendQueryParameter("limit", String.valueOf(PLAYLIST_PAGE_SIZE))
                .appendQueryParameter("offset", String.valueOf(offset))
                .build();
        String responseText = request(endpoint.toString(), "GET", accessToken, null, null);
        if (responseText.isEmpty()) throw new IOException("Spotify returned an empty playlist list");
        JSONObject response = new JSONObject(responseText);
        JSONArray items = response.optJSONArray("items");
        if (items == null) throw new JSONException("Spotify playlist response has no items");

        List<Playlist> playlists = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = optionalText(item, "name");
            String uri = optionalText(item, "uri");
            if (name == null || uri == null || !uri.startsWith("spotify:playlist:")) continue;
            playlists.add(new Playlist(name, uri));
        }

        boolean hasMore = items.length() > 0 && optionalText(response, "next") != null;
        return new PlaylistPage(playlists, hasMore ? offset + items.length() : -1);
    }

    static Playlist createPlaylist(String accessToken, String name)
            throws IOException, JSONException {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) throw new IllegalArgumentException("Playlist name is empty");
        JSONObject body = new JSONObject();
        body.put("name", normalizedName);
        body.put("public", false);
        String responseText = request(
                PLAYLISTS_ENDPOINT, "POST", accessToken, "application/json", body.toString());
        if (responseText.isEmpty()) throw new IOException("Spotify returned an empty playlist");
        JSONObject response = new JSONObject(responseText);
        String uri = optionalText(response, "uri");
        playlistId(uri);
        String playlistName = optionalText(response, "name");
        return new Playlist(playlistName == null ? normalizedName : playlistName, uri);
    }

    static List<Device> mergeDevices(List<Device> apiDevices, List<Device> localDevices) {
        List<Device> merged = new ArrayList<>();
        Set<String> deviceIds = new HashSet<>();
        if (apiDevices != null) {
            for (Device device : apiDevices) {
                merged.add(device);
                if (device.id != null) deviceIds.add(device.id);
            }
        }
        if (localDevices != null) {
            for (Device device : localDevices) {
                if (device.id != null && deviceIds.add(device.id)) merged.add(device);
            }
        }
        return merged;
    }

    static Playback getCurrentPlayback(String accessToken)
            throws IOException, JSONException {
        String response = request(PLAYER_ENDPOINT, "GET", accessToken, null, null);
        if (response.isEmpty()) return null;

        JSONObject playback = new JSONObject(response);
        JSONObject item = playback.optJSONObject("item");
        if (item == null) return null;

        String title = optionalText(item, "name");
        if (title == null) return null;
        String artist = artists(item.optJSONArray("artists"));
        JSONObject show = item.optJSONObject("show");
        if (artist == null && show != null) artist = optionalText(show, "name");

        JSONObject album = item.optJSONObject("album");
        String albumName = album == null ? null : optionalText(album, "name");
        JSONArray images = album == null
                ? item.optJSONArray("images") : album.optJSONArray("images");
        if (images == null && show != null) images = show.optJSONArray("images");
        String artworkUrl = firstImageUrl(images);
        JSONObject device = playback.optJSONObject("device");
        return new Playback(title, artist, albumName, artworkUrl,
                device == null ? null : optionalText(device, "id"),
                playback.optBoolean("is_playing", false),
                item.optLong("duration_ms", 0),
                playback.optLong("progress_ms", 0),
                normalizeSpotifyTrackUri(optionalText(item, "uri")));
    }

    static Bitmap downloadArtwork(String artworkUrl) throws IOException {
        URL url = new URL(artworkUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Spotify artwork URL is not HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "image/*");
        try {
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("Spotify artwork request failed with HTTP " + statusCode);
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_ARTWORK_BYTES) {
                throw new IOException("Spotify artwork exceeded the size limit");
            }
            byte[] data;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_ARTWORK_BYTES) {
                        throw new IOException("Spotify artwork exceeded the size limit");
                    }
                    output.write(buffer, 0, count);
                }
                data = output.toByteArray();
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sampleSize = 1;
            while (Math.max(options.outWidth / sampleSize, options.outHeight / sampleSize)
                    > MAX_ARTWORK_DIMENSION) {
                sampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } finally {
            connection.disconnect();
        }
    }

    static void controlPlayback(String accessToken, PlaybackCommand command, String deviceId)
            throws IOException, JSONException {
        String path;
        String method;
        switch (command) {
            case PLAY:
                path = "/play";
                method = "PUT";
                break;
            case PAUSE:
                path = "/pause";
                method = "PUT";
                break;
            case NEXT:
                path = "/next";
                method = "POST";
                break;
            case PREVIOUS:
                path = "/previous";
                method = "POST";
                break;
            default:
                throw new IllegalArgumentException("Unsupported Spotify playback command");
        }
        Uri.Builder endpoint = Uri.parse(PLAYER_ENDPOINT + path).buildUpon();
        if (deviceId != null && !deviceId.isEmpty()) {
            endpoint.appendQueryParameter("device_id", deviceId);
        }
        request(endpoint.build().toString(), method, accessToken, null, null);
    }

    static void transferPlayback(String accessToken, String deviceId, boolean play)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        JSONArray deviceIds = new JSONArray();
        deviceIds.put(deviceId);
        body.put("device_ids", deviceIds);
        body.put("play", play);
        request(PLAYER_ENDPOINT, "PUT", accessToken, "application/json", body.toString());
    }

    static void playPlaylist(String accessToken, String playlistUri, String deviceId)
            throws IOException, JSONException {
        if (playlistUri == null || !playlistUri.startsWith("spotify:playlist:")) {
            throw new IllegalArgumentException("Invalid Spotify playlist URI");
        }
        JSONObject body = new JSONObject();
        body.put("context_uri", playlistUri);
        Uri.Builder endpoint = Uri.parse(PLAYER_ENDPOINT + "/play").buildUpon();
        if (deviceId != null && !deviceId.isEmpty()) {
            endpoint.appendQueryParameter("device_id", deviceId);
        }
        request(endpoint.build().toString(), "PUT", accessToken,
                "application/json", body.toString());
    }

    static void seekTo(String accessToken, long positionMs, String deviceId)
            throws IOException {
        if (positionMs < 0) throw new IllegalArgumentException("Seek position cannot be negative");
        Uri.Builder endpoint = Uri.parse(PLAYER_ENDPOINT + "/seek").buildUpon()
                .appendQueryParameter("position_ms", String.valueOf(positionMs));
        if (deviceId != null && !deviceId.isEmpty()) {
            endpoint.appendQueryParameter("device_id", deviceId);
        }
        request(endpoint.build().toString(), "PUT", accessToken, null, null);
    }

    static boolean addTrackToPlaylist(
            String accessToken, String playlistUri, String trackUri)
            throws IOException, JSONException {
        String playlistId = playlistId(playlistUri);
        String normalizedTrackUri = normalizeSpotifyTrackUri(trackUri);
        if (normalizedTrackUri == null) {
            throw new IllegalArgumentException("Invalid Spotify track URI");
        }
        if (playlistContainsTrack(accessToken, playlistId, normalizedTrackUri)) return true;

        JSONObject body = new JSONObject();
        JSONArray uris = new JSONArray();
        uris.put(normalizedTrackUri);
        body.put("uris", uris);
        request(String.format(Locale.ROOT, PLAYLIST_ITEMS_ENDPOINT, playlistId),
                "POST", accessToken, "application/json", body.toString());
        return false;
    }

    static void removeTrackFromPlaylist(
            String accessToken, String playlistUri, String trackUri)
            throws IOException, JSONException {
        String playlistId = playlistId(playlistUri);
        String normalizedTrackUri = normalizeSpotifyTrackUri(trackUri);
        if (normalizedTrackUri == null) {
            throw new IllegalArgumentException("Invalid Spotify track URI");
        }
        String detailsUrl = String.format(Locale.ROOT, PLAYLIST_ENDPOINT, playlistId);
        String details = request(Uri.parse(detailsUrl).buildUpon()
                .appendQueryParameter("fields", "snapshot_id").build().toString(),
                "GET", accessToken, null, null);
        if (details.isEmpty()) throw new IOException("Spotify returned an empty playlist");
        String snapshotId = optionalText(new JSONObject(details), "snapshot_id");
        if (snapshotId == null) {
            throw new JSONException("Spotify playlist has no snapshot ID");
        }

        JSONObject item = new JSONObject();
        item.put("uri", normalizedTrackUri);
        JSONArray items = new JSONArray();
        items.put(item);
        JSONObject body = new JSONObject();
        body.put("items", items);
        body.put("snapshot_id", snapshotId);
        request(String.format(Locale.ROOT, PLAYLIST_ITEMS_ENDPOINT, playlistId),
                "DELETE", accessToken, "application/json", body.toString());
    }

    static String spotifyTrackUriForMediaId(String packageName, String mediaId) {
        if (packageName == null || !packageName.startsWith("com.spotify.")) return null;
        return normalizeSpotifyTrackUri(mediaId);
    }

    static String normalizeSpotifyTrackUri(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        String trackId;
        if (candidate.startsWith("spotify:track:")) {
            trackId = candidate.substring("spotify:track:".length());
        } else {
            String webTrackPrefix = "https://open.spotify.com/track/";
            if (candidate.startsWith(webTrackPrefix)) {
                trackId = candidate.substring(webTrackPrefix.length());
                int suffix = trackId.indexOf('/');
                if (suffix >= 0) trackId = trackId.substring(0, suffix);
                suffix = trackId.indexOf('?');
                if (suffix >= 0) trackId = trackId.substring(0, suffix);
                suffix = trackId.indexOf('#');
                if (suffix >= 0) trackId = trackId.substring(0, suffix);
            } else {
                trackId = candidate;
            }
        }
        if (!trackId.matches("[A-Za-z0-9]{1,128}")) return null;
        return "spotify:track:" + trackId;
    }

    private static String playlistId(String playlistUri) {
        String prefix = "spotify:playlist:";
        if (playlistUri == null || !playlistUri.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid Spotify playlist URI");
        }
        String playlistId = playlistUri.substring(prefix.length());
        if (!playlistId.matches("[A-Za-z0-9]{1,128}")) {
            throw new IllegalArgumentException("Invalid Spotify playlist URI");
        }
        return playlistId;
    }

    private static boolean playlistContainsTrack(
            String accessToken, String playlistId, String trackUri)
            throws IOException, JSONException {
        int offset = 0;
        while (true) {
            Uri endpoint = Uri.parse(String.format(
                    Locale.ROOT, PLAYLIST_ITEMS_ENDPOINT, playlistId)).buildUpon()
                    .appendQueryParameter("limit", String.valueOf(PLAYLIST_ITEMS_PAGE_SIZE))
                    .appendQueryParameter("offset", String.valueOf(offset))
                    .build();
            String responseText = request(endpoint.toString(), "GET", accessToken, null, null);
            if (responseText.isEmpty()) {
                throw new IOException("Spotify returned an empty playlist item list");
            }
            JSONObject response = new JSONObject(responseText);
            JSONArray items = response.optJSONArray("items");
            if (items == null) throw new JSONException("Spotify playlist has no items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject entry = items.optJSONObject(i);
                if (entry == null) continue;
                JSONObject item = entry.optJSONObject("item");
                if (item == null) item = entry.optJSONObject("track");
                if (item != null && trackUri.equals(
                        normalizeSpotifyTrackUri(optionalText(item, "uri")))) {
                    return true;
                }
            }
            if (items.length() == 0 || optionalText(response, "next") == null) return false;
            offset += items.length();
        }
    }

    private static String artists(JSONArray artists) throws JSONException {
        if (artists == null) return null;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            String name = artist == null ? null : optionalText(artist, "name");
            if (name == null) continue;
            if (names.length() > 0) names.append(", ");
            names.append(name);
        }
        return names.length() == 0 ? null : names.toString();
    }

    private static String firstImageUrl(JSONArray images) throws JSONException {
        if (images == null) return null;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            String url = image == null ? null : optionalText(image, "url");
            if (url != null) return url;
        }
        return null;
    }

    private static String optionalText(JSONObject object, String key) {
        String value = object.optString(key, null);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static Token tokenFromResponse(JSONObject response, String existingRefreshToken)
            throws JSONException {
        String accessToken = response.getString("access_token");
        String refreshToken = response.optString("refresh_token", existingRefreshToken);
        if (refreshToken != null && refreshToken.isEmpty()) {
            refreshToken = existingRefreshToken;
        }
        long expiresIn = response.getLong("expires_in");
        return new Token(accessToken, refreshToken,
                System.currentTimeMillis() + Math.max(0, expiresIn) * 1000L);
    }

    private static JSONObject postForm(String endpoint, String... fields)
            throws IOException, JSONException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            if (i > 0) body.append('&');
            body.append(urlEncode(fields[i])).append('=').append(urlEncode(fields[i + 1]));
        }
        return new JSONObject(request(
                endpoint, "POST", null, "application/x-www-form-urlencoded", body.toString()));
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 is unavailable", exception);
        }
    }

    private static String request(
            String endpoint, String method, String accessToken, String contentType, String body)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        if (accessToken != null) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
        }

        try {
            if (body != null) {
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int statusCode = connection.getResponseCode();
            if (statusCode == HttpURLConnection.HTTP_NO_CONTENT) return "";
            String response = readResponse(connection, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new ApiException(statusCode);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String readResponse(HttpURLConnection connection, int statusCode)
            throws IOException {
        InputStream stream = statusCode >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
                if (response.length() > 65536) {
                    throw new IOException("Spotify response exceeded the size limit");
                }
            }
        }
        return response.toString();
    }

    static final class Token {
        final String accessToken;
        final String refreshToken;
        final long expiresAtMillis;

        Token(String accessToken, String refreshToken, long expiresAtMillis) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMillis = expiresAtMillis;
        }

        boolean expiresSoon() {
            return expiresAtMillis <= System.currentTimeMillis() + 30000L;
        }
    }

    static final class Device {
        final String id;
        final String name;
        final String type;
        final boolean active;
        final boolean restricted;
        final boolean local;
        final String hostAddress;

        Device(String id, String name, String type, boolean active, boolean restricted) {
            this(id, name, type, active, restricted, false);
        }

        Device(String id, String name, String type, boolean active,
               boolean restricted, boolean local) {
            this(id, name, type, active, restricted, local, null);
        }

        Device(String id, String name, String type, boolean active,
               boolean restricted, boolean local, String hostAddress) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.active = active;
            this.restricted = restricted;
            this.local = local;
            this.hostAddress = hostAddress;
        }
    }

    static final class Playlist {
        final String name;
        final String uri;

        Playlist(String name, String uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    static final class PlaylistPage {
        final List<Playlist> playlists;
        final int nextOffset;

        PlaylistPage(List<Playlist> playlists, int nextOffset) {
            this.playlists = playlists;
            this.nextOffset = nextOffset;
        }

        boolean hasMore() {
            return nextOffset >= 0;
        }
    }

    enum PlaybackCommand {
        PLAY,
        PAUSE,
        NEXT,
        PREVIOUS
    }

    static final class Playback {
        final String title;
        final String artist;
        final String album;
        final String artworkUrl;
        final String deviceId;
        final boolean playing;
        final long durationMs;
        final long positionMs;
        final String trackUri;

        Playback(String title, String artist, String album, String artworkUrl, String deviceId,
                 boolean playing, long durationMs, long positionMs, String trackUri) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artworkUrl = artworkUrl;
            this.deviceId = deviceId;
            this.playing = playing;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.trackUri = trackUri;
        }
    }

    static final class ApiException extends IOException {
        final int statusCode;

        ApiException(int statusCode) {
            super("Spotify API request returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }
}
