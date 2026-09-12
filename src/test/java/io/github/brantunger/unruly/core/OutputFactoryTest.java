package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("output factory validation")
class OutputFactoryTest {

    private static final List<Function<Supplier<Map<String, Object>>, RulesEngine<Map<String, Object>>>> ENGINES =
            List.of(StatefulRulesEngine::new, StatelessRulesEngine::new);

    private static RulesEngine<Map<String, Object>> engineWith(
            Function<Supplier<Map<String, Object>>, RulesEngine<Map<String, Object>>> constructor,
            Supplier<Map<String, Object>> factory, String condition) {
        RulesEngine<Map<String, Object>> engine = constructor.apply(factory);
        engine.setRuleList(List.of(Rule.builder()
                .ruleName("first-rule")
                .condition(condition)
                .action("output.put('k', 1)")
                .priority(1)
                .build()));
        return engine;
    }

    @Test
    @DisplayName("a null factory is rejected by both constructors and the builder")
    void nullFactoryRejected() {
        NullPointerException stateful = assertThrows(NullPointerException.class,
                () -> new StatefulRulesEngine<Map<String, Object>>(null));
        assertTrue(stateful.getMessage().contains("outputFactory must not be null"));

        NullPointerException stateless = assertThrows(NullPointerException.class,
                () -> new StatelessRulesEngine<Map<String, Object>>(null));
        assertTrue(stateless.getMessage().contains("outputFactory must not be null"));

        assertThrows(NullPointerException.class, () -> RulesEngineBuilder.stateful(null));
        assertThrows(NullPointerException.class, () -> RulesEngineBuilder.stateless(null));
    }

    @Test
    @DisplayName("a factory that throws is reported as a factory failure with its cause")
    void throwingFactoryWrapped() {
        IllegalStateException boom = new IllegalStateException("no connection");
        for (var constructor : ENGINES) {
            RulesEngine<Map<String, Object>> engine = engineWith(constructor, () -> {
                throw boom;
            }, "true");

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
            assertTrue(ex.getMessage().startsWith("Output factory threw"));
            assertTrue(ex.getMessage().contains("no connection"));
            assertFalse(ex.getMessage().contains("first-rule"), "the rule is not to blame");
            assertSame(boom, ex.getCause());
        }
    }

    @Test
    @DisplayName("a factory that returns null is reported as a factory failure")
    void nullReturningFactoryRejected() {
        for (var constructor : ENGINES) {
            RulesEngine<Map<String, Object>> engine = engineWith(constructor, () -> null, "true");

            RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
            assertTrue(ex.getMessage().contains("Output factory returned null"));
            assertFalse(ex.getMessage().contains("first-rule"));
        }
    }

    @Test
    @DisplayName("the factory is not called when no rule matches")
    void factoryNotCalledWithoutMatch() {
        for (var constructor : ENGINES) {
            AtomicInteger calls = new AtomicInteger();
            RulesEngine<Map<String, Object>> engine = engineWith(constructor, () -> {
                calls.incrementAndGet();
                return null;
            }, "false");

            assertNull(engine.run(new FactMap<>()));
            assertEquals(0, calls.get());
        }
    }
}
