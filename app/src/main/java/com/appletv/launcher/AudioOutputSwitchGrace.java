package com.appletv.launcher;

final class AudioOutputSwitchGrace {
    static final long DURATION_MS = 10_000L;

    private long deadlineMs;

    synchronized void begin(long nowMs) {
        deadlineMs = nowMs + DURATION_MS;
    }

    synchronized void complete() {
        deadlineMs = 0L;
    }

    synchronized long remainingMs(long nowMs) {
        return deadlineMs <= nowMs ? 0L : deadlineMs - nowMs;
    }

    synchronized boolean expire(long nowMs) {
        if (deadlineMs == 0L || nowMs < deadlineMs) return false;
        deadlineMs = 0L;
        return true;
    }
}
