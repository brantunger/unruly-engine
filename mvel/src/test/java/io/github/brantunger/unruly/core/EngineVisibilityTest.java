package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the engines are internal: they can only be created through RulesEngineBuilder")
class EngineVisibilityTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {AbstractRulesEngine.class, StatelessRulesEngine.class, StatefulRulesEngine.class})
    @DisplayName("the engine classes aren't public and have no public constructor")
    void enginesArePackagePrivate(Class<?> engine) {
        assertFalse(Modifier.isPublic(engine.getModifiers()), engine.getSimpleName() + " is public");
        assertEquals(0, engine.getConstructors().length, engine.getSimpleName() + " has a public constructor");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {StatelessRulesEngine.class, StatefulRulesEngine.class})
    @DisplayName("the concrete engines are final")
    void concreteEnginesAreFinal(Class<?> engine) {
        assertTrue(Modifier.isFinal(engine.getModifiers()), engine.getSimpleName() + " isn't final");
    }

    @Test
    @DisplayName("AbstractRulesEngine has no JIT_PROPERTY constant")
    void noJitProperty() {
        assertThrows(NoSuchFieldException.class, () -> AbstractRulesEngine.class.getDeclaredField("JIT_PROPERTY"));
    }

    @Test
    @DisplayName("Engines, the builder's entry point, is final, has no public constructor and creates each engine")
    void enginesEntryPoint() {
        assertTrue(Modifier.isFinal(Engines.class.getModifiers()));
        assertEquals(0, Engines.class.getConstructors().length);
        EngineConfiguration<Object> unlimited = new EngineConfiguration<>(List.of(), null, List.of(), List.of(),
                CopyLimit.none(), null, Clock.systemUTC(), Object.class, OutputWriter.beansAndMaps(), Map.of(), Map.of(), false);
        EngineConfiguration<Object> limited = new EngineConfiguration<>(List.of(), null, List.of(), List.of(),
                CopyLimit.of(2), null, Clock.systemUTC(), Object.class, OutputWriter.beansAndMaps(), Map.of(), Map.of(), false);

        assertInstanceOf(StatelessRulesEngine.class, Engines.firstMatch(Object::new, unlimited));
        assertInstanceOf(StatefulRulesEngine.class, Engines.allMatches(Object::new, unlimited));
        assertInstanceOf(StatelessRulesEngine.class, Engines.firstMatch(Object::new, limited));
        assertInstanceOf(StatefulRulesEngine.class, Engines.allMatches(Object::new, limited));
    }
}
