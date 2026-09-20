package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses reflection, so the test compiles against any {@code Rule}, whatever members it has.
 */
@DisplayName("Rule is immutable")
class RuleImmutabilityTest {

    @Test
    @DisplayName("Rule and its builder are final classes")
    void finalClasses() {
        assertTrue(Modifier.isFinal(Rule.class.getModifiers()), "Rule isn't final");
        assertTrue(Modifier.isFinal(Rule.RuleBuilder.class.getModifiers()), "Rule.RuleBuilder isn't final");
    }

    @Test
    @DisplayName("every field of a rule is private and final")
    void privateFinalFields() {
        for (Field field : Rule.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()), field + " isn't private");
            assertTrue(Modifier.isFinal(field.getModifiers()), field + " isn't final");
        }
    }

    @Test
    @DisplayName("a rule can only be created with the builder: it has no public or protected constructor")
    void noConstructors() {
        List<Constructor<?>> visible = Arrays.stream(Rule.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers())
                        || Modifier.isProtected(constructor.getModifiers()))
                .toList();
        assertEquals(List.of(), visible);
    }

    @Test
    @DisplayName("a rule has no setters and no canEqual")
    void noSetters() {
        List<String> removed = Arrays.stream(Rule.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set") || name.equals("canEqual"))
                .toList();
        assertEquals(List.of(), removed);
    }
}
