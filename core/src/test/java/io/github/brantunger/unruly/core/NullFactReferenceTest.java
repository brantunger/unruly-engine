package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a null FactReference is bound as a null fact")
class NullFactReferenceTest {

    private static StatelessRulesEngine<Map<String, Object>> engine() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.language(new ToyExpressionLanguage()));
        engine.load(List.of(Rule.builder().ruleName("is-null").condition("x == null")
                .action("put isNull true").build()));
        return engine;
    }

    @Test
    @DisplayName("a rule sees a fact stored as a null reference, with the value null")
    void nullReferenceBoundAsNull() {
        FactMap<Object> facts = new FactMap<>();
        facts.put("x", null);

        assertEquals(Map.of("isNull", true), engine().run(facts));
    }

    @Test
    @DisplayName("a null reference behaves like a Fact holding null")
    void sameAsFactHoldingNull() {
        FactMap<Object> nullReference = new FactMap<>();
        nullReference.put("x", null);

        assertEquals(engine().run(new FactMap<>(new Fact<>("x", null))), engine().run(nullReference));
    }
}
