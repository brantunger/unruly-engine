package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.dynamic.DynamicOptimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// Public, as are the fact classes: MVEL's reflective accessors need to reach their getters.
@DisplayName("MVEL optimizer and concurrent runs")
public class MvelOptimizerTest {

    public static class ClaimA {
        public int getValue() {
            return 1;
        }
    }

    public static class ClaimB {
        public int getValue() {
            return 2;
        }
    }

    @Test
    @DisplayName("loading and running the engine leaves MVEL's global optimizer setting alone")
    void mvelOptimizerUntouched() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));
        engine.run(new FactMap<>());

        // MVEL's own default: the JIT, as the build doesn't set mvel2.disable.jit.
        assertInstanceOf(DynamicOptimizer.class, OptimizerFactory.getDefaultAccessorCompiler());
    }

    /**
     * Regression test for the MVEL deopt race. With the JIT on, warming a rule with one fact class and then
     * running it concurrently with another made about 2% of calls fail with ClassCastException, so over
     * 4,800 calls a broken engine fails this test essentially every time.
     */
    @Test
    @DisplayName("polymorphic facts do not fail when run concurrently")
    void polymorphicFactsUnderConcurrency() throws InterruptedException {
        int threads = 8;
        AtomicInteger calls = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        for (int trial = 0; trial < 200; trial++) {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .build();
            engine.load(List.of(Rule.builder()
                    .ruleName("poly")
                    .priority(1)
                    .condition("claim.value > 0")
                    .action("output.put('v', claim.value)")
                    .build()));

            // Enough hits inside DynamicOptimizer's 100 ms window to make the JIT compile an accessor for ClaimA.
            FactStore<Object> warm = new FactMap<>();
            warm.setValue("claim", new ClaimA());
            for (int i = 0; i < 300; i++) {
                engine.run(warm);
            }

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                Thread worker = new Thread(() -> {
                    FactStore<Object> facts = new FactMap<>();
                    facts.setValue("claim", new ClaimB());
                    try {
                        start.await();
                        for (int i = 0; i < 3; i++) {
                            calls.incrementAndGet();
                            engine.run(facts);
                        }
                    } catch (RuleExecutionException | InterruptedException e) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    } finally {
                        done.countDown();
                    }
                });
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        }

        assertTrue(failures.isEmpty(), () -> failures.size() + " of " + calls + " concurrent runs failed, first: "
                + failures.get(0));
    }

    public record Applicant(int creditScore) {
    }

    /**
     * Regression test for #130. MVEL replaces a compiled expression's cached accessor, without synchronization, when
     * a fact that was a Map in one run is an object in the next (or the other way round). With one compiled
     * expression shared by every thread, a few tenths of a percent of these runs failed with ClassCastException,
     * whichever optimizer was selected, so over 9,600 runs a broken engine fails this test essentially every time.
     */
    @Test
    @DisplayName("a fact that is a Map in some runs and an object in others does not fail when run concurrently")
    void mapAndObjectFactsUnderConcurrency() throws InterruptedException {
        int threads = 8;
        int runs = 4;
        AtomicInteger calls = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int trial = 0; trial < 300; trial++) {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .build();
            engine.load(List.of(Rule.builder()
                    .ruleName("prime-rate")
                    .condition("applicant.creditScore >= 750")
                    .action("output.put('approved', true)")
                    .build()));

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                int worker = t;
                new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < runs; i++) {
                            FactStore<Object> facts = new FactMap<>();
                            facts.setValue("applicant", (worker + i) % 2 == 0
                                    ? new Applicant(800)
                                    : new HashMap<>(Map.of("creditScore", 800)));
                            calls.incrementAndGet();
                            engine.run(facts);
                        }
                    } catch (RuleExecutionException | InterruptedException e) {
                        failures.add(e);
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        }

        assertTrue(failures.isEmpty(), () -> failures.size() + " of " + calls + " concurrent runs failed, first: "
                + failures.get(0));
    }
}
