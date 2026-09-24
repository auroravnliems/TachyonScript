package dev.tachyonscript.language.syntax;

/** Units of duration literals such as {@code 5 seconds}. Durations are stored in milliseconds. */
public enum DurationUnit {
    MILLISECONDS("millisecond", "milliseconds", 1L),
    TICKS("tick", "ticks", 50L),
    SECONDS("second", "seconds", 1_000L),
    MINUTES("minute", "minutes", 60_000L),
    HOURS("hour", "hours", 3_600_000L),
    DAYS("day", "days", 86_400_000L);

    private final String singular;
    private final String plural;
    private final long millis;

    DurationUnit(String singular, String plural, long millis) {
        this.singular = singular;
        this.plural = plural;
        this.millis = millis;
    }

    /** Milliseconds per unit. A tick is 50 ms (20 ticks per second). */
    public long millis() {
        return millis;
    }

    public String singular() {
        return singular;
    }

    public String plural() {
        return plural;
    }

    /** Unit named {@code word} (singular or plural, lowercase), or {@code null}. */
    public static DurationUnit fromWord(String word) {
        for (DurationUnit unit : values()) {
            if (unit.singular.equals(word) || unit.plural.equals(word)) {
                return unit;
            }
        }
        return null;
    }
}
