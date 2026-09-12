package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("rules compile in isolation")
class RuleCompilationIsolationTest {

    private static Rule rule(String name, int priority, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action(action).build();
    }

    private static FactStore<Object> facts(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    @Nested
    @DisplayName("variables")
    class Variables {

        @Test
        @DisplayName("a typed local in one action doesn't change how another rule computes")
        void typedLocalDoesNotLeakIntoAction() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(
                    rule("declares", 2, "true", "String total = 'n/a'; output.put('a', total)"),
                    rule("reads", 1, "total > 5", "output.put('b', total + 1)")));

            Map<String, Object> result = engine.run(facts("total", 41));

            assertEquals(42, result.get("b"));
        }

        @Test
        @DisplayName("a typed local in one action doesn't stop another rule's condition matching")
        void typedLocalDoesNotLeakIntoCondition() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(
                    rule("declares", 2, "true", "String total = 'n/a'; output.put('a', total)"),
                    rule("matches", 1, "total + 1 == 42", "output.put('b', true)")));

            Map<String, Object> result = engine.run(facts("total", 41));

            assertEquals(true, result.get("b"));
        }

        @Test
        @DisplayName("two rules may declare the same local with different types")
        void conflictingTypesInOneList() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(
                    rule("string", 2, "true", "String x = 'a'; output.put('a', x)"),
                    rule("int", 1, "true", "int x = 5; output.put('b', x)")));

            Map<String, Object> result = engine.run(new FactMap<>());

            assertEquals(Map.of("a", "a", "b", 5), result);
        }

        @Test
        @DisplayName("a local's type from an earlier rule list doesn't break a later reload")
        void conflictingTypesAcrossReloads() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(rule("string", 1, "true", "String x = 'a'; output.put('a', x)")));

            engine.setRuleList(List.of(rule("int", 1, "true", "int x = 5; output.put('b', x)")));

            assertEquals(Map.of("b", 5), engine.run(new FactMap<>()));
        }
    }

    @Nested
    @DisplayName("inline imports")
    class InlineImports {

        private static final String IMPORTS = "import java.time.LocalDate; output.put('a', LocalDate.of(2020, 1, 1))";
        private static final String USES = "output.put('b', LocalDate.of(2020, 1, 1))";

        @Test
        @DisplayName("an import in one rule isn't visible to another rule in the same list")
        void importDoesNotLeakWithinList() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(rule("imports", 2, "true", IMPORTS), rule("uses", 1, "true", USES)));

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
            assertTrue(ex.getMessage().contains("rule 'uses'"));
        }

        @Test
        @DisplayName("an import in a replaced rule list isn't visible after a reload")
        void importDoesNotLeakAcrossReloads() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(rule("imports", 1, "true", IMPORTS)));
            engine.setRuleList(List.of(rule("uses", 1, "true", USES)));

            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        }

        @Test
        @DisplayName("an import in a rule list that failed to compile isn't visible afterwards")
        void importDoesNotLeakFromFailedLoad() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            assertThrows(RuleCompilationException.class, () -> engine.setRuleList(List.of(
                    rule("imports", 2, "true", IMPORTS),
                    rule("broken", 1, "x == == 1", "output.put('c', 1)"))));
            engine.setRuleList(List.of(rule("uses", 1, "true", USES)));

            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("reloading rules while other threads run the engine doesn't make runs fail")
        void reloadDuringRun() throws InterruptedException {
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
            engine.setRuleList(List.of(rule("a", 1, "x > 0", "output.put('a', x)")));
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger runs = new AtomicInteger();
            ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();
            ExecutorService pool = Executors.newFixedThreadPool(5);

            for (int t = 0; t < 4; t++) {
                pool.submit(() -> {
                    FactStore<Object> facts = facts("x", 1);
                    while (!stop.get()) {
                        try {
                            engine.run(facts);
                            runs.incrementAndGet();
                        } catch (Exception e) {
                            failures.add(e);
                        }
                    }
                });
            }
            // Each reload brings in new variable names, so the new rules differ from the ones being run.
            pool.submit(() -> {
                for (int i = 0; !stop.get(); i++) {
                    String v = "v" + i;
                    engine.setRuleList(List.of(rule("a", 1, "x > 0 && String.valueOf(x).length() > 0",
                            v + "a = x; " + v + "b = String.valueOf(" + v + "a); "
                                    + "output.put('a', " + v + "b.concat(String.valueOf(" + v + "a)))")));
                }
            });
            TimeUnit.SECONDS.sleep(1);
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertTrue(runs.get() > 0);
            assertTrue(failures.isEmpty(), () -> failures.size() + " runs failed, first: " + failures.peek());
        }

        @Test
        @DisplayName("setRuleList() may be called from several threads at once")
        void concurrentReloads() throws InterruptedException {
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
            ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();
            ExecutorService pool = Executors.newFixedThreadPool(4);

            for (int t = 0; t < 4; t++) {
                int thread = t;
                pool.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        String v = "t" + thread + "v" + i;
                        try {
                            engine.setRuleList(List.of(rule("a", 1, "true",
                                    "int " + v + " = 1; output.put('a', " + v + ")")));
                        } catch (Exception e) {
                            failures.add(e);
                        }
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            assertTrue(failures.isEmpty(), () -> failures.size() + " reloads failed, first: " + failures.peek());
            assertEquals(Map.of("a", 1), engine.run(new FactMap<>()));
        }
    }
}
