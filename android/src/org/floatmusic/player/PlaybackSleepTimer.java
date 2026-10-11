package org.floatmusic.player;

import java.util.Locale;
import java.util.function.LongSupplier;

/** A monotonic timer: playback owns the pause, and a restart starts with no timer. */
final class PlaybackSleepTimer {
    private final LongSupplier clock;
    private String mode = "off";
    private long deadline;

    PlaybackSleepTimer(LongSupplier clock) { this.clock = clock; }
    String mode() { return mode; }
    int remainingSeconds() {
        if (!"countdown".equals(mode)) return 0;
        return (int)Math.max(0, (deadline - clock.getAsLong() + 999) / 1000);
    }
    boolean start(int minutes) {
        if (minutes < 1 || minutes > 180) return false;
        deadline = clock.getAsLong() + minutes * 60000L;
        mode = "countdown";
        return true;
    }
    void afterCurrentTrack() { mode = "endOfTrack"; deadline = 0; }
    void cancel() { mode = "off"; deadline = 0; }
    boolean poll() {
        if (!"countdown".equals(mode) || clock.getAsLong() < deadline) return false;
        cancel();
        return true;
    }
    boolean consumeTrackEnd() {
        if (!"endOfTrack".equals(mode)) return false;
        cancel();
        return true;
    }
    String text() {
        if ("endOfTrack".equals(mode)) return "播完本首后暂停";
        if (!"countdown".equals(mode)) return "未开启";
        int seconds = remainingSeconds();
        return String.format(Locale.ROOT, "%d:%02d 后暂停", seconds / 60, seconds % 60);
    }
}
