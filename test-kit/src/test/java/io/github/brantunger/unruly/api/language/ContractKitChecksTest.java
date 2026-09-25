package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    /** Wraps the toy so that each session it creates is new, and runs {@code close} when it's closed. */
    private static ExpressionLanguage closingWith(Runnable close) {
        return withSessions(new ToyExpressionLanguage(), () -> new Session() {
            @Override
            public void close() {
                close.run();
            }
        });
    }

    /** A contract test whose actions put the fact under the wrong key, so every check that reads the output fails. */
    private static ExpressionLanguageContractTest wrongKey(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            @Override
            protected String putFact(String key, String fact) {
                return "put wrong " + fact;
            }
        };
    }

    /** The variables a language's actions declare, kept in its session. */
    private static final class Variables implements Session {

        private final Map<String, Object> declared = new ConcurrentHashMap<>();
    }

    /**
     * Wraps a language so that an action's {@code let NAME = VALUE} keeps the variable in the session, and
     * {@code put KEY NAME} reads a name that isn't a fact from there. If {@code oneRunLate}, each {@code put} reads the
     * variables as they were when it last ran, as a language that caches what it resolved would: a later rule in the
     * same run doesn't see the variable, and a later run does.
     */
    private static ExpressionLanguage sessionVariables(ExpressionLanguage language, boolean oneRunLate) {
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
                        CompiledAction action = compiler.compileAction(expression);
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length == 4 && "let".equals(tokens[0])) {
                            return (actionContext, session) -> {
                                ((Variables) session).declared.put(tokens[1], Integer.valueOf(tokens[3]));
                                return ActionResult.done();
                            };
                        }
                        if (tokens.length != 3 || !"put".equals(tokens[0])) {
                            return action;
                        }
                        AtomicReference<Map<String, Object>> cached = new AtomicReference<>(Map.of());
                        return (actionContext, session) -> {
                            Map<String, Object> declared = ((Variables) session).declared;
                            Map<String, Object> visible =
                                    oneRunLate ? cached.getAndSet(Map.copyOf(declared)) : declared;
                            if (actionContext.facts().containsKey(tokens[2]) || !visible.containsKey(tokens[2])) {
                                return action.execute(actionContext, session);
                            }
                            return ActionResult.set(Map.of(tokens[1], visible.get(tokens[2])));
                        };
                    }

                    @Override
                    public Session newSession() {
                        return new Variables();
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that a {@code put} of a name that is neither a fact nor a variable puts {@code null}, as a
     * JsonLogic-style language reads a name it doesn't know, or, if {@code atLoad}, is refused when the rule loads.
     */
    private static ExpressionLanguage unknownNames(ExpressionLanguage language, boolean atLoad) {
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
                        // The checks' only name that is never a fact is the variable y.
                        if (!expression.text().endsWith(" y")) {
                            return compiler.compileAction(expression);
                        }
                        if (atLoad) {
                            throw new IllegalArgumentException("unknown name 'y'");
                        }
                        String key = expression.text().split("\\s+")[1];
                        return (actionContext, session) -> {
                            Map<String, Object> properties = new HashMap<>();
                            properties.put(key, null);
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
    }

    /** Wraps a language so that its checkFactName() rejects any name but letters, such as {@code credit_score2}. */
    private static ExpressionLanguage lettersOnly(ExpressionLanguage language) {
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
                        return compiler.newSession();
                    }

                    @Override
                    public void checkFactName(String name) {
                        if (!name.matches("[A-Za-z]+")) {
                            throw new IllegalArgumentException("not a name: " + name);
                        }
                    }
                };
            }
        };
    }

    /** Throws {@code thrown} whatever its type, as a language's code can, though close() declares nothing. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
    }

    /**
     * Wraps a language so that each of its compilers throws what {@code failure} supplies when it's closed, whatever
     * its type.
     */
    private static ExpressionLanguage throwingCompilerClose(ExpressionLanguage language,
                                                            Supplier<? extends Throwable> failure) {
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
                        return compiler.newSession();
                    }

                    @Override
                    public void close() {
                        compiler.close();
                        ContractKitChecksTest.<RuntimeException>sneakyThrow(failure.get());
                    }
                };
            }
        };
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

    @Test
    @DisplayName("a language that keeps an action's variables in its session fails the variable check (#584)")
    void sessionVariablesFail() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(sessionVariables(new ToyExpressionLanguage(), false), "actionVariablesStayLocal"));

        assertEquals("a later rule read the variable 'y' an action declared: {seen=2}", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose actions read the variables an earlier run declared fails the variable check (#584)")
    void earlierRunsVariablesFail() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(sessionVariables(new ToyExpressionLanguage(), true), "actionVariablesStayLocal"));

        assertEquals("a later run read the variable 'y' an action declared in an earlier one, although the rule that"
                + " declares it didn't fire: {seen=2}", failure.getMessage());
    }

    @Test
    @DisplayName("a language that reads a name it doesn't know as null, or refuses it at load, passes the variable"
            + " check (#584)")
    void unknownNamesPass() {
        assertDoesNotThrow(() -> runCheck(unknownNames(new ToyExpressionLanguage(), false),
                "actionVariablesStayLocal"));
        assertDoesNotThrow(() -> runCheck(unknownNames(new ToyExpressionLanguage(), true),
                "actionVariablesStayLocal"));
    }

    @Test
    @DisplayName("a contract test that names output as the fact name its language rejects fails the fact-name check"
            + " (#584)")
    void outputAsUnusableNameFails() {
        // The engine rejects output itself, so a language that accepts every name, as the toy does, would pass.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String unusableFactName() {
                return "output";
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "unusableFactNameRejected"));

        assertTrue(failure.getMessage().startsWith("unusableFactName() must return a name the language itself"
                + " rejects"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose checkFactName accepts the name its contract test says it rejects fails the"
            + " fact-name check, naming checkFactName (#618)")
    void unusableFactNameAcceptedFails() {
        // The toy fails on a name that isn't a fact, so the check's rule must be given every fact it reads.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String unusableFactName() {
                return "bad";
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "unusableFactNameRejected"));

        // Nothing was thrown, so the run reached checkFactName and didn't fail on x, which the rule reads.
        assertTrue(failure.getMessage().startsWith("unusableFactName() returned 'bad', but run() didn't throw an"
                + " IllegalArgumentException for a fact with that name: check the language's checkFactName"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("nothing was thrown"), failure.getMessage());
    }

    @Test
    @DisplayName("a contract test that names x as the fact name its language rejects fails the fact-name check (#618)")
    void xAsUnusableNameFails() {
        // The check's rule reads x and the run supplies it, so without the guard the check would build facts with x
        // twice and fail on the duplicate name, not on the name the contract test chose.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String unusableFactName() {
                return "x";
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "unusableFactNameRejected"));

        assertTrue(failure.getMessage().startsWith("unusableFactName() must not be x, which the check's rule reads"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that rejects a fact name its contract test says it accepts fails the usable-name check"
            + " (#584)")
    void tooStrictFactNameCheckFails() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return lettersOnly(new ToyExpressionLanguage());
            }

            @Override
            protected Collection<String> usableFactNames() {
                return List.of("score", "credit_score2");
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "usableFactNamesAccepted"));

        assertTrue(failure.getMessage().startsWith("a rule couldn't use the fact name 'credit_score2', which"
                + " usableFactNames() says it can"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose rule on a listed fact name doesn't put its value fails the usable-name check, naming"
            + " the name (#584)")
    void usableFactNameNotReadFails() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String putFact(String key, String fact) {
                return "put wrong " + fact;
            }

            @Override
            protected Collection<String> usableFactNames() {
                return List.of("x_1");
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "usableFactNamesAccepted"));

        assertEquals("a rule on the fact name 'x_1' didn't put its value: expected: <{seen=1}> but was: <{wrong=1}>",
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that accepts the fact names its contract test lists passes the usable-name check, and"
            + " one that lists none skips it (#584)")
    void usableFactNamesPass() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected Collection<String> usableFactNames() {
                return List.of("credit_score2", "x_1");
            }
        };

        assertDoesNotThrow(() -> runCheck(test, "usableFactNamesAccepted"));
        assertThrows(TestAbortedException.class,
                () -> runCheck(new ToyExpressionLanguageContractTest(), "usableFactNamesAccepted"));
    }

    @Test
    @DisplayName("a language whose compiler throws when it's closed fails the compiler check (#584)")
    void throwingCompilerCloseFails() {
        ExpressionLanguage language = throwingCompilerClose(new ToyExpressionLanguage(),
                () -> new IllegalStateException("failed to release the runtime"));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "compilerClosed"));

        assertEquals("a compiler's close() threw java.lang.IllegalStateException: failed to release the runtime,"
                + " which the engine only logs at WARN", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose compiler throws an Error the engine only logs when it's closed fails the compiler"
            + " check (#621)")
    void compilerCloseThrowingANonFatalErrorFails() {
        // An AssertionError or a StackOverflowError, which the engine logs at WARN, as it does an exception: only
        // another VirtualMachineError is rethrown.
        ExpressionLanguage asserting = throwingCompilerClose(new ToyExpressionLanguage(),
                () -> new AssertionError("runtime still in use"));
        ExpressionLanguage overflowing = throwingCompilerClose(new ToyExpressionLanguage(),
                () -> new StackOverflowError("released recursively"));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(asserting, "compilerClosed"));
        AssertionFailedError overflow = assertThrows(AssertionFailedError.class,
                () -> runCheck(overflowing, "compilerClosed"));

        assertEquals("a compiler's close() threw java.lang.AssertionError: runtime still in use, which the engine only"
                + " logs at WARN", failure.getMessage());
        assertEquals("a compiler's close() threw java.lang.StackOverflowError: released recursively, which the engine"
                + " only logs at WARN", overflow.getMessage());
    }

    @Test
    @DisplayName("a compiler check that fails reports its own failure, with what closing the compiler threw attached"
            + " (#584)")
    void compilerCloseFailureAttached() {
        IllegalStateException cached = new IllegalStateException("failed to release the runtime");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> runCheck(
                wrongKey(throwingCompilerClose(new ToyExpressionLanguage(), () -> cached)), "compilerClosed"));

        assertEquals("expected: <{seen=1}> but was: <{wrong=1}>", failure.getMessage());
        assertEquals(List.of(cached), Arrays.asList(failure.getSuppressed()));
    }

    @Test
    @DisplayName("a contract test whose assignment() returns null skips the assignment check (#594)")
    void noAssignmentSkipped() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String assignment(String fact, int value) {
                return null;
            }
        };

        TestAbortedException skipped = assertThrows(TestAbortedException.class,
                () -> runCheck(test, "conditionAssignmentRejected"));

        assertEquals("Assumption failed: the language's conditions can't assign a fact", skipped.getMessage());
    }

    @Test
    @DisplayName("a session check that fails reports its own failure, with what each session's close() threw attached"
            + " (#594)")
    void sessionCloseFailuresAttached() {
        // Two copies when the rules load, so two sessions, each throwing an exception of its own. The engine only
        // logs them, so nothing but the check attaches them.
        ExpressionLanguage language = closingWith(() -> {
            throw new IllegalStateException("the session's runtime was already shut down");
        });

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(wrongKey(language), "sessionsClosed"));

        assertEquals("expected: <{seen=1}> but was: <{wrong=1}>", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length, () -> Arrays.toString(failure.getSuppressed()));
        for (Throwable suppressed : failure.getSuppressed()) {
            assertEquals("the session's runtime was already shut down", suppressed.getMessage());
        }
    }

    @Test
    @DisplayName("one exception that every session's close() throws is attached to the session check's failure once"
            + " (#594)")
    void cachedSessionCloseFailureAttachedOnce() {
        IllegalStateException cached = new IllegalStateException("the session's runtime was already shut down");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(wrongKey(closingWith(() -> {
                    throw cached;
                })), "sessionsClosed"));

        assertEquals(List.of(cached), Arrays.asList(failure.getSuppressed()));
    }

    @Test
    @DisplayName("one fatal error that every session's close() throws is attached to the session check's failure"
            + " once, although closing the engine rethrows it (#594)")
    void cachedFatalSessionCloseAttachedOnce() {
        InternalError cached = new InternalError("the session's native runtime crashed");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(wrongKey(closingWith(() -> {
                    throw cached;
                })), "sessionsClosed"));

        assertEquals(List.of(cached), Arrays.asList(failure.getSuppressed()));
    }
}
