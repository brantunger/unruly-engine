package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static io.github.brantunger.unruly.core.PlainThrowableTest.builder;
import static io.github.brantunger.unruly.core.PlainThrowableTest.loaded;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1040: a language whose rules can catch errors, such as Lua's {@code pcall} or a JavaScript
 * {@code try}/{@code catch}, catches the {@link IllegalStateException} {@link FactProperties} wraps an interrupted
 * getter in, not the {@link InterruptedException} itself. The thread is left interrupted all the same, so the run
 * still stops.
 */
@DisplayName("a run whose rule catches the failure of an interrupted getter still stops as interrupted")
class InterruptedFactReadTest {

    /** A bean whose getter waits, as one reading from a pool or a queue does, and is interrupted while it waits. */
    public static final class Slow {

        public int getValue() throws InterruptedException {
            Thread.currentThread().interrupt();
            Thread.sleep(10_000);
            return 1;
        }
    }

    @Test
    @DisplayName("an action that catches the failed read returns, and the run stops, leaving the thread interrupted")
    void anActionThatCatchesTheFailedRead() {
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            try {
                FactProperties.read(action.facts().get("slow"), "value");
            } catch (IllegalStateException caught) {
                // As a rule's own try/catch or pcall would.
            }
            return ActionResult.done();
        });
        RulesEngine<Map<String, Object>> engine = loaded(builder(language));
        Outcome<Throwable> outcome;
        boolean interrupted;

        try {
            outcome = capture(() -> engine.run(new FactMap<>(new Fact<>("slow", new Slow()))));
        } finally {
            // Cleared for the tests after this one, whatever happened.
            interrupted = Thread.interrupted();
        }

        assertTrue(interrupted, "the thread's interrupt status");
        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals("run() was interrupted during rule 'r'", stop.getMessage());
        assertTrue(outcome.logs().contains("WARN " + ENGINE_LOGGER + "run() was interrupted during rule 'r'"),
                outcome.logs());
    }
}
