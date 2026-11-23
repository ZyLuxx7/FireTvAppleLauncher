package com.appletv.launcher;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;

final class MusixmatchApiKeyStore {
    private final EncryptedJsonStore dataStore;

    MusixmatchApiKeyStore(Context context) {
        dataStore = new EncryptedJsonStore(context, "musixmatch_credentials",
                "encrypted_data", "apple_launcher_musixmatch");
    }

    String readApiKey() throws GeneralSecurityException, JSONException, IOException {
        return dataStore.read().optString("api_key", "").trim();
    }

    void saveApiKey(String apiKey)
            throws GeneralSecurityException, JSONException, IOException {
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("API key cannot be empty");
        JSONObject data = dataStore.read();
        data.put("api_key", normalized);
        dataStore.write(data);
    }

    boolean clear() {
        return dataStore.clear();
    }
}
