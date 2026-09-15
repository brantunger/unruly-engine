package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;

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

        assertInstanceOf(StatelessRulesEngine.class, Engines.stateless(Object::new));
        assertInstanceOf(StatefulRulesEngine.class, Engines.stateful(Object::new));
        assertInstanceOf(StatelessRulesEngine.class, Engines.stateless(Object::new, 2));
        assertInstanceOf(StatefulRulesEngine.class, Engines.stateful(Object::new, 2));
    }
}
