package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.core.EngineLoggingTest.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A run that starts while another run holds the copy of the rules needs a new copy. The rule's condition here
 * copies itself normally, except while such an overlapping run is starting, when it does what the test says.
 */
@DisplayName("a compiled expression that fails to copy fails only the run that needed the copy")
class CopyFailureTest {

    private final StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
    private final AtomicBoolean overlapping = new AtomicBoolean();

    /** Loads one rule whose condition's copy() calls {@code copyWhileOverlapping} during an overlapping run. */
    private void load(Supplier<CompiledCondition> copyWhileOverlapping) {
        CompiledCondition condition = new CompiledCondition() {
            @Override
            public Object evaluate(EvaluationContext context) {
                return true;
            }

            @Override
            public CompiledCondition copy() {
                return overlapping.get() ? copyWhileOverlapping.get() : this;
            }
        };
        load(condition, context -> {
        });
    }

    /** Loads one rule whose action's copy() calls {@code copyWhileOverlapping} during an overlapping run. */
    private void loadWithAction(Supplier<CompiledAction> copyWhileOverlapping) {
        CompiledAction action = new CompiledAction() {
            @Override
            public void execute(ActionContext context) {
            }

            @Override
            public CompiledAction copy() {
                return overlapping.get() ? copyWhileOverlapping.get() : this;
            }
        };
        load(evaluation -> true, action);
    }

    private void load(CompiledCondition condition, CompiledAction action) {
        engine.registerLanguage(new ExpressionLanguage() {
            @Override
            public String name() {
                return "x";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(String source) {
                        return condition;
                    }

                    @Override
                    public CompiledAction compileAction(String source) {
                        return action;
                    }
                };
            }
        });
        engine.setRuleList(List.of(Rule.builder().ruleName("r").language("x").condition("c").action("a").build()));
    }

    /**
     * Runs the engine, and once, from inside that run while it holds its copy of the rules, runs it again.
     *
     * @return What the inner run threw, or {@code null}
     */
    private Throwable overlappingRunFailure() {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean();
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                if (started.compareAndSet(false, true)) {
                    overlapping.set(true);
                    try {
                        engine.run(new FactMap<>());
                    } catch (RuntimeException | Error e) {
                        thrown.set(e);
                    } finally {
                        overlapping.set(false);
                    }
                }
            }
        });
        engine.run(new FactMap<>());
        return thrown.get();
    }

    @Test
    @DisplayName("a copy() that throws fails the run with a RuleExecutionException naming the rule, and is logged")
    void copyThrows() {
        IllegalStateException cause = new IllegalStateException("compiled script handle can't be duplicated");
        load(() -> {
            throw cause;
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(overlappingRunFailure()));

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to copy the condition of rule 'r': compiled script handle can't be duplicated",
                ex.getMessage());
        assertSame(cause, ex.getCause());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + ex.getMessage()), logs);
    }

    @Test
    @DisplayName("an action whose copy() throws is reported as a failure to copy the action")
    void actionCopyThrows() {
        IllegalStateException cause = new IllegalStateException("compiled action can't be duplicated");
        loadWithAction(() -> {
            throw cause;
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(overlappingRunFailure()));

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to copy the action of rule 'r': compiled action can't be duplicated", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + ex.getMessage()), logs);
    }

    @Test
    @DisplayName("a copy() that returns null fails that run clearly, and later runs aren't affected")
    void copyReturnsNull() {
        load(() -> null);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(overlappingRunFailure()));

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to copy the condition of rule 'r': copy() returned null", ex.getMessage());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + ex.getMessage()), logs);
        for (int i = 0; i < 5; i++) {
            assertEquals(Map.of(), engine.run(new FactMap<>()), "run " + i + " after the failed copy");
        }
    }

    @Test
    @DisplayName("a fatal Error inside what copy() throws is logged, then rethrown unchanged")
    void copyWrapsFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        load(() -> {
            throw new IllegalStateException("wrapped", oom);
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(overlappingRunFailure()));

        assertSame(oom, thrown.get());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Failed to copy the condition of rule 'r': wrapped"), logs);
    }
}
