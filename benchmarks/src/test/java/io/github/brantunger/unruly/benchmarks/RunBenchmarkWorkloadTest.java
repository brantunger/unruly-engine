package io.github.brantunger.unruly.benchmarks;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the benchmark's own numbers rest on: the shape of the rule list it generates. These are counts, not timings,
 * so they belong in {@code check} where the measurements don't.
 */
@DisplayName("the benchmark's workload: one rule in ten matches, nine tenths of the way down the list")
class RunBenchmarkWorkloadTest {

    private static RunResult<Map<String, Object>> runOnce(int rules, String policy, String facts, String language) {
        RunBenchmark benchmark = new RunBenchmark();
        benchmark.rules = rules;
        benchmark.policy = policy;
        benchmark.facts = facts;
        benchmark.listener = "none";
        benchmark.language = language;
        benchmark.loadTheRules();
        try {
            return benchmark.engine.runWithResult(benchmark.newFacts());
        } finally {
            benchmark.closeTheEngine();
        }
    }

    private static long count(RunResult<?> result, RuleEvaluation.Outcome outcome) {
        return result.evaluations().stream().filter(evaluation -> evaluation.outcome() == outcome).count();
    }

    private static List<String> firedNames(RunResult<?> result) {
        return result.firedRules().stream().map(Rule::getRuleName).toList();
    }

    @ParameterizedTest(name = "{0}, {1} rules")
    @CsvSource({"mvel, 10", "mvel, 100", "mvel, 1000", "noop, 10", "noop, 100", "noop, 1000"})
    @DisplayName("a first-match run evaluates nine tenths of the list and fires the rule it stops at")
    void firstMatchStopsNineTenthsDown(String language, int rules) {
        RunResult<Map<String, Object>> result = runOnce(rules, "firstMatch", "record", language);

        int matchFrom = rules * 9 / 10;
        assertEquals(rules, result.evaluations().size());
        assertEquals(matchFrom, count(result, RuleEvaluation.Outcome.NOT_MATCHED));
        assertEquals(1, count(result, RuleEvaluation.Outcome.MATCHED));
        assertEquals(rules - matchFrom - 1, count(result, RuleEvaluation.Outcome.NOT_EVALUATED));
        assertEquals(List.of("rule" + matchFrom), firedNames(result));
    }

    @ParameterizedTest(name = "{0}, {1} rules")
    @CsvSource({"mvel, 10", "mvel, 100", "mvel, 1000", "noop, 10", "noop, 100", "noop, 1000"})
    @DisplayName("an all-matches run fires one rule in ten: the last tenth of the list, at every size")
    void allMatchesFiresOneRuleInTen(String language, int rules) {
        RunResult<Map<String, Object>> result = runOnce(rules, "allMatches", "record", language);

        int matchFrom = rules * 9 / 10;
        assertEquals(rules, result.evaluations().size());
        assertEquals(0, count(result, RuleEvaluation.Outcome.NOT_EVALUATED));
        assertEquals(rules / 10, result.firedRules().size());
        assertEquals(IntStream.range(matchFrom, rules).mapToObj(i -> "rule" + i).toList(), firedNames(result));
    }

    @Test
    @DisplayName("an MVEL condition reads the applicant the same way out of a map and out of a record")
    void mvelReadsBothFactShapes() {
        RunResult<Map<String, Object>> fromMap = runOnce(100, "allMatches", "map", "mvel");

        assertEquals(firedNames(runOnce(100, "allMatches", "record", "mvel")), firedNames(fromMap));
        assertEquals(10, fromMap.firedRules().size());
    }

    @Test
    @DisplayName("an MVEL condition reads the applicant's own employment, not the fact of the same name")
    void mvelReadsTheApplicantsEmployment() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new MvelExpressionLanguage()).defaultLanguage("mvel").build();
        try {
            engine.load(RunBenchmark.ruleList(100, "mvel"));
            // An applicant who isn't employed, while the top-level fact of that name says otherwise: a condition
            // reading the bare fact fires the whole matching tenth, and one reading the record's component fires
            // nothing.
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("applicant", new RunBenchmark.Applicant(700, "Alex", false));
            facts.setValue("employed", true);

            assertEquals(List.of(), firedNames(engine.runWithResult(facts)));
        } finally {
            engine.close();
        }
    }
}
