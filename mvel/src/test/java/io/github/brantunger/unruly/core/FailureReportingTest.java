package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static io.github.brantunger.unruly.core.EngineLoggingTest.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the engine logs and tells listeners when something fails: text a language or an application wrote is escaped
 * wherever it reaches a message (#357), only a failure an engine already reported counts as a nested run's (#358),
 * every run a listener opened is closed and names the rule a fatal error came from (#359), and a failure's cause is
 * neither lost nor hidden (#364).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("failures are logged once, escaped, and reported to every listener with their rule and cause")
class FailureReportingTest {

    private static final String FORGED = "[main] INFO com.example.Audit - forged entry";

    /**
     * A language whose conditions are compiled by {@code condition} and whose actions all run {@code action}. Its
     * sessions hold nothing, unless {@code stateful}: then each copy of the rules gets a session of its own, so a
     * limit on copies applies.
     */
    private record ToyLanguage(BiFunction<CompileContext, Expression, CompiledCondition> condition,
                               CompiledAction action, boolean stateful) implements ExpressionLanguage {

        ToyLanguage(BiFunction<CompileContext, Expression, CompiledCondition> condition, CompiledAction action) {
            this(condition, action, false);
        }

        @Override
        public String name() {
            return "toy";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return condition.apply(context, expression);
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return action;
                }

                @Override
                public Session newSession() {
                    return stateful ? new Session() { } : Session.none();
                }
            };
        }
    }

    /** Records the run callbacks and failures it receives. */
    private static final class Recorder implements RuleListener {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<RuntimeException> runErrors = new CopyOnWriteArrayList<>();
        private final List<RuleExecutionException> ruleErrors = new CopyOnWriteArrayList<>();

        @Override
        public void beforeRun(RunContext run) {
            calls.add("beforeRun");
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            calls.add("afterRun");
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            calls.add("onRunError");
            runErrors.add(error);
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            ruleErrors.add(error);
        }
    }

    private static final CompiledAction DONE = (context, session) -> ActionResult.done();

    private static ToyLanguage conditions(CompiledCondition condition) {
        return new ToyLanguage((context, expression) -> condition, DONE);
    }

    private static RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).defaultLanguage(language.name());
    }

    private static List<Rule> rule(String name) {
        return List.of(Rule.builder().ruleName(name).condition("c").action("a").build());
    }

    // ---- #357: text a language or the application wrote is escaped ----

    @Test
    @DisplayName("a compile error's message is escaped in the exception and in the log")
    void compileErrorIsEscaped() {
        RulesEngine<Map<String, Object>> engine = builder(new ToyLanguage((context, expression) -> {
            throw new InvalidExpressionException("bad\n" + FORGED);
        }, DONE)).build();
        AtomicReference<RuleCompilationException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(assertThrows(RuleCompilationException.class,
                () -> engine.load(rule("r1")))));

        assertFalse(thrown.get().getMessage().contains("\n"), thrown.get().getMessage());
        assertTrue(thrown.get().getMessage().contains("bad\\n" + FORGED), thrown.get().getMessage());
        assertFalse(logs.contains("\n" + FORGED), logs);
        assertEquals("bad\n" + FORGED, thrown.get().getCause().getMessage(),
                "what the language threw keeps the text it wrote");
    }

    @Test
    @DisplayName("a compile warning's message is escaped in the log")
    void compileWarningIsEscaped() {
        RulesEngine<Map<String, Object>> engine = builder(new ToyLanguage((context, expression) -> {
            context.warn(expression, new InvalidExpressionException.Issue(
                    InvalidExpressionException.Issue.Severity.WARNING, 1, 1, "careful\n" + FORGED));
            return (evaluation, session) -> true;
        }, DONE)).build();

        String logs = logsOf(() -> engine.load(rule("r2")));

        assertTrue(logs.contains("careful\\n" + FORGED), logs);
        assertFalse(logs.contains("\n" + FORGED), logs);
    }

    @Test
    @DisplayName("what an output factory threw is escaped in the exception and in the log")
    void outputFactoryFailureIsEscaped() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(() -> {
            throw new IllegalStateException("no connection\n" + FORGED);
        }).language(conditions((context, session) -> true)).defaultLanguage("toy").build();
        engine.load(rule("r"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(assertThrows(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()))));

        assertEquals("Output factory threw java.lang.IllegalStateException: no connection\\n" + FORGED,
                thrown.get().getMessage());
        assertFalse(logs.contains("\n" + FORGED), logs);
    }

    // ---- #358: only a failure an engine reported counts as a nested run's ----

    @Test
    @DisplayName("a RuleExecutionException a language throws itself is logged at ERROR and escaped")
    void aLanguagesOwnRuleExecutionExceptionIsLogged() {
        RulesEngine<Map<String, Object>> engine = builder(conditions((context, session) -> {
            throw new RuleExecutionException("own failure\n" + FORGED);
        })).build();
        engine.load(rule("r"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(assertThrows(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()))));

        String message = thrown.get().getMessage();
        assertEquals("Failed to evaluate condition for rule 'r': own failure\\n" + FORGED, message);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + message), logs);
    }

    @Test
    @DisplayName("a failed run of another engine, started from a rule, is logged by that engine only")
    void aNestedRunOfAnotherEngineIsLoggedOnce() {
        RulesEngine<Map<String, Object>> inner = builder(conditions((context, session) -> {
            throw new IllegalStateException("inner boom");
        })).build();
        inner.load(rule("inner"));
        RulesEngine<Map<String, Object>> outer = builder(conditions((context, session) -> {
            inner.run(new FactMap<>());
            return true;
        })).build();
        outer.load(rule("outer"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(assertThrows(RuleExecutionException.class,
                () -> outer.run(new FactMap<>()))));

        assertEquals("Failed to evaluate condition for rule 'outer': a nested run() failed: "
                + "Failed to evaluate condition for rule 'inner': inner boom", thrown.get().getMessage());
        assertEquals(1, logs.split("ERROR " + ENGINE_LOGGER, -1).length - 1, logs);
    }

    // ---- #359: every listener's run is closed, naming the rule a fatal error came from ----

    @Test
    @DisplayName("a fatal Error from a listener's beforeRun closes every listener's run with onRunError")
    void fatalErrorFromBeforeRun() {
        Recorder recorder = new Recorder();
        RuleListener fatal = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                throw new OutOfMemoryError("listener");
            }
        };
        RulesEngine<Map<String, Object>> engine = builder(conditions((context, session) -> true))
                .listener(fatal).listener(recorder).build();
        engine.load(rule("r"));

        assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        assertEquals(List.of("beforeRun", "onRunError"), recorder.calls);
        assertSame(OutOfMemoryError.class, recorder.runErrors.get(0).getCause().getClass());
    }

    @Test
    @DisplayName("a fatal Error from beforeRun of a run that stopped waiting for a copy closes that run too")
    void fatalErrorFromBeforeRunWhileWaitingForACopy() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recorder recorder = new Recorder();
        RuleListener fatalForWaiter = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                if (Thread.currentThread().getName().equals("waiter")) {
                    throw new OutOfMemoryError("listener");
                }
            }
        };
        RulesEngine<Map<String, Object>> engine = builder(new ToyLanguage((context, expression) -> (c, session) -> {
            holding.countDown();
            return release.await(30, TimeUnit.SECONDS);
        }, DONE, true)).listener(fatalForWaiter).listener(recorder).maxCopies(1).build();
        engine.load(rule("hold"));
        Thread holder = new Thread(() -> engine.run(new FactMap<>()), "holder");
        holder.start();
        assertTrue(holding.await(30, TimeUnit.SECONDS), "the first run never started");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        List<String> waiterCalls = new CopyOnWriteArrayList<>();
        Thread waiter = new Thread(() -> {
            try {
                engine.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofMillis(50)));
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                waiterCalls.addAll(recorder.calls);
            }
        }, "waiter");

        String logs = logsOf(() -> {
            waiter.start();
            assertDoesNotThrow(() -> waiter.join(TimeUnit.SECONDS.toMillis(30)));
        });
        release.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(30));

        assertTrue(logs.contains("while waiting for a compiled copy of the rules"), logs);
        assertInstanceOf(OutOfMemoryError.class, thrown.get());
        assertEquals(List.of("beforeRun", "beforeRun", "onRunError"), waiterCalls,
                "the holder's run opened, then the waiter's run opened and was closed");
    }

    @Test
    @DisplayName("onRunError for a fatal Error in a rule gets the failure onError got, which names the rule")
    void fatalErrorInARuleNamesTheRule() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = builder(conditions((context, session) -> {
            throw new OutOfMemoryError("no room");
        })).listener(recorder).build();
        engine.load(rule("booming"));

        assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        RuleExecutionException runError = assertInstanceOf(RuleExecutionException.class, recorder.runErrors.get(0));
        assertEquals("booming", runError.getRuleName());
        assertSame(recorder.ruleErrors.get(0), runError);
    }

    @Test
    @DisplayName("a fatal Error from a listener's beforeEvaluate reaches onRunError naming the rule")
    void fatalErrorFromARuleCallbackNamesTheRule() {
        Recorder recorder = new Recorder();
        RuleListener fatal = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                throw new OutOfMemoryError("listener");
            }
        };
        RulesEngine<Map<String, Object>> engine = builder(conditions((context, session) -> true))
                .listener(fatal).listener(recorder).build();
        engine.load(rule("watched"));

        assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        assertEquals("watched", ((RuleExecutionException) recorder.runErrors.get(0)).getRuleName());
    }

    @Test
    @DisplayName("a fatal Error from a listener's onError reaches onRunError with the failure onError got")
    void fatalErrorFromOnError() {
        Recorder recorder = new Recorder();
        RuleListener fatal = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw new OutOfMemoryError("listener");
            }
        };
        RulesEngine<Map<String, Object>> engine = builder(conditions((context, session) -> {
            throw new IllegalStateException("boom");
        })).listener(fatal).listener(recorder).build();
        engine.load(rule("failing"));

        assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        assertSame(recorder.ruleErrors.get(0), recorder.runErrors.get(0));
        assertEquals("failing", recorder.ruleErrors.get(0).getRuleName());
        assertInstanceOf(OutOfMemoryError.class, recorder.runErrors.get(0).getSuppressed()[0],
                "onRunError can tell the run failed with a fatal error");
    }

    @Test
    @DisplayName("a fatal Error from onError for a stopped run reaches onRunError with the stop onError got")
    void fatalErrorFromOnErrorForAStop() {
        Recorder recorder = new Recorder();
        RuleListener fatal = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw new OutOfMemoryError("listener");
            }
        };
        RulesEngine<Map<String, Object>> engine = builder(conditions((context, session) -> {
            Thread.sleep(200);
            return true;
        })).listener(fatal).listener(recorder).build();
        engine.load(rule("slow"));

        assertThrows(OutOfMemoryError.class,
                () -> engine.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofMillis(20))));

        assertSame(recorder.ruleErrors.get(0), recorder.runErrors.get(0));
        assertNull(recorder.ruleErrors.get(0).getRuleName(), "a stop names no rule");
        assertInstanceOf(OutOfMemoryError.class, recorder.runErrors.get(0).getSuppressed()[0],
                "onRunError can tell the run failed with a fatal error");
    }

    @Test
    @DisplayName("with nested runs, each run's onRunError names the rule of its own engine")
    void nestedFatalErrorsNameTheirOwnRules() {
        Recorder innerRecorder = new Recorder();
        Recorder outerRecorder = new Recorder();
        RulesEngine<Map<String, Object>> inner = builder(conditions((context, session) -> {
            throw new OutOfMemoryError("no room");
        })).listener(innerRecorder).build();
        inner.load(rule("inner"));
        RulesEngine<Map<String, Object>> outer = builder(conditions((context, session) -> {
            inner.run(new FactMap<>());
            return true;
        })).listener(outerRecorder).build();
        outer.load(rule("outer"));

        assertThrows(OutOfMemoryError.class, () -> outer.run(new FactMap<>()));

        assertEquals("inner", ((RuleExecutionException) innerRecorder.runErrors.get(0)).getRuleName());
        assertEquals("outer", ((RuleExecutionException) outerRecorder.runErrors.get(0)).getRuleName());
    }

    @Test
    @DisplayName("a fatal Error from beforeRun names no rule, even after a rule's fatal Error on the same thread")
    void fatalErrorOutsideARuleNamesNoRule() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> booming = builder(conditions((context, session) -> {
            throw new OutOfMemoryError("no room");
        })).listener(recorder).build();
        booming.load(rule("booming"));
        assertThrows(OutOfMemoryError.class, () -> booming.run(new FactMap<>()));
        RuleListener fatal = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                throw new OutOfMemoryError("listener");
            }
        };
        RulesEngine<Map<String, Object>> engine = builder(conditions((context, session) -> true))
                .listener(fatal).listener(recorder).build();
        engine.load(rule("quiet"));

        assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        RuleExecutionException runError = (RuleExecutionException) recorder.runErrors.get(1);
        assertNull(runError.getRuleName());
        assertEquals("The run failed with java.lang.OutOfMemoryError: listener", runError.getMessage());
    }

    // ---- #364: a failure's cause is neither lost nor hidden ----

    @Test
    @DisplayName("an OutputWriter's InvocationTargetException with no cause fails the rule, not with a bare NPE")
    void invocationTargetExceptionWithoutACause() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .outputWriter((output, property, value) -> {
                    throw new InvocationTargetException(null);
                })
                .language(new ToyLanguage((context, expression) -> (evaluation, session) -> true,
                        (context, session) -> ActionResult.set(Map.of("score", 1))))
                .defaultLanguage("toy").listener(recorder).build();
        engine.load(rule("writes"));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("writes", thrown.getRuleName());
        assertInstanceOf(InvocationTargetException.class, thrown.getCause());
        assertEquals(1, recorder.ruleErrors.size(), "the open beforeExecute was closed");
    }

    @Test
    @DisplayName("a cause with no message in the middle of the chain doesn't hide the root cause's message")
    void rootCauseBelowAMessagelessException() {
        RuntimeException root = new RuntimeException("static init boom");
        RuntimeException mvel = new RuntimeException("[Error: s.get(): null]", new ExceptionInInitializerError(root));

        assertEquals("[Error: s.get(): null] (caused by java.lang.RuntimeException: static init boom)",
                Failures.describe(mvel));
    }

    @Test
    @DisplayName("an exception with no message is described by its class and its root cause's message")
    void messagelessExceptionWithARootCause() {
        RuntimeException wrapper = new RuntimeException(null, new IllegalStateException("root\nline"));

        assertEquals("java.lang.RuntimeException (caused by java.lang.IllegalStateException: root\\nline)",
                Failures.describe(wrapper));
    }

    @Test
    @DisplayName("the root cause's message isn't repeated when the description already includes it")
    void rootCauseMessageAlreadyIncluded() {
        RuntimeException mvel = new RuntimeException("[Error: b.boom(): no room]",
                new InvocationTargetException(new IllegalStateException("no room")));

        assertEquals("[Error: b.boom(): no room]", Failures.describe(mvel));
    }
}
