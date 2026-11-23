package com.appletv.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class SpotifyConnectClientTest {
    @Test
    public void codeChallengeMatchesPkceReferenceVector() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                SpotifyConnectClient.createCodeChallenge(verifier));
    }

    @Test
    public void stateMustMatchBeforeAcceptingAuthorizationCallback() {
        assertTrue(SpotifyConnectClient.stateMatches("expected", "expected"));
        assertFalse(SpotifyConnectClient.stateMatches("expected", "different"));
        assertFalse(SpotifyConnectClient.stateMatches("expected", null));
    }

    @Test
    public void localDevicesAreAppendedWithoutDuplicatingApiDevices() {
        SpotifyConnectClient.Device apiDevice =
                new SpotifyConnectClient.Device("api-id", "Computer", "computer", true, false);
        SpotifyConnectClient.Device duplicate =
                new SpotifyConnectClient.Device("api-id", "Computer local", "speaker", false, false);
        SpotifyConnectClient.Device localDevice =
                new SpotifyConnectClient.Device(
                        "sonos-id", "Living Room", "speaker", false, false, true);

        List<SpotifyConnectClient.Device> merged = SpotifyConnectClient.mergeDevices(
                Collections.singletonList(apiDevice), Arrays.asList(duplicate, localDevice));

        assertEquals(2, merged.size());
        assertEquals("api-id", merged.get(0).id);
        assertFalse(merged.get(0).local);
        assertEquals("sonos-id", merged.get(1).id);
        assertEquals("Living Room", merged.get(1).name);
        assertTrue(merged.get(1).local);
    }

    @Test
    public void normalizesSpotifyTrackUrisFromMetadataAndWebLinks() {
        assertEquals("spotify:track:abc123",
                SpotifyConnectClient.spotifyTrackUriForMediaId(
                        "com.spotify.music", "spotify:track:abc123"));
        assertEquals("spotify:track:abc123",
                SpotifyConnectClient.spotifyTrackUriForMediaId(
                        "com.spotify.tv.android", "https://open.spotify.com/track/abc123?si=share"));
        assertNull(SpotifyConnectClient.spotifyTrackUriForMediaId(
                "com.example.player", "abc123"));
        assertNull(SpotifyConnectClient.spotifyTrackUriForMediaId(
                "com.spotify.music", "spotify:episode:abc123"));
        assertNull(SpotifyConnectClient.normalizeSpotifyTrackUri("https://example.com/abc123"));
    }
}
