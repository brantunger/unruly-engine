package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a fatal Error inside another exception is rethrown unchanged")
class WrappedFatalErrorTest {

    private final OutOfMemoryError oom = new OutOfMemoryError("simulated");
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

    /** Java code a rule calls. MVEL wraps what it throws, which is how a real OutOfMemoryError reaches a rule. */
    public static class Bomb {
        private final Error error;

        public Bomb(Error error) {
            this.error = error;
        }

        public boolean explode() {
            throw error;
        }

        public boolean isExploding() {
            throw error;
        }

        public boolean wrapped() {
            throw new IllegalStateException("wrapped by the fact's own code", error);
        }

        public boolean overflow() {
            throw new StackOverflowError("simulated");
        }
    }

    /** An exception whose cause chain loops back on itself. */
    static final class CyclicException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private Throwable loop;

        CyclicException() {
            super("cyclic");
        }

        @Override
        public synchronized Throwable getCause() {
            return loop;
        }
    }

    /** Where the error comes from: how a condition calls it, and how an action calls it. */
    static Stream<Arguments> calls() {
        List<Arguments> calls = List.of(
                Arguments.of("a method", "bomb.explode()", "bomb.explode()"),
                Arguments.of("a getter", "bomb.exploding", "bomb.exploding"),
                Arguments.of("a lambda held in a fact", "supplier.get() == 1", "supplier.get()"),
                Arguments.of("code that wraps it in its own exception", "bomb.wrapped()", "bomb.wrapped()"),
                Arguments.of("a nested run()", "nested.get() == null", "nested.get()"));
        List<Arguments> withEngines = new ArrayList<>();
        for (String engine : List.of("stateless", "stateful")) {
            for (Arguments call : calls) {
                Object[] values = call.get();
                withEngines.add(Arguments.of(engine, values[0], values[1], values[2]));
            }
        }
        return withEngines.stream();
    }

    private AbstractRulesEngine<Map<String, Object>> engine(String type, String condition, String action) {
        AbstractRulesEngine<Map<String, Object>> engine = "stateless".equals(type)
                ? new StatelessRulesEngine<>(HashMap::new)
                : new StatefulRulesEngine<>(HashMap::new);
        engine.registerListener(recorder);
        engine.setRuleList(List.of(Rule.builder().ruleName("bomb").condition(condition).action(action).build()));
        return engine;
    }

    private FactStore<Object> facts() {
        FactStore<Object> inner = new FactMap<>();
        inner.setValue("bomb", new Bomb(oom));
        StatefulRulesEngine<Map<String, Object>> nested = new StatefulRulesEngine<>(HashMap::new);
        nested.setRuleList(List.of(Rule.builder().ruleName("inner").condition("true").action("bomb.explode()").build()));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("bomb", new Bomb(oom));
        facts.setValue("supplier", (Supplier<Integer>) () -> {
            throw oom;
        });
        facts.setValue("nested", (Supplier<Object>) () -> nested.run(inner));
        return facts;
    }

    private static boolean inCauseChain(Throwable thrown, Throwable wanted) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t == wanted) {
                return true;
            }
        }
        return false;
    }

    @Nested
    @DisplayName("from a rule")
    class FromRule {

        @ParameterizedTest(name = "{0} engine: condition calls {1}")
        @MethodSource("io.github.brantunger.unruly.core.WrappedFatalErrorTest#calls")
        void fromCondition(String type, String call, String condition, String action) {
            AbstractRulesEngine<Map<String, Object>> engine = engine(type, condition, "output.put('k', 1)");

            assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(facts())));

            assertEquals(List.of("beforeEvaluate", "onError"), events);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).getMessage().contains("rule 'bomb'"), errors.get(0).getMessage());
            assertTrue(inCauseChain(errors.get(0), oom));
        }

        @ParameterizedTest(name = "{0} engine: action calls {1}")
        @MethodSource("io.github.brantunger.unruly.core.WrappedFatalErrorTest#calls")
        void fromAction(String type, String call, String condition, String action) {
            AbstractRulesEngine<Map<String, Object>> engine = engine(type, "true", action);

            assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(facts())));

            assertEquals(List.of("beforeEvaluate", "afterEvaluate", "beforeExecute", "onError"), events);
            assertEquals(1, errors.size());
            assertTrue(inCauseChain(errors.get(0), oom));
        }

        @Test
        @DisplayName("a StackOverflowError from a method a rule calls is still wrapped")
        void stackOverflowStaysWrapped() {
            AbstractRulesEngine<Map<String, Object>> engine = engine("stateful", "true", "bomb.overflow()");

            RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

            assertTrue(Stream.iterate((Throwable) thrown, t -> t != null, Throwable::getCause)
                    .anyMatch(StackOverflowError.class::isInstance), () -> String.valueOf(thrown));
            assertSame(thrown, errors.get(0));
        }
    }

    @Nested
    @DisplayName("from the output factory")
    class FromOutputFactory {

        @Test
        @DisplayName("an OutOfMemoryError inside the exception the factory throws is rethrown")
        void wrappedByFactory() {
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(() -> {
                throw new IllegalStateException("factory failed", oom);
            });
            engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));

            assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>())));
        }

        @Test
        @DisplayName("a cause chain that loops back on itself is wrapped instead of hanging")
        void cyclicCauseChain() {
            CyclicException first = new CyclicException();
            CyclicException second = new CyclicException();
            first.loop = second;
            second.loop = first;
            StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(() -> {
                throw first;
            });
            engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));

            RuleExecutionException thrown = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>())));

            assertSame(first, thrown.getCause());
        }
    }

    @Nested
    @DisplayName("from a listener")
    class FromListener {

        @Test
        @DisplayName("an OutOfMemoryError inside an exception thrown by afterExecute propagates")
        void wrappedInAfterExecute() {
            AbstractRulesEngine<Map<String, Object>> engine = engine("stateful", "true", "output.put('k', 1)");
            engine.registerListener(new RuleListener() {
                @Override
                public void afterExecute(Rule rule, Object output) {
                    throw new IllegalStateException("listener failed", oom);
                }
            });

            assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>())));
        }

        @Test
        @DisplayName("an OutOfMemoryError inside an exception thrown by beforeEvaluate stops the condition")
        void wrappedInBeforeEvaluate() {
            AbstractRulesEngine<Map<String, Object>> engine = engine("stateful", "true", "output.put('k', 1)");
            engine.registerListener(new RuleListener() {
                @Override
                public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                    throw new IllegalStateException("listener failed", oom);
                }
            });

            assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>())));

            assertEquals(List.of("beforeEvaluate", "onError"), events);
        }
    }
}
