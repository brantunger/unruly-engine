package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #861: valid expressions that ask for the class loader more times in all than #840's and #857's limits allowed, from
 * many places, load and run, and a loop with a long argument is stopped soon after it starts.
 */
@DisplayName("valid expressions that ask for the class loader many times, from many places, load and run")
class ValidManyCallsTest {

    private static final Imports IMPORTS = new Imports(Set.of(), Set.of(), ValidManyCallsTest.class.getClassLoader());

    /**
     * Runs a body on a daemon thread with a large stack, joined with a bound, so a run that never returns fails the
     * test, and the thread left doesn't keep the JVM alive. Each body here took at most about 8 seconds with JaCoCo.
     */
    private static void onLargeStack(String what, long seconds, Executable body) throws InterruptedException {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        // MVEL's analysis and the first run of calls nested hundreds deep take more stack than a test thread has.
        Thread thread = new Thread(null, () -> {
            try {
                body.execute();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "large-stack", 1L << 29);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(seconds));
            assertFalse(thread.isAlive(), () -> what + " didn't return");
        } finally {
            thread.interrupt();
        }
        if (failed.get() != null) {
            fail(what + " failed", failed.get());
        }
    }

    private static RulesEngine<Map<String, Object>> loaded(String condition, String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        Rule rule = Rule.builder().ruleName("r").priority(1).condition(condition).action(action).build();
        assertEquals(List.of(), engine.validate(List.of(rule)));
        engine.load(List.of(rule));
        return engine;
    }

    /** A chain of n names that aren't classes, wrapped in n levels of parentheses MVEL analyses it again for. */
    private static String wrappedChain(int levels) {
        return "(".repeat(levels) + "m" + ".a".repeat(levels) + ")".repeat(levels);
    }

    @ParameterizedTest(name = "{0} levels")
    @ValueSource(ints = {93, 100})
    @DisplayName("a long chain in many levels of brackets loads: MVEL asks about twice as many times as the levels and "
            + "the chain's parts multiplied, from a place of its own for each")
    void wrappedChainLoads(int levels) throws InterruptedException {
        // 17,577 calls for 93 levels against #840's limit of 17,460, and 20,300 for 100 against 18,020.
        onLargeStack("the analysis", 60, () -> new MvelAnalysis(wrappedChain(levels), IMPORTS).compile());
    }

    @Test
    @DisplayName("a long chain in 100 levels of brackets loads and runs, with the right result")
    void wrappedChainRuns() throws InterruptedException {
        Map<String, Object> m = new HashMap<>();
        m.put("a", m);
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", m);
        onLargeStack("the rule", 60, () -> assertEquals(Map.of("r", 1),
                loaded(wrappedChain(100) + ".size() == 1", "output.put('r', 1)").run(facts)));
    }

    @Test
    @DisplayName("calls through a class named with its package, nested 340 deep, run in each copy's first run")
    void nestedQualifiedCallsRun() throws InterruptedException {
        // 57,970 calls in a copy's first run, against #857's limit of 57,720 for the condition's 2,386 characters.
        String condition = "q.Q.f(".repeat(340) + "x" + ")".repeat(340) + " == 1";
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        onLargeStack("the rule", 60, () -> {
            RulesEngine<Map<String, Object>> engine = loaded(condition, "output.put('r', 1)");
            for (int run = 0; run < 3; run++) {
                assertEquals(Map.of("r", 1), engine.run(facts));
            }
        });
    }

    @Test
    @DisplayName("calls through a class named with its package, nested 200 deep in a foreach, run")
    void nestedCallsInForeachRun() throws InterruptedException {
        // MVEL builds the calls in the first round and again after 50: 40,201 calls in the run, against #857's limit
        // of 39,120 for the action's 1,456 characters.
        String action = "t = 0; foreach (i : l) { t = t + " + "q.Q.f(".repeat(200) + "i" + ")".repeat(200)
                + " }; output.put('r', t)";
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            values.add(i);
        }
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("l", values);
        onLargeStack("the rule", 60, () -> assertEquals(Map.of("r", 1_770), loaded("true", action).run(facts)));
    }

    @Test
    @DisplayName("a call glued to a word, with an argument of 4,000 characters, is rejected soon")
    void longArgumentLoopRejectedSoon() throws InterruptedException {
        // Each round analyses the whole argument again, about a millisecond a round here, so #840's limit of 90,400
        // rounds took over a minute and a half, and the limit for one place, twice, about 6,150 rounds, takes about 8
        // seconds. The bound is 10 times that. The thread ends on its own either way, after as many rounds, so it
        // doesn't go on into later tests; CallSitesTest counts the rounds.
        String text = "java.lang.Math.abs(" + String.join("+", Collections.nCopies(2_000, "1")) + ")x";
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        Rule rule = Rule.builder().ruleName("r").priority(1).condition("true").action(text).build();
        onLargeStack("load()", 80, () -> {
            RuleCompilationException loop = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule)));
            assertTrue(loop.getMessage().contains("MVEL's analysis went round in a loop"), loop::getMessage);
        });
    }
}
