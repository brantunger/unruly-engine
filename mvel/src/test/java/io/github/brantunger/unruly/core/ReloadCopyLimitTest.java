package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An engine's copy limit covers every rule list it has loaded, so a reload can't raise it: a run still using the rules
 * a reload replaced holds a copy that counts against the same limit as runs on the new rules (#372).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a reload doesn't raise an engine's limit on compiled copies")
class ReloadCopyLimitTest {

    /** A fact whose method holds the run that calls it until the test lets it go. */
    public static final class Gate {

        private final CountDownLatch holding = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * Waits until the test releases the gate.
         *
         * @return {@code true}, so a condition that calls it matches
         * @throws InterruptedException if the thread is interrupted while it waits
         */
        public boolean hold() throws InterruptedException {
            holding.countDown();
            return release.await(30, TimeUnit.SECONDS);
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    @Test
    @DisplayName("a run on the new rules waits while a run on the replaced rules holds the only copy")
    void reloadKeepsTheLimit() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .maxCopies(1).build();
        engine.load(List.of(rule("old", "gate.hold()", "output.put('rules', 'old')")));
        Gate gate = new Gate();
        Thread holder = new Thread(() -> engine.run(new FactMap<>(new Fact<Object>("gate", gate))), "holder");
        holder.start();
        assertTrue(gate.holding.await(30, TimeUnit.SECONDS), "the run on the old rules never started");

        engine.load(List.of(rule("new", "true", "output.put('rules', 'new')")));

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

        gate.release.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(30));
        assertEquals(Map.of("rules", "new"), engine.run(new FactMap<>()), "once it's given back, the new rules run");
    }
}
