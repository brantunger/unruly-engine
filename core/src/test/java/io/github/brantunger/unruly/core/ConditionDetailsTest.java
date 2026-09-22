package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleEvaluation.Outcome;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #410: a run result records the detail a language explains each condition's result with. */
@DisplayName("a run records the detail a language explains a condition with, on the rule's evaluation")
class ConditionDetailsTest {

    // The toy explains a comparison with the operands it read, and gives no detail for a single operand.
    private static final Rule HIGH = rule("high", 3, "score >= 700");
    private static final Rule MIDDLE = rule("middle", 2, "score >= 800");
    private static final Rule LOW = rule("low", 1, "true");

    private static Rule rule(String name, int priority, String condition) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action("put " + name + " 1")
                .build();
    }

    private static RulesEngine<Map<String, Object>> loaded(RulesEngineBuilder<Map<String, Object>> builder,
                                                           Rule... rules) {
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(rules));
        return engine;
    }

    private static List<Object> details(RunResult<?> result) {
        return result.evaluations().stream().map(RuleEvaluation::detail).toList();
    }

    @Test
    @DisplayName("a condition's detail is recorded whether it matched or not, and is null when the language gives none")
    void detailRecorded() {
        RunResult<Map<String, Object>> result = loaded(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new ToyExpressionLanguage()), HIGH, MIDDLE, LOW)
                .runWithResult(new FactMap<>(new Fact<>("score", 750)));

        assertEquals(List.of(RuleEvaluation.of(HIGH, Outcome.MATCHED), RuleEvaluation.of(MIDDLE, Outcome.NOT_MATCHED),
                RuleEvaluation.of(LOW, Outcome.MATCHED)), result.evaluations());
        assertEquals(Arrays.asList("750 >= 700", "750 >= 800", null), details(result));
    }

    @Test
    @DisplayName("a rule that is skipped, or not evaluated after a first match, has no detail")
    void noDetailWithoutEvaluation() {
        Rule skipped = rule("skipped", 4, "score >= 1").toBuilder().enabled(false).build();

        RunResult<Map<String, Object>> result = loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new ToyExpressionLanguage()), skipped, HIGH, MIDDLE)
                .runWithResult(new FactMap<>(new Fact<>("score", 750)));

        assertEquals(List.of(RuleEvaluation.of(skipped, Outcome.SKIPPED), RuleEvaluation.of(HIGH, Outcome.MATCHED),
                RuleEvaluation.of(MIDDLE, Outcome.NOT_EVALUATED)), result.evaluations());
        assertEquals(Arrays.asList(null, "750 >= 700", null), details(result));
    }

    @Test
    @DisplayName("two evaluations are equal whatever their details, which toString leaves out")
    void detailNotPartOfTheValue() {
        RuleEvaluation explained = RuleEvaluation.of(HIGH, Outcome.MATCHED, "750 >= 700");

        assertEquals("750 >= 700", explained.detail());
        assertNull(RuleEvaluation.of(HIGH, Outcome.MATCHED).detail());
        assertEquals(RuleEvaluation.of(HIGH, Outcome.MATCHED), explained);
        assertEquals(RuleEvaluation.of(HIGH, Outcome.MATCHED, "800 >= 700"), explained);
        assertEquals(RuleEvaluation.of(HIGH, Outcome.MATCHED).hashCode(), explained.hashCode());
        assertNotEquals(RuleEvaluation.of(HIGH, Outcome.NOT_MATCHED, "750 >= 700"), explained);
        assertEquals("high=MATCHED", explained.toString());
        assertThrows(NullPointerException.class, () -> RuleEvaluation.of(null, Outcome.MATCHED, "detail"));
        assertThrows(NullPointerException.class, () -> RuleEvaluation.of(HIGH, null, "detail"));
    }

    /** A language whose every condition returns what {@code result} is given. */
    private static ExpressionLanguage returning(ConditionResult result) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "returning";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return new CompiledCondition() {
                            @Override
                            public Object evaluate(EvaluationContext evaluation, Session session) {
                                throw new AssertionError("the engine called evaluate(), not evaluateWithDetail()");
                            }

                            @Override
                            public ConditionResult evaluateWithDetail(EvaluationContext evaluation,
                                                                      Session session) {
                                return result;
                            }
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (actionContext, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    private static RunResult<Map<String, Object>> runReturning(ConditionResult result) {
        Rule rule = rule("r", 1, "anything").toBuilder().language("returning").build();
        return loaded(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(returning(result)),
                rule).runWithResult(new FactMap<>());
    }

    @Test
    @DisplayName("the engine evaluates a condition with evaluateWithDetail, and records the detail it returns")
    void engineCallsEvaluateWithDetail() {
        List<Object> operands = List.of(750, 700);

        assertSame(operands, runReturning(ConditionResult.of(true, operands)).evaluations().get(0).detail());
        assertSame(operands, runReturning(ConditionResult.of(false, operands)).evaluations().get(0).detail());
        assertEquals(Outcome.NOT_MATCHED,
                runReturning(ConditionResult.of(false, operands)).evaluations().get(0).outcome());
    }

    @Test
    @DisplayName("a detail doesn't save a value that isn't a boolean, and a null result fails the rule, naming it")
    void wrongResultsFail() {
        RuleExecutionException notBoolean = assertThrows(RuleExecutionException.class,
                () -> runReturning(ConditionResult.of("yes", "a detail")));
        RuleExecutionException noResult = assertThrows(RuleExecutionException.class, () -> runReturning(null));

        assertEquals("Condition for rule 'r' evaluated to a java.lang.String. A condition expression must evaluate to"
                + " a boolean.", notBoolean.getMessage());
        assertEquals("Condition for rule 'r' returned no result from evaluateWithDetail. A condition expression must"
                + " evaluate to a boolean.", noResult.getMessage());
    }
}
