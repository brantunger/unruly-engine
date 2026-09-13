package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses reflection, as JSON and configuration binders do, so the test also compiles against a Rule without
 * public constructors.
 */
@DisplayName("Rule constructors")
class RuleConstructorTest {

    private static final Rule EXPECTED = Rule.builder()
            .ruleName("r").condition("true").action("output.put('k', 1)").priority(1).description("d").build();

    @Test
    @DisplayName("a public no-arg constructor lets frameworks create a Rule and fill it with setters")
    void publicNoArgConstructor() throws ReflectiveOperationException {
        Constructor<Rule> constructor = Rule.class.getConstructor();

        Rule rule = constructor.newInstance();
        rule.setRuleName("r");
        rule.setCondition("true");
        rule.setAction("output.put('k', 1)");
        rule.setPriority(1);
        rule.setDescription("d");

        assertEquals(EXPECTED, rule);
    }

    @Test
    @DisplayName("a public all-args constructor takes the fields in declaration order")
    void publicAllArgsConstructor() throws ReflectiveOperationException {
        Constructor<Rule> constructor = Rule.class.getConstructor(
                String.class, String.class, String.class, Integer.class, String.class);

        assertEquals(EXPECTED, constructor.newInstance("r", "true", "output.put('k', 1)", 1, "d"));
    }
}
