package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Code the engine runs may be interrupted while it blocks. The {@link InterruptedException} it throws clears the
 * thread's interrupt status, and reaches the engine wrapped, so the engine must set the status again. Each test runs
 * the engine on a thread of its own and reports that thread's interrupt status afterwards.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("an interrupt that reaches the engine inside a failure keeps the thread's interrupt status")
class InterruptStatusTest {

    /** Where the language, listener or output supplier throws. */
    enum Where { NEW_COMPILER, COMPILE, COPY, CONDITION, ACTION, LISTENER, OUTPUT, FACT_NAME }

    /** The thread's interrupt status after {@code engine} ran into {@code interrupted} at {@code where}. */
    private static boolean interruptStatusAfter(Where where, Exception interrupted) throws InterruptedException {
        RuntimeException thrown = new IllegalStateException("wrapped by the language", interrupted);
        AtomicBoolean status = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            logsOf(() -> {
                try {
                    runInto(where, thrown);
                } catch (RuntimeException expected) {
                    // Every place but LISTENER fails the call; the status is what's tested.
                }
            });
            status.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        thread.join();
        return status.get();
    }

    private static void runInto(Where where, RuntimeException thrown) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(where == Where.OUTPUT
                ? () -> {
                    throw thrown;
                }
                : HashMap::new);
        engine.registerLanguage(new ThrowingLanguage(where, thrown));
        if (where == Where.LISTENER) {
            engine.registerListener(new RuleListener() {
                @Override
                public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                    throw thrown;
                }
            });
        }
        engine.setRuleList(List.of(Rule.builder().ruleName("r").language("throwing").condition("c").action("a")
                .build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        engine.run(facts);
    }

    /** A language that throws {@code thrown} at {@code where}, and otherwise matches and does nothing. */
    private record ThrowingLanguage(Where where, RuntimeException thrown) implements ExpressionLanguage {

        private <T> T at(Where place, T value) {
            if (where == place) {
                throw thrown;
            }
            return value;
        }

        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return at(Where.NEW_COMPILER, new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(String source) {
                    return at(Where.COMPILE, new Condition());
                }

                @Override
                public CompiledAction compileAction(String source) {
                    return actionContext -> at(Where.ACTION, actionContext);
                }

                @Override
                public void checkFactName(String name) {
                    at(Where.FACT_NAME, name);
                }
            });
        }

        private final class Condition implements CompiledCondition {
            @Override
            public Object evaluate(EvaluationContext context) {
                return at(Where.CONDITION, true);
            }

            @Override
            public CompiledCondition copy() {
                return at(Where.COPY, new Condition());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Where.class)
    @DisplayName("an InterruptedException inside what is thrown sets the interrupt status again")
    void interruptKept(Where where) throws InterruptedException {
        assertTrue(interruptStatusAfter(where, new InterruptedException("sleep interrupted")), where.name());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Where.class)
    @DisplayName("an InterruptedIOException such as SocketTimeoutException doesn't set it")
    void socketTimeoutIsNotAnInterrupt(Where where) throws InterruptedException {
        assertFalse(interruptStatusAfter(where, new SocketTimeoutException("Read timed out")), where.name());
    }

    @Test
    @DisplayName("an MVEL action interrupted in Thread.sleep fails the run, and the caller still sees the interrupt")
    void mvelActionInterrupted() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateless(HashMap::new);
        engine.setRuleList(List.of(Rule.builder().ruleName("sleepy").condition("true")
                .action("java.lang.Thread.sleep(20000)").build()));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean status = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            logsOf(() -> {
                try {
                    engine.run(new FactMap<>());
                } catch (RuntimeException e) {
                    thrown.set(e);
                }
            });
            status.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            Thread.sleep(5);
        }
        thread.interrupt();
        thread.join();

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertInstanceOf(InterruptedException.class, Failures.rootCause(ex));
        assertTrue(status.get(), "the thread's interrupt status after run() threw");
    }

    @Test
    @DisplayName("keepInterruptStatus looks through the whole cause chain, and ignores null and other causes")
    void keepInterruptStatus() throws InterruptedException {
        Function<Throwable, Boolean> statusAfter = thrown -> {
            AtomicBoolean status = new AtomicBoolean();
            Thread thread = new Thread(() -> {
                Failures.keepInterruptStatus(thrown);
                status.set(Thread.currentThread().isInterrupted());
            });
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return status.get();
        };

        assertTrue(statusAfter.apply(new RuntimeException(new RuntimeException(new InterruptedException()))));
        assertFalse(statusAfter.apply(null));
        assertFalse(statusAfter.apply(new RuntimeException(new IllegalStateException())));
    }
}
