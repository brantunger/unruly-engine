package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedThenRethrown;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a fatal Error from the output supplier or a listener callback is logged at ERROR before it is rethrown")
class FatalErrorLogTest {

    private static final List<Rule> RULES = List.of(Rule.builder().ruleName("r").condition("true")
            .action("output.put('k', 1)").build());

    private static RulesEngine<Map<String, Object>> engine(Supplier<Map<String, Object>> output) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(output);
        engine.setRuleList(RULES);
        return engine;
    }

    @Test
    @DisplayName("an OutOfMemoryError thrown by the output supplier")
    void outputSupplierThrowsFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("factory oom");
        RulesEngine<Map<String, Object>> engine = engine(() -> {
            throw oom;
        });

        assertLoggedThenRethrown(oom, "Output factory threw java.lang.OutOfMemoryError: factory oom",
                () -> engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("an OutOfMemoryError that causes what the output supplier throws")
    void outputSupplierWrapsFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("factory oom");
        RulesEngine<Map<String, Object>> engine = engine(() -> {
            throw new IllegalStateException("no connection", oom);
        });

        assertLoggedThenRethrown(oom, "Output factory threw java.lang.IllegalStateException: no connection",
                () -> engine.run(new FactMap<>()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeEvaluate", "beforeExecute"})
    @DisplayName("an OutOfMemoryError from a before* callback names the callback and the rule, in the log and in onError")
    void beforeCallbackThrowsFatalError(String callback) {
        OutOfMemoryError oom = new OutOfMemoryError("listener oom");
        AtomicReference<RuleExecutionException> reported = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(HashMap::new);
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                if ("beforeEvaluate".equals(callback)) {
                    throw oom;
                }
            }

            @Override
            public void beforeExecute(Rule rule, Object output) {
                if ("beforeExecute".equals(callback)) {
                    throw oom;
                }
            }

            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                reported.set(error);
            }
        });
        String message = "A listener threw java.lang.OutOfMemoryError in " + callback + " for rule 'r'";

        assertLoggedThenRethrown(oom, message, () -> engine.run(new FactMap<>()));

        assertEquals(message, reported.get().getMessage());
        assertSame(oom, reported.get().getCause());
    }

    @Test
    @DisplayName("an OutOfMemoryError from afterEvaluate names the callback and the rule")
    void afterEvaluateThrowsFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("listener oom");
        RulesEngine<Map<String, Object>> engine = engine(HashMap::new);
        engine.registerListener(new RuleListener() {
            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                throw oom;
            }
        });

        assertLoggedThenRethrown(oom, "A listener threw java.lang.OutOfMemoryError in afterEvaluate for rule 'r'",
                () -> engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("an OutOfMemoryError that causes what afterExecute throws names the callback and the rule")
    void afterExecuteWrapsFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("listener oom");
        RulesEngine<Map<String, Object>> engine = engine(HashMap::new);
        engine.registerListener(new RuleListener() {
            @Override
            public void afterExecute(Rule rule, Object output) {
                throw new IllegalStateException("audit failed", oom);
            }
        });

        assertLoggedThenRethrown(oom, "A listener threw java.lang.OutOfMemoryError in afterExecute for rule 'r'",
                () -> engine.run(new FactMap<>()));
    }
}
