package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
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
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1128: a condition and an action read the instant their run read from the engine's clock, and the run's tags, from
 * their context. Reaches {@code startedAt()} and {@code tags()} by reflection, so that it compiles against a release
 * without them and fails there rather than not compiling: the fails-before proof of #1128 ran it on the base.
 * {@link ContextStartedAtAndTagsTest} calls the methods directly.
 */
@DisplayName("a condition and an action read the run's start and tags from their context, found by reflection")
class ContextStartedAtAndTagsReflectionTest {

    private static final Instant NOW = Instant.parse("2027-06-01T09:00:00Z");

    /** What {@code EvaluationContext.<method>()} returns for {@code context}, or an assertion failure naming #1128. */
    private static Object read(EvaluationContext context, String method, String what) {
        try {
            return EvaluationContext.class.getMethod(method).invoke(context);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("EvaluationContext has no " + method + "(), so a condition or an action can't see "
                    + what + " (#1128)", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object startedAt(EvaluationContext context) {
        return read(context, "startedAt", "the instant its run read from the engine's clock");
    }

    private static Object tags(EvaluationContext context) {
        return read(context, "tags", "the tags its run was given");
    }

    /** A language that hands each condition's and each action's context to {@code condition} and {@code action}. */
    private static ExpressionLanguage handing(AtomicReference<EvaluationContext> condition,
                                              AtomicReference<EvaluationContext> action) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "handing";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluation, session) -> {
                            condition.set(evaluation);
                            return true;
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (evaluation, session) -> {
                            action.set(evaluation);
                            return ActionResult.done();
                        };
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    private final AtomicReference<EvaluationContext> condition = new AtomicReference<>();
    private final AtomicReference<EvaluationContext> action = new AtomicReference<>();
    private final AtomicReference<RunContext> run = new AtomicReference<>();

    /** Runs one rule tagged {@code eu} on an engine whose clock is fixed at {@link #NOW}, with the tags {@code eu}. */
    private void runTagged() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(handing(condition, action)).clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext context) {
                        run.set(context);
                    }
                }).build()) {
            engine.load(List.of(Rule.builder().ruleName("eu").condition("any").action("any").tags(Set.of("eu"))
                    .validFrom(Instant.parse("2027-06-01T00:00:00Z")).build()));
            engine.runWithResult(new FactMap<>(), RunOptions.defaults().withTags(Set.of("eu")));
        }
    }

    @Test
    @DisplayName("a condition's context gives the instant the run read from the engine's clock")
    void conditionSeesTheRunsStart() {
        runTagged();

        assertEquals(NOW, startedAt(condition.get()));
        assertEquals(run.get().startedAt(), startedAt(condition.get()));
    }

    @Test
    @DisplayName("a condition's context gives the run's tags, the set the run's context has")
    void conditionSeesTheRunsTags() {
        runTagged();

        assertEquals(Set.of("eu"), tags(condition.get()));
        assertSame(run.get().tags(), tags(condition.get()));
    }

    @Test
    @DisplayName("an action's context gives the run's start and tags too")
    void actionSeesTheRunsStartAndTags() {
        runTagged();

        assertAll(
                () -> assertEquals(NOW, startedAt(action.get())),
                () -> assertSame(run.get().tags(), tags(action.get())));
    }
}
