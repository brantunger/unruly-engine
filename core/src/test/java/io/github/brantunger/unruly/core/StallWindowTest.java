package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestLogs;
import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules an engine loads wait for a copy as long as its {@code stallWindow()} test seam says, so a test whose runs
 * wait for copies on purpose can set a window longer than the test (#604).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("the rules an engine loads take their stall window from the engine's test seam")
class StallWindowTest {

    /**
     * Ends one of the test's threads: joined, and if it's still running after a few seconds, interrupted and joined
     * again, so a test that failed while the thread was held still ends well within its deadline. An interrupt while
     * joining interrupts the thread too and is set again, rather than thrown, so the test's other threads are still
     * ended and its own failure is the one reported.
     */
    private static void end(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(TimeUnit.SECONDS.toMillis(5));
            }
        } catch (InterruptedException e) {
            thread.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a run that finds the only copy held makes an extra one after the window set before load(), not after"
            + " the five seconds a real one waits")
    void loadedRulesWaitTheWindowSet() throws InterruptedException {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(GatedRuns.STATEFUL_TOY).maxCopies(1).build()) {
            ((AbstractRulesEngine<Map<String, Object>>) engine).stallWindow(1);
            engine.load(List.of(Rule.builder().ruleName("gated").condition("gate.hold").action("put ok true")
                    .build()));
            GatedRuns.Gate held = new GatedRuns.Gate();
            GatedRuns.Gate open = new GatedRuns.Gate();
            open.release();
            AtomicReference<Map<String, Object>> output = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread holder = new Thread(() -> engine.run(new FactMap<>(new Fact<Object>("gate", held))), "holder");
            // On a thread of its own, so it isn't a run nested in one on JUnit's thread, which takes an extra copy
            // without waiting at all. Its deadline comes before a five-second window would end, so a run that waited
            // that window instead of the one set stops at its deadline rather than making the extra copy.
            Thread overflowing = new Thread(() -> {
                try {
                    output.set(engine.runWithResult(new FactMap<>(new Fact<Object>("gate", open)),
                            RunOptions.withTimeoutOf(Duration.ofSeconds(4))).output());
                } catch (RuntimeException | Error e) {
                    failure.set(e);
                }
            }, "overflowing");
            try {
                holder.start();
                assertTrue(held.awaitHolding(20, TimeUnit.SECONDS), "the run holding the only copy never started");

                // Joined inside, so its warning is captured.
                String logs = TestLogs.logsOf(() -> {
                    overflowing.start();
                    try {
                        overflowing.join(TimeUnit.SECONDS.toMillis(10));
                    } catch (InterruptedException e) {
                        throw new AssertionError("interrupted while the run that found the only copy held ran", e);
                    }
                });

                assertNull(failure.get(), () -> "the run that found the only copy held failed: " + failure.get());
                assertEquals(Map.of("ok", true), output.get(), "the run that found the only copy held never ended");
                assertTrue(logs.contains("All 1 compiled copies of the rules were in use for 1 ms without one being"
                        + " given back, so this run made an extra copy"), logs);
            } finally {
                held.release();
                end(overflowing);
                end(holder);
            }
        }
    }
}
