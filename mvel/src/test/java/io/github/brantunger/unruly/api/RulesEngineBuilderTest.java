package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RulesEngineBuilderTest {

    private static final List<Rule> TWO_MATCHING_RULES = List.of(
            Rule.builder().ruleName("high").priority(2).condition("true").action("output.put('high', true)").build(),
            Rule.builder().ruleName("low").priority(1).condition("true").action("output.put('low', true)").build());

    @Test
    @DisplayName("stateless() builds a stateless engine, which fires only the highest-priority match")
    void testStateless() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateless(HashMap::new);
        engine.setRuleList(TWO_MATCHING_RULES);

        assertEquals("StatelessRulesEngine", engine.getClass().getSimpleName());
        assertEquals(Map.of("high", true), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("stateful() builds a stateful engine, which fires every match")
    void testStateful() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);
        engine.setRuleList(TWO_MATCHING_RULES);

        assertEquals("StatefulRulesEngine", engine.getClass().getSimpleName());
        assertEquals(Map.of("high", true, "low", true), engine.run(new FactMap<>()));
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
