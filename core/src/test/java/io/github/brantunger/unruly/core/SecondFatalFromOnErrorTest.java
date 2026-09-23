package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A listener's {@code onError} that throws a fatal {@link Error} while it closes a failure that is fatal itself: the
 * failure's own error is still the one {@code run()} rethrows, and the listener's is kept on the exception listeners
 * were told about, where {@code onRunError} finds it, and logged like a second fatal error in one callback.
 */
@DisplayName("a fatal error onError throws while closing a fatal failure is kept")
class SecondFatalFromOnErrorTest {

    private static final OutOfMemoryError RULE_ERROR = new OutOfMemoryError("rule oom");

    /** A fact whose getter fails as the JVM would when memory runs out. */
    public static final class Boom {

        /**
         * Throws the rule's fatal error.
         *
         * @return never
         */
        public boolean getX() {
            throw RULE_ERROR;
        }
    }

    /** Records the exception onRunError got. */
    private static final class Recorder implements RuleListener {

        private final AtomicReference<RuntimeException> runError = new AtomicReference<>();

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            runError.set(error);
        }
    }

    private record Outcome(Throwable thrown, RuntimeException runError, String logs) {
    }

    /** Runs a rule with two listeners: the first may fail in beforeEvaluate, the second's onError throws. */
    private static Outcome run(String condition, boolean fatalBefore, Function<RuleExecutionException, Error> onError) {
        Recorder recorder = new Recorder();
        RuleListener first = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                if (fatalBefore) {
                    throw new OutOfMemoryError("before oom");
                }
            }
        };
        RuleListener closer = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw onError.apply(error);
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage())
                .listener(first).listener(closer).listener(recorder).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action("put k 1").build()));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(Error.class,
                () -> engine.run(new FactMap<>(new Fact<Object>("boom", new Boom()))))));
        return new Outcome(thrown.get(), recorder.runError.get(), logs);
    }

    private static void assertKept(Outcome outcome, Error first, OutOfMemoryError second) {
        assertSame(first, outcome.thrown(), "run() must rethrow the failure's own error");
        assertTrue(Arrays.asList(outcome.runError().getSuppressed()).contains(second),
                "onRunError's exception doesn't keep the listener's error: " + outcome.runError());
        assertFalse(Arrays.asList(first.getSuppressed()).contains(second), "the rethrown error was changed");
        assertTrue(outcome.logs().contains("WARN " + ENGINE_LOGGER + "Listener threw exception in onError, "
                + "kept on the failure: java.lang.OutOfMemoryError: second from onError"), outcome.logs());
    }

    @Test
    @DisplayName("when the rule's own error is fatal")
    void ruleError() {
        OutOfMemoryError second = new OutOfMemoryError("second from onError");

        Outcome outcome = run("boom.x", false, error -> second);

        assertKept(outcome, RULE_ERROR, second);
    }

    @Test
    @DisplayName("the log names the root cause the listener's error hides when it has no message")
    void hiddenCauseLogged() {
        OutOfMemoryError second = new OutOfMemoryError();
        second.initCause(new IOException("disk full"));

        Outcome outcome = run("boom.x", false, error -> second);

        assertTrue(outcome.logs().contains("WARN " + ENGINE_LOGGER + "Listener threw exception in onError, "
                + "kept on the failure: java.lang.OutOfMemoryError (caused by java.io.IOException: disk full)"),
                outcome.logs());
    }

    @Test
    @DisplayName("when a before* callback's error is fatal")
    void beforeCallbackError() {
        OutOfMemoryError second = new OutOfMemoryError("second from onError");

        Outcome outcome = run("true", true, error -> second);

        assertEquals("before oom", outcome.thrown().getMessage());
        assertKept(outcome, (Error) outcome.thrown(), second);
    }

    @Test
    @DisplayName("a listener that rethrows the failure's own error doesn't hide a later listener's")
    void rethrownThenAnother() {
        OutOfMemoryError second = new OutOfMemoryError("second from onError");
        Recorder recorder = new Recorder();
        RuleListener rethrows = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw RULE_ERROR;
            }
        };
        RuleListener throwsAnother = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw second;
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage())
                .listener(rethrows).listener(throwsAnother).listener(recorder).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("boom.x").action("put k 1").build()));

        Throwable thrown = assertThrows(Error.class,
                () -> engine.run(new FactMap<>(new Fact<Object>("boom", new Boom()))));

        assertSame(RULE_ERROR, thrown);
        assertTrue(Arrays.asList(recorder.runError.get().getSuppressed()).contains(second),
                "the later listener's error was lost: " + recorder.runError.get());
    }

    @Test
    @DisplayName("a listener that wraps the failure's own error is logged, and keeps nothing")
    void wrappedRethrow() {
        Outcome outcome = run("boom.x", false, error -> {
            throw new IllegalStateException("could not close the span", error);
        });

        assertSame(RULE_ERROR, outcome.thrown());
        assertEquals(0, outcome.runError().getSuppressed().length, outcome.runError().toString());
        assertTrue(outcome.logs().contains("Listener threw exception in onError: java.lang.IllegalStateException: "
                + "could not close the span"), outcome.logs());
    }

    @Test
    @DisplayName("a listener that rethrows the failure's own error, or the exception it got, adds nothing")
    void sameErrorRethrown() {
        Outcome outcome = run("boom.x", false, error -> RULE_ERROR);
        Outcome rethrewException = run("boom.x", false, error -> {
            throw error;
        });

        assertFalse(rethrewException.logs().contains("Listener threw exception in onError"),
                rethrewException.logs());
        assertEquals(0, rethrewException.runError().getSuppressed().length,
                rethrewException.runError().toString());

        assertSame(RULE_ERROR, outcome.thrown());
        assertFalse(Arrays.asList(outcome.runError().getSuppressed()).contains(RULE_ERROR), outcome.runError()
                .toString());
        assertFalse(outcome.logs().contains("Listener threw exception in onError"), outcome.logs());
    }
}
