package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.core.EngineLoggingTest.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A run that starts while another run holds the copy of the rules needs a new copy, and so new sessions. The language
 * here creates sessions normally, except while such an overlapping run is starting, when it does what the test says.
 */
@DisplayName("a language that fails to create a session fails only the run that needed the session")
class SessionFailureTest {

    private final StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
    private final AtomicBoolean overlapping = new AtomicBoolean();

    /** A language whose rules always match and do nothing, and whose sessions come from {@code newSession}. */
    private static ExpressionLanguage language(String name, Supplier<Session> newSession) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluationContext, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (actionContext, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        return newSession.get();
                    }
                };
            }
        };
    }

    private static Rule rule(String name, String language, int priority) {
        return Rule.builder().ruleName(name).language(language).priority(priority).condition("c").action("a").build();
    }

    /** Loads one rule in language {@code x}, whose newSession() calls {@code whileOverlapping} during an overlapping run. */
    private void load(Supplier<Session> whileOverlapping) {
        engine.registerLanguage(language("x", () -> overlapping.get() ? whileOverlapping.get() : Session.none()));
        engine.setRuleList(List.of(rule("r", "x", 1)));
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
    @DisplayName("a newSession() that throws fails the run with a RuleExecutionException naming the language, and is logged")
    void sessionThrows() {
        IllegalStateException cause = new IllegalStateException("interpreter context can't be created");
        load(() -> {
            throw cause;
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(overlappingRunFailure()));

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("The 'x' expression language failed to create a session: interpreter context can't be created",
                ex.getMessage());
        assertSame(cause, ex.getCause());
        assertNull(ex.getRuleName(), "a session belongs to a language, not a rule");
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + ex.getMessage()), logs);
    }

    @Test
    @DisplayName("a newSession() that returns null fails that run clearly, and later runs aren't affected")
    void sessionIsNull() {
        load(() -> null);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(overlappingRunFailure()));

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("The 'x' expression language returned no session", ex.getMessage());
        assertNull(ex.getRuleName());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + ex.getMessage()), logs);
        for (int i = 0; i < 5; i++) {
            assertEquals(Map.of(), engine.run(new FactMap<>()), "run " + i + " after the failed session");
        }
    }

    @Test
    @DisplayName("a fatal Error inside what newSession() throws is logged, then rethrown unchanged")
    void sessionWrapsFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        load(() -> {
            throw new IllegalStateException("wrapped", oom);
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(overlappingRunFailure()));

        assertSame(oom, thrown.get());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "The 'x' expression language failed to create a session: "
                + "wrapped"), logs);
    }

    @Test
    @DisplayName("the sessions already created for the new copy are closed, and the running copy's aren't")
    void earlierSessionsClosed() {
        AtomicInteger createdWhileOverlapping = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        engine.registerLanguage(language("a", () -> {
            if (overlapping.get()) {
                createdWhileOverlapping.incrementAndGet();
            }
            return new Session() {
                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        }));
        engine.registerLanguage(language("x", () -> {
            if (overlapping.get()) {
                throw new IllegalStateException("no session");
            }
            return Session.none();
        }));
        // Language a is used first, so its session is created before x fails.
        engine.setRuleList(List.of(rule("first", "a", 2), rule("second", "x", 1)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(overlappingRunFailure()));

        assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals(1, createdWhileOverlapping.get(), "sessions of language a created for the new copy");
        assertEquals(1, closed.get(), "sessions closed: only the new copy's");
    }
}
