package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1009: a run's scope holds a lock, not a monitor, while a value kept with
 * {@link EvaluationContext#runScopedClosing} is made, and releasing a lock can fail once it is free, as waking a
 * virtual thread waiting for it allocates. The run's values are still handed back and closed, and what releasing
 * threw is thrown once they are.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#1009: a run's values are closed even when releasing its scope's lock fails")
class RunScopeReleaseFailureTest {

    private static final List<Rule> RULES = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());

    // What happened, in order: listener calls, values closed and sessions closed.
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();

    /** A value a language keeps for a run, which records its close() and then throws what the test set, if anything. */
    private class Value implements AutoCloseable {
        private final String name;
        private final RuntimeException failure;

        Value(String name) {
            this(name, null);
        }

        Value(String name, RuntimeException failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public void close() throws IOException {
            events.add(name + " closed");
            if (failure != null) {
                throw failure;
            }
        }
    }

    @AfterEach
    void clearFaults() {
        Faults.clear();
    }

    /**
     * An engine whose one rule runs {@code action}, whose sessions record their close(), and which makes no copy at
     * load, so a copy's session is closed only when a run gives back a copy of rules that were reloaded meanwhile.
     */
    private RulesEngine<Map<String, Object>> engine(Consumer<ActionContext> action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new StubExpressionLanguage().newSession(() -> new Session() {
                    @Override
                    public void close() {
                        events.add("session closed");
                    }
                }).action((context, session) -> {
                    action.accept(context);
                    return ActionResult.done();
                })).listener(new RuleListener() {
                    @Override
                    public void afterRun(RunContext run, RunResult<?> result) {
                        events.add("afterRun");
                    }
                }).copiesAtLoad(0).build();
        engine.load(RULES);
        self.set(engine);
        return engine;
    }

    @Test
    @DisplayName("#1009: when releasing the run's lock fails as its values are handed over, as waking a virtual thread"
            + " can, every value is still closed, the copy goes back, and run() throws what releasing threw")
    void releasingTheLockFailsStillClosesTheValues() {
        OutOfMemoryError injected = new OutOfMemoryError("waking a waiter ran out");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b"));
            self.get().load(RULES);
        })) {
            Faults.inject(Faults.Step.RUN_SCOPE_UNLOCKED, 1, injected);

            assertSame(injected, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(List.of("afterRun", "b closed", "a closed", "session closed"), events);

            events.clear();
            engine.run(new FactMap<>());
            assertEquals(List.of("afterRun", "b closed", "a closed", "session closed"), events);
        }
    }

    @Test
    @DisplayName("#1009: ending a test kit's run whose lock fails to release closes every value, then throws what"
            + " releasing threw, with what a close() threw suppressed on it, once")
    void endRunThrowsWhatReleasingTheLockThrewOnceTheValuesAreClosed() throws Exception {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        IllegalStateException closeFailure = new IllegalStateException("b failed to close");
        context.runScopedClosing("a", () -> new Value("a"));
        context.runScopedClosing("b", () -> new Value("b", closeFailure));
        OutOfMemoryError injected = new OutOfMemoryError("waking a waiter ran out");
        Faults.inject(Faults.Step.RUN_SCOPE_UNLOCKED, 1, injected);

        assertSame(injected, assertThrows(OutOfMemoryError.class, () -> EngineEvaluationContext.endRun(context)));
        assertEquals(List.of("b closed", "a closed"), events);
        assertEquals(List.of(closeFailure), Arrays.asList(injected.getSuppressed()));

        EngineEvaluationContext.endRun(context);
        assertEquals(List.of("b closed", "a closed"), events);
    }
}
