package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rule")
class RuleTest {

    /** A builder with only the required fields set. */
    private static Rule.RuleBuilder required() {
        return Rule.builder().ruleName("r").condition("c").action("a");
    }

    @Test
    @DisplayName("builder creates rule with all fields")
    void builderCreatesRuleWithAllFields() {
        Rule rule = Rule.builder()
                .ruleName("test-rule")
                .condition("input > 10")
                .action("output.setResult(true)")
                .priority(1)
                .description("A test rule")
                .language("toy")
                .build();

        assertEquals("test-rule", rule.getRuleName());
        assertEquals("input > 10", rule.getCondition());
        assertEquals("output.setResult(true)", rule.getAction());
        assertEquals(1, rule.getPriority());
        assertEquals("A test rule", rule.getDescription());
        assertEquals("toy", rule.getLanguage());
    }

    @Test
    @DisplayName("the optional fields default to null")
    void optionalFieldsDefaultToNull() {
        Rule rule = required().build();

        assertNull(rule.getPriority());
        assertNull(rule.getDescription());
        assertNull(rule.getLanguage());
    }

    @Test
    @DisplayName("a rule is enabled, with no validity window and no tags, unless they're set")
    void selectionFieldsDefault() {
        Rule rule = required().build();

        assertTrue(rule.isEnabled());
        assertNull(rule.getValidFrom());
        assertNull(rule.getValidTo());
        assertEquals(Set.of(), rule.getTags());
    }

    @Test
    @DisplayName("enabled, the window and the tags are kept, copied by toBuilder, and the tags can't be changed")
    void selectionFieldsKept() {
        Instant from = Instant.parse("2027-06-01T00:00:00Z");
        Instant to = Instant.parse("2027-09-01T00:00:00Z");
        List<String> given = new ArrayList<>(List.of("retail", "eu", "eu"));
        Rule rule = required().enabled(false).validFrom(from).validTo(to).tags(given).build();
        given.add("uk");

        assertFalse(rule.isEnabled());
        assertEquals(from, rule.getValidFrom());
        assertEquals(to, rule.getValidTo());
        assertEquals(List.of("eu", "retail"), List.copyOf(rule.getTags()), "in String order, each tag once, copied");
        assertThrows(UnsupportedOperationException.class, () -> rule.getTags().add("uk"));
        assertEquals(rule, rule.toBuilder().build());
        assertTrue(rule.toBuilder().enabled(true).build().isEnabled());
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("equal rules are equal")
        void equalRulesAreEqual() {
            Rule rule1 = required().priority(1).description("d").build();
            Rule rule2 = required().priority(1).description("d").build();

            assertEquals(rule1, rule2);
            assertEquals(rule1.hashCode(), rule2.hashCode());
        }

        @Test
        @DisplayName("different rules are not equal")
        void differentRulesAreNotEqual() {
            Rule rule1 = required().ruleName("r1").priority(1).build();
            Rule rule2 = required().ruleName("r2").priority(2).build();

            assertNotEquals(rule1, rule2);
        }

        @Test
        @DisplayName("rule is not equal to null")
        void notEqualToNull() {
            assertNotEquals(null, required().build());
        }

        @Test
        @DisplayName("rule is equal to itself")
        void equalToSelf() {
            Rule rule = required().build();

            assertEquals(rule, rule);
        }

        @Test
        @DisplayName("rule is not equal to different type")
        void notEqualToDifferentType() {
            assertNotEquals("string", required().build());
        }

        @Test
        @DisplayName("rules with only the required fields set are equal")
        void onlyRequiredFieldsAreEqual() {
            Rule rule1 = required().build();
            Rule rule2 = required().build();

            assertEquals(rule1, rule2);
            assertEquals(rule1.hashCode(), rule2.hashCode());
        }

        @Test
        @DisplayName("rules differing in one field are not equal")
        void differingInOneField() {
            Rule base = required().priority(1).build();
            Rule diffCondition = required().condition("different").priority(1).build();

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
        @DisplayName("toString shows unset optional fields as null")
        void toStringWithNulls() {
            assertEquals("Rule(ruleName=r, condition=c, action=a, priority=null, description=null, language=null, "
                    + "enabled=true, validFrom=null, validTo=null, tags=[])", required().build().toString());
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
