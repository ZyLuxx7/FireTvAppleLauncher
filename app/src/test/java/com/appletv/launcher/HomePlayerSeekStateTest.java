package com.appletv.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HomePlayerSeekStateTest {
    @Test
    public void holdingRewindAtZeroDoesNotIssueRepeatedSeeks() {
        HomePlayerSeekState state = new HomePlayerSeekState();

        assertEquals(0L, state.nextTarget(
                "track", 5_000L, 180_000L, -1, 1, 1_000L, 15_000L));
        assertEquals(HomePlayerSeekState.NO_SEEK, state.nextTarget(
                "track", 5_000L, 180_000L, -1, 2, 1_100L, 15_000L));

        state.releaseRewind();
        assertEquals(HomePlayerSeekState.NO_SEEK, state.nextTarget(
                "track", 0L, 180_000L, -1, 1, 1_200L, 15_000L));
    }

    @Test
    public void movingForwardFromZeroAllowsSeekingAgain() {
        HomePlayerSeekState state = new HomePlayerSeekState();
        state.nextTarget("track", 5_000L, 180_000L, -1, 1, 1_000L, 15_000L);

        assertEquals(15_000L, state.nextTarget(
                "track", 5_000L, 180_000L, 1, 1, 1_100L, 15_000L));
    }

    @Test
    public void aNewTrackClearsTheStartBoundaryLock() {
        HomePlayerSeekState state = new HomePlayerSeekState();
        state.nextTarget("track", 5_000L, 180_000L, -1, 1, 1_000L, 15_000L);

        assertEquals(45_000L, state.nextTarget(
                "next-track", 60_000L, 180_000L, -1, 1, 1_100L, 15_000L));
    }

    @Test
    public void confirmedPlaybackPositionReplacesPendingTarget() {
        HomePlayerSeekState state = new HomePlayerSeekState();
        state.nextTarget("track", 5_000L, 180_000L, -1, 1, 1_000L, 15_000L);

        state.updateFromSnapshot("track", 0L, 1_100L, 1_100L);
        assertEquals(15_000L, state.nextTarget(
                "track", 0L, 180_000L, 1, 1, 1_200L, 15_000L));
    }
}
