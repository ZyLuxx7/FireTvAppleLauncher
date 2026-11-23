package com.appletv.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DockAppsTest {
    @Test
    public void addingAppsReplacesMissingAndHiddenDockEntries() {
        List<String> result = DockApps.addSelected(
                Arrays.asList("installed", "missing", "hidden"),
                new HashSet<>(Arrays.asList("installed", "hidden", "new")),
                new HashSet<>(Arrays.asList("hidden")),
                Arrays.asList("new"), 4);

        assertEquals(Arrays.asList("installed", "new"), result);
    }

    @Test
    public void addingAppsPreservesOrderAndRespectsTheConfiguredLimit() {
        List<String> result = DockApps.addSelected(
                Arrays.asList("first", "second"),
                new HashSet<>(Arrays.asList("first", "second", "third", "fourth")),
                new HashSet<>(),
                Arrays.asList("third", "fourth"), 3);

        assertEquals(Arrays.asList("first", "second", "third"), result);
    }

    @Test
    public void fillingDockUsesAvailableGridAppsAfterConfiguredApps() {
        List<String> result = DockApps.addSelected(
                Arrays.asList("pinned", "missing"),
                new HashSet<>(Arrays.asList("pinned", "first", "second")),
                new HashSet<>(),
                Arrays.asList("first", "pinned", "second"), 4);

        assertEquals(Arrays.asList("pinned", "first", "second"), result);
    }

    @Test
    public void excludedAppsAreSkippedWhenFillingFreeDockSlots() {
        List<String> result = DockApps.addSelected(
                Arrays.asList(),
                new HashSet<>(Arrays.asList("excluded", "first", "second")),
                new HashSet<>(Arrays.asList("excluded")),
                Arrays.asList("excluded", "first", "second"), 2);

        assertEquals(Arrays.asList("first", "second"), result);
    }

    @Test
    public void removingAppsKeepsTheRemainingDockOrder() {
        List<String> result = DockApps.removeSelected(
                Arrays.asList("first", "second", "third"),
                new HashSet<>(Arrays.asList("second")));

        assertEquals(Arrays.asList("first", "third"), result);
    }
}
