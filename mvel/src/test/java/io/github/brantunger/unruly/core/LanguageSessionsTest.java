package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("each language gets one session per copy of the rules, and the engine closes what it no longer needs")
class LanguageSessionsTest {

    private static final String CLOSED = "The engine is closed";

    /** A single-threaded resource, such as a GraalJS context. */
    static final class ConfinedSession implements Session {
        final AtomicBoolean inUse = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();
        final Throwable closeFailure;

        ConfinedSession(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            throwIfSet(closeFailure);
        }
    }

    /**
     * A language whose sessions are single-threaded resources. A condition is always true, and an action puts the rule's
     * action text into the output, with the number of the session it ran with.
     */
    static final class ConfinedLanguage implements ExpressionLanguage {
        final String name;
        final List<ConfinedSession> sessions = new CopyOnWriteArrayList<>();
        final List<Session> usedSessions = new CopyOnWriteArrayList<>();
        final AtomicInteger misuses = new AtomicInteger();
        final AtomicInteger compilersClosed = new AtomicInteger();
        volatile Throwable closeFailure;
        volatile Throwable compilerCloseFailure;
        volatile Runnable duringAction = () -> {
        };

        ConfinedLanguage(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> {
                        use(session);
                        return true;
                    };
                }

                @SuppressWarnings("unchecked")
                @Override
                public CompiledAction compileAction(Expression expression) {
                    String source = expression.text();
                    return (action, session) -> {
                        use(session);
                        duringAction.run();
                        ((Map<String, Object>) action.output()).put(source, sessions.indexOf(session) + 1);
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    ConfinedSession session = new ConfinedSession(closeFailure);
                    sessions.add(session);
                    return session;
                }

                @Override
                public void close() {
                    compilersClosed.incrementAndGet();
                    throwIfSet(compilerCloseFailure);
                }
            };
        }

        // Counts a session used while another run uses it, or after it was closed.
        private void use(Session session) {
            ConfinedSession confined = (ConfinedSession) session;
            usedSessions.add(confined);
            if (confined.closes.get() > 0 || !confined.inUse.compareAndSet(false, true)) {
                misuses.incrementAndGet();
                return;
            }
            Thread.yield();
            confined.inUse.set(false);
        }

        List<Integer> closes() {
            return sessions.stream().map(session -> session.closes.get()).toList();
        }
    }

    private static void throwIfSet(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Rule rule(String name, String language) {
        return Rule.builder().ruleName(name).language(language).condition("c").action(name).build();
    }

    /** An engine that fires every match, with MVEL for rules without a language, and the given languages. */
    private static RulesEngine<Map<String, Object>> stateful(ExpressionLanguage... languages) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);
        builder.language(new MvelExpressionLanguage()).defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME);
        for (ExpressionLanguage language : languages) {
            builder.language(language);
        }
        return builder.build();
    }

    /** An engine that fires the first match, with only the given language. */
    private static RulesEngine<Map<String, Object>> firstMatch(ExpressionLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).language(language).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("a run gives every rule of a language the same session, and a later run reuses it")
    void oneSessionPerLanguagePerCopy() {
        ConfinedLanguage confined = new ConfinedLanguage("confined");
        RulesEngine<Map<String, Object>> engine = stateful(confined);
        engine.load(List.of(rule("a", "confined"), rule("b", "confined"), rule("c", "confined"),
                Rule.builder().ruleName("m").condition("true").action("output.put('m', 1)").build()));

        Map<String, Object> first = engine.run(new FactMap<>());
        Map<String, Object> second = engine.run(new FactMap<>());

        assertEquals(Map.of("a", 1, "b", 1, "c", 1, "m", 1), first);
        assertEquals(first, second);
        assertEquals(1, confined.sessions.size(), "sessions made for three rules and two runs");
        assertEquals(12, confined.usedSessions.size());
        assertTrue(confined.usedSessions.stream().allMatch(session -> session == confined.sessions.get(0)));
    }

    @Test
    @DisplayName("concurrent runs never use one session at the same time, and make no more sessions than runs overlap")
    void concurrentRunsNeverShareASession() throws Exception {
        ConfinedLanguage confined = new ConfinedLanguage("confined");
        RulesEngine<Map<String, Object>> engine = firstMatch(confined);
        engine.load(List.of(rule("a", "confined"), rule("b", "confined")));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                runs.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        assertEquals(1, engine.run(new FactMap<>()).size());
                    }
                }));
            }
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, confined.misuses.get(), "uses of a session another run was using, or that was closed");
        assertTrue(confined.sessions.size() <= 8, confined.sessions.size() + " sessions");
    }

    @Test
    @DisplayName("a reload closes the replaced rules' idle sessions and compiler at once")
    void reloadClosesIdleSessionsAndCompiler() {
        ConfinedLanguage confined = new ConfinedLanguage("confined");
        RulesEngine<Map<String, Object>> engine = stateful(confined);
        engine.load(List.of(rule("a", "confined")));
        engine.run(new FactMap<>());

        engine.load(List.of(rule("b", "confined")));

        assertEquals(List.of(1), confined.closes());
        assertEquals(1, confined.compilersClosed.get());
        assertEquals(Map.of("b", 2), engine.run(new FactMap<>()));
        assertEquals(List.of(1, 0), confined.closes());
    }

    @Test
    @DisplayName("a reload closes a session still in use, and then the compiler, only when the run using it returns")
    void reloadWaitsForRunsInProgress() throws Exception {
        ConfinedLanguage confined = new ConfinedLanguage("confined");
        RulesEngine<Map<String, Object>> engine = firstMatch(confined);
        engine.load(List.of(rule("a", "confined")));
        CountDownLatch inAction = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean hold = new AtomicBoolean(true);
        confined.duringAction = () -> {
            if (hold.getAndSet(false)) {
                inAction.countDown();
                await(finish);
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> held = pool.submit(() -> engine.run(new FactMap<>()));
            await(inAction);
            // Makes a second session, which is idle once this run returns.
            engine.run(new FactMap<>());

            engine.load(List.of(rule("b", "confined")));

            assertEquals(List.of(0, 1), confined.closes(), "the held session stays open, and the idle one is closed");
            assertEquals(0, confined.compilersClosed.get());

            finish.countDown();

            assertEquals(Map.of("a", 1), held.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(1, 1), confined.closes());
            assertEquals(1, confined.compilersClosed.get());
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("closing the engine closes its sessions and compiler; afterwards run and load fail")
    void closeEngine() {
        ConfinedLanguage confined = new ConfinedLanguage("confined");
        RulesEngine<Map<String, Object>> engine = stateful(confined);
        engine.load(List.of(rule("a", "confined")));
        engine.run(new FactMap<>());

        engine.close();
        engine.close();

        assertEquals(List.of(1), confined.closes());
        assertEquals(1, confined.compilersClosed.get());
        assertNull(((AbstractRulesEngine<?>) engine).getCompiledRules());
        assertEquals(CLOSED,
                assertThrows(IllegalStateException.class, () -> engine.run(new FactMap<>())).getMessage());
        List<Rule> rules = List.of(rule("b", "confined"));
        assertEquals(CLOSED, assertThrows(IllegalStateException.class, () -> engine.load(rules)).getMessage());
        assertEquals(2, confined.compilersClosed.get(), "the compiler of the rules loaded after the engine closed");
    }

    @Test
    @DisplayName("an engine closed before any rules are loaded says it's closed")
    void closeBeforeRulesLoaded() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();

        engine.close();

        assertEquals(CLOSED,
                assertThrows(IllegalStateException.class, () -> engine.run(new FactMap<>())).getMessage());
    }

    @Test
    @DisplayName("a session or compiler that fails to close is logged at WARN, and the reload still succeeds")
    void closeFailuresLogged() {
        ConfinedLanguage confined = new ConfinedLanguage("confined");
        confined.closeFailure = new IllegalStateException("session stuck");
        confined.compilerCloseFailure = new IllegalStateException("compiler stuck");
        RulesEngine<Map<String, Object>> engine = stateful(confined);
        engine.load(List.of(rule("a", "confined")));
        engine.run(new FactMap<>());

        String logs = logsOf(() -> engine.load(List.of(rule("b", "confined"))));

        assertTrue(logs.contains("WARN io.github.brantunger.unruly.engine - The 'confined' expression language failed "
                + "to close a session: "), logs);
        assertTrue(logs.contains("session stuck"), logs);
        assertTrue(logs.contains("WARN io.github.brantunger.unruly.engine - The 'confined' expression language failed "
                + "to close its compiler: "), logs);
        assertTrue(logs.contains("compiler stuck"), logs);
        assertEquals(Map.of("b", 2), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a fatal Error from closing is rethrown once every session and the compilers have been closed")
    void fatalCloseErrorRethrownAfterClosingTheRest() {
        OutOfMemoryError first = new OutOfMemoryError("first");
        ConfinedLanguage a = new ConfinedLanguage("a");
        a.closeFailure = first;
        ConfinedLanguage b = new ConfinedLanguage("b");
        b.closeFailure = new OutOfMemoryError("second");
        RulesEngine<Map<String, Object>> engine = stateful(a, b);
        engine.load(List.of(rule("x", "a"), rule("y", "b")));
        engine.run(new FactMap<>());
        List<Rule> next = List.of(rule("z", "a"));

        assertSame(first, assertThrows(OutOfMemoryError.class, () -> engine.load(next)));

        assertEquals(List.of(1), a.closes());
        assertEquals(List.of(1), b.closes());
        assertEquals(1, a.compilersClosed.get());
        assertEquals(1, b.compilersClosed.get());
        assertEquals(Map.of("z", 2), engine.run(new FactMap<>()), "the new rules were loaded before the old closed");
    }

    @Test
    @DisplayName("a rule list that fails to load closes the compilers it created")
    void failedLoadClosesCompilers() {
        ConfinedLanguage confined = new ConfinedLanguage("confined");
        RulesEngine<Map<String, Object>> engine = stateful(confined);
        List<Rule> rules = List.of(rule("a", "confined"), rule("b", "unknown"));

        assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals(1, confined.compilersClosed.get());
        assertEquals(List.of(), confined.sessions);
    }

    @Test
    @DisplayName("a run that reads rules just as they're closed reads the rules again")
    void runRetriesAfterRulesClosed() {
        RuleSet closedRules = new RuleSet(List.of(), Map.of());
        closedRules.retire();
        AtomicInteger reads = new AtomicInteger();
        EngineConfiguration<String> configuration = new EngineConfiguration<>(List.of(), null, List.of(), List.of(),
                EngineConfiguration.UNLIMITED_COPIES, Object.class, OutputWriter.beansAndMaps(), Map.of());
        AbstractRulesEngine<String> engine = new AbstractRulesEngine<>(configuration) {
            @Override
            RuleSet currentRules() {
                return reads.getAndIncrement() == 0 ? closedRules : super.currentRules();
            }

            @Override
            public RunResult<String> runWithResult(FactStore<?> facts) {
                return runInScope(facts, (rules, copy, values) ->
                        RunResult.of("rules: " + rules.rules().size(), List.of(), rules.checksum()));
            }

            @Override
            String matchPolicy() {
                return "firstMatch";
            }
        };
        engine.load(List.of());

        assertEquals("rules: 0", engine.run(new FactMap<>()));
        assertEquals(2, reads.get());
    }
}
