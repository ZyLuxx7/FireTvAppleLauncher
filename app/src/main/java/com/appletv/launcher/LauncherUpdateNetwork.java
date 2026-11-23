package com.appletv.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

final class LauncherUpdateNetwork {
    private LauncherUpdateNetwork() {
    }

    static Endpoint currentWifi(Context context) throws IOException {
        ConnectivityManager connectivity = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null || !hasWifiNetwork(connectivity)) {
            throw new IOException("Connect the TV to Wi-Fi before starting an update session.");
        }

        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        int address = info == null ? 0 : info.getIpAddress();
        if (address == 0) {
            throw new IOException("The TV does not have a Wi-Fi IPv4 address.");
        }

        byte[] addressBytes = new byte[]{
                (byte) address,
                (byte) (address >>> 8),
                (byte) (address >>> 16),
                (byte) (address >>> 24)
        };
        InetAddress localAddress = InetAddress.getByAddress(addressBytes);
        if (!(localAddress instanceof Inet4Address)
                || !localAddress.isSiteLocalAddress()
                || localAddress.isLoopbackAddress()
                || localAddress.isLinkLocalAddress()) {
            throw new IOException("The TV Wi-Fi address is not a private IPv4 address.");
        }

        int prefixLength = findPrefixLength(localAddress);
        if (prefixLength < 1 || prefixLength > 30) {
            throw new IOException("Could not determine the TV Wi-Fi subnet.");
        }
        return new Endpoint((Inet4Address) localAddress, prefixLength);
    }

    private static boolean hasWifiNetwork(ConnectivityManager connectivity) {
        Network[] networks = connectivity.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
        }
        return false;
    }

    private static int findPrefixLength(InetAddress address) {
        try {
            NetworkInterface network = NetworkInterface.getByInetAddress(address);
            if (network != null) {
                for (java.net.InterfaceAddress interfaceAddress : network.getInterfaceAddresses()) {
                    if (address.equals(interfaceAddress.getAddress())) {
                        return interfaceAddress.getNetworkPrefixLength();
                    }
                }
            }
        } catch (SocketException exception) {
            return -1;
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return -1;
            while (interfaces.hasMoreElements()) {
                for (java.net.InterfaceAddress interfaceAddress
                        : interfaces.nextElement().getInterfaceAddresses()) {
                    if (address.equals(interfaceAddress.getAddress())) {
                        return interfaceAddress.getNetworkPrefixLength();
                    }
                }
            }
        } catch (SocketException exception) {
            return -1;
        }
        return -1;
    }

    static Inet4Address parseIpv4Literal(String host) {
        if (host == null) return null;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return null;
            int value = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                char character = parts[i].charAt(j);
                if (character < '0' || character > '9') return null;
                value = value * 10 + character - '0';
            }
            if (value > 255 || !Integer.toString(value).equals(parts[i])) return null;
            bytes[i] = (byte) value;
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    static boolean isSameSubnet(InetAddress candidate, Endpoint endpoint) {
        if (!(candidate instanceof Inet4Address)) return false;
        byte[] expected = endpoint.address.getAddress();
        byte[] actual = candidate.getAddress();
        int fullBytes = endpoint.prefixLength / 8;
        int remainingBits = endpoint.prefixLength % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (expected[i] != actual[i]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xFF << (8 - remainingBits);
        return (expected[fullBytes] & mask) == (actual[fullBytes] & mask);
    }

    static final class Endpoint {
        final Inet4Address address;
        final int prefixLength;

        Endpoint(Inet4Address address, int prefixLength) {
            this.address = address;
            this.prefixLength = prefixLength;
        }

        String addressText() {
            return address.getHostAddress();
        }
    }
}
