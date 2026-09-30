package dev.tachyonscript.platform.paper.lib;

/** Conversions between script durations (milliseconds) and server ticks (50 ms). */
public final class Ticks {

    private Ticks() {
    }

    /** Ticks for a duration, rounded up; negative durations count as 0. */
    public static int of(long millis) {
        if (millis <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (millis + 49) / 50);
    }
}
