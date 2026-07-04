package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rule")
class RuleTest {

    @Test
    @DisplayName("builder creates rule with all fields")
    void builderCreatesRuleWithAllFields() {
        Rule rule = Rule.builder()
                .ruleName("test-rule")
                .condition("input > 10")
                .action("output.setResult(true)")
                .priority(1)
                .description("A test rule")
                .build();

        assertEquals("test-rule", rule.getRuleName());
        assertEquals("input > 10", rule.getCondition());
        assertEquals("output.setResult(true)", rule.getAction());
        assertEquals(1, rule.getPriority());
        assertEquals("A test rule", rule.getDescription());
    }

    @Test
    @DisplayName("builder defaults to null fields")
    void builderDefaultsToNull() {
        Rule rule = Rule.builder().build();

        assertNull(rule.getRuleName());
        assertNull(rule.getCondition());
        assertNull(rule.getAction());
        assertNull(rule.getPriority());
        assertNull(rule.getDescription());
    }

    @Test
    @DisplayName("setter methods work via Lombok @Data")
    void setterMethodsWork() {
        Rule rule = Rule.builder().build();

        rule.setRuleName("updated");
        rule.setPriority(5);

        assertEquals("updated", rule.getRuleName());
        assertEquals(5, rule.getPriority());
    }

    @Nested
    @DisplayName("setters for all fields")
    class AllSetters {

        @Test
        @DisplayName("setCondition and setAction update fields")
        void setConditionAndAction() {
            Rule rule = Rule.builder().build();

            rule.setCondition("x > 0");
            rule.setAction("output.put(\"k\", \"v\")");
            rule.setDescription("desc");

            assertEquals("x > 0", rule.getCondition());
            assertEquals("output.put(\"k\", \"v\")", rule.getAction());
            assertEquals("desc", rule.getDescription());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("equal rules are equal")
        void equalRulesAreEqual() {
            Rule rule1 = Rule.builder()
                    .ruleName("r").condition("c").action("a").priority(1).description("d")
                    .build();
            Rule rule2 = Rule.builder()
                    .ruleName("r").condition("c").action("a").priority(1).description("d")
                    .build();

            assertEquals(rule1, rule2);
            assertEquals(rule1.hashCode(), rule2.hashCode());
        }

        @Test
        @DisplayName("different rules are not equal")
        void differentRulesAreNotEqual() {
            Rule rule1 = Rule.builder().ruleName("r1").priority(1).build();
            Rule rule2 = Rule.builder().ruleName("r2").priority(2).build();

            assertNotEquals(rule1, rule2);
        }

        @Test
        @DisplayName("rule is not equal to null")
        void notEqualToNull() {
            Rule rule = Rule.builder().ruleName("r").build();

            assertNotEquals(null, rule);
        }

        @Test
        @DisplayName("rule is equal to itself")
        void equalToSelf() {
            Rule rule = Rule.builder().ruleName("r").build();

            assertEquals(rule, rule);
        }

        @Test
        @DisplayName("rule is not equal to different type")
        void notEqualToDifferentType() {
            Rule rule = Rule.builder().ruleName("r").build();

            assertNotEquals("string", rule);
        }

        @Test
        @DisplayName("rules with all null fields are equal")
        void allNullFieldsAreEqual() {
            Rule rule1 = Rule.builder().build();
            Rule rule2 = Rule.builder().build();

            assertEquals(rule1, rule2);
            assertEquals(rule1.hashCode(), rule2.hashCode());
        }

        @Test
        @DisplayName("rules differing in one field are not equal")
        void differingInOneField() {
            Rule base = Rule.builder().ruleName("r").condition("c").action("a").priority(1).build();
            Rule diffCondition = Rule.builder().ruleName("r").condition("different").action("a").priority(1).build();

            assertNotEquals(base, diffCondition);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("toString contains field values")
        void toStringContainsFields() {
            Rule rule = Rule.builder()
                    .ruleName("test")
                    .condition("x > 0")
                    .action("output.put(\"k\", \"v\")")
                    .priority(1)
                    .description("desc")
                    .build();

            String str = rule.toString();

            assertTrue(str.contains("test"));
            assertTrue(str.contains("x > 0"));
            assertTrue(str.contains("1"));
            assertTrue(str.contains("desc"));
        }

        @Test
        @DisplayName("toString does not throw with null fields")
        void toStringWithNulls() {
            Rule rule = Rule.builder().build();

            assertDoesNotThrow(() -> rule.toString());
        }
    }

    @Nested
    @DisplayName("builder toString")
    class BuilderToString {

        @Test
        @DisplayName("builder toString does not throw")
        void builderToStringDoesNotThrow() {
            assertDoesNotThrow(() -> Rule.builder().ruleName("r").toString());
        }
    }
}

