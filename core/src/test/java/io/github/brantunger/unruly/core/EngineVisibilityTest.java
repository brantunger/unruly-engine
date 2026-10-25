package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the engines are internal: they can only be created through RulesEngineBuilder")
class EngineVisibilityTest {

    @Test
    @DisplayName("the engine class isn't public, is final and has no public constructor")
    void engineIsPackagePrivate() {
        assertFalse(Modifier.isPublic(CoreRulesEngine.class.getModifiers()), "CoreRulesEngine is public");
        assertTrue(Modifier.isFinal(CoreRulesEngine.class.getModifiers()), "CoreRulesEngine isn't final");
        assertEquals(0, CoreRulesEngine.class.getConstructors().length, "CoreRulesEngine has a public constructor");
    }

    @Test
    @DisplayName("CoreRulesEngine has no JIT_PROPERTY constant")
    void noJitProperty() {
        assertThrows(NoSuchFieldException.class, () -> CoreRulesEngine.class.getDeclaredField("JIT_PROPERTY"));
    }

    @Test
    @DisplayName("Engines, the builder's entry point, is final, has no public constructor and creates each engine")
    void enginesEntryPoint() {
        assertTrue(Modifier.isFinal(Engines.class.getModifiers()));
        assertEquals(0, Engines.class.getConstructors().length);
        EngineConfiguration<Object> unlimited = TestConfigurations.engineConfiguration(
                Map.of(ToyExpressionLanguage.LANGUAGE_NAME, new ToyExpressionLanguage())).build();
        EngineConfiguration<Object> limited = TestConfigurations.engineConfiguration(
                        Map.of(ToyExpressionLanguage.LANGUAGE_NAME, new ToyExpressionLanguage()))
                .withCopyLimit(CopyLimit.of(2)).build();

        assertSame(MatchPolicy.FIRST_MATCH, policyOf(Engines.firstMatch(Object::new, unlimited)));
        assertSame(MatchPolicy.ALL_MATCHES, policyOf(Engines.allMatches(Object::new, unlimited)));
        assertSame(MatchPolicy.UNIQUE_MATCH, policyOf(Engines.uniqueMatch(Object::new, unlimited)));
        assertSame(MatchPolicy.FIRST_MATCH, policyOf(Engines.firstMatch(Object::new, limited)));
        assertSame(MatchPolicy.ALL_MATCHES, policyOf(Engines.allMatches(Object::new, limited)));
        assertSame(MatchPolicy.UNIQUE_MATCH, policyOf(Engines.uniqueMatch(Object::new, limited)));
    }

    private static MatchPolicy policyOf(Object engine) {
        return assertInstanceOf(CoreRulesEngine.class, engine).matchPolicy();
    }
}
