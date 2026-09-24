package dev.tachyonscript.language.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuggestionsTest {

    @Test
    void findsTranspositionsAndTypos() {
        List<String> members = List.of("give", "send", "name", "health", "teleport", "hasPermission");
        assertEquals(List.of("give"), Suggestions.closest("giev", members, 3));
        assertEquals(List.of("health"), Suggestions.closest("helth", members, 3));
        assertEquals(List.of("teleport"), Suggestions.closest("teleprot", members, 3));
        assertEquals(List.of("hasPermission"), Suggestions.closest("haspermission", members, 3));
        assertEquals(List.of(), Suggestions.closest("xyz", members, 3));
    }

    @Test
    void distanceIsOptimalStringAlignment() {
        assertEquals(0, Suggestions.distance("abc", "abc"));
        assertEquals(1, Suggestions.distance("abc", "acb"));
        assertEquals(1, Suggestions.distance("abc", "ab"));
        assertEquals(3, Suggestions.distance("", "abc"));
    }
}
