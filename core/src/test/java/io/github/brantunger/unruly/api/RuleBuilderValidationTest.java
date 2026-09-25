package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
    @DisplayName("validTo must be after validFrom: an empty or reversed window is rejected")
    void windowMustNotBeEmpty() {
        Instant start = Instant.parse("2027-06-01T00:00:00Z");

        assertEquals("validTo must be after validFrom, but validFrom is 2027-06-01T00:00:00Z and validTo is "
                + "2027-06-01T00:00:00Z", rejection(complete().validFrom(start).validTo(start)));
        assertEquals("validTo must be after validFrom, but validFrom is 2027-06-01T00:00:00Z and validTo is "
                + "2027-05-31T23:59:59Z", rejection(complete().validFrom(start).validTo(start.minusSeconds(1))));
        assertEquals(start.plusNanos(1), complete().validFrom(start).validTo(start.plusNanos(1)).build().getValidTo());
        assertEquals(start, complete().validTo(start).build().getValidTo(), "an end alone is a window");
        assertEquals(start, complete().validFrom(start).build().getValidFrom(), "a start alone is a window");
    }

    @Test
    @DisplayName("a tag must not be null or blank")
    void tagsMustBeNames() {
        assertEquals("tags must not contain null, but were [eu, null]",
                rejection(complete().tags(Arrays.asList("eu", null))));
        assertEquals("tags must not contain a blank tag, but were [eu, \\t]",
                rejection(complete().tags(List.of("eu", "\t"))));
    }

    @Test
    @DisplayName("null means the default for enabled and tags, as for the other optional fields")
    void nullMeansTheDefault() {
        Rule rule = complete().enabled(false).tags(List.of("eu")).enabled(null).tags(null).build();

        assertTrue(rule.isEnabled());
        assertEquals(Set.of(), rule.getTags());
    }

    @Test
    @DisplayName("a blank condition or action is built, and load() rejects it, naming the rule")
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

    @Test
    @DisplayName("the builder's constructor is public, so a binder such as a Jackson mix-in can create the builder")
    void constructorPublic() throws Exception {
        Constructor<Rule.RuleBuilder> constructor = Rule.RuleBuilder.class.getDeclaredConstructor();

        assertTrue(Modifier.isPublic(constructor.getModifiers()));
        Rule rule = constructor.newInstance().ruleName("r").condition("true").action("output.put('k', 1)").build();
        assertEquals(complete().build(), rule);
    }
}
