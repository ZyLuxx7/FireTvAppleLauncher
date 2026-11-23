package com.appletv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class EncryptedJsonStore {
    private static final int IV_LENGTH = 12;

    private final SharedPreferences preferences;
    private final String valueKey;
    private final String keyAlias;

    EncryptedJsonStore(Context context, String preferencesName, String valueKey, String keyAlias) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        this.valueKey = valueKey;
        this.keyAlias = keyAlias;
    }

    JSONObject read() throws GeneralSecurityException, JSONException, IOException {
        String stored = preferences.getString(valueKey, null);
        if (stored == null) return new JSONObject();
        byte[] encrypted;
        try {
            encrypted = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Stored encrypted settings are malformed", exception);
        }
        if (encrypted.length <= IV_LENGTH) {
            throw new IOException("Stored encrypted settings are incomplete");
        }
        byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, IV_LENGTH, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
    }

    void write(JSONObject data) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] ciphertext = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH) {
            throw new GeneralSecurityException("Android Keystore returned an invalid IV");
        }
        byte[] encrypted = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encrypted, 0, iv.length);
        System.arraycopy(ciphertext, 0, encrypted, iv.length, ciphertext.length);
        boolean saved = preferences.edit()
                .putString(valueKey, Base64.getEncoder().encodeToString(encrypted))
                .commit();
        if (!saved) throw new IOException("Could not save encrypted settings");
    }

    boolean clear() {
        return preferences.edit().clear().commit();
    }

    private SecretKey getKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(keyAlias)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
            keyStore.load(null);
        }
        Key key = keyStore.getKey(keyAlias, null);
        if (!(key instanceof SecretKey)) {
            throw new GeneralSecurityException("Android Keystore did not return the key");
        }
        return (SecretKey) key;
    }
}
