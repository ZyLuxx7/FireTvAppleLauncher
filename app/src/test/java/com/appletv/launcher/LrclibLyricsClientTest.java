package com.appletv.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class LrclibLyricsClientTest {
    @Test
    public void parsesMultipleTimestampsAndIgnoresMetadata() {
        List<LrclibLyricsClient.LyricLine> lines =
                LrclibLyricsClient.parseSyncedLyrics(
                        "[ar:Artist]\r\n[00:01.2][00:03.45]  First line  \r\n"
                                + "[01:02.345]Second line");

        assertEquals(3, lines.size());
        assertEquals(1_200L, lines.get(0).positionMs);
        assertEquals(3_450L, lines.get(1).positionMs);
        assertEquals("First line", lines.get(0).text);
        assertEquals(62_345L, lines.get(2).positionMs);
    }

    @Test
    public void selectsTheCurrentLineWithoutShowingLyricsBeforeItsTimestamp() {
        List<LrclibLyricsClient.LyricLine> lines =
                LrclibLyricsClient.parseSyncedLyrics(
                        "[00:02.00]First line\n[00:04.25]Second line");

        assertEquals("", LrclibLyricsClient.lineAt(lines, 1_999L));
        assertEquals("First line", LrclibLyricsClient.lineAt(lines, 2_000L));
        assertEquals("Second line", LrclibLyricsClient.lineAt(lines, 4_250L));
    }

    @Test
    public void searchFallbackPrefersTheMatchingAlbumAndDuration() {
        LrclibLyricsClient.Lyrics otherAlbum = lyrics("[00:01.00]Other");
        LrclibLyricsClient.Lyrics matchingAlbum = lyrics("[00:01.00]Match");
        List<LrclibLyricsClient.SearchCandidate> candidates = Arrays.asList(
                new LrclibLyricsClient.SearchCandidate(
                        "A Song", "An Artist", "Other Album", 181, otherAlbum),
                new LrclibLyricsClient.SearchCandidate(
                        "A Song", "An Artist", "The Album", 185, matchingAlbum),
                new LrclibLyricsClient.SearchCandidate(
                        "A Song (Live)", "An Artist", "The Album", 180, otherAlbum));

        assertSame(matchingAlbum, LrclibLyricsClient.selectBestMatch(
                candidates, "A Song", "An Artist", "The Album", 180));
    }

    @Test
    public void searchFallbackRejectsDifferentArtistsAndDistantVersions() {
        List<LrclibLyricsClient.SearchCandidate> candidates = Arrays.asList(
                new LrclibLyricsClient.SearchCandidate(
                        "A Song", "Another Artist", "The Album", 180,
                        lyrics("[00:01.00]Wrong artist")),
                new LrclibLyricsClient.SearchCandidate(
                        "A Song", "An Artist", "The Album", 260,
                        lyrics("[00:01.00]Different version")));

        assertNull(LrclibLyricsClient.selectBestMatch(
                candidates, "A Song", "An Artist", "The Album", 180));
    }

    @Test
    public void searchFallbackIgnoresACommonMasteringSuffix() {
        LrclibLyricsClient.Lyrics match = lyrics("[00:01.00]Line");
        List<LrclibLyricsClient.SearchCandidate> candidates = Arrays.asList(
                new LrclibLyricsClient.SearchCandidate(
                        "A Song", "An Artist", "The Album", 180, match));

        assertSame(match, LrclibLyricsClient.selectBestMatch(
                candidates, "A Song - Master", "An Artist", "The Album", 180));
    }

    private LrclibLyricsClient.Lyrics lyrics(String syncedLyrics) {
        return new LrclibLyricsClient.Lyrics(
                LrclibLyricsClient.parseSyncedLyrics(syncedLyrics), false);
    }
}
