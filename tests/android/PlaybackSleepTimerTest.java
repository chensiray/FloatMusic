package org.floatmusic.player;

import java.util.concurrent.atomic.AtomicLong;

public final class PlaybackSleepTimerTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong(500);
        PlaybackSleepTimer timer = new PlaybackSleepTimer(clock::get);
        check(timer.mode().equals("off") && timer.remainingSeconds() == 0, "fresh timer is off");
        check(timer.start(1), "one minute accepted");
        check(timer.mode().equals("countdown") && timer.remainingSeconds() == 60, "starts at full duration");
        clock.addAndGet(1);
        check(!timer.poll() && timer.remainingSeconds() == 60, "fractional seconds round upward");
        clock.addAndGet(59998);
        check(!timer.poll() && timer.remainingSeconds() == 1, "not early at the last millisecond");
        clock.incrementAndGet();
        check(timer.poll() && timer.mode().equals("off"), "expires at the deadline");
        check(!timer.poll(), "expiry is delivered exactly once");
        System.out.println("PASS deadline and exactly-once expiry");

        check(timer.start(180), "upper limit accepted");
        check(!timer.start(0) && !timer.start(181), "out-of-range values rejected");
        check(timer.remainingSeconds() == 10800, "invalid changes retain active timer");
        clock.addAndGet(1000);
        check(timer.start(15), "countdown can replace an existing one");
        check(timer.remainingSeconds() == 900, "replacement begins at the new duration");
        timer.cancel();
        clock.addAndGet(10800001);
        check(!timer.poll() && timer.mode().equals("off"), "cancelled deadline cannot fire");
        System.out.println("PASS bounds, replacement and cancellation");

        timer.afterCurrentTrack();
        check(timer.mode().equals("endOfTrack") && timer.remainingSeconds() == 0, "end mode has no countdown");
        check(!timer.poll(), "end mode ignores elapsed time");
        check(timer.consumeTrackEnd() && timer.mode().equals("off"), "track completion consumes end mode");
        check(!timer.consumeTrackEnd(), "completion delivers once");
        timer.afterCurrentTrack();
        check(timer.start(30), "countdown replaces end mode");
        check(!timer.consumeTrackEnd(), "countdown does not stop at track end");
        clock.addAndGet(1800001);
        check(timer.poll(), "clock jump past deadline still expires");
        PlaybackSleepTimer restarted = new PlaybackSleepTimer(clock::get);
        check(restarted.mode().equals("off"), "restart does not revive timer");
        System.out.println("PASS end-of-track, delayed polling and restart policy");
    }
}
