package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The contract test kit must fail a language that breaks a promise, not only pass one that keeps them. These run one
 * of the kit's checks against a broken language and expect it to fail (#347).
 */
@DisplayName("the contract test kit fails a language that breaks the contract")
class ContractKitChecksTest {

    /** Runs one of the kit's checks, by name, on a contract test for {@code language}. */
    static void runCheck(ExpressionLanguage language, String check) throws Throwable {
        runCheck(new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }
        }, check);
    }

    /** Runs one of the kit's checks, by name, on a contract test the caller built. */
    static void runCheck(ExpressionLanguageContractTest test, String check) throws Throwable {
        Method method = ExpressionLanguageContractTest.class.getDeclaredMethod(check);
        method.setAccessible(true);
        try {
            method.invoke(test);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Wraps a language so that its conditions can't read a fact that is neither a record nor a map. */
    private static ExpressionLanguage beanBlind(ExpressionLanguage language) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> {
                            // A reader that handles record components and map keys, and reads a getter as nothing.
                            boolean bean = evaluation.facts().values().stream()
                                    .anyMatch(fact -> fact != null && !(fact instanceof Record)
                                            && !(fact instanceof Map) && !(fact instanceof Number)
                                            && !(fact instanceof Boolean) && !(fact instanceof String));
                            return !bean && Boolean.TRUE.equals(condition.evaluate(evaluation, session));
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }
                };
            }
        };
    }

    /** Wraps a language so that its actions compile on first use instead of when the rule is loaded. */
    private static ExpressionLanguage lazyActions(ExpressionLanguage language) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (actionContext, session) ->
                                compiler.compileAction(expression).execute(actionContext, session);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that a condition that assigns, such as {@code x = 2}, compiles to {@code assignment} instead
     * of being rejected when the rule loads.
     */
    private static ExpressionLanguage assigningConditions(ExpressionLanguage language, CompiledCondition assignment) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return expression.text().contains(" = ") ? assignment : compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }
                };
            }
        };
    }

    /** A language whose condition writes the assigned fact, which the engine's read-only facts refuse at run time. */
    private static ExpressionLanguage runtimeReject(ExpressionLanguage language) {
        return assigningConditions(language, (evaluation, session) -> {
            evaluation.facts().put("x", 2);
            return true;
        });
    }

    /** A language whose condition assigns to a local copy of the fact, as JavaScript would, and returns the value. */
    private static ExpressionLanguage localAssign(ExpressionLanguage language) {
        return assigningConditions(language, (evaluation, session) -> 2);
    }

    /** A language whose condition ignores the assignment and is true, so nothing ever says it was wrong. */
    private static ExpressionLanguage silentTrue(ExpressionLanguage language) {
        return assigningConditions(language, (evaluation, session) -> true);
    }

    /** Wraps a language so that a condition reading a misspelled {@code creditScor} is rejected when the rule loads. */
    private static ExpressionLanguage missingPropertyAtLoad(ExpressionLanguage language) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        if (expression.text().contains(".creditScor ")) {
                            throw new IllegalArgumentException("the fact has no property creditScor");
                        }
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }

                    @Override
                    public void checkFactName(String name) {
                        compiler.checkFactName(name);
                    }

                    @Override
                    public void close() {
                        compiler.close();
                    }
                };
            }
        };
    }

    /** Wraps a language so that every action it compiles fails when it runs. */
    private static ExpressionLanguage throwingActions(ExpressionLanguage language) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (actionContext, session) -> {
                            throw new IllegalStateException("the action failed");
                        };
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }
                };
            }
        };
    }

    /** Wraps a language so that its compiler's newSession() returns what {@code sessions} supplies. */
    static ExpressionLanguage withSessions(ExpressionLanguage language, Supplier<Session> sessions) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return sessions.get();
                    }
                };
            }
        };
    }

    /** Wraps a language so that each session it creates is new, and throws when it's closed. */
    private static ExpressionLanguage throwingClose(ExpressionLanguage language) {
        return withSessions(language, () -> new Session() {
            @Override
            public void close() {
                throw new IllegalStateException("the session's runtime was already shut down");
            }
        });
    }

    /** Wraps a language so that it creates one session and returns it every time: state shared by every copy. */
    private static ExpressionLanguage sharedSession(ExpressionLanguage language) {
        Session shared = new Session() {
        };
        return withSessions(language, () -> shared);
    }

    @Test
    @DisplayName("a language whose session throws when it's closed fails the session check (#470)")
    void throwingCloseFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(throwingClose(new ToyExpressionLanguage()), "sessionsClosed"));

        assertEquals("a session's close() threw java.lang.IllegalStateException: the session's runtime was already"
                + " shut down, which the engine only logs at WARN", failure.getMessage());
    }

    @Test
    @DisplayName("a session whose close() throws a fatal error fails the session check with that error (#470)")
    void fatalCloseReported() {
        // The engine rethrows a fatal error from close(), the same instance the session threw, from engine.close().
        ExpressionLanguage fatalClose = withSessions(new ToyExpressionLanguage(), () -> new Session() {
            @Override
            public void close() {
                throw new InternalError("the session's native runtime crashed");
            }
        });

        InternalError failure = assertThrows(InternalError.class, () -> runCheck(fatalClose, "sessionsClosed"));

        assertEquals("the session's native runtime crashed", failure.getMessage());
    }

    @Test
    @DisplayName("a language that returns one session for every copy of the rules fails the session check (#470)")
    void sharedSessionFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(sharedSession(new ToyExpressionLanguage()), "sessionsClosed"));

        assertTrue(failure.getMessage().startsWith("newSession() returned the same session for two copies of the"
                + " rules, so two runs use it at once and the engine closes it twice: "), failure.getMessage());
    }

    @Test
    @DisplayName("a language that returns a new session each time, or none, passes the session check (#470)")
    void ownSessionsPass() {
        assertDoesNotThrow(() -> runCheck(withSessions(new ToyExpressionLanguage(), () -> new Session() {
        }), "sessionsClosed"));
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "sessionsClosed"));
    }

    @Test
    @DisplayName("the session check passes Session.none() on unwrapped, so a stateless language's copy is shared"
            + " (#470)")
    void noSessionNotWrapped() throws Throwable {
        AtomicInteger created = new AtomicInteger();

        runCheck(withSessions(new ToyExpressionLanguage(), () -> {
            created.incrementAndGet();
            return Session.none();
        }), "sessionsClosed");

        // Two copies are asked for when the rules load. A wrapped Session.none() is no longer the engine's stateless
        // session, so it would make a copy for each, and ask for a session twice.
        assertEquals(1, created.get());
    }

    @Test
    @DisplayName("the session check passes a null session on, so the engine still rejects it (#470)")
    void nullSessionStillRejected() {
        UnrulyException failure = assertThrows(UnrulyException.class,
                () -> runCheck(withSessions(new ToyExpressionLanguage(), () -> null), "sessionsClosed"));

        assertTrue(failure.getMessage().endsWith("expression language returned no session"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that rejects a condition's assignment when the rule runs passes the assignment check"
            + " (#508)")
    void runtimeRejectPasses() {
        assertDoesNotThrow(() -> runCheck(runtimeReject(new ToyExpressionLanguage()), "conditionAssignmentRejected"));
    }

    @Test
    @DisplayName("a language whose assigning condition evaluates to the value assigned passes the assignment check"
            + " (#508)")
    void localAssignPasses() {
        assertDoesNotThrow(() -> runCheck(localAssign(new ToyExpressionLanguage()), "conditionAssignmentRejected"));
    }

    @Test
    @DisplayName("a language that rejects a missing property when the rule loads passes the missing-property check"
            + " (#534)")
    void missingPropertyAtLoadPasses() {
        assertDoesNotThrow(() -> runCheck(missingPropertyAtLoad(new ToyExpressionLanguage()),
                "missingPropertyFailsTheRun"));
    }

    @Test
    @DisplayName("a language whose assigning condition is silently true fails the assignment check (#508)")
    void silentTrueFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(silentTrue(new ToyExpressionLanguage()), "conditionAssignmentRejected"));

        assertTrue(failure.getMessage().startsWith("a condition that assigns to a fact was neither rejected by load"
                + " nor failed by run"), failure.getMessage());
    }

    @Test
    @DisplayName("a silently true assigning condition fails the assignment check when its rule's action fails the run"
            + " instead (#508)")
    void actionFailureNotTakenForTheCondition() {
        // The run fails, naming the rule, but for its action: the check must look at which expression failed.
        ExpressionLanguage language = throwingActions(silentTrue(new ToyExpressionLanguage()));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "conditionAssignmentRejected"));

        assertTrue(failure.getMessage().endsWith("expected: <CONDITION> but was: <ACTION>"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that can't read a JavaBean fact fails the property check")
    void beanBlindLanguageFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(beanBlind(new ToyExpressionLanguage()), "conditionReadsProperties"));

        assertTrue(failure.getMessage().startsWith("a JavaBean fact's getter wasn't read"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that compares whole numbers by type fails the whole-number check")
    void byTypeLanguageFails() {
        // The toy is the broken language here: its == is Objects.equals. The hook it overrides to false goes back
        // to true, or the check would abort on its assumption instead of failing.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected boolean comparesWholeNumbersByValue() {
                return true;
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "conditionReadsWholeNumbers"));

        assertTrue(failure.getMessage().startsWith("the rule didn't fire for a Long fact"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that compiles its actions on first use fails the action syntax-error check")
    void lazyActionLanguageFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(lazyActions(new ToyExpressionLanguage()), "syntaxErrorInActionAtLoad"));

        assertTrue(failure.getMessage().contains("RuleCompilationException to be thrown"), failure.getMessage());
    }

    @Test
    @DisplayName("comparing numbers by value still fails a language that returns the wrong number")
    void wrongNumberStillFails() {
        ExpressionLanguage offByOne = new ExpressionLanguage() {
            private final ExpressionLanguage longs =
                    LongNumbersContractTest.longNumbers(new ToyExpressionLanguage("toy-longs", true));

            @Override
            public String name() {
                return longs.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = longs.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        return (actionContext, session) -> {
                            Map<String, Object> properties = new java.util.LinkedHashMap<>();
                            action.execute(actionContext, session).properties().forEach((key, value) ->
                                    properties.put(key, value instanceof Long number ? number + 1 : value));
                            return ActionResult.set(properties);
                        };
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }
                };
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(offByOne, "conditionReadsFacts"));

        assertEquals("expected: <{seen=1}> but was: <{seen=2}>", failure.getMessage());
    }
}
