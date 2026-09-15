package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AbstractRulesEngine (via concrete engines)")
class AbstractRulesEngineTest {

    @Nested
    @DisplayName("null facts parameter")
    class NullFacts {

        @Test
        @DisplayName("StatefulRulesEngine throws NullPointerException for null facts")
        void statefulThrowsForNullFacts() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            Rule rule = Rule.builder()
                    .ruleName("dummy")
                    .condition("true")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();
            engine.load(List.of(rule));

            NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.run(null));
            assertTrue(ex.getMessage().contains("facts must not be null"));
        }

        @Test
        @DisplayName("StatelessRulesEngine throws NullPointerException for null facts")
        void statelessThrowsForNullFacts() {
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
            Rule rule = Rule.builder()
                    .ruleName("dummy")
                    .condition("true")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();
            engine.load(List.of(rule));

            NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.run(null));
            assertTrue(ex.getMessage().contains("facts must not be null"));
        }
    }

    @Nested
    @DisplayName("null priority handling")
    class NullPriority {

        @Test
        @DisplayName("rules with null priority are treated as lowest priority")
        void nullPriorityTreatedAsLowest() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

            Rule withPriority = Rule.builder()
                    .ruleName("has-priority")
                    .condition("true")
                    .action("output.put(\"last\", \"has-priority\")")
                    .priority(1)
                    .build();

            Rule noPriority = Rule.builder()
                    .ruleName("no-priority")
                    .condition("true")
                    .action("output.put(\"last\", \"no-priority\")")
                    // priority is null
                    .build();

            engine.load(Arrays.asList(withPriority, noPriority));

            FactStore<Object> facts = new FactMap<>();

            Map<String, Object> result = engine.run(facts);

            // Null-priority rule should fire last (lowest priority) and overwrite
            assertEquals("no-priority", result.get("last"));
        }

        @Test
        @DisplayName("multiple rules with null priority do not throw")
        void multipleNullPrioritiesDoNotThrow() {
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);

            Rule rule1 = Rule.builder()
                    .ruleName("rule1")
                    .condition("true")
                    .action("output.put(\"source\", \"rule1\")")
                    .build();

            Rule rule2 = Rule.builder()
                    .ruleName("rule2")
                    .condition("true")
                    .action("output.put(\"source\", \"rule2\")")
                    .build();

            assertDoesNotThrow(() -> engine.load(Arrays.asList(rule1, rule2)));

            FactStore<Object> facts = new FactMap<>();
            Map<String, Object> result = engine.run(facts);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("imports accumulation")
    class ImportsAccumulation {

        @Test
        @DisplayName("imports(Collection) does not discard imports added before with imports(String...)")
        void collectionImportsDoNotDiscardEarlierImports() {
            // java.util first, then java.time from a collection, which must not discard java.util
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                    builder -> builder.imports("java.util").imports(Set.of("java.time")));

            // This rule uses Objects from java.util — should still work
            Rule rule = Rule.builder()
                    .ruleName("uses-both")
                    .condition("Objects.nonNull(name)")
                    .action("output.put(\"result\", true)")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("name", "test");

            Map<String, Object> result = engine.run(facts);
            assertEquals(true, result.get("result"));
        }

        @Test
        @DisplayName("multiple imports(Collection) calls accumulate")
        void multipleAddImportsCallsAccumulate() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                    builder -> builder.imports(Set.of("java.util")).imports(Set.of("java.time")));

            Rule rule = Rule.builder()
                    .ruleName("uses-objects")
                    .condition("Objects.nonNull(name)")
                    .action("output.put(\"valid\", true)")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("name", "value");

            Map<String, Object> result = engine.run(facts);
            assertEquals(true, result.get("valid"));
        }
    }

    @Nested
    @DisplayName("error paths")
    class ErrorPaths {

        @Test
        @DisplayName("compilation error throws RuleCompilationException")
        void compilationErrorThrown() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

            Rule rule = Rule.builder()
                    .ruleName("bad-syntax")
                    .condition("this is invalid MVEL %$#")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();

            RuleCompilationException ex = assertThrows(RuleCompilationException.class, 
                    () -> engine.load(List.of(rule)));
            assertTrue(ex.getMessage().contains("bad-syntax"));
        }

        @Test
        @DisplayName("condition evaluation error is thrown and logged")
        void conditionEvaluationErrorThrown() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

            // This condition references a variable not in facts
            Rule rule = Rule.builder()
                    .ruleName("missing-var")
                    .condition("nonExistentVariable > 5")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
            assertTrue(ex.getMessage().contains("missing-var"));
        }

        @Test
        @DisplayName("action evaluation error is thrown and logged")
        void actionEvaluationErrorThrown() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

            Rule rule = Rule.builder()
                    .ruleName("bad-action")
                    .condition("true")
                    .action("output.callNonExistentMethod()")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
            assertTrue(ex.getMessage().contains("bad-action"));
        }

        @Test
        @DisplayName("condition evaluating to null reports the rule, not an internal NPE")
        void nullConditionResultReportsRule() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

            Rule rule = Rule.builder()
                    .ruleName("null-condition")
                    .condition("null")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
            assertTrue(ex.getMessage().contains("null-condition"));
            assertTrue(ex.getMessage().contains("must evaluate to a boolean"));
            assertNull(ex.getCause());
        }
    }

    @Nested
    @DisplayName("non-boolean condition results")
    class NonBooleanConditions {

        private Map<String, Object> runCondition(String condition, FactStore<Object> facts) {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            engine.load(List.of(Rule.builder()
                    .ruleName("non-boolean")
                    .condition(condition)
                    .action("output.put(\"fired\", true)")
                    .priority(1)
                    .build()));
            return engine.run(facts);
        }

        @ParameterizedTest(name = "condition {0} throws instead of being coerced")
        @ValueSource(strings = {"status", "\"APPROVED\"", "\"false\"", "amount", "0", "5"})
        void nonBooleanResultThrows(String condition) {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("status", "DENIED");
            facts.setValue("amount", 5);

            RuleExecutionException ex = assertThrows(RuleExecutionException.class,
                    () -> runCondition(condition, facts));
            assertTrue(ex.getMessage().contains("non-boolean"));
            assertTrue(ex.getMessage().contains("must evaluate to a boolean"));
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("message names the type the condition produced")
        void messageNamesResultType() {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("status", "DENIED");

            RuleExecutionException ex = assertThrows(RuleExecutionException.class,
                    () -> runCondition("status", facts));
            assertTrue(ex.getMessage().contains("java.lang.String"));
        }

        @Test
        @DisplayName("a Boolean-valued fact is still a valid condition")
        void booleanFactIsValidCondition() {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("approved", Boolean.TRUE);

            assertEquals(true, runCondition("approved", facts).get("fired"));
        }

        @Test
        @DisplayName("a false condition still does not match")
        void falseConditionDoesNotMatch() {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("approved", Boolean.FALSE);

            assertNull(runCondition("approved", facts));
        }
    }

    @Nested
    @DisplayName("fact isolation between rules")
    class FactIsolation {

        private Rule rule(String name, int priority, String condition, String action) {
            return Rule.builder()
                    .ruleName(name)
                    .condition(condition)
                    .action(action)
                    .priority(priority)
                    .build();
        }

        @Test
        @DisplayName("assigning a fact in a condition is rejected by load()")
        void conditionAssignmentToFactThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            // `approved = true` is a typo for `==`; it evaluates to a Boolean, so it would otherwise run.
            RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(List.of(
                    rule("typo", 2, "approved = true", "output.put(\"typo\", true)"),
                    rule("check", 1, "approved == true", "output.put(\"check\", true)"))));

            assertTrue(ex.getMessage().contains("'typo'"));
            assertTrue(ex.getMessage().contains("'=' at position 9"));
        }

        @Test
        @DisplayName("creating a new variable in a condition is rejected by load()")
        void conditionNewVariableThrows() {
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);

            RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(
                    List.of(rule("new-var", 1, "(flag = true) == true", "output.put(\"k\", 1)"))));
            assertTrue(ex.getMessage().contains("'=' at position 6"));
        }

        @Test
        @DisplayName("assignments in an action are local to that action")
        void actionAssignmentIsLocal() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            engine.load(List.of(
                    rule("assigns", 2, "true", "score = 10; output.put(\"first\", score)"),
                    rule("reads", 1, "score == 1", "output.put(\"second\", score)")));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("score", 1);

            Map<String, Object> result = engine.run(facts);
            assertEquals(10, result.get("first"));
            assertEquals(1, result.get("second"));
            assertEquals(1, facts.getValue("score"));
        }

        @Test
        @DisplayName("a fact named 'output' is rejected")
        void outputFactNameRejected() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            engine.load(List.of(rule("any", 1, "true", "output.put(\"k\", 1)")));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("output", "shadowed");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(facts));
            assertTrue(ex.getMessage().contains("'output' is reserved"));
        }
    }

    @Nested
    @DisplayName("null fact values")
    class NullFactValues {

        @Test
        @DisplayName("facts with null values do not cause NPE in condition evaluation")
        void nullFactValueDoesNotCauseNPE() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

            Rule rule = Rule.builder()
                    .ruleName("always-true")
                    .condition("true")
                    .action("output.put(\"result\", \"ok\")")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("key", null);

            Map<String, Object> result = engine.run(facts);
            assertEquals("ok", result.get("result"));
        }
    }

    @Nested
    @DisplayName("null ruleList parameter")
    class NullRuleList {

        @Test
        @DisplayName("load throws NullPointerException for null ruleList")
        void loadThrowsForNull() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

            NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.load(null));
            assertTrue(ex.getMessage().contains("ruleList must not be null"));
        }

        @Test
        @DisplayName("a null rule in the list throws RuleCompilationException naming its index")
        void nullRuleElementThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            Rule good = Rule.builder().ruleName("good").condition("true").action("output.put('k', 1)").build();

            RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(Arrays.asList(good, null)));
            assertTrue(ex.getMessage().contains("index 1"));
        }

        @Test
        @DisplayName("validation errors can be caught as UnrulyException")
        void validationErrorsAreUnrulyExceptions() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            Rule blank = Rule.builder().ruleName("blank").condition(" ").action("output.put('k', 1)").build();

            assertThrows(io.github.brantunger.unruly.api.exception.UnrulyException.class,
                    () -> engine.load(List.of(blank)));
        }
    }

    @Nested
    @DisplayName("Rule condition validation")
    class RuleConditionValidation {

        @Test
        @DisplayName("blank condition throws RuleCompilationException naming the rule")
        void blankConditionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition("   ")
                    .action("output.put(\"k\",1)").build();

            RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule)));
            assertTrue(ex.getMessage().contains("my-rule"));
            assertTrue(ex.getMessage().contains("condition"));
        }

        @Test
        @DisplayName("empty string condition throws RuleCompilationException")
        void emptyConditionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition("")
                    .action("output.put(\"k\",1)").build();

            assertThrows(RuleCompilationException.class, () -> engine.load(List.of(rule)));
        }
    }

    @Nested
    @DisplayName("Rule action validation")
    class RuleActionValidation {

        @Test
        @DisplayName("blank action throws RuleCompilationException naming the rule")
        void blankActionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition("true").action("  ").build();

            RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule)));
            assertTrue(ex.getMessage().contains("my-rule"));
            assertTrue(ex.getMessage().contains("action"));
        }
    }

    @Nested
    @DisplayName("imports validation")
    class AddImportValidation {

        @Test
        @DisplayName("imports((String[]) null) and imports(null element) throw NullPointerException with a message")
        void nullImportsThrow() {
            RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

            NullPointerException array = assertThrows(NullPointerException.class,
                    () -> builder.imports((String[]) null));
            NullPointerException element = assertThrows(NullPointerException.class,
                    () -> builder.imports((String) null));
            assertEquals("names must not be null", array.getMessage());
            assertEquals("names must not contain null", element.getMessage());
        }

        @Test
        @DisplayName("imports((Collection) null) throws NullPointerException")
        void nullImportCollectionThrows() {
            RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> builder.imports((java.util.Collection<String>) null));
            assertEquals("names must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("imports with null element throws NullPointerException with a message")
        void nullImportElementThrows() {
            RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);
            // Set.of() doesn't accept nulls, use Arrays.asList to allow one
            Set<String> withNull = new java.util.HashSet<>(Arrays.asList("java.util", null));

            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> builder.imports(withNull));
            assertEquals("names must not contain null", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("fact names a run rejects")
    class RejectedFactNames {

        @Test
        @DisplayName("a fact named output is rejected, because actions bind the output object to that name")
        void outputIsReserved() {
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
            engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));
            FactStore<Object> output = new FactMap<>();
            output.setValue("output", 1);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(output));

            assertEquals("'output' is reserved for the output object and cannot be used as a fact name",
                    ex.getMessage());
        }
    }
}
