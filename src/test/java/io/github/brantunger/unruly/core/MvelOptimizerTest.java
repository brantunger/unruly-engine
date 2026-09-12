package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.dynamic.DynamicOptimizer;
import org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// Public, as are the fact classes: MVEL's reflective accessors need to reach their getters.
@DisplayName("MVEL optimizer configuration")
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
    @DisplayName("the engine selects MVEL's reflective optimizer when loaded")
    void reflectiveOptimizerIsDefault() {
        new StatelessRulesEngine<>(HashMap::new);

        assertInstanceOf(ReflectiveAccessorOptimizer.class, OptimizerFactory.getDefaultAccessorCompiler());
    }

    @Test
    @DisplayName("opting in to the JIT leaves MVEL's own setting untouched")
    void jitOptInLeavesMvelUntouched() {
        try {
            OptimizerFactory.setDefaultOptimizer(OptimizerFactory.DYNAMIC);

            AbstractRulesEngine.configureMvel("true");
            assertInstanceOf(DynamicOptimizer.class, OptimizerFactory.getDefaultAccessorCompiler());

            AbstractRulesEngine.configureMvel("false");
            assertInstanceOf(ReflectiveAccessorOptimizer.class, OptimizerFactory.getDefaultAccessorCompiler());

            OptimizerFactory.setDefaultOptimizer(OptimizerFactory.DYNAMIC);
            AbstractRulesEngine.configureMvel(null);
            assertInstanceOf(ReflectiveAccessorOptimizer.class, OptimizerFactory.getDefaultAccessorCompiler());
        } finally {
            OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        }
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
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(Rule.builder()
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
}
