package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** How one of a condition's two paths evaluates it, given the language's own condition. */
    @FunctionalInterface
    private interface ConditionPath {
        ConditionResult evaluate(CompiledCondition condition, EvaluationContext evaluation, Session session)
                throws Exception;
    }

    /** The language's own evaluateWithDetail. */
    private static final ConditionPath OWN = CompiledCondition::evaluateWithDetail;

    /** Whether the fact {@code x} is 1, compared by value, whatever kind of number it is. */
    private static final ConditionPath BY_VALUE = (condition, evaluation, session) -> ConditionResult.of(
            evaluation.facts().get("x") instanceof Number number
                    && new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) == 0);

    /** The language's own evaluateWithDetail for an {@code Integer} fact {@code x}, and a failure for any other. */
    private static final ConditionPath INTEGERS_ONLY = (condition, evaluation, session) -> {
        if (!(evaluation.facts().get("x") instanceof Integer)) {
            throw new IllegalStateException("compares Integers only");
        }
        return condition.evaluateWithDetail(evaluation, session);
    };

    /**
     * Wraps a language so that each condition's evaluate returns the value {@code evaluate} gives, and its
     * evaluateWithDetail what {@code detailed} gives.
     */
    private static ExpressionLanguage twoPaths(ExpressionLanguage language, ConditionPath evaluate,
                                               ConditionPath detailed) {
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
                                return evaluate.evaluate(condition, evaluation, session).value();
                            }

                            @Override
                            public ConditionResult evaluateWithDetail(EvaluationContext evaluation, Session session)
                                    throws Exception {
                                return detailed.evaluate(condition, evaluation, session);
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

    /** A session that counts the conditions evaluated with it. */
    private static final class CountingSession implements Session {

        private final AtomicInteger evaluations = new AtomicInteger();
    }

    /**
     * Wraps a language so that each condition's detail prints how many conditions its session has evaluated when
     * it's printed, not when it was made: a buffer the session reuses, which every later run overwrites.
     */
    private static ExpressionLanguage readsSessionWhenPrinted(ExpressionLanguage language) {
        return explainedBy(withSessions(language, CountingSession::new), (value, session) -> {
            AtomicInteger evaluations = ((CountingSession) session).evaluations;
            evaluations.incrementAndGet();
            return new Object() {
                @Override
                public String toString() {
                    return "evaluation " + evaluations.get();
                }
            };
        });
    }

    /** Wraps a language so that each condition's detail prints whether its session is closed yet. */
    private static ExpressionLanguage printsWhetherClosed(ExpressionLanguage language) {
        return explainedBy(withSessions(language, ClosingSession::new), (value, session) -> new Object() {
            @Override
            public String toString() {
                return ((ClosingSession) session).closed ? "closed" : "open";
            }
        });
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

        assertEquals("for x = 1 (Integer), evaluate returned a different value than evaluateWithDetail reported ==>"
                + " expected: <true> but was: <false>", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose evaluate compares whole numbers by type, and its evaluateWithDetail by value, fails"
            + " the agreement check (#584)")
    void byTypeEvaluateFails() {
        // The toy's own == compares by type.
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(twoPaths(new ToyExpressionLanguage(), OWN, BY_VALUE), "evaluateAgreesWithDetail"));

        assertEquals("for x = 1 (Long), evaluate returned a different value than evaluateWithDetail reported ==>"
                + " expected: <true> but was: <false>", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose evaluateWithDetail throws for a fact that its evaluate reads fails the agreement"
            + " check (#584)")
    void onlyDetailThrowsFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(twoPaths(new ToyExpressionLanguage(), OWN, INTEGERS_ONLY), "evaluateAgreesWithDetail"));

        assertTrue(failure.getMessage().startsWith("for x = 1 (Long), evaluateWithDetail threw"
                        + " java.lang.IllegalStateException: compares Integers only, but evaluate didn't"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose evaluate throws for a fact that its evaluateWithDetail reads fails the agreement"
            + " check (#584)")
    void onlyEvaluateThrowsFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(twoPaths(new ToyExpressionLanguage(), INTEGERS_ONLY, OWN), "evaluateAgreesWithDetail"));

        assertTrue(failure.getMessage().startsWith("for x = 1 (Long), evaluate threw, but evaluateWithDetail returned"
                + " false"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that throws from both evaluate and evaluateWithDetail for a fact passes the agreement"
            + " check (#584)")
    void bothPathsThrowingPasses() {
        assertDoesNotThrow(() -> runCheck(twoPaths(new ToyExpressionLanguage(), INTEGERS_ONLY, INTEGERS_ONLY),
                "evaluateAgreesWithDetail"));
    }

    @Test
    @DisplayName("a language whose newSession() returns null fails the agreement check, which runs outside an engine"
            + " (#594)")
    void nullSessionFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(withSessions(new ToyExpressionLanguage(), () -> null), "evaluateAgreesWithDetail"));

        assertEquals("newSession() returned null, which fails every run that needs a session ==> expected: not"
                + " <null>", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose condition detail reads its session when it's printed fails the detail check once"
            + " another run has used the session (#584)")
    void detailReadingTheSessionWhenPrintedFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(readsSessionWhenPrinted(new ToyExpressionLanguage()), "conditionDetail"));

        assertTrue(failure.getMessage().startsWith("the detail of rule 'matches' changed after another run"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose condition detail prints differently once its session is closed fails the detail"
            + " check (#584)")
    void detailChangedByTheCloseFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(printsWhetherClosed(new ToyExpressionLanguage()), "conditionDetail"));

        assertEquals("the detail of rule 'matches' changed once the engine closed its session ==> expected: <open> but"
                + " was: <closed>", failure.getMessage());
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
