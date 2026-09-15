package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("each run shares the compiled rules, with sessions that no other run is using")
class CompiledRuleCopiesTest {

    private static FactStore<Object> x(int value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", value);
        return facts;
    }

    /** A language that counts its sessions, and whose compiled expressions record what ran and with which session. */
    private static final class CountingLanguage implements ExpressionLanguage {

        private final AtomicInteger sessions = new AtomicInteger();
        private final List<Object> evaluated = new CopyOnWriteArrayList<>();
        private final List<Object> executed = new CopyOnWriteArrayList<>();
        private final List<Session> evaluatedWith = new CopyOnWriteArrayList<>();
        private final List<Session> executedWith = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return new Condition();
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return new Action();
                }

                @Override
                public Session newSession() {
                    sessions.incrementAndGet();
                    return new Session() {
                    };
                }
            };
        }

        private final class Condition implements CompiledCondition {
            @Override
            public Object evaluate(EvaluationContext context, Session session) {
                evaluated.add(this);
                evaluatedWith.add(session);
                if (context.facts().containsKey("fail")) {
                    throw new IllegalStateException("condition failed");
                }
                return true;
            }
        }

        private final class Action implements CompiledAction {
            @Override
            public ActionResult execute(ActionContext context, Session session) {
                executed.add(this);
                executedWith.add(session);
                return ActionResult.done();
            }
        }
    }

    private static StatefulRulesEngine<Map<String, Object>> countingEngine(CountingLanguage language) {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.registerLanguage(language);
        engine.setRuleList(List.of(Rule.builder().ruleName("r").language(language.name()).condition("c").action("a")
                .build()));
        return engine;
    }

    private static int distinct(List<?> instances) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        instances.forEach(instance -> seen.put(instance, true));
        return seen.size();
    }

    @Test
    @DisplayName("a run started while another is still running gets its own sessions and the right result")
    void runDuringRun() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        List<Object> innerOutputs = new CopyOnWriteArrayList<>();
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                // Only the outer run starts an inner one, while it still holds its copy of the rules.
                if (innerOutputs.isEmpty()) {
                    innerOutputs.add("started");
                    innerOutputs.set(0, engine.run(x(2)));
                }
            }
        });
        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("x > 0").action("output.put('x', x)").build()));

        assertEquals(Map.of("x", 1), engine.run(x(1)));
        assertEquals(List.of(Map.of("x", 2)), innerOutputs);
        assertEquals(Map.of("x", 3), engine.run(x(3)), "both copies are reused afterwards");
    }

    @Test
    @DisplayName("getCompiledRules returns the rules compiled by setRuleList, unmodifiable, or null before it")
    void compiledRules() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        assertNull(engine.getCompiledRules());

        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("x > 0").action("output.put('x', x)").build()));
        List<CompiledRule> rules = engine.getCompiledRules();

        assertEquals(List.of("r"), rules.stream().map(CompiledRule::displayName).toList());
        assertEquals(List.of("mvel"), rules.stream().map(CompiledRule::language).toList());
        assertThrows(UnsupportedOperationException.class, () -> rules.remove(0));
    }

    @Test
    @DisplayName("every run evaluates the rules compiled by setRuleList, and sequential runs reuse one session")
    void compiledRulesSharedWithOneSession() {
        CountingLanguage language = new CountingLanguage();
        StatefulRulesEngine<Map<String, Object>> engine = countingEngine(language);
        CompiledRule compiled = engine.getCompiledRules().get(0);

        for (int i = 0; i < 3; i++) {
            engine.run(new FactMap<>());
        }

        assertEquals(List.of(compiled.compiledCondition(), compiled.compiledCondition(), compiled.compiledCondition()),
                language.evaluated, "the compiled condition is shared by every run");
        assertEquals(List.of(compiled.compiledAction(), compiled.compiledAction(), compiled.compiledAction()),
                language.executed, "the compiled action is shared by every run");
        assertEquals(1, language.sessions.get(), "sessions created");
        assertEquals(1, distinct(language.evaluatedWith), "distinct sessions conditions ran with");
        assertSame(language.evaluatedWith.get(0), language.executedWith.get(0),
                "a run's condition and action get the same session");
    }

    @Test
    @DisplayName("a run that throws gives its sessions back, so later runs reuse them instead of creating new ones")
    void failedRunsGiveBackTheirCopy() {
        CountingLanguage language = new CountingLanguage();
        StatefulRulesEngine<Map<String, Object>> engine = countingEngine(language);
        FactStore<Object> rejected = new FactMap<>();
        rejected.setValue("output", 1);
        FactStore<Object> failing = new FactMap<>();
        failing.setValue("fail", true);

        logsOf(() -> {
            for (int i = 0; i < 5; i++) {
                assertThrows(IllegalArgumentException.class, () -> engine.run(rejected));
                assertThrows(RuleExecutionException.class, () -> engine.run(failing));
            }
        });
        assertEquals(Map.of(), engine.run(new FactMap<>()));

        assertEquals(1, language.sessions.get(), "sessions created");
        assertEquals(1, distinct(language.evaluatedWith), "distinct sessions conditions ran with");
    }

    @Test
    @DisplayName("a run that starts while another holds the only session gets its own, for the condition and the action")
    void overlappingRunGetsItsOwnSession() {
        CountingLanguage language = new CountingLanguage();
        StatefulRulesEngine<Map<String, Object>> engine = countingEngine(language);
        AtomicBoolean nested = new AtomicBoolean();
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                if (nested.compareAndSet(false, true)) {
                    engine.run(new FactMap<>());
                }
            }
        });

        engine.run(new FactMap<>());

        assertEquals(2, language.sessions.get(), "sessions created");
        assertEquals(2, distinct(language.evaluatedWith), "distinct sessions conditions ran with");
        assertEquals(2, distinct(language.executedWith), "distinct sessions actions ran with");
        assertEquals(1, distinct(language.evaluated), "both runs evaluated the same compiled condition");
        assertEquals(1, distinct(language.executed), "both runs executed the same compiled action");
    }
}
