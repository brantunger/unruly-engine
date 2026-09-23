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
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #506: when closing the sessions or the compilers of a rule list throws a fatal {@link Error}, the engine still
 * closes every idle session, then the compilers, and only then throws, whether a {@code close()}, a reload, a load that
 * failed or the last run to give back a copy did the closing. A fatal error beats any other failure, which it carries
 * as suppressed, and of two fatal errors the first wins; the other is only logged.
 *
 * <p>
 * #539-#543: the same holds for a copy the idle queue can't take, a copy that can't be lent once it's made, a
 * {@code Throwable} that is neither an {@code Exception} nor an {@code Error} from compiling at load or from closing a
 * session, and a run interrupted while it waits for a copy, which keeps its thread's interrupt status.
 * </p>
 *
 * <p>
 * #552: such a {@code Throwable} from a language or a listener is reported like an exception, so it's the load's or the
 * run's failure, wrapping it, that a fatal {@code Error} from closing carries. #553: a run stopped while it waits for a
 * copy is reported as a stop before it leaves the rules it waited on, even when leaving closes them with a fatal
 * {@code Error}.
 * </p>
 */
@DisplayName("closing a rule list closes every session and then the compilers, and throws the first fatal Error")
class CloseEverySessionTest {

    private static final String LANGUAGE = "recording";

    /**
     * A stub language that records, in order, each session and compiler it closes, and throws from a session's
     * {@code close()} or {@code warmUp()}, or the compiler's {@code close()}, what the test sets. Its sessions are
     * numbered from 1 in the order they're created, and {@code duringNewSession} runs before each is. A condition whose
     * text is {@code bad} fails to compile, and each rule's action puts its text into the output, after running
     * {@code duringAction}.
     */
    private static final class RecordingLanguage implements ExpressionLanguage {
        final String name;
        final List<String> closed = new CopyOnWriteArrayList<>();
        // Each compiler close: the name of the thread that closed it, and whether its interrupt status was set.
        final List<String> compilerClosings = new CopyOnWriteArrayList<>();
        final Map<Integer, Throwable> sessionCloseFailures = new ConcurrentHashMap<>();
        final Map<Integer, Throwable> warmUpFailures = new ConcurrentHashMap<>();
        volatile Throwable compilerCloseFailure;
        // Whether the compiler's close() clears its thread's interrupt status, as one that swallows an
        // InterruptedException does.
        volatile boolean compilerCloseClearsInterrupt;
        volatile Runnable duringCompile = () -> {
        };
        volatile Runnable duringNewSession = () -> {
        };
        volatile Throwable newCompilerFailure;
        volatile Runnable duringAction = () -> {
        };
        private final AtomicInteger sessionsMade = new AtomicInteger();
        private final ExpressionLanguage stub;

        RecordingLanguage() {
            this(LANGUAGE);
        }

        RecordingLanguage(String name) {
            this.name = name;
            this.stub = StubExpressionLanguage.named(name).compileAction(this::action);
        }

        @Override
        public String name() {
            return name;
        }

        @SuppressWarnings("unchecked")
        private CompiledAction action(Expression expression) {
            return (action, session) -> {
                duringAction.run();
                ((Map<String, Object>) action.output()).put(expression.text(), true);
                return ActionResult.done();
            };
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            throwIfSet(newCompilerFailure);
            ExpressionCompiler compiler = stub.newCompiler(context);
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    duringCompile.run();
                    if ("bad".equals(source.text())) {
                        throw new IllegalArgumentException("bad condition");
                    }
                    return compiler.compileCondition(source);
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    return compiler.compileAction(source);
                }

                @Override
                public Session newSession() {
                    duringNewSession.run();
                    return new NumberedSession(sessionsMade.incrementAndGet());
                }

                @Override
                public void warmUp(Session session) {
                    throwIfSet(warmUpFailures.get(((NumberedSession) session).number));
                }

                @Override
                public void close() {
                    closed.add("compiler");
                    compilerClosings.add(Thread.currentThread().getName() + " interrupted "
                            + Thread.currentThread().isInterrupted());
                    if (compilerCloseClearsInterrupt) {
                        Thread.interrupted();
                    }
                    throwIfSet(compilerCloseFailure);
                }
            };
        }

        /** A session that keeps state of its own, so every copy of the rules gets one. */
        private final class NumberedSession implements Session {
            final int number;

            NumberedSession(int number) {
                this.number = number;
            }

            @Override
            public void close() {
                closed.add("session " + number);
                throwIfSet(sessionCloseFailures.get(number));
            }
        }
    }

    private static void throwIfSet(Throwable failure) {
        if (failure != null) {
            CloseEverySessionTest.<RuntimeException>sneakyThrow(failure);
        }
    }

    /**
     * Throws any throwable, a checked one or a {@link Throwable} that is neither an {@link Exception} nor an
     * {@link Error} too, from code the compiler thinks throws nothing, as a language or a listener compiled apart can.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    private static RulesEngine<Map<String, Object>> engine(RecordingLanguage language, int copiesAtLoad) {
        return engine(builder -> builder.language(language), copiesAtLoad);
    }

    /** An engine with the second language as well, whose rules run after the first language's. */
    private static RulesEngine<Map<String, Object>> engine(RecordingLanguage first, RecordingLanguage second,
                                                           int copiesAtLoad) {
        return engine(builder -> builder.language(first).language(second), copiesAtLoad);
    }

    private static RulesEngine<Map<String, Object>> engine(
            UnaryOperator<RulesEngineBuilder<Map<String, Object>>> languages, int copiesAtLoad) {
        return languages.apply(RulesEngineBuilder.allMatches(HashMap::new)).defaultLanguage(LANGUAGE)
                .copiesAtLoad(copiesAtLoad).build();
    }

    private static List<Rule> rules(String name) {
        return List.of(Rule.builder().ruleName(name).language(LANGUAGE).condition("c").action(name).build());
    }

    /** A rule in each language, the first language's first, so its session is made before the second's. */
    private static List<Rule> rulesInBoth(RecordingLanguage first, RecordingLanguage second) {
        return List.of(
                Rule.builder().ruleName("a").language(first.name).priority(2).condition("c").action("a").build(),
                Rule.builder().ruleName("b").language(second.name).priority(1).condition("c").action("b").build());
    }

    private static List<String> sessions(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(n -> "session " + n).toList();
    }

    private static List<String> sessionsThenCompiler(int from, int to) {
        List<String> closed = new ArrayList<>(sessions(from, to));
        closed.add("compiler");
        return closed;
    }

    /**
     * A compiler for a rule set made by hand, which records, in order, each session it made and itself as they're
     * closed, as {@link RecordingLanguage} does, and throws {@code closeFailure} from its own {@code close()}, which
     * records whether its thread's interrupt status was set. Its sessions are numbered from 1.
     */
    private static final class RecordingCompiler implements ExpressionCompiler {
        final List<String> closed = new CopyOnWriteArrayList<>();
        final AtomicInteger sessionsMade = new AtomicInteger();
        volatile Throwable closeFailure;
        volatile boolean closedInterrupted;

        @Override
        public CompiledCondition compileCondition(Expression source) {
            throw new UnsupportedOperationException("not compiled");
        }

        @Override
        public CompiledAction compileAction(Expression source) {
            throw new UnsupportedOperationException("not compiled");
        }

        @Override
        public Session newSession() {
            int number = sessionsMade.incrementAndGet();
            return new Session() {
                @Override
                public void close() {
                    closed.add("session " + number);
                }
            };
        }

        @Override
        public void close() {
            closed.add("compiler");
            closedInterrupted = Thread.currentThread().isInterrupted();
            throwIfSet(closeFailure);
        }
    }

    /**
     * An idle queue that fails the way one that can't allocate room for a copy does: the next {@code add()} throws
     * {@code addFailure}, once. With {@code pollFailure} set, the next copy it gives out throws that, once, when it's
     * lent and the rule set first reads its sessions.
     */
    private static final class FailingQueue extends AbstractQueue<Map<String, Session>> {
        final AtomicReference<Throwable> addFailure = new AtomicReference<>();
        final AtomicReference<Throwable> pollFailure = new AtomicReference<>();
        private final Queue<Map<String, Session>> copies = new ConcurrentLinkedQueue<>();

        @Override
        public boolean offer(Map<String, Session> sessions) {
            throwIfSet(addFailure.getAndSet(null));
            return copies.offer(sessions);
        }

        @Override
        public Map<String, Session> poll() {
            Map<String, Session> sessions = copies.poll();
            Throwable failure = pollFailure.getAndSet(null);
            return failure == null || sessions == null ? sessions : new FailingSessions(sessions, failure);
        }

        @Override
        public Map<String, Session> peek() {
            return copies.peek();
        }

        @Override
        public Iterator<Map<String, Session>> iterator() {
            return copies.iterator();
        }

        @Override
        public int size() {
            return copies.size();
        }
    }

    /** A copy's sessions whose {@code values()} throws {@code failure} once; closing them reads the entries. */
    private static final class FailingSessions extends AbstractMap<String, Session> {
        private final Map<String, Session> sessions;
        private final AtomicReference<Throwable> failure;

        FailingSessions(Map<String, Session> sessions, Throwable failure) {
            this.sessions = sessions;
            this.failure = new AtomicReference<>(failure);
        }

        @Override
        public Set<Entry<String, Session>> entrySet() {
            return sessions.entrySet();
        }

        @Override
        public Collection<Session> values() {
            throwIfSet(failure.getAndSet(null));
            return sessions.values();
        }
    }

    /**
     * A rule set of no rules in one language, made by hand, whose runs share {@code permits}, a limit of one copy,
     * and wait for a copy until they're interrupted.
     */
    private static RuleSet ruleSet(RecordingCompiler compiler, CopyPermits permits, Queue<Map<String, Session>> idle) {
        // A stall window no test outlasts, so that a run waits for a copy however slow the machine is.
        return new RuleSet(List.of(), Map.of(LANGUAGE, compiler), CopyLimit.of(1), permits, Long.MAX_VALUE, idle);
    }

    @Test
    @DisplayName("close() with two sessions that throw a fatal Error closes the third and then the compiler, and"
            + " throws the first")
    void twoFatalSessionsOnClose() {
        OutOfMemoryError first = new OutOfMemoryError("first");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, first);
        language.sessionCloseFailures.put(2, new OutOfMemoryError("second"));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertSame(first, thrown.get());
        assertEquals(sessionsThenCompiler(1, 3), language.closed, "every session, then the compiler, once");
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "The '" + LANGUAGE
                + "' expression language failed to close a session: second"), "the second is only logged: " + logs);
        assertEquals(0, first.getSuppressed().length);
        engine.close();
        assertEquals(sessionsThenCompiler(1, 3), language.closed, "closing again closes nothing more");
    }

    @Test
    @DisplayName("a reload that closes two sessions that throw a fatal Error closes the third and then the compiler,"
            + " throws the first, and the new rules serve runs")
    void twoFatalSessionsOnReload() {
        OutOfMemoryError first = new OutOfMemoryError("first");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        engine.load(rules("old"));
        // Only the old rules' sessions: the reload makes sessions 4 to 6 for the new ones.
        language.sessionCloseFailures.put(1, first);
        language.sessionCloseFailures.put(2, new OutOfMemoryError("second"));
        List<Rule> next = rules("new");
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(next))));

        assertSame(first, thrown.get());
        assertEquals(sessionsThenCompiler(1, 3), language.closed, "every old session, then the old compiler, once");
        assertEquals("new", engine.rules().rules().get(0).getRuleName(), "the new rules stay loaded");
        assertEquals(Map.of("new", true), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("close() from a run that holds a copy closes every idle copy before it throws, and the held copy and"
            + " the compiler when the run gives it back")
    void fatalOnCloseWhileARunHoldsACopy() {
        OutOfMemoryError fatal = new OutOfMemoryError("second copy");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 5);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(2, fatal);
        AtomicReference<Throwable> closeThrew = new AtomicReference<>();
        AtomicReference<List<String>> closedWhenCloseReturned = new AtomicReference<>();
        language.duringAction = () -> {
            try {
                engine.close();
            } catch (OutOfMemoryError e) {
                closeThrew.set(e);
            }
            closedWhenCloseReturned.set(List.copyOf(language.closed));
        };

        AtomicReference<Map<String, Object>> output = new AtomicReference<>();

        logsOf(() -> output.set(engine.run(new FactMap<>())));

        assertEquals(Map.of("r", true), output.get(), "the run finished normally");
        assertSame(fatal, closeThrew.get());
        assertEquals(sessions(2, 5), closedWhenCloseReturned.get(),
                "the idle copies, closed before close() returned; not the run's copy, nor the compiler");
        List<String> expected = new ArrayList<>(sessions(2, 5));
        expected.addAll(List.of("session 1", "compiler"));
        assertEquals(expected, language.closed, "the run's copy and then the compiler, once the run gave it back");
    }

    @Test
    @DisplayName("a load whose copy fails to warm up throws a fatal Error from closing, carrying the load's failure")
    void warmUpFailureThenFatalClose() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the copy that failed to warm up");
        RecordingLanguage language = new RecordingLanguage();
        language.warmUpFailures.put(2, new IllegalStateException("can't warm up"));
        language.sessionCloseFailures.put(2, fatal);
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules("r")))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the load's failure");
        RuleCompilationException loadFailure = assertInstanceOf(RuleCompilationException.class,
                fatal.getSuppressed()[0]);
        assertEquals("The '" + LANGUAGE + "' expression language failed to warm up a session: can't warm up",
                loadFailure.getMessage());
        assertEquals(sessionsThenCompiler(1, 2), language.closed, "both copies made, then the compiler");
    }

    @Test
    @DisplayName("a load whose copy fails to warm up with a fatal Error throws that one, not a later one from closing")
    void fatalWarmUpBeatsFatalClose() {
        InternalError warmUp = new InternalError("warming up");
        OutOfMemoryError close = new OutOfMemoryError("closing");
        RecordingLanguage language = new RecordingLanguage();
        language.warmUpFailures.put(2, warmUp);
        language.sessionCloseFailures.put(2, close);
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules("r")))));

        assertSame(warmUp, thrown.get());
        assertEquals(0, warmUp.getSuppressed().length);
        assertEquals(0, close.getSuppressed().length);
        assertEquals(sessionsThenCompiler(1, 2), language.closed);
    }

    @Test
    @DisplayName("a load whose rules don't compile throws a fatal Error from closing the compiler, carrying the"
            + " load's failure")
    void compileFailureThenFatalClose() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage language = new RecordingLanguage();
        language.compilerCloseFailure = fatal;
        RulesEngine<Map<String, Object>> engine = engine(language, 0);
        List<Rule> broken = List.of(Rule.builder().ruleName("r").language(LANGUAGE).condition("bad").action("a")
                .build());
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(broken))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length);
        assertInstanceOf(RuleCompilationException.class, fatal.getSuppressed()[0]);
        assertEquals(List.of("compiler"), language.closed);
    }

    @Test
    @DisplayName("a load that finds the engine closed throws a fatal Error from closing its rules, carrying the"
            + " IllegalStateException")
    void closedEngineLoadThenFatalClose() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the refused rules");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 2);
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        language.duringCompile = () -> {
            compiling.countDown();
            await(release);
        };
        language.sessionCloseFailures.put(1, fatal);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread loading = new Thread(() -> thrown.set(thrownBy(() -> engine.load(rules("r")))));

        String logs = logsOf(() -> {
            loading.start();
            await(compiling);
            engine.close();
            release.countDown();
            join(loading);
        });

        assertSame(fatal, thrown.get(), logs);
        assertEquals(1, fatal.getSuppressed().length);
        IllegalStateException refused = assertInstanceOf(IllegalStateException.class, fatal.getSuppressed()[0]);
        assertEquals("The engine is closed", refused.getMessage());
        assertEquals(sessionsThenCompiler(1, 2), language.closed);
    }

    @Test
    @DisplayName("close() whose only fatal Error comes from the compiler closes every session first, then throws it")
    void fatalCompilerOnly() {
        OutOfMemoryError fatal = new OutOfMemoryError("compiler");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        engine.load(rules("r"));
        language.compilerCloseFailure = fatal;
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertSame(fatal, thrown.get());
        assertEquals(sessionsThenCompiler(1, 3), language.closed);
    }

    @Test
    @DisplayName("close() with a fatal Error from a session and another from the compiler throws the session's, which"
            + " came first")
    void fatalSessionBeatsFatalCompiler() {
        OutOfMemoryError session = new OutOfMemoryError("session");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, session);
        language.compilerCloseFailure = new InternalError("compiler");
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertSame(session, thrown.get());
        assertEquals(sessionsThenCompiler(1, 3), language.closed);
    }

    @Test
    @DisplayName("guard: close() with one session that throws a fatal Error closes the rest and the compiler, and"
            + " throws it")
    void oneFatalSession() {
        OutOfMemoryError fatal = new OutOfMemoryError("second copy");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(2, fatal);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertSame(fatal, thrown.get());
        assertEquals(sessionsThenCompiler(1, 3), language.closed);
    }

    @Test
    @DisplayName("guard: close() with sessions that throw an Error that isn't fatal logs them and throws nothing")
    void nonFatalSessionErrorsAbsorbed() {
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, new AssertionError("first"));
        language.sessionCloseFailures.put(2, new AssertionError("second"));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertNull(thrown.get());
        assertEquals(sessionsThenCompiler(1, 3), language.closed);
        assertTrue(logs.contains("failed to close a session: first"), logs);
        assertTrue(logs.contains("failed to close a session: second"), logs);
    }

    @Test
    @DisplayName("guard: a run that succeeds and then gives back the last copy of closed rules throws the fatal Error"
            + " from closing it")
    void runSucceedsThenFatalOnGiveBack() {
        OutOfMemoryError fatal = new OutOfMemoryError("the run's copy");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 1);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, fatal);
        language.duringAction = engine::close;
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(sessionsThenCompiler(1, 1), language.closed);
    }

    @Test
    @DisplayName("a run that fails and then gives back the last copy of closed rules throws the fatal Error from"
            + " closing it, carrying the run's failure")
    void runFailsThenFatalOnGiveBack() {
        OutOfMemoryError fatal = new OutOfMemoryError("the run's copy");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 1);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, fatal);
        language.duringAction = () -> {
            engine.close();
            throw new IllegalStateException("the action failed");
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        RuleExecutionException runFailure = assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]);
        assertEquals("r", runFailure.getRuleName());
        assertEquals(sessionsThenCompiler(1, 1), language.closed);
    }

    @Test
    @DisplayName("a run that fails with a fatal Error and then gives back the last copy of closed rules throws its own,"
            + " not the one from closing")
    void runFatalBeatsFatalOnGiveBack() {
        InternalError runFatal = new InternalError("the action");
        OutOfMemoryError closeFatal = new OutOfMemoryError("the run's copy");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 1);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, closeFatal);
        language.duringAction = () -> {
            engine.close();
            throw runFatal;
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(runFatal, thrown.get());
        assertEquals(0, runFatal.getSuppressed().length);
        assertEquals(sessionsThenCompiler(1, 1), language.closed);
    }

    @Test
    @DisplayName("a run whose action ends with a Throwable that is neither an Exception nor an Error fails, and still"
            + " gives back its copy")
    void rawThrowableFromRunStillGivesBackTheCopy() {
        Throwable raw = new Throwable("raw");
        RecordingLanguage language = new RecordingLanguage();
        language.duringAction = () -> CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
        RulesEngine<Map<String, Object>> engine = engine(language, 1);
        engine.load(rules("r"));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));
        engine.close();

        RuleExecutionException runFailure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertSame(raw, runFailure.getCause());
        assertEquals(sessionsThenCompiler(1, 1), language.closed, "the copy was given back, so close() closed it");
    }

    @Test
    @DisplayName("a run whose listener's beforeRun throws a Throwable that is neither an Exception nor an Error goes"
            + " on, and gives back its copy")
    void rawThrowableFromAListenerStillGivesBackTheCopy() {
        Throwable raw = new Throwable("raw");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language)
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
                    }
                }), 1);
        engine.load(rules("r"));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));
        engine.close();

        assertNull(thrown.get(), "the listener's throw is contained, like an exception");
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in beforeRun:"
                + " java.lang.Throwable: raw"), logs);
        assertEquals(sessionsThenCompiler(1, 1), language.closed, "the copy was given back, so close() closed it");
    }

    @Test
    @DisplayName("close() with a session that throws a Throwable that is neither an Exception nor an Error logs it,"
            + " closes the other copies and the compiler, and throws nothing")
    void rawThrowableFromSessionCloseLoggedAndTheRestClosed() {
        Throwable raw = new Throwable("raw");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 3);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, raw);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertNull(thrown.get());
        assertEquals(sessionsThenCompiler(1, 3), language.closed);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "The '" + LANGUAGE
                + "' expression language failed to close a session: raw"), logs);
    }

    @Test
    @DisplayName("a Throwable that is neither an Exception nor an Error from one language's session close() doesn't"
            + " stop the same copy's other sessions from closing")
    void rawThrowableFromOneLanguagesCloseStillClosesTheOthers() {
        Throwable raw = new Throwable("raw");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        RulesEngine<Map<String, Object>> engine = engine(first, second, 1);
        engine.load(rulesInBoth(first, second));
        first.sessionCloseFailures.put(1, raw);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertEquals(List.of("session 1", "compiler"), second.closed, "the same copy's other session");
        assertEquals(List.of("session 1", "compiler"), first.closed);
        assertNull(thrown.get());
    }

    @Test
    @DisplayName("a new copy whose session throws a Throwable that is neither an Exception nor an Error closes the"
            + " sessions already made, and the run still leaves the rules")
    void rawThrowableFromNewSessionStillClosesAndLeaves() {
        Throwable raw = new Throwable("raw");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        RulesEngine<Map<String, Object>> engine = engine(first, second, 0);
        engine.load(rulesInBoth(first, second));
        second.duringNewSession = () -> CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException runFailure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("The 'second' expression language failed to create a session: raw", runFailure.getMessage());
        assertSame(raw, runFailure.getCause());
        assertEquals(List.of("session 1"), first.closed, "the session made for the copy before the other failed");
        engine.close();
        assertEquals(List.of("session 1", "compiler"), first.closed, "the run left, so close() closed the compilers");
        assertEquals(List.of("compiler"), second.closed);
    }

    @Test
    @DisplayName("a load whose copy is only partly made throws a fatal Error from closing it, carrying the load's"
            + " failure")
    void partlyMadeCopyAtLoadThenFatalClose() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the partly made copy");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        first.sessionCloseFailures.put(1, fatal);
        second.duringNewSession = () -> {
            throw new IllegalStateException("no session");
        };
        RulesEngine<Map<String, Object>> engine = engine(first, second, 1);
        List<Rule> rules = rulesInBoth(first, second);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the load's failure");
        RuleCompilationException loadFailure = assertInstanceOf(RuleCompilationException.class,
                fatal.getSuppressed()[0]);
        assertEquals("The 'second' expression language failed to create a session: no session",
                loadFailure.getMessage());
        assertEquals(List.of("session 1", "compiler"), first.closed);
        assertEquals(List.of("compiler"), second.closed);
    }

    @Test
    @DisplayName("a run whose new copy is only partly made throws a fatal Error from closing it, carrying the run's"
            + " failure")
    void partlyMadeCopyAtRunThenFatalClose() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the partly made copy");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        RulesEngine<Map<String, Object>> engine = engine(first, second, 0);
        engine.load(rulesInBoth(first, second));
        first.sessionCloseFailures.put(1, fatal);
        second.duringNewSession = () -> {
            throw new IllegalStateException("no session");
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        RuleExecutionException runFailure = assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]);
        assertEquals("The 'second' expression language failed to create a session: no session",
                runFailure.getMessage());
        assertEquals(List.of("session 1"), first.closed);
    }

    @Test
    @DisplayName("a run that fails to get a copy of rules closed meanwhile, as their last user, throws a fatal Error"
            + " from closing the compiler, carrying its failure")
    void failedBorrowOfTheLastUserThenFatalClose() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 0);
        engine.load(rules("r"));
        language.compilerCloseFailure = fatal;
        language.duringNewSession = () -> {
            engine.close();
            throw new IllegalStateException("no session");
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        RuleExecutionException runFailure = assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]);
        assertEquals("The '" + LANGUAGE + "' expression language failed to create a session: no session",
                runFailure.getMessage());
        assertEquals(List.of("compiler"), language.closed);
    }

    @Test
    @DisplayName("validate() throws a fatal Error from closing the compiler of rules that compiled")
    void validateThenFatalClose() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage language = new RecordingLanguage();
        language.compilerCloseFailure = fatal;
        RulesEngine<Map<String, Object>> engine = engine(language, 0);
        List<Rule> rules = rules("r");
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.validate(rules))));

        assertSame(fatal, thrown.get());
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(List.of("compiler"), language.closed);
    }

    @Test
    @DisplayName("validate() that fails with a fatal Error throws that one, not a later one from closing a compiler")
    void validateFatalBeatsFatalClose() {
        InternalError compiling = new InternalError("creating a compiler");
        OutOfMemoryError closing = new OutOfMemoryError("closing the compiler");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        first.compilerCloseFailure = closing;
        second.newCompilerFailure = compiling;
        RulesEngine<Map<String, Object>> engine = engine(first, second, 0);
        List<Rule> rules = rulesInBoth(first, second);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.validate(rules))));

        assertSame(compiling, thrown.get());
        assertEquals(0, compiling.getSuppressed().length);
        assertEquals(List.of("compiler"), first.closed, "the compiler created was still closed");
    }

    @Test
    @DisplayName("a nested run given an extra copy throws a fatal Error from closing it when it gives it back")
    void fatalFromClosingAnExtraCopy() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the extra copy");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1), 1);
        engine.load(rules("r"));
        // Session 1 is the kept copy the outer run holds; the nested run finds no permit, and makes session 2.
        language.sessionCloseFailures.put(2, fatal);
        AtomicBoolean nested = new AtomicBoolean();
        AtomicReference<Throwable> nestedThrew = new AtomicReference<>();
        language.duringAction = () -> {
            if (nested.compareAndSet(false, true)) {
                nestedThrew.set(thrownBy(() -> engine.run(new FactMap<>())));
            }
        };

        logsOf(() -> engine.run(new FactMap<>()));

        assertSame(fatal, nestedThrew.get());
        assertEquals(List.of("session 2"), language.closed, "only the extra copy, which isn't kept");
    }

    @Test
    @DisplayName("a load whose copy is only partly made because a language threw a Throwable that is neither an"
            + " Exception nor an Error still closes every session and the compilers")
    void rawThrowableFromNewSessionAtLoadStillClosesEverything() {
        Throwable raw = new Throwable("raw");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        second.duringNewSession = () -> CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
        RulesEngine<Map<String, Object>> engine = engine(first, second, 1);
        List<Rule> rules = rulesInBoth(first, second);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException loadFailure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("The 'second' expression language failed to create a session: raw", loadFailure.getMessage());
        assertSame(raw, loadFailure.getCause());
        assertEquals(List.of("session 1", "compiler"), first.closed);
        assertEquals(List.of("compiler"), second.closed);
    }

    @Test
    @DisplayName("a load that fails with a Throwable that is neither an Exception nor an Error throws a fatal Error"
            + " from closing, carrying the load's failure, which wraps that Throwable")
    void rawThrowableAtLoadThenFatalClose() {
        Throwable raw = new Throwable("raw");
        OutOfMemoryError fatal = new OutOfMemoryError("closing the partly made copy");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        first.sessionCloseFailures.put(1, fatal);
        second.duringNewSession = () -> CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
        RulesEngine<Map<String, Object>> engine = engine(first, second, 1);
        List<Rule> rules = rulesInBoth(first, second);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the load's failure");
        assertSame(raw, assertInstanceOf(RuleCompilationException.class, fatal.getSuppressed()[0]).getCause());
        assertEquals(List.of("session 1", "compiler"), first.closed);
        assertEquals(List.of("compiler"), second.closed);
    }

    @Test
    @DisplayName("a run that fails with a Throwable that is neither an Exception nor an Error, and then gives back the"
            + " last copy of closed rules, throws the fatal Error from closing it, carrying the run's failure, which"
            + " wraps that Throwable")
    void rawThrowableFromRunThenFatalOnGiveBack() {
        Throwable raw = new Throwable("raw");
        OutOfMemoryError fatal = new OutOfMemoryError("the run's copy");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 1);
        engine.load(rules("r"));
        language.sessionCloseFailures.put(1, fatal);
        language.duringAction = () -> {
            engine.close();
            CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        RuleExecutionException runFailure = assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]);
        assertEquals("r", runFailure.getRuleName());
        assertSame(raw, runFailure.getCause());
        assertEquals(sessionsThenCompiler(1, 1), language.closed);
    }

    @Test
    @DisplayName("a run whose new copy fails with a Throwable that is neither an Exception nor an Error throws a"
            + " fatal Error from closing the sessions already made, carrying the run's failure, which wraps that"
            + " Throwable")
    void rawThrowableFromNewSessionAtRunThenFatalClose() {
        Throwable raw = new Throwable("raw");
        OutOfMemoryError fatal = new OutOfMemoryError("closing the partly made copy");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        RulesEngine<Map<String, Object>> engine = engine(first, second, 0);
        engine.load(rulesInBoth(first, second));
        first.sessionCloseFailures.put(1, fatal);
        second.duringNewSession = () -> CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        assertSame(raw, assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]).getCause());
        assertEquals(List.of("session 1"), first.closed);
    }

    @Test
    @DisplayName("a run that fails to get a copy with a Throwable that is neither an Exception nor an Error, as the"
            + " last user of closed rules, throws a fatal Error from closing the compiler, carrying the run's failure,"
            + " which wraps that Throwable")
    void rawThrowableFromFailedBorrowThenFatalClose() {
        Throwable raw = new Throwable("raw");
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 0);
        engine.load(rules("r"));
        language.compilerCloseFailure = fatal;
        language.duringNewSession = () -> {
            engine.close();
            CloseEverySessionTest.<RuntimeException>sneakyThrow(raw);
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        assertSame(raw, assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]).getCause());
        assertEquals(List.of("compiler"), language.closed);
    }

    @Test
    @DisplayName("validate() whose language fails to create a compiler with a Throwable that is neither an Exception"
            + " nor an Error reports it like an exception, and still closes the compiler created, throwing the fatal"
            + " Error from closing it")
    void rawThrowableFromValidateThenFatalClose() {
        Throwable raw = new Throwable("raw");
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        first.compilerCloseFailure = fatal;
        second.newCompilerFailure = raw;
        RulesEngine<Map<String, Object>> engine = engine(first, second, 0);
        List<Rule> rules = rulesInBoth(first, second);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.validate(rules))));

        // A failure validate() reports rather than throws, so the fatal Error from closing carries nothing.
        assertSame(fatal, thrown.get());
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(List.of("compiler"), first.closed);
    }

    @Test
    @DisplayName("a load whose language fails to create a compiler with a Throwable that is neither an Exception nor"
            + " an Error fails like one that throws an exception, and closes the compilers already created")
    void rawThrowableFromCompilingAtLoadClosesTheCompilers() {
        Throwable raw = new Throwable("raw");
        RecordingLanguage first = new RecordingLanguage();
        RecordingLanguage second = new RecordingLanguage("second");
        second.newCompilerFailure = raw;
        RulesEngine<Map<String, Object>> engine = engine(first, second, 0);
        List<Rule> rules = rulesInBoth(first, second);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException loadFailure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("The 'second' expression language failed to create a compiler: raw", loadFailure.getMessage());
        assertSame(raw, loadFailure.getCause());
        assertEquals(List.of("compiler"), first.closed, "the first language's compiler was created, so it's closed");
    }

    @Test
    @DisplayName("a copy given back that the idle queue can't take with a fatal Error is closed, and the error is"
            + " returned for the run to throw")
    void fatalFromKeepingACopyClosesIt() throws InterruptedException, TimeoutException {
        OutOfMemoryError fatal = new OutOfMemoryError("keeping the copy");
        RecordingCompiler compiler = new RecordingCompiler();
        FailingQueue idle = new FailingQueue();
        RuleSet rules = ruleSet(compiler, new CopyPermits(1), idle);
        RuleSet.Copy copy = rules.borrow(null);
        idle.addFailure.set(fatal);
        AtomicReference<Error> returned = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> returned.set(rules.release(copy)))));

        assertNull(thrown.get(), "returned, for the run to weigh against its own failure");
        assertSame(fatal, returned.get());
        assertEquals(List.of("session 1"), compiler.closed, "the copy that couldn't be kept");
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "A copy of the rules couldn't be kept for a later run, so"
                + " its sessions were closed: keeping the copy"), logs);
        rules.release(rules.borrow(Instant.now().plusSeconds(10)));
        assertEquals(2, compiler.sessionsMade.get(), "the next run made a copy of its own under the limit");
    }

    @Test
    @DisplayName("a copy given back that the idle queue can't take with a failure that isn't fatal is closed, and the"
            + " failure only logged")
    void nonFatalFromKeepingACopyClosesIt() throws InterruptedException, TimeoutException {
        RecordingCompiler compiler = new RecordingCompiler();
        FailingQueue idle = new FailingQueue();
        RuleSet rules = ruleSet(compiler, new CopyPermits(1), idle);
        RuleSet.Copy copy = rules.borrow(null);
        idle.addFailure.set(new IllegalStateException("queue full"));
        AtomicReference<Error> returned = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> returned.set(rules.release(copy)))));

        assertNull(thrown.get());
        assertNull(returned.get());
        assertEquals(List.of("session 1"), compiler.closed);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "A copy of the rules couldn't be kept for a later run, so"
                + " its sessions were closed: queue full"), logs);
    }

    @Test
    @DisplayName("a load whose idle queue can't take a copy with a fatal Error leaves no session it made unclosed")
    void fatalFromKeepingACopyAtLoadLeavesNoSessionOpen() {
        OutOfMemoryError fatal = new OutOfMemoryError("keeping the copy");
        RecordingCompiler compiler = new RecordingCompiler();
        FailingQueue idle = new FailingQueue();
        idle.addFailure.set(fatal);
        RuleSet rules = ruleSet(compiler, new CopyPermits(1), idle);

        Throwable thrown = thrownBy(() -> rules.prepareCopies(1));
        assertNull(rules.retire());

        assertSame(fatal, thrown);
        assertEquals(sessionsThenCompiler(1, compiler.sessionsMade.get()), compiler.closed,
                "every session made, then the compiler");
    }

    @Test
    @DisplayName("a kept copy that fails with a fatal Error as it's lent is closed, and the permit taken for it given"
            + " back, so the next run still gets a copy under the limit")
    void fatalFromLendingAKeptCopyClosesItAndGivesBackThePermit() throws InterruptedException, TimeoutException {
        OutOfMemoryError fatal = new OutOfMemoryError("lending the copy");
        RecordingCompiler compiler = new RecordingCompiler();
        FailingQueue idle = new FailingQueue();
        CopyPermits permits = new CopyPermits(1);
        RuleSet rules = ruleSet(compiler, permits, idle);
        rules.prepareCopies(1);
        idle.pollFailure.set(fatal);

        Throwable thrown = thrownBy(() -> rules.borrow(null));

        assertSame(fatal, thrown);
        assertEquals(List.of("session 1"), compiler.closed, "the copy that couldn't be lent");
        assertEquals(1, permits.available().availablePermits(), "the permit taken for it was given back");
        rules.release(rules.borrow(Instant.now().plusSeconds(10)));
        assertEquals(2, compiler.sessionsMade.get(), "the next run made a copy of its own under the limit");
    }

    @Test
    @DisplayName("guard: a nested run that fails to get a copy leaves the run around it counted, so a later nested"
            + " run still takes an extra copy at once")
    void failedNestedBorrowLeavesTheOuterRunCounted() {
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1), 1);
        engine.load(rules("r"));
        AtomicBoolean nesting = new AtomicBoolean();
        AtomicReference<Throwable> failedNested = new AtomicReference<>();
        AtomicReference<Map<String, Object>> laterNested = new AtomicReference<>();
        language.duringAction = () -> {
            if (nesting.compareAndSet(false, true)) {
                language.duringNewSession = () -> {
                    throw new IllegalStateException("no session");
                };
                failedNested.set(thrownBy(() -> engine.run(new FactMap<>())));
                language.duringNewSession = () -> {
                };
                laterNested.set(engine.run(new FactMap<>()));
            }
        };

        String logs = logsOf(() -> engine.run(new FactMap<>()));

        assertInstanceOf(RuleExecutionException.class, failedNested.get());
        assertEquals(Map.of("r", true), laterNested.get());
        assertFalse(logs.contains("made an extra copy"), "the later nested run didn't wait for a copy: " + logs);
    }

    @Test
    @DisplayName("guard: a run of another engine that a language starts while a run gets its copy leaves that run"
            + " counted, so a run nested in it takes an extra copy at once, on every run of the thread")
    void runStartedWhileGettingACopyLeavesTheRunCounted() {
        RecordingLanguage other = new RecordingLanguage();
        RulesEngine<Map<String, Object>> otherEngine = engine(other, 0);
        otherEngine.load(rules("o"));
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1), 0);
        engine.load(rules("r"));
        AtomicBoolean firstSession = new AtomicBoolean(true);
        language.duringNewSession = () -> {
            if (firstSession.compareAndSet(true, false)) {
                otherEngine.run(new FactMap<>());
            }
        };
        AtomicBoolean nesting = new AtomicBoolean();
        List<Map<String, Object>> nested = new CopyOnWriteArrayList<>();
        language.duringAction = () -> {
            if (nesting.compareAndSet(false, true)) {
                nested.add(engine.run(new FactMap<>()));
            }
        };
        // A thread of its own, so a count this leaves broken breaks no other test.
        Thread thread = new Thread(() -> {
            engine.run(new FactMap<>());
            // Again on the same thread, whose count a first run left broken would break for good.
            nesting.set(false);
            engine.run(new FactMap<>());
        });

        // Time for a run that isn't counted to wait out the stall window twice, so it fails on what it logs.
        String logs = logsOf(() -> {
            thread.start();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        assertFalse(thread.isAlive(), "timed out");
        assertEquals(List.of(Map.of("r", true), Map.of("r", true)), nested);
        assertFalse(logs.contains("made an extra copy"), "the nested runs didn't wait for a copy: " + logs);
    }

    @Test
    @DisplayName("a fatal Error that replaces a failure caused by an interrupt sets the thread's interrupt status")
    void fatalInsteadOfAnInterruptKeepsTheInterruptStatus() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing");
        InterruptedException interrupted = new InterruptedException();
        AtomicBoolean status = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            Failures.fatalInsteadOf(interrupted, fatal);
            status.set(Thread.currentThread().isInterrupted());
        });

        thread.start();
        join(thread);

        assertTrue(status.get(), "the thread's interrupt status");
        assertArrayEquals(new Throwable[] {interrupted}, fatal.getSuppressed());
    }

    @Test
    @DisplayName("a run interrupted while it waits for a copy, whose leaving closes retired rules with a fatal Error,"
            + " keeps its thread's interrupt status, and leaves only once it's been handed the stop")
    void interruptedWaitThenFatalCloseKeepsTheInterruptStatus() throws InterruptedException, TimeoutException {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        CopyPermits permits = new CopyPermits(1);
        RuleSet held = ruleSet(new RecordingCompiler(), permits, new ConcurrentLinkedQueue<>());
        RecordingCompiler compiler = new RecordingCompiler();
        RuleSet waitedFor = ruleSet(compiler, permits, new ConcurrentLinkedQueue<>());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<List<String>> closedBeforeLeaving = new AtomicReference<>();
        AtomicReference<Error> left = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            thrown.set(thrownBy(() -> waitedFor.borrow(null)));
            closedBeforeLeaving.set(List.copyOf(compiler.closed));
            left.set(waitedFor.leaveAfterStop());
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        // The one permit, held with a copy of the other rule set, so the waiter waits for it.
        RuleSet.Copy copy = held.borrow(null);
        try {
            logsOf(() -> {
                waiter.start();
                awaitQueued(permits);
                compiler.closeFailure = fatal;
                // Retired while the waiter is its only user, so the waiter's leaving closes the compiler.
                assertNull(waitedFor.retire());
                waiter.interrupt();
                join(waiter);
            });
        } finally {
            held.release(copy);
        }

        assertInstanceOf(InterruptedException.class, thrown.get(), "the stop, for the run to report");
        assertEquals(List.of(), closedBeforeLeaving.get(), "nothing is closed before the run leaves");
        assertSame(fatal, left.get(), "leaving returns the fatal Error from closing, for the run to throw");
        assertTrue(interrupted.get(), "the thread's interrupt status");
        assertEquals(List.of("compiler"), compiler.closed);
        assertTrue(compiler.closedInterrupted, "the interrupt status was set again before the compiler was closed");
    }

    /**
     * Records the run callbacks a listener gets, each with the name of the thread it came on, and the class of the
     * error {@code onRunError} got and of its cause.
     */
    private static RuleListener recordingRunCallbacks(List<String> events) {
        return new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                events.add(Thread.currentThread().getName() + " beforeRun");
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                events.add(Thread.currentThread().getName() + " onRunError " + error.getClass().getSimpleName()
                        + " cause " + (error.getCause() == null ? null : error.getCause().getClass().getSimpleName()));
            }
        };
    }

    /** The run callbacks {@link #recordingRunCallbacks} recorded for the waiter's run. */
    private static List<String> waiterCallbacks(List<String> events) {
        return events.stream().filter(event -> event.startsWith("waiter ")).toList();
    }

    /**
     * Starts a waiter that waits for the engine's one copy, which a holder keeps with rules a reload has retired; waits
     * with {@code untilWaiting} until the waiter has got where the test wants it; then retires the waiter's rules too
     * with {@code close()}, while the compiler's {@code close()} throws {@code closeFailure}, so the waiter, their only
     * user, closes them when it leaves; then lets the waiter go on with {@code stop}. The holder's rules are closed
     * once the waiter has finished, with a compiler that no longer throws.
     */
    private static void waitRetireAndStop(RecordingLanguage language, RulesEngine<Map<String, Object>> engine,
                                          Throwable closeFailure, Runnable waiterRun, Consumer<Thread> untilWaiting,
                                          Consumer<Thread> stop) {
        engine.load(rules("r"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        language.duringAction = () -> {
            holding.countDown();
            await(finish);
        };
        Thread holder = new Thread(() -> thrownBy(() -> engine.run(new FactMap<>())), "holder");
        holder.start();
        await(holding);
        language.duringAction = () -> {
        };
        // The rules the holder uses are retired; it still holds the engine's one permit.
        engine.load(rules("r"));
        Thread waiter = new Thread(waiterRun, "waiter");
        waiter.start();
        untilWaiting.accept(waiter);
        language.compilerCloseFailure = closeFailure;
        thrownBy(engine::close);
        stop.accept(waiter);
        join(waiter);
        language.compilerCloseFailure = null;
        finish.countDown();
        join(holder);
    }

    /** Waits until the waiter is waiting for a copy, which it does with a time limit, the stall window. */
    private static void awaitWaitingForACopy(Thread waiter) {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (waiter.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < giveUp, "the waiter never started waiting for a copy");
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("guard: a run interrupted while it waits for a copy of rules retired meanwhile, as their last user, is"
            + " reported as a stop")
    void interruptedWaitOfTheLastUserIsReportedAsAStop() {
        RecordingLanguage language = new RecordingLanguage();
        List<String> events = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1)
                .listener(recordingRunCallbacks(events)), 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> waitRetireAndStop(language, engine, null,
                () -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))),
                CloseEverySessionTest::awaitWaitingForACopy, Thread::interrupt));

        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertInstanceOf(InterruptedException.class, stop.getCause());
        assertEquals(List.of("waiter beforeRun", "waiter onRunError ReportedFailure cause InterruptedException"),
                waiterCallbacks(events));
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "run() was interrupted while waiting for a compiled copy of"
                + " the rules: all 1 were in use"), logs);
        assertEquals(List.of("compiler", "session 1", "compiler"), language.closed,
                "the waiter's rules as it left, then the holder's copy and rules as it gave the copy back");
    }

    @Test
    @DisplayName("a run interrupted while it waits for a copy, whose leaving closes retired rules with a fatal Error,"
            + " is reported as a stop first, and the error carries the stop")
    void interruptedWaitThenFatalCloseIsReportedAsAStop() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage language = new RecordingLanguage();
        List<String> events = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1)
                .listener(recordingRunCallbacks(events)), 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();

        String logs = logsOf(() -> waitRetireAndStop(language, engine, fatal, () -> {
            thrown.set(thrownBy(() -> engine.run(new FactMap<>())));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, CloseEverySessionTest::awaitWaitingForACopy, Thread::interrupt));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]);
        assertInstanceOf(InterruptedException.class, stop.getCause());
        assertEquals(List.of("waiter beforeRun", "waiter onRunError ReportedFailure cause InterruptedException"),
                waiterCallbacks(events), "every callback: " + events);
        String warning = "WARN " + ENGINE_LOGGER + "run() was interrupted while waiting for a compiled copy of the"
                + " rules: all 1 were in use";
        String closing = "WARN " + ENGINE_LOGGER + "The '" + LANGUAGE + "' expression language failed to close its"
                + " compiler: closing the compiler";
        assertTrue(logs.contains(warning), logs);
        assertTrue(logs.indexOf(warning) < logs.indexOf(closing), "the stop, then the closing: " + logs);
        assertTrue(interrupted.get(), "the thread's interrupt status");
    }

    @Test
    @DisplayName("a run interrupted while it waits for a copy, whose listener clears the interrupt status, closes the"
            + " retired rules and returns with the status set again")
    void interruptedWaitWithAListenerThatClearsTheStatusKeepsIt() {
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1)
                .listener(new RuleListener() {
                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        // As a listener that swallows an InterruptedException does.
                        Thread.interrupted();
                    }
                }), 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();

        logsOf(() -> waitRetireAndStop(language, engine, null, () -> {
            thrown.set(thrownBy(() -> engine.run(new FactMap<>())));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, CloseEverySessionTest::awaitWaitingForACopy, Thread::interrupt));

        assertInstanceOf(InterruptedException.class,
                assertInstanceOf(RuleExecutionException.class, thrown.get()).getCause());
        assertTrue(interrupted.get(), "the thread's interrupt status after run()");
        assertEquals("waiter interrupted true", language.compilerClosings.get(0),
                "the waiter closed its rules with the status set: " + language.compilerClosings);
    }

    @Test
    @DisplayName("a run interrupted while it waits for a copy, whose leaving closes retired rules with a compiler that"
            + " clears the interrupt status, returns with the status set")
    void interruptedWaitWithACompilerCloseThatClearsTheStatusKeepsIt() {
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1), 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();

        logsOf(() -> waitRetireAndStop(language, engine, null, () -> {
            language.compilerCloseClearsInterrupt = true;
            thrown.set(thrownBy(() -> engine.run(new FactMap<>())));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, CloseEverySessionTest::awaitWaitingForACopy, Thread::interrupt));

        assertInstanceOf(InterruptedException.class,
                assertInstanceOf(RuleExecutionException.class, thrown.get()).getCause());
        assertEquals("waiter interrupted true", language.compilerClosings.get(0),
                "the waiter closed its rules with the status set: " + language.compilerClosings);
        assertTrue(interrupted.get(), "the thread's interrupt status after run()");
    }

    @Test
    @DisplayName("a run interrupted before a rule, whose listener clears the interrupt status, gives back its copy with"
            + " the status set, and returns with it set")
    void interruptedRunWithAListenerThatClearsTheStatusKeepsIt() {
        RecordingLanguage language = new RecordingLanguage();
        AtomicReference<RulesEngine<Map<String, Object>>> engineRef = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language)
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        // Retired while the run holds its copy, so giving it back closes the rules.
                        engineRef.get().close();
                    }

                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        // As a listener that swallows an InterruptedException does.
                        Thread.interrupted();
                    }
                }), 1);
        engineRef.set(engine);
        engine.load(rules("r"));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread runner = new Thread(() -> {
            Thread.currentThread().interrupt();
            thrown.set(thrownBy(() -> engine.run(new FactMap<>())));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "runner");

        logsOf(() -> {
            runner.start();
            join(runner);
        });

        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("run() was interrupted before rule 'r'", stop.getMessage());
        assertEquals(List.of("runner interrupted true"), language.compilerClosings,
                "the run gave back its copy, closing the rules, with the status set");
        assertTrue(interrupted.get(), "the thread's interrupt status after run()");
    }

    @Test
    @DisplayName("a run interrupted while it waits for a copy, whose listener clears the interrupt status in beforeRun"
            + " and throws a fatal Error, closes the retired rules and throws the error with the status set")
    void interruptedWaitWithAFatalBeforeRunThatClearsTheStatusKeepsIt() {
        OutOfMemoryError fatal = new OutOfMemoryError("from beforeRun, on purpose");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1)
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        if ("waiter".equals(Thread.currentThread().getName())) {
                            Thread.interrupted();
                            throw fatal;
                        }
                    }
                }), 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();

        logsOf(() -> waitRetireAndStop(language, engine, null, () -> {
            thrown.set(thrownBy(() -> engine.run(new FactMap<>())));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, CloseEverySessionTest::awaitWaitingForACopy, Thread::interrupt));

        assertSame(fatal, thrown.get());
        assertEquals("waiter interrupted true", language.compilerClosings.get(0),
                "the waiter closed its rules with the status set: " + language.compilerClosings);
        assertTrue(interrupted.get(), "the thread's interrupt status after run()");
    }

    /**
     * Runs {@code engine} once on a thread of its own, named {@code runner}, whose interrupt status is set first if
     * {@code interruptFirst}, and returns what the run threw, and whether the thread was interrupted after it.
     */
    private static Map.Entry<Throwable, Boolean> runOnRunner(RulesEngine<Map<String, Object>> engine,
                                                             boolean interruptFirst) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread runner = new Thread(() -> {
            if (interruptFirst) {
                Thread.currentThread().interrupt();
            }
            thrown.set(thrownBy(() -> engine.run(new FactMap<>())));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "runner");

        logsOf(() -> {
            runner.start();
            join(runner);
        });
        return Map.entry(thrown.get(), interrupted.get());
    }

    @Test
    @DisplayName("a run interrupted before a rule, whose listener clears the interrupt status in onRunError and throws"
            + " a fatal Error, gives back its copy and throws the error with the status set")
    void interruptedRunWithAFatalOnRunErrorThatClearsTheStatusKeepsIt() {
        OutOfMemoryError fatal = new OutOfMemoryError("from onRunError, on purpose");
        RecordingLanguage language = new RecordingLanguage();
        AtomicReference<RulesEngine<Map<String, Object>>> engineRef = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language)
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        // Retired while the run holds its copy, so giving it back closes the rules.
                        engineRef.get().close();
                    }

                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        Thread.interrupted();
                        throw fatal;
                    }
                }), 1);
        engineRef.set(engine);
        engine.load(rules("r"));

        Map.Entry<Throwable, Boolean> outcome = runOnRunner(engine, true);

        assertSame(fatal, outcome.getKey());
        assertEquals(List.of("runner interrupted true"), language.compilerClosings,
                "the run gave back its copy, closing the rules, with the status set");
        assertTrue(outcome.getValue(), "the thread's interrupt status after run()");
    }

    @Test
    @DisplayName("a run interrupted during a rule, whose listener clears the interrupt status in onError and throws a"
            + " fatal Error, gives back its copy and throws the error with the status set")
    void interruptedRuleWithAFatalOnErrorThatClearsTheStatusKeepsIt() {
        OutOfMemoryError fatal = new OutOfMemoryError("from onError, on purpose");
        RecordingLanguage language = new RecordingLanguage();
        AtomicReference<RulesEngine<Map<String, Object>>> engineRef = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language)
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        // Retired while the run holds its copy, so giving it back closes the rules.
                        engineRef.get().close();
                    }

                    @Override
                    public void onError(Rule rule, RuleExecutionException error) {
                        Thread.interrupted();
                        throw fatal;
                    }
                }), 1);
        engineRef.set(engine);
        engine.load(rules("r"));
        // The action is interrupted while it runs, so the run stops during the rule, when the action returns.
        language.duringAction = () -> Thread.currentThread().interrupt();

        Map.Entry<Throwable, Boolean> outcome = runOnRunner(engine, false);

        assertSame(fatal, outcome.getKey());
        assertEquals(List.of("runner interrupted true"), language.compilerClosings,
                "the run gave back its copy, closing the rules, with the status set");
        assertTrue(outcome.getValue(), "the thread's interrupt status after run()");
    }

    @Test
    @DisplayName("a run whose deadline passes while it waits for a copy, whose leaving closes retired rules with a"
            + " fatal Error, is reported as a stop first, and the error carries the stop")
    void timedOutWaitThenFatalCloseIsReportedAsAStop() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage language = new RecordingLanguage();
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch reporting = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        // Holds the waiter in onRunError, once its deadline has passed, until close() has retired its rules. The
        // stop is reported before the run leaves the rules, so they're retired before the waiter leaves, whatever
        // the timing.
        RuleListener holdingTheStop = new RuleListener() {
            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                if ("waiter".equals(Thread.currentThread().getName())) {
                    reporting.countDown();
                    await(retired);
                }
            }
        };
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1)
                .listener(recordingRunCallbacks(events)).listener(holdingTheStop), 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> waitRetireAndStop(language, engine, fatal,
                () -> thrown.set(thrownBy(() -> engine.runWithResult(new FactMap<>(),
                        RunOptions.withTimeoutOf(Duration.ofMillis(50))))),
                waiter -> await(reporting), waiter -> retired.countDown()));

        assertSame(fatal, thrown.get());
        assertEquals(1, fatal.getSuppressed().length, "the run's failure");
        RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class, fatal.getSuppressed()[0]);
        assertInstanceOf(TimeoutException.class, stop.getCause());
        assertEquals(List.of("waiter beforeRun", "waiter onRunError ReportedFailure cause TimeoutException"),
                waiterCallbacks(events), "every callback: " + events);
        assertTrue(logs.contains("while waiting for a compiled copy of the rules: all 1 were in use"), logs);
    }

    @Test
    @DisplayName("a run stopped while it waits for a copy, whose listener throws a fatal Error from onRunError, still"
            + " leaves the retired rules, and throws the listener's error rather than the later one from closing")
    void stoppedWaitWithAFatalListenerStillLeaves() {
        InternalError listenerFatal = new InternalError("the listener");
        OutOfMemoryError closeFatal = new OutOfMemoryError("closing the compiler");
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1)
                .listener(new RuleListener() {
                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        if ("waiter".equals(Thread.currentThread().getName())) {
                            throw listenerFatal;
                        }
                    }
                }), 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> waitRetireAndStop(language, engine, closeFatal,
                () -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))),
                CloseEverySessionTest::awaitWaitingForACopy, Thread::interrupt));

        assertSame(listenerFatal, thrown.get());
        assertEquals(0, listenerFatal.getSuppressed().length);
        assertEquals(List.of("compiler", "session 1", "compiler"), language.closed,
                "the waiter's rules as it left, then the holder's copy and rules as it gave the copy back");
    }

    @Test
    @DisplayName("a fatal Error that can't carry the failure it replaces, as the JVM's own OutOfMemoryError can't,"
            + " logs that failure at WARN")
    void fatalThatCantCarryTheFailureLogsIt() {
        // Suppression disabled, as on an OutOfMemoryError the JVM keeps ready, which no constructor built.
        Error fatal = new Error("preallocated", null, false, false) {
        };
        IllegalStateException failure = new IllegalStateException("the engine is closed");
        AtomicReference<Error> result = new AtomicReference<>();

        String logs = logsOf(() -> result.set(Failures.fatalInsteadOf(failure, fatal)));

        assertSame(fatal, result.get());
        assertEquals(0, fatal.getSuppressed().length);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "A failure was replaced by the fatal error " + fatal
                + ", which can't carry it as a suppressed exception: java.lang.IllegalStateException: the engine is"
                + " closed"), logs);
    }

    @Test
    @DisplayName("guard: a fatal Error that carries the failure it replaces logs nothing more")
    void fatalThatCarriesTheFailureLogsNothing() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing");
        IllegalStateException failure = new IllegalStateException("the engine is closed");

        String logs = logsOf(() -> Failures.fatalInsteadOf(failure, fatal));

        assertArrayEquals(new Throwable[] {failure}, fatal.getSuppressed());
        assertEquals("", logs);
    }

    /**
     * Waits until a run is queued for one of {@code permits}, which it can only be once it's counted on its rule set
     * and waiting for a copy: a thread merely parked with a timeout may be waiting for something else.
     */
    private static void awaitQueued(CopyPermits permits) {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!permits.available().hasQueuedThreads()) {
            assertTrue(System.nanoTime() < giveUp, "the run never started waiting for a copy");
            Thread.onSpinWait();
        }
    }

    /**
     * Returns what {@code action} throws, or {@code null} if it throws nothing. Not {@code assertThrows()}, which
     * rethrows an {@link OutOfMemoryError} it didn't expect, and so would stop the test JVM rather than fail the test.
     */
    private static Throwable thrownBy(Executable action) {
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
