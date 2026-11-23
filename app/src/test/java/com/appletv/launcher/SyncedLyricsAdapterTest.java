package com.appletv.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SyncedLyricsAdapterTest {
    @Test
    public void findsNextNonEmptyLyricAfterGap() {
        List<String> lines = Arrays.asList("First line", "", "  ", "Next line", "");

        assertEquals(3, SyncedLyricsAdapter.nextNonEmptyLineIndex(lines, 1));
        assertEquals(-1, SyncedLyricsAdapter.nextNonEmptyLineIndex(lines, 4));
    }

    @Test
    public void gapProgressTracksPlaybackAndIsClamped() {
        assertEquals(0f, SyncedLyricsAdapter.gapProgressAt(1_000L, 5_000L, 0L), 0.001f);
        assertEquals(0.5f,
                SyncedLyricsAdapter.gapProgressAt(1_000L, 5_000L, 3_000L), 0.001f);
        assertEquals(1f, SyncedLyricsAdapter.gapProgressAt(1_000L, 5_000L, 6_000L), 0.001f);
        assertEquals(1f, SyncedLyricsAdapter.gapProgressAt(5_000L, 1_000L, 3_000L), 0.001f);
    }
}
