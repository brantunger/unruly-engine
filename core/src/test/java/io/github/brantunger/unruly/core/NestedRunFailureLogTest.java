package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
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
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@code run()} started on the same thread while another run is in progress, from an action, the output supplier, a
 * listener callback or a language, logs its own failure, and the code around it doesn't log that failure again: the
 * output supplier's failure reads {@code Output factory threw: a nested run() failed: } and the innermost failure, a
 * listener's exception gets no WARN line, and a language's failure to create a session or check a fact name isn't
 * logged a second time. An exception wrapped around the nested failure with a message of its own is news, and is
 * logged. A fatal {@link Error}, which every run rethrows unchanged, is logged
 * once, by the first run that logs it, however many runs it passes through, and listeners are still told of it at
 * every level; once the outermost run on the thread has ended, the same instance is logged again. An exception wrapped
 * around a nested run's fatal {@link Error} with a message of its own is logged too, with the {@link Error} as a note.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a nested run's failure, and a fatal Error, are logged once")
class NestedRunFailureLogTest {

    private static final String INNER_FAILURE = "Failed to execute action for rule 'inner-rule': inner rule failed";
    private static final String NESTED_FAILURE = "a nested run() failed: " + INNER_FAILURE;
    private static final String INNER_FATAL = "Failed to execute action for rule 'inner-rule': simulated heap "
            + "exhaustion";
    private static final String AFTER_NESTED_FATAL = " (after a nested run() failed: java.lang.OutOfMemoryError: "
            + "simulated heap exhaustion)";
    private static final String OUTPUT_REJECTED = "'output' is reserved for the output object and cannot be used as "
            + "a fact name";

    private final OutOfMemoryError oom = new OutOfMemoryError("simulated heap exhaustion");
    private final AtomicInteger onError = new AtomicInteger();
    private final AtomicInteger onRunError = new AtomicInteger();
    private final RuleListener counter = new RuleListener() {
        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            onError.incrementAndGet();
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            onRunError.incrementAndGet();
        }
    };

    /** A non-fatal {@link Error}, as a class a factory needs failing to load would throw. */
    static final class FactoryError extends Error {
        private static final long serialVersionUID = 1L;

        FactoryError(String message) {
            super(message);
        }
    }

    private RulesEngine<Map<String, Object>> engine(String ruleName, CompiledAction action,
                                                    Supplier<Map<String, Object>> output, RuleListener... listeners) {
        return engine(ruleName, new StubExpressionLanguage().action(action), output, listeners);
    }

    private RulesEngine<Map<String, Object>> engine(String ruleName, ExpressionLanguage language,
                                                    Supplier<Map<String, Object>> output, RuleListener... listeners) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>firstMatch(output)
                .language(language).listener(counter);
        for (RuleListener listener : listeners) {
            builder.listener(listener);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(Rule.builder().ruleName(ruleName).condition("c").action("a").build()));
        return engine;
    }

    private static CompiledAction doing(Runnable what) {
        return (action, session) -> {
            what.run();
            return ActionResult.done();
        };
    }

    private static Runnable running(RulesEngine<Map<String, Object>> engine) {
        return () -> engine.run(new FactMap<>());
    }

    private static Supplier<Map<String, Object>> factoryRunning(RulesEngine<Map<String, Object>> engine) {
        return () -> {
            engine.run(new FactMap<>());
            return new HashMap<>();
        };
    }

    private RulesEngine<Map<String, Object>> failing() {
        return engine("inner-rule", doing(() -> {
            throw new IllegalStateException("inner rule failed");
        }), HashMap::new);
    }

    private RulesEngine<Map<String, Object>> throwingOom() {
        return engine("inner-rule", doing(() -> {
            throw oom;
        }), HashMap::new);
    }

    private RulesEngine<Map<String, Object>> plain(String ruleName, RuleListener... listeners) {
        return engine(ruleName, doing(() -> { }), HashMap::new, listeners);
    }

    private static RuleListener onBeforeRun(Runnable what) {
        return new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                what.run();
            }
        };
    }

    private static RuleListener onBeforeExecute(Runnable what) {
        return new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                what.run();
            }
        };
    }

    private static RuleListener onAfterExecute(Runnable what) {
        return new RuleListener() {
            @Override
            public void afterExecute(Rule rule, Object output) {
                what.run();
            }
        };
    }

    private static Outcome<Throwable> failed(Executable run) {
        return capture(Throwable.class, run);
    }

    private static Outcome<Throwable> returned(Runnable run) {
        return new Outcome<>(null, logsOf(run));
    }

    private void assertOnlyInnerFatalLogged(Outcome<Throwable> outcome, int onErrors, int onRunErrors) {
        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL), outcome.lines("ERROR"), outcome.logs());
        assertEquals(onErrors, onError.get(), "onError calls");
        assertEquals(onRunErrors, onRunError.get(), "onRunError calls");
    }

    private void assertFailedWith(Outcome<Throwable> outcome, String message) {
        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals(message, failure.getMessage());
        assertFalse(outcome.logs().contains("ReportedFailure"), outcome.logs());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    // The output supplier

    @Test
    @DisplayName("an output supplier whose run() fails names the nested failure, logged once, by the nested run")
    void factoryNestedFailure() {
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> { }), factoryRunning(failing()));

        assertFailedWith(failed(() -> engine.run(new FactMap<>())), "Output factory threw: " + NESTED_FAILURE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a supplier", "an action"})
    @DisplayName("through two output suppliers, or a supplier and an action, the failure names the innermost one")
    void factoryTwoLevels(String middle) {
        RulesEngine<Map<String, Object>> mid = "a supplier".equals(middle)
                ? engine("mid-rule", doing(() -> { }), factoryRunning(failing()))
                : engine("mid-rule", doing(running(failing())), HashMap::new);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> { }), factoryRunning(mid));

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertFailedWith(outcome, "Output factory threw: " + NESTED_FAILURE);
        assertFalse(outcome.logs().contains("mid-rule"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"one run deep", "through two suppliers", "through a supplier and an action"})
    @DisplayName("a fatal Error from an output supplier's run() is logged once, by the run it came from")
    void factoryNestedFatal(String how) {
        RulesEngine<Map<String, Object>> nested = switch (how) {
            case "one run deep" -> throwingOom();
            case "through two suppliers" -> engine("mid-rule", doing(() -> { }), factoryRunning(throwingOom()));
            default -> engine("mid-rule", doing(running(throwingOom())), HashMap::new);
        };
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> { }), factoryRunning(nested));

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        switch (how) {
            case "one run deep" -> assertOnlyInnerFatalLogged(outcome, 1, 2);
            case "through two suppliers" -> assertOnlyInnerFatalLogged(outcome, 1, 3);
            default -> assertOnlyInnerFatalLogged(outcome, 2, 3);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"wrapped", "wrapped without a message", "shortened"})
    @DisplayName("an output supplier that wraps its run()'s failure, or one shortened, names the innermost once")
    void factoryWrappedOrShortenedNestedFailure(String how) {
        String message = "shortened".equals(how) ? "m".repeat(1100) : "inner rule failed";
        RulesEngine<Map<String, Object>> inner = engine("inner-rule", doing(() -> {
            throw new IllegalStateException(message);
        }), HashMap::new);
        AtomicReference<String> innermost = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> { }), () -> {
            try {
                inner.run(new FactMap<>());
            } catch (RuleExecutionException nested) {
                innermost.set(nested.getMessage());
                throw switch (how) {
                    case "wrapped" -> new IllegalStateException("audit failed", nested);
                    case "wrapped without a message" -> new IllegalStateException((String) null, nested);
                    default -> nested;
                };
            }
            return new HashMap<>();
        });

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals(1, failure.getMessage().split("a nested run\\(\\) failed: ", -1).length - 1,
                failure.getMessage());
        if ("wrapped".equals(how)) {
            // The wrapper's message is news: nothing logged it.
            assertEquals("Output factory threw: audit failed (after a nested run() failed: " + innermost.get() + ")",
                    failure.getMessage());
            assertEquals(List.of(innermost.get(), failure.getMessage()), outcome.lines("ERROR"), outcome.logs());
        } else {
            assertEquals("Output factory threw: a nested run() failed: " + innermost.get(), failure.getMessage());
            assertEquals(List.of(innermost.get()), outcome.lines("ERROR"), outcome.logs());
        }
    }

    // Actions

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"one run deep", "two runs deep", "through an action and a supplier"})
    @DisplayName("a fatal Error from an action's run() is logged once, and each rule it passes still gets onError")
    void actionNestedFatal(String how) {
        RulesEngine<Map<String, Object>> nested = switch (how) {
            case "one run deep" -> throwingOom();
            case "two runs deep" -> engine("mid-rule", doing(running(throwingOom())), HashMap::new);
            default -> engine("mid-rule", doing(() -> { }), factoryRunning(throwingOom()));
        };
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(running(nested)), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        switch (how) {
            case "one run deep" -> assertOnlyInnerFatalLogged(outcome, 2, 2);
            case "two runs deep" -> assertOnlyInnerFatalLogged(outcome, 3, 3);
            default -> assertOnlyInnerFatalLogged(outcome, 2, 3);
        }
    }

    @Test
    @DisplayName("a rule whose action runs an engine whose output supplier's run() fails names the innermost failure")
    void actionThroughFactoryNamesInnermost() {
        RulesEngine<Map<String, Object>> middle = engine("b-rule", doing(() -> { }), factoryRunning(failing()));
        RulesEngine<Map<String, Object>> engine = engine("a-rule", doing(running(middle)), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertFailedWith(outcome, "Failed to execute action for rule 'a-rule': " + NESTED_FAILURE);
    }

    @Test
    @DisplayName("an action that wraps its run()'s failure in one of its own has that logged, and the run around it"
            + " names it, not the failure below it")
    void wrapperMessageKeptRunsDeep() {
        RulesEngine<Map<String, Object>> inner = failing();
        RulesEngine<Map<String, Object>> middle = engine("mid-rule", doing(() -> {
            try {
                inner.run(new FactMap<>());
            } catch (RuleExecutionException nested) {
                throw new IllegalStateException("fallback pricing failed for order 42", nested);
            }
        }), HashMap::new);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(running(middle)), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        String midFailure = "Failed to execute action for rule 'mid-rule': fallback pricing failed for order 42 (after "
                + NESTED_FAILURE + ")";
        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals("Failed to execute action for rule 'outer-rule': a nested run() failed: " + midFailure,
                failure.getMessage());
        assertEquals(List.of(INNER_FAILURE, midFailure), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"before", "after", "the engine's words before"})
    @DisplayName("an action that puts words of its own around its run()'s failure's text has them logged, with the "
            + "nested failure as a note")
    void wrapperWordsAroundNestedText(String where) {
        RulesEngine<Map<String, Object>> inner = failing();
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                inner.run(new FactMap<>());
            } catch (RuleExecutionException nested) {
                throw new IllegalStateException(switch (where) {
                    case "before" -> "order 42: " + nested.getMessage();
                    case "after" -> nested.getMessage() + " (retried 3 times)";
                    default -> "a nested run() failed: " + nested.getMessage();
                }, nested);
            }
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        String words = switch (where) {
            case "before" -> "order 42: " + INNER_FAILURE;
            case "after" -> INNER_FAILURE + " (retried 3 times)";
            default -> NESTED_FAILURE;
        };
        String outerFailure = "Failed to execute action for rule 'outer-rule': " + words + " (after " + NESTED_FAILURE
                + ")";
        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals(outerFailure, failure.getMessage());
        assertEquals(List.of(INNER_FAILURE, outerFailure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("an action that rethrows its run()'s failure two runs deep with the same message adds nothing, and "
            + "the innermost failure is logged once")
    void sameMessageRethrownTwoRunsDeep() {
        RulesEngine<Map<String, Object>> middle = engine("mid-rule", doing(running(failing())), HashMap::new);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                middle.run(new FactMap<>());
            } catch (RuleExecutionException nested) {
                throw new IllegalStateException(nested.getMessage(), nested);
            }
        }), HashMap::new);

        assertFailedWith(failed(() -> engine.run(new FactMap<>())),
                "Failed to execute action for rule 'outer-rule': " + NESTED_FAILURE);
    }

    @Test
    @DisplayName("an output supplier's Error below a nested run still fails the rule once the deadline has passed")
    void factoryErrorPastTheDeadline() {
        Duration timeout = Duration.ofMillis(500);
        AtomicLong actionStarted = new AtomicLong();
        AtomicBoolean reached = new AtomicBoolean();
        RulesEngine<Map<String, Object>> middle = engine("b-rule", doing(() -> { }), () -> {
            reached.set(true);
            // The action started after the outer run did, so this is past the outer run's deadline. Timed on
            // System.nanoTime(), which decides the deadline: the system clock can be a moment either side of it.
            while (System.nanoTime() - actionStarted.get() <= timeout.toNanos()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            throw new FactoryError("factory broke");
        });
        RulesEngine<Map<String, Object>> engine = engine("a-rule", doing(() -> {
            actionStarted.set(System.nanoTime());
            middle.run(new FactMap<>());
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.runWithResult(new FactMap<>(),
                RunOptions.withTimeoutOf(timeout)));

        assertTrue(reached.get(), "the nested run stopped before its output supplier was called; raise the timeout");
        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals("Failed to execute action for rule 'a-rule': a nested run() failed: Output factory threw "
                + FactoryError.class.getName() + ": factory broke", failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load()", "validate()"})
    @DisplayName("an OutOfMemoryError a top-level load() or validate() saw logged is logged again by a later run")
    void sameFatalLoggedAgainAfterALoad(String which) {
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> loading = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().compileAction(expression -> {
                    nested.run();
                    return (action, session) -> ActionResult.done();
                })).build();
        List<Rule> rules = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
        RulesEngine<Map<String, Object>> later = throwingOom();

        Outcome<Throwable> first = failed(() -> {
            if ("load()".equals(which)) {
                loading.load(rules);
            } else {
                loading.validate(rules);
            }
        });
        Outcome<Throwable> second = failed(() -> later.run(new FactMap<>()));

        assertEquals(List.of(INNER_FATAL), first.lines("ERROR"), first.logs());
        assertEquals(List.of(INNER_FATAL), second.lines("ERROR"), second.logs());
        assertSame(oom, second.thrown());
    }

    @Test
    @DisplayName("an OutOfMemoryError one run logged is logged again by a later run that throws the same instance")
    void sameFatalLoggedAgainByALaterRun() {
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(running(throwingOom())), HashMap::new);

        Outcome<Throwable> first = failed(() -> engine.run(new FactMap<>()));
        Outcome<Throwable> second = failed(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(INNER_FATAL), first.lines("ERROR"), first.logs());
        assertEquals(List.of(INNER_FATAL), second.lines("ERROR"), second.logs());
        assertSame(oom, second.thrown());
    }

    // Listeners

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeRun", "afterExecute"})
    @DisplayName("a listener whose run() fails gets no WARN line: the nested run logged the failure once, at ERROR")
    void listenerNestedFailure(String callback) {
        RulesEngine<Map<String, Object>> inner = failing();
        RuleListener listener = "beforeRun".equals(callback)
                ? onBeforeRun(running(inner)) : onAfterExecute(running(inner));
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", listener);

        Outcome<Throwable> outcome = returned(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
        assertTrue(outcome.logs().contains("DEBUG " + ENGINE_LOGGER + "Listener threw exception in " + callback),
                outcome.logs());
    }

    @Test
    @DisplayName("a listener whose run() runs a listener whose run() fails gets no WARN line at either level")
    void listenerTwoLevels() {
        RulesEngine<Map<String, Object>> mid = plain("mid-rule", onBeforeRun(running(failing())));
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", onBeforeRun(running(mid)));

        Outcome<Throwable> outcome = returned(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeRun", "beforeExecute", "afterExecute", "beforeRun, two runs deep"})
    @DisplayName("a fatal Error from a listener's run() is logged once, by the run it came from")
    void listenerNestedFatal(String callback) {
        Runnable nested = running(throwingOom());
        RuleListener listener = switch (callback) {
            case "beforeRun" -> onBeforeRun(nested);
            case "beforeExecute" -> onBeforeExecute(nested);
            case "afterExecute" -> onAfterExecute(nested);
            default -> onBeforeRun(running(plain("mid-rule", onBeforeRun(nested))));
        };
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", listener);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        switch (callback) {
            case "beforeRun", "afterExecute" -> assertOnlyInnerFatalLogged(outcome, 1, 2);
            case "beforeExecute" -> assertOnlyInnerFatalLogged(outcome, 2, 2);
            default -> assertOnlyInnerFatalLogged(outcome, 1, 3);
        }
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"onError", "onRunError"})
    @DisplayName("a listener whose onError or onRunError runs a failing run() gets no WARN line for it")
    void failureCallbackNestedFailure(String callback) {
        Runnable nested = running(failing());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            throw new IllegalStateException("outer rule failed");
        }), HashMap::new, new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                runIf("onError");
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                runIf("onRunError");
            }

            private void runIf(String which) {
                if (which.equals(callback)) {
                    nested.run();
                }
            }
        });

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertEquals(List.of("Failed to execute action for rule 'outer-rule': outer rule failed", INNER_FAILURE),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"onError", "onRunError", "onError, for a stop"})
    @DisplayName("a listener that wraps the failure it's told of isn't taken for one whose run() failed")
    void wrappedToldFailureDescribedWithItsClass(String callback) {
        boolean stop = callback.endsWith("stop");
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", (action, session) -> {
            if (stop) {
                while (!action.isCancelled()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                return ActionResult.done();
            }
            // The rule's failure is itself a nested run's, which the listener must still not be taken to have run.
            failing().run(new FactMap<>());
            return ActionResult.done();
        }, HashMap::new, new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                if (callback.startsWith("onError")) {
                    throw new IllegalStateException("could not close the span", error);
                }
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                if ("onRunError".equals(callback)) {
                    throw new IllegalStateException("could not close the span", error);
                }
            }
        });

        Outcome<Throwable> outcome = failed(() -> engine.runWithResult(new FactMap<>(),
                stop ? RunOptions.withTimeoutOf(Duration.ofMillis(50)) : RunOptions.defaults()));

        String name = callback.startsWith("onError") ? "onError" : "onRunError";
        assertEquals(List.of("Listener threw exception in " + name + ": java.lang.IllegalStateException: could not "
                + "close the span"), outcome.lines("WARN").stream().filter(line -> line.startsWith("Listener"))
                .toList(), outcome.logs());
    }

    // Languages

    @ParameterizedTest(name = "fatal: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a language whose newSession() runs a failing run() isn't logged again")
    void sessionNestedFailure(boolean fatal) {
        Runnable nested = running(fatal ? throwingOom() : failing());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().newSession(() -> {
            nested.run();
            return Session.none();
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        if (fatal) {
            // The session is created before the outer run opens its scope, so only the nested run gets onRunError.
            assertOnlyInnerFatalLogged(outcome, 1, 1);
        } else {
            assertFailedWith(outcome, "The 'stub' expression language failed to create a session: " + NESTED_FAILURE);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"non-fatal", "fatal", "rejected"})
    @DisplayName("a language whose check of a fact name runs a failing run() isn't logged again")
    void factNameCheckNestedFailure(String how) {
        Runnable nested = running("fatal".equals(how) ? throwingOom() : failing());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().checkFactName(
                name -> {
                    try {
                        nested.run();
                    } catch (RuleExecutionException failure) {
                        // A language may reject the name for the nested run's failure.
                        throw "rejected".equals(how) ? new IllegalArgumentException("rejected", failure) : failure;
                    }
                }), HashMap::new);
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        Outcome<Throwable> outcome = failed(() -> engine.run(facts));

        if ("fatal".equals(how)) {
            assertOnlyInnerFatalLogged(outcome, 1, 2);
        } else if ("rejected".equals(how)) {
            IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, outcome.thrown());
            assertEquals("rejected", failure.getMessage());
            // The rejection's message is news, which the nested run didn't log.
            assertEquals(List.of(INNER_FAILURE, "rejected (after " + NESTED_FAILURE + ")"), outcome.lines("ERROR"),
                    outcome.logs());
        } else {
            IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, outcome.thrown());
            assertEquals("The 'stub' expression language failed to check fact name 'x': " + NESTED_FAILURE,
                    failure.getMessage());
            assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        }
    }

    /** A language whose sessions are its own, so {@code load()} warms them up, running {@code warmUp}. */
    private static ExpressionLanguage warmingUp(Runnable warmUp) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "warming";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, session) -> true;
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
                        warmUp.run();
                    }
                };
            }
        };
    }

    private static RulesEngine<Map<String, Object>> loadedWarmingUp(Runnable warmUp) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(warmingUp(warmUp)).copiesAtLoad(1).build();
        engine.load(List.of(Rule.builder().ruleName("w").condition("c").action("a").build()));
        return engine;
    }

    @Test
    @DisplayName("a language whose warmUp() runs a failing run() fails load(), and isn't logged again")
    void warmUpNestedFailure() {
        Runnable nested = running(failing());

        Outcome<Throwable> outcome = failed(() -> loadedWarmingUp(nested));

        RuntimeException failure = assertInstanceOf(RuntimeException.class, outcome.thrown());
        assertEquals("The 'warming' expression language failed to warm up a session: " + NESTED_FAILURE,
                failure.getMessage());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load(), compiling", "validate(), compiling", "load(), a declared fact's name",
        "validate(), a declared fact's name"})
    @DisplayName("a load() or validate() whose language's run() fails names the nested failure, logged once")
    void loadNestedFailure(String where) {
        Runnable nested = running(failing());
        StubExpressionLanguage stub = new StubExpressionLanguage();
        boolean compiling = where.endsWith("compiling");
        ExpressionLanguage language = compiling
                ? stub.compileAction(expression -> {
                    nested.run();
                    return (action, session) -> ActionResult.done();
                })
                : stub.checkFactName(name -> nested.run());
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(language).fact("x", Integer.class).build();
        List<Rule> rules = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
        AtomicReference<List<RuleCompilationException>> problems = new AtomicReference<>();

        Outcome<Throwable> outcome = returned(() -> {
            if (where.startsWith("validate")) {
                problems.set(engine.validate(rules));
            } else {
                problems.set(List.of(assertThrows(RuleCompilationException.class, () -> engine.load(rules))));
            }
        });

        String expected = (compiling ? "Action for rule 'r' failed to compile: " : "Declared fact 'x' can't be used: ")
                + NESTED_FAILURE;
        assertEquals(List.of(expected), problems.get().stream().map(Throwable::getMessage).toList());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load(), warmUp()", "load(), newSession()", "load(), compiling", "validate(), compiling",
        "load(), a declared fact's name", "validate(), a declared fact's name"})
    @DisplayName("a fatal Error from a run() a language started in a top-level load() or validate() is logged once")
    void loadNestedFatal(String where) {
        Runnable nested = running(throwingOom());
        StubExpressionLanguage stub = new StubExpressionLanguage();
        ExpressionLanguage language = switch (where) {
            case "load(), warmUp()" -> warmingUp(nested);
            case "load(), newSession()" -> stub.newSession(() -> {
                nested.run();
                return Session.none();
            });
            case "load(), compiling", "validate(), compiling" -> stub.compileAction(expression -> {
                nested.run();
                return (action, session) -> ActionResult.done();
            });
            default -> stub.checkFactName(name -> nested.run());
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(language).copiesAtLoad(1).fact("x", Integer.class).build();
        List<Rule> rules = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());

        Outcome<Throwable> outcome = failed(() -> {
            if (where.startsWith("validate")) {
                engine.validate(rules);
            } else {
                engine.load(rules);
            }
        });

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("a fatal Error from a run() a language's warmUp() started, in a load() a rule started, is logged once")
    void warmUpNestedFatalInsideARun() {
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> loadedWarmingUp(nested)),
                HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertOnlyInnerFatalLogged(outcome, 2, 2);
    }

    /**
     * An engine whose language's sessions run {@code close} as they're closed, with one session made and kept idle, so
     * a reload or a close of the engine closes it.
     */
    private RulesEngine<Map<String, Object>> closingWith(Runnable close) {
        RulesEngine<Map<String, Object>> engine = engine("closing-rule", new StubExpressionLanguage().newSession(
                () -> new Session() {
                    @Override
                    public void close() {
                        close.run();
                    }
                }), HashMap::new);
        engine.run(new FactMap<>());
        return engine;
    }

    /**
     * Reloads the engine, or closes it, which closes the idle session of the rules it had.
     *
     * @param engine The engine
     * @param how    {@code load()} or {@code close()}
     */
    private static void closeTheSession(RulesEngine<Map<String, Object>> engine, String how) {
        if ("load()".equals(how)) {
            engine.load(List.of(Rule.builder().ruleName("next-rule").condition("c").action("a").build()));
        } else {
            engine.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load()", "close()"})
    @DisplayName("a session whose close() runs a failing run() isn't logged again, at load() or close()")
    void sessionCloseNestedFailure(String how) {
        RulesEngine<Map<String, Object>> engine = closingWith(running(failing()));

        Outcome<Throwable> outcome = returned(() -> closeTheSession(engine, how));

        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load()", "close()"})
    @DisplayName("a session whose close() runs a run() that rejects its facts isn't logged again, at load() or close()")
    void sessionCloseNestedRejection(String how) {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        RulesEngine<Map<String, Object>> engine = closingWith(() -> {
            FactMap<Object> facts = new FactMap<>();
            facts.setValue("output", 1);
            nested.run(facts);
        });

        Outcome<Throwable> outcome = returned(() -> closeTheSession(engine, how));

        assertEquals(List.of(OUTPUT_REJECTED), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load()", "close()"})
    @DisplayName("a session whose close() runs a run() that throws a fatal Error logs it once, and rethrows it")
    void sessionCloseNestedFatal(String how) {
        RulesEngine<Map<String, Object>> engine = closingWith(running(throwingOom()));

        Outcome<Throwable> outcome = failed(() -> closeTheSession(engine, how));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("a session whose close() wraps its run()'s failure in one of its own gets a WARN line for it")
    void sessionCloseWrappedNestedFailure() {
        RulesEngine<Map<String, Object>> inner = failing();
        RulesEngine<Map<String, Object>> engine = closingWith(() -> {
            try {
                inner.run(new FactMap<>());
            } catch (RuleExecutionException nested) {
                throw new IllegalStateException("session cleanup failed", nested);
            }
        });

        Outcome<Throwable> outcome = returned(engine::close);

        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of("The 'stub' expression language failed to close a session: session cleanup failed (after "
                + NESTED_FAILURE + ")"), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("a second fatal Error in one callback, from a listener's run(), isn't logged again")
    void listenerNestedFatalSecondInACallback() {
        OutOfMemoryError first = new OutOfMemoryError("the listener's own");
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", onBeforeRun(() -> {
            throw first;
        }), onBeforeRun(running(throwingOom())));

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(first, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, "A listener threw java.lang.OutOfMemoryError in beforeRun"),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("a fatal Error from the run() of a listener's onError, kept on a fatal failure, isn't logged again")
    void keptSecondFatalFromANestedRun() {
        OutOfMemoryError ruleError = new OutOfMemoryError("the rule's own");
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            throw ruleError;
        }), HashMap::new, new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                nested.run();
            }
        });

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(ruleError, outcome.thrown());
        assertEquals(List.of("Failed to execute action for rule 'outer-rule': the rule's own", INNER_FATAL),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("two listeners whose run()s fail with fatal Errors of their own have each logged once, by its run")
    void twoListenersNestedFatals() {
        InternalError first = new InternalError("first fatal");
        InternalError second = new InternalError("second fatal");
        RulesEngine<Map<String, Object>> engine = plain("outer-rule",
                onBeforeRun(running(engine("inner1", doing(() -> {
                    throw first;
                }), HashMap::new))),
                onBeforeRun(running(engine("inner2", doing(() -> {
                    throw second;
                }), HashMap::new))));

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(first, outcome.thrown());
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
        assertEquals(List.of("Failed to execute action for rule 'inner1': first fatal",
                "Failed to execute action for rule 'inner2': second fatal"), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("a rule's fatal Error that onRunError rethrows after onError's run() logged another isn't logged"
            + " again")
    void onRunErrorRethrowsAfterANestedFatal() {
        OutOfMemoryError ruleError = new OutOfMemoryError("the rule's own");
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            throw ruleError;
        }), HashMap::new, new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                nested.run();
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                throw error;
            }
        });

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(ruleError, outcome.thrown());
        assertEquals(List.of("Failed to execute action for rule 'outer-rule': the rule's own", INNER_FATAL),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rethrows", "wraps"})
    @DisplayName("a listener whose onRunError rethrows or wraps the run's fatal failure isn't taken for a nested run")
    void onRunErrorRethrowsTheRunsFatalFailure(String how) {
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            throw new IllegalStateException("pricing cache blew up", oom);
        }), HashMap::new, new RuleListener() {
            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                throw "rethrows".equals(how) ? error : new IllegalStateException("audit of failed run failed", error);
            }
        });

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of("Failed to execute action for rule 'outer-rule': pricing cache blew up"),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("a second listener whose onRunError rethrows the run's fatal failure, which a nested run logged, has"
            + " that logged")
    void secondListenerRethrowsTheRunsNestedFatalFailure() {
        Runnable nested = running(engine("inner-rule", doing(() -> {
            throw new IllegalStateException("pricing cache blew up", oom);
        }), HashMap::new));
        RuleListener[] rethrowing = new RuleListener[2];
        for (int i = 0; i < rethrowing.length; i++) {
            rethrowing[i] = new RuleListener() {
                @Override
                public void onRunError(RunContext run, RuntimeException error) {
                    throw error;
                }
            };
        }
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(nested), HashMap::new, rethrowing);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        // The first listener's is the error run() rethrows; only the second's is logged.
        assertSame(oom, outcome.thrown());
        assertEquals(List.of("Listener threw exception in onRunError: " + ReportedFailure.class.getName()
                + ": Failed to execute action for rule 'outer-rule': simulated heap exhaustion"), outcome.lines("WARN"),
                outcome.logs());
    }

    @Test
    @DisplayName("an action that wraps a nested run's fatal Error in its own exception has that logged once, with the"
            + " Error as a note, and the Error logged once and rethrown")
    void wrappedNestedFatalLogged() {
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                nested.run();
            } catch (OutOfMemoryError e) {
                throw new IllegalStateException("audit write failed", e);
            }
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, "Failed to execute action for rule 'outer-rule': audit write failed"
                + AFTER_NESTED_FATAL), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
        assertEquals(2, onError.get(), "onError calls");
        assertEquals(2, onRunError.get(), "onRunError calls");
    }

    @Test
    @DisplayName("an action that wraps a nested run's fatal Error in its own exception with a long message has that"
            + " message logged shortened, with the Error as a note")
    void wrappedNestedFatalLongMessageShortened() {
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                nested.run();
            } catch (OutOfMemoryError e) {
                throw new IllegalStateException("x".repeat(5_000), e);
            }
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, "Failed to execute action for rule 'outer-rule': "
                + "x".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (4000 more characters)" + AFTER_NESTED_FATAL),
                outcome.lines("ERROR"), outcome.logs());
    }

    // A fatal Error a nested run logged, wrapped in an exception of its own

    /** Runs {@code nested}, and wraps the fatal {@link Error} it throws in an exception with a message of its own. */
    private static Runnable wrapping(Runnable nested) {
        return () -> {
            try {
                nested.run();
            } catch (OutOfMemoryError e) {
                throw new IllegalStateException("audit write failed", e);
            }
        };
    }

    private static Runnable rejecting(Runnable nested) {
        return () -> {
            try {
                nested.run();
            } catch (OutOfMemoryError e) {
                throw new IllegalArgumentException("audit write failed", e);
            }
        };
    }

    private void assertWrapperLogged(Outcome<Throwable> outcome, String wrapper) {
        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, wrapper + ": audit write failed" + AFTER_NESTED_FATAL),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("an output supplier that wraps its run()'s fatal Error in its own exception has that logged once")
    void factoryWrappedNestedFatal() {
        Runnable nested = wrapping(running(throwingOom()));
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> { }), () -> {
            nested.run();
            return new HashMap<>();
        });

        assertWrapperLogged(failed(() -> engine.run(new FactMap<>())), "Output factory threw");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeRun", "beforeExecute", "afterExecute"})
    @DisplayName("a listener that wraps its run()'s fatal Error in its own exception has that logged once, at ERROR")
    void listenerWrappedNestedFatal(String callback) {
        Runnable nested = wrapping(running(throwingOom()));
        RuleListener listener = switch (callback) {
            case "beforeRun" -> onBeforeRun(nested);
            case "beforeExecute" -> onBeforeExecute(nested);
            default -> onAfterExecute(nested);
        };
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", listener);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertWrapperLogged(outcome, "A listener threw java.lang.OutOfMemoryError in " + callback
                + ("beforeRun".equals(callback) ? "" : " for rule 'outer-rule'"));
    }

    @Test
    @DisplayName("a listener whose onRunError wraps its own run()'s fatal Error has that logged once, at ERROR")
    void onRunErrorWrappedNestedFatal() {
        Runnable nested = wrapping(running(throwingOom()));
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            throw new IllegalStateException("outer rule failed");
        }), HashMap::new, new RuleListener() {
            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                nested.run();
            }
        });

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of("Failed to execute action for rule 'outer-rule': outer rule failed", INNER_FATAL,
                "A listener threw java.lang.OutOfMemoryError in onRunError: audit write failed" + AFTER_NESTED_FATAL),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    /**
     * The rule's wrapper is logged once; and the failure a listener is told of is the run's own, although the fatal
     * {@link Error} in it was logged by a run nested in the rule's action, so what the listener wraps around that adds
     * no line.
     */
    @Test
    @DisplayName("a listener whose onRunError wraps the run's failure, itself a wrapped nested fatal Error, adds"
            + " no line")
    void onRunErrorWrapsTheRunsWrappedNestedFatal() {
        Runnable nested = wrapping(running(throwingOom()));
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(nested), HashMap::new,
                new RuleListener() {
                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        throw new IllegalStateException("audit of failed run failed", error);
                    }
                });

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertWrapperLogged(outcome, "Failed to execute action for rule 'outer-rule'");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load(), compiling", "validate(), compiling", "load(), warmUp()", "load(), newSession()",
        "run(), newSession()", "run(), a fact's name", "load(), a declared fact's name",
        "validate(), a declared fact's name"})
    @DisplayName("a language that wraps its run()'s fatal Error in its own exception has that logged once")
    void languageWrappedNestedFatal(String where) {
        Runnable nested = wrapping(running(throwingOom()));
        StubExpressionLanguage stub = new StubExpressionLanguage();
        ExpressionLanguage language = switch (where) {
            case "load(), warmUp()" -> warmingUp(nested);
            case "load(), newSession()", "run(), newSession()" -> stub.newSession(() -> {
                nested.run();
                return Session.none();
            });
            case "load(), compiling", "validate(), compiling" -> stub.compileAction(expression -> {
                nested.run();
                return (action, session) -> ActionResult.done();
            });
            default -> stub.checkFactName(name -> nested.run());
        };
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(language).copiesAtLoad(where.startsWith("load()") ? 1 : 0);
        if (where.endsWith("declared fact's name")) {
            builder.fact("x", Integer.class);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        List<Rule> rules = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        Outcome<Throwable> outcome = failed(() -> {
            if (where.startsWith("validate")) {
                engine.validate(rules);
            } else {
                engine.load(rules);
                engine.run(facts);
            }
        });

        assertWrapperLogged(outcome, switch (where) {
            case "load(), compiling", "validate(), compiling" -> "Action for rule 'r' failed to compile";
            case "load(), warmUp()" -> "The 'warming' expression language failed to warm up a session";
            case "load(), newSession()", "run(), newSession()" -> "The 'stub' expression language failed to create a"
                    + " session";
            default -> "The 'stub' expression language failed to check fact name 'x'";
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"run(), a fact's name", "load(), a declared fact's name",
        "validate(), a declared fact's name"})
    @DisplayName("a language that rejects a fact name with an IllegalArgumentException wrapping its run()'s fatal"
            + " Error has that logged once, then rethrown")
    void languageRejectionWrappingNestedFatal(String where) {
        Runnable nested = rejecting(running(throwingOom()));
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new StubExpressionLanguage().checkFactName(name -> nested.run()));
        if (where.endsWith("declared fact's name")) {
            builder.fact("x", Integer.class);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        List<Rule> rules = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        Outcome<Throwable> outcome = failed(() -> {
            if (where.startsWith("validate")) {
                engine.validate(rules);
            } else {
                engine.load(rules);
                engine.run(facts);
            }
        });

        assertWrapperLogged(outcome, "The 'stub' expression language failed to check fact name 'x'");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load()", "validate()", "load(), through its language's run()"})
    @DisplayName("an action that wraps a nested load()'s or validate()'s fatal Error in its own exception names the"
            + " load")
    void wrappedNestedLoadFatal(String how) {
        boolean throughARun = how.endsWith("run()");
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> loading = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().compileAction(expression -> {
                    if (throughARun) {
                        nested.run();
                    }
                    throw oom;
                })).build();
        List<Rule> rules = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                if (how.startsWith("validate")) {
                    loading.validate(rules);
                } else {
                    loading.load(rules);
                }
            } catch (OutOfMemoryError e) {
                throw new IllegalStateException("audit write failed", e);
            }
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(throughARun ? INNER_FATAL : "Action for rule 'r' failed to compile: simulated heap"
                        + " exhaustion", "Failed to execute action for rule 'outer-rule': audit write failed (after a"
                        + " nested load() failed: java.lang.OutOfMemoryError: simulated heap exhaustion)"),
                outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("a fatal Error wrapped at two levels has each wrapper logged once, and the Error once")
    void wrappedNestedFatalAtTwoLevels() {
        RulesEngine<Map<String, Object>> middle = engine("mid-rule", doing(wrapping(running(throwingOom()))),
                HashMap::new);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(wrapping(running(middle))),
                HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, "Failed to execute action for rule 'mid-rule': audit write failed"
                + AFTER_NESTED_FATAL, "Failed to execute action for rule 'outer-rule': audit write failed"
                + AFTER_NESTED_FATAL), outcome.lines("ERROR"), outcome.logs());
    }

    /**
     * A fatal {@link Error} a nested run logged, which the code around it kept and a later nested run's rule wraps
     * itself, is that run's own, not one a run it started logged, so the wrapper is logged with the error as a note
     * that says it was logged already, not that a nested run failed. So is the {@link OutOfMemoryError} the JVM throws
     * again and again.
     */
    @Test
    @DisplayName("a fatal Error a sibling nested run logged, wrapped by a later one's rule, is noted as logged already")
    void siblingNestedFatalWrappedNotedAsLogged() {
        RulesEngine<Map<String, Object>> first = throwingOom();
        AtomicReference<OutOfMemoryError> kept = new AtomicReference<>();
        RulesEngine<Map<String, Object>> second = engine("second-rule", doing(() -> {
            throw new IllegalStateException("audit write failed", kept.get());
        }), HashMap::new);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                first.run(new FactMap<>());
            } catch (OutOfMemoryError e) {
                kept.set(e);
            }
            second.run(new FactMap<>());
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, "Failed to execute action for rule 'second-rule': audit write failed (caused"
                + " by java.lang.OutOfMemoryError: simulated heap exhaustion, already logged)"),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    /**
     * A fatal {@link Error} a run or a {@code load()} nested one level deeper than a later sibling run logged, which
     * the code around them kept and the sibling's rule wraps, wasn't logged below that rule either: the run that
     * logged it had ended before the sibling started, however deep it was.
     *
     * @param how {@code run()} or {@code load()}, what the first nested run's rule starts
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"run()", "load()"})
    @DisplayName("a fatal Error a deeper sibling's nested run() or load() logged, wrapped by a later one's rule, is"
            + " noted as logged already")
    void deeperSiblingNestedFatalWrappedNotedAsLogged(String how) {
        boolean load = "load()".equals(how);
        RulesEngine<Map<String, Object>> loading = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().compileAction(expression -> {
                    throw oom;
                })).build();
        List<Rule> rules = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
        RulesEngine<Map<String, Object>> first = engine("mid-rule", doing(load ? () -> loading.load(rules)
                : running(throwingOom())), HashMap::new);
        AtomicReference<OutOfMemoryError> kept = new AtomicReference<>();
        RulesEngine<Map<String, Object>> second = engine("second-rule", doing(() -> {
            throw new IllegalStateException("audit write failed", kept.get());
        }), HashMap::new);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                first.run(new FactMap<>());
            } catch (OutOfMemoryError e) {
                kept.set(e);
            }
            second.run(new FactMap<>());
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(load ? "Action for rule 'r' failed to compile: simulated heap exhaustion" : INNER_FATAL,
                "Failed to execute action for rule 'second-rule': audit write failed (caused by"
                        + " java.lang.OutOfMemoryError: simulated heap exhaustion, already logged)"),
                outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    /**
     * Runs an engine with a fact it rejects, as a nested run that fails without a rule's failure does: it logs the
     * rejection and throws it as is.
     */
    private static Runnable rejectedFacts(RulesEngine<Map<String, Object>> engine) {
        return () -> {
            FactMap<Object> facts = new FactMap<>();
            facts.setValue("output", 1);
            engine.run(facts);
        };
    }

    /** What a nested run that fails logs and throws: its rule's failure, or the facts it rejects. */
    private Runnable failingNested(boolean rejected) {
        return rejected ? rejectedFacts(plain("inner-rule")) : running(failing());
    }

    /** Runs {@code nested} and keeps what it throws, as code that handles a nested run's failure and goes on does. */
    private static Runnable keeping(Runnable nested, AtomicReference<RuntimeException> kept) {
        return () -> {
            try {
                nested.run();
            } catch (RuntimeException e) {
                kept.set(e);
            }
        };
    }

    /** An engine whose rule wraps what was kept in an exception of its own. */
    private RulesEngine<Map<String, Object>> wrappingKept(String ruleName, AtomicReference<RuntimeException> kept) {
        return engine(ruleName, doing(() -> {
            throw new IllegalStateException("own words", kept.get());
        }), HashMap::new);
    }

    /**
     * A failure a nested run logged, a rule's or the facts it rejected, which the code around it kept and a later
     * sibling's rule wraps in an exception of its own, wasn't logged by a run that rule started: the run that logged it
     * had ended before the sibling started, at the sibling's depth or one deeper. The wrapper is logged with the
     * failure as a note that says it was logged already, as for a fatal {@link Error}, and each line once.
     *
     * @param how Whose failure, and of what kind
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a sibling's failure", "a sibling's rejected facts", "a deeper sibling's failure",
            "a deeper sibling's rejected facts"})
    @DisplayName("#904: a failure an earlier nested run logged, wrapped by a later sibling's rule, is noted as logged"
            + " already")
    void siblingNestedFailureWrappedNotedAsLogged(String how) {
        boolean rejected = how.endsWith("rejected facts");
        AtomicReference<RuntimeException> kept = new AtomicReference<>();
        Runnable first = how.contains("deeper")
                ? running(engine("mid-rule", doing(keeping(failingNested(rejected), kept)), HashMap::new))
                : keeping(failingNested(rejected), kept);
        RulesEngine<Map<String, Object>> second = wrappingKept("second-rule", kept);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            first.run();
            second.run(new FactMap<>());
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        String logged = rejected ? OUTPUT_REJECTED : INNER_FAILURE;
        String wrapper = "Failed to execute action for rule 'second-rule': own words (caused by " + logged
                + ", already logged)";
        assertEquals(List.of(logged, wrapper), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
        assertEquals("Failed to execute action for rule 'outer-rule': a nested run() failed: " + wrapper,
                outcome.thrown().getMessage());
    }

    /**
     * The rule that started the nested run, wrapping its failure in an exception of its own, still names it as a
     * nested run's failure: a guard for #904's fix, which tells this case from a sibling's.
     *
     * @param kind A rule's failure, or rejected facts
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a failure", "rejected facts"})
    @DisplayName("#904 guard: the rule that started a nested run, wrapping its failure, notes a nested run() failed")
    void starterWrappingNestedFailureNotesNestedRun(String kind) {
        boolean rejected = "rejected facts".equals(kind);
        Runnable nested = failingNested(rejected);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
            try {
                nested.run();
            } catch (RuntimeException e) {
                throw new IllegalStateException("own words", e);
            }
        }), HashMap::new);

        Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

        String logged = rejected ? OUTPUT_REJECTED : INNER_FAILURE;
        String wrapper = "Failed to execute action for rule 'outer-rule': own words (after a nested run() failed: "
                + logged + ")";
        assertEquals(List.of(logged, wrapper), outcome.lines("ERROR"), outcome.logs());
        assertEquals(wrapper, outcome.thrown().getMessage());
    }

    /**
     * A failure kept from an earlier outermost run, a nested run's or the outermost run's own, and wrapped by a later
     * run's rule, wasn't logged by a run that rule started. A rule's failure says which outermost run built it, so it's
     * noted as logged already; the facts a nested run rejected say nothing of it, and the thread's record of them is
     * forgotten when the outermost run ends, so the wrapper reads plain, as for a fatal {@link Error}.
     *
     * @param how Where the failure kept came from, and of what kind
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"an earlier outermost run's nested failure", "an earlier outermost run's nested rejected"
            + " facts", "an earlier outermost run's own failure"})
    @DisplayName("#904: a failure kept from an earlier outermost run, wrapped by a later run's rule, isn't noted as a"
            + " nested run's")
    void earlierOutermostFailureWrappedNotNested(String how) {
        boolean rejected = how.endsWith("rejected facts");
        AtomicReference<RuntimeException> kept = new AtomicReference<>();
        Runnable earlier = how.endsWith("own failure") ? keeping(running(failing()), kept)
                : running(engine("earlier-rule", doing(keeping(failingNested(rejected), kept)), HashMap::new));
        RulesEngine<Map<String, Object>> later = wrappingKept("later-rule", kept);

        Outcome<Throwable> outcome = failed(() -> {
            earlier.run();
            later.run(new FactMap<>());
        });

        String logged = rejected ? OUTPUT_REJECTED : INNER_FAILURE;
        String wrapper = "Failed to execute action for rule 'later-rule': own words"
                + (rejected ? "" : " (caused by " + logged + ", already logged)");
        assertEquals(List.of(logged, wrapper), outcome.lines("ERROR"), outcome.logs());
        assertEquals(wrapper, outcome.thrown().getMessage());
    }

    /**
     * A run an action hands to another thread and waits for isn't nested, but the failure it reports is still named as
     * a nested run's when the action wraps it, as {@code docs/nested-runs.md} says: a guard for #904's fix, which reads
     * where a failure was logged only for one built on the same thread.
     */
    @Test
    @DisplayName("#904 guard: a run's failure from another thread, wrapped by the rule that waited for it, notes a"
            + " nested run() failed")
    void otherThreadFailureWrappedNotesNestedRun() {
        RulesEngine<Map<String, Object>> inner = failing();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> {
                Future<?> run = executor.submit(running(inner));
                try {
                    run.get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("own words", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }), HashMap::new);

            Outcome<Throwable> outcome = failed(() -> engine.run(new FactMap<>()));

            String wrapper = "Failed to execute action for rule 'outer-rule': own words (after " + NESTED_FAILURE
                    + ")";
            assertEquals(List.of(INNER_FAILURE, wrapper), outcome.lines("ERROR"), outcome.logs());
            assertEquals(wrapper, outcome.thrown().getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A pooled thread keeps nothing of a run that has ended: a nested run's failure kept by one task and wrapped by a
     * later task's run on the same thread is noted as logged already, not as a nested run's, and each line is logged
     * once. A guard for #904's fix, which records where each failure a run reports was logged.
     */
    @Test
    @DisplayName("#904 guard: a failure kept by one task on a pooled thread, wrapped by a later task's run there, is"
            + " noted as logged already")
    void pooledThreadKeepsNothingOfAnEndedRun() throws Exception {
        AtomicReference<RuntimeException> kept = new AtomicReference<>();
        RulesEngine<Map<String, Object>> earlier = engine("earlier-rule", doing(keeping(running(failing()), kept)),
                HashMap::new);
        RulesEngine<Map<String, Object>> later = wrappingKept("later-rule", kept);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Thread> threads = new AtomicReference<>();
            Outcome<Throwable> outcome = failed(() -> {
                executor.submit(() -> {
                    threads.set(Thread.currentThread());
                    earlier.run(new FactMap<>());
                }).get();
                try {
                    executor.submit(() -> {
                        assertSame(threads.get(), Thread.currentThread(), "the same pooled thread");
                        later.run(new FactMap<>());
                    }).get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            });

            String wrapper = "Failed to execute action for rule 'later-rule': own words (caused by " + INNER_FAILURE
                    + ", already logged)";
            assertEquals(List.of(INNER_FAILURE, wrapper), outcome.lines("ERROR"), outcome.logs());
            assertEquals(wrapper, outcome.thrown().getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a failure", "a fatal failure", "a stop"})
    @DisplayName("a listener whose onError wraps its own run()'s fatal Error has that logged once, at ERROR")
    void onErrorWrappedNestedFatal(String closing) {
        OutOfMemoryError ruleError = new OutOfMemoryError("the rule's own");
        boolean stop = "a stop".equals(closing);
        // A run started once the run around it must stop stops at once, so onError closing a stop loads instead.
        RulesEngine<Map<String, Object>> loading = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().compileAction(expression -> {
                    throw oom;
                })).build();
        Runnable nested = wrapping(stop
                ? () -> loading.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()))
                : running(throwingOom()));
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", (action, session) -> {
            switch (closing) {
                case "a failure" -> throw new IllegalStateException("outer rule failed");
                case "a fatal failure" -> throw ruleError;
                default -> {
                    while (!action.isCancelled()) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                    return ActionResult.done();
                }
            }
        }, HashMap::new, new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                nested.run();
            }
        });

        Outcome<Throwable> outcome = failed(() -> engine.runWithResult(new FactMap<>(),
                stop ? RunOptions.withTimeoutOf(Duration.ofMillis(50)) : RunOptions.defaults()));

        assertSame("a fatal failure".equals(closing) ? ruleError : oom, outcome.thrown());
        String wrapper = "A listener threw java.lang.OutOfMemoryError in onError for rule 'outer-rule': audit write"
                + " failed" + (stop ? AFTER_NESTED_FATAL.replace("run()", "load()") : AFTER_NESTED_FATAL);
        assertEquals(switch (closing) {
            case "a failure" -> List.of("Failed to execute action for rule 'outer-rule': outer rule failed",
                    INNER_FATAL, wrapper);
            case "a fatal failure" -> List.of("Failed to execute action for rule 'outer-rule': the rule's own",
                    INNER_FATAL, wrapper);
            default -> List.of("Action for rule 'r' failed to compile: simulated heap exhaustion", wrapper);
        }, outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN").stream().filter(line -> line.startsWith("Listener")).toList(),
                outcome.logs());
    }

    @Test
    @DisplayName("a session whose close() wraps its run()'s fatal Error gets a WARN line with the Error as a note")
    void sessionCloseWrappedNestedFatal() {
        RulesEngine<Map<String, Object>> engine = closingWith(wrapping(running(throwingOom())));

        Outcome<Throwable> outcome = failed(engine::close);

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of("The 'stub' expression language failed to close a session: audit write failed"
                + AFTER_NESTED_FATAL), outcome.lines("WARN"), outcome.logs());
    }
}
