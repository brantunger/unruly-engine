package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #552: a {@link Throwable} that is neither an {@link Exception} nor an {@link Error}, as a language or a listener
 * compiled apart can throw, is treated like an exception wherever the engine calls a rule, a listener, a language or
 * the output. From a condition, an action, the output factory or the output writer it fails the rule or the run,
 * logged at ERROR and reported through {@code onError} and then {@code onRunError}; from a listener it's logged at
 * WARN, and the other listeners still get the callback; from loading, it fails the load with a
 * {@link RuleCompilationException}. It's never fatal itself, but a fatal {@link Error} among its causes still is.
 */
@DisplayName("a Throwable that is neither an Exception nor an Error is reported like an exception")
class PlainThrowableTest {

    private static final String LANGUAGE = StubExpressionLanguage.LANGUAGE_NAME;

    /** Every callback on a run whose one rule matches and fires, and that nothing fails. */
    static final List<String> EVERY_CALLBACK = List.of("A.beforeRun", "B.beforeRun", "A.beforeEvaluate",
            "B.beforeEvaluate", "A.afterEvaluate", "B.afterEvaluate", "A.beforeExecute", "B.beforeExecute",
            "A.afterExecute", "B.afterExecute", "A.afterRun", "B.afterRun");

    /** Every callback on a run whose one rule's action fails. */
    static final List<String> FAILED_ACTION = List.of("A.beforeRun", "B.beforeRun", "A.beforeEvaluate",
            "B.beforeEvaluate", "A.afterEvaluate", "B.afterEvaluate", "A.beforeExecute", "B.beforeExecute",
            "A.onError", "B.onError", "A.onRunError", "B.onRunError");

    /**
     * Records each callback it gets, prefixed with its name, and throws {@code failure} from the callback named
     * {@code failIn}, after recording it.
     */
    static class Recorder implements RuleListener {
        final String name;
        final List<String> calls;
        volatile String failIn;
        final Throwable failure;
        final AtomicReference<RuntimeException> runError = new AtomicReference<>();

        Recorder(String name, List<String> calls) {
            this(name, calls, null, null);
        }

        Recorder(String name, List<String> calls, String failIn, Throwable failure) {
            this.name = name;
            this.calls = calls;
            this.failIn = failIn;
            this.failure = failure;
        }

        private void called(String callback) {
            calls.add(name + "." + callback);
            if (callback.equals(failIn)) {
                PlainThrowableTest.<RuntimeException>sneakyThrow(failure);
            }
        }

        @Override
        public void beforeRun(RunContext run) {
            called("beforeRun");
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            called("afterRun");
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            runError.set(error);
            called("onRunError");
        }

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            called("beforeEvaluate");
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            called("afterEvaluate");
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            called("beforeExecute");
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            called("afterExecute");
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            called("onError");
        }
    }

    /**
     * A stub language whose conditions throw {@code conditionFailure}, whose compiler throws {@code compileFailure}
     * from compiling a condition, and whose sessions, which keep state of their own, throw {@code warmUpFailure} from
     * being warmed up; each only when set.
     */
    static final class ThrowingLanguage implements ExpressionLanguage {
        volatile Throwable conditionFailure;
        volatile Throwable compileFailure;
        volatile Throwable warmUpFailure;

        @Override
        public String name() {
            return LANGUAGE;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    throwIfSet(compileFailure);
                    return (evaluation, session) -> {
                        throwIfSet(conditionFailure);
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return new Session() {
                    };
                }

                @Override
                public void warmUp(Session session) {
                    throwIfSet(warmUpFailure);
                }
            };
        }
    }

    private static void throwIfSet(Throwable failure) {
        if (failure != null) {
            PlainThrowableTest.<RuntimeException>sneakyThrow(failure);
        }
    }

    /**
     * Throws any throwable, a {@link Throwable} that is neither an {@link Exception} nor an {@link Error} too, from
     * code the compiler thinks throws nothing, as a language or a listener compiled apart can.
     */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    static RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(language)
                .defaultLanguage(LANGUAGE);
    }

    static RulesEngine<Map<String, Object>> loaded(RulesEngineBuilder<Map<String, Object>> builder) {
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(rules());
        return engine;
    }

    static List<Rule> rules() {
        return List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
    }

    private static FactMap<Object> oneFact() {
        FactMap<Object> facts = new FactMap<>();
        facts.put(new Fact<>("f", 1));
        return facts;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeRun", "beforeEvaluate", "afterEvaluate", "beforeExecute", "afterExecute",
            "afterRun"})
    @DisplayName("from a listener's callback, it's logged at WARN, the listeners after it still get the callback, and"
            + " the run goes on")
    void fromAListener(String callback) {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage())
                .listener(new Recorder("A", calls, callback, raw)).listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertNull(thrown.get(), "the run goes on");
        assertEquals(EVERY_CALLBACK, calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in " + callback
                + ": java.lang.Throwable: raw"), logs);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"onError", "onRunError"})
    @DisplayName("from a listener's onError or onRunError, it's logged at WARN, the listeners after it still get the"
            + " callback, and the run's own failure is thrown")
    void fromAListenerClosingAFailure(String callback) {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            throw new IllegalStateException("the action failed");
        });
        RulesEngine<Map<String, Object>> engine = loaded(builder(language)
                .listener(new Recorder("A", calls, callback, raw)).listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("r", failure.getRuleName(), "the run's own failure, not what the listener threw");
        assertEquals(FAILED_ACTION, calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in " + callback
                + ": java.lang.Throwable: raw"), logs);
    }

    @Test
    @DisplayName("from a listener, with a fatal Error as its cause, that error is rethrown once every listener has had"
            + " the callback")
    void fromAListenerWithAFatalCause() {
        OutOfMemoryError fatal = new OutOfMemoryError("listener oom");
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage())
                .listener(new Recorder("A", calls, "afterExecute", new Throwable("raw", fatal)))
                .listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(List.of("A.beforeRun", "B.beforeRun", "A.beforeEvaluate", "B.beforeEvaluate", "A.afterEvaluate",
                "B.afterEvaluate", "A.beforeExecute", "B.beforeExecute", "A.afterExecute", "B.afterExecute",
                "A.onRunError", "B.onRunError"), calls);
    }

    @Test
    @DisplayName("from a condition, it fails the rule, logged at ERROR, through onError and then onRunError")
    void fromACondition() {
        Throwable raw = new Throwable("raw");
        ThrowingLanguage language = new ThrowingLanguage();
        language.conditionFailure = raw;
        List<String> calls = new CopyOnWriteArrayList<>();
        Recorder listener = new Recorder("A", calls);
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).listener(listener));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to evaluate condition for rule 'r': raw", failure.getMessage());
        assertEquals("r", failure.getRuleName());
        assertEquals(ExpressionKind.CONDITION, failure.getExpressionKind());
        assertSame(raw, failure.getCause());
        assertSame(failure, listener.runError.get());
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.onError", "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Failed to evaluate condition for rule 'r': raw"), logs);
    }

    @Test
    @DisplayName("from an action, it fails the rule, logged at ERROR, through onError and then onRunError")
    void fromAnAction() {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            sneakyThrow(raw);
            return ActionResult.done();
        });
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to execute action for rule 'r': raw", failure.getMessage());
        assertEquals(ExpressionKind.ACTION, failure.getExpressionKind());
        assertSame(raw, failure.getCause());
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.afterEvaluate", "A.beforeExecute", "A.onError",
                "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Failed to execute action for rule 'r': raw"), logs);
    }

    @Test
    @DisplayName("from an action, with a fatal Error as its cause, that error is rethrown unchanged once onError and"
            + " onRunError have been called")
    void fromAnActionWithAFatalCause() {
        OutOfMemoryError fatal = new OutOfMemoryError("action oom");
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            sneakyThrow(new Throwable("raw", fatal));
            return ActionResult.done();
        });
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.afterEvaluate", "A.beforeExecute", "A.onError",
                "A.onRunError"), calls);
    }

    @Test
    @DisplayName("from an action on an interrupted thread, the run stops, keeping it as a suppressed exception")
    void fromAnActionOnAnInterruptedThread() {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            Thread.currentThread().interrupt();
            sneakyThrow(raw);
            return ActionResult.done();
        });
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs;
        boolean interrupted;

        try {
            logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));
        } finally {
            // Cleared for the tests after this one, whatever happened.
            interrupted = Thread.interrupted();
        }

        assertTrue(interrupted, "the thread's interrupt status");
        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("run() was interrupted during rule 'r'", stop.getMessage());
        assertNull(stop.getRuleName(), "a stop names no rule");
        assertInstanceOf(InterruptedException.class, stop.getCause());
        assertArrayEquals(new Throwable[] {raw}, stop.getSuppressed());
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.afterEvaluate", "A.beforeExecute", "A.onError",
                "A.onRunError"), calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "run() was interrupted during rule 'r'"), logs);
    }

    @Test
    @DisplayName("from the output factory, it fails the run, logged at ERROR, through onRunError")
    void fromTheOutputFactory() {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>allMatches(() -> {
            sneakyThrow(raw);
            return new HashMap<>();
        }).language(new StubExpressionLanguage()).defaultLanguage(LANGUAGE).listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Output factory threw java.lang.Throwable: raw", failure.getMessage());
        assertSame(raw, failure.getCause());
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.afterEvaluate", "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Output factory threw java.lang.Throwable: raw"), logs);
    }

    @Test
    @DisplayName("from the output writer, it fails the rule, logged at ERROR, through onError and then onRunError")
    void fromTheOutputWriter() {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage()
                .action((action, session) -> ActionResult.set(Map.of("x", 1)));
        OutputWriter<Object> writer = (output, property, value) -> sneakyThrow(raw);
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).outputWriter(writer)
                .listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to set 'x' on the output for rule 'r': raw", failure.getMessage());
        assertSame(raw, failure.getCause());
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.afterEvaluate", "A.beforeExecute", "A.onError",
                "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Failed to set 'x' on the output for rule 'r': raw"),
                logs);
    }

    @Test
    @DisplayName("from a language's fact-name check, it fails the run like a rejected name, through onRunError")
    void fromAFactNameCheck() {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage()
                .checkFactName(name -> sneakyThrow(raw))).listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(oneFact()))));

        IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, thrown.get());
        String msg = "The '" + LANGUAGE + "' expression language failed to check fact name 'f': raw";
        assertEquals(msg, failure.getMessage());
        assertSame(raw, failure.getCause());
        assertEquals(List.of("A.beforeRun", "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + msg), logs);
    }

    @Test
    @DisplayName("from a language's check of a declared fact name, it fails the load")
    void fromADeclaredFactNameCheck() {
        Throwable raw = new Throwable("raw");
        RulesEngine<Map<String, Object>> engine = builder(new StubExpressionLanguage()
                .checkFactName(name -> sneakyThrow(raw))).fact("f", Integer.class).build();
        List<Rule> rules = rules();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException failure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("Declared fact 'f' can't be used: The '" + LANGUAGE + "' expression language failed to check"
                + " fact name 'f': raw", failure.getMessage());
        assertSame(raw, failure.getCause().getCause());
    }

    @Test
    @DisplayName("from compiling a condition, it fails the load, naming the rule")
    void fromCompiling() {
        Throwable raw = new Throwable("raw");
        ThrowingLanguage language = new ThrowingLanguage();
        language.compileFailure = raw;
        RulesEngine<Map<String, Object>> engine = builder(language).build();
        List<Rule> rules = rules();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException failure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("Condition for rule 'r' failed to compile: raw", failure.getMessage());
        assertEquals("r", failure.getRuleName());
        assertSame(raw, failure.getCause());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Condition for rule 'r' failed to compile: raw"), logs);
    }

    @Test
    @DisplayName("from warming up a session made at load, it fails the load")
    void fromWarmingUp() {
        Throwable raw = new Throwable("raw");
        ThrowingLanguage language = new ThrowingLanguage();
        language.warmUpFailure = raw;
        RulesEngine<Map<String, Object>> engine = builder(language).copiesAtLoad(1).build();
        List<Rule> rules = rules();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException failure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("The '" + LANGUAGE + "' expression language failed to warm up a session: raw",
                failure.getMessage());
        assertSame(raw, failure.getCause());
    }

    @Test
    @DisplayName("from a listener's beforeRun, for a run whose deadline passed while it waited for a copy, it's logged"
            + " at WARN, and every listener still gets beforeRun and onRunError")
    void fromAListenerOfARunThatStoppedWaiting() {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        StubExpressionLanguage language = new StubExpressionLanguage().newSession(() -> new Session() {
        }).action((action, session) -> {
            holding.countDown();
            await(finish);
            return ActionResult.done();
        });
        // Throws only once the holder's run has begun, so only in the waiter's beforeRun.
        Recorder thrower = new Recorder("A", calls, null, raw);
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).maxCopies(1).listener(thrower)
                .listener(new Recorder("B", calls)));
        // Holds the engine's one copy until the waiter has stopped.
        Thread holder = new Thread(() -> thrownBy(() -> engine.run(new FactMap<>())));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        List<String> waiterCalls = new ArrayList<>();

        String logs = logsOf(() -> {
            holder.start();
            await(holding);
            thrower.failIn = "beforeRun";
            calls.clear();
            thrown.set(thrownBy(() -> engine.runWithResult(new FactMap<>(),
                    RunOptions.withTimeoutOf(Duration.ofMillis(200)))));
            waiterCalls.addAll(calls);
            finish.countDown();
            join(holder);
        });

        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertTrue(stop.getMessage().contains("while waiting for a compiled copy of the rules: all 1 were in use"),
                stop.getMessage());
        assertEquals(List.of("A.beforeRun", "B.beforeRun", "A.onRunError", "B.onRunError"), waiterCalls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in beforeRun:"
                + " java.lang.Throwable: raw"), logs);
    }

    @Test
    @DisplayName("from anywhere else in a run, it still fails the run, logged at ERROR, through onRunError")
    void fromAnywhereElseInARun() {
        Throwable raw = new Throwable("raw");
        List<String> calls = new CopyOnWriteArrayList<>();
        Recorder listener = new Recorder("A", calls);
        AbstractRulesEngine<String> engine = engineWhoseRunsThrow(raw, listener);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("The run failed with java.lang.Throwable: raw", failure.getMessage());
        assertSame(raw, failure.getCause());
        assertSame(failure, listener.runError.get(), "the failure onRunError got is the one thrown");
        assertEquals(List.of("A.beforeRun", "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "The run failed with java.lang.Throwable: raw"), logs);
    }

    @Test
    @DisplayName("from anywhere else in a run, with an InterruptedException as its cause, it fails the run and sets"
            + " the thread's interrupt status again")
    void fromAnywhereElseInARunWithAnInterruptCause() {
        Throwable raw = new Throwable("raw", new InterruptedException("interrupted in the run"));
        List<String> calls = new CopyOnWriteArrayList<>();
        Recorder listener = new Recorder("A", calls);
        AbstractRulesEngine<String> engine = engineWhoseRunsThrow(raw, listener);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        boolean interrupted;

        try {
            logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));
        } finally {
            // Cleared for the tests after this one, whatever happened.
            interrupted = Thread.interrupted();
        }

        assertTrue(interrupted, "the interrupt among the causes was swallowed");
        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertSame(raw, failure.getCause());
        assertEquals(List.of("A.beforeRun", "A.onRunError"), calls);
    }

    @Test
    @DisplayName("from anywhere else in a run, with a fatal Error as its cause, that error is rethrown unchanged once"
            + " onRunError has been called")
    void fromAnywhereElseInARunWithAFatalCause() {
        OutOfMemoryError fatal = new OutOfMemoryError("run oom");
        Throwable raw = new Throwable("raw", fatal);
        List<String> calls = new CopyOnWriteArrayList<>();
        Recorder listener = new Recorder("A", calls);
        AbstractRulesEngine<String> engine = engineWhoseRunsThrow(raw, listener);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(List.of("A.beforeRun", "A.onRunError"), calls);
        assertSame(raw, listener.runError.get().getCause());
    }

    /**
     * An engine with no rules whose every run's body throws {@code failure}, from where no rule, listener, language or
     * output is called, which is the one place in a run that nothing reports it but the run itself.
     */
    private static AbstractRulesEngine<String> engineWhoseRunsThrow(Throwable failure, RuleListener listener) {
        EngineConfiguration<String> configuration = new EngineConfiguration<>(List.of(new StubExpressionLanguage()),
                null, List.of(), List.of(listener), CopyLimit.none(), 0, null, Clock.systemUTC(), Object.class,
                OutputWriter.beansAndMaps(), Map.of(), Map.of(), false);
        AbstractRulesEngine<String> engine = new AbstractRulesEngine<>(configuration) {
            @Override
            RunResult<String> runRules(FactStore<?> facts, Duration timeout, Set<String> tags) {
                return runInScope(facts, timeout, tags, (rules, copy, runFacts) -> {
                    sneakyThrow(failure);
                    return RunResult.of("ran", List.of(), rules.checksum());
                });
            }

            @Override
            String matchPolicy() {
                return "firstMatch";
            }
        };
        engine.load(List.of());
        return engine;
    }

    /**
     * Returns what {@code action} throws, or {@code null} if it throws nothing. Not {@code assertThrows()}, which
     * rethrows an {@link OutOfMemoryError} it didn't expect, and so would stop the test JVM rather than fail the test.
     */
    static Throwable thrownBy(Executable action) {
        try {
            action.execute();
        } catch (Throwable t) {
            return t;
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertFalse(thread.isAlive(), "timed out");
    }
}
