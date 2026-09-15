package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Fact copy constructor with a narrower source type")
class FactCopyConstructorTest {

    @Test
    @DisplayName("copying a Fact<String> into a Fact<Object> copies its name and value")
    void copiesNameAndValue() {
        Fact<String> source = new Fact<>("x", "hi");

        Fact<Object> copy = new Fact<Object>(source);

        assertEquals("x", copy.getName());
        assertEquals("hi", copy.getValue());
    }

    @Test
    @DisplayName("a rule can refer to the copied fact by its name")
    void copiedFactUsableInRules() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateless(HashMap::new);
        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("x == 'hi'")
                .action("output.put('hit', true)").build()));

        FactMap<Object> facts = new FactMap<>(new Fact<Object>(new Fact<>("x", "hi")));

        assertEquals(Map.of("hit", true), engine.run(facts));
    }
}
