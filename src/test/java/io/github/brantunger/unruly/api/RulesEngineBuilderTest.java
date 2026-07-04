package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RulesEngineBuilderTest {

    @Test
    @DisplayName("Test stateless builder")
    void testStateless() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateless(HashMap::new);
        assertNotNull(engine);
    }

    @Test
    @DisplayName("Test stateful builder")
    void testStateful() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);
        assertNotNull(engine);
    }
    
    @Test
    @DisplayName("Test private constructor")
    void testPrivateConstructor() throws Exception {
        Constructor<RulesEngineBuilder> constructor = RulesEngineBuilder.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
