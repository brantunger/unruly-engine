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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@code run()} started on the same thread while another run is in progress, from an action, the output supplier, a
 * listener callback or a language, logs its own failure, and the code around it doesn't log that failure again: the
 * output supplier's failure reads {@code Output factory threw: a nested run() failed: } and the innermost failure, a
 * listener's WARN line names the innermost failure the same way, and a language's failure to create a session or
 * check a fact name isn't logged a second time. A fatal {@link Error}, which every run rethrows unchanged, is logged
 * once, by the first run that logs it, however many runs it passes through, and listeners are still told of it at
 * every level; once the outermost run on the thread has ended, the same instance is logged again.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a nested run's failure, and a fatal Error, are logged once")
class NestedRunFailureLogTest {

    private static final String INNER_FAILURE = "Failed to execute action for rule 'inner-rule': inner rule failed";
    private static final String NESTED_FAILURE = "a nested run() failed: " + INNER_FAILURE;
    private static final String INNER_FATAL = "Failed to execute action for rule 'inner-rule': simulated heap "
            + "exhaustion";

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

    /**
     * Returns the messages the engine logged at one level, in order.
     *
     * @param logs  What was logged
     * @param level {@code ERROR} or {@code WARN}
     * @return The messages, without the thread, level and logger
     */
    private static List<String> logged(String logs, String level) {
        String prefix = level + " " + ENGINE_LOGGER;
        return logs.lines().filter(line -> line.contains(prefix))
                .map(line -> line.substring(line.indexOf(prefix) + prefix.length())).toList();
    }

    /** What a run threw, and what the engine logged while it ran. */
    private record Outcome(Throwable thrown, String logs) {
        List<String> errors() {
            return logged(logs, "ERROR");
        }

        List<String> warnings() {
            return logged(logs, "WARN");
        }
    }

    private static Outcome failed(Executable run) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(Throwable.class, run)));
        return new Outcome(thrown.get(), logs);
    }

    private static Outcome returned(Runnable run) {
        return new Outcome(null, logsOf(run));
    }

    private void assertOnlyInnerFatalLogged(Outcome outcome, int onErrors, int onRunErrors) {
        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL), outcome.errors(), outcome.logs());
        assertEquals(onErrors, onError.get(), "onError calls");
        assertEquals(onRunErrors, onRunError.get(), "onRunError calls");
    }

    private void assertFailedWith(Outcome outcome, String message) {
        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals(message, failure.getMessage());
        assertFalse(outcome.logs().contains("ReportedFailure"), outcome.logs());
        assertEquals(List.of(INNER_FAILURE), outcome.errors(), outcome.logs());
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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals("Output factory threw: a nested run() failed: " + innermost.get(), failure.getMessage());
        assertEquals(1, failure.getMessage().split("a nested run\\(\\) failed: ", -1).length - 1,
                failure.getMessage());
        assertEquals(List.of(innermost.get()), outcome.errors(), outcome.logs());
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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        assertFailedWith(outcome, "Failed to execute action for rule 'a-rule': " + NESTED_FAILURE);
    }

    @Test
    @DisplayName("an output supplier's Error below a nested run still fails the rule once the deadline has passed")
    void factoryErrorPastTheDeadline() {
        Duration timeout = Duration.ofMillis(500);
        AtomicReference<Instant> actionStarted = new AtomicReference<>();
        AtomicBoolean reached = new AtomicBoolean();
        RulesEngine<Map<String, Object>> middle = engine("b-rule", doing(() -> { }), () -> {
            reached.set(true);
            // The action started after the outer run did, so this is past the outer run's deadline.
            while (!Instant.now().isAfter(actionStarted.get().plus(timeout))) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            throw new FactoryError("factory broke");
        });
        RulesEngine<Map<String, Object>> engine = engine("a-rule", doing(() -> {
            actionStarted.set(Instant.now());
            middle.run(new FactMap<>());
        }), HashMap::new);

        Outcome outcome = failed(() -> engine.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(timeout)));

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

        Outcome first = failed(() -> {
            if ("load()".equals(which)) {
                loading.load(rules);
            } else {
                loading.validate(rules);
            }
        });
        Outcome second = failed(() -> later.run(new FactMap<>()));

        assertEquals(List.of(INNER_FATAL), first.errors(), first.logs());
        assertEquals(List.of(INNER_FATAL), second.errors(), second.logs());
        assertSame(oom, second.thrown());
    }

    @Test
    @DisplayName("an OutOfMemoryError one run logged is logged again by a later run that throws the same instance")
    void sameFatalLoggedAgainByALaterRun() {
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(running(throwingOom())), HashMap::new);

        Outcome first = failed(() -> engine.run(new FactMap<>()));
        Outcome second = failed(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(INNER_FATAL), first.errors(), first.logs());
        assertEquals(List.of(INNER_FATAL), second.errors(), second.logs());
        assertSame(oom, second.thrown());
    }

    // Listeners

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeRun", "afterExecute"})
    @DisplayName("a listener whose run() fails gets one WARN line naming the nested failure, logged once, at ERROR")
    void listenerNestedFailure(String callback) {
        RulesEngine<Map<String, Object>> inner = failing();
        RuleListener listener = "beforeRun".equals(callback)
                ? onBeforeRun(running(inner)) : onAfterExecute(running(inner));
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", listener);

        Outcome outcome = returned(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(INNER_FAILURE), outcome.errors(), outcome.logs());
        assertEquals(List.of("Listener threw exception in " + callback + ": " + NESTED_FAILURE),
                outcome.warnings(), outcome.logs());
    }

    @Test
    @DisplayName("a listener whose run() runs a listener whose run() fails gets one WARN line, from the middle run")
    void listenerTwoLevels() {
        RulesEngine<Map<String, Object>> mid = plain("mid-rule", onBeforeRun(running(failing())));
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", onBeforeRun(running(mid)));

        Outcome outcome = returned(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(INNER_FAILURE), outcome.errors(), outcome.logs());
        assertEquals(List.of("Listener threw exception in beforeRun: " + NESTED_FAILURE), outcome.warnings(),
                outcome.logs());
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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        switch (callback) {
            case "beforeRun", "afterExecute" -> assertOnlyInnerFatalLogged(outcome, 1, 2);
            case "beforeExecute" -> assertOnlyInnerFatalLogged(outcome, 2, 2);
            default -> assertOnlyInnerFatalLogged(outcome, 1, 3);
        }
        assertEquals(List.of(), outcome.warnings(), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"onError", "onRunError"})
    @DisplayName("a listener whose onError or onRunError runs a failing run() gets one WARN line naming the nested one")
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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        assertEquals(List.of("Failed to execute action for rule 'outer-rule': outer rule failed", INNER_FAILURE),
                outcome.errors(), outcome.logs());
        assertEquals(List.of("Listener threw exception in " + callback + ": " + NESTED_FAILURE), outcome.warnings(),
                outcome.logs());
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

        Outcome outcome = failed(() -> engine.runWithResult(new FactMap<>(),
                stop ? RunOptions.withTimeoutOf(Duration.ofMillis(50)) : RunOptions.defaults()));

        String name = callback.startsWith("onError") ? "onError" : "onRunError";
        assertEquals(List.of("Listener threw exception in " + name + ": java.lang.IllegalStateException: could not "
                + "close the span"), outcome.warnings().stream().filter(line -> line.startsWith("Listener")).toList(),
                outcome.logs());
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

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

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

        Outcome outcome = failed(() -> engine.run(facts));

        if ("fatal".equals(how)) {
            assertOnlyInnerFatalLogged(outcome, 1, 2);
        } else if ("rejected".equals(how)) {
            IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, outcome.thrown());
            assertEquals("rejected", failure.getMessage());
            assertEquals(List.of(INNER_FAILURE), outcome.errors(), outcome.logs());
        } else {
            IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, outcome.thrown());
            assertEquals("The 'stub' expression language failed to check fact name 'x': " + NESTED_FAILURE,
                    failure.getMessage());
            assertEquals(List.of(INNER_FAILURE), outcome.errors(), outcome.logs());
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

        Outcome outcome = failed(() -> loadedWarmingUp(nested));

        RuntimeException failure = assertInstanceOf(RuntimeException.class, outcome.thrown());
        assertEquals("The 'warming' expression language failed to warm up a session: " + NESTED_FAILURE,
                failure.getMessage());
        assertEquals(List.of(INNER_FAILURE), outcome.errors(), outcome.logs());
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

        String logs = logsOf(() -> {
            if (where.startsWith("validate")) {
                problems.set(engine.validate(rules));
            } else {
                problems.set(List.of(assertThrows(RuleCompilationException.class, () -> engine.load(rules))));
            }
        });

        String expected = (compiling ? "Action for rule 'r' failed to compile: " : "Declared fact 'x' can't be used: ")
                + NESTED_FAILURE;
        assertEquals(List.of(expected), problems.get().stream().map(Throwable::getMessage).toList());
        assertEquals(List.of(INNER_FAILURE), logged(logs, "ERROR"), logs);
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

        Outcome outcome = failed(() -> {
            if (where.startsWith("validate")) {
                engine.validate(rules);
            } else {
                engine.load(rules);
            }
        });

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL), outcome.errors(), outcome.logs());
    }

    @Test
    @DisplayName("a fatal Error from a run() a language's warmUp() started, in a load() a rule started, is logged once")
    void warmUpNestedFatalInsideARun() {
        Runnable nested = running(throwingOom());
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", doing(() -> loadedWarmingUp(nested)),
                HashMap::new);

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        assertOnlyInnerFatalLogged(outcome, 2, 2);
    }
}
