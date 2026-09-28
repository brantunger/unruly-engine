package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** What the kit writes for an object of the language's whose {@code toString()} throws, after its class name. */
    static final String UNAVAILABLE = " (message unavailable: java.lang.IllegalStateException)";

    /** An exception whose {@code getMessage()} throws, and so its {@code toString()}: a message that can't be read. */
    static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("the message's field is not set");
        }
    }

    /** A session whose {@code toString()} throws. */
    private static final class UnprintableSession implements Session {

        @Override
        public String toString() {
            throw new IllegalStateException("the session's runtime can't print it");
        }
    }

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

    /**
     * Wraps a language so that each of its compilers has a runtime that all its sessions share, which its conditions
     * need, and that each session it creates is new. When {@code closeTearsDown}, closing any one session shuts that
     * runtime down, and every other session's conditions fail.
     */
    private static ExpressionLanguage sharedRuntime(ExpressionLanguage language, boolean closeTearsDown) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                AtomicBoolean running = new AtomicBoolean(true);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> {
                            if (!running.get()) {
                                throw new IllegalStateException(
                                        "shared runtime was torn down by another session's close()");
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
                        return new Session() {
                            @Override
                            public void close() {
                                if (closeTearsDown) {
                                    running.set(false);
                                }
                            }
                        };
                    }
                };
            }
        };
    }

    /** Wraps the toy so that each session it creates is new, and throws when it's closed on another thread. */
    private static ExpressionLanguage threadBound() {
        return withSessions(new ToyExpressionLanguage(), () -> {
            Thread creator = Thread.currentThread();
            return new Session() {
                @Override
                public void close() {
                    if (!Thread.currentThread().equals(creator)) {
                        throw new IllegalStateException("the session's runtime is bound to the thread that made it");
                    }
                }
            };
        });
    }

    /**
     * Wraps the toy so that each session it creates is new, and throws when it's closed while another is still open,
     * as a session that pops a context stack its compiler's sessions share might.
     */
    private static ExpressionLanguage closeFailsWhileAnotherOpen() {
        Set<Session> open = ConcurrentHashMap.newKeySet();
        return withSessions(new ToyExpressionLanguage(), () -> {
            Session session = new Session() {
                @Override
                public void close() {
                    open.remove(this);
                    if (!open.isEmpty()) {
                        throw new IllegalStateException("another session of the compiler is still in use");
                    }
                }
            };
            open.add(session);
            return session;
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

    /**
     * Wraps a language so that an action {@code let NAME = FACT ; put KEY NAME} keeps its variable in a map compiled
     * into the action, which every run shares. The first two runs of the action wait for each other between declaring
     * the variable and putting it, each on its own thread, so one of them puts the other's value.
     */
    private static ExpressionLanguage sharedActionVariables(ExpressionLanguage language) {
        CountDownLatch bothDeclared = new CountDownLatch(2);
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
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length != 8 || !"let".equals(tokens[0]) || !"put".equals(tokens[5])) {
                            return compiler.compileAction(expression);
                        }
                        Map<String, Object> variables = new ConcurrentHashMap<>();
                        return (actionContext, session) -> {
                            variables.put(tokens[1], actionContext.facts().get(tokens[3]));
                            // Later runs don't wait. A timeout, so a check that runs one run at a time only waits.
                            bothDeclared.countDown();
                            bothDeclared.await(10, TimeUnit.SECONDS);
                            return ActionResult.set(Map.of(tokens[6], variables.get(tokens[7])));
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

    /**
     * Wraps a language so that the first {@code runs} conditions it evaluates wait for each other, each on its own
     * thread, so that as many runs at once hold a copy of the rules each. If {@code failsWhenShared}, a condition fails
     * when another run is using its session, as a session whose state two runs at once corrupt would make it.
     */
    private static ExpressionLanguage overlapping(ExpressionLanguage language, int runs, boolean failsWhenShared) {
        CountDownLatch together = new CountDownLatch(runs);
        Set<Session> inUse = ConcurrentHashMap.newKeySet();
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
                            // Marked before this run is counted, so the run it shares a session with is still waiting.
                            boolean own = inUse.add(session);
                            // A timeout, so a check that runs one run at a time only waits.
                            together.countDown();
                            if (!own && failsWhenShared) {
                                throw new IllegalStateException("the session is in use by another run");
                            }
                            try {
                                together.await(10, TimeUnit.SECONDS);
                                return condition.evaluate(evaluation, session);
                            } finally {
                                if (own) {
                                    inUse.remove(session);
                                }
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

                    @Override
                    public void close() {
                        compiler.close();
                    }
                };
            }
        };
    }

    /**
     * The toy, with sessions whose {@code newSession()} returns a new one twice, and from then on one it already
     * returned, and whose first four runs overlap, so that the engine asks for four sessions at once. If
     * {@code failsWhenShared}, the run that finds its session in use by another fails.
     */
    private static ExpressionLanguage reusedAfterTwo(boolean failsWhenShared) {
        AtomicInteger created = new AtomicInteger();
        Session reused = new Session() {
        };
        return overlapping(withSessions(new ToyExpressionLanguage(), () -> created.incrementAndGet() > 2
                ? reused
                : new Session() {
                }), 4, failsWhenShared);
    }

    /**
     * Wraps a language so that an action {@code put KEY OPERAND} whose key is one of {@code keys} reads its operand and
     * then fails.
     */
    private static ExpressionLanguage readThenThrow(ExpressionLanguage language, Set<String> keys) {
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
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length != 3 || !"put".equals(tokens[0]) || !keys.contains(tokens[1])) {
                            return compiler.compileAction(expression);
                        }
                        // The toy reads a condition that is one operand as the operand's value.
                        CompiledCondition operand = compiler.compileCondition(
                                new Expression(expression.ruleName(), ExpressionKind.CONDITION, tokens[2]));
                        return (actionContext, session) -> {
                            operand.evaluate(actionContext, session);
                            throw new IllegalStateException("the action failed after reading " + tokens[2]);
                        };
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

    /**
     * Wraps a language so that an action {@code put KEY OPERAND} keeps the output it writes to in a
     * {@link ThreadLocal}, and looks it up again after reading the operand, as an adapter over a runtime whose context
     * is bound to the thread might. A run started on the same thread while the operand is read, by a getter, replaces
     * it, so the action writes to that run's output.
     */
    private static ExpressionLanguage threadLocalOutput(ExpressionLanguage language) {
        ThreadLocal<Map<String, Object>> output = new ThreadLocal<>();
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
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length != 3 || !"put".equals(tokens[0])) {
                            return compiler.compileAction(expression);
                        }
                        // The toy reads a condition that is one operand as the operand's value.
                        CompiledCondition operand = compiler.compileCondition(
                                new Expression(expression.ruleName(), ExpressionKind.CONDITION, tokens[2]));
                        return (actionContext, session) -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> target = (Map<String, Object>) actionContext.output();
                            output.set(target);
                            Object value = operand.evaluate(actionContext, session);
                            output.get().put(tokens[1], value);
                            return ActionResult.done();
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
    @DisplayName("a language whose session's close() tears down what its compiler's other sessions share fails the"
            + " nested-run check (#696)")
    void tornDownRuntimeFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(sharedRuntime(new ToyExpressionLanguage(), true), "sessionClosedWhileAnotherRuns"));

        assertEquals("the run failed after a run nested in it ended and its session was closed, so a session's"
                + " close(), or the nested run's session, broke what its compiler's other sessions share: Failed to"
                + " evaluate condition for rule 'b': shared runtime was torn down by another session's close()",
                failure.getMessage());
    }

    @Test
    @DisplayName("the nested-run check can't be undone by configure(): it still fails a close() that tears down what"
            + " the sessions share (#696)")
    void tornDownRuntimeFailsWhateverConfigureSets() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return sharedRuntime(new ToyExpressionLanguage(), true);
            }

            // Enough copies, all made when the rules load, that no run would need an extra one.
            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.maxCopies(4).copiesAtLoad(4);
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "sessionClosedWhileAnotherRuns"));

        assertTrue(failure.getMessage().endsWith("shared runtime was torn down by another session's close()"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose session's close() throws while another session of its compiler is in use fails the"
            + " nested-run check (#696)")
    void closeWhileAnotherInUseFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(closeFailsWhileAnotherOpen(), "sessionClosedWhileAnotherRuns"));

        assertEquals("a session's close() threw java.lang.IllegalStateException: another session of the compiler is"
                + " still in use, which the engine only logs at WARN", failure.getMessage());
    }

    @Test
    @DisplayName("the nested-run check says a run failed before a run could be nested in it, rather than blaming a"
            + " session's close() (#696)")
    void nestedRunNeverStartedReported() {
        // Every condition fails, so rule a's does, before the listener can start the nested run.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return assigningConditions(new ToyExpressionLanguage(), (evaluation, session) -> {
                    throw new IllegalStateException("the condition failed");
                });
            }

            @Override
            protected String factEquals(String fact, int value) {
                return fact + " = " + value;
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "sessionClosedWhileAnotherRuns"));

        assertEquals("the run failed before a run could be nested in it: Failed to evaluate condition for rule 'a':"
                + " the condition failed", failure.getMessage());
    }

    @Test
    @DisplayName("the nested-run check says both runs failed, with the nested run's failure attached, rather than"
            + " blaming a session's close() (#696)")
    void bothRunsFailedReported() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(throwingActions(new ToyExpressionLanguage()), "sessionClosedWhileAnotherRuns"));

        assertEquals("the run failed, and so did the run nested in it, which is attached: Failed to execute action for"
                + " rule 'a': the action failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("Failed to execute action for rule 'a': the action failed",
                failure.getSuppressed()[0].getMessage());
    }

    @Test
    @DisplayName("the nested-run check says which run returned the wrong output (#696)")
    void wrongOutputNamesTheRun() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(wrongKey(new ToyExpressionLanguage()), "sessionClosedWhileAnotherRuns"));

        // The expected map's order isn't fixed.
        assertTrue(failure.getMessage().startsWith("the run nested in the check's run ==> expected: <"),
                failure.getMessage());
        assertTrue(failure.getMessage().endsWith("> but was: <{wrong=1}>"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose sessions leave what they share running passes the nested-run check, and so do one"
            + " with no session and one whose sessions close only on their own thread (#696)")
    void runtimeKeptPasses() {
        assertDoesNotThrow(() -> runCheck(sharedRuntime(new ToyExpressionLanguage(), false),
                "sessionClosedWhileAnotherRuns"));
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "sessionClosedWhileAnotherRuns"));
        // The nested run's copy is made and closed on the check's thread, and so is the outer run's.
        assertDoesNotThrow(() -> runCheck(threadBound(), "sessionClosedWhileAnotherRuns"));
    }

    @Test
    @DisplayName("a language whose session's close() throws on a thread other than the one that made it fails the"
            + " other-thread check (#696)")
    void threadBoundCloseFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(threadBound(), "sessionClosedOnAnotherThread"));

        assertEquals("a session's close() threw java.lang.IllegalStateException: the session's runtime is bound to the"
                + " thread that made it, which the engine only logs at WARN", failure.getMessage());
    }

    @Test
    @DisplayName("the other-thread check can't be undone by configure(): it still fails a thread-bound close() (#696)")
    void threadBoundCloseFailsWhateverConfigureSets() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return threadBound();
            }

            // Copies made on this thread when the rules load, which the run would use instead of making its own.
            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.maxCopies(4).copiesAtLoad(4);
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "sessionClosedOnAnotherThread"));

        assertTrue(failure.getMessage().startsWith("a session's close() threw java.lang.IllegalStateException: "),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose sessions close on any thread passes the other-thread check, and so do one with no"
            + " session and one whose close() tears down what the sessions share (#696)")
    void anyThreadClosePasses() {
        assertDoesNotThrow(() -> runCheck(withSessions(new ToyExpressionLanguage(), () -> new Session() {
        }), "sessionClosedOnAnotherThread"));
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "sessionClosedOnAnotherThread"));
        // Its one session is closed when the engine is, once the run is over.
        assertDoesNotThrow(() -> runCheck(sharedRuntime(new ToyExpressionLanguage(), true),
                "sessionClosedOnAnotherThread"));
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
    @DisplayName("a contract test that names a blank fact name as the one its language rejects fails the fact-name"
            + " check (#712)")
    void blankUnusableNameFails() {
        // The engine rejects a blank name itself, so a language that accepts every name, as the toy does, would pass.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String unusableFactName() {
                return " ";
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

    @Test
    @DisplayName("a compiler check that fails for a close() whose exception can't be read reports the kit's own"
            + " message (#657)")
    void unreadableCompilerCloseReported() {
        ExpressionLanguage language = throwingCompilerClose(new ToyExpressionLanguage(), Unreadable::new);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "compilerClosed"));

        assertEquals("a compiler's close() threw " + Unreadable.class.getName() + UNAVAILABLE + ", which the engine"
                + " only logs at WARN", failure.getMessage());
    }

    @Test
    @DisplayName("a session check that fails for a close() whose exception can't be read reports the kit's own"
            + " message (#657)")
    void unreadableSessionCloseReported() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(closingWith(() -> {
                    throw new Unreadable();
                }), "sessionsClosed"));

        assertEquals("a session's close() threw " + Unreadable.class.getName() + UNAVAILABLE + ", which the engine"
                + " only logs at WARN", failure.getMessage());
    }

    @Test
    @DisplayName("a session check that fails for a shared session that can't be printed reports the kit's own message"
            + " (#657)")
    void unprintableSharedSessionReported() {
        Session shared = new UnprintableSession();

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(withSessions(new ToyExpressionLanguage(), () -> shared), "sessionsClosed"));

        assertEquals("newSession() returned the same session for two copies of the rules, so two runs use it at once"
                + " and the engine closes it twice: " + UnprintableSession.class.getName() + UNAVAILABLE,
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose actions keep their variables where every run shares them fails the concurrent-runs"
            + " check (#764)")
    void sharedActionVariablesFail() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(sharedActionVariables(new ToyExpressionLanguage()), "concurrentRuns"));

        assertTrue(failure.getMessage().contains("copied="), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose newSession() returns a session twice after its second call fails the"
            + " concurrent-runs check (#764)")
    void sessionReusedAfterTwoFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(reusedAfterTwo(false), "concurrentRuns"));

        assertTrue(failure.getMessage().startsWith("newSession() returned the same session for two copies of the"
                + " rules, so two runs use it at once and the engine closes it twice: "), failure.getMessage());
    }

    @Test
    @DisplayName("a contract test whose copyThroughVariable() returns null still has the concurrent-runs check watch"
            + " the sessions (#764)")
    void sessionReusedAfterTwoFailsWithoutTheVariable() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return reusedAfterTwo(false);
            }

            @Override
            protected String copyThroughVariable(String key, String fact) {
                return null;
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "concurrentRuns"));

        assertTrue(failure.getMessage().startsWith("newSession() returned the same session for two copies of the"
                + " rules"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose runs have variables and sessions of their own passes the concurrent-runs check, and"
            + " so does one with no session (#764)")
    void ownVariablesAndSessionsPassConcurrentRuns() {
        assertDoesNotThrow(() -> runCheck(overlapping(withSessions(new ToyExpressionLanguage(), () -> new Session() {
        }), 4, true), "concurrentRuns"));
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "concurrentRuns"));
    }

    @Test
    @DisplayName("a language that keeps an action's output in per-thread state fails the check that starts a run inside"
            + " an action (#765)")
    void threadLocalOutputFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(threadLocalOutput(new ToyExpressionLanguage()), "nestedRunInsideAnAction"));

        assertEquals("the run started inside the action ==> expected: <{inner=2}> but was: <{inner=2, seen=7}>",
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that keeps a run's state on the stack or in its sessions passes the check that starts a"
            + " run inside an action (#765)")
    void ownRunStatePassesNestedRunInsideAnAction() {
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "nestedRunInsideAnAction"));
        assertDoesNotThrow(() -> runCheck(withSessions(new ToyExpressionLanguage(), () -> new Session() {
        }), "nestedRunInsideAnAction"));
    }

    @Test
    @DisplayName("a contract test whose putFactProperty() returns null skips the check that starts a run inside an"
            + " action (#765)")
    void noPutFactPropertySkipped() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String putFactProperty(String key, String fact, String property) {
                return null;
            }
        };

        TestAbortedException skipped = assertThrows(TestAbortedException.class,
                () -> runCheck(test, "nestedRunInsideAnAction"));

        assertEquals("Assumption failed: the language's actions can't read a fact's property", skipped.getMessage());
    }

    @Test
    @DisplayName("the concurrent-runs check reports a session returned twice, with the run it made fail as the cause,"
            + " rather than the run's failure alone (#764)")
    void sessionReusedAfterTwoReportedOverTheRunItFailed() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(reusedAfterTwo(true), "concurrentRuns"));

        assertTrue(failure.getMessage().startsWith("newSession() returned the same session for two copies of the"
                + " rules"), failure.getMessage());
        assertInstanceOf(ExecutionException.class, failure.getCause());
    }

    @Test
    @DisplayName("the concurrent-runs check leaves its thread interrupted when it's interrupted while it waits for the"
            + " runs (#764)")
    void interruptedConcurrentRunsStaysInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> runCheck(new ToyExpressionLanguage(), "concurrentRuns"));

            assertTrue(Thread.currentThread().isInterrupted(), "the check cleared the thread's interrupt");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("the concurrent-runs check fails a run that throws with the worker's exception, when no session was"
            + " returned twice (#764)")
    void throwingRunNotWrapped() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> runCheck(throwingActions(new ToyExpressionLanguage()), "concurrentRuns"));

        assertInstanceOf(RuleExecutionException.class, failure.getCause());
    }

    @Test
    @DisplayName("the check that starts a run inside an action fails when that run fails (#765)")
    void failedRunInsideAnActionReported() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> runCheck(
                readThenThrow(new ToyExpressionLanguage(), Set.of("inner")), "nestedRunInsideAnAction"));

        assertTrue(failure.getMessage().startsWith("the run started inside the action failed: "),
                failure.getMessage());
        // The engine's message, without the class of the engine's internal exception that carries it.
        assertFalse(failure.getMessage().contains("ReportedFailure"), failure.getMessage());
        assertInstanceOf(RuleExecutionException.class, failure.getCause());
    }

    @Test
    @DisplayName("the check that starts a run inside an action fails an action that never reads the fact's property"
            + " (#765)")
    void actionThatNeverReadsTheGetterFails() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String putFactProperty(String key, String fact, String property) {
                return "put " + key + " 7";
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "nestedRunInsideAnAction"));

        assertTrue(failure.getMessage().startsWith("the action didn't read nest.value, so no run was started inside"
                + " it"), failure.getMessage());
    }

    @Test
    @DisplayName("the check that starts a run inside an action says the run around it failed after that run ended,"
            + " with the engine's failure as the cause (#765)")
    void runAroundTheNestedRunFailedReported() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> runCheck(
                readThenThrow(new ToyExpressionLanguage(), Set.of("seen")), "nestedRunInsideAnAction"));

        assertTrue(failure.getMessage().startsWith("the run failed after a run started inside its action ended, so"
                + " the nested run may have replaced or removed state the action kept for its own run: "),
                failure.getMessage());
        assertInstanceOf(RuleExecutionException.class, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    @DisplayName("the check that starts a run inside an action says both runs failed, with the nested run's failure"
            + " attached (#765)")
    void bothRunsFailedInsideAnActionReported() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> runCheck(
                readThenThrow(new ToyExpressionLanguage(), Set.of("seen", "inner")), "nestedRunInsideAnAction"));

        assertTrue(failure.getMessage().startsWith("the run failed after a run started inside its action failed too,"
                + " which is attached: "), failure.getMessage());
        assertInstanceOf(RuleExecutionException.class, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(RuleExecutionException.class, failure.getSuppressed()[0]);
    }

    @Test
    @DisplayName("the check that starts a run inside an action reports a run that fails before starting one as the"
            + " engine reported it (#765)")
    void runFailedBeforeNestingNotWrapped() {
        assertThrows(RuleExecutionException.class,
                () -> runCheck(throwingActions(new ToyExpressionLanguage()), "nestedRunInsideAnAction"));
    }
}
