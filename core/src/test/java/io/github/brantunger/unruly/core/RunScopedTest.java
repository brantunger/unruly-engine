package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EvaluationContext#runScoped}: the values a language keeps for one run, which every condition and action of
 * the run shares, and which a nested run and the next run don't see. Uses only the public API, apart from creating
 * contexts directly, so it shows what a language can rely on.
 */
@DisplayName("a language's run-scoped values last one run and are shared by its conditions and actions")
class RunScopedTest {

    /** What a run keeps under {@link ScopedLanguage#KEY}: a new object each time the supplier is called. */
    private static final class Converted {
    }

    /**
     * A language whose conditions and actions each ask for the value under {@link #KEY} and record it with the fact
     * {@code depth}. An action at depth 0 runs {@link #engine} again at depth 1, when {@link #nest} is set. With
     * {@link #keepNothing} set, they ask for nothing and only record the context they're given.
     */
    private static final class ScopedLanguage implements ExpressionLanguage {

        private static final Object KEY = new Object();

        private final AtomicInteger made = new AtomicInteger();
        private final List<Object> seen = new CopyOnWriteArrayList<>();
        private final List<Object> seenNested = new CopyOnWriteArrayList<>();
        private final List<EvaluationContext> contexts = new CopyOnWriteArrayList<>();
        private RulesEngine<Map<String, Object>> engine;
        private boolean nest;
        private boolean keepNothing;

        @Override
        public String name() {
            return "scoped";
        }

        private Object record(EvaluationContext context) {
            contexts.add(context);
            if (keepNothing) {
                return context;
            }
            Object value = context.runScoped(KEY, () -> {
                made.incrementAndGet();
                return new Converted();
            });
            (Integer.valueOf(1).equals(context.facts().get("depth")) ? seenNested : seen).add(value);
            return value;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> record(evaluation) != null;
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> {
                        Object before = record(action);
                        if (nest && !Integer.valueOf(1).equals(action.facts().get("depth"))) {
                            FactMap<Object> nested = new FactMap<>();
                            nested.setValue("depth", 1);
                            engine.run(nested);
                            assertSame(before, record(action), "the nested run changed the outer run's value");
                        }
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    private static RulesEngine<Map<String, Object>> engine(ScopedLanguage language, int rules) {
        List<Rule> list = new ArrayList<>();
        for (int i = 0; i < rules; i++) {
            list.add(Rule.builder().ruleName("r" + i).condition("any").action("any").build());
        }
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).build();
        engine.load(list);
        language.engine = engine;
        return engine;
    }

    @Test
    @DisplayName("every condition and action of a run gets the value the first one made")
    void oneValuePerRunSharedByConditionsAndActions() {
        ScopedLanguage language = new ScopedLanguage();

        try (RulesEngine<Map<String, Object>> engine = engine(language, 5)) {
            engine.run(new FactMap<>());
        }

        assertEquals(1, language.made.get());
        assertEquals(10, language.seen.size());
        language.seen.forEach(value -> assertSame(language.seen.get(0), value));
    }

    @Test
    @DisplayName("the next run starts without the last run's values")
    void freshValuesPerRun() {
        ScopedLanguage language = new ScopedLanguage();

        try (RulesEngine<Map<String, Object>> engine = engine(language, 3)) {
            engine.run(new FactMap<>());
            engine.run(new FactMap<>());
        }

        assertEquals(2, language.made.get());
        assertEquals(12, language.seen.size());
        assertNotSame(language.seen.get(0), language.seen.get(6));
        language.seen.subList(0, 6).forEach(value -> assertSame(language.seen.get(0), value));
        language.seen.subList(6, 12).forEach(value -> assertSame(language.seen.get(6), value));
    }

    @Test
    @DisplayName("a nested run has values of its own, and leaves the outer run's alone")
    void nestedRunHasItsOwnValues() {
        ScopedLanguage language = new ScopedLanguage();
        language.nest = true;

        try (RulesEngine<Map<String, Object>> engine = engine(language, 1)) {
            engine.run(new FactMap<>());
        }

        // The outer condition, its action before and after the nested run, and the nested condition and action.
        assertEquals(3, language.seen.size());
        assertEquals(2, language.seenNested.size());
        assertEquals(2, language.made.get());
        language.seen.forEach(value -> assertSame(language.seen.get(0), value));
        assertSame(language.seenNested.get(0), language.seenNested.get(1));
        assertNotSame(language.seen.get(0), language.seenNested.get(0));
    }

    @Test
    @DisplayName("an action context the engine creates for a run shares the run's values, as its evaluation does")
    void actionContextSharesTheEvaluationContextsValues() {
        RunScope scope = new RunScope();
        EvaluationContext evaluation = new EngineEvaluationContext(Map.of(), Deadline.NONE, scope);
        EvaluationContext action = new EngineActionContext(Map.of(), new HashMap<>(), Deadline.NONE, scope);
        EvaluationContext otherRun = new EngineActionContext(Map.of(), new HashMap<>(), Deadline.NONE);

        Object value = evaluation.runScoped("key", Object::new);

        assertSame(value, action.runScoped("key", Object::new));
        assertNotSame(value, otherRun.runScoped("key", Object::new));
        assertEquals("other", action.runScoped("other key", () -> "other"));
        assertEquals("other", evaluation.runScoped("other key", () -> "not made"));
    }

    @Test
    @DisplayName("an action context created in the same run as another context takes that run's deadline and values")
    void actionContextInTheSameRun() {
        Deadline deadline = Deadline.from(Duration.ofMinutes(1));
        EngineEvaluationContext evaluation = new EngineEvaluationContext(Map.of("x", 1), deadline);
        Map<String, Object> output = new HashMap<>();

        EngineActionContext action = new EngineActionContext(evaluation, output);
        EngineActionContext next = new EngineActionContext(action, output);

        assertSame(deadline, action.runDeadline());
        assertSame(evaluation.runScope(), action.runScope());
        assertSame(evaluation.runScope(), next.runScope());
        assertSame(deadline, next.runDeadline());
        assertEquals(Map.of("x", 1), next.facts());
        assertEquals("sameRun must not be null", assertThrows(NullPointerException.class,
                () -> new EngineActionContext((EvaluationContext) null, output)).getMessage());
    }

    @Test
    @DisplayName("a null key, supplier or value is rejected with a message that names it")
    void nullsRejected() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        Supplier<Object> none = () -> null;

        assertEquals("key must not be null",
                assertThrows(NullPointerException.class, () -> context.runScoped(null, Object::new)).getMessage());
        assertEquals("init must not be null",
                assertThrows(NullPointerException.class, () -> context.runScoped("key", null)).getMessage());
        assertEquals("init must not return null",
                assertThrows(NullPointerException.class, () -> context.runScoped("key", none)).getMessage());
        assertEquals("made", context.runScoped("key", () -> "made"));
    }

    @Test
    @DisplayName("what the supplier throws reaches the caller, and nothing is kept, so the next call makes the value")
    void supplierFailurePropagatesAndKeepsNothing() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        IllegalStateException failure = new IllegalStateException("conversion failed");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> context.runScoped("key", () -> {
            throw failure;
        })));
        assertEquals("made", context.runScoped("key", () -> "made"));
    }

    @Test
    @DisplayName("a supplier can ask for another key of the same run")
    void supplierCanAskForAnotherKey() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);

        String outer = context.runScoped("outer", () -> context.runScoped("inner", () -> "inner") + " and outer");

        assertEquals("inner and outer", outer);
        assertEquals("inner", context.runScoped("inner", () -> "not made"));
    }

    @Test
    @DisplayName("an init that asks for its own key fails, rather than recursing or making the value twice")
    void initAskingForItsOwnKeyFails() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        Supplier<String> recursive = () -> context.runScoped("key", () -> "inner") + " and outer";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> context.runScoped("key", recursive));

        assertEquals("runScoped was called for a key (java.lang.String) while that key's init is running",
                ex.getMessage());
        assertEquals("made", context.runScoped("key", () -> "made"));
        assertEquals("made", context.runScoped("key", recursive));
    }

    @Test
    @DisplayName("a run whose language keeps nothing makes no map for its values")
    void runKeepingNothingAllocatesNoMap() {
        ScopedLanguage language = new ScopedLanguage();
        language.keepNothing = true;

        try (RulesEngine<Map<String, Object>> engine = engine(language, 2)) {
            engine.run(new FactMap<>());
        }

        assertEquals(4, language.contexts.size());
        for (EvaluationContext context : language.contexts) {
            assertFalse(EngineEvaluationContext.runScopeOf(context).allocated());
        }
        EvaluationContext asked = language.contexts.get(0);
        asked.runScoped("key", () -> "made");
        assertTrue(EngineEvaluationContext.runScopeOf(asked).allocated());
    }

    @Test
    @DisplayName("a value asked for as another type fails with a ClassCastException in the caller")
    void sameKeyAnotherType() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        context.runScoped("key", () -> "text");

        assertThrows(ClassCastException.class, () -> {
            Integer number = context.runScoped("key", () -> 1);
            fail("got " + number);
        });
    }

    @Test
    @DisplayName("a context needs the run's values")
    void contextsNeedARunScope() {
        assertEquals("runScope must not be null", assertThrows(NullPointerException.class,
                () -> new EngineEvaluationContext(Map.of(), Deadline.NONE, null)).getMessage());
        assertEquals("runScope must not be null", assertThrows(NullPointerException.class,
                () -> new EngineActionContext(Map.of(), new HashMap<>(), Deadline.NONE, null)).getMessage());
    }
}
