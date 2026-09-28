package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KitFailures describes what a language threw, and attaches what closing threw to a check's failure")
class KitFailuresTest {

    /** An exception whose getMessage() throws, as a broken language's may. */
    private static final class UnreadableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("unreadable");
        }
    }

    @Test
    @DisplayName("an exception's message is its getMessage()")
    void message() {
        assertEquals("boom", KitFailures.message(new IllegalStateException("boom")));
        assertEquals("null", KitFailures.message(new IllegalStateException()));
    }

    @Test
    @DisplayName("an exception whose getMessage() throws is described by its class and the class of what it threw")
    void unreadableMessage() {
        assertEquals(UnreadableException.class.getName() + " (message unavailable: java.lang.IllegalStateException)",
                KitFailures.message(new UnreadableException()));
    }

    @Test
    @DisplayName("what closing threw is attached to a check's failure once, and never to itself")
    void attach() {
        IllegalStateException failure = new IllegalStateException("check");
        IllegalStateException closeFailure = new IllegalStateException("close");

        KitFailures.suppressAll(failure, List.of(failure, closeFailure, closeFailure));
        KitFailures.attach(failure, closeFailure);

        assertArrayEquals(new Throwable[] {closeFailure}, failure.getSuppressed());
    }
}
