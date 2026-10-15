package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
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

/**
 * #1128: a condition and an action see the instant their run read from the engine's clock, which judged every rule's
 * validity window, and the run's tags, as its listeners do. Uses only the public API, so it shows what a language can
 * rely on; {@link ContextStartedAtAndTagsReflectionTest} reaches the same methods by reflection.
 */
@DisplayName("a condition and an action see the instant their run read from the engine's clock, and the run's tags")
class ContextStartedAtAndTagsTest {

    private static final Instant NOW = Instant.parse("2027-06-01T09:00:00Z");
    private static final Clock AT_NOW = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant JUNE = Instant.parse("2027-06-01T00:00:00Z");
    private static final Instant JULY = Instant.parse("2027-07-01T00:00:00Z");

    /** What a condition or an action saw of its run: the {@code depth} fact, the context's start and its tags. */
    private record Seen(Object depth, Instant startedAt, Set<String> tags) {

        static Seen of(EvaluationContext context) {
            return new Seen(context.facts().get("depth"), context.startedAt(), context.tags());
        }
    }

    /**
     * A language whose conditions match when the run started in June 2027 by the context's {@code startedAt()}, as the
     * issue's language did by the system clock, and whose conditions and actions record what they see. When
     * {@link #nestedTags} is set, an action at depth 0 runs {@link #engine} again at depth 1 with those tags, and
     * records what it sees after that run too.
     */
    private static final class RunInfoLanguage implements ExpressionLanguage {

        private final List<Seen> conditions = new CopyOnWriteArrayList<>();
        private final List<Seen> actions = new CopyOnWriteArrayList<>();
        private RulesEngine<Map<String, Object>> engine;
        private Set<String> nestedTags;

        @Override
        public String name() {
            return "run-info";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> {
                        conditions.add(Seen.of(evaluation));
                        Instant now = evaluation.startedAt();
                        return !now.isBefore(JUNE) && now.isBefore(JULY);
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> {
                        actions.add(Seen.of(action));
                        if (nestedTags != null && !Integer.valueOf(1).equals(action.facts().get("depth"))) {
                            FactMap<Object> nested = new FactMap<>();
                            nested.setValue("depth", 1);
                            engine.runWithResult(nested, RunOptions.defaults().withTags(nestedTags));
                            actions.add(Seen.of(action));
                        }
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    /** The context of each run the engine reported to {@code beforeRun}, in order. */
    private final List<RunContext> runs = new CopyOnWriteArrayList<>();

    private RulesEngine<Map<String, Object>> loaded(RunInfoLanguage language, Clock clock, Rule... rules) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).clock(clock).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        runs.add(run);
                    }
                }).build();
        engine.load(List.of(rules));
        language.engine = engine;
        return engine;
    }

    private static Rule rule(String name, Set<String> tags) {
        return Rule.builder().ruleName(name).condition("in June 2027").action("noop").tags(tags)
                .validFrom(JUNE).build();
    }

    @Test
    @DisplayName("a condition and an action see the instant the run read from the engine's clock, which selected the"
            + " rule, and the run's tags: the set the run's context has")
    void conditionAndActionSeeTheRunsStartAndTags() {
        RunInfoLanguage language = new RunInfoLanguage();

        RunResult<Map<String, Object>> result;
        try (RulesEngine<Map<String, Object>> engine = loaded(language, AT_NOW, rule("june-2027", Set.of("eu")))) {
            result = engine.runWithResult(new FactMap<>(), RunOptions.defaults().withTags(Set.of("retail", "eu")));
        }

        // The rule's window selected it, and its condition, judged at the same instant, matched: by the system clock
        // it would not have.
        assertEquals("[june-2027=MATCHED]", result.evaluations().toString());
        assertEquals(1, runs.size());
        RunContext run = runs.get(0);
        assertEquals(1, language.conditions.size());
        assertEquals(1, language.actions.size());
        Seen condition = language.conditions.get(0);
        Seen action = language.actions.get(0);
        assertAll(
                () -> assertEquals(NOW, run.startedAt()),
                () -> assertEquals(NOW, condition.startedAt()),
                () -> assertEquals(NOW, action.startedAt()),
                () -> assertSame(run.tags(), condition.tags(), "the run's own sorted, unmodifiable tags"),
                () -> assertSame(run.tags(), action.tags()),
                () -> assertSame(result.tags(), condition.tags()),
                () -> assertEquals(List.of("eu", "retail"), List.copyOf(condition.tags())),
                () -> assertThrows(UnsupportedOperationException.class, () -> condition.tags().add("uk")));
    }

    @Test
    @DisplayName("a run without tags gives its conditions and actions none")
    void withoutTagsTheContextsHaveNone() {
        RunInfoLanguage language = new RunInfoLanguage();

        try (RulesEngine<Map<String, Object>> engine = loaded(language, AT_NOW, rule("june-2027", Set.of()))) {
            engine.run(new FactMap<>());
        }

        // Whether the context has the run's own empty set can't be told apart from an empty set of its own, so only
        // the tagged runs above check for the same set.
        assertAll(
                () -> assertTrue(language.conditions.get(0).tags().isEmpty(), "the condition's tags"),
                () -> assertTrue(language.actions.get(0).tags().isEmpty(), "the action's tags"),
                () -> assertEquals(NOW, language.actions.get(0).startedAt()));
    }

    @Test
    @DisplayName("a nested run reads the clock and its tags for itself, and the outer run's contexts keep their own")
    void nestedRunSeesItsOwnStartAndTags() {
        AtomicInteger reads = new AtomicInteger();
        // Each read is a second later than the one before, so the nested run starts a second after the outer one.
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
        RunInfoLanguage language = new RunInfoLanguage();
        language.nestedTags = Set.of("inner");

        try (RulesEngine<Map<String, Object>> engine = loaded(language, ticking,
                rule("both", Set.of("inner", "outer")))) {
            engine.runWithResult(new FactMap<>(), RunOptions.defaults().withTags(Set.of("outer")));
        }

        assertEquals(2, runs.size());
        RunContext outer = runs.get(0);
        RunContext inner = runs.get(1);
        assertEquals(NOW, outer.startedAt());
        assertEquals(NOW.plusSeconds(1), inner.startedAt());
        // The outer condition and the nested one; the outer action before the nested run, the nested action, and the
        // outer action after it.
        assertEquals(List.of(new Seen(null, NOW, outer.tags()), new Seen(1, NOW.plusSeconds(1), inner.tags())),
                language.conditions);
        assertEquals(List.of(new Seen(null, NOW, outer.tags()), new Seen(1, NOW.plusSeconds(1), inner.tags()),
                new Seen(null, NOW, outer.tags())), language.actions);
        assertAll(
                () -> assertEquals(Set.of("outer"), outer.tags()),
                () -> assertEquals(Set.of("inner"), inner.tags()),
                () -> assertSame(outer.tags(), language.conditions.get(0).tags()),
                () -> assertSame(inner.tags(), language.conditions.get(1).tags()),
                () -> assertSame(outer.tags(), language.actions.get(2).tags()));
    }
}
