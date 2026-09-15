package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rule.RuleBuilder.build() rejects an incomplete rule")
class RuleBuilderValidationTest {

    private static Rule.RuleBuilder complete() {
        return Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)");
    }

    private static String rejection(Rule.RuleBuilder builder) {
        return assertThrows(IllegalStateException.class, builder::build).getMessage();
    }

    @Test
    @DisplayName("a rule needs a name, so errors, listeners and results can always say which rule it was")
    void nameRequired() {
        assertEquals("ruleName must not be null", rejection(complete().ruleName(null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    @DisplayName("a blank name is rejected too")
    void blankNameRejected(String name) {
        assertEquals("ruleName must not be blank", rejection(complete().ruleName(name)));
    }

    @Test
    @DisplayName("a rule needs a condition and an action")
    void conditionAndActionRequired() {
        assertEquals("condition must not be null", rejection(complete().condition(null)));
        assertEquals("action must not be null", rejection(complete().action(null)));
    }

    @Test
    @DisplayName("an empty builder names the rule's name first")
    void nameCheckedFirst() {
        assertEquals("ruleName must not be null", rejection(Rule.builder()));
        assertEquals("condition must not be null", rejection(Rule.builder().ruleName("r")));
    }

    @Test
    @DisplayName("a blank condition or action is built, and setRuleList() rejects it, naming the rule")
    void blankExpressionsLeftToTheEngine() {
        Rule rule = complete().condition(" ").action("").build();

        assertEquals(" ", rule.getCondition());
        assertEquals("", rule.getAction());
    }

    @Test
    @DisplayName("the builder can be completed and used again after a rejection")
    void builderReusable() {
        Rule.RuleBuilder builder = Rule.builder().condition("true").action("x");
        assertThrows(IllegalStateException.class, builder::build);

        assertEquals("r", builder.ruleName("r").build().getRuleName());
    }
}
