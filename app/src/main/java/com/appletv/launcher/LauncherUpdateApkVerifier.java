package com.appletv.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class LauncherUpdateApkVerifier {
    private LauncherUpdateApkVerifier() {
    }

    static VerifiedApk verify(Context context, File apk) throws IOException {
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            throw new IOException("The downloaded APK is empty or missing.");
        }

        PackageManager packageManager = context.getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;
        PackageInfo candidate = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || candidate.packageName == null) {
            throw new IOException("The downloaded file is not a valid Android APK.");
        }
        if (!context.getPackageName().equals(candidate.packageName)) {
            throw new IOException("This APK is not an update for the Apple TV Launcher.");
        }

        PackageInfo installed;
        try {
            installed = packageManager.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IOException("Could not verify the installed launcher.", exception);
        }

        long candidateVersion = candidate.getLongVersionCode();
        long installedVersion = installed.getLongVersionCode();
        if (candidateVersion < installedVersion) {
            throw new IOException("This APK is older than the installed launcher.");
        }

        List<String> candidateSigners = signerFingerprints(candidate);
        List<String> installedSigners = signerFingerprints(installed);
        if (candidateSigners.isEmpty() || !candidateSigners.equals(installedSigners)) {
            throw new IOException("The APK signing certificate does not match this launcher.");
        }

        String versionName = candidate.versionName;
        if (versionName == null || versionName.trim().isEmpty()) versionName = "unknown";
        return new VerifiedApk(
                candidate.packageName,
                versionName,
                candidateVersion,
                apk.length(),
                sha256(apk),
                candidateSigners.get(0));
    }

    private static List<String> signerFingerprints(PackageInfo packageInfo) throws IOException {
        Signature[] signatures = null;
        SigningInfo signingInfo = packageInfo.signingInfo;
        if (signingInfo != null) signatures = signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) signatures = packageInfo.signatures;
        if (signatures == null || signatures.length == 0) {
            throw new IOException("The APK has no verifiable signing certificate.");
        }
        List<String> fingerprints = new ArrayList<>();
        for (Signature signature : signatures) {
            fingerprints.add(sha256(signature.toByteArray()));
        }
        Collections.sort(fingerprints);
        return fingerprints;
    }

    private static String sha256(File file) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable on this device.", exception);
        }
    }

    private static String sha256(byte[] input) throws IOException {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable on this device.", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02X", value & 0xFF));
        }
        return result.toString();
    }

    static final class VerifiedApk {
        final String packageName;
        final String versionName;
        final long versionCode;
        final long sizeBytes;
        final String apkSha256;
        final String signerSha256;

        VerifiedApk(String packageName, String versionName, long versionCode,
                long sizeBytes, String apkSha256, String signerSha256) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.sizeBytes = sizeBytes;
            this.apkSha256 = apkSha256;
            this.signerSha256 = signerSha256;
        }
    }
}
