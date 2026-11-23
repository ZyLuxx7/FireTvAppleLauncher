package com.appletv.launcher;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

final class SpotifyTokenStore {
    private static final String PREFERENCES_NAME = "spotify_connect_tokens";
    private static final String VALUE_KEY = "encrypted_data";
    private static final String KEY_ALIAS = "apple_launcher_spotify_connect";

    private final EncryptedJsonStore dataStore;

    SpotifyTokenStore(Context context) {
        dataStore = new EncryptedJsonStore(context, PREFERENCES_NAME, VALUE_KEY, KEY_ALIAS);
    }

    SpotifyConnectClient.Token loadToken(String clientId)
            throws GeneralSecurityException, JSONException, IOException {
        JSONObject data = readData();
        if (!clientId.equals(data.optString("client_id", ""))) return null;
        String accessToken = data.optString("access_token", "");
        if (accessToken.isEmpty()) return null;
        String refreshToken = data.optString("refresh_token", "");
        return new SpotifyConnectClient.Token(accessToken,
                refreshToken.isEmpty() ? null : refreshToken,
                data.optLong("expires_at_ms", 0));
    }

    SpotifyConnectClient.Token validToken(String clientId)
            throws GeneralSecurityException, JSONException, IOException {
        SpotifyConnectClient.Token token = loadToken(clientId);
        if (token == null || !token.expiresSoon()) return token;
        if (token.refreshToken == null) {
            clearTokens();
            return null;
        }
        return refreshToken(clientId, token);
    }

    SpotifyConnectClient.Token refreshToken(
            String clientId, SpotifyConnectClient.Token token)
            throws GeneralSecurityException, JSONException, IOException {
        SpotifyConnectClient.Token refreshed = SpotifyConnectClient.refreshAccessToken(
                clientId, token.refreshToken);
        saveToken(clientId, refreshed);
        return refreshed;
    }

    void saveToken(String clientId, SpotifyConnectClient.Token token)
            throws GeneralSecurityException, JSONException, IOException {
        JSONObject data = readData();
        data.put("client_id", clientId);
        data.put("access_token", token.accessToken);
        if (token.refreshToken == null || token.refreshToken.isEmpty()) {
            data.remove("refresh_token");
        } else {
            data.put("refresh_token", token.refreshToken);
        }
        data.put("expires_at_ms", token.expiresAtMillis);
        writeData(data);
    }

    void clearTokens() throws GeneralSecurityException, JSONException, IOException {
        JSONObject data = readData();
        data.remove("client_id");
        data.remove("access_token");
        data.remove("refresh_token");
        data.remove("expires_at_ms");
        writeData(data);
    }

    boolean reset() {
        return dataStore.clear();
    }

    void savePendingAuthorization(String clientId, String state, String verifier)
            throws GeneralSecurityException, JSONException, IOException {
        JSONObject data = readData();
        data.put("pending_client_id", clientId);
        data.put("pending_state", state);
        data.put("pending_verifier", verifier);
        writeData(data);
    }

    PendingAuthorization loadPendingAuthorization()
            throws GeneralSecurityException, JSONException, IOException {
        JSONObject data = readData();
        String clientId = data.optString("pending_client_id", "");
        String state = data.optString("pending_state", "");
        String verifier = data.optString("pending_verifier", "");
        if (clientId.isEmpty() || state.isEmpty() || verifier.isEmpty()) return null;
        return new PendingAuthorization(clientId, state, verifier);
    }

    void clearPendingAuthorization()
            throws GeneralSecurityException, JSONException, IOException {
        JSONObject data = readData();
        data.remove("pending_client_id");
        data.remove("pending_state");
        data.remove("pending_verifier");
        writeData(data);
    }

    private JSONObject readData()
            throws GeneralSecurityException, JSONException, IOException {
        return dataStore.read();
    }

    private void writeData(JSONObject data)
            throws GeneralSecurityException, IOException {
        dataStore.write(data);
    }

    static final class PendingAuthorization {
        final String clientId;
        final String state;
        final String verifier;

        PendingAuthorization(String clientId, String state, String verifier) {
            this.clientId = clientId;
            this.state = state;
            this.verifier = verifier;
        }
    }
}
