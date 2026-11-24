package com.appletv.launcher;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LauncherReleaseCheckerTest {
    @Test
    public void detectsNewerGitHubPrereleaseAcrossVersionSuffixes() {
        assertTrue(LauncherReleaseChecker.isNewerVersion("1.0.0-firetv", "v1.0.1-test"));
    }

    @Test
    public void doesNotTreatSameOrOlderReleaseAsAnUpdate() {
        assertFalse(LauncherReleaseChecker.isNewerVersion("1.0.1-firetv", "v1.0.1-test"));
        assertFalse(LauncherReleaseChecker.isNewerVersion("1.0.2-firetv", "v1.0.1-test"));
    }

    @Test
    public void ignoresUnparseableReleaseNames() {
        assertFalse(LauncherReleaseChecker.isNewerVersion("1.0.0-firetv", "nightly"));
        assertFalse(LauncherReleaseChecker.isNewerVersion("nightly", "v1.0.1-test"));
    }
}
