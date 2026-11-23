package com.appletv.launcher;

import java.util.Objects;

final class HomePlayerSeekState {
    static final long NO_SEEK = -1L;
    private static final long PENDING_TARGET_TIMEOUT_MS = 2_000L;
    private static final long POSITION_TOLERANCE_MS = 1_500L;

    private String trackKey;
    private long pendingTargetMs = NO_SEEK;
    private long pendingTargetAtMs;
    private boolean rewindHeldAtStart;

    long nextTarget(String trackKey, long currentMs, long durationMs, int direction,
                    int steps, long nowMs, long stepMs) {
        if (trackKey == null || trackKey.isEmpty() || durationMs <= 0
                || direction == 0 || stepMs <= 0) {
            return NO_SEEK;
        }
        selectTrack(trackKey);
        expirePendingTarget(nowMs);
        if (direction > 0) rewindHeldAtStart = false;
        if (direction < 0 && rewindHeldAtStart) return NO_SEEK;

        long position = pendingTargetMs >= 0
                ? pendingTargetMs : Math.max(0, Math.min(currentMs, durationMs));
        long offset = stepMs * Math.max(1, Math.min(20, steps));
        long target = direction < 0
                ? Math.max(0, position - offset)
                : Math.min(durationMs, position + offset);
        if (direction < 0 && target == 0) rewindHeldAtStart = true;
        if (target == position) return NO_SEEK;

        pendingTargetMs = target;
        pendingTargetAtMs = nowMs;
        return target;
    }

    boolean updateFromSnapshot(String trackKey, long positionMs, long positionUpdatedAtMs,
                               long nowMs) {
        if (!Objects.equals(this.trackKey, trackKey)) {
            reset();
            this.trackKey = trackKey;
            return true;
        }
        if (pendingTargetMs < 0) return false;

        boolean positionConfirmed = positionUpdatedAtMs >= pendingTargetAtMs
                && Math.abs(positionMs - pendingTargetMs) <= POSITION_TOLERANCE_MS;
        if (positionConfirmed || nowMs - pendingTargetAtMs > PENDING_TARGET_TIMEOUT_MS) {
            clearPendingTarget();
        }
        return false;
    }

    void releaseRewind() {
        rewindHeldAtStart = false;
    }

    void clearPendingTarget(String trackKey) {
        if (Objects.equals(this.trackKey, trackKey)) clearPendingTarget();
    }

    void reset() {
        trackKey = null;
        clearPendingTarget();
        rewindHeldAtStart = false;
    }

    private void selectTrack(String trackKey) {
        if (Objects.equals(this.trackKey, trackKey)) return;
        reset();
        this.trackKey = trackKey;
    }

    private void expirePendingTarget(long nowMs) {
        if (pendingTargetMs >= 0
                && nowMs - pendingTargetAtMs > PENDING_TARGET_TIMEOUT_MS) {
            clearPendingTarget();
        }
    }

    private void clearPendingTarget() {
        pendingTargetMs = NO_SEEK;
        pendingTargetAtMs = 0;
    }
}
