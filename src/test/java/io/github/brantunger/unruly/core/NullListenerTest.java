package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("null listener registration")
class NullListenerTest {

    private final StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

    @Test
    @DisplayName("registerListener(null) throws NullPointerException")
    void nullListenerRejected() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.registerListener(null));
        assertTrue(ex.getMessage().contains("listener must not be null"));
    }

    @Test
    @DisplayName("registerListeners(null) throws NullPointerException")
    void nullListRejected() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.registerListeners(null));
        assertTrue(ex.getMessage().contains("listeners must not be null"));
    }

    @Test
    @DisplayName("a list containing null is rejected and registers nothing")
    void nullElementRejectedAtomically() {
        AtomicInteger callbacks = new AtomicInteger();
        RuleListener counting = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                callbacks.incrementAndGet();
            }
        };

        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> engine.registerListeners(Arrays.asList(counting, null)));
        assertTrue(ex.getMessage().contains("listener element must not be null"));

        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));
        assertEquals(1, engine.run(new FactMap<>()).get("k"));
        assertEquals(0, callbacks.get(), "the valid listener before the null must not have been registered");
    }
}
