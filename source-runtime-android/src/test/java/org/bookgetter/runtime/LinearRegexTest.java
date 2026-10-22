package org.bookgetter.runtime;

import static org.junit.Assert.*;
import org.junit.Test;

public final class LinearRegexTest {
    @Test(timeout = 2000) public void nestedAlternationDoesNotBacktrack() {
        assertFalse(new LinearRegex("https://x/(a|aa)+$").matches("https://x/" + "a".repeat(10000) + "!"));
        assertTrue(new LinearRegex("https://x/(a|aa)+!").matches("https://x/" + "a".repeat(10000) + "!"));
    }
    @Test public void unsupportedJavaFeaturesAreNeverSilentlyApproximated() {
        assertThrows(LinearRegex.UnsupportedPatternException.class, () -> new LinearRegex("(?<=a)b"));
        assertThrows(LinearRegex.UnsupportedPatternException.class, () -> new LinearRegex("(a)\\1"));
    }
    @Test public void cancellationAndLengthLimitsRemainActive() {
        assertThrows(IllegalArgumentException.class, () -> new LinearRegex(".*").matches("text", 250, () -> true));
        assertThrows(IllegalArgumentException.class, () -> new LinearRegex(".*").matches("x".repeat(65_537)));
    }
    @Test public void compilationCannotExpandShortCountedRepeatsWithoutBound() {
        assertThrows(IllegalArgumentException.class, () -> new LinearRegex("((a{1000}){1000}){1000}"));
        assertThrows(IllegalArgumentException.class, () -> new LinearRegex("(".repeat(65) + "a" + ")".repeat(65)));
        assertTrue(new LinearRegex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches("2026-10-22"));
    }

}
