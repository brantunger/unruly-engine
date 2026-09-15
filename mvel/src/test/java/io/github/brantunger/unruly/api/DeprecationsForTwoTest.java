package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("1.8.0 deprecates the members 2.0 removes, and keeps their replacements undeprecated")
class DeprecationsForTwoTest {

    private static List<Executable> deprecatedMembers() throws NoSuchMethodException {
        return List.of(
                FactReference.class.getMethod("setName", String.class),
                FactReference.class.getMethod("setValue", Object.class),
                Fact.class.getMethod("setName", String.class),
                Fact.class.getMethod("setValue", Object.class));
    }

    @Test
    @DisplayName("the fact setters are deprecated for removal since 1.8.0")
    void deprecatedForRemoval() throws NoSuchMethodException {
        for (Executable member : deprecatedMembers()) {
            Deprecated deprecated = member.getAnnotation(Deprecated.class);

            assertNotNull(deprecated, member + " isn't deprecated");
            assertTrue(deprecated.forRemoval(), member + " isn't marked for removal");
            assertEquals("1.8.0", deprecated.since(), member.toString());
        }
    }

    @Test
    @DisplayName("the replacements aren't deprecated: the builder, toBuilder, the getters, Fact's constructor and FactStore.setValue")
    void replacementsNotDeprecated() throws NoSuchMethodException {
        Stream<Executable> replacements = Stream.concat(
                Stream.of(Rule.class.getMethod("builder"), Rule.class.getMethod("toBuilder"),
                        Rule.RuleBuilder.class.getConstructor(),
                        Fact.class.getConstructor(String.class, Object.class),
                        FactStore.class.getMethod("setValue", String.class, Object.class)),
                Stream.of(Rule.class.getMethods()).filter(method -> method.getName().startsWith("get")));

        replacements.forEach(member -> assertNull(member.getAnnotation(Deprecated.class), member.toString()));
        for (Method method : Rule.RuleBuilder.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(Deprecated.class), method.toString());
        }
    }

    @Test
    @DisplayName("RuleBuilder's constructor is public, so a binder such as a Jackson mix-in can create the builder")
    void ruleBuilderConstructorPublic() throws Exception {
        Constructor<Rule.RuleBuilder> constructor = Rule.RuleBuilder.class.getDeclaredConstructor();

        assertTrue(Modifier.isPublic(constructor.getModifiers()));
        Rule rule = constructor.newInstance().ruleName("bound").condition("true").action("x").build();
        assertEquals(Rule.builder().ruleName("bound").condition("true").action("x").build(), rule);
    }
}
