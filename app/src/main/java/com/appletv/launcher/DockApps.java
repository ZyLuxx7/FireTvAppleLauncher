package com.appletv.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class DockApps {
    static final String EXCLUDED_APPS_KEY = "dock_excluded_apps";

    private DockApps() { }

    static List<String> addSelected(List<String> configured, Set<String> installed,
                                    Set<String> hidden, List<String> selected, int limit) {
        List<String> result = new ArrayList<>();
        int max = Math.max(0, limit);
        addAvailable(result, configured, installed, hidden, max);
        addAvailable(result, selected, installed, hidden, max);
        return result;
    }

    static List<String> removeSelected(List<String> configured, Set<String> selected) {
        List<String> result = new ArrayList<>();
        for (String packageName : configured) {
            if (!selected.contains(packageName) && !result.contains(packageName)) {
                result.add(packageName);
            }
        }
        return result;
    }

    private static void addAvailable(List<String> result, List<String> candidates,
                                     Set<String> installed, Set<String> hidden, int limit) {
        for (String packageName : candidates) {
            if (result.size() >= limit) return;
            if (installed.contains(packageName) && !hidden.contains(packageName)
                    && !result.contains(packageName)) {
                result.add(packageName);
            }
        }
    }
}
