package com.appletv.launcher;

/**
 * The dock is the first row of the grid. dock_apps decides what is in it: a slot it leaves free stays
 * empty instead of taking the first grid app when fill_dock is false; by default they fill.
 */
final class DockSlots {
    private DockSlots() { }

    /**
     * Empty slots to put after the {@code docked} dock apps found. None when the dock is full, when no
     * dock app is installed (the first row fills so Home still has a dock) or when nothing follows.
     */
    static int empty(int docked, int dockSize, int tiles, boolean fill) {
        if (fill || docked <= 0 || docked >= dockSize || tiles <= docked) return 0;
        return dockSize - docked;
    }

    /** Empty slots focus the last dock app, or Apps when there is no dock app. */
    static int focusable(int position, int docked, int empty, int emptyFallback) {
        if (empty > 0 && position >= docked && position < docked + empty) {
            return docked > 0 ? docked - 1 : Math.max(0, emptyFallback);
        }
        return position;
    }

    /** Position of the Apps tile: after empty placeholders, otherwise after the visible dock apps. */
    static int appsPosition(int docked, int appSlots, int empty) {
        return empty > 0 ? appSlots : docked;
    }
}
