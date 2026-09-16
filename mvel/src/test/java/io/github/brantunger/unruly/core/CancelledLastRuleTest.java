package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
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
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A language that notices it was cancelled and returns, as {@code EvaluationContext.isCancelled()} says it may, must
 * not turn a cancelled run into a normal result when its expression was the last one the run evaluates. Uses only
 * API that predates {@code RunOptions}, so it compiles against the engine before the fix.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a run cancelled during its last condition or action throws, rather than returning a result")
class CancelledLastRuleTest {

    private static final Duration SHORT = Duration.ofMillis(200);

    /** A language whose expressions wait until the run is cancelled, then return as the SPI says they may. */
    private static final class PoliteLanguage implements ExpressionLanguage {

        @Override
        public String name() {
            return "polite";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    if ("yes".equals(expression.text())) {
                        return (evaluation, session) -> true;
                    }
                    return (evaluation, session) -> {
                        while (!evaluation.isCancelled()) {
                            Thread.onSpinWait();
                        }
                        return false;
                    };
                }

                @Override
                @SuppressWarnings("unchecked")
                public CompiledAction compileAction(Expression expression) {
                    if ("immediate".equals(expression.text())) {
                        return (action, session) -> ActionResult.done();
                    }
                    return (action, session) -> {
                        ((Map<String, Object>) action.output()).put("half", true);
                        while (!action.isCancelled()) {
                            Thread.onSpinWait();
                        }
                        return ActionResult.set(Map.of("rest", true));
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    /** Records how a run ended for listeners. */
    private static final class Ending implements RuleListener {

        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void afterExecute(Rule rule, Object output) {
            calls.add("afterExecute " + rule.getRuleName());
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            calls.add("onError " + rule.getRuleName());
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            calls.add("afterRun");
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            calls.add("onRunError");
        }
    }

    private static RulesEngine<Map<String, Object>> engine(RulesEngineBuilder<Map<String, Object>> builder,
                                                           Ending ending, Rule rule) {
        RulesEngine<Map<String, Object>> engine = builder.language(new PoliteLanguage()).runTimeout(SHORT)
                .listener(ending).build();
        engine.load(List.of(rule));
        return engine;
    }

    @Test
    @DisplayName("a first-match condition that gives up at the deadline doesn't become 'no rule matched'")
    void aConditionThatGivesUp() {
        Ending ending = new Ending();
        RulesEngine<Map<String, Object>> engine = engine(RulesEngineBuilder.firstMatch(HashMap::new), ending,
                Rule.builder().ruleName("only").condition("wait").action("immediate").build());

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()),
                "a cancelled run returned 'no rule matched'");

        assertInstanceOf(TimeoutException.class, thrown.getCause());
        assertNull(thrown.getRuleName());
        assertTrue(thrown.getMessage().endsWith(" during rule 'only'"), thrown.getMessage());
        assertEquals(List.of("onError only", "onRunError"), ending.calls);
    }

    @Test
    @DisplayName("an all-matches action that gives up half-way isn't reported as fired")
    void anActionThatGivesUp() {
        Ending ending = new Ending();
        Map<String, Object> output = new HashMap<>();
        RulesEngine<Map<String, Object>> engine = engine(RulesEngineBuilder.allMatches(() -> output), ending,
                Rule.builder().ruleName("only").condition("yes").action("half").build());

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()),
                "a cancelled run returned the action's half-done output");

        assertInstanceOf(TimeoutException.class, thrown.getCause());
        assertFalse(output.containsKey("rest"), "the properties the action returned past its deadline were set");
        assertFalse(ending.calls.contains("afterExecute only"), ending.calls.toString());
        assertTrue(ending.calls.contains("onRunError"), ending.calls.toString());
    }
}
