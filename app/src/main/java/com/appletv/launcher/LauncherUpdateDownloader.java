package com.appletv.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

final class LauncherUpdateDownloader {
    static final long MAX_APK_BYTES = 100L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int BUFFER_SIZE = 32 * 1024;

    private LauncherUpdateDownloader() {
    }

    static void download(String input, LauncherUpdateNetwork.Endpoint wifi,
            File destination, long maxBytes)
            throws IOException {
        if (maxBytes <= 0 || maxBytes > MAX_APK_BYTES) {
            throw new IOException("The update download limit is invalid.");
        }
        URI current = LauncherUpdateUrlPolicy.parse(input, wifi);
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            LauncherUpdateUrlPolicy.validateResolvedAddresses(current, wifi);
            if ("https".equalsIgnoreCase(current.getScheme())) {
                DownloadResponse response = openHttps(current);
                if (isRedirect(response.statusCode)) {
                    String location = response.header("location");
                    response.close();
                    if (redirectCount == MAX_REDIRECTS) {
                        throw new IOException("The update link redirected too many times.");
                    }
                    current = LauncherUpdateUrlPolicy.resolveRedirect(current, location, wifi);
                    continue;
                }
                if (response.statusCode != HttpURLConnection.HTTP_OK) {
                    response.close();
                    throw new IOException("The APK server returned HTTP " + response.statusCode + ".");
                }
                try (DownloadResponse accepted = response) {
                    copyBounded(accepted.body, destination,
                            accepted.contentLength(maxBytes), maxBytes);
                }
                return;
            }

            LocalHttpResponse response = openLocalHttp(current, wifi);
            if (isRedirect(response.statusCode)) {
                String location = response.headers.get("location");
                response.close();
                if (redirectCount == MAX_REDIRECTS) {
                    throw new IOException("The update link redirected too many times.");
                }
                current = LauncherUpdateUrlPolicy.resolveRedirect(current, location, wifi);
                continue;
            }
            if (response.statusCode != HttpURLConnection.HTTP_OK) {
                response.close();
                throw new IOException("The local APK server returned HTTP "
                        + response.statusCode + ".");
            }
            try (LocalHttpResponse accepted = response) {
                String contentEncoding = accepted.headers.get("content-encoding");
                if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding)) {
                    throw new IOException("Compressed APK responses are not supported.");
                }
                long length = parseContentLength(accepted.headers.get("content-length"));
                if (length > maxBytes) {
                    throw new IOException("The APK exceeds the configured download limit.");
                }
                String transferEncoding = accepted.headers.get("transfer-encoding");
                if (transferEncoding != null
                        && !transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
                    throw new IOException("The local APK server used an unsupported transfer encoding.");
                }
                if (transferEncoding != null) {
                    copyChunked(accepted.body, destination, maxBytes);
                } else {
                    copyBounded(accepted.body, destination, length, maxBytes);
                }
            }
            return;
        }
        throw new IOException("The update link could not be downloaded.");
    }

    private static DownloadResponse openHttps(URI uri) throws IOException {
        URLConnection connection = uri.toURL().openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            throw new IOException("The update link did not use HTTPS.");
        }
        HttpsURLConnection https = (HttpsURLConnection) connection;
        https.setInstanceFollowRedirects(false);
        https.setConnectTimeout(CONNECT_TIMEOUT_MS);
        https.setReadTimeout(READ_TIMEOUT_MS);
        https.setRequestProperty("Accept-Encoding", "identity");
        https.setRequestProperty("Accept",
                "application/vnd.android.package-archive,application/octet-stream,*/*;q=0.5");
        https.setRequestProperty("User-Agent", "AppleTvLauncherUpdater/1");
        try {
            int status = https.getResponseCode();
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.List<String>> header : https.getHeaderFields().entrySet()) {
                if (header.getKey() != null && header.getValue() != null
                        && !header.getValue().isEmpty()) {
                    headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue().get(0));
                }
            }
            InputStream body = status >= 400 ? https.getErrorStream() : https.getInputStream();
            if (body == null) body = new ByteArrayInputStream(new byte[0]);
            return new DownloadResponse(status, headers, body, https, https.getContentLengthLong());
        } catch (IOException exception) {
            https.disconnect();
            throw exception;
        }
    }

    private static LocalHttpResponse openLocalHttp(
            URI uri, LauncherUpdateNetwork.Endpoint wifi) throws IOException {
        InetAddress address = LauncherUpdateNetwork.parseIpv4Literal(uri.getHost());
        if (address == null || !LauncherUpdateNetwork.isSameSubnet(address, wifi)
                || address.equals(wifi.address)) {
            throw new IOException("Local HTTP links must point to a different device on this Wi-Fi.");
        }
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
            OutputStream output = new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            String hostHeader = address.getHostAddress() + (port == 80 ? "" : ":" + port);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Accept: application/vnd.android.package-archive,application/octet-stream,*/*;q=0.5\r\n"
                    + "User-Agent: AppleTvLauncherUpdater/1\r\n\r\n";
            output.write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.flush();

            String statusLine = readAsciiLine(input, MAX_HEADER_BYTES);
            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length < 2 || !statusParts[0].startsWith("HTTP/")) {
                throw new IOException("The local APK server returned an invalid HTTP response.");
            }
            int status;
            try {
                status = Integer.parseInt(statusParts[1]);
            } catch (NumberFormatException exception) {
                throw new IOException("The local APK server returned an invalid status.", exception);
            }

            Map<String, String> headers = new LinkedHashMap<>();
            int headerBytes = statusLine.length() + 2;
            while (true) {
                String line = readAsciiLine(input, MAX_HEADER_BYTES);
                headerBytes += line.length() + 2;
                if (headerBytes > MAX_HEADER_BYTES) {
                    throw new IOException("The local APK server returned oversized headers.");
                }
                if (line.isEmpty()) break;
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    throw new IOException("The local APK server returned a malformed header.");
                }
                String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                if (headers.containsKey(name)) headers.put(name, headers.get(name) + "," + value);
                else headers.put(name, value);
            }
            return new LocalHttpResponse(status, headers, input, socket);
        } catch (IOException | RuntimeException exception) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (exception instanceof IOException) throw (IOException) exception;
            throw new IOException("Could not read the local APK server response.", exception);
        }
    }

    private static String readAsciiLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() <= limit) {
            int value = input.read();
            if (value == -1) throw new IOException("The local APK server closed the connection.");
            if (previous == '\r' && value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length,
                        java.nio.charset.StandardCharsets.US_ASCII);
            }
            line.write(value);
            previous = value;
        }
        throw new IOException("The local APK server returned an oversized header line.");
    }

    private static void copyChunked(InputStream input, File destination, long maxBytes)
            throws IOException {
        long total = 0;
        try (BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(destination), BUFFER_SIZE)) {
            while (true) {
                String sizeLine = readAsciiLine(input, 128);
                int extension = sizeLine.indexOf(';');
                if (extension >= 0) sizeLine = sizeLine.substring(0, extension);
                long chunkSize;
                try {
                    chunkSize = Long.parseLong(sizeLine.trim(), 16);
                } catch (NumberFormatException exception) {
                    throw new IOException("The local APK server returned an invalid chunk.", exception);
                }
                if (chunkSize < 0 || chunkSize > maxBytes - total) {
                    throw new IOException("The APK exceeds the configured download limit.");
                }
                if (chunkSize == 0) {
                    while (!readAsciiLine(input, MAX_HEADER_BYTES).isEmpty()) {
                        // Consume optional trailer headers.
                    }
                    break;
                }
                total += copyExact(input, output, chunkSize, maxBytes);
                String terminator = readAsciiLine(input, 2);
                if (!terminator.isEmpty()) {
                    throw new IOException("The local APK server returned a malformed chunk.");
                }
            }
            if (total == 0) throw new IOException("The downloaded APK is empty.");
        }
    }

    private static long copyExact(InputStream input, OutputStream output, long expected, long maxBytes)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long copied = 0;
        while (copied < expected) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, expected - copied));
            if (count < 0) throw new IOException("The local APK download ended early.");
            if (count == 0) continue;
            if (copied + count > maxBytes) {
                throw new IOException("The APK exceeds the configured download limit.");
            }
            output.write(buffer, 0, count);
            copied += count;
        }
        return copied;
    }

    private static long copyBounded(InputStream input, File destination, long expected, long maxBytes)
            throws IOException {
        if (expected > maxBytes) throw new IOException("The APK exceeds the configured download limit.");
        long total = 0;
        try (BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(destination), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                total += count;
                if (total > maxBytes || expected >= 0 && total > expected) {
                    throw new IOException("The APK exceeds the declared or allowed size.");
                }
                output.write(buffer, 0, count);
            }
            if (expected >= 0 && total != expected) {
                throw new IOException("The APK download ended before its declared length.");
            }
        }
        if (total == 0) throw new IOException("The downloaded APK is empty.");
        return total;
    }

    private static long parseContentLength(String value) throws IOException {
        if (value == null) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IOException("The local APK server returned an invalid content length.", exception);
        }
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }

    private static final class DownloadResponse implements Closeable {
        final int statusCode;
        final Map<String, String> headers;
        final InputStream body;
        final HttpURLConnection connection;
        final long contentLength;

        DownloadResponse(int statusCode, Map<String, String> headers, InputStream body,
                HttpURLConnection connection, long contentLength) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.connection = connection;
            this.contentLength = contentLength;
        }

        String header(String name) {
            return headers.get(name);
        }

        long contentLength(long maxBytes) throws IOException {
            String encoding = header("content-encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                throw new IOException("Compressed APK responses are not supported.");
            }
            if (contentLength > maxBytes) {
                throw new IOException("The APK exceeds the configured download limit.");
            }
            return contentLength;
        }

        @Override
        public void close() throws IOException {
            try {
                body.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    private static final class LocalHttpResponse implements Closeable {
        final int statusCode;
        final Map<String, String> headers;
        final InputStream body;
        final Socket socket;

        LocalHttpResponse(int statusCode, Map<String, String> headers,
                InputStream body, Socket socket) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.socket = socket;
        }

        @Override
        public void close() throws IOException {
            try {
                body.close();
            } finally {
                socket.close();
            }
        }
    }
}
