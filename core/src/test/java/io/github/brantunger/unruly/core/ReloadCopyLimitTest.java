package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ForwardingExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An engine's copy limit covers every rule list it has loaded, so a reload can't raise it: a run still using the rules
 * a reload replaced holds a copy that counts against the same limit as runs on the new rules (#372). Runs queued on
 * the replaced rules when the reload comes share the copies given back, rather than each making one of its own (#695).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a reload doesn't raise an engine's limit on compiled copies")
class ReloadCopyLimitTest {

    /** How many sessions one compiler has made, and how many of them have been closed. */
    private record Sessions(AtomicInteger made, AtomicInteger closed) {
    }

    /**
     * The toy language, with a session of its own as {@link GatedRuns#STATEFUL_TOY} has, which counts the sessions
     * each of its compilers makes and closes: one {@link Sessions} for each compiler, in the order they were made.
     */
    private static final class CountingToy implements ExpressionLanguage {
        final List<Sessions> compilers = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return ToyExpressionLanguage.LANGUAGE_NAME;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            ExpressionCompiler toy = new ToyExpressionLanguage().newCompiler(context);
            Sessions sessions = new Sessions(new AtomicInteger(), new AtomicInteger());
            compilers.add(sessions);
            return new ForwardingExpressionCompiler(toy) {
                @Override
                public Session newSession() {
                    sessions.made().incrementAndGet();
                    return new Session() {
                        @Override
                        public void close() {
                            sessions.closed().incrementAndGet();
                        }
                    };
                }
            };
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /** Waits until {@code count} runs are waiting for a copy of {@code rules}. */
    private static void awaitWaiting(RuleSet rules, int count) throws InterruptedException {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (rules.waiters() != count) {
            assertTrue(System.nanoTime() < giveUp, rules.waiters() + " of the " + count + " runs started waiting");
            Thread.sleep(1);
        }
    }

    /** Runs the old rules with {@code gate}, keeping what the run throws for the test to report. */
    private static void runOldRules(RulesEngine<Map<String, Object>> engine, GatedRuns.Gate gate,
                                    List<Throwable> failures) {
        try {
            assertEquals(Map.of("rules", "old"), engine.run(new FactMap<>(new Fact<Object>("gate", gate))));
        } catch (RuntimeException | Error e) {
            failures.add(e);
        }
    }

    @Test
    @DisplayName("a run on the new rules waits while a run on the replaced rules holds the only copy")
    void reloadKeepsTheLimit() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(GatedRuns.STATEFUL_TOY).maxCopies(1).build();
        engine.load(List.of(rule("old", "gate.hold", "put rules 'old'")));
        GatedRuns.Gate gate = new GatedRuns.Gate();
        Thread holder = new Thread(() -> engine.run(new FactMap<>(new Fact<Object>("gate", gate))), "holder");
        holder.start();
        assertTrue(gate.awaitHolding(30, TimeUnit.SECONDS), "the run on the old rules never started");

        engine.load(List.of(rule("new", "true", "put rules 'new'")));

        // On a thread of its own, so nothing another test left on JUnit's thread can make it look like a nested run.
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                engine.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofMillis(300)));
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        }, "waiter");
        waiter.start();
        waiter.join(TimeUnit.SECONDS.toMillis(30));
        RuleExecutionException waited = assertInstanceOf(RuleExecutionException.class, thrown.get(),
                "the run on the new rules got a copy instead of waiting");
        assertInstanceOf(TimeoutException.class, waited.getCause(),
                "the run on the new rules waited for the copy the old rules' run holds");

        gate.release();
        holder.join(TimeUnit.SECONDS.toMillis(30));
        assertEquals(Map.of("rules", "new"), engine.run(new FactMap<>()), "once it's given back, the new rules run");
    }

    @Test
    @DisplayName("runs queued on the replaced rules share the copies given back, so those rules make no more copies"
            + " than the limit")
    void queuedRunsShareTheReplacedRulesCopies() throws InterruptedException {
        int limit = 2;
        CountingToy language = new CountingToy();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).maxCopies(limit).build();
        // A window far longer than the test, so a run that waits never gives up and makes an extra copy.
        ((AbstractRulesEngine<Map<String, Object>>) engine).stallWindow(TimeUnit.MINUTES.toMillis(5));
        engine.load(List.of(rule("old", "gate.hold", "put rules 'old'")));
        RuleSet oldRules = ((AbstractRulesEngine<Map<String, Object>>) engine).currentRules();
        Sessions old = language.compilers.get(language.compilers.size() - 1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        // A gate for each run that holds a copy, as a gate tells only when the first run has reached it.
        List<GatedRuns.Gate> gates = new ArrayList<>();
        List<Thread> runs = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            GatedRuns.Gate gate = new GatedRuns.Gate();
            gates.add(gate);
            Thread holder = new Thread(() -> runOldRules(engine, gate, failures), "holder " + i);
            runs.add(holder);
            holder.start();
            assertTrue(gate.awaitHolding(30, TimeUnit.SECONDS), "the run on the old rules never started");
        }
        // Queued on the old rules before the reload, and let through at once when they get a copy.
        GatedRuns.Gate open = new GatedRuns.Gate();
        open.release();
        int queued = 6;
        for (int i = 0; i < queued; i++) {
            Thread waiter = new Thread(() -> runOldRules(engine, open, failures), "waiter " + i);
            runs.add(waiter);
            waiter.start();
        }
        awaitWaiting(oldRules, queued);

        engine.load(List.of(rule("new", "true", "put rules 'new'")));
        gates.forEach(GatedRuns.Gate::release);
        for (Thread run : runs) {
            run.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(run.isAlive(), run.getName() + " never ended");
        }

        assertEquals(List.of(), failures, "every run finished on the old rules");
        assertEquals(limit, old.made().get(), "the queued runs used the copies given back instead of making their own");
        assertEquals(limit, old.closed().get(), "and each copy was closed once the last run had left");
        assertEquals(Map.of("rules", "new"), engine.run(new FactMap<>()), "the new rules run");
        engine.close();
    }
}
