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
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A listener's {@code onError} that throws a fatal {@link Error} while it closes a failure that is fatal itself: the
 * failure's own error is still the one {@code run()} rethrows, and the listener's is kept on it and on the exception
 * listeners were told about, where {@code onRunError} finds it, and logged like a second fatal error in one callback.
 * Each test has a rule error of its own, since what the engine keeps on one would reach the next test.
 */
@DisplayName("a fatal error onError throws while closing a fatal failure is kept")
class SecondFatalFromOnErrorTest {

    /** A fact whose getter fails as the JVM would when memory runs out. */
    public static final class Boom {

        private final Error error;

        Boom(Error error) {
            this.error = error;
        }

        /**
         * Throws the rule's fatal error.
         *
         * @return never
         */
        public boolean getX() {
            throw error;
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

    /**
     * Runs a rule with two listeners: the first may fail in beforeEvaluate, the second's onError throws. The third,
     * {@code recorder}, records what onRunError got.
     */
    private static Outcome<Error> run(Recorder recorder, String condition, boolean fatalBefore, Error ruleError,
                                      Function<RuleExecutionException, Error> onError) {
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
        return capture(Error.class, () -> engine.run(new FactMap<>(new Fact<Object>("boom", new Boom(ruleError)))));
    }

    private static void assertKept(Outcome<Error> outcome, Recorder recorder, Error first, OutOfMemoryError second) {
        assertSame(first, outcome.thrown(), "run() must rethrow the failure's own error");
        assertTrue(Arrays.asList(recorder.runError.get().getSuppressed()).contains(second),
                "onRunError's exception doesn't keep the listener's error: " + recorder.runError.get());
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed(), "the rethrown error doesn't keep it");
        assertTrue(outcome.logs().contains("WARN " + ENGINE_LOGGER + "Listener threw exception in onError, "
                + "kept on the failure: java.lang.OutOfMemoryError: second from onError"), outcome.logs());
    }

    @Test
    @DisplayName("when the rule's own error is fatal")
    void ruleError() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule oom");
        OutOfMemoryError second = new OutOfMemoryError("second from onError");
        Recorder recorder = new Recorder();

        Outcome<Error> outcome = run(recorder, "boom.x", false, ruleError, error -> second);

        assertKept(outcome, recorder, ruleError, second);
    }

    @Test
    @DisplayName("the log names the root cause the listener's error hides when it has no message")
    void hiddenCauseLogged() {
        OutOfMemoryError second = new OutOfMemoryError();
        second.initCause(new IOException("disk full"));
        Recorder recorder = new Recorder();

        Outcome<Error> outcome = run(recorder, "boom.x", false, new OutOfMemoryError("rule oom"), error -> second);

        assertTrue(outcome.logs().contains("WARN " + ENGINE_LOGGER + "Listener threw exception in onError, "
                + "kept on the failure: java.lang.OutOfMemoryError (caused by java.io.IOException: disk full)"),
                outcome.logs());
    }

    @Test
    @DisplayName("when a before* callback's error is fatal")
    void beforeCallbackError() {
        OutOfMemoryError second = new OutOfMemoryError("second from onError");
        Recorder recorder = new Recorder();

        Outcome<Error> outcome = run(recorder, "true", true, new OutOfMemoryError("rule oom"), error -> second);

        assertEquals("before oom", outcome.thrown().getMessage());
        assertKept(outcome, recorder, outcome.thrown(), second);
    }

    @Test
    @DisplayName("a listener that rethrows the failure's own error doesn't hide a later listener's")
    void rethrownThenAnother() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule oom");
        OutOfMemoryError second = new OutOfMemoryError("second from onError");
        Recorder recorder = new Recorder();
        RuleListener rethrows = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw ruleError;
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
                () -> engine.run(new FactMap<>(new Fact<Object>("boom", new Boom(ruleError)))));

        assertSame(ruleError, thrown);
        assertTrue(Arrays.asList(recorder.runError.get().getSuppressed()).contains(second),
                "the later listener's error was lost: " + recorder.runError.get());
        assertArrayEquals(new Throwable[] {second}, ruleError.getSuppressed(), "the rethrown error doesn't keep it");
    }

    @Test
    @DisplayName("a listener that wraps the failure's own error is logged, and keeps nothing")
    void wrappedRethrow() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule oom");
        Recorder recorder = new Recorder();
        Outcome<Error> outcome = run(recorder, "boom.x", false, ruleError, error -> {
            throw new IllegalStateException("could not close the span", error);
        });

        assertSame(ruleError, outcome.thrown());
        assertEquals(0, ruleError.getSuppressed().length, ruleError.toString());
        assertEquals(0, recorder.runError.get().getSuppressed().length, recorder.runError.get().toString());
        assertTrue(outcome.logs().contains("Listener threw exception in onError: java.lang.IllegalStateException: "
                + "could not close the span"), outcome.logs());
    }

    @Test
    @DisplayName("guard: a listener's fatal error that wraps the failure's own isn't kept on it, which would make a"
            + " loop")
    void fatalWrappingTheRuleErrorNotKeptOnIt() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule oom");
        InternalError wrapping = new InternalError("second from onError", ruleError);
        Recorder recorder = new Recorder();

        Outcome<Error> outcome = run(recorder, "boom.x", false, ruleError, error -> wrapping);

        assertSame(ruleError, outcome.thrown());
        assertFalse(Arrays.asList(ruleError.getSuppressed()).contains(wrapping), "kept on the error it wraps");
        assertNoLoop(ruleError);
        assertTrue(Arrays.asList(recorder.runError.get().getSuppressed()).contains(wrapping),
                "onRunError's exception doesn't keep the listener's error: " + recorder.runError.get());
    }

    /** Fails if a throwable reached from {@code thrown} through causes and suppressed exceptions leads back to it. */
    static void assertNoLoop(Throwable thrown) {
        assertNoLoop(thrown, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void assertNoLoop(Throwable thrown, Set<Throwable> path) {
        assertTrue(path.add(thrown), "a loop of causes and suppressed exceptions through " + thrown);
        if (thrown.getCause() != null) {
            assertNoLoop(thrown.getCause(), path);
        }
        for (Throwable suppressed : thrown.getSuppressed()) {
            assertNoLoop(suppressed, path);
        }
        path.remove(thrown);
    }

    @Test
    @DisplayName("a listener that rethrows the failure's own error, or the exception it got, adds nothing")
    void sameErrorRethrown() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule oom");
        Recorder recorder = new Recorder();
        Outcome<Error> outcome = run(recorder, "boom.x", false, ruleError, error -> ruleError);
        Recorder rethrewRecorder = new Recorder();
        Outcome<Error> rethrewException = run(rethrewRecorder, "boom.x", false, new OutOfMemoryError("rule oom"),
                error -> {
                    throw error;
                });

        assertFalse(rethrewException.logs().contains("Listener threw exception in onError"),
                rethrewException.logs());
        assertEquals(0, rethrewRecorder.runError.get().getSuppressed().length,
                rethrewRecorder.runError.get().toString());

        assertSame(ruleError, outcome.thrown());
        assertFalse(Arrays.asList(recorder.runError.get().getSuppressed()).contains(ruleError), recorder.runError.get()
                .toString());
        assertEquals(0, ruleError.getSuppressed().length, "kept on itself: " + ruleError);
        assertFalse(outcome.logs().contains("Listener threw exception in onError"), outcome.logs());
    }
}
