package com.appletv.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;

public class LauncherUpdateUrlPolicyTest {
    private final LauncherUpdateNetwork.Endpoint wifi = new LauncherUpdateNetwork.Endpoint(
            (Inet4Address) LauncherUpdateNetwork.parseIpv4Literal("192.168.1.20"), 24);

    @Test
    public void allowsHttpsAndDirectHttpOnTheSameWifiSubnet() throws Exception {
        assertEquals("https", LauncherUpdateUrlPolicy.parse(
                "https://updates.example/launcher.apk", wifi).getScheme());
        assertEquals("http", LauncherUpdateUrlPolicy.parse(
                "http://192.168.1.42:8080/launcher.apk", wifi).getScheme());
    }

    @Test
    public void rejectsHttpOutsideTheWifiSubnetAndTheTvAddress() {
        assertThrows(IOException.class, () -> LauncherUpdateUrlPolicy.parse(
                "http://192.168.2.42/launcher.apk", wifi));
        assertThrows(IOException.class, () -> LauncherUpdateUrlPolicy.parse(
                "http://192.168.1.20/launcher.apk", wifi));
        assertThrows(IOException.class, () -> LauncherUpdateUrlPolicy.parse(
                "http://updates.example/launcher.apk", wifi));
    }

    @Test
    public void rejectsNonHttpSchemesAndCredentialBearingUrls() {
        assertThrows(IOException.class, () -> LauncherUpdateUrlPolicy.parse(
                "file:///data/local/tmp/update.apk", wifi));
        assertThrows(IOException.class, () -> LauncherUpdateUrlPolicy.parse(
                "https://user:password@updates.example/launcher.apk", wifi));
    }

    @Test
    public void rejectsResolvedPrivateHostsOutsideTheWifiSubnet() throws Exception {
        URI uri = URI.create("https://10.0.0.5/launcher.apk");
        InetAddress localPrivateAddress = InetAddress.getByName(uri.getHost());
        assertTrue(localPrivateAddress.isSiteLocalAddress());
        assertThrows(IOException.class, () -> LauncherUpdateUrlPolicy.validateResolvedAddresses(
                uri, wifi));
    }
}
