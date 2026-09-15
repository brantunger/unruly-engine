package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("each run uses a compiled copy of the rules that no other run is using")
class CompiledRuleCopiesTest {

    private static FactStore<Object> x(int value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", value);
        return facts;
    }

    /** A language whose compiled expressions record which instance ran, and count their copies. */
    private static final class CountingLanguage implements ExpressionLanguage {

        private final AtomicInteger conditionCopies = new AtomicInteger();
        private final AtomicInteger actionCopies = new AtomicInteger();
        private final List<Object> evaluated = new CopyOnWriteArrayList<>();
        private final List<Object> executed = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(String source) {
                    return new Condition();
                }

                @Override
                public CompiledAction compileAction(String source) {
                    return new Action();
                }
            };
        }

        private final class Condition implements CompiledCondition {
            @Override
            public Object evaluate(EvaluationContext context) {
                evaluated.add(this);
                if (context.facts().containsKey("fail")) {
                    throw new IllegalStateException("condition failed");
                }
                return true;
            }

            @Override
            public CompiledCondition copy() {
                conditionCopies.incrementAndGet();
                return new Condition();
            }
        }

        private final class Action implements CompiledAction {
            @Override
            public void execute(ActionContext context) {
                executed.add(this);
            }

            @Override
            public CompiledAction copy() {
                actionCopies.incrementAndGet();
                return new Action();
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

    private static int distinct(List<Object> instances) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        instances.forEach(instance -> seen.put(instance, true));
        return seen.size();
    }

    @Test
    @DisplayName("a run started while another is still running gets its own copy and the right result")
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
        assertThrows(UnsupportedOperationException.class, () -> rules.remove(0));
    }

    @Test
    @DisplayName("the rules compiled by setRuleList are never run, and sequential runs reuse one copy")
    void compiledRulesNeverRun() {
        CountingLanguage language = new CountingLanguage();
        StatefulRulesEngine<Map<String, Object>> engine = countingEngine(language);
        CompiledRule compiled = engine.getCompiledRules().get(0);

        for (int i = 0; i < 3; i++) {
            engine.run(new FactMap<>());
        }

        assertEquals(3, language.evaluated.size());
        assertFalse(language.evaluated.contains(compiled.compiledCondition()), "the compiled condition was evaluated");
        assertFalse(language.executed.contains(compiled.compiledAction()), "the compiled action was executed");
        assertEquals(1, language.conditionCopies.get(), "condition copies");
        assertEquals(1, language.actionCopies.get(), "action copies");
    }

    @Test
    @DisplayName("a run that throws gives its copy back, so later runs reuse it instead of copying the rules again")
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

        assertEquals(1, language.conditionCopies.get(), "condition copies");
        assertEquals(1, language.actionCopies.get(), "action copies");
    }

    @Test
    @DisplayName("a run that starts while another holds the only copy gets its own copy of the condition and the action")
    void overlappingRunCopiesConditionAndAction() {
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

        assertEquals(2, language.conditionCopies.get(), "condition copies");
        assertEquals(2, language.actionCopies.get(), "action copies");
        assertEquals(2, distinct(language.evaluated), "distinct conditions evaluated");
        assertEquals(2, distinct(language.executed), "distinct actions executed");
        assertTrue(Collections.disjoint(language.executed, List.of(engine.getCompiledRules().get(0).compiledAction())));
    }
}
