package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Code the engine runs may be interrupted while it blocks. The {@link InterruptedException} it throws clears the
 * thread's interrupt status, and reaches the engine wrapped, or suppressed on another exception by a
 * {@code try}-with-resources whose {@code close()} was interrupted (#895), so the engine must set the status again.
 * Each test runs the engine on a thread of its own and reports that thread's interrupt status afterwards.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("an interrupt that reaches the engine inside a failure keeps the thread's interrupt status")
class InterruptStatusTest {

    /** Where the language, listener or output supplier throws. */
    enum Where { NEW_COMPILER, COMPILE, SESSION, CONDITION, ACTION, LISTENER, OUTPUT, FACT_NAME }

    /** The thread's interrupt status after {@code engine} ran into {@code interrupted} at {@code where}. */
    private static boolean interruptStatusAfter(Where where, Exception interrupted) throws InterruptedException {
        return interruptStatusAfterThrowing(where, new IllegalStateException("wrapped by the language", interrupted));
    }

    /**
     * Returns what a {@code try}-with-resources throws when its body fails and its resource's {@code close()} is
     * interrupted: the body's exception, carrying the {@link InterruptedException} only as a suppressed exception.
     */
    private static IllegalStateException suppressingAnInterrupt() {
        IllegalStateException body = new IllegalStateException("body failed");
        body.addSuppressed(new InterruptedException("close interrupted"));
        return body;
    }

    /** The thread's interrupt status after {@code engine} ran into {@code thrown}, as it is, at {@code where}. */
    private static boolean interruptStatusAfterThrowing(Where where, RuntimeException thrown)
            throws InterruptedException {
        AtomicBoolean status = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            logsOf(() -> {
                try {
                    runInto(where, thrown);
                } catch (RuntimeException expected) {
                    // Every place but LISTENER fails the call; the status is what's tested.
                }
            });
            status.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        thread.join();
        return status.get();
    }

    private static void runInto(Where where, RuntimeException thrown) {
        runInto(where, thrown, new RuleListener() {
        });
    }

    private static void runInto(Where where, RuntimeException thrown, RuleListener listener) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                where == Where.OUTPUT
                        ? () -> {
                            throw thrown;
                        }
                        : HashMap::new)
                .language(new ThrowingLanguage(where, thrown)).listener(listener);
        if (where == Where.LISTENER) {
            builder.listener(new RuleListener() {
                @Override
                public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                    throw thrown;
                }
            });
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(Rule.builder().ruleName("r").language("throwing").condition("c").action("a")
                .build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        engine.run(facts);
    }

    /** A language that throws {@code thrown} at {@code where}, and otherwise matches and does nothing. */
    private record ThrowingLanguage(Where where, RuntimeException thrown) implements ExpressionLanguage {

        private <T> T at(Where place, T value) {
            if (where == place) {
                throw thrown;
            }
            return value;
        }

        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return at(Where.NEW_COMPILER, new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    CompiledCondition condition = (evaluationContext, session) -> at(Where.CONDITION, true);
                    return at(Where.COMPILE, condition);
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (actionContext, session) -> at(Where.ACTION, ActionResult.done());
                }

                @Override
                public Session newSession() {
                    return at(Where.SESSION, Session.none());
                }

                @Override
                public void checkFactName(String name) {
                    at(Where.FACT_NAME, name);
                }
            });
        }
    }

    /**
     * A language whose expression interrupts its own thread and then asks whether the run was cancelled, which is how
     * a language decides to give up. Asking is a question, not an answer to the interrupt: the status stays set for
     * the engine's own check and for the caller.
     */
    private record PollingLanguage(Where where) implements ExpressionLanguage {

        private void interruptAndAsk(BooleanSupplier cancelled, Where place) {
            if (where == place) {
                Thread.currentThread().interrupt();
                if (!cancelled.getAsBoolean()) {
                    throw new IllegalStateException("the expression wasn't told the run was cancelled");
                }
            }
        }

        @Override
        public String name() {
            return "polling";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> {
                        interruptAndAsk(evaluation::isCancelled, Where.CONDITION);
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> {
                        interruptAndAsk(action::isCancelled, Where.ACTION);
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Where.class, names = {"CONDITION", "ACTION"})
    @DisplayName("a language that asks isCancelled() on an interrupted thread leaves the interrupt to be seen")
    void askingWhetherTheRunWasCancelledKeepsTheInterrupt(Where where) throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new PollingLanguage(where)).build();
        engine.load(List.of(Rule.builder().ruleName("first").language("polling").condition("c").action("a").build(),
                Rule.builder().ruleName("second").language("polling").condition("c").action("a").build()));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean status = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            logsOf(() -> {
                try {
                    engine.run(new FactMap<>());
                } catch (RuntimeException e) {
                    thrown.set(e);
                }
            });
            status.set(Thread.currentThread().isInterrupted());
        });

        thread.start();
        thread.join();

        RuleExecutionException stopped = assertInstanceOf(RuleExecutionException.class, thrown.get(),
                "the run carried on although its thread was interrupted");
        assertTrue(stopped.getMessage().contains("was interrupted"), stopped.getMessage());
        assertTrue(status.get(), "the caller's interrupt was answered by the question, and cleared");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Where.class)
    @DisplayName("an InterruptedException inside what is thrown sets the interrupt status again")
    void interruptKept(Where where) throws InterruptedException {
        assertTrue(interruptStatusAfter(where, new InterruptedException("sleep interrupted")), where.name());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Where.class)
    @DisplayName("#895: an InterruptedException only suppressed on what is thrown sets the interrupt status again")
    void suppressedInterruptKept(Where where) throws InterruptedException {
        assertTrue(interruptStatusAfterThrowing(where, suppressingAnInterrupt()), where.name());
    }

    /**
     * Runs a rule whose {@code where} throws {@code thrown} on a thread of its own, and checks the run stopped as
     * interrupted: no rule name, an {@link InterruptedException} cause, what the expression threw kept as a suppressed
     * exception, logged at WARN and not as the rule's failure at ERROR, told to the rule's {@code onError} and to
     * {@code onRunError}, and the thread still interrupted.
     */
    private static void assertStoppedAsInterrupted(Where where, RuntimeException thrown) throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicReference<String> logs = new AtomicReference<>();
        AtomicBoolean status = new AtomicBoolean();
        List<Throwable> onError = new CopyOnWriteArrayList<>();
        List<Throwable> onRunError = new CopyOnWriteArrayList<>();
        RuleListener told = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                onError.add(error);
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                onRunError.add(error);
            }
        };
        Thread thread = new Thread(() -> {
            logs.set(logsOf(() -> {
                try {
                    runInto(where, thrown, told);
                } catch (RuntimeException e) {
                    caught.set(e);
                }
            }));
            status.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        thread.join();

        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, caught.get());
        assertNull(stop.getRuleName(), "the run was reported as the rule's failure: " + stop.getMessage());
        assertInstanceOf(InterruptedException.class, stop.getCause());
        assertArrayEquals(new Throwable[] {thrown}, stop.getSuppressed(), "what the expression threw isn't kept");
        assertTrue(logs.get().contains("WARN " + ENGINE_LOGGER + "run() was interrupted during rule 'r'"),
                logs.get());
        assertFalse(logs.get().contains("ERROR"), logs.get());
        assertEquals(List.of(stop), onError, "the rule's onError wasn't told of the stop");
        assertEquals(List.of(stop), onRunError, "onRunError wasn't told of the stop");
        assertTrue(status.get(), "the thread's interrupt status after run() threw");
    }

    @ParameterizedTest(name = "suppressed: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("#895: a fact name a language rejects with an InterruptedException inside sets the status again")
    void factNameRejectionKeepsTheInterrupt(boolean suppressed) throws InterruptedException {
        IllegalArgumentException rejected = suppressed
                ? suppressing(new IllegalArgumentException("bad name"), new InterruptedException("check interrupted"))
                : new IllegalArgumentException("bad name", new InterruptedException("check interrupted"));

        assertTrue(interruptStatusAfterThrowing(Where.FACT_NAME, rejected));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Where.class, names = {"CONDITION", "ACTION"})
    @DisplayName("#895: a condition or action that throws with an InterruptedException only suppressed stops the run as"
            + " interrupted, as one with it as a cause does")
    void suppressedInterruptStopsTheRun(Where where) throws InterruptedException {
        assertStoppedAsInterrupted(where, suppressingAnInterrupt());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Where.class, names = {"CONDITION", "ACTION"})
    @DisplayName("a condition or action that throws with an InterruptedException as a cause stops the run as"
            + " interrupted")
    void causedInterruptStopsTheRun(Where where) throws InterruptedException {
        assertStoppedAsInterrupted(where,
                new IllegalStateException("wrapped by the language", new InterruptedException("sleep interrupted")));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Where.class)
    @DisplayName("an InterruptedIOException such as SocketTimeoutException doesn't set it")
    void socketTimeoutIsNotAnInterrupt(Where where) throws InterruptedException {
        assertFalse(interruptStatusAfter(where, new SocketTimeoutException("Read timed out")), where.name());
    }

    @Test
    @DisplayName("an MVEL action interrupted in Thread.sleep fails the run, and the caller still sees the interrupt")
    void mvelActionInterrupted() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(Rule.builder().ruleName("sleepy").condition("true")
                .action("java.lang.Thread.sleep(20000)").build()));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean status = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            logsOf(() -> {
                try {
                    engine.run(new FactMap<>());
                } catch (RuntimeException e) {
                    thrown.set(e);
                }
            });
            status.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            Thread.sleep(5);
        }
        thread.interrupt();
        thread.join();

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertInstanceOf(InterruptedException.class, Failures.rootCause(ex));
        assertTrue(status.get(), "the thread's interrupt status after run() threw");
    }

    /** The interrupt status of a thread of its own after {@link Failures#keepInterruptStatus} read {@code thrown}. */
    private static boolean statusAfter(Throwable thrown) {
        AtomicBoolean status = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            Failures.keepInterruptStatus(thrown);
            status.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        return status.get();
    }

    /** Returns {@code top}, with {@code suppressed} added to its suppressed exceptions. */
    private static <T extends Throwable> T suppressing(T top, Throwable suppressed) {
        top.addSuppressed(suppressed);
        return top;
    }

    @Test
    @DisplayName("keepInterruptStatus looks through the whole cause chain, and ignores null and other causes")
    void keepInterruptStatus() {
        assertTrue(statusAfter(new RuntimeException(new RuntimeException(new InterruptedException()))));
        assertFalse(statusAfter(null));
        assertFalse(statusAfter(new RuntimeException(new IllegalStateException())));
    }

    @Test
    @DisplayName("#895: keepInterruptStatus looks among suppressed exceptions at any depth, and ignores other ones")
    void keepInterruptStatusAmongSuppressed() {
        assertTrue(statusAfter(suppressing(new RuntimeException("top"), new InterruptedException())), "on the top");
        assertTrue(statusAfter(new RuntimeException("top",
                suppressing(new RuntimeException("cause"), new InterruptedException()))), "on a cause");
        assertTrue(statusAfter(suppressing(new RuntimeException("top"),
                new RuntimeException("suppressed", new InterruptedException()))), "causing a suppressed exception");
        assertTrue(statusAfter(suppressing(new RuntimeException("top"),
                suppressing(new RuntimeException("suppressed"), new InterruptedException()))),
                "suppressed on a suppressed exception");
        assertFalse(statusAfter(suppressing(new RuntimeException("top"), new SocketTimeoutException("Read timed out"))),
                "an InterruptedIOException");
        ReportedFailure stop = new ReportedFailure("stopped", null);
        stop.addSuppressedByEngine(new InterruptedException("kept by the engine"));
        assertFalse(statusAfter(stop), "one the engine added with addSuppressedByEngine");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"found as the last exception read", "missed one past the last exception read"})
    @DisplayName("#895: an InterruptedException is found as the last exception read, and not past it")
    void keepInterruptStatusUpToTheLastExceptionRead(String where) {
        int fewer = where.startsWith("found") ? 2 : 1;
        IllegalStateException top = new IllegalStateException("top");
        for (int i = 0; i < Failures.MAX_EXCEPTIONS_READ - fewer; i++) {
            top.addSuppressed(new IllegalStateException("before " + i));
        }
        top.addSuppressed(new InterruptedException("last read"));

        assertEquals(fewer == 2, statusAfter(top), where);
    }
}
