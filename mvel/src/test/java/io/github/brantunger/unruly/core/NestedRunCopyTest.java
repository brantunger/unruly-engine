package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run started from inside another run must not wait for the copy of the rules the run around it is holding. The
 * engine used to recognise only a run on the same thread borrowing from the same rule list, so a nested run on
 * another thread, on another engine, or after a reload waited for a copy that could never come free, and the first
 * two hung for good. Each test here hangs on 1.x until its own timeout fails it.
 *
 * <p>
 * Every rule below fires again in the nested run, because it's the same rule list, so each fact stops crossing after
 * the first call.
 * </p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a nested run never waits for a copy the run around it is holding")
class NestedRunCopyTest {

    private static final String INNER = "inner";

    /** A fact that runs its engine on another thread from inside a run, as a fan-out or a join would. */
    public static final class OtherThread {

        private final AtomicReference<RulesEngine<Map<String, Object>>> engine = new AtomicReference<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicBoolean fannedOut = new AtomicBoolean();

        /**
         * Runs the engine on another thread and waits for it.
         *
         * @return What the nested run produced, or {@code "TIMEOUT"} if it never got a copy
         */
        public Object nested() {
            if (!fannedOut.compareAndSet(false, true)) {
                return INNER;
            }
            Future<Map<String, Object>> nested = executor.submit(() -> engine.get().run(facts("helper", this)));
            try {
                return nested.get(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (TimeoutException e) {
                return "TIMEOUT";
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    /** A fact that runs another engine from inside a run, once both runs hold their own engine's only copy. */
    public static final class OtherEngine {

        private final AtomicReference<RulesEngine<Map<String, Object>>> other = new AtomicReference<>();
        private final CyclicBarrier bothHoldACopy;
        private final AtomicBoolean crossed = new AtomicBoolean();

        OtherEngine(CyclicBarrier bothHoldACopy) {
            this.bothHoldACopy = bothHoldACopy;
        }

        /**
         * Waits until the other engine's run holds its copy too, then runs the other engine.
         *
         * @return What the other engine's run produced
         */
        public Object crossing() {
            if (!crossed.compareAndSet(false, true)) {
                return INNER;
            }
            try {
                bothHoldACopy.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return other.get().run(facts("cross", this));
        }
    }

    /** A fact that holds up a run until the rules have been replaced, then runs the engine again. */
    public static final class Reload {

        private final CountDownLatch holdingTheOldCopy = new CountDownLatch(1);
        private final CountDownLatch reloaded = new CountDownLatch(1);
        private final CountDownLatch holdingTheNewCopy = new CountDownLatch(1);
        private final AtomicReference<RulesEngine<Map<String, Object>>> engine = new AtomicReference<>();

        /**
         * Waits until the rules have been replaced and another thread holds the new list's only copy, then runs the
         * engine, which borrows from the new list.
         *
         * @param holder The fact the reloaded rule needs
         * @return What the nested run produced
         */
        public Object afterReload(Holder holder) {
            holdingTheOldCopy.countDown();
            try {
                assertTrue(reloaded.await(20, TimeUnit.SECONDS), "the rules were never reloaded");
                assertTrue(holdingTheNewCopy.await(20, TimeUnit.SECONDS), "the new list's copy was never taken");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return engine.get().run(facts("holder", holder));
        }
    }

    /** A fact that holds the first run that reaches it until the test releases it. */
    public static final class Holder {

        private final CountDownLatch holding = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean held = new AtomicBoolean();

        /**
         * Reports that this run holds a copy, and waits. Only the first run is held: the nested run fires the same
         * rule, and it's the one the test is about.
         *
         * @return {@code true}
         */
        public boolean hold() {
            if (!held.compareAndSet(false, true)) {
                return true;
            }
            holding.countDown();
            try {
                assertTrue(release.await(20, TimeUnit.SECONDS), "the run was never released");
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static RulesEngine<Map<String, Object>> engine(int maxCopies, Rule... rules) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .maxCopies(maxCopies).build();
        engine.load(List.of(rules));
        return engine;
    }

    private static Rule rule(String name, String action) {
        return Rule.builder().ruleName(name).condition("true").action(action).build();
    }

    private static FactStore<Object> facts(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    @Test
    @DisplayName("a run handed to another thread from inside a run gets a copy instead of waiting for ever")
    void nestedOnAnotherThread() {
        OtherThread helper = new OtherThread();
        RulesEngine<Map<String, Object>> engine = engine(1, rule("fan-out", "output.put('nested', helper.nested())"));
        helper.engine.set(engine);

        // The engine can't tell the other thread's run from an unrelated caller, so that run does wait — but only
        // until five seconds pass with no copy given back. Then it takes a copy of its own, rather than waiting for
        // the copy this run is holding while it waits for that run.
        Map<String, Object> output = engine.run(facts("helper", helper));

        assertEquals(Map.of("nested", INNER), output.get("nested"), "the nested run finished");
        helper.executor.shutdownNow();
    }

    @Test
    @DisplayName("two engines that run each other from their actions don't deadlock")
    void nestedOnAnotherEngine() throws Exception {
        CyclicBarrier bothHoldACopy = new CyclicBarrier(2);
        OtherEngine fromA = new OtherEngine(bothHoldACopy);
        OtherEngine fromB = new OtherEngine(bothHoldACopy);
        RulesEngine<Map<String, Object>> a = engine(1, rule("a", "output.put('other', cross.crossing())"));
        RulesEngine<Map<String, Object>> b = engine(1, rule("b", "output.put('other', cross.crossing())"));
        // Each engine's action runs the other, and neither crosses until both hold their own engine's only copy.
        fromA.other.set(b);
        fromB.other.set(a);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        Future<Map<String, Object>> first = threads.submit(() -> a.run(facts("cross", fromA)));
        Future<Map<String, Object>> second = threads.submit(() -> b.run(facts("cross", fromB)));

        assertEquals(Map.of("other", Map.of("other", INNER)), first.get(30, TimeUnit.SECONDS),
                "the run of engine A deadlocked");
        assertEquals(Map.of("other", Map.of("other", INNER)), second.get(30, TimeUnit.SECONDS),
                "the run of engine B deadlocked");
        threads.shutdownNow();
    }

    @Test
    @DisplayName("a nested run started after a reload doesn't wait for the new rules' copy")
    void nestedAfterAReload() throws Exception {
        Reload reload = new Reload();
        Holder holder = new Holder();
        RulesEngine<Map<String, Object>> engine =
                engine(1, rule("outer", "output.put('nested', reload.afterReload(holder))"));
        reload.engine.set(engine);
        ExecutorService threads = Executors.newFixedThreadPool(2);

        FactStore<Object> outerFacts = facts("reload", reload);
        outerFacts.setValue("holder", holder);
        Future<Map<String, Object>> outer = threads.submit(() -> engine.run(outerFacts));
        // The rules the outer run started with are replaced while it holds their only copy, and another thread takes
        // the new list's only copy and keeps it. The nested run borrows from the new list, where its own thread holds
        // nothing, and must still not wait.
        assertTrue(reload.holdingTheOldCopy.await(20, TimeUnit.SECONDS),
                "the outer run never reached its action, so it would read the reloaded rules");
        engine.load(List.of(rule("reloaded", "output.put('held', holder.hold())")));
        reload.reloaded.countDown();
        Future<Map<String, Object>> newList = threads.submit(() -> engine.run(facts("holder", holder)));
        assertTrue(holder.holding.await(20, TimeUnit.SECONDS), "the new list's only copy was never taken");
        reload.holdingTheNewCopy.countDown();

        Map<String, Object> output = outer.get(30, TimeUnit.SECONDS);

        assertEquals(Map.of("held", true), output.get("nested"),
                "the nested run fired the reloaded rule while the other thread still held the only copy");
        holder.release.countDown();
        assertNotNull(newList.get(20, TimeUnit.SECONDS));
        threads.shutdownNow();
    }
}
