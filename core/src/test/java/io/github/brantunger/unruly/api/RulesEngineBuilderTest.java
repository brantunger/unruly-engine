package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RulesEngineBuilderTest {

    private static final List<Rule> TWO_MATCHING_RULES = List.of(
            Rule.builder().ruleName("high").priority(2).condition("true").action("put high true").build(),
            Rule.builder().ruleName("low").priority(1).condition("true").action("put low true").build());

    @Test
    @DisplayName("firstMatch() builds an engine that fires only the highest-priority match")
    void testFirstMatch() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        engine.load(TWO_MATCHING_RULES);

        assertEquals("StatelessRulesEngine", engine.getClass().getSimpleName());
        assertEquals(Map.of("high", true), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("allMatches() builds an engine that fires every match")
    void testAllMatches() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        engine.load(TWO_MATCHING_RULES);

        assertEquals("StatefulRulesEngine", engine.getClass().getSimpleName());
        assertEquals(Map.of("high", true, "low", true), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a builder is created only with firstMatch(), allMatches() or uniqueMatch(): its constructors are"
            + " private")
    void testPrivateConstructors() {
        for (Constructor<?> constructor : RulesEngineBuilder.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()), constructor.toString());
        }
    }
}
