package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("concurrent reloads never leave one list's rules with another list's fact-name checks")
class ConcurrentReloadTest {

    private static final int ITERATIONS = 20_000;
    private static final long TIME_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** A language whose rules put {@code src=A} into the output, and whose fact-name check rejects {@code banned}. */
    private static final ExpressionLanguage STRICT = StubExpressionLanguage.named("strict")
            .action((action, session) -> {
                asMap(action.output()).put("src", "A");
                return ActionResult.done();
            })
            .checkFactName(name -> {
                if ("banned".equals(name)) {
                    throw new IllegalArgumentException("'banned' is not allowed");
                }
            });

    private static final List<Rule> LIST_A =
            List.of(Rule.builder().ruleName("a").language("strict").condition("c").action("a").build());
    private static final List<Rule> LIST_B =
            List.of(Rule.builder().ruleName("b").condition("true").action("output.put('src', 'B')").build());

    /** The output object, which every engine here builds with {@code HashMap::new}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object output) {
        return (Map<String, Object>) output;
    }

    // Probabilistic before the fix, which wrote the rules and the checks separately: the writes of two reloads could
    // interleave, pairing the rules of one list with the checks of the other until the next reload.
    @Test
    @DisplayName("after two load calls race, the loaded rules are checked with their own languages")
    void rulesAndChecksSwappedTogether() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int iterations = 0;
            int mismatches = 0;
            long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
            while (iterations < ITERATIONS && System.nanoTime() < deadline) {
                StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                        builder -> builder.language(STRICT).language(new MvelExpressionLanguage())
                                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME));
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> loadA = pool.submit(() -> {
                    barrier.await();
                    engine.load(LIST_A);
                    return null;
                });
                Future<?> loadB = pool.submit(() -> {
                    barrier.await();
                    engine.load(LIST_B);
                    return null;
                });
                loadA.get();
                loadB.get();

                boolean listALoaded = "A".equals(engine.run(new FactMap<>(new Fact<>("fine", 1))).get("src"));
                boolean bannedAccepted = acceptsBanned(engine);
                // List A must reject the name, and list B (MVEL) must accept it.
                if (listALoaded == bannedAccepted) {
                    mismatches++;
                }
                iterations++;
            }

            assertEquals(0, mismatches, "mismatched rules and checks after " + iterations + " races");
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean acceptsBanned(StatefulRulesEngine<Map<String, Object>> engine) {
        try {
            engine.run(new FactMap<>(new Fact<>("banned", 1)));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
