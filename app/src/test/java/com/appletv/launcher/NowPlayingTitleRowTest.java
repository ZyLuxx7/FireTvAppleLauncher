package com.appletv.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NowPlayingTitleRowTest {
    @Test
    public void higherSpeedShortensTheMarqueePass() {
        long slow = NowPlayingTitleRow.durationForScrollPx(800, 1f, 20);
        long fast = NowPlayingTitleRow.durationForScrollPx(800, 1f, 80);

        assertTrue(slow > fast);
    }

    @Test
    public void zeroOverflowDoesNotAnimate() {
        assertEquals(0L, NowPlayingTitleRow.durationForScrollPx(0, 1f, 25));
    }

    @Test
    public void scrollSpeedIsClampedToSliderRange() {
        assertEquals(NowPlayingTitleRow.scrollSpeedDpPerSecond(
                        NowPlayingTitleRow.MIN_SCROLL_SPEED_PERCENT),
                NowPlayingTitleRow.scrollSpeedDpPerSecond(-10), 0.001f);
        assertEquals(NowPlayingTitleRow.scrollSpeedDpPerSecond(
                        NowPlayingTitleRow.MAX_SCROLL_SPEED_PERCENT),
                NowPlayingTitleRow.scrollSpeedDpPerSecond(110), 0.001f);
    }
}
