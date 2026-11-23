package com.appletv.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioOutputSwitchGraceTest {
    @Test
    public void graceExpiresAfterTenSecondsUnlessSpotifyUpdates() {
        AudioOutputSwitchGrace grace = new AudioOutputSwitchGrace();
        grace.begin(100L);

        assertEquals(10_000L, grace.remainingMs(100L));
        assertFalse(grace.expire(10_099L));
        assertTrue(grace.expire(10_100L));
        assertEquals(0L, grace.remainingMs(10_100L));
    }

    @Test
    public void successfulUpdateCancelsTheGracePeriod() {
        AudioOutputSwitchGrace grace = new AudioOutputSwitchGrace();
        grace.begin(100L);
        grace.complete();

        assertEquals(0L, grace.remainingMs(101L));
        assertFalse(grace.expire(10_100L));
    }
}
