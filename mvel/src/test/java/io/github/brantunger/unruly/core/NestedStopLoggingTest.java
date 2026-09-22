package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A stop is logged once, by the run that stopped first: when a run started from inside a condition or action stops,
 * and its exception reaches the run around it, that run stops too without logging the same interrupt or deadline
 * again. A stop the expression swallows, or a nested run that failed rather than stopped, leaves the outer run's
 * stop logged as before. The same holds for a run that stopped before it got a copy of the rules: while waiting for
 * one, or while reading the engine's rules again.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a stop in a nested run is logged once")
class NestedStopLoggingTest {

    private static final Duration SHORT = Duration.ofSeconds(1);
    private static final long PAUSE_MILLIS = 2500;
    private static final Pattern STOP_LINE = Pattern.compile("WARN " + Pattern.quote(ENGINE_LOGGER) + "run\\(\\) ");

    /** A fact a condition calls to take longer than {@link #SHORT}. */
    public static final class Pause {

        /**
         * Sleeps past the deadline, then answers {@code true}.
         *
         * @return {@code true}
         * @throws InterruptedException if the thread is interrupted while sleeping
         */
        public boolean longer() throws InterruptedException {
            Thread.sleep(PAUSE_MILLIS);
            return true;
        }

        /**
         * Interrupts the current thread, then answers {@code true}.
         *
         * @return {@code true}
         */
        public boolean interrupt() {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** A fact an action calls to run another engine from inside a run. */
    public static final class Nested {

        private final RulesEngine<Map<String, Object>> inner;
        private final boolean swallow;
        private final boolean pauseAfter;

        Nested(RulesEngine<Map<String, Object>> inner, boolean swallow, boolean pauseAfter) {
            this.inner = inner;
            this.swallow = swallow;
            this.pauseAfter = pauseAfter;
        }

        /**
         * Runs the inner engine. What it throws leaves the action, unless this swallows it; with {@code pauseAfter},
         * the action first waits past the outer run's deadline.
         *
         * @return {@code true}
         * @throws InterruptedException if the thread is interrupted while waiting
         */
        public boolean run() throws InterruptedException {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("pause", new Pause());
            try {
                inner.run(facts);
            } catch (RuntimeException e) {
                if (pauseAfter) {
                    Thread.sleep(PAUSE_MILLIS);
                }
                if (!swallow) {
                    throw e;
                }
            }
            return true;
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static RulesEngine<Map<String, Object>> engine(RulesEngineBuilder<Map<String, Object>> builder,
                                                          Rule rule) {
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(rule));
        return engine;
    }

    private static RulesEngine<Map<String, Object>> outer() {
        return outer(RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).runTimeout(SHORT));
    }

    private static RulesEngine<Map<String, Object>> outer(RulesEngineBuilder<Map<String, Object>> builder) {
        return engine(builder, rule("outer", "true", "output.put('ran', nested.run())"));
    }

    private static FactStore<Object> nested(RulesEngine<Map<String, Object>> inner, boolean swallow,
                                            boolean pauseAfter) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("nested", new Nested(inner, swallow, pauseAfter));
        return facts;
    }

    /** Runs {@code outer}, which must stop, and returns the log lines it wrote about stopping. */
    private static List<String> stopLines(RulesEngine<Map<String, Object>> outer, FactStore<Object> facts,
                                          AtomicReference<RuleExecutionException> thrown) {
        String logs = logsOf(() -> thrown.set(assertThrows(RuleExecutionException.class, () -> outer.run(facts))));
        Thread.interrupted();
        return logs.lines().filter(line -> STOP_LINE.matcher(line).find()).toList();
    }

    private static int count(List<String> lines, String text) {
        int n = 0;
        for (String line : lines) {
            Matcher matcher = Pattern.compile(Pattern.quote(text)).matcher(line);
            while (matcher.find()) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("a nested run that passes the outer run's deadline logs the stop, and the outer run doesn't")
    void deadline() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.firstMatch(HashMap::new),
                rule("slow", "pause.longer()", "output.put('x', 1)"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer(), nested(inner, false, false), thrown);

        assertInstanceOf(TimeoutException.class, thrown.get().getCause());
        assertTrue(thrown.get().getMessage().endsWith(" during rule 'outer'"), thrown.get().getMessage());
        assertEquals(1, lines.size(), String.join("\n", lines));
        assertEquals(1, count(lines, "during rule 'slow'"), String.join("\n", lines));
    }

    @Test
    @DisplayName("a nested run whose thread is interrupted logs the stop, and the outer run doesn't")
    void interrupt() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.firstMatch(HashMap::new),
                rule("interrupting", "pause.interrupt()", "output.put('x', 1)"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer(RulesEngineBuilder.firstMatch(HashMap::new)), nested(inner, false, false),
                thrown);

        assertInstanceOf(InterruptedException.class, thrown.get().getCause());
        assertEquals(List.of("run() was interrupted during rule 'interrupting'"),
                lines.stream().map(line -> line.substring(STOP_LINE.matcher(line).results().findFirst()
                        .orElseThrow().start() + ("WARN " + ENGINE_LOGGER).length())).toList());
    }

    @Test
    @DisplayName("a stop three runs deep is logged once")
    void threeLevels() {
        RulesEngine<Map<String, Object>> innermost = engine(RulesEngineBuilder.firstMatch(HashMap::new),
                rule("slow", "pause.longer()", "output.put('x', 1)"));
        RulesEngine<Map<String, Object>> middle = engine(RulesEngineBuilder.firstMatch(HashMap::new),
                rule("middle", "true", "output.put('ran', nested.run())"));
        FactStore<Object> middleFacts = nested(innermost, false, false);
        RulesEngine<Map<String, Object>> outer = engine(
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).runTimeout(SHORT),
                rule("outer", "true", "output.put('ran', middleRun.run())"));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("middleRun", new MiddleRun(middle, middleFacts));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer, facts, thrown);

        assertEquals(1, lines.size(), String.join("\n", lines));
        assertEquals(1, count(lines, "during rule 'slow'"), String.join("\n", lines));
    }

    /** A fact the outer action calls to run the middle engine with facts that start the innermost run. */
    public static final class MiddleRun {

        private final RulesEngine<Map<String, Object>> middle;
        private final FactStore<Object> facts;

        MiddleRun(RulesEngine<Map<String, Object>> middle, FactStore<Object> facts) {
            this.middle = middle;
            this.facts = facts;
        }

        /**
         * Runs the middle engine, letting what it throws leave the action.
         *
         * @return {@code true}
         */
        public boolean run() {
            middle.run(facts);
            return true;
        }
    }

    @Test
    @DisplayName("a nested run that passed its own, earlier deadline doesn't hide the outer run's deadline")
    void differentDeadlines() {
        RulesEngine<Map<String, Object>> inner = engine(
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).runTimeout(Duration.ofMillis(500)),
                rule("slow", "pause.longer()", "output.put('x', 1)"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer(), nested(inner, false, false), thrown);

        assertEquals(2, lines.size(), String.join("\n", lines));
        assertEquals(1, count(lines, "during rule 'slow'"), String.join("\n", lines));
        assertEquals(1, count(lines, "during rule 'outer'"), String.join("\n", lines));
    }

    @Test
    @DisplayName("a nested run that passed the deadline doesn't hide the outer run's interrupt")
    void deadlineThenInterrupt() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.firstMatch(HashMap::new),
                rule("slow", "pause.longer()", "output.put('x', 1)"));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("nested", new InterruptingNested(inner));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer(), facts, thrown);

        assertInstanceOf(InterruptedException.class, thrown.get().getCause());
        assertEquals(1, count(lines, "passed its deadline of"), String.join("\n", lines));
        assertEquals(1, count(lines, "was interrupted during rule 'outer'"), String.join("\n", lines));
    }

    /** A fact an action calls to run another engine, then interrupt its own thread and rethrow what that run threw. */
    public static final class InterruptingNested {

        private final RulesEngine<Map<String, Object>> inner;

        InterruptingNested(RulesEngine<Map<String, Object>> inner) {
            this.inner = inner;
        }

        /**
         * Runs the inner engine; when it throws, interrupts the thread and rethrows.
         *
         * @return {@code true}
         */
        public boolean run() {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("pause", new Pause());
            try {
                inner.run(facts);
            } catch (RuntimeException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return true;
        }
    }

    @Test
    @DisplayName("a stop the action swallows is logged again when the outer run stops at its next check")
    void swallowedStop() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.firstMatch(HashMap::new),
                rule("slow", "pause.longer()", "output.put('x', 1)"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer(), nested(inner, true, false), thrown);

        assertEquals(2, lines.size(), String.join("\n", lines));
        assertEquals(1, count(lines, "during rule 'slow'"), String.join("\n", lines));
        assertEquals(1, count(lines, "during rule 'outer'"), String.join("\n", lines));
    }

    @Test
    @DisplayName("a nested run that failed, rather than stopped, doesn't hide the outer run's stop")
    void nestedFailureThenOuterStop() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.firstMatch(HashMap::new),
                rule("broken", "missing.value > 1", "output.put('x', 1)"));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(RuleExecutionException.class,
                () -> outer().run(nested(inner, false, true)))));

        assertInstanceOf(TimeoutException.class, thrown.get().getCause());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Failed to evaluate condition for rule 'broken'"), logs);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "run() passed its deadline of "), logs);
        assertTrue(logs.contains(" during rule 'outer'"), logs);
    }

    @Test
    @DisplayName("a nested run that passes the outer run's deadline while reading the rules again logs the stop, and"
            + " the outer run doesn't")
    void deadlineWhileReadingAgain() {
        RulesEngine<Map<String, Object>> inner = readingAgain(RulesEngineBuilder.firstMatch(HashMap::new), SHORT);
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer(), nested(inner, false, false), thrown);

        assertInstanceOf(TimeoutException.class, thrown.get().getCause());
        assertEquals(1, lines.size(), String.join("\n", lines));
        assertEquals(1, count(lines, "while reading the engine's rules again"), String.join("\n", lines));
    }

    @Test
    @DisplayName("a nested run that passed its own deadline while reading the rules again doesn't hide the outer"
            + " run's interrupt")
    void deadlineWhileReadingAgainThenInterrupt() {
        RulesEngine<Map<String, Object>> inner = readingAgain(
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).runTimeout(Duration.ofMillis(1)),
                Duration.ofMillis(5));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("nested", new InterruptingNested(inner));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        List<String> lines = stopLines(outer(RulesEngineBuilder.firstMatch(HashMap::new)), facts, thrown);

        assertInstanceOf(InterruptedException.class, thrown.get().getCause());
        assertEquals(2, lines.size(), String.join("\n", lines));
        assertEquals(1, count(lines, "passed its deadline of"), String.join("\n", lines));
        assertEquals(1, count(lines, "while reading the engine's rules again"), String.join("\n", lines));
        assertEquals(1, count(lines, "was interrupted during rule 'outer'"), String.join("\n", lines));
    }

    /**
     * Returns an engine whose next run finds the rules it read closed, and passes its deadline while it reads them
     * again.
     */
    private static RulesEngine<Map<String, Object>> readingAgain(RulesEngineBuilder<Map<String, Object>> builder,
                                                                Duration past) {
        ReloadingClock clock = new ReloadingClock(past);
        RulesEngine<Map<String, Object>> engine = engine(builder.clock(clock), inner());
        clock.arm(engine);
        return engine;
    }

    private static Rule inner() {
        return rule("inner", "true", "output.put('x', 1)");
    }

    /**
     * An engine's clock, which a run reads once, after it has read the engine's rules and before it borrows a copy of
     * them. Once armed, the next read reloads the engine, which closes the rules that run read, then waits until
     * {@code past} has gone by, so the run's deadline has passed when it finds them closed. That relies on the order in
     * which {@code AbstractRulesEngine.runInScope} works: it reads the rules, takes the run's deadline, then reads
     * the clock once, before it borrows a copy. Should the clock be read anywhere else, the tests that use this one
     * would stop reaching the rules being read again.
     */
    private static final class ReloadingClock extends Clock {

        private final Duration past;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicReference<RulesEngine<Map<String, Object>>> engine = new AtomicReference<>();

        ReloadingClock(Duration past) {
            this.past = past;
        }

        void arm(RulesEngine<Map<String, Object>> reloaded) {
            engine.set(reloaded);
            armed.set(true);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (armed.getAndSet(false)) {
                engine.get().load(List.of(inner()));
                // Parks rather than spins, and ends only once past has gone by, however early a park returns: parkNanos
                // can wake spuriously. On an interrupted thread it returns at once, so the wait still lasts past, as a
                // spin, and the interrupt status is left set for the run to see. No test here arms it on one.
                Instant until = Instant.now().plus(past);
                for (Duration left = Duration.between(Instant.now(), until); left.isPositive();
                     left = Duration.between(Instant.now(), until)) {
                    LockSupport.parkNanos(left.toNanos());
                }
            }
            return Instant.now();
        }
    }

    @Test
    @DisplayName("a run on another thread that timed out waiting for a copy doesn't hide the interrupt of the run"
            + " whose action rethrew its stop")
    void timeoutWaitingForACopyThenInterrupt() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RulesEngine<Map<String, Object>> limited = engine(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new HoldingLanguage(holding, release)).defaultLanguage("holding").maxCopies(1),
                rule("held", "c", "a"));
        Thread holder = new Thread(() -> limited.run(new FactMap<>()), "holder");
        holder.start();
        try {
            assertTrue(holding.await(30, TimeUnit.SECONDS), "the holder's run never started");
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("nested", new CrossThread(limited));
            AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

            List<String> lines = stopLines(outer(RulesEngineBuilder.firstMatch(HashMap::new)), facts, thrown);

            assertInstanceOf(InterruptedException.class, thrown.get().getCause());
            assertEquals(2, lines.size(), String.join("\n", lines));
            assertEquals(1, count(lines, "while waiting for a compiled copy"), String.join("\n", lines));
            assertEquals(1, count(lines, "was interrupted during rule 'outer'"), String.join("\n", lines));
        } finally {
            // stopLines clears the interrupt CrossThread set only when the outer run stops as expected; cleared here
            // too, so a failed assertion isn't hidden by the join throwing, and the holder is always joined.
            Thread.interrupted();
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    /**
     * A language whose conditions, on the thread named {@code holder}, keep the copy of the rules they run in until
     * released. Each copy gets a session of its own, so a limit on copies applies.
     */
    private record HoldingLanguage(CountDownLatch holding, CountDownLatch release) implements ExpressionLanguage {

        @Override
        public String name() {
            return "holding";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (c, session) -> {
                        if (Thread.currentThread().getName().equals("holder")) {
                            holding.countDown();
                            return release.await(30, TimeUnit.SECONDS);
                        }
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (c, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return new Session() { };
                }
            };
        }
    }

    /** A fact an action calls to run another engine on another thread, then interrupt its own and rethrow. */
    public static final class CrossThread {

        private final RulesEngine<Map<String, Object>> engine;

        CrossThread(RulesEngine<Map<String, Object>> engine) {
            this.engine = engine;
        }

        /**
         * Runs the engine on another thread, with a timeout shorter than the other run holds the only copy, and waits
         * for it; then interrupts this thread and rethrows what that run threw.
         *
         * @return {@code true}
         * @throws InterruptedException if this thread is interrupted while waiting
         * @throws AssertionError       if the run on the other thread hasn't ended within 30 seconds
         */
        public boolean run() throws InterruptedException {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    engine.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofMillis(50)));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "worker");
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(30));
            if (worker.isAlive()) {
                throw new AssertionError("the run on the other thread didn't end within 30 seconds");
            }
            Thread.currentThread().interrupt();
            Throwable thrown = failure.get();
            if (thrown instanceof RuntimeException e) {
                throw e;
            }
            if (thrown instanceof Error e) {
                throw e;
            }
            if (thrown != null) {
                throw new AssertionError("the run on the other thread threw a checked exception", thrown);
            }
            return true;
        }
    }
}
