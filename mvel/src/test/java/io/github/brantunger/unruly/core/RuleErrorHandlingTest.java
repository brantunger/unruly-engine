package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Errors thrown while running rules")
class RuleErrorHandlingTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<RuleExecutionException> errors = new CopyOnWriteArrayList<>();

    private final RuleListener recorder = new RuleListener() {
        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            events.add("beforeEvaluate");
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            events.add("afterEvaluate");
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            events.add("beforeExecute");
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            events.add("afterExecute");
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            events.add("onError");
            errors.add(error);
        }
    };

    private StatefulRulesEngine<Map<String, Object>> engine(String condition, String action, RuleListener... more) {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.listener(recorder).listeners(List.of(more)));
        engine.load(List.of(Rule.builder().ruleName("failing").condition(condition).action(action).build()));
        return engine;
    }

    @Nested
    @DisplayName("in a rule")
    class InRule {

        @Test
        @DisplayName("a StackOverflowError from an action is wrapped and ends beforeExecute with onError")
        void actionStackOverflow() {
            StatefulRulesEngine<Map<String, Object>> engine = engine("true", "def f(n) { f(n + 1) }; f(1)");

            RuleExecutionException thrown =
                    assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

            assertInstanceOf(StackOverflowError.class, thrown.getCause());
            assertTrue(thrown.getMessage().contains("rule 'failing'"));
            assertEquals(List.of("beforeEvaluate", "afterEvaluate", "beforeExecute", "onError"), events);
            assertSame(thrown, errors.get(0));
        }

        @Test
        @DisplayName("an AssertionError from an action is wrapped and ends beforeExecute with onError")
        void actionAssertionError() {
            StatefulRulesEngine<Map<String, Object>> engine = engine("true", "assert false");

            RuleExecutionException thrown =
                    assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

            assertInstanceOf(AssertionError.class, thrown.getCause());
            assertEquals(List.of("beforeEvaluate", "afterEvaluate", "beforeExecute", "onError"), events);
        }

        @Test
        @DisplayName("an AssertionError from a condition is wrapped and ends beforeEvaluate with onError")
        void conditionAssertionError() {
            StatefulRulesEngine<Map<String, Object>> engine = engine("assert false", "output.put('k', 1)");

            RuleExecutionException thrown =
                    assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

            assertInstanceOf(AssertionError.class, thrown.getCause());
            assertEquals(List.of("beforeEvaluate", "onError"), events);
        }

        @Test
        @DisplayName("an OutOfMemoryError from an action reaches onError and is rethrown unchanged")
        void actionFatalErrorRethrown() {
            StatefulRulesEngine<Map<String, Object>> engine = engine("true", "new int[2147483647]");

            OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

            assertEquals(List.of("beforeEvaluate", "afterEvaluate", "beforeExecute", "onError"), events);
            assertSame(thrown, errors.get(0).getCause());
        }
    }

    @Nested
    @DisplayName("in the output factory")
    class InOutputFactory {

        @Test
        @DisplayName("a StackOverflowError is wrapped in RuleExecutionException")
        void factoryStackOverflowWrapped() {
            StackOverflowError overflow = new StackOverflowError("simulated");
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(() -> {
                throw overflow;
            });
            engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));

            RuleExecutionException thrown =
                    assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

            assertSame(overflow, thrown.getCause());
        }

        @Test
        @DisplayName("an OutOfMemoryError is rethrown unchanged")
        void factoryFatalErrorRethrown() {
            OutOfMemoryError oom = new OutOfMemoryError("simulated");
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(() -> {
                throw oom;
            });
            engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));

            assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>())));
        }
    }

    @Nested
    @DisplayName("in a listener")
    class InListener {

        @Test
        @DisplayName("an AssertionError is logged and doesn't interrupt the run")
        void listenerAssertionErrorContained() {
            StatefulRulesEngine<Map<String, Object>> engine = engine("true", "output.put('k', 1)", new RuleListener() {
                @Override
                public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                    throw new AssertionError("listener assert");
                }
            });

            assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
        }

        @Test
        @DisplayName("an OutOfMemoryError propagates out of run()")
        void listenerFatalErrorPropagates() {
            OutOfMemoryError oom = new OutOfMemoryError("simulated");
            StatefulRulesEngine<Map<String, Object>> engine = engine("true", "output.put('k', 1)", new RuleListener() {
                @Override
                public void afterExecute(Rule rule, Object output) {
                    throw oom;
                }
            });

            assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>())));
        }
    }
}
