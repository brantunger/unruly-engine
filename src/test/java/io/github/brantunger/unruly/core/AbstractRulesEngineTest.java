package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder()
                    .ruleName("dummy")
                    .condition("true")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();
            engine.setRuleList(List.of(rule));

            NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.run(null));
            assertTrue(ex.getMessage().contains("facts must not be null"));
        }

        @Test
        @DisplayName("StatelessRulesEngine throws NullPointerException for null facts")
        void statelessThrowsForNullFacts() {
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder()
                    .ruleName("dummy")
                    .condition("true")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();
            engine.setRuleList(List.of(rule));

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
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

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

            engine.setRuleList(Arrays.asList(withPriority, noPriority));

            FactStore<Object> facts = new FactMap<>();

            Map<String, Object> result = engine.run(facts);

            // Null-priority rule should fire last (lowest priority) and overwrite
            assertEquals("no-priority", result.get("last"));
        }

        @Test
        @DisplayName("multiple rules with null priority do not throw")
        void multipleNullPrioritiesDoNotThrow() {
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

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

            assertDoesNotThrow(() -> engine.setRuleList(Arrays.asList(rule1, rule2)));

            FactStore<Object> facts = new FactMap<>();
            Map<String, Object> result = engine.run(facts);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("addImports accumulation")
    class ImportsAccumulation {

        @Test
        @DisplayName("addImports does not discard previously added imports via addImport")
        void addImportsDoesNotDiscardPriorAddImport() {
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

            // First add via addImport
            engine.addImport("java.util");

            // Then add more via addImports — should NOT discard java.util
            engine.addImports(Set.of("java.time"));

            // This rule uses Objects from java.util — should still work
            Rule rule = Rule.builder()
                    .ruleName("uses-both")
                    .condition("Objects.nonNull(name)")
                    .action("output.put(\"result\", true)")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("name", "test");

            Map<String, Object> result = engine.run(facts);
            assertEquals(true, result.get("result"));
        }

        @Test
        @DisplayName("multiple addImports calls accumulate")
        void multipleAddImportsCallsAccumulate() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            engine.addImports(Set.of("java.util"));
            engine.addImports(Set.of("java.time"));

            Rule rule = Rule.builder()
                    .ruleName("uses-objects")
                    .condition("Objects.nonNull(name)")
                    .action("output.put(\"valid\", true)")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

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
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            Rule rule = Rule.builder()
                    .ruleName("bad-syntax")
                    .condition("this is invalid MVEL %$#")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();

            RuleCompilationException ex = assertThrows(RuleCompilationException.class, 
                    () -> engine.setRuleList(List.of(rule)));
            assertTrue(ex.getMessage().contains("bad-syntax"));
        }

        @Test
        @DisplayName("condition evaluation error is thrown and logged")
        void conditionEvaluationErrorThrown() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            // This condition references a variable not in facts
            Rule rule = Rule.builder()
                    .ruleName("missing-var")
                    .condition("nonExistentVariable > 5")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
            assertTrue(ex.getMessage().contains("missing-var"));
        }

        @Test
        @DisplayName("action evaluation error is thrown and logged")
        void actionEvaluationErrorThrown() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            Rule rule = Rule.builder()
                    .ruleName("bad-action")
                    .condition("true")
                    .action("output.callNonExistentMethod()")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
            assertTrue(ex.getMessage().contains("bad-action"));
        }
    }

    @Nested
    @DisplayName("null fact values")
    class NullFactValues {

        @Test
        @DisplayName("facts with null values do not cause NPE in condition evaluation")
        void nullFactValueDoesNotCauseNPE() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            Rule rule = Rule.builder()
                    .ruleName("always-true")
                    .condition("true")
                    .action("output.put(\"result\", \"ok\")")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

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
        @DisplayName("setRuleList throws NullPointerException for null ruleList")
        void setRuleListThrowsForNull() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.setRuleList(null));
            assertTrue(ex.getMessage().contains("ruleList must not be null"));
        }
    }

    @Nested
    @DisplayName("Rule condition validation")
    class RuleConditionValidation {

        @Test
        @DisplayName("null condition throws IllegalArgumentException naming the rule")
        void nullConditionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition(null)
                    .action("output.put(\"k\",1)").build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.setRuleList(List.of(rule)));
            assertTrue(ex.getMessage().contains("my-rule"), "error should name the offending rule");
            assertTrue(ex.getMessage().contains("condition"));
        }

        @Test
        @DisplayName("blank condition throws IllegalArgumentException naming the rule")
        void blankConditionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition("   ")
                    .action("output.put(\"k\",1)").build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.setRuleList(List.of(rule)));
            assertTrue(ex.getMessage().contains("my-rule"));
            assertTrue(ex.getMessage().contains("condition"));
        }

        @Test
        @DisplayName("empty string condition throws IllegalArgumentException")
        void emptyConditionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition("")
                    .action("output.put(\"k\",1)").build();

            assertThrows(IllegalArgumentException.class, () -> engine.setRuleList(List.of(rule)));
        }

        @Test
        @DisplayName("unnamed rule with null condition reports '(unnamed)' in error")
        void unnamedRuleWithNullConditionReportsUnnamed() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder().condition(null).action("output.put(\"k\",1)").build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.setRuleList(List.of(rule)));
            assertTrue(ex.getMessage().contains("(unnamed)"));
        }
    }

    @Nested
    @DisplayName("Rule action validation")
    class RuleActionValidation {

        @Test
        @DisplayName("null action throws IllegalArgumentException naming the rule")
        void nullActionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition("true").action(null).build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.setRuleList(List.of(rule)));
            assertTrue(ex.getMessage().contains("my-rule"));
            assertTrue(ex.getMessage().contains("action"));
        }

        @Test
        @DisplayName("blank action throws IllegalArgumentException naming the rule")
        void blankActionThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            Rule rule = Rule.builder().ruleName("my-rule").condition("true").action("  ").build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.setRuleList(List.of(rule)));
            assertTrue(ex.getMessage().contains("my-rule"));
            assertTrue(ex.getMessage().contains("action"));
        }
    }

    @Nested
    @DisplayName("addImport validation")
    class AddImportValidation {

        @Test
        @DisplayName("addImport(null) throws NullPointerException with a message")
        void addImportNullThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> engine.addImport(null));
            assertTrue(ex.getMessage().contains("packageString must not be null"));
        }

        @Test
        @DisplayName("addImports(null) throws NullPointerException")
        void addImportsNullSetThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

            assertThrows(NullPointerException.class, () -> engine.addImports(null));
        }

        @Test
        @DisplayName("addImports with null element throws NullPointerException with a message")
        void addImportsNullElementThrows() {
            StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
            // Set.of() doesn't accept nulls, use Arrays.asList to allow one
            Set<String> withNull = new java.util.HashSet<>(Arrays.asList("java.util", null));

            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> engine.addImports(withNull));
            assertTrue(ex.getMessage().contains("package element must not be null"));
        }
    }
}
