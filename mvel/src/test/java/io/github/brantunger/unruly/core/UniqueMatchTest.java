package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/** #292: a unique-match engine fires the one match and fails when there are more. */
@DisplayName("a unique-match engine fires the one rule that matches, and fails the run when more than one does")
class UniqueMatchTest {

    private static Rule rule(String name, int priority, String condition) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition)
                .action("output.put('fired', '" + name + "')").build();
    }

    private static final Rule PRIME = rule("prime-rate", 10, "score >= 750");
    private static final Rule STANDARD = rule("standard-rate", 5, "score >= 600");
    private static final Rule DECLINE = rule("decline", 1, "score < 600");

    private static FactStore<Object> score(int score) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("score", score);
        return facts;
    }

    /** Records every callback in order, so a test can check which ones a run reaches. */
    private static final class Recording implements RuleListener {
        final List<String> callbacks = new CopyOnWriteArrayList<>();
        final AtomicReference<RuntimeException> runError = new AtomicReference<>();

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            callbacks.add("beforeEvaluate " + rule.getRuleName());
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            callbacks.add("afterEvaluate " + rule.getRuleName() + "=" + matchResult);
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            callbacks.add("beforeExecute " + rule.getRuleName());
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            callbacks.add("onError " + rule.getRuleName());
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            callbacks.add("afterRun");
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            callbacks.add("onRunError");
            runError.set(error);
        }
    }

    private static RulesEngine<Map<String, Object>> engine(AtomicInteger outputs, RuleListener listener,
                                                           Rule... rules) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.uniqueMatch(() -> {
            outputs.incrementAndGet();
            return new HashMap<>();
        });
        if (listener != null) {
            builder.listener(listener);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(rules));
        return engine;
    }

    private static RulesEngine<Map<String, Object>> engine(Rule... rules) {
        return engine(new AtomicInteger(), null, rules);
    }

    @Test
    @DisplayName("exactly one match fires, and the result reports it")
    void oneMatchFires() {
        RunResult<Map<String, Object>> result = engine(PRIME, STANDARD, DECLINE).runWithResult(score(500));

        assertEquals(Map.of("fired", "decline"), result.output());
        assertEquals(List.of(DECLINE), result.firedRules());
    }

    @Test
    @DisplayName("no match returns null and fires nothing, like the other engines")
    void noMatchReturnsNull() {
        AtomicInteger outputs = new AtomicInteger();
        RulesEngine<Map<String, Object>> engine = engine(outputs, null, rule("never", 1, "false"));

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>());

        assertNull(result.output());
        assertEquals(List.of(), result.firedRules());
        assertEquals(0, outputs.get());
    }

    @Test
    @DisplayName("two matches fail the run, naming both in priority order, fire nothing and never create the output")
    void twoMatchesFail() {
        AtomicInteger outputs = new AtomicInteger();
        Recording listener = new Recording();
        RulesEngine<Map<String, Object>> engine = engine(outputs, listener, DECLINE, STANDARD, PRIME);

        RuleExecutionException e = assertThrows(RuleExecutionException.class, () -> engine.run(score(800)));

        assertEquals("2 rules matched, but a unique-match engine allows one: 'prime-rate', 'standard-rate'",
                e.getMessage());
        assertNull(e.getRuleName());
        assertNull(e.getExpressionKind());
        assertNull(e.getCause());
        assertEquals(0, outputs.get(), "the output factory was called");
        assertEquals(List.of(
                "beforeEvaluate prime-rate", "afterEvaluate prime-rate=true",
                "beforeEvaluate standard-rate", "afterEvaluate standard-rate=true",
                "beforeEvaluate decline", "afterEvaluate decline=false",
                "onRunError"), listener.callbacks);
        assertSame(e, listener.runError.get());
    }

    @Test
    @DisplayName("three matches name all three")
    void threeMatchesNameEach() {
        RulesEngine<Map<String, Object>> engine = engine(rule("a", 3, "true"), rule("b", 2, "true"),
                rule("c", 1, "true"));

        RuleExecutionException e = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("3 rules matched, but a unique-match engine allows one: 'a', 'b', 'c'", e.getMessage());
    }

    @Test
    @DisplayName("the failure is logged at ERROR, like every failure the engine throws")
    void failureIsLogged() {
        RulesEngine<Map<String, Object>> engine = engine(rule("a", 2, "true"), rule("b", 1, "true"));

        String log = logsOf(() -> assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>())));

        assertTrue(log.contains("ERROR") && log.contains("2 rules matched, but a unique-match engine allows one"),
                log);
    }

    @Test
    @DisplayName("a rule name in the failure is escaped, so it can't start a log line of its own")
    void namesAreEscaped() {
        RulesEngine<Map<String, Object>> engine = engine(rule("line\nbreak", 2, "true"), rule("b", 1, "true"));

        RuleExecutionException e = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("2 rules matched, but a unique-match engine allows one: 'line\\nbreak', 'b'", e.getMessage());
    }

    @Test
    @DisplayName("the list of matched names is cut at 1,000 characters, like text copied from an exception")
    void longListOfNamesIsCut() {
        Rule[] rules = new Rule[6];
        for (int i = 0; i < rules.length; i++) {
            rules[i] = rule("rule-" + i + "-" + "x".repeat(300), rules.length - i, "true");
        }
        RulesEngine<Map<String, Object>> engine = engine(rules);

        RuleExecutionException e = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        String prefix = "6 rules matched, but a unique-match engine allows one: ";
        assertTrue(e.getMessage().startsWith(prefix), e.getMessage());
        String list = e.getMessage().substring(prefix.length());
        // Six names of 200 characters plus a "... (100 more characters)" note each, quoted and separated, is more
        // than 1,000 characters, so the list ends with how much was left out.
        assertTrue(list.matches("(?s).{1000}\\.\\.\\. \\(\\d+ more characters\\)"), list.length() + ": " + list);
        assertTrue(list.startsWith("'rule-0-" + "x".repeat(193) + "... (107 more characters)', 'rule-1-"), list);
    }

    @Test
    @DisplayName("every condition is evaluated before anything fires, so a broken lower rule fails the run")
    void evaluatesEveryConditionFirst() {
        AtomicInteger outputs = new AtomicInteger();
        RulesEngine<Map<String, Object>> engine = engine(outputs, null, PRIME, rule("broken", 1, "x.missing > 1"));

        RuleExecutionException e = assertThrows(RuleExecutionException.class, () -> engine.run(score(800)));

        assertEquals("broken", e.getRuleName());
        assertEquals(0, outputs.get(), "the output factory was called before every condition was evaluated");
    }

    @Test
    @DisplayName("a successful run reaches afterRun with the one fired rule, and no onError")
    void successfulRunCallbacks() {
        Recording listener = new Recording();
        RulesEngine<Map<String, Object>> engine = engine(new AtomicInteger(), listener, PRIME, DECLINE);

        engine.run(score(800));

        assertEquals(List.of(
                "beforeEvaluate prime-rate", "afterEvaluate prime-rate=true",
                "beforeEvaluate decline", "afterEvaluate decline=false",
                "beforeExecute prime-rate", "afterRun"), listener.callbacks);
    }

    @Test
    @DisplayName("the run context names the policy uniqueMatch")
    void matchPolicyName() {
        AtomicReference<String> policy = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(new AtomicInteger(), new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                policy.set(run.matchPolicy());
            }
        }, PRIME);

        engine.run(score(800));

        assertEquals("uniqueMatch", policy.get());
    }

    @Test
    @DisplayName("the builder rejects a null output factory")
    void nullOutputFactory() {
        assertThrows(NullPointerException.class, () -> RulesEngineBuilder.uniqueMatch(null));
    }
}
