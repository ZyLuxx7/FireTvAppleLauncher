package com.appletv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

final class SpotifyRemotePlayback {
    static final String CLIENT_ID_KEY = "spotify_client_id";

    private final Context appContext;
    private final SharedPreferences preferences;
    private final SpotifyTokenStore tokenStore;

    SpotifyRemotePlayback(Context context) {
        Context appContext = context.getApplicationContext();
        this.appContext = appContext;
        preferences = appContext.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        tokenStore = new SpotifyTokenStore(appContext);
    }

    String clientId() {
        return preferences.getString(CLIENT_ID_KEY, "").trim();
    }

    static NowPlayingListener.Snapshot snapshot(
            SpotifyConnectClient.Playback playback, Bitmap artwork) {
        return new NowPlayingListener.Snapshot(
                playback.title, playback.artist, playback.album, null,
                "spotify-connect", artwork, playback.playing, true,
                playback.durationMs, playback.positionMs, SystemClock.elapsedRealtime(),
                playback.playing ? 1f : 0f, null, true,
                playback.deviceId, playback.artworkUrl, playback.trackUri);
    }

    SpotifyConnectClient.Playback currentPlayback()
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) return null;
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) return null;
        try {
            return SpotifyConnectClient.getCurrentPlayback(token.accessToken);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            return SpotifyConnectClient.getCurrentPlayback(token.accessToken);
        }
    }

    List<SpotifyConnectClient.Device> availableDevices()
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) return null;
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) return null;
        List<SpotifyConnectClient.Device> devices;
        try {
            devices = SpotifyConnectClient.getAvailableDevices(token.accessToken);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            devices = SpotifyConnectClient.getAvailableDevices(token.accessToken);
        }
        return SpotifyConnectDiscovery.mergeLocalDevices(appContext, devices);
    }

    SpotifyConnectClient.PlaylistPage playlists(int offset)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) return null;
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) return null;
        try {
            return SpotifyConnectClient.getPlaylists(token.accessToken, offset);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            return SpotifyConnectClient.getPlaylists(token.accessToken, offset);
        }
    }

    void playPlaylist(String playlistUri, String deviceId)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) throw new IOException("Spotify Client ID is not configured");
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) throw new IOException("Spotify authorization is unavailable");
        try {
            SpotifyConnectClient.playPlaylist(token.accessToken, playlistUri, deviceId);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            SpotifyConnectClient.playPlaylist(token.accessToken, playlistUri, deviceId);
        }
    }

    boolean addTrackToPlaylist(String playlistUri, String trackUri)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) throw new IOException("Spotify Client ID is not configured");
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) throw new IOException("Spotify authorization is unavailable");
        try {
            return SpotifyConnectClient.addTrackToPlaylist(
                    token.accessToken, playlistUri, trackUri);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            return SpotifyConnectClient.addTrackToPlaylist(
                    token.accessToken, playlistUri, trackUri);
        }
    }

    SpotifyConnectClient.Playlist createPlaylist(String name)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) throw new IOException("Spotify Client ID is not configured");
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) throw new IOException("Spotify authorization is unavailable");
        try {
            return SpotifyConnectClient.createPlaylist(token.accessToken, name);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            return SpotifyConnectClient.createPlaylist(token.accessToken, name);
        }
    }

    void removeTrackFromPlaylist(String playlistUri, String trackUri)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) throw new IOException("Spotify Client ID is not configured");
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) throw new IOException("Spotify authorization is unavailable");
        try {
            SpotifyConnectClient.removeTrackFromPlaylist(
                    token.accessToken, playlistUri, trackUri);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            SpotifyConnectClient.removeTrackFromPlaylist(
                    token.accessToken, playlistUri, trackUri);
        }
    }

    void seekTo(long positionMs, String deviceId)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) throw new IOException("Spotify Client ID is not configured");
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) throw new IOException("Spotify authorization is unavailable");
        try {
            SpotifyConnectClient.seekTo(token.accessToken, positionMs, deviceId);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            SpotifyConnectClient.seekTo(token.accessToken, positionMs, deviceId);
        }
    }

    void transferPlayback(String deviceId, boolean play)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) throw new IOException("Spotify Client ID is not configured");
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) throw new IOException("Spotify authorization is unavailable");
        try {
            SpotifyConnectClient.transferPlayback(token.accessToken, deviceId, play);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            SpotifyConnectClient.transferPlayback(token.accessToken, deviceId, play);
        }
    }

    void controlPlayback(SpotifyConnectClient.PlaybackCommand command, String deviceId)
            throws GeneralSecurityException, JSONException, IOException {
        String clientId = clientId();
        if (clientId.isEmpty()) throw new IOException("Spotify Client ID is not configured");
        SpotifyConnectClient.Token token = tokenStore.validToken(clientId);
        if (token == null) throw new IOException("Spotify authorization is unavailable");
        try {
            SpotifyConnectClient.controlPlayback(token.accessToken, command, deviceId);
        } catch (SpotifyConnectClient.ApiException exception) {
            if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
            token = tokenStore.refreshToken(clientId, token);
            SpotifyConnectClient.controlPlayback(token.accessToken, command, deviceId);
        }
    }
}
