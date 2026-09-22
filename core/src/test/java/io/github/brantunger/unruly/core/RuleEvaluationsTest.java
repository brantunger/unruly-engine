package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleEvaluation.Outcome;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** #289: a run result records what each rule's condition evaluated to. */
@DisplayName("a run reports each rule's outcome: matched, not matched or not evaluated")
class RuleEvaluationsTest {

    private static final Rule HIGH = Rule.builder().ruleName("high").priority(3).condition("false")
            .action("put high 1").build();
    private static final Rule MIDDLE = Rule.builder().ruleName("middle").priority(2).condition("true")
            .action("put middle 1").build();
    private static final Rule LOW = Rule.builder().ruleName("low").priority(1).condition("true")
            .action("put low 1").build();
    private static final Rule BROKEN = Rule.builder().ruleName("broken").priority(0).condition("x.missing > 1")
            .action("put broken 1").build();

    private static RulesEngine<Map<String, Object>> loaded(RulesEngineBuilder<Map<String, Object>> builder,
                                                           Rule... rules) {
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(rules));
        return engine;
    }

    private static RulesEngineBuilder<Map<String, Object>> firstMatch() {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage());
    }

    private static RulesEngineBuilder<Map<String, Object>> allMatches() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ToyExpressionLanguage());
    }

    private static RulesEngineBuilder<Map<String, Object>> uniqueMatch() {
        return RulesEngineBuilder.<Map<String, Object>>uniqueMatch(HashMap::new)
                .language(new ToyExpressionLanguage());
    }

    private static List<String> names(List<RuleEvaluation> evaluations) {
        return evaluations.stream().map(RuleEvaluation::toString).toList();
    }

    @Test
    @DisplayName("a first-match engine reports the rules after the match as not evaluated")
    void firstMatchStopsRecordingAtTheMatch() {
        RunResult<Map<String, Object>> result = loaded(firstMatch(), LOW, HIGH, MIDDLE, BROKEN)
                .runWithResult(new FactMap<>());

        assertEquals(List.of(
                RuleEvaluation.of(HIGH, Outcome.NOT_MATCHED),
                RuleEvaluation.of(MIDDLE, Outcome.MATCHED),
                RuleEvaluation.of(LOW, Outcome.NOT_EVALUATED),
                RuleEvaluation.of(BROKEN, Outcome.NOT_EVALUATED)), result.evaluations());
        assertEquals(List.of(MIDDLE), result.firedRules());
    }

    @Test
    @DisplayName("an all-matches engine evaluates every rule, so none is reported as not evaluated")
    void allMatchesEvaluatesEveryRule() {
        RunResult<Map<String, Object>> result = loaded(allMatches(), HIGH, MIDDLE, LOW).runWithResult(new FactMap<>());

        assertEquals(List.of("high=NOT_MATCHED", "middle=MATCHED", "low=MATCHED"), names(result.evaluations()));
        assertEquals(List.of(MIDDLE, LOW), result.firedRules());
    }

    @Test
    @DisplayName("a unique-match engine's result reports every rule too")
    void uniqueMatchEvaluatesEveryRule() {
        RunResult<Map<String, Object>> result = loaded(uniqueMatch(), HIGH, MIDDLE).runWithResult(new FactMap<>());

        assertEquals(List.of("high=NOT_MATCHED", "middle=MATCHED"), names(result.evaluations()));
    }

    @Test
    @DisplayName("when no rule matches, every rule is reported as not matched, on every policy")
    void noMatchReportsEveryRuleNotMatched() {
        Rule never = Rule.builder().ruleName("never").priority(1).condition("false").action("put n 1")
                .build();
        for (Supplier<RulesEngineBuilder<Map<String, Object>>> builder : List.<Supplier<RulesEngineBuilder<Map<String, Object>>>>of(
                RuleEvaluationsTest::firstMatch, RuleEvaluationsTest::allMatches, RuleEvaluationsTest::uniqueMatch)) {
            RunResult<Map<String, Object>> result = loaded(builder.get(), HIGH, never).runWithResult(new FactMap<>());

            assertNull(result.output());
            assertEquals(List.of("high=NOT_MATCHED", "never=NOT_MATCHED"), names(result.evaluations()));
        }
    }

    @Test
    @DisplayName("an empty rule list reports no evaluations")
    void emptyRuleListReportsNothing() {
        assertEquals(List.of(), loaded(firstMatch()).runWithResult(new FactMap<>()).evaluations());
    }

    @Test
    @DisplayName("the evaluations cover every loaded rule, in evaluation order, with the same Rule instances")
    void oneEvaluationPerLoadedRule() {
        RulesEngine<Map<String, Object>> engine = loaded(firstMatch(), LOW, MIDDLE, HIGH);

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>());

        List<Rule> loaded = engine.rules().rules();
        assertEquals(loaded.size(), result.evaluations().size());
        for (int i = 0; i < loaded.size(); i++) {
            assertSame(loaded.get(i), result.evaluations().get(i).rule());
        }
    }

    @Test
    @DisplayName("the outcomes match what listeners saw in afterEvaluate, and afterRun gets the same result")
    void outcomesAgreeWithListeners() {
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicReference<RunResult<?>> reported = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = loaded(firstMatch().listener(new RuleListener() {
            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                seen.add(rule.getRuleName() + "=" + (matchResult ? Outcome.MATCHED : Outcome.NOT_MATCHED));
            }

            @Override
            public void afterRun(RunContext run, RunResult<?> result) {
                reported.set(result);
            }
        }), HIGH, MIDDLE, LOW);

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>());

        assertEquals(seen, names(result.evaluations()).subList(0, seen.size()));
        assertEquals("low=NOT_EVALUATED", names(result.evaluations()).get(seen.size()));
        assertSame(result, reported.get());
    }

    @Test
    @DisplayName("a run that fails throws instead of returning a result, although rules were already evaluated")
    void failedRunHasNoResult() {
        List<String> seen = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(allMatches().listener(new RuleListener() {
            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                seen.add(rule.getRuleName());
            }
        }), MIDDLE, BROKEN);
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        RuleExecutionException e = assertThrows(RuleExecutionException.class, () -> engine.runWithResult(facts));

        assertEquals("broken", e.getRuleName());
        assertEquals(List.of("middle"), seen);
    }

    @Test
    @DisplayName("the evaluations can't be changed")
    void evaluationsAreUnmodifiable() {
        RunResult<Map<String, Object>> result = loaded(allMatches(), HIGH).runWithResult(new FactMap<>());

        assertThrows(UnsupportedOperationException.class, () -> result.evaluations().clear());
    }

    @Test
    @DisplayName("a result built without evaluations has none, and one built with them copies the list")
    void resultsBuiltByHand() {
        List<RuleEvaluation> evaluations = new java.util.ArrayList<>(List.of(RuleEvaluation.of(HIGH, Outcome.MATCHED)));
        List<RuleEvaluation> withNull = new java.util.ArrayList<>();
        withNull.add(null);

        RunResult<String> without = RunResult.of("out", List.of(HIGH), "checksum");
        RunResult<String> with = RunResult.of("out", List.of(HIGH), evaluations, "checksum");
        evaluations.clear();

        assertEquals(List.of(), without.evaluations());
        assertEquals(List.of(RuleEvaluation.of(HIGH, Outcome.MATCHED)), with.evaluations());
        assertThrows(NullPointerException.class, () -> RunResult.of("out", List.of(), null, "checksum"));
        assertThrows(NullPointerException.class, () -> RunResult.of("out", List.of(), withNull, "checksum"));
    }

    @Test
    @DisplayName("an evaluation is a value: equal to another with the same rule and outcome, and named in toString")
    void evaluationIsAValue() {
        RuleEvaluation matched = RuleEvaluation.of(HIGH, Outcome.MATCHED);

        assertEquals(matched, RuleEvaluation.of(HIGH, Outcome.MATCHED));
        assertEquals(matched.hashCode(), RuleEvaluation.of(HIGH, Outcome.MATCHED).hashCode());
        // The outcome is part of the hash too, not only of equals, so two outcomes of one rule don't collide in a
        // HashSet or a HashMap a caller puts them in. Asserted as an inequality rather than as a number: a literal
        // would pin Objects.hash's formula and the enum's identity hash, which differs from one JVM to the next.
        assertNotEquals(matched.hashCode(), RuleEvaluation.of(HIGH, Outcome.NOT_MATCHED).hashCode());
        assertNotEquals(matched, RuleEvaluation.of(HIGH, Outcome.NOT_MATCHED));
        assertNotEquals(matched, RuleEvaluation.of(LOW, Outcome.MATCHED));
        assertNotEquals(matched, "high=MATCHED");
        assertSame(HIGH, matched.rule());
        assertEquals(Outcome.MATCHED, matched.outcome());
        assertEquals("high=MATCHED", matched.toString());
        assertThrows(NullPointerException.class, () -> RuleEvaluation.of(null, Outcome.MATCHED));
        assertThrows(NullPointerException.class, () -> RuleEvaluation.of(HIGH, null));
    }

    @Test
    @DisplayName("a result lists the evaluations in its toString")
    void readableToString() {
        RunResult<Map<String, Object>> result = loaded(firstMatch(), MIDDLE, LOW).runWithResult(new FactMap<>());

        assertTrue(result.toString().contains("evaluations=[middle=MATCHED, low=NOT_EVALUATED]"), result.toString());
    }
}
