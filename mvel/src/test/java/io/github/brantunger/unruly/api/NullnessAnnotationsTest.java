package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.core.Engines;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads the JSpecify annotations by reflection, as Kotlin and nullness checkers read them from the class files.
 */
@DisplayName("the public API declares its nullness with JSpecify")
class NullnessAnnotationsTest {

    @Test
    @DisplayName("the public API packages are @NullMarked, and the internal core package isn't")
    void publicPackagesNullMarked() {
        for (Class<?> type : List.of(Rule.class, UnrulyException.class, CompiledCondition.class,
                MvelExpressionLanguage.class, LanguageTestContexts.class)) {
            assertTrue(type.getPackage().isAnnotationPresent(NullMarked.class),
                    type.getPackageName() + " isn't @NullMarked");
        }
        assertFalse(Engines.class.getPackage().isAnnotationPresent(NullMarked.class));
    }

    @Test
    @DisplayName("run() returns @Nullable O and takes a FactStore<@Nullable Object>")
    void runNullness() throws NoSuchMethodException {
        Method run = RulesEngine.class.getMethod("run", FactStore.class);

        assertNullable(run.getAnnotatedReturnType());
        assertNullable(typeArgument(run.getAnnotatedParameterTypes()[0], 0));
    }

    @Test
    @DisplayName("a fact's value, a fact's name and a FactReference in a FactStore can be null")
    void factsNullness() throws NoSuchMethodException {
        for (Class<?> type : List.of(FactReference.class, FactStore.class, Fact.class, FactMap.class)) {
            assertNullable(type.getTypeParameters()[0].getAnnotatedBounds()[0]);
        }
        assertNullable(FactReference.class.getMethod("getName").getAnnotatedReturnType());
        assertNullable(FactStore.class.getMethod("getValue", String.class).getAnnotatedReturnType());
        assertNullable(typeArgument(FactStore.class.getAnnotatedInterfaces()[0], 1));
        assertNullable(FactMap.class.getMethod("get", Object.class).getAnnotatedParameterTypes()[0]);
        assertNullable(Fact.class.getMethod("equals", Object.class).getAnnotatedParameterTypes()[0]);
    }

    @Test
    @DisplayName("listeners and conditions see facts whose values can be null, and a condition may return null")
    void contextsNullness() throws NoSuchMethodException {
        assertNullable(typeArgument(RuleListener.class.getMethod("beforeEvaluate", Rule.class, Map.class)
                .getAnnotatedParameterTypes()[1], 1));
        assertNullable(typeArgument(RuleListener.class.getMethod("afterEvaluate", Rule.class, Map.class,
                boolean.class).getAnnotatedParameterTypes()[1], 1));
        assertNullable(typeArgument(EvaluationContext.class.getMethod("facts").getAnnotatedReturnType(), 1));
        assertNullable(CompiledCondition.class.getMethod("evaluate", EvaluationContext.class, Session.class)
                .getAnnotatedReturnType());
    }

    @Test
    @DisplayName("Rule's optional fields are @Nullable on its accessors, builder and equals, and its required ones aren't")
    void ruleNullness() throws NoSuchMethodException {
        Map<String, Class<?>> optional = Map.of("Priority", Integer.class, "Description", String.class,
                "Language", String.class);
        for (Map.Entry<String, Class<?>> field : optional.entrySet()) {
            String builderMethod = Character.toLowerCase(field.getKey().charAt(0)) + field.getKey().substring(1);
            assertNullable(Rule.class.getMethod("get" + field.getKey()).getAnnotatedReturnType());
            assertNullable(Rule.RuleBuilder.class.getMethod(builderMethod, field.getValue())
                    .getAnnotatedParameterTypes()[0]);
        }
        assertNullable(Rule.class.getMethod("equals", Object.class).getAnnotatedParameterTypes()[0]);
        for (String required : List.of("RuleName", "Condition", "Action")) {
            String builderMethod = Character.toLowerCase(required.charAt(0)) + required.substring(1);
            assertFalse(isNullable(Rule.class.getMethod("get" + required).getAnnotatedReturnType()),
                    "get" + required + "() is @Nullable");
            assertFalse(isNullable(Rule.RuleBuilder.class.getMethod(builderMethod, String.class)
                    .getAnnotatedParameterTypes()[0]), "RuleBuilder." + builderMethod + "() is @Nullable");
        }
    }

    @Test
    @DisplayName("an exception's message and cause can be null")
    void exceptionNullness() throws NoSuchMethodException {
        AnnotatedType[] parameters = UnrulyException.class.getConstructor(String.class, Throwable.class)
                .getAnnotatedParameterTypes();

        assertNullable(parameters[0]);
        assertNullable(parameters[1]);
    }

    private static AnnotatedType typeArgument(AnnotatedType type, int index) {
        return ((AnnotatedParameterizedType) type).getAnnotatedActualTypeArguments()[index];
    }

    private static boolean isNullable(AnnotatedType type) {
        return type.isAnnotationPresent(Nullable.class);
    }

    private static void assertNullable(AnnotatedType type) {
        assertTrue(isNullable(type), type + " isn't @Nullable");
    }
}
