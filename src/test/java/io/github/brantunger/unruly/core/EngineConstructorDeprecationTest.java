package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the engines' public constructors are deprecated for removal in favour of RulesEngineBuilder")
class EngineConstructorDeprecationTest {

    private static final List<Class<?>> ENGINES = List.of(StatelessRulesEngine.class, StatefulRulesEngine.class);

    @Test
    @DisplayName("each engine's public constructor is @Deprecated(since = \"1.3.0\", forRemoval = true)")
    void constructorsDeprecatedForRemoval() throws NoSuchMethodException {
        for (Class<?> engine : ENGINES) {
            Deprecated deprecated = engine.getConstructor(Supplier.class).getAnnotation(Deprecated.class);

            assertNotNull(deprecated, engine.getSimpleName() + "'s constructor isn't deprecated");
            assertTrue(deprecated.forRemoval(), engine.getSimpleName() + "'s constructor isn't marked for removal");
            assertEquals("1.3.0", deprecated.since(), engine.getSimpleName());
        }
    }

    @Test
    @DisplayName("the engine classes and the builder methods that replace the constructors aren't deprecated")
    void classesAndBuilderNotDeprecated() throws NoSuchMethodException {
        for (Class<?> engine : ENGINES) {
            assertNull(engine.getAnnotation(Deprecated.class), engine.getSimpleName());
        }
        assertNull(AbstractRulesEngine.class.getAnnotation(Deprecated.class));
        assertNull(RulesEngineBuilder.class.getMethod("stateless", Supplier.class).getAnnotation(Deprecated.class));
        assertNull(RulesEngineBuilder.class.getMethod("stateful", Supplier.class).getAnnotation(Deprecated.class));
    }
}
