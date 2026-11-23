package com.appletv.launcher;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class LauncherUpdateActivity extends LauncherActivity
        implements LauncherUpdateServer.Listener {
    private static final String TAG = "LauncherUpdateActivity";
    private static final int UNKNOWN_SOURCES_REQUEST = 701;
    private static final int INSTALL_REQUEST = 702;
    private static final int QR_SIZE = 520;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sessionWatchdog = new Runnable() {
        @Override
        public void run() {
            LauncherUpdateServer current = server;
            if (current != null && current.isExpired()) {
                stopServer();
                showSessionExpired();
            } else if (current != null) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    private TextView title;
    private TextView description;
    private TextView status;
    private TextView pin;
    private TextView address;
    private TextView phoneHint;
    private TextView allowSources;
    private TextView newSession;
    private ImageView qr;
    private LauncherUpdateServer server;
    private LauncherUpdateServer.InstallCandidate pendingInstall;
    private long expectedVersionCode;
    private long installedVersionBefore;
    private boolean waitingForUnknownSources;
    private boolean activityStarted;
    private String currentSessionPin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_update);
        title = findViewById(R.id.launcherUpdateTitle);
        description = findViewById(R.id.launcherUpdateDescription);
        status = findViewById(R.id.launcherUpdateStatus);
        pin = findViewById(R.id.launcherUpdatePin);
        address = findViewById(R.id.launcherUpdateAddress);
        phoneHint = findViewById(R.id.launcherUpdatePhoneHint);
        allowSources = findViewById(R.id.launcherUpdateAllowSources);
        newSession = findViewById(R.id.launcherUpdateNewSession);
        qr = findViewById(R.id.launcherUpdateQr);

        findViewById(R.id.launcherUpdateBack).setOnClickListener(view -> finish());
        allowSources.setOnClickListener(view -> openUnknownSourcesSettings());
        newSession.setOnClickListener(view -> {
            deletePendingFile();
            pendingInstall = null;
            expectedVersionCode = 0;
            startUpdateSession();
        });

        startUpdateSession();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (server != null) {
            handler.removeCallbacks(sessionWatchdog);
            handler.postDelayed(sessionWatchdog, 1000);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForUnknownSources) handleUnknownSourcesReturn();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        handler.removeCallbacks(sessionWatchdog);
        stopServer();
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UNKNOWN_SOURCES_REQUEST) {
            handleUnknownSourcesReturn();
        } else if (requestCode == INSTALL_REQUEST) {
            handleInstallResult(resultCode);
        }
    }

    @Override
    public void onCandidatesChanged(List<LauncherUpdateServer.CandidateSnapshot> candidates) {
        handler.post(() -> updateCandidateStatus(candidates));
    }

    @Override
    public void onInstallRequested(LauncherUpdateServer.InstallCandidate candidate) {
        handler.post(() -> beginInstall(candidate));
    }

    private void startUpdateSession() {
        stopServer();
        qr.setImageDrawable(null);
        qr.setVisibility(View.GONE);
        pin.setVisibility(View.GONE);
        address.setVisibility(View.GONE);
        phoneHint.setVisibility(View.GONE);
        allowSources.setVisibility(View.GONE);
        newSession.setVisibility(View.GONE);
        title.setText(R.string.launcher_update_title);
        description.setVisibility(View.VISIBLE);

        try {
            LauncherUpdateNetwork.Endpoint wifi = LauncherUpdateNetwork.currentWifi(this);
            currentSessionPin = LauncherUpdateServer.newPin();
            server = new LauncherUpdateServer(this, wifi,
                    LauncherUpdateServer.newToken(), currentSessionPin, this);
            server.startSession();
            Bitmap qrBitmap = createQrCode(server.getPageUrl());
            qr.setImageBitmap(qrBitmap);
            qr.setVisibility(View.VISIBLE);
            pin.setText(getString(R.string.launcher_update_pin_format, currentPin()));
            address.setText(getString(R.string.launcher_update_address_format, server.getPageUrl()));
            pin.setVisibility(View.VISIBLE);
            address.setVisibility(View.VISIBLE);
            phoneHint.setVisibility(View.VISIBLE);
            newSession.setVisibility(View.VISIBLE);
            status.setText(R.string.launcher_update_status_waiting);
            handler.removeCallbacks(sessionWatchdog);
            handler.postDelayed(sessionWatchdog, 1000);
            newSession.requestFocus();
        } catch (IOException | WriterException | SecurityException exception) {
            Log.e(TAG, "Could not start the local launcher update session", exception);
            stopServer();
            status.setText(R.string.launcher_update_status_error);
            newSession.setVisibility(View.VISIBLE);
            newSession.requestFocus();
        }
    }

    private String currentPin() {
        return currentSessionPin;
    }

    private Bitmap createQrCode(String value) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(
                value, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private void updateCandidateStatus(List<LauncherUpdateServer.CandidateSnapshot> candidates) {
        if (isFinishing() || isDestroyed() || server == null) return;
        int ready = 0;
        int errors = 0;
        boolean working = false;
        boolean installing = false;
        for (LauncherUpdateServer.CandidateSnapshot candidate : candidates) {
            switch (candidate.status) {
                case READY:
                    ready++;
                    break;
                case ERROR:
                    errors++;
                    break;
                case QUEUED:
                case DOWNLOADING:
                    working = true;
                    break;
                case INSTALLING:
                    installing = true;
                    break;
            }
        }
        if (installing) {
            status.setText(R.string.launcher_update_status_installing);
        } else if (working) {
            status.setText(R.string.launcher_update_status_working);
        } else if (ready > 0) {
            status.setText(getString(R.string.launcher_update_status_ready, ready));
        } else if (!candidates.isEmpty() && errors == candidates.size()) {
            status.setText(R.string.launcher_update_web_no_candidates);
        }
    }

    private void beginInstall(LauncherUpdateServer.InstallCandidate candidate) {
        if (!activityStarted || isFinishing() || isDestroyed()) return;
        pendingInstall = candidate;
        expectedVersionCode = candidate.verifiedApk.versionCode;
        installedVersionBefore = installedVersionCode();
        stopServer();
        qr.setVisibility(View.GONE);
        pin.setVisibility(View.GONE);
        address.setVisibility(View.GONE);
        phoneHint.setVisibility(View.GONE);
        newSession.setVisibility(View.GONE);
        status.setText(R.string.launcher_update_status_installing);
        if (!canRequestPackageInstalls()) {
            showUnknownSourcesRequired();
            openUnknownSourcesSettings();
            return;
        }
        launchAndroidInstaller();
    }

    private void launchAndroidInstaller() {
        if (pendingInstall == null) return;
        if (!canRequestPackageInstalls()) {
            showUnknownSourcesRequired();
            openUnknownSourcesSettings();
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".launcherupdate.files", pendingInstall.file);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(install, INSTALL_REQUEST);
        } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException exception) {
            Log.e(TAG, "Could not open the verified launcher APK in Android's installer", exception);
            status.setText(R.string.launcher_update_install_failed);
            deletePendingFile();
            pendingInstall = null;
            newSession.setVisibility(View.VISIBLE);
            newSession.requestFocus();
        }
    }

    private void handleInstallResult(int resultCode) {
        long installedVersion = installedVersionCode();
        boolean versionMatches = installedVersion >= expectedVersionCode;
        boolean installed = versionMatches
                && (resultCode == RESULT_OK || installedVersion > installedVersionBefore);
        deletePendingFile();
        pendingInstall = null;
        expectedVersionCode = 0;
        if (installed) {
            status.setText(R.string.launcher_update_install_success);
            handler.postDelayed(this::reopenLauncher, 900);
        } else {
            status.setText(R.string.launcher_update_install_cancelled);
            newSession.setVisibility(View.VISIBLE);
            newSession.requestFocus();
        }
    }

    private long installedVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException exception) {
            Log.e(TAG, "The installed launcher package could not be found", exception);
            return -1;
        }
    }

    private void reopenLauncher() {
        if (isFinishing() || isDestroyed()) return;
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(launch);
        finish();
    }

    private void showUnknownSourcesRequired() {
        title.setText(R.string.launcher_update_install_source_title);
        status.setText(R.string.launcher_update_install_source_message);
        description.setVisibility(View.GONE);
        qr.setVisibility(View.GONE);
        pin.setVisibility(View.GONE);
        address.setVisibility(View.GONE);
        phoneHint.setVisibility(View.GONE);
        allowSources.setVisibility(View.VISIBLE);
        newSession.setVisibility(View.VISIBLE);
        allowSources.requestFocus();
    }

    private void openUnknownSourcesSettings() {
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        try {
            waitingForUnknownSources = true;
            startActivityForResult(settings, UNKNOWN_SOURCES_REQUEST);
        } catch (ActivityNotFoundException | SecurityException exception) {
            waitingForUnknownSources = false;
            Log.e(TAG, "Could not open Android's unknown app source setting", exception);
            Toast.makeText(this, R.string.launcher_update_install_settings_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void handleUnknownSourcesReturn() {
        if (!waitingForUnknownSources) return;
        waitingForUnknownSources = false;
        if (!canRequestPackageInstalls()) {
            showUnknownSourcesRequired();
            return;
        }
        if (pendingInstall != null) {
            launchAndroidInstaller();
        } else {
            startUpdateSession();
        }
    }

    private boolean canRequestPackageInstalls() {
        return getPackageManager().canRequestPackageInstalls();
    }

    private void showSessionExpired() {
        qr.setVisibility(View.GONE);
        pin.setVisibility(View.GONE);
        address.setVisibility(View.GONE);
        phoneHint.setVisibility(View.GONE);
        status.setText(R.string.launcher_update_status_expired);
        newSession.setVisibility(View.VISIBLE);
        newSession.requestFocus();
    }

    private void stopServer() {
        if (server == null) return;
        server.stop();
        server = null;
    }

    private void deletePendingFile() {
        if (pendingInstall != null) {
            File file = pendingInstall.file;
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not remove the temporary verified APK from cache");
            }
        }
    }
}
