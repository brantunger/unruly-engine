package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

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

    @Test
    @DisplayName("a public all-args constructor also takes the language, after the other fields")
    void publicAllArgsConstructorWithLanguage() throws ReflectiveOperationException {
        Constructor<Rule> constructor = Rule.class.getConstructor(
                String.class, String.class, String.class, Integer.class, String.class, String.class);
        Rule expected = Rule.builder()
                .ruleName("r").condition("true").action("output.put('k', 1)").priority(1).description("d")
                .language("toy").build();

        assertEquals(expected, constructor.newInstance("r", "true", "output.put('k', 1)", 1, "d", "toy"));
    }

    @Test
    @DisplayName("both positional constructors are @Deprecated(since = \"1.4.0\", forRemoval = true)")
    void positionalConstructorsDeprecatedForRemoval() throws NoSuchMethodException {
        List<Class<?>[]> signatures = List.of(
                new Class<?>[] {String.class, String.class, String.class, Integer.class, String.class},
                new Class<?>[] {String.class, String.class, String.class, Integer.class, String.class, String.class});

        for (Class<?>[] parameters : signatures) {
            String name = parameters.length + "-argument constructor";
            Deprecated deprecated = Rule.class.getConstructor(parameters).getAnnotation(Deprecated.class);

            assertNotNull(deprecated, name + " isn't deprecated");
            assertTrue(deprecated.forRemoval(), name + " isn't marked for removal");
            assertEquals("1.4.0", deprecated.since(), name);
        }
    }

    @Test
    @DisplayName("the no-arg constructor that JSON and configuration binders use isn't deprecated")
    void noArgConstructorNotDeprecated() throws NoSuchMethodException {
        assertNull(Rule.class.getConstructor().getAnnotation(Deprecated.class));
    }
}
