package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Failures.truncate keeps a message of up to 1,000 characters and shortens a longer one")
class FailuresTest {

    @Test
    @DisplayName("a message of exactly 1,000 characters is returned unchanged")
    void messageAtTheLimitUnchanged() {
        String message = "a".repeat(Failures.MAX_DESCRIPTION_LENGTH);

        assertEquals(message, Failures.truncate(message));
    }

    @Test
    @DisplayName("a message of 1,001 characters keeps its first 1,000 and says one more was left out")
    void messageOneOverTheLimitShortened() {
        String kept = "a".repeat(Failures.MAX_DESCRIPTION_LENGTH);

        assertEquals(kept + "... (1 more characters)", Failures.truncate(kept + "b"));
    }
}
