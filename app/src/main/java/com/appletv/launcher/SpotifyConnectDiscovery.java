package com.appletv.launcher;

import android.content.Context;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class SpotifyConnectDiscovery {
    private static final String TAG = "AppleTv.SpotifyDiscovery";
    private static final String SERVICE_TYPE = "_spotify-connect._tcp.";
    private static final int DISCOVERY_WINDOW_MS = 1400;
    private static final int DISCOVERY_START_TIMEOUT_MS = 1500;
    private static final int RESOLVE_TIMEOUT_MS = 1000;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 1500;
    private static final int MAX_SERVICES = 16;
    private static final int MAX_RESPONSE_BYTES = 16384;

    private SpotifyConnectDiscovery() {
    }

    static List<SpotifyConnectClient.Device> mergeLocalDevices(
            Context context, List<SpotifyConnectClient.Device> apiDevices) {
        return SpotifyConnectClient.mergeDevices(apiDevices, discoverLocalDevices(context));
    }

    static boolean isCurrentTvDevice(Context context, String targetDeviceId) {
        if (targetDeviceId == null || targetDeviceId.trim().isEmpty()) return false;
        String currentAddress = currentWifiAddress(context);
        if (currentAddress == null) return false;
        for (SpotifyConnectClient.Device device : discoverLocalDevices(context)) {
            if (targetDeviceId.equals(device.id)
                    && currentAddress.equals(device.hostAddress)) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static String currentWifiAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return null;
        int address = wifiManager.getConnectionInfo().getIpAddress();
        if (address == 0) return null;
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                address & 0xFF, address >> 8 & 0xFF,
                address >> 16 & 0xFF, address >> 24 & 0xFF);
    }

    private static List<SpotifyConnectClient.Device> discoverLocalDevices(Context context) {
        Context appContext = context.getApplicationContext();
        NsdManager manager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        if (manager == null) {
            Log.w(TAG, "Local device discovery is unavailable on this device");
            return Collections.emptyList();
        }

        WifiManager wifiManager = (WifiManager) appContext.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = wifiManager == null
                ? null : wifiManager.createMulticastLock(TAG);
        List<SpotifyConnectClient.Device> devices = new ArrayList<>();
        Set<String> deviceIds = new HashSet<>();
        try {
            if (multicastLock != null) {
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            List<NsdServiceInfo> services = discoverServices(manager);
            Log.i(TAG, "Discovered " + services.size()
                    + " local Spotify Connect service(s)");
            for (NsdServiceInfo service : services) {
                NsdServiceInfo resolved = resolveService(manager, service);
                if (resolved == null) continue;
                SpotifyConnectClient.Device device = readDeviceInfo(resolved);
                if (device != null && deviceIds.add(device.id)) {
                    devices.add(device);
                }
            }
            Log.i(TAG, "Returning " + devices.size()
                    + " local Spotify Connect device(s)");
        } catch (SecurityException exception) {
            Log.w(TAG, "Local Spotify Connect discovery was denied", exception);
        } finally {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        }
        return devices;
    }

    private static List<NsdServiceInfo> discoverServices(NsdManager manager) {
        Map<String, NsdServiceInfo> found = new LinkedHashMap<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                active.set(true);
                started.countDown();
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!isSpotifyConnectService(serviceInfo.getServiceType())) return;
                synchronized (found) {
                    if (found.size() < MAX_SERVICES
                            || found.containsKey(serviceInfo.getServiceName())) {
                        found.put(serviceInfo.getServiceName(), serviceInfo);
                    }
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                synchronized (found) {
                    found.remove(serviceInfo.getServiceName());
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                active.set(false);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                active.set(false);
                started.countDown();
                Log.w(TAG, "Local Spotify Connect discovery failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                active.set(false);
                Log.w(TAG, "Could not stop local Spotify Connect discovery: " + errorCode);
            }
        };

        boolean requested = false;
        try {
            manager.discoverServices(
                    SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            requested = true;
            if (!started.await(DISCOVERY_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    || !active.get()) {
                return Collections.emptyList();
            }
            Thread.sleep(DISCOVERY_WINDOW_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Local Spotify Connect discovery was interrupted");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            Log.w(TAG, "Could not start local Spotify Connect discovery", exception);
        } finally {
            if (requested) {
                try {
                    manager.stopServiceDiscovery(listener);
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    Log.d(TAG, "Local Spotify Connect discovery had already stopped");
                }
            }
        }

        synchronized (found) {
            return new ArrayList<>(found.values());
        }
    }

    private static boolean isSpotifyConnectService(String serviceType) {
        return SERVICE_TYPE.equalsIgnoreCase(serviceType)
                || SERVICE_TYPE.substring(0, SERVICE_TYPE.length() - 1)
                        .equalsIgnoreCase(serviceType);
    }

    private static NsdServiceInfo resolveService(NsdManager manager, NsdServiceInfo service) {
        CountDownLatch resolvedLatch = new CountDownLatch(1);
        AtomicReference<NsdServiceInfo> resolved = new AtomicReference<>();
        try {
            manager.resolveService(service, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "Could not resolve a local Spotify Connect device: " + errorCode);
                    resolvedLatch.countDown();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    resolved.set(serviceInfo);
                    resolvedLatch.countDown();
                }
            });
            if (!resolvedLatch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out resolving a local Spotify Connect device");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Resolving a local Spotify Connect device was interrupted");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            Log.w(TAG, "Could not resolve a local Spotify Connect device", exception);
        }
        return resolved.get();
    }

    private static SpotifyConnectClient.Device readDeviceInfo(NsdServiceInfo service) {
        InetAddress address = service.getHost();
        if (address == null || (!address.isSiteLocalAddress() && !address.isLinkLocalAddress())) {
            Log.w(TAG, "Ignoring local service with no private network address");
            return null;
        }
        int port = service.getPort();
        if (port < 1 || port > 65535) {
            Log.w(TAG, "Ignoring local service with an invalid port");
            return null;
        }

        Map<String, byte[]> attributes = service.getAttributes();
        String path = attribute(attributes, "CPath");
        String version = attribute(attributes, "VERSION");
        if (!"/spotifyzc".equals(path) && !"/zc".equals(path)) {
            Log.w(TAG, "Ignoring local service with an unsupported info path");
            return null;
        }
        if (version == null || version.isEmpty()) {
            Log.w(TAG, "Ignoring local service without a protocol version");
            return null;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            String hostHeader = address instanceof Inet6Address
                    ? "[" + address.getHostAddress().replace("%", "%25") + "]:" + port
                    : address.getHostAddress() + ":" + port;
            String request = "GET " + path + "?action=getInfo&version=" + Uri.encode(version)
                    + " HTTP/1.0\r\nHost: " + hostHeader
                    + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            LocalHttpResponse response = readHttpResponse(socket.getInputStream());
            if (response.statusCode != 200) {
                Log.w(TAG, "Local Spotify Connect info request returned HTTP "
                        + response.statusCode);
                return null;
            }
            JSONObject info = new JSONObject(response.body);
            String id = info.optString("deviceID", "").trim();
            if (id.isEmpty() || "null".equalsIgnoreCase(id)) {
                Log.w(TAG, "Local Spotify Connect info response has no device ID");
                return null;
            }

            String name = info.optString("remoteName", "").trim();
            if (name.isEmpty() || "null".equalsIgnoreCase(name)) {
                name = info.optString("modelDisplayName", "Spotify Connect speaker").trim();
            }
            String type = info.optString("deviceType", "speaker").trim();
            if (type.isEmpty() || "null".equalsIgnoreCase(type)) type = "speaker";
            return new SpotifyConnectClient.Device(
                    id, name, type, false, false, true, address.getHostAddress());
        } catch (IOException | JSONException exception) {
            Log.w(TAG, "Could not read local Spotify Connect device information", exception);
            return null;
        }
    }

    private static String attribute(Map<String, byte[]> attributes, String name) {
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                return new String(entry.getValue(), StandardCharsets.UTF_8).trim();
            }
        }
        return null;
    }

    static LocalHttpResponse readHttpResponse(InputStream input) throws IOException {
        byte[] response = readBoundedResponse(input);
        int headerEnd = findHeaderEnd(response);
        if (headerEnd < 0) throw new IOException("Incomplete local device HTTP response");

        String header = new String(response, 0, headerEnd, StandardCharsets.US_ASCII);
        String[] lines = header.split("\r\n");
        String[] statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2 || !statusParts[0].startsWith("HTTP/")) {
            throw new IOException("Invalid local device HTTP status line");
        }

        int statusCode;
        try {
            statusCode = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid local device HTTP status code", exception);
        }

        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator > 0 && "transfer-encoding".equalsIgnoreCase(
                    lines[i].substring(0, separator).trim())) {
                chunked = lines[i].substring(separator + 1).toLowerCase(Locale.ROOT)
                        .contains("chunked");
            }
        }

        byte[] body = new byte[response.length - headerEnd - 4];
        System.arraycopy(response, headerEnd + 4, body, 0, body.length);
        if (chunked) body = decodeChunkedBody(body);
        return new LocalHttpResponse(statusCode, new String(body, StandardCharsets.UTF_8));
    }

    private static int findHeaderEnd(byte[] response) {
        for (int i = 0; i <= response.length - 4; i++) {
            if (response[i] == '\r' && response[i + 1] == '\n'
                    && response[i + 2] == '\r' && response[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static byte[] decodeChunkedBody(byte[] body) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        while (true) {
            int lineEnd = findCrlf(body, offset);
            if (lineEnd < 0) throw new IOException("Invalid chunked local device response");
            String sizeLine = new String(
                    body, offset, lineEnd - offset, StandardCharsets.US_ASCII);
            int extension = sizeLine.indexOf(';');
            if (extension >= 0) sizeLine = sizeLine.substring(0, extension);

            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid local device response chunk size", exception);
            }
            if (chunkSize < 0 || chunkSize > MAX_RESPONSE_BYTES - decoded.size()) {
                throw new IOException("Chunked local device response exceeded the size limit");
            }
            if (chunkSize == 0) return decoded.toByteArray();

            int chunkStart = lineEnd + 2;
            int chunkEnd = chunkStart + chunkSize;
            if (chunkEnd + 2 > body.length
                    || body[chunkEnd] != '\r' || body[chunkEnd + 1] != '\n') {
                throw new IOException("Incomplete chunked local device response");
            }
            decoded.write(body, chunkStart, chunkSize);
            offset = chunkEnd + 2;
        }
    }

    private static int findCrlf(byte[] body, int start) {
        for (int i = start; i < body.length - 1; i++) {
            if (body[i] == '\r' && body[i + 1] == '\n') return i;
        }
        return -1;
    }

    private static byte[] readBoundedResponse(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Spotify Connect info response exceeded the size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static final class LocalHttpResponse {
        final int statusCode;
        final String body;

        LocalHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
