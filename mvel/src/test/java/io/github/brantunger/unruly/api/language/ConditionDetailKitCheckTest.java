package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.runCheck;
import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.withSessions;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The contract test kit must fail a language whose condition detail can't be kept after the run, because it is the
 * session or needs the session open (#410), and a language whose {@code evaluate} disagrees with its
 * {@code evaluateWithDetail} (#509). Apart from {@link ContractKitChecksTest}, which has to compile without
 * {@link ConditionResult}.
 */
@DisplayName("the contract test kit fails a language whose condition detail can't outlive the run")
class ConditionDetailKitCheckTest {

    /**
     * Wraps a language so that its conditions explain themselves with what {@code detail} makes of the value and the
     * session.
     */
    private static ExpressionLanguage explainedBy(ExpressionLanguage language,
                                                  BiFunction<Object, Session, Object> detail) {
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
                        return new CompiledCondition() {
                            @Override
                            public Object evaluate(EvaluationContext evaluation, Session session) throws Exception {
                                return condition.evaluate(evaluation, session);
                            }

                            @Override
                            public ConditionResult evaluateWithDetail(EvaluationContext evaluation, Session session)
                                    throws Exception {
                                Object value = condition.evaluate(evaluation, session);
                                return ConditionResult.of(value, detail.apply(value, session));
                            }
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

    /** Wraps a language so that each condition's evaluate returns the opposite of what evaluateWithDetail reports. */
    private static ExpressionLanguage liar(ExpressionLanguage language) {
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
                        return new CompiledCondition() {
                            @Override
                            public Object evaluate(EvaluationContext evaluation, Session session) throws Exception {
                                return !Boolean.TRUE.equals(evaluateWithDetail(evaluation, session).value());
                            }

                            @Override
                            public ConditionResult evaluateWithDetail(EvaluationContext evaluation, Session session)
                                    throws Exception {
                                return condition.evaluateWithDetail(evaluation, session);
                            }
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

    /**
     * Wraps a language so that each session it creates and each compiler record in {@code closes} when they're closed,
     * and, if {@code throwing}, then throw.
     */
    private static ExpressionLanguage recordingCloses(ExpressionLanguage language, List<String> closes,
                                                      boolean throwing) {
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
                        return new Session() {
                            @Override
                            public void close() {
                                closed("session");
                            }
                        };
                    }

                    @Override
                    public void close() {
                        closed("compiler");
                    }

                    private void closed(String what) {
                        closes.add(what);
                        if (throwing) {
                            throw new IllegalStateException("the " + what + "'s runtime was already shut down");
                        }
                    }
                };
            }
        };
    }

    /** A session that knows whether it has been closed. */
    private static final class ClosingSession implements Session {

        private volatile boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Wraps a language so that each session it creates is new, and each condition's detail is that session. */
    private static ExpressionLanguage sessionAsDetail(ExpressionLanguage language) {
        return explainedBy(withSessions(language, ClosingSession::new), (value, session) -> session);
    }

    /** Wraps a language so that a condition's detail is its session only when the condition is false. */
    private static ExpressionLanguage sessionAsDetailWhenFalse(ExpressionLanguage language) {
        return explainedBy(withSessions(language, ClosingSession::new),
                (value, session) -> Boolean.TRUE.equals(value) ? "matched" : session);
    }

    /** Wraps a language so that each condition's detail reads its session when it's printed. */
    private static ExpressionLanguage readsSessionLater(ExpressionLanguage language) {
        return explainedBy(withSessions(language, ClosingSession::new), (value, session) -> new Object() {
            @Override
            public String toString() {
                if (((ClosingSession) session).closed) {
                    throw new IllegalStateException("read after its session was closed");
                }
                return "read from an open session";
            }
        });
    }

    @Test
    @DisplayName("a language whose condition detail is the session it ran with fails the detail check")
    void sessionAsDetailFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(sessionAsDetail(new ToyExpressionLanguage()), "conditionDetail"));

        assertTrue(failure.getMessage().startsWith("the condition's detail is the session it ran with, which the"
                + " engine gives to another run or closes: "), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose detail is the session only for a false condition fails the detail check")
    void sessionAsDetailWhenFalseFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(sessionAsDetailWhenFalse(new ToyExpressionLanguage()), "conditionDetail"));

        assertTrue(failure.getMessage().startsWith("the condition's detail is the session it ran with, which the"
                + " engine gives to another run or closes: "), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose condition detail needs its session open fails the detail check")
    void detailReadingTheSessionFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(readsSessionLater(new ToyExpressionLanguage()), "conditionDetail"));

        assertTrue(failure.getMessage().startsWith(
                "the condition's detail can't be read once the engine has closed its session"), failure.getMessage());
        assertTrue(failure.getMessage().contains("read after its session was closed"), failure.getMessage());
    }

    @Test
    @DisplayName("a detail that is its own value passes the detail check, with a session or without one")
    void detachedDetailPasses() {
        assertDoesNotThrow(() -> runCheck(explainedBy(withSessions(new ToyExpressionLanguage(), ClosingSession::new),
                (value, session) -> "a copy of what it read"), "conditionDetail"));
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "conditionDetail"));
    }

    @Test
    @DisplayName("a language whose evaluate disagrees with its evaluateWithDetail fails the agreement check (#509)")
    void liarFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(liar(new ToyExpressionLanguage()), "evaluateAgreesWithDetail"));

        assertEquals("for x = 1, evaluate returned a different value than evaluateWithDetail reported ==> expected:"
                + " <true> but was: <false>", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose evaluate returns what evaluateWithDetail reports passes the agreement check, with"
            + " a session or without one (#509)")
    void agreeingLanguagePasses() {
        List<String> closes = new CopyOnWriteArrayList<>();

        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "evaluateAgreesWithDetail"));
        assertDoesNotThrow(() -> runCheck(recordingCloses(new ToyExpressionLanguage(), closes, false),
                "evaluateAgreesWithDetail"));
        // Closed as the engine closes them: the session before the compiler that created it.
        assertEquals(List.of("session", "compiler"), closes);
    }

    @Test
    @DisplayName("a session or compiler whose close() throws doesn't fail the agreement check, which the engine only"
            + " logs (#509)")
    void closeFailuresIgnored() {
        List<String> closes = new CopyOnWriteArrayList<>();

        assertDoesNotThrow(() -> runCheck(recordingCloses(new ToyExpressionLanguage(), closes, true),
                "evaluateAgreesWithDetail"));
        // The compiler is still closed after its session's close() threw.
        assertEquals(List.of("session", "compiler"), closes);
    }
}
