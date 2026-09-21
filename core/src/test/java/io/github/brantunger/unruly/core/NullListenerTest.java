package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("null listeners on the builder")
class NullListenerTest {

    private final RulesEngineBuilder<Map<String, Object>> builder =
            RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).language(new ToyExpressionLanguage());

    @Test
    @DisplayName("listener(null) throws NullPointerException")
    void nullListenerRejected() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> builder.listener(null));
        assertTrue(ex.getMessage().contains("listener must not be null"));
    }

    @Test
    @DisplayName("listeners(null) throws NullPointerException")
    void nullListRejected() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> builder.listeners(null));
        assertTrue(ex.getMessage().contains("listeners must not be null"));
    }

    @Test
    @DisplayName("a collection containing null is rejected and adds nothing")
    void nullElementRejectedAtomically() {
        AtomicInteger callbacks = new AtomicInteger();
        RuleListener counting = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                callbacks.incrementAndGet();
            }
        };

        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> builder.listeners(Arrays.asList(counting, null)));
        assertTrue(ex.getMessage().contains("listeners must not contain null"));

        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("put k 1").build()));
        assertEquals(1, engine.run(new FactMap<>()).get("k"));
        assertEquals(0, callbacks.get(), "the valid listener before the null must not have been added");
    }
}
