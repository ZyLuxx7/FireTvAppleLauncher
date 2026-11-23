package com.appletv.launcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class LauncherUpdateUrlPolicy {
    private static final int MAX_URL_LENGTH = 2048;

    private LauncherUpdateUrlPolicy() {
    }

    static URI parse(String input, LauncherUpdateNetwork.Endpoint wifi) throws IOException {
        if (input == null || input.trim().isEmpty() || input.length() > MAX_URL_LENGTH) {
            throw new IOException("Enter a direct APK link no longer than 2048 characters.");
        }
        URI uri;
        try {
            uri = new URI(input.trim()).normalize();
        } catch (URISyntaxException exception) {
            throw new IOException("The APK link is not a valid URL.", exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IOException("The APK link must have a valid host and no embedded credentials.");
        }

        scheme = scheme.toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            if (isLocalhost(host)) {
                throw new IOException("Localhost is not a valid update source.");
            }
            Inet4Address ipv4 = LauncherUpdateNetwork.parseIpv4Literal(host);
            if (ipv4 != null && isPrivateOrReserved(ipv4)
                    && (!LauncherUpdateNetwork.isSameSubnet(ipv4, wifi)
                    || ipv4.equals(wifi.address))) {
                throw new IOException("Private HTTPS links must point to another device on this Wi-Fi.");
            }
            return uri;
        }

        if (!"http".equals(scheme)) {
            throw new IOException("Only HTTPS links or direct HTTP links on this Wi-Fi are allowed.");
        }

        Inet4Address address = LauncherUpdateNetwork.parseIpv4Literal(host);
        if (address == null || !LauncherUpdateNetwork.isSameSubnet(address, wifi)
                || address.equals(wifi.address)
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            throw new IOException("HTTP APK links must use another numeric IPv4 address on this Wi-Fi.");
        }
        return uri;
    }

    static void validateResolvedAddresses(URI uri, LauncherUpdateNetwork.Endpoint wifi)
            throws IOException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (IOException exception) {
            throw new IOException("The update host could not be resolved.", exception);
        }
        if (addresses.length == 0) {
            throw new IOException("The update host did not resolve to an address.");
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                throw new IOException("The update host resolved to a restricted network address.");
            }
            if (isPrivateOrReserved(address)
                    && (!(address instanceof Inet4Address)
                    || !LauncherUpdateNetwork.isSameSubnet(address, wifi)
                    || address.equals(wifi.address))) {
                throw new IOException("Private update hosts must be on this Wi-Fi network.");
            }
        }
    }

    static URI resolveRedirect(URI current, String location,
            LauncherUpdateNetwork.Endpoint wifi) throws IOException {
        if (location == null || location.trim().isEmpty() || location.length() > MAX_URL_LENGTH) {
            throw new IOException("The update server returned an invalid redirect.");
        }
        try {
            return parse(current.resolve(new URI(location.trim())).toString(), wifi);
        } catch (URISyntaxException exception) {
            throw new IOException("The update server returned an invalid redirect.", exception);
        }
    }

    private static boolean isLocalhost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || normalized.endsWith(".localhost");
    }

    private static boolean isPrivateOrReserved(InetAddress address) {
        if (address.isSiteLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 0 || first == 100 && second >= 64 && second <= 127
                    || first == 192 && second == 0
                    || first == 198 && (second == 18 || second == 19)
                    || first >= 224;
        }
        if (bytes.length == 16) return (bytes[0] & 0xFE) == 0xFC;
        return true;
    }
}
