package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
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
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
            engine.load(List.of(
                    rule("declares", 2, "true", "String total = 'n/a'; output.put('a', total)"),
                    rule("reads", 1, "total > 5", "output.put('b', total + 1)")));

            Map<String, Object> result = engine.run(facts("total", 41));

            assertEquals(42, result.get("b"));
        }

        @Test
        @DisplayName("a typed local in one action doesn't stop another rule's condition matching")
        void typedLocalDoesNotLeakIntoCondition() {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
            engine.load(List.of(
                    rule("declares", 2, "true", "String total = 'n/a'; output.put('a', total)"),
                    rule("matches", 1, "total + 1 == 42", "output.put('b', true)")));

            Map<String, Object> result = engine.run(facts("total", 41));

            assertEquals(true, result.get("b"));
        }

        @Test
        @DisplayName("two rules may declare the same local with different types")
        void conflictingTypesInOneList() {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
            engine.load(List.of(
                    rule("string", 2, "true", "String x = 'a'; output.put('a', x)"),
                    rule("int", 1, "true", "int x = 5; output.put('b', x)")));

            Map<String, Object> result = engine.run(new FactMap<>());

            assertEquals(Map.of("a", "a", "b", 5), result);
        }

        @Test
        @DisplayName("a local's type from an earlier rule list doesn't break a later reload")
        void conflictingTypesAcrossReloads() {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
            engine.load(List.of(rule("string", 1, "true", "String x = 'a'; output.put('a', x)")));

            engine.load(List.of(rule("int", 1, "true", "int x = 5; output.put('b', x)")));

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
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
            engine.load(List.of(rule("imports", 2, "true", IMPORTS), rule("uses", 1, "true", USES)));

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
            assertTrue(ex.getMessage().contains("rule 'uses'"));
        }

        @Test
        @DisplayName("an import in a replaced rule list isn't visible after a reload")
        void importDoesNotLeakAcrossReloads() {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
            engine.load(List.of(rule("imports", 1, "true", IMPORTS)));
            engine.load(List.of(rule("uses", 1, "true", USES)));

            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        }

        @Test
        @DisplayName("an import in a rule list that failed to compile isn't visible afterwards")
        void importDoesNotLeakFromFailedLoad() {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
            assertThrows(RuleCompilationException.class, () -> engine.load(List.of(
                    rule("imports", 2, "true", IMPORTS),
                    rule("broken", 1, "x == == 1", "output.put('c', 1)"))));
            engine.load(List.of(rule("uses", 1, "true", USES)));

            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("reloading rules while other threads run the engine doesn't make runs fail")
        void reloadDuringRun() throws InterruptedException {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
            engine.load(List.of(rule("a", 1, "x > 0", "output.put('a', x)")));
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger runs = new AtomicInteger();
            AtomicInteger reloads = new AtomicInteger();
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
                    try {
                        engine.load(List.of(rule("a", 1, "x > 0 && String.valueOf(x).length() > 0",
                                v + "a = x; " + v + "b = String.valueOf(" + v + "a); "
                                        + "output.put('a', " + v + "b.concat(String.valueOf(" + v + "a)))")));
                        reloads.incrementAndGet();
                    } catch (RuntimeException e) {
                        // Collected rather than lost in the unchecked Future, so a failing reload fails the test.
                        failures.add(e);
                    }
                }
            });
            TimeUnit.SECONDS.sleep(1);
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertTrue(runs.get() > 0);
            assertTrue(reloads.get() > 0, "no reload succeeded");
            assertTrue(failures.isEmpty(), () -> failures.size() + " runs or reloads failed, first: " + failures.peek());
        }

        @Test
        @DisplayName("setRuleList() may be called from several threads at once")
        void concurrentReloads() throws InterruptedException {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
            ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();
            ExecutorService pool = Executors.newFixedThreadPool(4);

            for (int t = 0; t < 4; t++) {
                int thread = t;
                pool.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        String v = "t" + thread + "v" + i;
                        try {
                            engine.load(List.of(rule("a", 1, "true",
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
