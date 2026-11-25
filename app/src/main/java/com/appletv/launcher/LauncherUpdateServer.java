package com.appletv.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class LauncherUpdateServer extends NanoHTTPD {
    static final int MAX_CANDIDATES = 5;
    static final long SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final String TAG = "LauncherUpdateServer";
    private static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";
    private static final String UPLOAD_PIN_HEADER = "x-launcher-update-pin";
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final long MAX_BATCH_BYTES = 250L * 1024L * 1024L;
    private static final SecureRandom RANDOM = new SecureRandom();

    interface Listener {
        void onCandidatesChanged(List<CandidateSnapshot> candidates);
        void onInstallRequested(InstallCandidate candidate);
        void onClientConnected();
    }

    private final Context context;
    private final LauncherUpdateNetwork.Endpoint wifi;
    private final String token;
    private final String pin;
    private final String html;
    private final Listener listener;
    private final File cacheDirectory;
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private final List<CandidateState> candidates = new ArrayList<>();
    private final Set<File> files = new HashSet<>();

    private long lastActivityAt = SystemClock.elapsedRealtime();
    private long downloadedBytes;
    private int failedPinAttempts;
    private boolean batchSubmitted;
    private boolean batchRunning;
    private boolean installRequested;
    private boolean clientConnected;
    private String installState = "none";
    private boolean expired;
    private boolean stopped;
    private File preservedFile;

    LauncherUpdateServer(Context context, LauncherUpdateNetwork.Endpoint wifi,
            String token, String pin, Listener listener) throws IOException {
        super(wifi.addressText(), 0);
        this.context = context;
        this.wifi = wifi;
        this.token = token;
        this.pin = pin;
        this.listener = listener;
        cacheDirectory = new File(this.context.getCacheDir(), "launcher-update-candidates");
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Could not create the private launcher update cache.");
        }
        html = renderWebPage(readWebPage(this.context));
    }

    static String newToken() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder token = new StringBuilder(random.length * 2);
        for (byte value : random) token.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xFF));
        return token.toString();
    }

    static String newPin() {
        return String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    void startSession() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        if (getListeningPort() <= 0) {
            stop();
            throw new IOException("The local update page could not start.");
        }
    }

    String getPageUrl() {
        return "http://" + wifi.addressText() + ":" + getListeningPort()
                + "/u/" + token;
    }

    boolean isExpired() {
        synchronized (lock) {
            if (!expired && !stopped && !batchRunning
                    && remainingSessionMillisLocked(SystemClock.elapsedRealtime()) == 0) {
                expired = true;
            }
            return expired || stopped;
        }
    }

    boolean isClientConnected() {
        synchronized (lock) {
            return clientConnected;
        }
    }

    long getRemainingSessionMillis() {
        synchronized (lock) {
            if (expired || stopped) return 0;
            if (batchRunning) return -1;
            return remainingSessionMillisLocked(SystemClock.elapsedRealtime());
        }
    }

    void setInstallResult(String result) {
        synchronized (lock) {
            installState = result;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (!isRemoteOnThisWifi(session.getRemoteIpAddress())) {
                return textResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT,
                        "This update page is limited to the TV Wi-Fi network.");
            }

            String basePath = "/u/" + token;
            String path = session.getUri();
            String apiPath = basePath + "/api";
            String uploadPath = basePath + "/upload";
            if (session.getMethod() == Method.GET && basePath.equals(path)) {
                if (isExpired()) return jsonResponse(Response.Status.BAD_REQUEST,
                        error(context.getString(R.string.launcher_update_web_expired)));
                return htmlResponse(html);
            }
            if (session.getMethod() != Method.POST
                    || (!apiPath.equals(path) && !uploadPath.equals(path))) {
                return textResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
            }
            if (isExpired()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                        error(context.getString(R.string.launcher_update_web_expired)));
            }
            if (!hasExpectedOrigin(session)) {
                return textResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid origin");
            }
            if (uploadPath.equals(path)) {
                String suppliedPin = session.getHeaders().get(UPLOAD_PIN_HEADER);
                if (!authenticate(suppliedPin == null ? "" : suppliedPin, true)) {
                    return jsonResponse(Response.Status.FORBIDDEN,
                            error(context.getString(R.string.launcher_update_web_bad_pin)));
                }
                return uploadCandidate(session);
            }
            JSONObject request = readJsonRequest(session);
            String suppliedPin = request.optString("pin", "");
            String action = request.optString("action", "");
            if (!authenticate(suppliedPin, !"status".equals(action))) {
                return jsonResponse(Response.Status.FORBIDDEN,
                        error(context.getString(R.string.launcher_update_web_bad_pin)));
            }
            if ("verify".equals(action)) {
                synchronized (lock) {
                    clientConnected = true;
                }
                if (listener != null) listener.onClientConnected();
                return jsonResponse(Response.Status.OK, new JSONObject().put("ok", true));
            }
            if ("submit".equals(action)) return submitCandidates(request);
            if ("status".equals(action)) return jsonResponse(Response.Status.OK, getStatus());
            if ("install".equals(action)) return requestInstall(request.optString("candidateId", ""));
            return jsonResponse(Response.Status.BAD_REQUEST,
                    error(context.getString(R.string.launcher_update_web_error)));
        } catch (NanoHTTPD.ResponseException exception) {
            Log.w(TAG, "Rejected a malformed local update request", exception);
            return jsonResponse(Response.Status.BAD_REQUEST,
                    error(context.getString(R.string.launcher_update_web_error)));
        } catch (IOException | JSONException exception) {
            Log.e(TAG, "Could not process a local launcher update request", exception);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    error(context.getString(R.string.launcher_update_web_error)));
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unexpected local launcher update request failure", exception);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    error(context.getString(R.string.launcher_update_web_error)));
        }
    }

    private boolean isRemoteOnThisWifi(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.trim().isEmpty()) return false;
        try {
            return LauncherUpdateNetwork.isSameSubnet(
                    java.net.InetAddress.getByName(remoteAddress), wifi)
                    && !wifi.address.equals(java.net.InetAddress.getByName(remoteAddress));
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean hasExpectedOrigin(IHTTPSession session) {
        String origin = session.getHeaders().get("origin");
        if (origin == null || origin.trim().isEmpty()) return true;
        return origin.equals("http://" + wifi.addressText() + ":" + getListeningPort());
    }

    private JSONObject readJsonRequest(IHTTPSession session)
            throws IOException, JSONException, NanoHTTPD.ResponseException {
        String contentType = session.getHeaders().get("content-type");
        if (contentType == null
                || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new IOException("Expected an application/json update request.");
        }
        String lengthValue = session.getHeaders().get("content-length");
        if (lengthValue == null) throw new IOException("The update request has no content length.");
        long length;
        try {
            length = Long.parseLong(lengthValue);
        } catch (NumberFormatException exception) {
            throw new IOException("The update request has an invalid content length.", exception);
        }
        if (length < 1 || length > MAX_REQUEST_BYTES) {
            throw new IOException("The update request exceeds the allowed size.");
        }
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new IOException("The update request body is missing or too large.");
        }
        return new JSONObject(body);
    }

    private boolean authenticate(String suppliedPin, boolean countAsUserActivity) {
        byte[] expected = pin.getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedPin.getBytes(StandardCharsets.US_ASCII);
        boolean matches = MessageDigest.isEqual(expected, supplied);
        synchronized (lock) {
            if (expired || stopped) return false;
            if (!matches) {
                failedPinAttempts++;
                if (failedPinAttempts >= 5) expired = true;
                return false;
            }
            if (countAsUserActivity) lastActivityAt = SystemClock.elapsedRealtime();
        }
        return true;
    }

    private Response submitCandidates(JSONObject request) throws JSONException, IOException {
        JSONArray urls = request.optJSONArray("urls");
        if (urls == null || urls.length() < 1 || urls.length() > MAX_CANDIDATES) {
            return jsonResponse(Response.Status.BAD_REQUEST,
                    error(context.getString(R.string.launcher_update_web_invalid_links)));
        }
        synchronized (lock) {
            if (batchSubmitted) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                        error(context.getString(R.string.launcher_update_web_already_submitted)));
            }
            batchSubmitted = true;
        }

        List<CandidateState> accepted = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        for (int i = 0; i < urls.length(); i++) {
            String rawUrl = urls.optString(i, "").trim();
            CandidateState candidate = new CandidateState(Integer.toString(i + 1), rawUrl);
            if (rawUrl.isEmpty() || !duplicates.add(rawUrl)) {
                candidate.status = CandidateStatus.ERROR;
                candidate.message = context.getString(R.string.launcher_update_web_invalid_link);
            } else {
                try {
                    LauncherUpdateUrlPolicy.parse(rawUrl, wifi);
                    candidate.status = CandidateStatus.QUEUED;
                    candidate.message = context.getString(R.string.launcher_update_web_queued);
                    accepted.add(candidate);
                } catch (IOException exception) {
                    candidate.status = CandidateStatus.ERROR;
                    candidate.message = context.getString(
                            R.string.launcher_update_web_invalid_link);
                }
            }
            synchronized (lock) {
                candidates.add(candidate);
            }
        }
        synchronized (lock) {
            batchRunning = !accepted.isEmpty();
            lastActivityAt = SystemClock.elapsedRealtime();
        }
        notifyCandidatesChanged();
        if (!accepted.isEmpty()) {
            downloadExecutor.execute(() -> downloadCandidates(accepted));
        } else {
            synchronized (lock) {
                lastActivityAt = SystemClock.elapsedRealtime();
            }
        }
        return jsonResponse(Response.Status.OK, getStatus());
    }

    private Response uploadCandidate(IHTTPSession session) throws JSONException {
        long contentLength;
        try {
            contentLength = validateUploadRequest(session);
        } catch (IOException exception) {
            return jsonResponse(Response.Status.BAD_REQUEST,
                    error(context.getString(R.string.launcher_update_web_upload_invalid)));
        }

        CandidateState candidate = new CandidateState("1", "");
        candidate.status = CandidateStatus.DOWNLOADING;
        candidate.message = context.getString(R.string.launcher_update_web_downloading);
        synchronized (lock) {
            if (batchSubmitted) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                        error(context.getString(R.string.launcher_update_web_already_submitted)));
            }
            batchSubmitted = true;
            batchRunning = true;
            lastActivityAt = SystemClock.elapsedRealtime();
            candidates.add(candidate);
        }
        notifyCandidatesChanged();

        final File apk;
        try {
            apk = receiveUploadedApk(session, contentLength);
        } catch (IOException exception) {
            Log.w(TAG, "Could not receive a launcher APK from the phone", exception);
            finishUploadedCandidate(candidate, null);
            return jsonResponse(Response.Status.OK, getStatus());
        }

        boolean unavailable;
        synchronized (lock) {
            unavailable = stopped || expired;
            if (unavailable) {
                files.remove(apk);
                candidate.status = CandidateStatus.ERROR;
                candidate.message = context.getString(R.string.launcher_update_web_expired);
                batchRunning = false;
                lastActivityAt = SystemClock.elapsedRealtime();
            } else {
                downloadedBytes += apk.length();
            }
        }
        if (unavailable) {
            if (apk.exists() && !apk.delete()) {
                Log.w(TAG, "Could not remove an APK uploaded after its update session ended.");
            }
            notifyCandidatesChanged();
            return jsonResponse(Response.Status.BAD_REQUEST,
                    error(context.getString(R.string.launcher_update_web_expired)));
        }

        try {
            downloadExecutor.execute(() -> verifyUploadedCandidate(candidate, apk));
        } catch (RejectedExecutionException exception) {
            Log.w(TAG, "Could not queue the uploaded launcher APK for verification", exception);
            finishUploadedCandidate(candidate, apk);
        }
        return jsonResponse(Response.Status.OK, getStatus());
    }

    private long validateUploadRequest(IHTTPSession session) throws IOException {
        Map<String, String> headers = session.getHeaders();
        String contentType = headers.get("content-type");
        if (contentType == null || !APK_CONTENT_TYPE.equalsIgnoreCase(contentType.trim())) {
            throw new IOException("The upload must contain an Android APK.");
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new IOException("Chunked APK uploads are not supported.");
        }
        String lengthValue = headers.get("content-length");
        if (lengthValue == null) throw new IOException("The APK upload has no content length.");

        long length;
        try {
            length = Long.parseLong(lengthValue);
        } catch (NumberFormatException exception) {
            throw new IOException("The APK upload has an invalid content length.", exception);
        }
        long remaining;
        synchronized (lock) {
            remaining = MAX_BATCH_BYTES - downloadedBytes;
        }
        long maximum = Math.min(LauncherUpdateDownloader.MAX_APK_BYTES, remaining);
        if (length < 1 || length > maximum) {
            throw new IOException("The APK upload is empty or exceeds the size limit.");
        }
        return length;
    }

    private File receiveUploadedApk(IHTTPSession session, long contentLength) throws IOException {
        File apk = File.createTempFile("candidate-", ".apk", cacheDirectory);
        synchronized (lock) {
            files.add(apk);
        }
        try {
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(apk))) {
                InputStream input = session.getInputStream();
                byte[] buffer = new byte[32 * 1024];
                long remaining = contentLength;
                while (remaining > 0) {
                    int count = input.read(buffer, 0,
                            (int) Math.min((long) buffer.length, remaining));
                    if (count < 0) {
                        throw new IOException("The APK upload ended before its declared length.");
                    }
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            return apk;
        } catch (IOException exception) {
            synchronized (lock) {
                files.remove(apk);
            }
            if (apk.exists() && !apk.delete()) {
                Log.w(TAG, "Could not remove a partial launcher APK upload.");
            }
            throw exception;
        }
    }

    private void verifyUploadedCandidate(CandidateState candidate, File apk) {
        try {
            LauncherUpdateApkVerifier.VerifiedApk verified =
                    LauncherUpdateApkVerifier.verify(context, apk);
            boolean discard;
            synchronized (lock) {
                discard = stopped || expired;
                if (discard) {
                    files.remove(apk);
                    candidate.status = CandidateStatus.ERROR;
                    candidate.message = context.getString(R.string.launcher_update_web_expired);
                } else {
                    candidate.file = apk;
                    candidate.verifiedApk = verified;
                    candidate.status = CandidateStatus.READY;
                    candidate.message = context.getString(R.string.launcher_update_web_ready,
                            verified.versionName, verified.versionCode);
                }
                batchRunning = false;
                lastActivityAt = SystemClock.elapsedRealtime();
            }
            if (discard && apk.exists() && !apk.delete()) {
                Log.w(TAG, "Could not remove an APK after its update session ended.");
            }
        } catch (IOException | SecurityException exception) {
            Log.w(TAG, "The uploaded launcher APK could not be verified", exception);
            finishUploadedCandidate(candidate, apk);
            return;
        }
        notifyCandidatesChanged();
    }

    private void finishUploadedCandidate(CandidateState candidate, File apk) {
        if (apk != null) {
            synchronized (lock) {
                files.remove(apk);
            }
            if (apk.exists() && !apk.delete()) {
                Log.w(TAG, "Could not remove a rejected launcher APK upload.");
            }
        }
        synchronized (lock) {
            candidate.status = CandidateStatus.ERROR;
            candidate.message = context.getString(R.string.launcher_update_web_upload_failed);
            batchRunning = false;
            lastActivityAt = SystemClock.elapsedRealtime();
        }
        notifyCandidatesChanged();
    }

    private void downloadCandidates(List<CandidateState> accepted) {
        for (CandidateState candidate : accepted) {
            synchronized (lock) {
                if (stopped || expired) break;
                candidate.status = CandidateStatus.DOWNLOADING;
                candidate.message = context.getString(R.string.launcher_update_web_downloading);
            }
            notifyCandidatesChanged();

            File apk = null;
            boolean sizeCounted = false;
            try {
                long remaining;
                synchronized (lock) {
                    remaining = MAX_BATCH_BYTES - downloadedBytes;
                }
                if (remaining <= 0) {
                    throw new IOException("The 250 MiB total session download limit was reached.");
                }
                long sizeLimit = Math.min(LauncherUpdateDownloader.MAX_APK_BYTES, remaining);
                apk = File.createTempFile("candidate-", ".apk", cacheDirectory);
                synchronized (lock) {
                    files.add(apk);
                }
                LauncherUpdateDownloader.download(candidate.url, wifi, apk, sizeLimit);
                long size = apk.length();
                synchronized (lock) {
                    downloadedBytes += size;
                }
                sizeCounted = true;
                LauncherUpdateApkVerifier.VerifiedApk verified =
                        LauncherUpdateApkVerifier.verify(context, apk);
                synchronized (lock) {
                    if (stopped || expired) {
                        apk.delete();
                        files.remove(apk);
                        candidate.status = CandidateStatus.ERROR;
                        candidate.message = context.getString(R.string.launcher_update_web_expired);
                    } else {
                        candidate.file = apk;
                        candidate.verifiedApk = verified;
                        candidate.status = CandidateStatus.READY;
                        candidate.message = context.getString(
                                R.string.launcher_update_web_ready,
                                verified.versionName, verified.versionCode);
                    }
                }
            } catch (IOException | SecurityException exception) {
                Log.w(TAG, "A launcher update candidate could not be verified", exception);
                if (apk != null) {
                    synchronized (lock) {
                        if (!sizeCounted) downloadedBytes += apk.length();
                        files.remove(apk);
                    }
                    apk.delete();
                }
                synchronized (lock) {
                    candidate.status = CandidateStatus.ERROR;
                    candidate.message = context.getString(
                            R.string.launcher_update_web_candidate_failed);
                }
            }
            notifyCandidatesChanged();
        }
        synchronized (lock) {
            batchRunning = false;
            lastActivityAt = SystemClock.elapsedRealtime();
        }
        notifyCandidatesChanged();
    }

    private Response requestInstall(String candidateId) throws JSONException {
        InstallCandidate selected;
        synchronized (lock) {
            if (installRequested || batchRunning || expired || stopped) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                        error(context.getString(R.string.launcher_update_web_not_ready)));
            }
            CandidateState candidate = findCandidate(candidateId);
            if (candidate == null || candidate.status != CandidateStatus.READY
                    || candidate.file == null || candidate.verifiedApk == null) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                        error(context.getString(R.string.launcher_update_web_not_ready)));
            }
            installRequested = true;
            installState = "pending";
            candidate.status = CandidateStatus.INSTALLING;
            candidate.message = context.getString(R.string.launcher_update_web_installing);
            preservedFile = candidate.file;
            selected = new InstallCandidate(candidate.file, candidate.verifiedApk);
            lastActivityAt = SystemClock.elapsedRealtime();
        }
        notifyCandidatesChanged();
        listener.onInstallRequested(selected);
        return jsonResponse(Response.Status.OK, new JSONObject()
                .put("ok", true)
                .put("message", context.getString(R.string.launcher_update_web_install_sent)));
    }

    private CandidateState findCandidate(String id) {
        for (CandidateState candidate : candidates) {
            if (candidate.id.equals(id)) return candidate;
        }
        return null;
    }

    private JSONObject getStatus() throws JSONException {
        JSONArray items = new JSONArray();
        synchronized (lock) {
            for (CandidateState candidate : candidates) items.put(candidate.toJson());
            long now = SystemClock.elapsedRealtime();
            return new JSONObject()
                    .put("ok", true)
                    .put("expired", expired || stopped)
                    .put("connected", clientConnected)
                    .put("remainingMs", expired || stopped ? 0
                            : batchRunning ? -1 : remainingSessionMillisLocked(now))
                    .put("installState", installState)
                    .put("submitted", batchSubmitted)
                    .put("working", batchRunning)
                    .put("candidates", items);
        }
    }

    private long remainingSessionMillisLocked(long now) {
        long idleMillis = now - lastActivityAt;
        return Math.max(0, SESSION_IDLE_TIMEOUT_MS - idleMillis);
    }

    private JSONObject error(String message) {
        try {
            return new JSONObject().put("ok", false).put("message", message);
        } catch (JSONException exception) {
            throw new IllegalStateException("Could not encode an updater error response.", exception);
        }
    }

    private void notifyCandidatesChanged() {
        if (listener == null || isStopped()) return;
        List<CandidateSnapshot> snapshots = new ArrayList<>();
        synchronized (lock) {
            for (CandidateState candidate : candidates) {
                snapshots.add(new CandidateSnapshot(candidate.status, candidate.message,
                        candidate.verifiedApk));
            }
        }
        listener.onCandidatesChanged(snapshots);
    }

    private boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
            expired = true;
        }
        super.stop();
        downloadExecutor.shutdownNow();
        synchronized (lock) {
            for (File file : files) {
                if (!file.equals(preservedFile)) file.delete();
            }
            files.clear();
        }
    }

    private Response htmlResponse(String body) {
        Response response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body);
        addSecurityHeaders(response, true);
        return response;
    }

    private Response jsonResponse(Response.Status status, JSONObject body) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8",
                body.toString());
        addSecurityHeaders(response, false);
        return response;
    }

    private Response textResponse(Response.Status status, String type, String body) {
        Response response = newFixedLengthResponse(status, type, body);
        addSecurityHeaders(response, false);
        return response;
    }

    private void addSecurityHeaders(Response response, boolean htmlPage) {
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("Content-Security-Policy", htmlPage
                ? "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
                : "default-src 'none'; frame-ancestors 'none'");
    }

    private String renderWebPage(String template) {
        String page = template;
        page = replace(page, "TITLE", R.string.launcher_update_web_title);
        page = replace(page, "INTRO", R.string.launcher_update_web_intro);
        page = replace(page, "PIN_LABEL", R.string.launcher_update_web_pin);
        page = replace(page, "LINKS_LABEL", R.string.launcher_update_web_links);
        page = replace(page, "LINKS_HINT", R.string.launcher_update_web_links_hint);
        page = replace(page, "UPLOAD_OR", R.string.launcher_update_web_upload_or);
        page = replace(page, "UPLOAD_LABEL", R.string.launcher_update_web_upload_label);
        page = replace(page, "UPLOAD_HINT", R.string.launcher_update_web_upload_hint);
        page = replace(page, "UPLOAD_BUTTON", R.string.launcher_update_web_upload_button);
        page = replace(page, "UPLOAD_PROGRESS", R.string.launcher_update_web_upload_progress);
        page = replace(page, "UPLOAD_INVALID", R.string.launcher_update_web_upload_invalid);
        page = replace(page, "UPLOAD_FAILED", R.string.launcher_update_web_upload_failed);
        page = replace(page, "VERIFY_BUTTON", R.string.launcher_update_web_verify);
        page = replace(page, "SUBMIT_BUTTON", R.string.launcher_update_web_check);
        page = replace(page, "CANDIDATES_TITLE", R.string.launcher_update_web_candidates);
        page = replace(page, "INSTALL_BUTTON", R.string.launcher_update_web_install);
        page = replace(page, "ENTER_PIN", R.string.launcher_update_web_enter_pin);
        page = replace(page, "BAD_PIN", R.string.launcher_update_web_bad_pin);
        page = replace(page, "EXPIRED", R.string.launcher_update_web_expired);
        page = replace(page, "VERIFYING", R.string.launcher_update_web_verifying);
        page = replace(page, "NO_CANDIDATES", R.string.launcher_update_web_no_candidates);
        page = replace(page, "INSTALL_SENT", R.string.launcher_update_web_install_sent);
        page = replace(page, "VERSION_LABEL", R.string.launcher_update_web_version);
        page = replace(page, "HASH_LABEL", R.string.launcher_update_web_hash);
        page = replace(page, "SIGNER_LABEL", R.string.launcher_update_web_signer);
        page = replace(page, "ERROR", R.string.launcher_update_web_error);
        page = replace(page, "CONNECTION_LOST", R.string.launcher_update_web_connection_lost);
        page = replace(page, "READY", R.string.launcher_update_web_ready_generic);
        page = replace(page, "QUEUED", R.string.launcher_update_web_queued);
        page = replace(page, "DOWNLOADING", R.string.launcher_update_web_downloading);
        page = replace(page, "INSTALLING", R.string.launcher_update_web_installing);
        page = replace(page, "CONNECTED", R.string.launcher_update_web_connected);
        page = replace(page, "WAITING", R.string.launcher_update_web_waiting);
        page = replace(page, "COUNTDOWN", R.string.launcher_update_web_countdown);
        page = replace(page, "PROCESSING", R.string.launcher_update_web_processing);
        page = replace(page, "INSTALL_UNKNOWN", R.string.launcher_update_web_install_unknown);
        page = replace(page, "INSTALL_COMPLETE", R.string.launcher_update_web_install_complete);
        page = replace(page, "INSTALL_CANCELLED", R.string.launcher_update_web_install_cancelled);
        page = replace(page, "INSTALL_FAILED", R.string.launcher_update_web_install_failed);
        page = replace(page, "FOOTER", R.string.launcher_update_web_footer);
        page = page.replace("{{MAX_APK_BYTES}}",
                Long.toString(LauncherUpdateDownloader.MAX_APK_BYTES));
        return page;
    }

    private String replace(String page, String key, int resourceId) {
        return page.replace("{{" + key + "}}",
                JSONObject.quote(context.getString(resourceId)));
    }

    private static String readWebPage(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("launcher_update.html");
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static final class CandidateSnapshot {
        final CandidateStatus status;
        final String message;
        final LauncherUpdateApkVerifier.VerifiedApk verifiedApk;

        CandidateSnapshot(CandidateStatus status, String message,
                LauncherUpdateApkVerifier.VerifiedApk verifiedApk) {
            this.status = status;
            this.message = message;
            this.verifiedApk = verifiedApk;
        }
    }

    static final class InstallCandidate {
        final File file;
        final LauncherUpdateApkVerifier.VerifiedApk verifiedApk;

        InstallCandidate(File file, LauncherUpdateApkVerifier.VerifiedApk verifiedApk) {
            this.file = file;
            this.verifiedApk = verifiedApk;
        }
    }

    private static final class CandidateState {
        final String id;
        final String url;
        CandidateStatus status = CandidateStatus.QUEUED;
        String message;
        File file;
        LauncherUpdateApkVerifier.VerifiedApk verifiedApk;

        CandidateState(String id, String url) {
            this.id = id;
            this.url = url;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject()
                    .put("id", id)
                    .put("status", status.name().toLowerCase(java.util.Locale.ROOT))
                    .put("message", message == null ? "" : message);
            if (verifiedApk != null) {
                result.put("versionName", verifiedApk.versionName)
                        .put("versionCode", verifiedApk.versionCode)
                        .put("size", verifiedApk.sizeBytes)
                        .put("apkSha256", verifiedApk.apkSha256)
                        .put("signerSha256", verifiedApk.signerSha256);
            }
            return result;
        }
    }

    enum CandidateStatus {
        QUEUED,
        DOWNLOADING,
        READY,
        ERROR,
        INSTALLING
    }
}
