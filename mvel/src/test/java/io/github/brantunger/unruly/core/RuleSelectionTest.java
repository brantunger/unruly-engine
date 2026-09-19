package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleEvaluation.Outcome;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** #296: a run skips rules that are disabled, outside their validity window, or without any of the run's tags. */
@DisplayName("a run skips disabled rules, rules outside their window, and rules without the run's tags")
class RuleSelectionTest {

    private static final Instant NOW = Instant.parse("2027-06-01T00:00:00Z");
    private static final Clock AT_NOW = Clock.fixed(NOW, ZoneOffset.UTC);

    /** A condition that fails the run if it's ever evaluated: no fact named {@code x} is given. */
    private static final String FAILS_IF_EVALUATED = "x.missing > 1";

    private static Rule rule(String name, String condition) {
        return Rule.builder().ruleName(name).condition(condition).action("output.put('" + name + "', 1)").build();
    }

    private static RulesEngine<Map<String, Object>> loaded(RulesEngineBuilder<Map<String, Object>> builder,
                                                           Rule... rules) {
        RulesEngine<Map<String, Object>> engine = builder.clock(AT_NOW).build();
        engine.load(List.of(rules));
        return engine;
    }

    private static RulesEngineBuilder<Map<String, Object>> allMatches() {
        return RulesEngineBuilder.allMatches(HashMap::new);
    }

    private static List<String> outcomes(RunResult<?> result) {
        return result.evaluations().stream().map(RuleEvaluation::toString).toList();
    }

    @Test
    @DisplayName("a disabled rule is skipped: its condition isn't evaluated, and no listener hears about it")
    void disabledRuleSkipped() {
        List<String> heard = new CopyOnWriteArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, @Nullable Object> facts) {
                heard.add("evaluate " + rule.getRuleName());
            }

            @Override
            public void beforeExecute(Rule rule, Object output) {
                heard.add("execute " + rule.getRuleName());
            }
        };
        Rule off = rule("off", FAILS_IF_EVALUATED).toBuilder().enabled(false).build();
        Rule on = rule("on", "true");

        RunResult<Map<String, Object>> result = loaded(allMatches().listener(listener), off, on)
                .runWithResult(new FactMap<>());

        assertEquals(List.of(RuleEvaluation.of(off, Outcome.SKIPPED), RuleEvaluation.of(on, Outcome.MATCHED)),
                result.evaluations());
        assertEquals(List.of(on), result.firedRules());
        assertEquals(Map.of("on", 1), result.output());
        assertEquals(List.of("evaluate on", "execute on"), heard);
    }

    @Test
    @DisplayName("a rule is used from validFrom, inclusive, until validTo, exclusive, by the engine's clock")
    void validityWindow() {
        Rule startsNow = rule("starts-now", "true").toBuilder().validFrom(NOW).build();
        Rule endsNow = rule("ends-now", FAILS_IF_EVALUATED).toBuilder().validTo(NOW).build();
        Rule startsLater = rule("starts-later", FAILS_IF_EVALUATED).toBuilder()
                .validFrom(NOW.plusNanos(1)).build();
        Rule endsLater = rule("ends-later", "true").toBuilder().validTo(NOW.plusNanos(1)).build();
        Rule within = rule("within", "true").toBuilder().validFrom(NOW.minusSeconds(1))
                .validTo(NOW.plusSeconds(1)).build();

        RunResult<Map<String, Object>> result = loaded(allMatches(), startsNow, endsNow, startsLater, endsLater,
                within).runWithResult(new FactMap<>());

        assertEquals(List.of("starts-now=MATCHED", "ends-now=SKIPPED", "starts-later=SKIPPED", "ends-later=MATCHED",
                "within=MATCHED"), outcomes(result));
    }

    @Test
    @DisplayName("without a clock of its own, the engine judges windows by the system clock")
    void systemClockByDefault() {
        Rule past = rule("past", FAILS_IF_EVALUATED).toBuilder().validTo(Instant.parse("2000-01-01T00:00:00Z"))
                .build();
        Rule current = rule("current", "true").toBuilder().validFrom(Instant.parse("2000-01-01T00:00:00Z")).build();
        RulesEngine<Map<String, Object>> engine = allMatches().build();
        engine.load(List.of(past, current));

        assertEquals(List.of("past=SKIPPED", "current=MATCHED"), outcomes(engine.runWithResult(new FactMap<>())));
    }

    @Test
    @DisplayName("a run reads the clock once when it starts, so every rule's window is judged at the same time")
    void clockReadOncePerRun() {
        AtomicInteger reads = new AtomicInteger();
        // Each read is a second later than the one before, so a second read in a run would move past validTo.
        Clock ticking = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return NOW.plusSeconds(reads.getAndIncrement());
            }
        };
        Rule first = rule("first", "true").toBuilder().validTo(NOW.plusSeconds(1)).build();
        Rule second = rule("second", "true").toBuilder().validTo(NOW.plusSeconds(1)).build();
        RulesEngine<Map<String, Object>> engine = allMatches().clock(ticking).build();
        engine.load(List.of(first, second));

        assertEquals(List.of("first=MATCHED", "second=MATCHED"), outcomes(engine.runWithResult(new FactMap<>())));
        assertEquals(1, reads.get(), "one read for the run");
        assertEquals(List.of("first=SKIPPED", "second=SKIPPED"), outcomes(engine.runWithResult(new FactMap<>())),
                "the second run starts a second later, at validTo");
        assertEquals(2, reads.get());
    }

    @Test
    @DisplayName("a run given tags uses only the rules carrying at least one of them; untagged rules are skipped")
    void tagsChooseRules() {
        Rule eu = rule("eu", "true").toBuilder().tags(Set.of("eu")).build();
        Rule uk = rule("uk", FAILS_IF_EVALUATED).toBuilder().tags(Set.of("uk")).build();
        Rule both = rule("both", "true").toBuilder().tags(Set.of("eu", "uk")).build();
        Rule untagged = rule("untagged", FAILS_IF_EVALUATED);
        Rule retail = rule("retail", "true").toBuilder().tags(Set.of("retail")).build();
        RulesEngine<Map<String, Object>> engine = loaded(allMatches(), eu, uk, both, untagged, retail);

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>(),
                RunOptions.defaults().withTags(Set.of("eu", "retail")));

        assertEquals(List.of("eu=MATCHED", "uk=SKIPPED", "both=MATCHED", "untagged=SKIPPED", "retail=MATCHED"),
                outcomes(result));
        assertEquals(List.of(eu, both, retail), result.firedRules());
        assertEquals("uk=SKIPPED", outcomes(engine.runWithResult(new FactMap<>(),
                RunOptions.defaults().withTags(Set.of("EU")))).get(1), "tags are compared case included");
    }

    @Test
    @DisplayName("a run without tags uses every enabled rule in its window, whatever its tags")
    void noTagsUsesEveryRule() {
        Rule eu = rule("eu", "true").toBuilder().tags(Set.of("eu")).build();
        Rule untagged = rule("untagged", "true");

        assertEquals(List.of("eu=MATCHED", "untagged=MATCHED"),
                outcomes(loaded(allMatches(), eu, untagged).runWithResult(new FactMap<>())));
    }

    @Test
    @DisplayName("on a first-match engine, a skipped rule reads as skipped even below the match")
    void firstMatchReportsSkippedBelowTheMatch() {
        Rule off = rule("off", "true").toBuilder().enabled(false).build();
        Rule match = rule("match", "true");
        Rule offBelow = rule("off-below", "true").toBuilder().enabled(false).build();
        Rule below = rule("below", "true");

        RunResult<Map<String, Object>> result = loaded(RulesEngineBuilder.firstMatch(HashMap::new), off, match,
                offBelow, below).runWithResult(new FactMap<>());

        assertEquals(List.of("off=SKIPPED", "match=MATCHED", "off-below=SKIPPED", "below=NOT_EVALUATED"),
                outcomes(result));
        assertEquals(List.of(match), result.firedRules());
    }

    @Test
    @DisplayName("on a unique-match engine, a skipped rule can't make a second match")
    void uniqueMatchIgnoresSkippedRules() {
        Rule off = rule("off", "true").toBuilder().enabled(false).build();
        Rule on = rule("on", "true");

        RunResult<Map<String, Object>> result = loaded(RulesEngineBuilder.uniqueMatch(HashMap::new), off, on)
                .runWithResult(new FactMap<>());

        assertEquals(List.of("off=SKIPPED", "on=MATCHED"), outcomes(result));
        assertEquals(Map.of("on", 1), result.output());
    }

    @Test
    @DisplayName("load() and validate() still compile disabled rules and rules outside their window")
    void skippedRulesStillCompiled() {
        Rule disabled = rule("disabled", "x == == 1").toBuilder().enabled(false).build();
        Rule expired = rule("expired", "x == == 1").toBuilder().validTo(Instant.EPOCH).build();
        RulesEngine<Map<String, Object>> engine = allMatches().clock(AT_NOW).build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(disabled)));
        assertEquals("disabled", ex.getRuleName());
        assertEquals(List.of("disabled", "expired"), engine.validate(List.of(disabled, expired)).stream()
                .map(RuleCompilationException::getRuleName).toList());
    }

    @Test
    @DisplayName("the engine keeps and reports skipped rules as it loaded them")
    void skippedRulesStayLoaded() {
        Rule off = rule("off", "true").toBuilder().enabled(false).tags(Set.of("eu")).build();

        assertEquals(List.of(off), loaded(allMatches(), off).rules().rules());
    }

    @Test
    @DisplayName("a clock that throws fails the run unchanged, before any listener hears of the run")
    void throwingClockFailsTheRun() {
        IllegalStateException broken = new IllegalStateException("no time");
        Clock throwing = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw broken;
            }
        };
        List<String> heard = new CopyOnWriteArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                heard.add("beforeRun");
            }
        };
        RulesEngine<Map<String, Object>> engine = allMatches().clock(throwing).listener(listener).build();
        engine.load(List.of(rule("r", "true")));

        assertSame(broken, assertThrows(IllegalStateException.class, () -> engine.run(new FactMap<>())));
        assertEquals(List.of(), heard);
    }

    @Test
    @DisplayName("a clock that returns null fails the run, naming the clock, before any listener hears of it")
    void nullInstantFailsTheRun() {
        Clock returnsNull = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return null;
            }
        };
        List<String> heard = new CopyOnWriteArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                heard.add("beforeRun");
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                heard.add("onRunError");
            }
        };
        // No rule has a window, so only the run's context needs the instant.
        RulesEngine<Map<String, Object>> engine = allMatches().clock(returnsNull).listener(listener).build();
        engine.load(List.of(rule("r", "true")));

        assertEquals("the engine's clock returned a null instant",
                assertThrows(NullPointerException.class, () -> engine.run(new FactMap<>())).getMessage());
        assertEquals(List.of(), heard);
    }

    @Test
    @DisplayName("the builder rejects a null clock")
    void nullClockRejected() {
        RulesEngineBuilder<Map<String, Object>> builder = allMatches();

        assertEquals("clock must not be null",
                assertThrows(NullPointerException.class, () -> builder.clock(null)).getMessage());
    }
}
