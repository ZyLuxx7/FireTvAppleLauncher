package com.appletv.launcher;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OAuth PKCE sign-in and Spotify Connect device selection. */
public final class SpotifyConnectActivity extends LauncherActivity {
    private static final String TAG = "AppleTv.SpotifyConnect";
    private static final String CLIENT_ID_KEY = "spotify_client_id";
    static final String EXTRA_REAUTHORIZE = "spotify_reauthorize";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView connectAction;
    private TextView changeClientIdAction;
    private TextView status;
    private TextView disconnectAction;
    private ScrollView deviceScroll;
    private LinearLayout deviceList;
    private SpotifyTokenStore tokenStore;
    private ExecutorService networkExecutor;
    private boolean working;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_spotify_connect);
        connectAction = findViewById(R.id.spotifyConnectAction);
        changeClientIdAction = findViewById(R.id.spotifyConnectChangeClientId);
        status = findViewById(R.id.spotifyConnectStatus);
        disconnectAction = findViewById(R.id.spotifyConnectSignOut);
        deviceScroll = findViewById(R.id.spotifyConnectDeviceScroll);
        deviceList = findViewById(R.id.spotifyConnectDevices);
        tokenStore = new SpotifyTokenStore(this);
        networkExecutor = Executors.newSingleThreadExecutor();

        findViewById(R.id.spotifyConnectBack).setOnClickListener(v -> finish());
        connectAction.setOnClickListener(v -> connectOrRefresh());
        changeClientIdAction.setOnClickListener(v -> promptForClientId());
        disconnectAction.setOnClickListener(v -> confirmDisconnect());
        updateConnectAction();

        if (isSpotifyCallback(getIntent().getData())) {
            handleAuthorizationRedirect(getIntent());
        } else if (getIntent().getBooleanExtra(EXTRA_REAUTHORIZE, false)) {
            reauthorizeForPlaylists();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isSpotifyCallback(intent.getData())) handleAuthorizationRedirect(intent);
        else if (intent.getBooleanExtra(EXTRA_REAUTHORIZE, false)) reauthorizeForPlaylists();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (networkExecutor != null) networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void connectOrRefresh() {
        String clientId = configuredClientId();
        if (clientId.isEmpty()) {
            promptForClientId();
            return;
        }
        try {
            if (tokenStore.loadToken(clientId) == null) {
                beginAuthorization(clientId);
            } else {
                loadDevices(clientId);
            }
        } catch (GeneralSecurityException | JSONException | IOException exception) {
            showStorageError(exception);
        }
    }

    private void promptForClientId() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(configuredClientId());
        input.setHint(R.string.spotify_connect_client_id_title);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.spotify_connect_client_id_title)
                .setMessage(getString(R.string.spotify_connect_client_id_help,
                        SpotifyConnectClient.REDIRECT_URI))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String previousClientId = configuredClientId();
                    String clientId = input.getText().toString().trim();
                    if (clientId.isEmpty()) {
                        status.setText(R.string.spotify_connect_error_client_id);
                        return;
                    }
                    if (!clientId.equals(previousClientId)) {
                        try {
                            tokenStore.clearTokens();
                        } catch (GeneralSecurityException | JSONException | IOException exception) {
                            showStorageError(exception);
                            return;
                        }
                        NowPlayingListener.setSpotifyRemoteSnapshot(this, null);
                        deviceList.removeAllViews();
                        deviceScroll.setVisibility(View.GONE);
                        disconnectAction.setVisibility(View.GONE);
                        status.setText(R.string.spotify_connect_status_setup);
                    }
                    launcherPreferences().edit().putString(CLIENT_ID_KEY, clientId).apply();
                    updateConnectAction();
                    beginAuthorization(clientId);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void beginAuthorization(String clientId) {
        try {
            String verifier = SpotifyConnectClient.createCodeVerifier();
            String state = SpotifyConnectClient.createState();
            String challenge = SpotifyConnectClient.createCodeChallenge(verifier);
            tokenStore.savePendingAuthorization(clientId, state, verifier);
            Uri authorization = SpotifyConnectClient.authorizationUri(clientId, state, challenge);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, authorization));
            } catch (ActivityNotFoundException | SecurityException exception) {
                Log.e(TAG, "No browser can open Spotify authorization", exception);
                status.setText(getString(R.string.spotify_connect_error_auth,
                        exception.getLocalizedMessage()));
            }
        } catch (GeneralSecurityException | JSONException | IOException exception) {
            showStorageError(exception);
        }
    }

    private void reauthorizeForPlaylists() {
        String clientId = configuredClientId();
        if (clientId.isEmpty()) promptForClientId();
        else beginAuthorization(clientId);
    }

    private void handleAuthorizationRedirect(Intent intent) {
        Uri callback = intent.getData();
        if (callback == null) return;
        try {
            SpotifyTokenStore.PendingAuthorization pending =
                    tokenStore.loadPendingAuthorization();
            if (pending == null) {
                status.setText(getString(R.string.spotify_connect_error_auth,
                        "No matching sign-in request"));
                return;
            }
            String returnedState = callback.getQueryParameter("state");
            if (!SpotifyConnectClient.stateMatches(pending.state, returnedState)) {
                status.setText(getString(R.string.spotify_connect_error_auth,
                        "The sign-in state did not match"));
                return;
            }
            String authorizationError = callback.getQueryParameter("error");
            tokenStore.clearPendingAuthorization();
            if (authorizationError != null) {
                status.setText(R.string.spotify_connect_status_cancelled);
                return;
            }
            String code = callback.getQueryParameter("code");
            if (code == null || code.isEmpty()) {
                status.setText(getString(R.string.spotify_connect_error_auth,
                        "Spotify did not return an authorization code"));
                return;
            }
            exchangeAuthorizationCode(pending, code);
        } catch (GeneralSecurityException | JSONException | IOException exception) {
            showStorageError(exception);
        }
    }

    private void exchangeAuthorizationCode(
            SpotifyTokenStore.PendingAuthorization pending, String code) {
        setBusy(true);
        status.setText(R.string.spotify_connect_status_loading);
        networkExecutor.execute(() -> {
            try {
                SpotifyConnectClient.Token token = SpotifyConnectClient.exchangeAuthorizationCode(
                        pending.clientId, code, pending.verifier);
                tokenStore.saveToken(pending.clientId, token);
                postToUi(() -> {
                    setBusy(false);
                    loadDevices(pending.clientId);
                });
            } catch (SpotifyConnectClient.ApiException exception) {
                postToUi(() -> showApiError(exception));
            } catch (IOException exception) {
                postToUi(() -> showNetworkError(exception));
            } catch (GeneralSecurityException | JSONException exception) {
                postToUi(() -> showStorageError(exception));
            }
        });
    }

    private void loadDevices(String clientId) {
        loadDevices(clientId, null);
    }

    private void loadDevices(String clientId, String focusDeviceId) {
        if (working) return;
        setBusy(true);
        status.setText(R.string.spotify_connect_status_loading);
        deviceList.removeAllViews();
        deviceScroll.setVisibility(View.GONE);
        networkExecutor.execute(() -> {
            try {
                SpotifyConnectClient.Token token = validToken(clientId);
                if (token == null) {
                    postToUi(() -> {
                        setBusy(false);
                        beginAuthorization(clientId);
                    });
                    return;
                }
                List<SpotifyConnectClient.Device> devices;
                try {
                    devices = SpotifyConnectClient.getAvailableDevices(token.accessToken);
                } catch (SpotifyConnectClient.ApiException exception) {
                    if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
                    token = refreshToken(clientId, token);
                    devices = SpotifyConnectClient.getAvailableDevices(token.accessToken);
                }
                devices = SpotifyConnectDiscovery.mergeLocalDevices(
                        getApplicationContext(), devices);
                List<SpotifyConnectClient.Device> result = devices;
                postToUi(() -> showDevices(result, focusDeviceId));
            } catch (SpotifyConnectClient.ApiException exception) {
                postToUi(() -> showApiError(exception));
            } catch (IOException exception) {
                postToUi(() -> showNetworkError(exception));
            } catch (GeneralSecurityException | JSONException exception) {
                postToUi(() -> showStorageError(exception));
            }
        });
    }

    private SpotifyConnectClient.Token validToken(String clientId)
            throws GeneralSecurityException, JSONException, IOException {
        return tokenStore.validToken(clientId);
    }

    private SpotifyConnectClient.Token refreshToken(
            String clientId, SpotifyConnectClient.Token token)
            throws IOException, JSONException, GeneralSecurityException {
        return tokenStore.refreshToken(clientId, token);
    }

    private void showDevices(
            List<SpotifyConnectClient.Device> devices, String focusDeviceId) {
        setBusy(false);
        disconnectAction.setVisibility(View.VISIBLE);
        connectAction.setText(R.string.spotify_connect_action_refresh);
        deviceList.removeAllViews();
        if (devices.isEmpty()) {
            deviceScroll.setVisibility(View.GONE);
            status.setText(R.string.spotify_connect_status_empty);
            return;
        }

        status.setText(R.string.spotify_connect_status_ready);
        deviceScroll.setVisibility(View.VISIBLE);
        View focusedDevice = null;
        View firstAvailableDevice = null;
        for (SpotifyConnectClient.Device device : devices) {
            TextView row = (TextView) getLayoutInflater().inflate(
                    R.layout.item_spotify_device, deviceList, false);
            String label = getString(device.active
                            ? R.string.spotify_connect_device_active_format
                            : R.string.spotify_connect_device_format,
                    device.name, device.type);
            if (device.local) {
                label += " · " + getString(R.string.spotify_connect_device_local);
            } else if (device.restricted) {
                label += " · " + getString(R.string.spotify_connect_device_restricted);
            }
            row.setText(label);
            row.setEnabled(device.id != null && !device.restricted);
            if (row.isEnabled()) {
                if (firstAvailableDevice == null) firstAvailableDevice = row;
                if (device.id.equals(focusDeviceId)) focusedDevice = row;
            }
            row.setOnClickListener(v -> transferToDevice(device));
            deviceList.addView(row);
        }
        if (focusDeviceId != null) {
            View focusTarget = focusedDevice != null ? focusedDevice : firstAvailableDevice;
            if (focusTarget == null) focusTarget = connectAction;
            View finalFocusTarget = focusTarget;
            finalFocusTarget.setFocusableInTouchMode(true);
            finalFocusTarget.post(() -> {
                if (!isFinishing() && finalFocusTarget.isShown()
                        && finalFocusTarget.isEnabled()) {
                    finalFocusTarget.requestFocus();
                }
            });
        }
    }

    private void transferToDevice(SpotifyConnectClient.Device device) {
        if (working || device.id == null || device.restricted) return;
        if (device.local) {
            status.setText(R.string.spotify_connect_error_local_device);
            return;
        }
        String clientId = configuredClientId();
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        boolean resumePlayback = NowPlayingListener.isSpotifyPlayback(snapshot)
                && snapshot.playing;
        setBusy(true);
        status.setText(R.string.spotify_connect_status_loading);
        networkExecutor.execute(() -> {
            try {
                SpotifyConnectClient.Token token = validToken(clientId);
                if (token == null) {
                    postToUi(() -> {
                        setBusy(false);
                        beginAuthorization(clientId);
                    });
                    return;
                }
                try {
                    SpotifyConnectClient.transferPlayback(token.accessToken, device.id, resumePlayback);
                } catch (SpotifyConnectClient.ApiException exception) {
                    if (exception.statusCode != 401 || token.refreshToken == null) throw exception;
                    token = refreshToken(clientId, token);
                    SpotifyConnectClient.transferPlayback(token.accessToken, device.id, resumePlayback);
                }
                postToUi(() -> {
                    setBusy(false);
                    NowPlayingListener.beginAudioOutputSwitchGrace(snapshot);
                    status.setText(getString(
                            R.string.spotify_connect_status_transferred, device.name));
                    loadDevices(clientId, device.id);
                });
            } catch (SpotifyConnectClient.ApiException exception) {
                postToUi(() -> showApiError(exception));
            } catch (IOException exception) {
                postToUi(() -> showNetworkError(exception));
            } catch (GeneralSecurityException | JSONException exception) {
                postToUi(() -> showStorageError(exception));
            }
        });
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.spotify_connect_sign_out)
                .setMessage(R.string.spotify_connect_disconnect_confirmation)
                .setPositiveButton(R.string.spotify_connect_sign_out, (dialog, which) -> {
                    try {
                        tokenStore.clearTokens();
                        NowPlayingListener.setSpotifyRemoteSnapshot(this, null);
                        deviceList.removeAllViews();
                        deviceScroll.setVisibility(View.GONE);
                        disconnectAction.setVisibility(View.GONE);
                        connectAction.setText(R.string.spotify_connect_action_setup);
                        status.setText(R.string.spotify_connect_status_disconnected);
                    } catch (GeneralSecurityException | JSONException | IOException exception) {
                        showStorageError(exception);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setBusy(boolean busy) {
        working = busy;
        connectAction.setEnabled(!busy);
        changeClientIdAction.setEnabled(!busy);
        disconnectAction.setEnabled(!busy);
    }

    private void updateConnectAction() {
        String clientId = configuredClientId();
        connectAction.setText(clientId.isEmpty()
                ? R.string.spotify_connect_action_setup
                : R.string.spotify_connect_action_connect);
        changeClientIdAction.setVisibility(clientId.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String configuredClientId() {
        return launcherPreferences().getString(CLIENT_ID_KEY, "").trim();
    }

    private SharedPreferences launcherPreferences() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
    }

    private void showApiError(SpotifyConnectClient.ApiException exception) {
        setBusy(false);
        Log.w(TAG, "Spotify API request failed with HTTP " + exception.statusCode);
        if (exception.statusCode == 403) {
            status.setText(R.string.spotify_connect_error_premium);
        } else if (exception.statusCode == 429) {
            status.setText(R.string.spotify_connect_error_rate_limit);
        } else {
            status.setText(getString(R.string.spotify_connect_error_api, exception.statusCode));
        }
    }

    private void showNetworkError(IOException exception) {
        setBusy(false);
        Log.w(TAG, "Spotify network request failed", exception);
        String detail = exception.getLocalizedMessage();
        status.setText(getString(R.string.spotify_connect_error_network,
                detail == null ? exception.getClass().getSimpleName() : detail));
    }

    private void showStorageError(Exception exception) {
        setBusy(false);
        Log.e(TAG, "Could not read or save Spotify authorization data", exception);
        status.setText(R.string.spotify_connect_error_storage);
    }

    private void postToUi(Runnable action) {
        mainHandler.post(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    private boolean isSpotifyCallback(Uri uri) {
        return uri != null
                && "com.appletv.launcher.firetv".equals(uri.getScheme())
                && "spotify-callback".equals(uri.getHost());
    }
}
