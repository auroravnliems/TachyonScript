package dev.tachyonscript.api.value;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValuesTest {

    @Test
    void formatsNumbersForHumans() {
        assertEquals("20", Values.toString(20.0));
        assertEquals("19.5", Values.toString(19.5));
        assertEquals("0", Values.toString(-0.0));
        assertEquals("-3", Values.toString(-3.0));
        assertEquals("1.0E20", Values.toString(1e20));
        assertEquals("NaN", Values.toString(Double.NaN));
        assertEquals("Infinity", Values.toString(Double.POSITIVE_INFINITY));
        assertEquals("2.5", Values.toString(2.5f));
        assertEquals("7", Values.toString(7.0f));
        assertEquals("null", Values.toString((Object) null));
        assertEquals("20", Values.toString((Object) 20.0));
        assertEquals("[1, 2]", Values.toString((Object) java.util.List.of(1, 2)));
    }

    @Test
    void formatsDurations() {
        assertEquals("0ms", Values.durationToString(0));
        assertEquals("250ms", Values.durationToString(250));
        assertEquals("5s", Values.durationToString(5_000));
        assertEquals("1m 30s", Values.durationToString(90_000));
        assertEquals("1d 2h", Values.durationToString(93_600_000));
        assertEquals("-5s", Values.durationToString(-5_000));
        assertEquals("1s 1ms", Values.durationToString(1_001));
    }
}
