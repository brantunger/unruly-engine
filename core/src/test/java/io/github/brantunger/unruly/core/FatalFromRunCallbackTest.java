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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A fatal {@link Error} a listener throws from a run callback: one from {@code onRunError} is what {@code run()}
 * throws, in place of what the run failed with, which it carries as a suppressed exception; of two from one callback,
 * the first is thrown, carrying the second.
 */
@DisplayName("a fatal error from a run callback keeps what it's thrown in place of")
class FatalFromRunCallbackTest {

    /** A fact whose getter throws what it was given. */
    public static final class Boom {

        private final RuntimeException exception;
        private final Error error;

        Boom(RuntimeException exception, Error error) {
            this.exception = exception;
            this.error = error;
        }

        /**
         * Throws the rule's failure.
         *
         * @return never
         */
        public boolean getX() {
            if (error != null) {
                throw error;
            }
            throw exception;
        }
    }

    /** A listener whose onRunError records what it got and then throws what {@code thrown} makes of it. */
    private static final class OnRunError implements RuleListener {

        private final AtomicReference<RuntimeException> runError = new AtomicReference<>();
        private final Function<RuntimeException, Error> thrown;

        OnRunError(Function<RuntimeException, Error> thrown) {
            this.thrown = thrown;
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            runError.set(error);
            throw thrown.apply(error);
        }
    }

    private static Throwable run(Boom boom, RuleListener... listeners) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).listeners(List.of(listeners)).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("boom.x").action("put k 1").build()));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        logsOf(() -> thrown.set(assertThrows(Throwable.class,
                () -> engine.run(new FactMap<>(new Fact<Object>("boom", boom))))));
        return thrown.get();
    }

    @Test
    @DisplayName("from onRunError, in place of a failure that isn't fatal: the listener's error carries the failure")
    void inPlaceOfAFailure() {
        OutOfMemoryError listenerError = new OutOfMemoryError("onRunError");
        OnRunError listener = new OnRunError(error -> listenerError);

        Throwable thrown = run(new Boom(new IllegalStateException("rule failed"), null), listener);

        assertSame(listenerError, thrown);
        assertArrayEquals(new Throwable[] {listener.runError.get()}, listenerError.getSuppressed());
    }

    @Test
    @DisplayName("from onRunError, in place of the rule's own fatal error: the listener's error carries the rule's")
    void inPlaceOfAFatalError() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule");
        OutOfMemoryError listenerError = new OutOfMemoryError("onRunError");
        OnRunError listener = new OnRunError(error -> listenerError);

        Throwable thrown = run(new Boom(null, ruleError), listener);

        assertSame(listenerError, thrown);
        assertArrayEquals(new Throwable[] {ruleError}, listenerError.getSuppressed());
        assertEquals(0, ruleError.getSuppressed().length);
    }

    @Test
    @DisplayName("guard: from onRunError, the rule's own fatal error rethrown is thrown carrying nothing")
    void sameErrorRethrown() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule");
        OnRunError listener = new OnRunError(error -> ruleError);

        Throwable thrown = run(new Boom(null, ruleError), listener);

        assertSame(ruleError, thrown);
        assertEquals(0, ruleError.getSuppressed().length);
    }

    @Test
    @DisplayName("two from beforeRun: the first is thrown, carrying the second")
    void twoFromBeforeRun() {
        OutOfMemoryError first = new OutOfMemoryError("first");
        OutOfMemoryError second = new OutOfMemoryError("second");
        RuleListener throwsFirst = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                throw first;
            }
        };
        RuleListener throwsSecond = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                throw second;
            }
        };

        Throwable thrown = run(new Boom(new IllegalStateException("not reached"), null), throwsFirst, throwsSecond);

        assertSame(first, thrown);
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
    }

    @Test
    @DisplayName("from onError and then onRunError, the same error: thrown in place of the rule's, which carries it,"
            + " and not kept on it again, which would make a loop")
    void sameErrorFromOnErrorAndOnRunError() {
        OutOfMemoryError ruleError = new OutOfMemoryError("rule");
        OutOfMemoryError listenerError = new OutOfMemoryError("listener");
        RuleListener listener = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw listenerError;
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                throw listenerError;
            }
        };

        Throwable thrown = run(new Boom(null, ruleError), listener);

        assertSame(listenerError, thrown);
        assertArrayEquals(new Throwable[] {listenerError}, ruleError.getSuppressed());
        assertEquals(0, listenerError.getSuppressed().length);
        SecondFatalFromOnErrorTest.assertNoLoop(listenerError);
    }
}
