package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.runCheck;
import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.withSessions;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Each of the contract test kit's checks must close the engine it builds, whether the check passes or fails, or a
 * language whose compilers and sessions hold a native or pooled resource leaks one with every check (#534).
 */
@DisplayName("the contract test kit closes every engine its checks build")
class ContractKitClosesEnginesTest {

    /** What a language created while one check ran, and how much of it was closed. */
    private static final class Counts {

        private final AtomicInteger compilers = new AtomicInteger();
        private final AtomicInteger compilersClosed = new AtomicInteger();
        private final AtomicInteger sessions = new AtomicInteger();
        private final AtomicInteger sessionsClosed = new AtomicInteger();

        boolean leaked() {
            return compilers.get() != compilersClosed.get() || sessions.get() != sessionsClosed.get();
        }

        @Override
        public String toString() {
            return "compilers " + compilersClosed + " of " + compilers + " closed, sessions " + sessionsClosed + " of "
                    + sessions + " closed";
        }
    }

    /**
     * Wraps a language so that it counts the compilers it creates and their closes, and gives each run a session of
     * its own that counts its close. The session isn't {@link Session#none()}, so the engine keeps a copy of the rules
     * for each session, as it does for a language that keeps state. The toy's expressions ignore their session.
     */
    private static ExpressionLanguage counting(ExpressionLanguage language, Counts counts) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                counts.compilers.incrementAndGet();
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
                        counts.sessions.incrementAndGet();
                        return new Session() {
                            @Override
                            public void close() {
                                counts.sessionsClosed.incrementAndGet();
                            }
                        };
                    }

                    @Override
                    public void checkFactName(String name) {
                        compiler.checkFactName(name);
                    }

                    @Override
                    public void close() {
                        counts.compilersClosed.incrementAndGet();
                        compiler.close();
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that it rejects what the toy rejects at load only when the rule runs: a condition that
     * assigns, such as {@code x = 2}, and an action that assigns the output. It also refuses a fact name with a
     * {@code -} in it, so the fact-name check runs rather than being skipped.
     */
    private static ExpressionLanguage lateRejecting(ExpressionLanguage language) {
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
                        if (expression.text().contains(" = ")) {
                            return (evaluation, session) -> {
                                throw new IllegalStateException("the condition assigns to a fact");
                            };
                        }
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        if (expression.text().startsWith("output =")) {
                            return (actionContext, session) -> {
                                throw new IllegalStateException("the action assigns the output");
                            };
                        }
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }

                    @Override
                    public void checkFactName(String name) {
                        if (name.contains("-")) {
                            throw new IllegalArgumentException("a fact name can't contain '-': " + name);
                        }
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

    /** Wraps a language so that each of its compilers throws {@code crash}, the one instance, when it's closed. */
    private static ExpressionLanguage crashingOnClose(ExpressionLanguage language, InternalError crash) {
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
                        compiler.checkFactName(name);
                    }

                    @Override
                    public void close() {
                        compiler.close();
                        throw crash;
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that it throws {@code crash}, the one instance, wherever it can: from each condition's
     * {@code evaluateWithDetail}, from closing each of its sessions, and from closing each of its compilers.
     */
    private static ExpressionLanguage crashingEverywhere(ExpressionLanguage language, InternalError crash) {
        return crashingOnClose(new ExpressionLanguage() {
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
                            public ConditionResult evaluateWithDetail(EvaluationContext evaluation, Session session) {
                                throw crash;
                            }
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    // A session of its own, not Session.none(), so that the engine and the kit close it.
                    @Override
                    public Session newSession() {
                        return new Session() {
                            @Override
                            public void close() {
                                throw crash;
                            }
                        };
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
        }, crash);
    }

    /**
     * Wraps a language so that a condition run against a fact {@code x} of 2 interrupts the thread it runs on and is
     * true, as a language might when a run is interrupted.
     */
    private static ExpressionLanguage interruptingOnTwo(ExpressionLanguage language) {
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
                            if (Integer.valueOf(2).equals(evaluation.facts().get("x"))) {
                                Thread.currentThread().interrupt();
                                return true;
                            }
                            return condition.evaluate(evaluation, session);
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

    /** Whether a check failed with Throwable's refusal to suppress an exception in itself, or with one it caused. */
    private static boolean selfSuppression(Throwable failure) {
        // Bounded, in case two causes name each other.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException
                    && "Self-suppression not permitted".equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /** The checks the kit has, found as JUnit finds them. */
    private static List<Method> checks() {
        return Arrays.stream(ExpressionLanguageContractTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class)).toList();
    }

    /**
     * Runs every one of the kit's checks on a contract test that {@code tests} makes for the counts it's given, and
     * asserts that each check closed every compiler and session it created, and that every check that wasn't
     * skipped created at least one, so the counting can't pass by counting nothing. A check may pass or fail: its
     * verdict isn't what this is about.
     */
    private static void assertEveryCheckCloses(Function<Counts, ExpressionLanguageContractTest> tests) {
        List<String> leaks = new ArrayList<>();
        List<String> createdNothing = new ArrayList<>();
        List<Method> checks = checks();
        for (Method check : checks) {
            Counts counts = new Counts();
            boolean skipped = false;
            try {
                runCheck(tests.apply(counts), check.getName());
            } catch (TestAbortedException e) {
                skipped = true;
            } catch (Throwable e) {
                // The check's verdict, which some checks give against the toy on purpose.
            }
            if (counts.leaked()) {
                leaks.add(check.getName() + ": " + counts);
            }
            if (!skipped && counts.compilers.get() == 0) {
                createdNothing.add(check.getName());
            }
        }

        assertFalse(checks.isEmpty(), "found none of the kit's checks");
        assertEquals(List.of(), leaks, "checks that left a compiler or a session unclosed");
        assertEquals(List.of(), createdNothing, "checks that ran and created no compiler");
    }

    @Test
    @DisplayName("every check closes what the toy language created, whether it passes or fails (#534)")
    void everyCheckClosesTheToysEngine() {
        assertEveryCheckCloses(counts -> new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return counting(new ToyExpressionLanguage(), counts);
            }
        });
    }

    @Test
    @DisplayName("every check closes its engine for a language that rejects at run time what the toy rejects at load"
            + " (#534)")
    void everyCheckClosesALateRejectingLanguagesEngine() {
        // Every check runs: whole numbers are compared by value, which fails that check against the toy, and a fact
        // name is refused, so neither check is skipped.
        assertEveryCheckCloses(counts -> new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return counting(lateRejecting(new ToyExpressionLanguage()), counts);
            }

            @Override
            protected boolean comparesWholeNumbersByValue() {
                return true;
            }

            @Override
            protected String unusableFactName() {
                return "x-y";
            }
        });
    }

    @Test
    @DisplayName("a check that fails reports its own failure, with the fatal error closing its engine threw attached"
            + " (#534)")
    void closeFailureAttachedToTheChecksFailure() {
        InternalError crash = new InternalError("the session's native runtime crashed");
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return withSessions(new ToyExpressionLanguage(), () -> new Session() {
                    @Override
                    public void close() {
                        throw crash;
                    }
                });
            }

            // The toy compares whole numbers by type, so the check fails, and the engine then fails to close.
            @Override
            protected boolean comparesWholeNumbersByValue() {
                return true;
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "conditionReadsWholeNumbers"));

        assertTrue(failure.getMessage().startsWith("the rule didn't fire for a Long fact"), failure.getMessage());
        assertEquals(List.of(crash), Arrays.asList(failure.getSuppressed()));
    }

    @Test
    @DisplayName("a check that fails with the error closing its engine throws again reports that error, not a"
            + " self-suppression (#534)")
    void sameErrorFromCloseNotSelfSuppressed() {
        // A reload closes the replaced compiler, so load() throws the crash, and closing the engine then throws the
        // same instance. Adding an exception to itself as suppressed throws IllegalArgumentException.
        InternalError crash = new InternalError("the compiler's native runtime crashed");

        InternalError failure = assertThrows(InternalError.class,
                () -> runCheck(crashingOnClose(new ToyExpressionLanguage(), crash), "compilerClosed"));

        assertSame(crash, failure);
        assertEquals(List.of(), Arrays.asList(failure.getSuppressed()));
    }

    @Test
    @DisplayName("no check fails with a self-suppression for a language that throws one error from its compilers'"
            + " close(), or from its conditions and its sessions' close() as well (#534)")
    void noCheckSelfSuppresses() {
        // Both: a language that crashes everywhere fails most checks at their first run, so the one that crashes only
        // when a compiler closes is what reaches a reload, and the rest of each check.
        List<Function<InternalError, ExpressionLanguage>> languages = List.of(
                crash -> crashingOnClose(new ToyExpressionLanguage(), crash),
                crash -> crashingEverywhere(new ToyExpressionLanguage(), crash));
        List<String> selfSuppressed = new ArrayList<>();
        for (int i = 0; i < languages.size(); i++) {
            for (Method check : checks()) {
                try {
                    runCheck(languages.get(i).apply(new InternalError("the language's native runtime crashed")),
                            check.getName());
                } catch (Throwable e) {
                    // The check's verdict, which the crash decides for most of them, unless it's a self-suppression.
                    if (selfSuppression(e)) {
                        selfSuppressed.add(check.getName() + " with language " + i);
                    }
                }
            }
        }

        assertEquals(List.of(), selfSuppressed, "checks that failed with a self-suppression");
    }

    @Test
    @DisplayName("a check that fails on an interrupted thread reports its own failure, not the interrupt of its wait"
            + " for its workers (#534)")
    void interruptedCheckReportsItsOwnFailure() {
        // The check's last run, on its own thread, is interrupted by the condition. The engine stops the run and
        // leaves the thread interrupted, and the check then stops its workers.
        Throwable failure;
        boolean interrupted;
        try {
            failure = assertThrows(Throwable.class,
                    () -> runCheck(interruptingOnTwo(new ToyExpressionLanguage()), "copiesAtLoad"));
        } finally {
            // Cleared, so that the tests after this one run on a thread that isn't interrupted.
            interrupted = Thread.interrupted();
        }

        assertInstanceOf(RuleExecutionException.class, failure, failure::toString);
        assertEquals("run() was interrupted during rule 'r'", failure.getMessage());
        assertTrue(interrupted, "the check's thread is no longer interrupted");
    }
}
