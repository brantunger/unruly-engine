package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
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
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A listener keys what it opens in {@code beforeRun} on the run's context, as {@link RunContext} suggests. A context
 * that compared by value made two engines' runs one key, and lost its key when a fact changed during the run; one
 * whose {@code toString()} printed the facts leaked their values into any log that printed it (#360).
 */
@DisplayName("a run's context compares by identity, and none of the contexts prints the facts")
class RunContextIdentityTest {

    private static final String SECRET = "4111 1111 1111 1111";
    private static final Clock AT_NOW = Clock.fixed(Instant.parse("2027-06-01T00:00:00Z"), ZoneOffset.UTC);

    /** Keeps a "span" per run in a map keyed on the context, as a tracing listener would. */
    private static final class Spans implements RuleListener {

        private final Map<RunContext, String> open = new HashMap<>();
        private final List<RunContext> runs = new ArrayList<>();
        private final List<String> closed = new ArrayList<>();

        @Override
        public void beforeRun(RunContext run) {
            open.put(run, "span " + runs.size());
            runs.add(run);
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            closed.add(open.remove(run));
        }
    }

    /** A fact whose method starts a run of the same engine from inside an action. */
    public static final class Nester {

        private final RulesEngine<Map<String, Object>> engine;

        Nester(RulesEngine<Map<String, Object>> engine) {
            this.engine = engine;
        }

        /**
         * Runs the engine again, with a fact that stops the rule from matching a second time.
         *
         * @return {@code true}
         */
        public boolean runNested() {
            engine.run(new FactMap<>(new Fact<Object>("depth", 1), new Fact<Object>("nester", this)));
            return true;
        }
    }

    private static RulesEngine<Map<String, Object>> engine(RuleListener listener, String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .listener(listener).clock(AT_NOW).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action(action).build()));
        return engine;
    }

    @Test
    @DisplayName("runs of two engines with the same rules and equal facts are different keys")
    void twoEnginesAreDifferentKeys() {
        Spans spans = new Spans();
        RuleListener keepOpen = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                spans.beforeRun(run);
            }
        };

        engine(keepOpen, "output.put('x', 1)").run(new FactMap<>(new Fact<Object>("n", 5)));
        engine(keepOpen, "output.put('x', 1)").run(new FactMap<>(new Fact<Object>("n", 5)));

        assertNotEquals(spans.runs.get(0), spans.runs.get(1));
        assertEquals(2, spans.open.size(), "one entry for each run");
    }

    @Test
    @DisplayName("a fact changed during the run doesn't lose the run's key")
    void changedFactKeepsTheKey() {
        Spans spans = new Spans();

        engine(spans, "list.add('x')").run(new FactMap<>(new Fact<Object>("list", new ArrayList<>())));

        assertEquals(List.of("span 0"), spans.closed, "afterRun found what beforeRun stored");
    }

    @Test
    @DisplayName("a run's context describes the run, its tags and when it started, not its facts")
    void runContextLeavesTheFactsOut() {
        Spans spans = new Spans();
        RulesEngine<Map<String, Object>> engine = engine(spans, "output.put('x', 1)");

        engine.run(new FactMap<>(new Fact<Object>("card", SECRET)));
        engine.runWithResult(new FactMap<>(new Fact<Object>("card", SECRET)),
                RunOptions.defaults().withTags(Set.of("retail", "eu")));

        String text = spans.runs.get(0).toString();
        assertFalse(text.contains(SECRET), text);
        assertEquals("RunContext(runId=1, parent=none, matchPolicy=allMatches, ruleSetChecksum="
                + spans.runs.get(0).ruleSetChecksum() + ", tags=[], startedAt=2027-06-01T00:00:00Z)", text);
        assertEquals("RunContext(runId=2, parent=none, matchPolicy=allMatches, ruleSetChecksum="
                + spans.runs.get(1).ruleSetChecksum() + ", tags=[eu, retail], startedAt=2027-06-01T00:00:00Z)",
                spans.runs.get(1).toString());
    }

    @Test
    @DisplayName("a nested run's context names its parent run, and the tags its own options give it")
    void nestedRunNamesItsParent() {
        List<RunContext> runs = new CopyOnWriteArrayList<>();
        RuleListener recorder = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                runs.add(run);
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .listener(recorder).clock(AT_NOW).build();
        engine.load(List.of(Rule.builder().ruleName("outer").condition("depth == 0").action("nester.runNested()")
                .tags(Set.of("eu")).build()));

        engine.runWithResult(new FactMap<>(new Fact<Object>("depth", 0),
                new Fact<Object>("nester", new Nester(engine))), RunOptions.defaults().withTags(Set.of("eu")));

        assertTrue(runs.get(1).toString().startsWith("RunContext(runId=2, parent=1, "), runs.get(1).toString());
        // The nested run has the tags its own options give it, none here; the run around it keeps its own.
        assertTrue(runs.get(1).toString().endsWith(", tags=[], startedAt=2027-06-01T00:00:00Z)"),
                runs.get(1).toString());
        assertTrue(runs.get(0).toString().endsWith(", tags=[eu], startedAt=2027-06-01T00:00:00Z)"),
                runs.get(0).toString());
    }

    @Test
    @DisplayName("a language's contexts name the run's deadline when it has one")
    void languageContextsNameTheDeadline() {
        Instant deadline = Instant.parse("2026-09-16T12:00:00Z");

        assertEquals("EvaluationContext(deadline=2026-09-16T12:00:00Z)",
                new EngineEvaluationContext(Map.of("card", SECRET), deadline).toString());
        assertEquals("ActionContext(output=java.util.HashMap, deadline=2026-09-16T12:00:00Z)",
                new EngineActionContext(Map.of("card", SECRET), new HashMap<>(Map.of("result", SECRET)), deadline)
                        .toString());
    }

    @Test
    @DisplayName("the contexts a language gets describe themselves without the facts or the output")
    void languageContextsLeaveTheFactsOut() {
        List<String> described = new CopyOnWriteArrayList<>();
        ExpressionLanguage describing = new ExpressionLanguage() {
            @Override
            public String name() {
                return "describing";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluation, session) -> described.add(evaluation.toString());
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (action, session) -> {
                            described.add(action.toString());
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
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                () -> new HashMap<>(Map.of("result", SECRET))).language(describing).defaultLanguage("describing")
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()));

        engine.run(new FactMap<>(new Fact<Object>("card", SECRET)));

        assertEquals(List.of("EvaluationContext(deadline=none)",
                "ActionContext(output=java.util.HashMap, deadline=none)"), described);
    }
}
