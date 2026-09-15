package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what 1.x releases returned from {@code equals}, {@code hashCode} and {@code toString}, so users' logs, hashed
 * collections and equality checks don't change.
 */
@DisplayName("Rule's equals, hashCode and toString keep the behavior of earlier releases")
class RuleObjectMethodsTest {

    private static Rule full() {
        return Rule.builder()
                .ruleName("prime-rate").condition("applicant.score >= 750").action("output.rate = 4.5")
                .priority(10).description("Prime").language("mvel").build();
    }

    @Test
    @DisplayName("toString lists every field in declaration order")
    void toStringFormat() {
        assertEquals("Rule(ruleName=prime-rate, condition=applicant.score >= 750, action=output.rate = 4.5, "
                + "priority=10, description=Prime, language=mvel)", full().toString());
    }

    @Test
    @DisplayName("the builder's toString lists every field set so far")
    void builderToStringFormat() {
        assertEquals("Rule.RuleBuilder(ruleName=prime-rate, condition=applicant.score >= 750, "
                + "action=output.rate = 4.5, priority=10, description=Prime, language=mvel)", full().toBuilder().toString());
        assertEquals("Rule.RuleBuilder(ruleName=null, condition=null, action=null, priority=null, description=null, "
                + "language=null)", Rule.builder().toString());
    }

    @Test
    @DisplayName("hashCode returns the same values as earlier releases")
    void hashCodeValues() {
        assertEquals(-1798543528, full().hashCode());
        assertEquals(-144318294, Rule.builder().ruleName("r").condition("true").action("x").build().hashCode());
    }

    @Test
    @DisplayName("rules that differ in any one field, including one null and one not, aren't equal")
    void differentInOneField() {
        Map<String, UnaryOperator<Rule.RuleBuilder>> changes = new LinkedHashMap<>();
        changes.put("ruleName", builder -> builder.ruleName("other"));
        changes.put("condition", builder -> builder.condition("false"));
        changes.put("action", builder -> builder.action("output.rate = 5"));
        changes.put("priority", builder -> builder.priority(11));
        changes.put("priority null", builder -> builder.priority(null));
        changes.put("description", builder -> builder.description("other"));
        changes.put("description null", builder -> builder.description(null));
        changes.put("language", builder -> builder.language("toy"));
        changes.put("language null", builder -> builder.language(null));

        changes.forEach((name, change) -> {
            Rule changed = change.apply(full().toBuilder()).build();

            assertNotEquals(full(), changed, name);
            assertNotEquals(changed, full(), name);
        });
        assertEquals(full(), full());
        assertEquals(full().hashCode(), full().hashCode());
    }

    @Test
    @DisplayName("a rule isn't equal to null or to an object of another type")
    void notEqualToNullOrOtherType() {
        // Called directly: assertNotEquals(null, rule) and assertNotEquals("text", rule) never call Rule.equals.
        assertFalse(full().equals(null));
        assertFalse(full().equals("prime-rate"));
    }
}
