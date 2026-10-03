package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.TestSupport;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
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

    /** The checks that repeat a failed run, and what each says failed. */
    private static final Map<String, String> REPEATED = Map.of(
            "conditionAssignmentRejected", "a condition that assigns to a fact",
            "conditionWritesRejected", "a condition that writes a fact's property",
            "missingPropertyFailsTheRun", "a misspelled property of a record fact",
            "outputNotReplaceable", "an action that replaces the output");

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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
                };
            }
        };
    }

    /** Wraps a language so that its actions compile on first use instead of when the rule is loaded. */
    private static ExpressionLanguage lazyActions(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (actionContext, session) ->
                                compiler.compileAction(expression).execute(actionContext, session);
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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return expression.text().contains(" = ") ? assignment : compiler.compileCondition(expression);
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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        if (expression.text().contains(".creditScor ")) {
                            throw new IllegalArgumentException("the fact has no property creditScor");
                        }
                        return compiler.compileCondition(expression);
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that every action it compiles waits for {@code release} before it runs, so a check's runs
     * can't finish, and its thread must wait for them, until the test lets them go.
     */
    private static ExpressionLanguage waitingActions(ExpressionLanguage language, CountDownLatch release) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        return (actionContext, session) -> {
                            release.await();
                            return action.execute(actionContext, session);
                        };
                    }
                };
            }
        };
    }

    /** Wraps a language so that every action it compiles fails when it runs. */
    private static ExpressionLanguage throwingActions(ExpressionLanguage language) {
        return throwingActions(language, ruleName -> true);
    }

    /** Wraps a language so that every action it compiles for a rule {@code ruleNames} accepts fails when it runs. */
    private static ExpressionLanguage throwingActions(ExpressionLanguage language, Predicate<String> ruleNames) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        if (!ruleNames.test(expression.ruleName())) {
                            return compiler.compileAction(expression);
                        }
                        return (actionContext, session) -> {
                            throw new IllegalStateException("the action failed");
                        };
                    }
                };
            }
        };
    }

    /** Wraps a language so that its compiler's newSession() returns what {@code sessions} supplies. */
    static ExpressionLanguage withSessions(ExpressionLanguage language, Supplier<Session> sessions) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                AtomicBoolean running = new AtomicBoolean(true);
                return new ForwardingExpressionCompiler(compiler) {
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

    /**
     * The variables a language's actions declare, kept in its session, and, for {@link #sessionCachedVariables}, the
     * variables as a {@code put} last found them.
     */
    private static final class Variables implements Session {

        private final Map<String, Object> declared = new ConcurrentHashMap<>();
        private final AtomicReference<Map<String, Object>> resolved = new AtomicReference<>(Map.of());
    }

    /**
     * Wraps a language so that an action's {@code let NAME = VALUE} keeps the variable in the session, and
     * {@code put KEY NAME} reads a name that isn't a fact from there. If {@code oneRunLate}, each {@code put} reads the
     * variables as they were when it last ran, as a language that caches what it resolved would: a later rule in the
     * same run doesn't see the variable, and a later run does.
     */
    private static ExpressionLanguage sessionVariables(ExpressionLanguage language, boolean oneRunLate) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
     * Wraps a language as {@link #sessionVariables} does one run late, but with the cache in the session rather than
     * in the compiled action, which every copy of the rules shares: each {@code put} reads the variables as they were
     * when a {@code put} last ran with the session, so a later run sees the variable only if it gets the same session.
     */
    private static ExpressionLanguage sessionCachedVariables(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
                        return (actionContext, session) -> {
                            Variables variables = (Variables) session;
                            Map<String, Object> visible = variables.resolved.getAndSet(Map.copyOf(variables.declared));
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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        // The only name the checks' actions read that is never a fact is the variable y.
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
                };
            }
        };
    }

    /**
     * Wraps a language so that an action's {@code let NAME = VALUE} keeps the variable in the session, and a condition
     * {@code NAME == VALUE} reads a name that isn't a fact from there, while its actions read only facts: a later run's
     * condition sees what an earlier run's action declared, and no later action does.
     */
    private static ExpressionLanguage conditionsReadVariables(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length != 3 || !"==".equals(tokens[1])) {
                            return condition;
                        }
                        return (evaluation, session) -> {
                            Map<String, Object> declared = ((Variables) session).declared;
                            if (evaluation.facts().containsKey(tokens[0]) || !declared.containsKey(tokens[0])) {
                                return condition.evaluate(evaluation, session);
                            }
                            return declared.get(tokens[0]).equals(Integer.valueOf(tokens[2]));
                        };
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
                        return action;
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
     * Wraps a language so that an action's {@code let NAME = VALUE} keeps the variable until the end of the run, with
     * {@code runScoped}, and {@code put KEY NAME} reads a name that isn't a fact from there: a later rule in the same
     * run reads the variable, and no later run does.
     */
    private static ExpressionLanguage runVariables(ExpressionLanguage language) {
        Object key = new Object();
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length == 4 && "let".equals(tokens[0])) {
                            return (actionContext, session) -> {
                                declared(actionContext).put(tokens[1], Integer.valueOf(tokens[3]));
                                return ActionResult.done();
                            };
                        }
                        if (tokens.length != 3 || !"put".equals(tokens[0])) {
                            return action;
                        }
                        return (actionContext, session) -> {
                            Map<String, Object> declared = declared(actionContext);
                            if (actionContext.facts().containsKey(tokens[2]) || !declared.containsKey(tokens[2])) {
                                return action.execute(actionContext, session);
                            }
                            return ActionResult.set(Map.of(tokens[1], declared.get(tokens[2])));
                        };
                    }

                    private Map<String, Object> declared(ActionContext actionContext) {
                        return actionContext.runScoped(key, ConcurrentHashMap::new);
                    }
                };
            }
        };
    }

    /** Where {@link #sharedBuiltins} keeps the built-ins its actions change. */
    private enum Builtins {
        /** In the session, as a runtime that isn't sealed, and that every run of a copy of the rules uses. */
        SESSION,
        /** With {@code runScoped}, so that a change lasts only its run. */
        RUN,
        /** Nowhere: the language refuses an action that changes one when it loads the rule. */
        REFUSED_AT_LOAD,
        /** Nowhere: an action that changes one fails, as a sealed runtime's does. */
        READ_ONLY,
        /** In the session, as {@link #SESSION} keeps them, by an action that then fails. */
        WRITE_THEN_FAIL
    }

    /** The built-ins of a language's runtime, kept in its session. */
    private static final class BuiltinsSession implements Session {

        private final Map<String, Object> values = new ConcurrentHashMap<>();
    }

    /**
     * Wraps a language so that an action {@code set NAME = VALUE} changes one of its built-ins, and a condition
     * {@code builtin NAME == VALUE} is true when the built-in holds the value, and false while it doesn't: a language
     * with state outside a run's variables, kept as {@code builtins} says.
     */
    private static ExpressionLanguage sharedBuiltins(ExpressionLanguage language, Builtins builtins) {
        Object key = new Object();
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length != 4 || !"builtin".equals(tokens[0]) || !"==".equals(tokens[2])) {
                            return compiler.compileCondition(expression);
                        }
                        Integer value = Integer.valueOf(tokens[3]);
                        return (evaluation, session) -> value.equals(values(evaluation, session).get(tokens[1]));
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length != 4 || !"set".equals(tokens[0]) || !"=".equals(tokens[2])) {
                            return compiler.compileAction(expression);
                        }
                        if (builtins == Builtins.REFUSED_AT_LOAD) {
                            throw new IllegalArgumentException("the built-in '" + tokens[1] + "' is read-only");
                        }
                        Integer value = Integer.valueOf(tokens[3]);
                        return (actionContext, session) -> {
                            if (builtins == Builtins.READ_ONLY) {
                                throw new IllegalStateException("the built-in '" + tokens[1] + "' is read-only");
                            }
                            values(actionContext, session).put(tokens[1], value);
                            if (builtins == Builtins.WRITE_THEN_FAIL) {
                                throw new IllegalStateException("the action failed after it changed '" + tokens[1]
                                        + "'");
                            }
                            return ActionResult.done();
                        };
                    }

                    @Override
                    public Session newSession() {
                        return new BuiltinsSession();
                    }

                    private Map<String, Object> values(EvaluationContext evaluation, Session session) {
                        return builtins == Builtins.RUN
                                ? evaluation.runScoped(key, ConcurrentHashMap::new)
                                : ((BuiltinsSession) session).values;
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that an action {@code let NAME = VALUE ; ...} keeps the variable in the session while the
     * rest of the action runs, and removes it once the rest has succeeded, but not when it throws, and {@code put KEY
     * NAME} reads a name that isn't a fact from there: a variable outlives only an action that fails.
     */
    private static ExpressionLanguage clearsVariablesOnSuccess(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        String[] statements = expression.text().split(";", 2);
                        String[] tokens = statements[0].trim().split("\\s+");
                        if (tokens.length == 4 && "let".equals(tokens[0])) {
                            CompiledAction rest = statements.length == 2
                                    ? compiler.compileAction(new Expression(expression.ruleName(), expression.kind(),
                                    statements[1]))
                                    : (actionContext, session) -> ActionResult.done();
                            return (actionContext, session) -> {
                                Map<String, Object> declared = ((Variables) session).declared;
                                declared.put(tokens[1], Integer.valueOf(tokens[3]));
                                ActionResult result = rest.execute(actionContext, session);
                                // Not in a finally block: the variable is left behind when the rest throws.
                                declared.remove(tokens[1]);
                                return result;
                            };
                        }
                        CompiledAction action = compiler.compileAction(expression);
                        if (tokens.length != 3 || !"put".equals(tokens[0])) {
                            return action;
                        }
                        return (actionContext, session) -> {
                            Map<String, Object> declared = ((Variables) session).declared;
                            if (actionContext.facts().containsKey(tokens[2]) || !declared.containsKey(tokens[2])) {
                                return action.execute(actionContext, session);
                            }
                            return ActionResult.set(Map.of(tokens[1], declared.get(tokens[2])));
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
     * Wraps a language so that its variables have a namespace of their own, as SpEL's do: an action's
     * {@code let NAME = VALUE} keeps the variable in the session, where every later rule and run finds it, and only
     * {@code #NAME} reads it, in an action {@code put KEY #NAME} and in a condition {@code #NAME == VALUE}. A variable
     * read as a fact is never found.
     */
    private static ExpressionLanguage hashVariables(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length == 3 && tokens[0].startsWith("#")) {
                            return (evaluation, session) -> Integer.valueOf(tokens[2])
                                    .equals(variable(session, tokens[0]));
                        }
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length == 4 && "let".equals(tokens[0])) {
                            return (actionContext, session) -> {
                                ((Variables) session).declared.put(tokens[1], Integer.valueOf(tokens[3]));
                                return ActionResult.done();
                            };
                        }
                        if (tokens.length == 3 && "put".equals(tokens[0]) && tokens[2].startsWith("#")) {
                            return (actionContext, session) ->
                                    ActionResult.set(Map.of(tokens[1], variable(session, tokens[2])));
                        }
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return new Variables();
                    }

                    private Object variable(Session session, String name) {
                        Object value = ((Variables) session).declared.get(name.substring(1));
                        if (value == null) {
                            throw new IllegalStateException("unknown variable '" + name + "'");
                        }
                        return value;
                    }
                };
            }
        };
    }

    /** A contract test for {@code language} that reads a variable only as {@code #NAME}, as {@link #hashVariables}. */
    private static ExpressionLanguageContractTest readingHashVariables(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            @Override
            protected String putVariable(String key, String variable) {
                return "put " + key + " #" + variable;
            }

            @Override
            protected String variableEquals(String variable, int value) {
                return "#" + variable + " == " + value;
            }
        };
    }

    /** A contract test for {@code language} whose configure() makes two copies of the rules when they load. */
    static ExpressionLanguageContractTest twoCopiesAtLoad(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.copiesAtLoad(2);
            }
        };
    }

    /**
     * A contract test for {@code language} whose actions change a built-in with {@code set NAME = VALUE}, and whose
     * conditions read one with {@code builtin NAME == VALUE}, as {@link #sharedBuiltins} compiles them.
     */
    private static class SharedStateContractTest extends ToyExpressionLanguageContractTest {

        private final ExpressionLanguage language;

        SharedStateContractTest(ExpressionLanguage language) {
            this.language = language;
        }

        @Override
        protected ExpressionLanguage language() {
            return language;
        }

        // The two hooks have no @Override, so that this class compiles against the kit before them (#848), where the
        // tests that run sharedStateStaysLocal fail for want of the check.
        protected String changeSharedState(String name, int value) {
            return "set " + name + " = " + value;
        }

        protected String sharedStateEquals(String name, int value) {
            return "builtin " + name + " == " + value;
        }
    }

    /**
     * A contract test for {@code language} whose {@code actionVariablesLastTheRun()} returns {@code true}. The hook has
     * no {@code @Override}, so that this compiles against the kit before it (#853), where the variable check ignores
     * it.
     */
    private static ExpressionLanguageContractTest lastingTheRun(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            protected boolean actionVariablesLastTheRun() {
                return true;
            }
        };
    }

    /**
     * Wraps a language so that a condition {@code FACT.PROPERTY = VALUE} writes the value into a map fact, and then is
     * true, or, if {@code thenFail}, throws, and a condition {@code let NAME = VALUE ; true} is true: a language that
     * lets its conditions write the facts and declare variables.
     */
    private static ExpressionLanguage writingConditions(ExpressionLanguage language, boolean thenFail) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    @SuppressWarnings("unchecked")
                    public CompiledCondition compileCondition(Expression expression) {
                        String text = expression.text();
                        if (text.startsWith("let ")) {
                            return (evaluation, session) -> true;
                        }
                        String[] tokens = text.trim().split("\\s+");
                        if (tokens.length != 3 || !"=".equals(tokens[1]) || !tokens[0].contains(".")) {
                            return compiler.compileCondition(expression);
                        }
                        String[] path = tokens[0].split("\\.");
                        return (evaluation, session) -> {
                            ((Map<String, Object>) evaluation.facts().get(path[0]))
                                    .put(path[1], Integer.valueOf(tokens[2]));
                            if (thenFail) {
                                throw new IllegalStateException("a condition can't write a fact");
                            }
                            return true;
                        };
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that a condition {@code FACT.PROPERTY = VALUE} fails the rule when the fact is a map, and
     * otherwise calls the fact's setter for the property and is then true, or, if {@code thenFail}, throws: a language
     * that rejects a condition's write to a map fact, and lets it through to a bean's setter.
     */
    private static ExpressionLanguage writesThroughSetters(ExpressionLanguage language, boolean thenFail) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        String[] tokens = expression.text().trim().split("\\s+");
                        if (tokens.length != 3 || !"=".equals(tokens[1]) || !tokens[0].contains(".")) {
                            return compiler.compileCondition(expression);
                        }
                        String[] path = tokens[0].split("\\.");
                        String setter = "set" + Character.toUpperCase(path[1].charAt(0)) + path[1].substring(1);
                        return (evaluation, session) -> {
                            Object fact = evaluation.facts().get(path[0]);
                            if (fact instanceof Map) {
                                throw new IllegalStateException("a condition can't write a map fact");
                            }
                            fact.getClass().getMethod(setter, int.class).invoke(fact, Integer.valueOf(tokens[2]));
                            if (thenFail) {
                                throw new IllegalStateException("a condition can't write a fact");
                            }
                            return true;
                        };
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that each expression it would reject fails only the first time it's evaluated, as in a
     * language that checks a compiled expression lazily and then remembers it did: a condition that assigns or writes,
     * such as {@code x = 2}, fails its first evaluation and is then true, a condition that reads the missing
     * {@code creditScor} fails its first and is then false, and an action that assigns the output fails its first run
     * and then does nothing.
     */
    private static ExpressionLanguage checksFirstRunOnly(ExpressionLanguage language) {
        return checksFirstRunOnly(language, () -> {
            AtomicBoolean checked = new AtomicBoolean();
            return session -> checked.compareAndSet(false, true);
        });
    }

    /**
     * Wraps a language as {@link #checksFirstRunOnly(ExpressionLanguage)} does, but keeps what it checked in the
     * session, which is new for each copy of the rules, rather than in the compiled expression: an expression it would
     * reject fails only its first evaluation with each session.
     */
    private static ExpressionLanguage checksFirstRunOfEachSessionOnly(ExpressionLanguage language) {
        return withSessions(checksFirstRunOnly(language, () -> {
            Object expression = new Object();
            return session -> ((CheckingSession) session).checked.add(expression);
        }), CheckingSession::new);
    }

    /** A session that keeps the expressions it has checked, for {@link #checksFirstRunOfEachSessionOnly}. */
    private static final class CheckingSession implements Session {

        private final Set<Object> checked = ConcurrentHashMap.newKeySet();
    }

    /**
     * Wraps a language so that each expression it would reject fails the first time {@code firstEvaluation}'s test
     * says it's evaluated, and behaves as {@link #checksFirstRunOnly(ExpressionLanguage)} describes after.
     */
    private static ExpressionLanguage checksFirstRunOnly(ExpressionLanguage language,
            Supplier<Predicate<Session>> firstEvaluation) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        String text = expression.text();
                        if (text.contains(".creditScor ")) {
                            return failsFirstOnly("the fact has no property creditScor", false,
                                    firstEvaluation.get());
                        }
                        if (text.contains(" = ") && !text.startsWith("let ")) {
                            return failsFirstOnly("a condition can't write a fact", true, firstEvaluation.get());
                        }
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        if (!expression.text().startsWith("output =")) {
                            return compiler.compileAction(expression);
                        }
                        Predicate<Session> first = firstEvaluation.get();
                        return (actionContext, session) -> {
                            if (first.test(session)) {
                                throw new IllegalStateException("an action can't replace the output");
                            }
                            return ActionResult.done();
                        };
                    }
                };
            }
        };
    }

    /**
     * A condition that throws {@code failure} when {@code first} says it's evaluated for the first time, and
     * evaluates to {@code later} otherwise.
     */
    private static CompiledCondition failsFirstOnly(String failure, boolean later, Predicate<Session> first) {
        return (evaluation, session) -> {
            if (first.test(session)) {
                throw new IllegalStateException(failure);
            }
            return later;
        };
    }

    /**
     * Wraps a language so that each expression it would reject fails its first evaluation, and a later run of its
     * rule fails elsewhere in the rule: a condition that assigns, writes or reads the missing {@code creditScor} is
     * then true, its compiler's {@code compileAction} returns an action that always fails, whatever the expression,
     * and the other conditions fail from their second evaluation on, so that the rule of an action that assigns the
     * output then fails in its condition. Only rule {@code r}'s expressions: the valid rule the checks load beside it
     * compiles as the language compiles it.
     */
    private static ExpressionLanguage failsElsewhereAfterTheFirstRun(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        if (!"r".equals(expression.ruleName())) {
                            return compiler.compileCondition(expression);
                        }
                        AtomicBoolean evaluated = new AtomicBoolean();
                        String text = expression.text();
                        if (text.contains(".creditScor ") || text.contains(" = ") && !text.startsWith("let ")) {
                            return failsFirstOnly("the condition fails its first run", true,
                                    session -> evaluated.compareAndSet(false, true));
                        }
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> {
                            if (!evaluated.compareAndSet(false, true)) {
                                throw new IllegalStateException("the condition fails from its second run on");
                            }
                            return condition.evaluate(evaluation, session);
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        if (!"r".equals(expression.ruleName())) {
                            return compiler.compileAction(expression);
                        }
                        return (actionContext, session) -> {
                            throw new IllegalStateException("the action fails");
                        };
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that each expression the checks that repeat a failed run expect it to reject compiles, and
     * fails each time it's evaluated, which {@code rejections} counts: a condition that assigns, writes a property,
     * declares a variable or reads the missing {@code creditScor}, and an action that assigns the output.
     */
    private static ExpressionLanguage rejectsOnEveryRun(ExpressionLanguage language, AtomicInteger rejections) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        String text = expression.text();
                        if (!text.contains(" = ") && !text.contains(".creditScor ")) {
                            return compiler.compileCondition(expression);
                        }
                        return (evaluation, session) -> {
                            rejections.incrementAndGet();
                            throw new IllegalStateException("the condition is rejected");
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        if (!expression.text().startsWith("output =")) {
                            return compiler.compileAction(expression);
                        }
                        return (actionContext, session) -> {
                            rejections.incrementAndGet();
                            throw new IllegalStateException("an action can't replace the output");
                        };
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that the condition of a rule named {@code ok} fails to compile, if {@code atLoad}, or else
     * fails each time it's evaluated: a language that can't run the valid rule the checks that repeat a failed run load
     * before the rejected one.
     */
    private static ExpressionLanguage failsRuleOk(ExpressionLanguage language, boolean atLoad) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        if (!"ok".equals(expression.ruleName())) {
                            return compiler.compileCondition(expression);
                        }
                        if (atLoad) {
                            throw new IllegalArgumentException("the condition can't compile");
                        }
                        return (evaluation, session) -> {
                            throw new IllegalStateException("the condition fails");
                        };
                    }
                };
            }
        };
    }

    /**
     * Wraps a language as a runtime that marks its session busy while it evaluates a condition, and, apart, while it
     * runs an action: a condition, or an action, evaluated with a busy session fails with "session busy", whatever its
     * expression. An expression that returns clears the mark; one that throws clears it only if
     * {@code clearedOnFailure}, so that otherwise every later condition, or action, of the copy fails, as with an
     * "evaluating" flag a failure never clears.
     */
    private static ExpressionLanguage busySessions(ExpressionLanguage language, boolean clearedOnFailure) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                // By identity, and for each compiler: the toy's session is Session.none(), which every copy shares.
                Set<Session> evaluating = identitySet();
                Set<Session> running = identitySet();
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> busy(evaluating, session,
                                () -> condition.evaluate(evaluation, session));
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        return (actionContext, session) -> busy(running, session,
                                () -> action.execute(actionContext, session));
                    }
                };
            }

            private Set<Session> identitySet() {
                return Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
            }

            private <T> T busy(Set<Session> busy, Session session, Callable<T> work) throws Exception {
                if (!busy.add(session)) {
                    throw new IllegalStateException("session busy");
                }
                boolean returned = false;
                try {
                    T result = work.call();
                    returned = true;
                    return result;
                } finally {
                    if (returned || clearedOnFailure) {
                        busy.remove(session);
                    }
                }
            }
        };
    }

    /**
     * Wraps a language so that its compilers accept each fact name the first time they check it, and reject it with
     * an exception that isn't an {@link IllegalArgumentException} after, which a run reports as an
     * {@code IllegalArgumentException} before it runs a rule.
     */
    private static ExpressionLanguage rejectsFactNamesAfterTheFirstRun(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                Set<String> checked = ConcurrentHashMap.newKeySet();
                return new ForwardingExpressionCompiler(language.newCompiler(context)) {
                    @Override
                    public void checkFactName(String name) {
                        if (!checked.add(name)) {
                            throw new IllegalStateException("the fact name was checked before: " + name);
                        }
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that each of its compilers returns a new session each time, and throws when it's closed
     * once it has warmed up a session, as a compiler whose warm-up starts a runtime that fails to stop might.
     */
    private static ExpressionLanguage closeFailsOnceWarmedUp(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                AtomicBoolean warmedUp = new AtomicBoolean();
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public Session newSession() {
                        return new Session() {
                        };
                    }

                    @Override
                    public void warmUp(Session session) {
                        warmedUp.set(true);
                    }

                    @Override
                    public void close() {
                        compiler.close();
                        if (warmedUp.get()) {
                            throw new IllegalStateException("close() fails after warmUp()");
                        }
                    }
                };
            }
        };
    }

    /**
     * Wraps a language so that each action it compiles turns every value in the output into its text once it has run:
     * an output that prints as the one expected, and isn't.
     */
    private static ExpressionLanguage textOutputs(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    @SuppressWarnings("unchecked")
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        return (actionContext, session) -> {
                            ActionResult result = action.execute(actionContext, session);
                            ((Map<String, Object>) actionContext.output()).replaceAll((key, value) ->
                                    String.valueOf(value));
                            return result;
                        };
                    }
                };
            }
        };
    }

    /** Runs every one of the kit's checks on the contract test, and returns the name of each that failed. */
    private static List<String> failedChecks(ExpressionLanguageContractTest test) {
        List<String> failed = new ArrayList<>();
        for (Method check : ExpressionLanguageContractTest.class.getDeclaredMethods()) {
            if (!check.isAnnotationPresent(Test.class)) {
                continue;
            }
            try {
                runCheck(test, check.getName());
            } catch (TestAbortedException e) {
                // Skipped by one of the toy's hooks, as it is in the toy's own contract test.
            } catch (Throwable e) {
                failed.add(check.getName());
            }
        }
        return failed;
    }

    /** Wraps a language so that its checkFactName() rejects any name but letters, such as {@code credit_score2}. */
    private static ExpressionLanguage lettersOnly(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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

    /**
     * Wraps a language so that each of its compilers throws what {@code failure} supplies when it's closed, whatever
     * its type.
     */
    private static ExpressionLanguage throwingCompilerClose(ExpressionLanguage language,
                                                            Supplier<? extends Throwable> failure) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public void close() {
                        compiler.close();
                        TestSupport.<RuntimeException>sneakyThrow(failure.get());
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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
        return threadLocalOutput(language, false);
    }

    /**
     * Wraps a language as {@link #threadLocalOutput(ExpressionLanguage)} does. If {@code restores}, the action puts
     * back the output it replaced when it returns, but not in a {@code finally}: a run started while the operand is
     * read then leaves nothing behind, unless its own action fails, which leaves its output there.
     */
    private static ExpressionLanguage threadLocalOutput(ExpressionLanguage language, boolean restores) {
        ThreadLocal<Map<String, Object>> output = new ThreadLocal<>();
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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
                            Map<String, Object> replaced = output.get();
                            output.set(target);
                            Object value = operand.evaluate(actionContext, session);
                            output.get().put(tokens[1], value);
                            if (restores) {
                                // Skipped when reading the operand threw.
                                output.set(replaced);
                            }
                            return ActionResult.done();
                        };
                    }
                };
            }
        };
    }

    /** How a language that keeps a condition's context in a {@link ThreadLocal} puts back what it replaced. */
    private enum Restores {
        /** It never puts it back. */
        NEVER,
        /** It puts back the context it replaced when the condition returns, but not in a {@code finally}. */
        OUTSIDE_FINALLY,
        /** It removes the context in a {@code finally}, rather than putting back the one it replaced. */
        REMOVES_IN_FINALLY
    }

    /**
     * Wraps a language so that a condition {@code LEFT and RIGHT} keeps the context it evaluates in a
     * {@link ThreadLocal}, and looks it up again for each side, as an adapter over a runtime whose context is bound to
     * the thread might. It's true when both sides are, and reads the right side only if the left is true. A run
     * started on the same thread while the left side is read, by a getter, replaces the context, so the right side
     * reads that run's facts, or, once that run has removed it, none at all.
     */
    private static ExpressionLanguage threadLocalConditions(ExpressionLanguage language, Restores restores) {
        ThreadLocal<EvaluationContext> current = new ThreadLocal<>();
        return ToyConjunctionsContractTest.conjunctions(language, (left, right) -> (evaluation, session) -> {
            EvaluationContext replaced = current.get();
            current.set(evaluation);
            try {
                boolean result = Boolean.TRUE.equals(left.evaluate(current.get(), session))
                        && Boolean.TRUE.equals(right.evaluate(current.get(), session));
                if (restores == Restores.OUTSIDE_FINALLY) {
                    // Skipped when a side threw.
                    current.set(replaced);
                }
                return result;
            } finally {
                if (restores == Restores.REMOVES_IN_FINALLY) {
                    current.remove();
                }
            }
        });
    }

    /**
     * Wraps a language so that what one of its conditions throws is handled by {@code onFailure}, which returns the
     * condition's value instead, or throws something else, as a language that hides or rewraps failures would.
     */
    private static ExpressionLanguage handlingConditionFailures(ExpressionLanguage language,
                                                                Function<RuntimeException, Object> onFailure) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> {
                            try {
                                return condition.evaluate(evaluation, session);
                            } catch (RuntimeException e) {
                                return onFailure.apply(e);
                            }
                        };
                    }
                };
            }
        };
    }

    /** The message of the last of an exception's causes, which a language that rewraps a failure may keep alone. */
    private static String rootMessage(Throwable thrown) {
        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /**
     * Wraps a language so that a condition evaluated while another of its conditions is being evaluated on the same
     * thread fails, as an interpreter that isn't re-entrant does: a run started inside a condition then fails before
     * it reads anything.
     */
    private static ExpressionLanguage nonReentrant(ExpressionLanguage language) {
        ThreadLocal<Boolean> busy = ThreadLocal.withInitial(() -> false);
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> {
                            if (busy.get()) {
                                throw new IllegalStateException("the interpreter is already running on this thread");
                            }
                            busy.set(true);
                            try {
                                return condition.evaluate(evaluation, session);
                            } finally {
                                busy.set(false);
                            }
                        };
                    }
                };
            }
        };
    }

    /** Wraps a language so that it records each thread one of its conditions is evaluated on. */
    private static ExpressionLanguage recordingThreads(ExpressionLanguage language, Set<Thread> threads) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> {
                            threads.add(Thread.currentThread());
                            return condition.evaluate(evaluation, session);
                        };
                    }
                };
            }
        };
    }

    /**
     * Runs one of the kit's checks, by name, on a contract test for {@code language} whose conditions require both of
     * two with {@code and}.
     */
    private static void runConjunctionsCheck(ExpressionLanguage language, String check) throws Throwable {
        runCheck(new ToyConjunctionsContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }
        }, check);
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
    @DisplayName("a session whose close() throws an exception carrying a fatal error fails each session check with that"
            + " error, without the exception attached to it as one the engine only logs (#894)")
    void sessionCloseCarryingAFatalErrorNotAttachedToIt() {
        List<String> sessionChecks = List.of("sessionsClosed", "sessionClosedWhileAnotherRuns",
                "sessionClosedOnAnotherThread");
        for (String check : sessionChecks) {
            InternalError crash = new InternalError("the session's native runtime crashed");
            ExpressionLanguage language = withSessions(new ToyExpressionLanguage(), () -> new Session() {
                @Override
                public void close() {
                    throw new IllegalStateException("failed to release the session", crash);
                }
            });

            InternalError failure = assertThrows(InternalError.class, () -> runCheck(language, check), check);

            assertSame(crash, failure, check);
            assertEquals(List.of(), Arrays.asList(failure.getSuppressed()), check);
        }
    }

    @Test
    @DisplayName("of two sessions, one whose close() throws what the engine only logs and one whose close() throws an"
            + " exception carrying a fatal error, the session check fails with that error, with only the first's"
            + " exception attached (#894)")
    void sessionCloseCarryingAFatalErrorBesideALoggedOne() {
        IllegalStateException leak = new IllegalStateException("leak");
        InternalError crash = new InternalError("the session's native runtime crashed");
        IllegalStateException carrier = new IllegalStateException("failed to release the session", crash);
        AtomicInteger created = new AtomicInteger();
        ExpressionLanguage language = withSessions(new ToyExpressionLanguage(), () -> {
            RuntimeException thrown = created.getAndIncrement() % 2 == 0 ? leak : carrier;
            return new Session() {
                @Override
                public void close() {
                    throw thrown;
                }
            };
        });

        InternalError failure = assertThrows(InternalError.class, () -> runCheck(language, "sessionsClosed"));

        assertSame(crash, failure);
        assertEquals(List.of(leak), Arrays.asList(failure.getSuppressed()));
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
        // The run fails, naming the rule, but for its action: the check must look at which expression failed. Only
        // r's action fails, since the valid rule the check loads before it fires first.
        ExpressionLanguage language = throwingActions(silentTrue(new ToyExpressionLanguage()), "r"::equals);

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
        ExpressionLanguage offByOne = new ForwardingExpressionLanguage(
                LongNumbersContractTest.longNumbers(new ToyExpressionLanguage("toy-longs", true))) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = super.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
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

    /** Wraps a language so that {@code reservedFactNames()} returns what {@code reserved} supplies on each call. */
    private static ExpressionLanguage reserving(ExpressionLanguage language, Supplier<Set<String>> reserved) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public Set<String> reservedFactNames() {
                return reserved.get();
            }
        };
    }

    @Test
    @DisplayName("a contract test that names a fact name its language reserves as the one it rejects fails the"
            + " fact-name check (#468)")
    void reservedAsUnusableNameFails() {
        // The engine rejects the name itself, so a language that accepts every name, as the toy does, would pass.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return reserving(new ToyExpressionLanguage(), () -> Set.of("ctx"));
            }

            @Override
            protected String unusableFactName() {
                return "ctx";
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "unusableFactNameRejected"));

        assertTrue(failure.getMessage().startsWith("unusableFactName() must return a name the language itself"
                + " rejects, not 'ctx', which reservedFactNames() reserves"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that reserves its own fact names, or none, passes the reserved-name check, and one that"
            + " returns null, a null name or other names each time fails it (#468)")
    void reservedFactNamesChecked() throws Throwable {
        runCheck(new ToyExpressionLanguage(), "reservedFactNamesRejected");
        runCheck(reserving(new ToyExpressionLanguage(), Set::of), "reservedFactNamesRejected");
        runCheck(reserving(new ToyExpressionLanguage(), () -> Set.of("ctx", "x")), "reservedFactNamesRejected");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(reserving(new ToyExpressionLanguage(), () -> null), "reservedFactNamesRejected"));
        assertTrue(failure.getMessage().startsWith("reservedFactNames() must not return null"), failure.getMessage());
        Set<String> withNull = new HashSet<>();
        withNull.add(null);
        failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(reserving(new ToyExpressionLanguage(), () -> withNull), "reservedFactNamesRejected"));
        assertTrue(failure.getMessage().startsWith("reservedFactNames() must not return a set holding null"),
                failure.getMessage());
        AtomicInteger calls = new AtomicInteger();
        failure = assertThrows(AssertionFailedError.class, () -> runCheck(reserving(new ToyExpressionLanguage(),
                () -> Set.of("name" + calls.incrementAndGet())), "reservedFactNamesRejected"));
        assertTrue(failure.getMessage().startsWith("reservedFactNames() must return the same names every time"),
                failure.getMessage());
        // A blank name is the engine's to reject, so it's skipped.
        runCheck(reserving(new ToyExpressionLanguage(), () -> Set.of(" ", "ctx")), "reservedFactNamesRejected");
    }

    @Test
    @DisplayName("a run that rejects a reserved fact name for another reason than its being reserved fails the"
            + " reserved-name check (#468)")
    void reservedNameRejectedForAnotherReasonFails() {
        // Reserves ctx when the check asks, twice, and nothing when the engine asks, once it's built: the engine then
        // doesn't reject ctx itself, and the language's own checkFactName does.
        AtomicInteger asked = new AtomicInteger();
        ExpressionLanguage language = new ForwardingExpressionLanguage(new ToyExpressionLanguage()) {
            @Override
            public Set<String> reservedFactNames() {
                return asked.incrementAndGet() <= 2 ? Set.of("ctx") : Set.of();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ForwardingExpressionCompiler(super.newCompiler(context)) {
                    @Override
                    public void checkFactName(String name) {
                        if ("ctx".equals(name)) {
                            throw new IllegalArgumentException("not a name: " + name);
                        }
                    }
                };
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "reservedFactNamesRejected"));

        assertTrue(failure.getMessage().startsWith("reservedFactNames() returned 'ctx', but run() rejected a fact with"
                + " that name for another reason: not a name: ctx"), failure.getMessage());
    }

    @Test
    @DisplayName("the checks that wrap the language keep the fact names it reserves: one that reserves none passes"
            + " every check with a fact named output declared (#468)")
    void wrappedLanguageKeepsReservedNames() {
        // A wrapper that didn't forward reservedFactNames() would reserve output by default, and build() would reject
        // the declaration in compilerClosed and the session checks.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return reserving(new ToyExpressionLanguage(), Set::of);
            }

            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.fact("output", Object.class);
            }
        };

        assertEquals(List.of(), failedChecks(test));
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
    @DisplayName("a compiler whose close() throws an exception carrying a fatal error fails the compiler check with"
            + " that error, without the exception attached to it as one the engine only logs (#894)")
    void compilerCloseCarryingAFatalErrorNotAttachedToIt() {
        InternalError crash = new InternalError("the compiler's native runtime crashed");
        ExpressionLanguage language = throwingCompilerClose(new ToyExpressionLanguage(),
                () -> new IllegalStateException("failed to release the runtime", crash));

        InternalError failure = assertThrows(InternalError.class, () -> runCheck(language, "compilerClosed"));

        assertSame(crash, failure);
        assertEquals(List.of(), Arrays.asList(failure.getSuppressed()));
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
        // The runs wait until the check has been interrupted, so it is still waiting for them when it looks: runs
        // that have all finished are collected without a wait, and the interrupt is never seen.
        CountDownLatch release = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> runCheck(waitingActions(new ToyExpressionLanguage(), release), "concurrentRuns"));

            assertTrue(Thread.currentThread().isInterrupted(), "the check cleared the thread's interrupt");
        } finally {
            release.countDown();
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

    @Test
    @DisplayName("a language whose conditions keep what they evaluate in per-thread state, and never put it back, fails"
            + " the checks that start a run inside a condition (#843)")
    void conditionStateNeverRestoredFails() {
        ExpressionLanguage language = threadLocalConditions(new ToyExpressionLanguage(), Restores.NEVER);

        AssertionFailedError nested = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunInsideACondition"));
        assertEquals("the run around the run started inside its condition ==> expected: <{seen=1}> but was: <null>",
                nested.getMessage());
        AssertionFailedError failed = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunFailsInsideACondition"));
        assertEquals("the run around the run that failed inside its condition ==> expected: <{seen=1}> but was:"
                + " <null>", failed.getMessage());
    }

    @Test
    @DisplayName("a language whose conditions put back the per-thread state they replaced, but not in a finally, fails"
            + " only the check whose nested run fails inside a condition (#843)")
    void conditionStateRestoredOutsideFinallyFails() {
        ExpressionLanguage language = threadLocalConditions(new ToyExpressionLanguage(), Restores.OUTSIDE_FINALLY);

        assertDoesNotThrow(() -> runConjunctionsCheck(language, "nestedRunInsideACondition"));
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunFailsInsideACondition"));
        assertEquals("the run around the run that failed inside its condition ==> expected: <{seen=1}> but was:"
                + " <null>", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose conditions remove their per-thread state in a finally fails the checks that start a"
            + " run inside a condition, saying the nested run may have removed the state, whether or not it failed"
            + " (#843)")
    void conditionStateRemovedReported() {
        ExpressionLanguage language = threadLocalConditions(new ToyExpressionLanguage(), Restores.REMOVES_IN_FINALLY);

        AssertionFailedError ended = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunInsideACondition"));
        assertTrue(ended.getMessage().startsWith("the run failed after a run started inside its condition ended, so"
                + " the nested run may have replaced or removed state the condition kept for its own run: "),
                ended.getMessage());
        assertInstanceOf(RuleExecutionException.class, ended.getCause());
        assertEquals(0, ended.getSuppressed().length);

        AssertionFailedError failed = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunFailsInsideACondition"));
        assertTrue(failed.getMessage().startsWith("the run failed after a run started inside its condition failed, as"
                + " the check meant it to, which is attached, so the failed run may have replaced or removed state the"
                + " condition kept for its own run: "), failed.getMessage());
        assertInstanceOf(RuleExecutionException.class, failed.getCause());
        assertEquals(1, failed.getSuppressed().length);
        assertInstanceOf(RuleExecutionException.class, failed.getSuppressed()[0]);
    }

    @Test
    @DisplayName("a language whose actions put back the per-thread state they replaced, but not in a finally, fails"
            + " only the check whose nested run fails inside an action (#843)")
    void actionStateRestoredOutsideFinallyFails() {
        ExpressionLanguage language = threadLocalOutput(new ToyExpressionLanguage(), true);

        assertDoesNotThrow(() -> runCheck(language, "nestedRunInsideAnAction"));
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "nestedRunFailsInsideAnAction"));
        assertEquals("the run around the run that failed inside its action ==> expected: <{seen=7}> but was: <{}>",
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that keeps a run's state on the stack or in its sessions passes the checks that start a"
            + " run inside a condition, and make a nested run fail (#843)")
    void ownRunStatePassesNestedRunChecks() {
        ExpressionLanguage withSessions = withSessions(new ToyExpressionLanguage(), () -> new Session() {
        });
        for (String check : List.of("nestedRunInsideACondition", "nestedRunFailsInsideACondition",
                "nestedRunFailsInsideAnAction")) {
            assertDoesNotThrow(() -> runCheck(new ToyConjunctionsContractTest(), check), check);
            assertDoesNotThrow(() -> runConjunctionsCheck(ToyConjunctionsContractTest.conjunctions(withSessions),
                    check), check);
        }
    }

    @Test
    @DisplayName("the nested-run checks run on a thread of their own, so per-thread state a language leaves there can't"
            + " reach a later check (#843)")
    void nestedRunChecksRunOnTheirOwnThread() {
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        ExpressionLanguage language = recordingThreads(
                ToyConjunctionsContractTest.conjunctions(new ToyExpressionLanguage()), threads);

        List<Thread> earlier = new ArrayList<>();
        for (String check : List.of("nestedRunInsideAnAction", "nestedRunInsideACondition",
                "nestedRunFailsInsideACondition", "nestedRunFailsInsideAnAction")) {
            threads.clear();
            assertDoesNotThrow(() -> runConjunctionsCheck(language, check), check);
            // The run around and the one nested in it evaluate conditions, and on one thread.
            assertEquals(1, threads.size(), check + " evaluated its conditions on " + threads);
            Thread thread = threads.iterator().next();
            assertNotSame(Thread.currentThread(), thread, check + " ran on the test's thread");
            assertTrue(earlier.stream().noneMatch(used -> used == thread), check + " reused an earlier check's thread");
            earlier.add(thread);
        }
    }

    @Test
    @DisplayName("a contract test whose bothConditions() returns null skips the checks that start a run inside a"
            + " condition, and one whose putFactProperty() returns null the check whose nested run fails inside an"
            + " action (#843)")
    void noBothConditionsSkipped() {
        for (String check : List.of("nestedRunInsideACondition", "nestedRunFailsInsideACondition")) {
            TestAbortedException skipped = assertThrows(TestAbortedException.class,
                    () -> runCheck(new ToyExpressionLanguage(), check), check);
            assertEquals("Assumption failed: the language's conditions can't require both of two conditions",
                    skipped.getMessage());
        }
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String putFactProperty(String key, String fact, String property) {
                return null;
            }
        };
        TestAbortedException skipped = assertThrows(TestAbortedException.class,
                () -> runCheck(test, "nestedRunFailsInsideAnAction"));
        assertEquals("Assumption failed: the language's actions can't read a fact's property", skipped.getMessage());
    }

    @Test
    @DisplayName("the check whose nested run fails inside a condition is skipped for a language whose nested run never"
            + " reads the getter that makes it fail, as one that evaluates the right side first (#843)")
    void rightSideFirstSkipped() {
        ExpressionLanguageContractTest test = new ToyConjunctionsContractTest() {
            @Override
            protected String bothConditions(String condition, String other) {
                return other + " and " + condition;
            }
        };

        assertDoesNotThrow(() -> runCheck(test, "nestedRunInsideACondition"));
        TestAbortedException skipped = assertThrows(TestAbortedException.class,
                () -> runCheck(test, "nestedRunFailsInsideACondition"));
        assertEquals("Assumption failed: the run started inside the condition never read its own nest.value, whose"
                + " getter makes it fail, as a language that reads the right side of a condition first may not",
                skipped.getMessage());
    }

    @Test
    @DisplayName("the checks that start a run inside a condition, or make a nested run fail, fail an expression that"
            + " never reads the fact's property (#843)")
    void expressionThatNeverReadsTheGetterFails() {
        ExpressionLanguageContractTest conditions = new ToyConjunctionsContractTest() {
            @Override
            protected String bothConditions(String condition, String other) {
                return other;
            }
        };
        for (String check : List.of("nestedRunInsideACondition", "nestedRunFailsInsideACondition")) {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> runCheck(conditions, check),
                    check);
            assertTrue(failure.getMessage().startsWith("the condition didn't read nest.value, so no run was started"
                    + " inside it"), failure.getMessage());
        }
        ExpressionLanguageContractTest actions = new ToyExpressionLanguageContractTest() {
            @Override
            protected String putFactProperty(String key, String fact, String property) {
                return "put " + key + " 7";
            }
        };
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(actions, "nestedRunFailsInsideAnAction"));
        assertTrue(failure.getMessage().startsWith("the action didn't read nest.value, so no run was started inside"
                + " it"), failure.getMessage());
    }

    @Test
    @DisplayName("the check whose nested run fails inside a condition fails a language whose nested run reads the"
            + " getter that throws and doesn't fail (#843)")
    void nestedRunThatDoesNotFailFails() {
        ExpressionLanguage language = handlingConditionFailures(
                ToyConjunctionsContractTest.conjunctions(new ToyExpressionLanguage()), e -> false);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunFailsInsideACondition"));

        assertEquals("the run started inside the condition didn't fail, though a getter its rule read threw: it"
                + " returned {inner=2}", failure.getMessage());
    }

    @Test
    @DisplayName("the check whose nested run fails inside a condition fails a language whose nested run fails for"
            + " another reason than the getter that throws (#843)")
    void nestedRunFailingForAnotherReasonFails() {
        ExpressionLanguage language = handlingConditionFailures(
                ToyConjunctionsContractTest.conjunctions(new ToyExpressionLanguage()), e -> {
                    throw new IllegalStateException("the condition failed, for a reason it doesn't say");
                });

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunFailsInsideACondition"));

        assertTrue(failure.getMessage().startsWith("the run started inside the condition failed, but its failure"
                + " doesn't carry what its nest.value threw, as a cause, a suppressed exception or by its message: "),
                failure.getMessage());
        assertInstanceOf(RuleExecutionException.class, failure.getCause());
    }

    @Test
    @DisplayName("the check whose nested run fails inside a condition fails, rather than skips, a language whose nested"
            + " run fails before it reads the getter that throws, as an interpreter that isn't re-entrant does (#843)")
    void nestedRunFailingBeforeTheGetterFails() {
        ExpressionLanguage language = nonReentrant(
                ToyConjunctionsContractTest.conjunctions(new ToyExpressionLanguage()));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runConjunctionsCheck(language, "nestedRunFailsInsideACondition"));

        assertTrue(failure.getMessage().startsWith("the run started inside the condition failed for another reason"
                + " than its nest.value, which throws: "), failure.getMessage());
        assertTrue(failure.getMessage().contains("the interpreter is already running on this thread"),
                failure.getMessage());
    }

    @Test
    @DisplayName("the check whose nested run fails inside a condition says both runs failed when the nested run failed"
            + " for another reason than the getter that throws (#843)")
    void unplannedNestedFailureReportedAsASecondFailure() {
        // The toy fails a condition that reads a name that is neither a variable nor a fact.
        ExpressionLanguageContractTest test = new ToyConjunctionsContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return nonReentrant(ToyConjunctionsContractTest.conjunctions(new ToyExpressionLanguage()));
            }

            @Override
            protected String bothConditions(String condition, String other) {
                return condition + " and y == 1";
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "nestedRunFailsInsideACondition"));

        assertTrue(failure.getMessage().startsWith("the run failed after a run started inside its condition failed"
                + " too, which is attached: "), failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(RuleExecutionException.class, failure.getSuppressed()[0]);
    }

    @Test
    @DisplayName("the checks whose nested run fails accept the planned failure however a language carries it: as a"
            + " cause, as a suppressed exception, or by its message alone (#843)")
    void plannedFailureCarriedAnyWayPasses() {
        List<Function<RuntimeException, Object>> rewraps = List.of(
                e -> {
                    throw new IllegalStateException("the condition failed", e);
                },
                e -> {
                    IllegalStateException rewrapped = new IllegalStateException("the condition failed");
                    rewrapped.addSuppressed(e);
                    throw rewrapped;
                },
                e -> {
                    throw new IllegalStateException(rootMessage(e));
                });
        for (Function<RuntimeException, Object> rewrap : rewraps) {
            assertDoesNotThrow(() -> runConjunctionsCheck(handlingConditionFailures(
                    ToyConjunctionsContractTest.conjunctions(new ToyExpressionLanguage()), rewrap),
                    "nestedRunFailsInsideACondition"));
        }
    }

    @Test
    @DisplayName("the check that starts a run inside a condition says the run around it failed, with the nested run's"
            + " failure attached, and reports a run that fails before starting one as the engine reported it (#843)")
    void runAroundTheNestedRunInsideAConditionFailedReported() {
        // The toy fails a condition that reads a name that is neither a variable nor a fact.
        ExpressionLanguageContractTest after = new ToyConjunctionsContractTest() {
            @Override
            protected String bothConditions(String condition, String other) {
                return condition + " and y == 1";
            }
        };
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(after, "nestedRunInsideACondition"));
        assertTrue(failure.getMessage().startsWith("the run failed after a run started inside its condition failed"
                + " too, which is attached: "), failure.getMessage());
        assertInstanceOf(RuleExecutionException.class, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(RuleExecutionException.class, failure.getSuppressed()[0]);

        ExpressionLanguageContractTest before = new ToyConjunctionsContractTest() {
            @Override
            protected String bothConditions(String condition, String other) {
                return "y == 1 and " + condition;
            }
        };
        assertThrows(RuleExecutionException.class, () -> runCheck(before, "nestedRunInsideACondition"));
    }

    @Test
    @DisplayName("a language whose later run's condition reads a variable an earlier run's action declared fails the"
            + " variable check (#697)")
    void conditionReadingAnEarlierRunsVariableFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(conditionsReadVariables(new ToyExpressionLanguage()), "actionVariablesStayLocal"));

        assertEquals("a later run's condition read the variable 'y' an action declared in an earlier one, although"
                + " the rule that declares it didn't fire: {seen=2}", failure.getMessage());
    }

    @Test
    @DisplayName("the variable check fails a language whose run that declares the variable read by a later run's"
            + " condition fails, rather than passing it (#697)")
    void failedRunBeforeTheConditionReadsFails() {
        // The toy's > compares numbers, and fails the rule for a string.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String variableEquals(String variable, int value) {
                return "'text' > " + value;
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "actionVariablesStayLocal"));

        assertTrue(failure.getMessage().startsWith("the run that declares the variable 'y', with y a fact, failed ==>"),
                failure.getMessage());
    }

    @Test
    @DisplayName("the variable check reads a variable with putVariable(), so a language whose variables have a"
            + " namespace of their own fails it when they leak (#766)")
    void namespacedVariablesFail() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(readingHashVariables(hashVariables(new ToyExpressionLanguage())),
                        "actionVariablesStayLocal"));

        assertEquals("a later rule read the variable 'y' an action declared: {seen=2}", failure.getMessage());
    }

    @Test
    @DisplayName("a language that keeps the variable of an action that fails fails the failed-action check, whatever"
            + " configure() sets, and passes the variable check (#766)")
    void failedActionsVariablesFail() throws Throwable {
        ExpressionLanguage language = clearsVariablesOnSuccess(new ToyExpressionLanguage());
        for (ExpressionLanguageContractTest test : List.of(new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }
        }, twoCopiesAtLoad(language))) {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(test, "failedActionVariablesStayLocal"));

            assertEquals("a later run read the variable 'y' that a failed action declared: {seen=2}",
                    failure.getMessage());
            // Only a failed action leaves its variable behind.
            runCheck(test, "actionVariablesStayLocal");
        }
    }

    @Test
    @DisplayName("the failed-action check fails a contract test whose declareVariableThenFail() returns an action that"
            + " doesn't fail the run (#766)")
    void declareVariableThenFailThatSucceedsFails() {
        // A language that reads the unknown y as null, so that the rule that reads it doesn't fail the run either.
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return unknownNames(new ToyExpressionLanguage(), false);
            }

            @Override
            protected String declareVariableThenFail(String name, int value) {
                return "let " + name + " = " + value;
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "failedActionVariablesStayLocal"));

        assertTrue(failure.getMessage().startsWith("the action from declareVariableThenFail() didn't fail the run"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that clears a failed action's variables, or keeps none, passes the failed-action check"
            + " (#766)")
    void failedActionsVariablesClearedPass() {
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "failedActionVariablesStayLocal"));
        assertDoesNotThrow(() -> runCheck(unknownNames(new ToyExpressionLanguage(), false),
                "failedActionVariablesStayLocal"));
    }

    @Test
    @DisplayName("a language that refuses a name that isn't a fact when it loads the rule passes the failed-action"
            + " check, as it passes the variable check (#766)")
    void unknownNamesRefusedAtLoadPassFailedActionCheck() {
        assertDoesNotThrow(() -> runCheck(unknownNames(new ToyExpressionLanguage(), true),
                "failedActionVariablesStayLocal"));
    }

    @Test
    @DisplayName("a contract test whose declareVariableThenFail() returns null skips the failed-action check (#766)")
    void noDeclareVariableThenFailSkipped() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String declareVariableThenFail(String name, int value) {
                return null;
            }
        };

        assertThrows(TestAbortedException.class, () -> runCheck(test, "failedActionVariablesStayLocal"));
    }

    @Test
    @DisplayName("the variable check can't be undone by configure(): with two copies made when the rules load, it"
            + " still fails a language whose later run reads an earlier run's variable")
    void earlierRunsVariablesFailWhateverConfigureSets() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(twoCopiesAtLoad(sessionCachedVariables(new ToyExpressionLanguage())),
                        "actionVariablesStayLocal"));

        assertEquals("a later run read the variable 'y' an action declared in an earlier one, although the rule that"
                + " declares it didn't fire: {seen=2}", failure.getMessage());
        // And a later run's condition.
        assertThrows(AssertionFailedError.class,
                () -> runCheck(twoCopiesAtLoad(conditionsReadVariables(new ToyExpressionLanguage())),
                        "actionVariablesStayLocal"));
    }

    @Test
    @DisplayName("a language whose condition writes a map fact's property and is true fails the condition-write check"
            + " (#767)")
    void propertyWritingConditionFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(writingConditions(new ToyExpressionLanguage(), false), "conditionWritesRejected"));

        assertTrue(failure.getMessage().startsWith("a condition that writes a fact's property was neither rejected by"
                + " load nor failed by run"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose condition writes a map fact's property before it fails the rule fails the"
            + " condition-write check, since the fact has changed (#767)")
    void propertyWrittenBeforeTheFailureFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(writingConditions(new ToyExpressionLanguage(), true), "conditionWritesRejected"));

        assertEquals("a condition that writes a fact's property changed it ==> expected: <{creditScore=750}> but was:"
                + " <{creditScore=1}>", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose condition declares a variable and is true fails the condition-write check, when"
            + " its contract test writes no property (#767)")
    void declaringConditionFails() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return writingConditions(new ToyExpressionLanguage(), false);
            }

            @Override
            protected String propertyAssignment(String fact, String property, int value) {
                return null;
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "conditionWritesRejected"));

        assertTrue(failure.getMessage().startsWith("a condition that declares a variable was neither rejected by load"
                + " nor failed by run"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that rejects a condition that writes a property or declares a variable passes the"
            + " condition-write check, and so does one whose contract test has only one of them (#767)")
    void rejectedConditionWritesPass() {
        assertDoesNotThrow(() -> runCheck(new ToyExpressionLanguage(), "conditionWritesRejected"));
        ExpressionLanguageContractTest noDeclaration = new ToyExpressionLanguageContractTest() {
            @Override
            protected String conditionDeclaration(String name, int value) {
                return null;
            }
        };
        assertDoesNotThrow(() -> runCheck(noDeclaration, "conditionWritesRejected"));
    }

    @Test
    @DisplayName("a contract test whose propertyAssignment() and conditionDeclaration() return null skips the"
            + " condition-write check (#767)")
    void noConditionWritesSkipped() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected String propertyAssignment(String fact, String property, int value) {
                return null;
            }

            @Override
            protected String conditionDeclaration(String name, int value) {
                return null;
            }
        };

        assertThrows(TestAbortedException.class, () -> runCheck(test, "conditionWritesRejected"));
    }

    @Test
    @DisplayName("a language whose compiler's close() throws once it has warmed up a session fails the compiler check"
            + " when configure() makes copies when the rules load, and fails no other check (#725)")
    void compilerCloseFailingOnceWarmedUpFails() {
        ExpressionLanguageContractTest test = twoCopiesAtLoad(closeFailsOnceWarmedUp(new ToyExpressionLanguage()));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(test, "compilerClosed"));

        assertEquals("a compiler's close() threw java.lang.IllegalStateException: close() fails after warmUp(), which"
                + " the engine only logs at WARN", failure.getMessage());
        assertEquals(List.of("compilerClosed"), failedChecks(test));
    }

    @Test
    @DisplayName("a language whose compiler's close() throws once it has warmed up a session fails the compiler check"
            + " when configure() makes no copies when the rules load (#847)")
    void compilerCloseFailingOnceWarmedUpFailsUnconfigured() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(closeFailsOnceWarmedUp(new ToyExpressionLanguage()), "compilerClosed"));

        assertEquals("a compiler's close() threw java.lang.IllegalStateException: close() fails after warmUp(), which"
                + " the engine only logs at WARN", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose compiler's warmUp() throws fails the compiler check when the rules load, since the"
            + " check warms up a session whatever configure() sets (#847)")
    void warmUpFailureFailsCompilerCheck() {
        ExpressionLanguage stateful = withSessions(new ToyExpressionLanguage(), () -> new Session() {
        });
        ExpressionLanguage language = new ForwardingExpressionLanguage(stateful) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ForwardingExpressionCompiler(stateful.newCompiler(context)) {
                    @Override
                    public void warmUp(Session session) {
                        throw new IllegalStateException("the runtime failed to start");
                    }
                };
            }
        };

        RuleCompilationException failure = assertThrows(RuleCompilationException.class,
                () -> runCheck(language, "compilerClosed"));

        assertEquals("The 'toy' expression language failed to warm up a session: the runtime failed to start",
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose conditions it would reject fail only their first evaluation fails the checks that"
            + " reject a condition, and no other check (#847)")
    void conditionRejectedOnFirstRunOnlyFails() {
        ExpressionLanguage language = checksFirstRunOnly(new ToyExpressionLanguage());

        AssertionFailedError assignment = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "conditionAssignmentRejected"));
        assertEquals("a condition that assigns to a fact failed the first run but not the second",
                assignment.getMessage());
        AssertionFailedError write = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "conditionWritesRejected"));
        assertEquals("a condition that writes a fact's property failed the first run but not the second",
                write.getMessage());
        AssertionFailedError missing = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "missingPropertyFailsTheRun"));
        assertEquals("a misspelled property of a record fact failed the first run but not the second",
                missing.getMessage());

        assertEquals(Set.of("conditionAssignmentRejected", "conditionWritesRejected", "missingPropertyFailsTheRun",
                "outputNotReplaceable"), Set.copyOf(failedChecks(new ToyExpressionLanguageContractTest() {
                    @Override
                    protected ExpressionLanguage language() {
                        return language;
                    }
                })));
    }

    @Test
    @DisplayName("a language whose action that replaces the output fails only its first run fails the output check"
            + " (#847)")
    void outputReplacedAfterTheFirstRunFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(checksFirstRunOnly(new ToyExpressionLanguage()), "outputNotReplaceable"));

        assertEquals("an action that replaces the output failed the first run but not the second",
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose session remembers the expressions it rejected once fails the checks that repeat a"
            + " failed run, even when configure() makes two copies when the rules load (#847)")
    void rejectedOnFirstRunOfEachSessionOnlyFails() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return checksFirstRunOfEachSessionOnly(new ToyExpressionLanguage());
            }

            // A second run on the other copy would get a session that never saw the first run.
            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.copiesAtLoad(2);
            }
        };

        REPEATED.forEach((check, what) -> {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> runCheck(test, check),
                    check);
            assertEquals(what + " failed the first run but not the second", failure.getMessage());
        });
    }

    @Test
    @DisplayName("a language whose rule fails its second run somewhere other than where it failed its first fails the"
            + " checks that repeat a failed run, with what the second run threw (#847)")
    void failsDifferentlyOnTheSecondRunFails() {
        ExpressionLanguage language = failsElsewhereAfterTheFirstRun(new ToyExpressionLanguage());

        REPEATED.forEach((check, what) -> {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(language, check), check);
            String kind = "outputNotReplaceable".equals(check) ? "action" : "condition";
            assertTrue(failure.getMessage().startsWith(what + " failed the second run differently, not with a"
                    + " RuleExecutionException naming rule r and its " + kind + ": "), failure.getMessage());
            RuleExecutionException again = assertInstanceOf(RuleExecutionException.class, failure.getCause(), check);
            assertEquals("r", again.getRuleName(), check);
            assertNotEquals(ExpressionKind.valueOf(kind.toUpperCase(Locale.ROOT)), again.getExpressionKind(), check);
        });
    }

    @Test
    @DisplayName("a language whose second run fails with an exception that isn't a RuleExecutionException fails the"
            + " checks that repeat a failed run, with what the second run threw (#847)")
    void secondRunFailsOutsideTheRulesFails() {
        ExpressionLanguage language = rejectsFactNamesAfterTheFirstRun(checksFirstRunOnly(new ToyExpressionLanguage()));

        REPEATED.forEach((check, what) -> {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(language, check), check);
            assertTrue(failure.getMessage().startsWith(what + " failed the second run differently, not with a"
                    + " RuleExecutionException naming rule r and its "), failure.getMessage());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause(), check);
        });
    }

    @Test
    @DisplayName("a second run that fails with a RuleExecutionException naming another rule, or none, fails the"
            + " repeat, with what it threw (#847)")
    void secondRunFailsNamingAnotherRuleFails() throws Exception {
        // The engine names the rule that failed, which in the checks is r or the valid rule ok, which another test
        // covers, so the check's helper is called with an engine whose run fails as the engine never would.
        Method repeat = ExpressionLanguageContractTest.class.getDeclaredMethod("assertRunFailsAgain",
                RulesEngine.class, boolean.class, FactStore.class, ExpressionKind.class, String.class);
        repeat.setAccessible(true);
        for (String ruleName : Arrays.asList("q", null)) {
            RuleExecutionException again = new RuleExecutionException("the rule failed", null, ruleName,
                    ExpressionKind.CONDITION);
            RulesEngine<?> engine = (RulesEngine<?>) Proxy.newProxyInstance(RulesEngine.class.getClassLoader(),
                    new Class<?>[] {RulesEngine.class}, (proxy, method, args) -> {
                        throw again;
                    });

            InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> repeat.invoke(
                    null, engine, true, new FactMap<>(), ExpressionKind.CONDITION, "a condition that assigns"));

            AssertionFailedError failure = assertInstanceOf(AssertionFailedError.class, thrown.getCause());
            assertEquals("a condition that assigns failed the second run differently, not with a"
                    + " RuleExecutionException naming rule r and its condition: " + again, failure.getMessage());
            assertSame(again, failure.getCause());
        }
    }

    @Test
    @DisplayName("a language whose session a rejected expression leaves broken, so that every later run fails, fails"
            + " the checks that repeat a failed run in the valid rule before it, whether it stops rejecting the"
            + " expression or not (#870)")
    void sessionLeftBrokenAfterTheFirstFailureFails() {
        for (ExpressionLanguage rejecting : List.of(checksFirstRunOnly(new ToyExpressionLanguage()),
                rejectsOnEveryRun(new ToyExpressionLanguage(), new AtomicInteger()))) {
            ExpressionLanguage language = busySessions(rejecting, false);

            REPEATED.forEach((check, what) -> {
                AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                        () -> runCheck(language, check), check);
                assertTrue(failure.getMessage().startsWith(what + " failed the first run, and the second run failed"
                        + " in the valid rule ok, which runs before rule r: a failed run must leave its copy usable: "),
                        failure.getMessage());
                RuleExecutionException again = assertInstanceOf(RuleExecutionException.class, failure.getCause(),
                        check);
                assertEquals("ok", again.getRuleName(), check);
                assertEquals("outputNotReplaceable".equals(check) ? ExpressionKind.ACTION : ExpressionKind.CONDITION,
                        again.getExpressionKind(), check);
                assertTrue(failure.getMessage().contains("session busy"), failure.getMessage());
            });
        }
    }

    @Test
    @DisplayName("a language that fails the rejected expression on every run passes the checks that repeat a failed"
            + " run, which repeat it, and so does one whose session a failure leaves usable (#870)")
    void sessionUsableAfterTheFirstFailurePasses() {
        AtomicInteger rejections = new AtomicInteger();
        // Two runs of each rejected expression; conditionWritesRejected has three: a map, a bean and a declaration.
        Map<String, Integer> expected = Map.of("conditionAssignmentRejected", 2, "conditionWritesRejected", 6,
                "missingPropertyFailsTheRun", 2, "outputNotReplaceable", 2);
        for (ExpressionLanguage language : List.of(rejectsOnEveryRun(new ToyExpressionLanguage(), rejections),
                busySessions(rejectsOnEveryRun(new ToyExpressionLanguage(), rejections), true))) {
            REPEATED.keySet().forEach(check -> {
                rejections.set(0);
                assertDoesNotThrow(() -> runCheck(language, check), check);
                assertEquals(expected.get(check), rejections.get(), check);
            });
        }
    }

    @Test
    @DisplayName("a language that can't compile the valid rule the checks that repeat a failed run load first fails"
            + " them, saying so, rather than passing them untested (#870)")
    void guardRuleRejectedAtLoadFails() {
        ExpressionLanguage language = failsRuleOk(new ToyExpressionLanguage(), true);

        REPEATED.forEach((check, what) -> {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(language, check), check);
            assertTrue(failure.getMessage().startsWith(guardRuleFailed(what)), failure.getMessage());
            RuleCompilationException ex = assertInstanceOf(RuleCompilationException.class, failure.getCause(), check);
            assertEquals("ok", ex.getRuleName(), check);
        });
    }

    @Test
    @DisplayName("a language that rejects both the valid rule's action and the output assignment when the rules load"
            + " fails the output check with what the valid rule says, rather than passing it (#870)")
    void guardRuleRejectedBesideTheOutputAssignmentFails() {
        // A guard of the combined case, not a test that fails without the guard rule's check: the toy rejects
        // output = 1 when it compiles it, so the load fails for both rules, and its rule name is ok's, the first.
        ExpressionLanguage language = new ForwardingExpressionLanguage(new ToyExpressionLanguage()) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = super.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        if ("ok".equals(expression.ruleName())) {
                            throw new IllegalArgumentException("the action can't compile");
                        }
                        return compiler.compileAction(expression);
                    }
                };
            }
        };

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "outputNotReplaceable"));

        assertTrue(failure.getMessage().startsWith(guardRuleFailed("an action that replaces the output")),
                failure.getMessage());
        RuleCompilationException ex = assertInstanceOf(RuleCompilationException.class, failure.getCause());
        assertEquals(List.of("ok", "r"), ex.failures().stream().map(RuleCompilationException::getRuleName).toList());
    }

    @Test
    @DisplayName("a load failure that names no rule fails the checks that repeat a failed run, saying it names none,"
            + " rather than passing them untested (#870)")
    void loadFailureNamingNoRuleFails() {
        ExpressionLanguage language = new ForwardingExpressionLanguage(new ToyExpressionLanguage()) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throw new IllegalStateException("the runtime can't start");
            }
        };

        REPEATED.forEach((check, what) -> {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(language, check), check);
            assertTrue(failure.getMessage().startsWith(namesNoRule(what)), failure.getMessage());
            RuleCompilationException ex = assertInstanceOf(RuleCompilationException.class, failure.getCause(), check);
            assertNull(ex.getRuleName(), check);
        });
    }

    @Test
    @DisplayName("a first run that fails naming no rule, since the language can't make a session, fails the checks"
            + " that repeat a failed run, whether a later run can make one or not (#870)")
    void firstRunFailureNamingNoRuleFails() {
        // A language that rejects r's expression only when it runs it, so that the rules load and the run makes the
        // copy, and its session, which newSession() can't: ever, or only the first time it's called, so that the
        // second run gets a session and fails in r.
        for (boolean onlyOnce : List.of(false, true)) {
            REPEATED.forEach((check, what) -> {
                AtomicBoolean called = new AtomicBoolean();
                ExpressionLanguage language = withSessions(
                        rejectsOnEveryRun(new ToyExpressionLanguage(), new AtomicInteger()), () -> {
                            if (onlyOnce && called.getAndSet(true)) {
                                return new Session() {
                                };
                            }
                            throw new IllegalStateException("the runtime can't start");
                        });

                AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                        () -> runCheck(language, check), check);
                assertTrue(failure.getMessage().startsWith(namesNoRule(what)), failure.getMessage());
                RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, failure.getCause(),
                        check);
                assertNull(ex.getRuleName(), check);
            });
        }
    }

    /** What a check that repeats a failed run says when load or the first run failed naming no rule. */
    private static String namesNoRule(String what) {
        return "the check of " + what + " failed before rule r ran, with a failure that names no rule: ";
    }

    @Test
    @DisplayName("a language whose valid rule, which the checks that repeat a failed run load first, fails every run"
            + " fails them, saying so, rather than blaming the session (#870)")
    void guardRuleFailingEveryRunFails() {
        // A language that rejects r's expression when it runs it, so that the rules load and ok runs.
        ExpressionLanguage language = failsRuleOk(rejectsOnEveryRun(new ToyExpressionLanguage(), new AtomicInteger()),
                false);

        REPEATED.forEach((check, what) -> {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(language, check), check);
            assertTrue(failure.getMessage().startsWith(guardRuleFailed(what)), failure.getMessage());
            RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, failure.getCause(), check);
            assertEquals("ok", ex.getRuleName(), check);
        });
    }

    /** What a check that repeats a failed run says when its valid rule {@code ok} failed, before what it threw. */
    private static String guardRuleFailed(String what) {
        return "the check of " + what + " failed in the valid rule ok, which runs before rule r, so the language can't"
                + " run the check's guard rule, alwaysTrue() with putFact(): ";
    }

    @Test
    @DisplayName("a language whose condition rejects a write to a map fact, but calls a bean fact's setter, fails the"
            + " condition-write check (#847)")
    void beanWritingConditionFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(writesThroughSetters(new ToyExpressionLanguage(), false), "conditionWritesRejected"));

        assertTrue(failure.getMessage().startsWith("a condition that writes a bean fact's property was neither"
                + " rejected by load nor failed by run"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose condition calls a bean fact's setter before it fails the rule fails the"
            + " condition-write check, since the fact has changed (#847)")
    void beanWrittenBeforeTheFailureFails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(writesThroughSetters(new ToyExpressionLanguage(), true), "conditionWritesRejected"));

        assertEquals("a condition that writes a bean fact's property changed it ==> expected: <750> but was: <1>",
                failure.getMessage());
    }

    @Test
    @DisplayName("an output that prints as the one expected, and isn't, fails with a message that says where the"
            + " values differ and what each one's class is (#768)")
    void outputThatPrintsTheSameExplained() {
        ExpressionLanguage language = textOutputs(new ToyExpressionLanguage());

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "conditionReadsFacts"));

        assertEquals("expected: <{seen=1}> but was: <{seen=1}>, and at seen, expected 1 (java.lang.Integer) but was 1"
                + " (java.lang.String)", failure.getMessage());
        assertEquals(Map.of("seen", 1), failure.getExpected().getValue());
        assertEquals(Map.of("seen", "1"), failure.getActual().getValue());

        AssertionFailedError notBoolean = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "conditionMustBeBoolean"));
        assertEquals("expected: <{seen=true}> but was: <{seen=true}>, and at seen, expected true (java.lang.Boolean)"
                + " but was true (java.lang.String)", notBoolean.getMessage());

        // A list of outputs: the second run of the first worker is the first that differs and prints the same.
        AssertionFailedError concurrent = assertThrows(AssertionFailedError.class,
                () -> runCheck(language, "concurrentRuns"));
        assertTrue(concurrent.getMessage().matches("(?s).*, and at \\[1]\\.(seen|copied), expected 1"
                + " \\(java\\.lang\\.Integer\\) but was 1 \\(java\\.lang\\.String\\)"), concurrent.getMessage());

        ExpressionLanguageContractTest usableName = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            @Override
            protected Collection<String> usableFactNames() {
                return List.of("score");
            }
        };
        AssertionFailedError usable = assertThrows(AssertionFailedError.class,
                () -> runCheck(usableName, "usableFactNamesAccepted"));
        assertEquals("a rule on the fact name 'score' didn't put its value: expected: <{seen=1}> but was: <{seen=1}>,"
                + " and at seen, expected 1 (java.lang.Integer) but was 1 (java.lang.String)", usable.getMessage());
    }

    @Test
    @DisplayName("a language whose actions change a built-in that a later run sees fails the shared-state check,"
            + " whatever configure() sets (#848)")
    void sharedBuiltinsFail() {
        for (ExpressionLanguageContractTest test : List.of(
                new SharedStateContractTest(sharedBuiltins(new ToyExpressionLanguage(), Builtins.SESSION)),
                // The run that changes it fails, but the change is made.
                new SharedStateContractTest(sharedBuiltins(new ToyExpressionLanguage(), Builtins.WRITE_THEN_FAIL)),
                new SharedStateContractTest(sharedBuiltins(new ToyExpressionLanguage(), Builtins.SESSION)) {
                    @Override
                    protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                        builder.copiesAtLoad(2);
                    }
                })) {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(test, "sharedStateStaysLocal"));

            assertEquals("a later run's condition saw what an earlier run's action changed in the language's shared"
                    + " state: {seen=2}", failure.getMessage());
        }
    }

    @Test
    @DisplayName("a language that refuses a change to a built-in when it loads the rule, fails the action that makes"
            + " it, or keeps it to the run passes the shared-state check (#848)")
    void builtinsKeptFromLaterRunsPass() {
        for (Builtins builtins : List.of(Builtins.REFUSED_AT_LOAD, Builtins.READ_ONLY, Builtins.RUN)) {
            assertDoesNotThrow(() -> runCheck(new SharedStateContractTest(
                    sharedBuiltins(new ToyExpressionLanguage(), builtins)), "sharedStateStaysLocal"), builtins.name());
        }
    }

    @Test
    @DisplayName("the shared-state check doesn't take a rejected or failed condition for a refused change (#848)")
    void otherFailuresNotTakenForARefusedChange() {
        // The toy rejects "builtin leak ==" when it loads the rule, and its > fails the rule for a string.
        ExpressionLanguage language = sharedBuiltins(new ToyExpressionLanguage(), Builtins.SESSION);
        ExpressionLanguageContractTest refusedAtLoad = new SharedStateContractTest(language) {
            @Override
            protected String sharedStateEquals(String name, int value) {
                return "builtin " + name + " ==";
            }
        };
        ExpressionLanguageContractTest failsTheRun = new SharedStateContractTest(language) {
            @Override
            protected String sharedStateEquals(String name, int value) {
                return "'text' > " + value;
            }
        };

        AssertionFailedError atLoad = assertThrows(AssertionFailedError.class,
                () -> runCheck(refusedAtLoad, "sharedStateStaysLocal"));
        assertTrue(atLoad.getMessage().startsWith("sharedStateEquals() must be false, not fail, while the shared state"
                + " doesn't hold its value, but load() refused the rule that reads it, naming its condition: "),
                atLoad.getMessage());
        RuleCompilationException refused = assertInstanceOf(RuleCompilationException.class, atLoad.getCause());
        assertEquals("reads", refused.getRuleName());
        assertEquals(ExpressionKind.CONDITION, refused.getExpressionKind());
        AssertionFailedError atRun = assertThrows(AssertionFailedError.class,
                () -> runCheck(failsTheRun, "sharedStateStaysLocal"));
        assertTrue(atRun.getMessage().startsWith("sharedStateEquals() must be false, not fail, while the shared state"
                + " doesn't hold its value, but the run before any change failed, naming its condition: "),
                atRun.getMessage());
        RuleExecutionException failed = assertInstanceOf(RuleExecutionException.class, atRun.getCause());
        assertEquals("reads", failed.getRuleName());
        assertEquals(ExpressionKind.CONDITION, failed.getExpressionKind());
    }

    @Test
    @DisplayName("the shared-state check doesn't take a rejected or failed condition of the rule that makes the change"
            + " for a refused change (#848)")
    void changingRulesConditionNotTakenForARefusedChange() {
        // The check's factEquals() is the condition of the rule that makes the change.
        ExpressionLanguage language = sharedBuiltins(new ToyExpressionLanguage(), Builtins.SESSION);
        ExpressionLanguageContractTest refusedAtLoad = new SharedStateContractTest(language) {
            @Override
            protected String factEquals(String fact, int value) {
                return fact + " ==";
            }
        };
        ExpressionLanguageContractTest failsTheRun = new SharedStateContractTest(language) {
            @Override
            protected String factEquals(String fact, int value) {
                return "'text' > " + value;
            }
        };

        RuleCompilationException atLoad = assertThrows(RuleCompilationException.class,
                () -> runCheck(refusedAtLoad, "sharedStateStaysLocal"));
        assertEquals("changes", atLoad.getRuleName());
        assertEquals(ExpressionKind.CONDITION, atLoad.getExpressionKind());
        RuleExecutionException atRun = assertThrows(RuleExecutionException.class,
                () -> runCheck(failsTheRun, "sharedStateStaysLocal"));
        assertEquals("changes", atRun.getRuleName());
        assertEquals(ExpressionKind.CONDITION, atRun.getExpressionKind());
    }

    @Test
    @DisplayName("the shared-state check fails a contract test whose sharedStateEquals() is true before any action"
            + " changed the state, rather than blame a leak (#848)")
    void sharedStateTrueBeforeTheChangeFails() {
        // Whether the action keeps the change to its run, or fails before it changes anything.
        for (Builtins builtins : List.of(Builtins.RUN, Builtins.READ_ONLY)) {
            ExpressionLanguageContractTest test = new SharedStateContractTest(
                    sharedBuiltins(new ToyExpressionLanguage(), builtins)) {
                @Override
                protected String sharedStateEquals(String name, int value) {
                    return "true";
                }
            };

            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(test, "sharedStateStaysLocal"), builtins.name());

            assertEquals("sharedStateEquals() was true before any action changed the language's shared state, or an"
                    + " earlier engine left it changed: {seen=0}", failure.getMessage(), builtins.name());
        }
    }

    @Test
    @DisplayName("a contract test whose changeSharedState() returns an action, and whose sharedStateEquals() returns"
            + " null, fails the shared-state check rather than skip it (#848)")
    void noSharedStateEqualsFails() {
        ExpressionLanguageContractTest test = new SharedStateContractTest(
                sharedBuiltins(new ToyExpressionLanguage(), Builtins.SESSION)) {
            @Override
            protected String sharedStateEquals(String name, int value) {
                return null;
            }
        };

        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> runCheck(test, "sharedStateStaysLocal"));

        assertEquals("sharedStateEquals() returned null, but changeSharedState() didn't", failure.getMessage());
    }

    @Test
    @DisplayName("a contract test whose changeSharedState() returns null skips the shared-state check (#848)")
    void noChangeSharedStateSkipped() {
        assertThrows(TestAbortedException.class,
                () -> runCheck(new ToyExpressionLanguage(), "sharedStateStaysLocal"));
    }

    @Test
    @DisplayName("a language whose action variables last the run fails the variable check while its contract test"
            + " doesn't say so (#853)")
    void runVariablesFailWithoutTheHook() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(runVariables(new ToyExpressionLanguage()), "actionVariablesStayLocal"));

        assertEquals("a later rule read the variable 'y' an action declared: {seen=2}", failure.getMessage());
    }

    @Test
    @DisplayName("a language whose action variables last the run, and no longer, passes the variable check once its"
            + " contract test says so (#853)")
    void runVariablesPassWithTheHook() {
        assertDoesNotThrow(() -> runCheck(lastingTheRun(runVariables(new ToyExpressionLanguage())),
                "actionVariablesStayLocal"));
    }

    @Test
    @DisplayName("a language whose action variables reach a later run fails the variable check, though its contract"
            + " test says they last the run (#853)")
    void sessionVariablesFailWithTheHook() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(lastingTheRun(sessionVariables(new ToyExpressionLanguage(), false)),
                        "actionVariablesStayLocal"));

        assertEquals("a later run read the variable 'y' an action declared in an earlier one, although the rule that"
                + " declares it didn't fire: {seen=2}", failure.getMessage());
    }

    @Test
    @DisplayName("a contract test that says its language's action variables last the run fails the variable check when"
            + " a later rule in the run can't read them (#853)")
    void localVariablesFailWithTheHook() {
        // The toy fails the run on the unknown y; the wrapped ones read it as null, or refuse it at load.
        AssertionFailedError failedRun = assertThrows(AssertionFailedError.class,
                () -> runCheck(lastingTheRun(new ToyExpressionLanguage()), "actionVariablesStayLocal"));
        AssertionFailedError readNull = assertThrows(AssertionFailedError.class,
                () -> runCheck(lastingTheRun(unknownNames(new ToyExpressionLanguage(), false)),
                        "actionVariablesStayLocal"));
        AssertionFailedError refused = assertThrows(AssertionFailedError.class,
                () -> runCheck(lastingTheRun(unknownNames(new ToyExpressionLanguage(), true)),
                        "actionVariablesStayLocal"));

        assertTrue(failedRun.getMessage().startsWith("actionVariablesLastTheRun() returns true, but the run in which a"
                + " later rule reads the variable 'y' an action declared failed: "), failedRun.getMessage());
        assertEquals("actionVariablesLastTheRun() returns true, but a later rule in the same run didn't read the"
                + " variable 'y' an action declared: {seen=null}", readNull.getMessage());
        assertTrue(refused.getMessage().startsWith("actionVariablesLastTheRun() returns true, but load() refused the"
                + " rule that reads the variable 'y' an earlier rule's action declares: "), refused.getMessage());
    }
}
