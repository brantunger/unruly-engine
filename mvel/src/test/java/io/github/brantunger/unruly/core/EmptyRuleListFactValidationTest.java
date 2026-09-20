package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.RulesEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("facts are validated even when the rule list is empty")
class EmptyRuleListFactValidationTest {

    private static RulesEngine<Map<String, Object>> withNoRules(RulesEngine<Map<String, Object>> engine) {
        engine.load(List.of());
        return engine;
    }

    @ParameterizedTest(name = "stateless: {0}")
    @ValueSource(strings = {"output", "my-fact", "empty"})
    void statelessRejectsInvalidFact(String name) {
        RulesEngine<Map<String, Object>> engine = withNoRules(TestEngines.firstMatch(HashMap::new));

        assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(new Fact<>(name, 1))));
    }

    @ParameterizedTest(name = "stateful: {0}")
    @ValueSource(strings = {"output", "my-fact", "empty"})
    void statefulRejectsInvalidFact(String name) {
        RulesEngine<Map<String, Object>> engine = withNoRules(TestEngines.allMatches(HashMap::new));

        assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(new Fact<>(name, 1))));
    }

    @Test
    @DisplayName("valid facts with no rules still return null")
    void validFactsReturnNull() {
        assertNull(withNoRules(TestEngines.firstMatch(HashMap::new)).run(new FactMap<>(new Fact<>("claim", 1))));
        assertNull(withNoRules(TestEngines.allMatches(HashMap::new)).run(new FactMap<>(new Fact<>("claim", 1))));
    }
}
