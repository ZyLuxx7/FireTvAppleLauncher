package com.appletv.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioOutputRouteTest {
    @Test
    public void internalOutputIsRecognizedAsCurrentTv() {
        assertTrue(AudioOutputRoute.isCurrentTvOutput(false, false, false, false, false));
    }

    @Test
    public void remoteAndExternalRoutesAreNotCurrentTvOutput() {
        assertFalse(AudioOutputRoute.isCurrentTvOutput(true, false, false, false, false));
        assertFalse(AudioOutputRoute.isCurrentTvOutput(false, false, true, false, false));
        assertFalse(AudioOutputRoute.isCurrentTvOutput(false, false, false, true, false));
        assertFalse(AudioOutputRoute.isCurrentTvOutput(false, false, false, false, true));
    }

    @Test
    public void spotifyOnCurrentTvIsAllowedButOtherSpotifyDevicesAreNot() {
        assertTrue(AudioOutputRoute.isCurrentTvOutput(true, true, false, false, false));
        assertFalse(AudioOutputRoute.isCurrentTvOutput(true, false, false, false, false));
        assertFalse(AudioOutputRoute.isCurrentTvOutput(true, true, true, false, false));
    }
}
