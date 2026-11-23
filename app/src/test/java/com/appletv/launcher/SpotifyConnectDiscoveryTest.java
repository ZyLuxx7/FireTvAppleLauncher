package com.appletv.launcher;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class SpotifyConnectDiscoveryTest {
    @Test
    public void readsJsonFromLocalHttpResponse() throws Exception {
        String body = "{\"deviceID\":\"sonos-id\"}";
        String response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                + "\r\n\r\n" + body;

        SpotifyConnectDiscovery.LocalHttpResponse parsed =
                SpotifyConnectDiscovery.readHttpResponse(asInput(response));

        assertEquals(200, parsed.statusCode);
        assertEquals(body, parsed.body);
    }

    @Test
    public void decodesChunkedLocalHttpResponse() throws Exception {
        String response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "7\r\n{\"a\":1}\r\n0\r\n\r\n";

        SpotifyConnectDiscovery.LocalHttpResponse parsed =
                SpotifyConnectDiscovery.readHttpResponse(asInput(response));

        assertEquals(200, parsed.statusCode);
        assertEquals("{\"a\":1}", parsed.body);
    }

    private static ByteArrayInputStream asInput(String response) {
        return new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII));
    }
}
