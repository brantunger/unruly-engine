package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A nested {@code run()} that rejects its facts, and a nested {@code load()} that fails, throw the exception their
 * callers expect, an {@link IllegalArgumentException} or a {@link RuleCompilationException}, not the engine's own
 * failure of a run; they are still logged once, where they happened, and the code around them, a rule, the output
 * supplier, a listener or a language, names them as {@code a nested run() failed: } or {@code a nested load() failed: }
 * and the innermost failure, however many runs deep, without logging them again. What the caller of the nested
 * {@code run()} or {@code load()} catches is unchanged.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a nested run's rejected facts, and a nested load()'s failure, are logged once")
class NestedRejectionLogTest {

    private static final String OUTPUT_REJECTED = "'output' is reserved for the output object and cannot be used as "
            + "a fact name";
    private static final String DUPLICATE = "Duplicate rule name 'dup'";
    private static final String NESTED_RUN = "a nested run() failed: ";
    private static final String NESTED_LOAD = "a nested load() failed: ";
    private static final String OUTER_ACTION = "Failed to execute action for rule 'outer-rule': ";
    private static final String INNER_FAILURE = "Failed to execute action for rule 'inner-rule': inner rule failed";

    private static RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).language(language);
    }

    private static Rule rule(String name) {
        return Rule.builder().ruleName(name).condition("c").action("a").build();
    }

    private static RulesEngine<Map<String, Object>> engine(String ruleName, ExpressionLanguage language,
                                                           Supplier<Map<String, Object>> output,
                                                           RuleListener... listeners) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>firstMatch(output)
                .language(language);
        for (RuleListener listener : listeners) {
            builder.listener(listener);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(rule(ruleName)));
        return engine;
    }

    private static CompiledAction doing(Runnable what) {
        return (action, session) -> {
            what.run();
            return ActionResult.done();
        };
    }

    private static RulesEngine<Map<String, Object>> outer(Runnable action) {
        return engine("outer-rule", new StubExpressionLanguage().action(doing(action)), HashMap::new);
    }

    private static RulesEngine<Map<String, Object>> plain(String ruleName, RuleListener... listeners) {
        return engine(ruleName, new StubExpressionLanguage(), HashMap::new, listeners);
    }

    private static RulesEngine<Map<String, Object>> failing() {
        return engine("inner-rule", new StubExpressionLanguage().action(doing(() -> {
            throw new IllegalStateException("inner rule failed");
        })), HashMap::new);
    }

    private static RulesEngine<Map<String, Object>> unloaded() {
        return builder(new StubExpressionLanguage()).build();
    }

    /** Runs an engine with a fact named {@code output}, which every run rejects. */
    private static void runWithOutputFact(RulesEngine<Map<String, Object>> engine) {
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("output", 1);
        engine.run(facts);
    }

    private static void loadDuplicates(RulesEngine<Map<String, Object>> engine) {
        engine.load(List.of(rule("dup"), rule("dup")));
    }

    private static RuleListener onBeforeRun(Runnable what) {
        return new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                what.run();
            }
        };
    }

    /**
     * Returns the messages the engine logged at one level, in order.
     *
     * @param logs  What was logged
     * @param level {@code ERROR} or {@code WARN}
     * @return The messages, without the thread, level and logger
     */
    private static List<String> logged(String logs, String level) {
        String prefix = level + " " + ENGINE_LOGGER;
        return logs.lines().filter(line -> line.contains(prefix))
                .map(line -> line.substring(line.indexOf(prefix) + prefix.length())).toList();
    }

    /** What a run threw, and what the engine logged while it ran. */
    private record Outcome(Throwable thrown, String logs) {
        List<String> errors() {
            return logged(logs, "ERROR");
        }

        List<String> warnings() {
            return logged(logs, "WARN");
        }
    }

    private static Outcome failed(Executable run) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(Throwable.class, run)));
        return new Outcome(thrown.get(), logs);
    }

    private static Outcome returned(Runnable run) {
        return new Outcome(null, logsOf(run));
    }

    /**
     * Asserts that the outer run failed with {@code message} and the engine logged {@code logged} at ERROR, and
     * nothing else.
     *
     * @return What the outer run threw
     */
    private static Throwable assertFailed(Outcome outcome, String message, String... logged) {
        assertEquals(message, outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(logged), outcome.errors(), outcome.logs());
        return outcome.thrown();
    }

    // A nested run whose facts are rejected

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a fact named output", "a fact of the wrong type", "a declared fact left out",
        "a fact nobody declared"})
    @DisplayName("an action whose run() is given facts the engine rejects names that failure, logged once")
    void actionNestedRejectedFacts(String how) {
        RulesEngineBuilder<Map<String, Object>> inner = builder(new StubExpressionLanguage());
        FactMap<Object> facts = new FactMap<>();
        String rejected = switch (how) {
            case "a fact named output" -> {
                facts.setValue("output", 1);
                yield OUTPUT_REJECTED;
            }
            case "a fact of the wrong type" -> {
                inner.fact("n", Integer.class);
                facts.setValue("n", "s");
                yield "Fact 'n' was declared as java.lang.Integer, but the run supplied a java.lang.String";
            }
            case "a declared fact left out" -> {
                inner.fact("n", Integer.class).requireDeclaredFacts();
                yield "Fact 'n' was declared, but the run didn't supply it, and this engine was built with "
                        + "requireDeclaredFacts()";
            }
            default -> {
                inner.requireDeclaredFacts();
                facts.setValue("extra", 1);
                yield "Fact 'extra' wasn't declared, and this engine was built with requireDeclaredFacts()";
            }
        };
        RulesEngine<Map<String, Object>> nested = inner.build();
        nested.load(List.of(rule("inner-rule")));
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                nested.run(facts);
            } catch (IllegalArgumentException e) {
                caught.set(e);
                throw e;
            }
        });

        Throwable thrown = assertFailed(failed(() -> engine.run(new FactMap<>())),
                OUTER_ACTION + NESTED_RUN + rejected, rejected);

        assertInstanceOf(RuleExecutionException.class, thrown);
        assertSame(caught.get(), thrown.getCause());
    }

    @Test
    @DisplayName("an action whose run() has a name its language rejects names the language's exception, logged once")
    void actionNestedNameRejectedByLanguage() {
        IllegalArgumentException rejection = new IllegalArgumentException("bad name x");
        RulesEngine<Map<String, Object>> nested = engine("inner-rule", new StubExpressionLanguage().checkFactName(
                name -> {
                    throw rejection;
                }), HashMap::new);
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            FactMap<Object> facts = new FactMap<>();
            facts.setValue("x", 1);
            try {
                nested.run(facts);
            } catch (IllegalArgumentException e) {
                caught.set(e);
                throw e;
            }
        });

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + NESTED_RUN + "bad name x",
                "bad name x");
        assertSame(rejection, caught.get());
    }

    @Test
    @DisplayName("an action whose run()'s language fails to check a name names that failure, logged once")
    void actionNestedNameCheckFailure() {
        RulesEngine<Map<String, Object>> nested = engine("inner-rule", new StubExpressionLanguage().checkFactName(
                name -> {
                    throw new IllegalStateException("check broke");
                }), HashMap::new);
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            FactMap<Object> facts = new FactMap<>();
            facts.setValue("x", 1);
            nested.run(facts);
        });
        String failure = "The 'stub' expression language failed to check fact name 'x': check broke";

        Throwable thrown = assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + NESTED_RUN + failure,
                failure);

        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertInstanceOf(IllegalStateException.class, thrown.getCause().getCause());
    }

    // A nested load() that fails

    @Test
    @DisplayName("an action whose load() finds two rules of one name names that failure, logged once")
    void actionNestedLoadDuplicate() {
        RulesEngine<Map<String, Object>> other = unloaded();
        RulesEngine<Map<String, Object>> engine = outer(() -> loadDuplicates(other));

        Throwable thrown = assertFailed(failed(() -> engine.run(new FactMap<>())),
                OUTER_ACTION + NESTED_LOAD + DUPLICATE, DUPLICATE);

        assertInstanceOf(RuleCompilationException.class, thrown.getCause());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"one rule", "two rules"})
    @DisplayName("an action whose load() fails to compile names that failure, all of it, logged once per rule")
    void actionNestedLoadCompileFailure(String how) {
        RulesEngine<Map<String, Object>> other = builder(new StubExpressionLanguage().compileAction(expression -> {
            throw new IllegalStateException("no compile");
        })).build();
        boolean two = "two rules".equals(how);
        List<Rule> rules = two ? List.of(rule("r1"), rule("r2")) : List.of(rule("r1"));
        RulesEngine<Map<String, Object>> engine = outer(() -> other.load(rules));
        String first = "Action for rule 'r1' failed to compile: no compile";
        String second = "Action for rule 'r2' failed to compile: no compile";

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        if (two) {
            assertFailed(outcome, OUTER_ACTION + NESTED_LOAD + "2 rules failed to compile: " + first + "; " + second,
                    first, second);
        } else {
            assertFailed(outcome, OUTER_ACTION + NESTED_LOAD + first, first);
        }
        assertInstanceOf(RuleCompilationException.class, outcome.thrown().getCause());
    }

    @Test
    @DisplayName("an action whose load() can't create a session for a copy names that failure, logged once")
    void actionNestedLoadSessionFailure() {
        IllegalStateException noSession = new IllegalStateException("no session");
        RulesEngine<Map<String, Object>> other = builder(new StubExpressionLanguage().newSession(() -> {
            throw noSession;
        })).copiesAtLoad(1).build();
        RulesEngine<Map<String, Object>> engine = outer(() -> other.load(List.of(rule("r1"))));
        String failure = "The 'stub' expression language failed to create a session: no session";

        Throwable thrown = assertFailed(failed(() -> engine.run(new FactMap<>())),
                OUTER_ACTION + NESTED_LOAD + failure, failure);

        // What load() throws is as it was: the language's exception is its cause.
        RuleCompilationException loadFailure = assertInstanceOf(RuleCompilationException.class, thrown.getCause());
        assertSame(noSession, loadFailure.getCause());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a failing run()", "a run() that rejects its facts"})
    @DisplayName("an action whose load() fails as its language's session runs a run() names the innermost failure")
    void actionNestedLoadSessionRunFailure(String how) {
        boolean rejects = how.endsWith("facts");
        RulesEngine<Map<String, Object>> nested = rejects ? plain("inner-rule") : failing();
        RulesEngine<Map<String, Object>> other = builder(new StubExpressionLanguage().newSession(() -> {
            if (rejects) {
                runWithOutputFact(nested);
            } else {
                nested.run(new FactMap<>());
            }
            return Session.none();
        })).copiesAtLoad(1).build();
        RulesEngine<Map<String, Object>> engine = outer(() -> other.load(List.of(rule("r1"))));
        String failure = rejects ? OUTPUT_REJECTED : INNER_FAILURE;

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + NESTED_RUN + failure, failure);
    }

    @Test
    @DisplayName("an action whose load() fails for two rules whose compiler runs a failing run() names both")
    void actionNestedLoadOfRulesWhoseCompilerRunsFailingRun() {
        RulesEngine<Map<String, Object>> nested = failing();
        RulesEngine<Map<String, Object>> other = builder(new StubExpressionLanguage().compileAction(expression -> {
            nested.run(new FactMap<>());
            return doing(() -> { });
        })).build();
        RulesEngine<Map<String, Object>> engine = outer(() -> other.load(List.of(rule("r1"), rule("r2"))));

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + NESTED_LOAD
                + "2 rules failed to compile: Action for rule 'r1' failed to compile: " + NESTED_RUN + INNER_FAILURE
                + "; Action for rule 'r2' failed to compile: " + NESTED_RUN + INNER_FAILURE,
                INNER_FAILURE, INNER_FAILURE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a load() whose rule fails to compile", "a load() that can't create a session",
        "a run() whose language fails to check a name", "a run() whose language rejects a name",
        "a load() whose rule fails to compile, for a cause with a message"})
    @DisplayName("the failure a nested run() or load() logged names a root cause without a message once")
    void rootCauseNotedOnce(String how) {
        boolean named = how.endsWith("a message");
        RuntimeException root = named ? new RuntimeException("root") : new RuntimeException();
        String note = named ? "" : " (caused by java.lang.RuntimeException)";
        StubExpressionLanguage language = new StubExpressionLanguage();
        String failure;
        Runnable action;
        if (how.startsWith("a load()")) {
            boolean session = how.contains("session");
            RulesEngine<Map<String, Object>> other = session
                    ? builder(language.newSession(() -> {
                        throw new IllegalStateException("no session", root);
                    })).copiesAtLoad(1).build()
                    : builder(language.compileAction(expression -> {
                        throw new IllegalStateException("no compile", root);
                    })).build();
            action = () -> other.load(List.of(rule("r1")));
            failure = NESTED_LOAD + (session ? "The 'stub' expression language failed to create a session: no session"
                    : "Action for rule 'r1' failed to compile: no compile") + note;
        } else {
            boolean rejects = how.endsWith("rejects a name");
            RulesEngine<Map<String, Object>> nested = engine("inner-rule", language.checkFactName(name -> {
                throw rejects ? new IllegalArgumentException("bad name", root)
                        : new IllegalStateException("check broke", root);
            }), HashMap::new);
            action = () -> {
                FactMap<Object> facts = new FactMap<>();
                facts.setValue("x", 1);
                nested.run(facts);
            };
            failure = NESTED_RUN + (rejects ? "bad name"
                    : "The 'stub' expression language failed to check fact name 'x': check broke") + note;
        }
        RulesEngine<Map<String, Object>> engine = outer(action);

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + failure,
                failure.substring(failure.indexOf(": ") + 2));
    }

    // The output supplier and listeners

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"run()", "load()"})
    @DisplayName("an output supplier whose run() rejects its facts, or whose load() fails, names it, logged once")
    void factoryNestedRejection(String call) {
        boolean run = "run()".equals(call);
        RulesEngine<Map<String, Object>> nested = run ? plain("inner-rule") : unloaded();
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage(), () -> {
            if (run) {
                runWithOutputFact(nested);
            } else {
                loadDuplicates(nested);
            }
            return new HashMap<>();
        });
        String failure = run ? OUTPUT_REJECTED : DUPLICATE;

        assertFailed(failed(() -> engine.run(new FactMap<>())),
                "Output factory threw: " + (run ? NESTED_RUN : NESTED_LOAD) + failure, failure);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"run()", "load()"})
    @DisplayName("a listener whose run() rejects its facts, or whose load() fails, has it logged once, by that call")
    void listenerNestedRejection(String call) {
        boolean run = "run()".equals(call);
        RulesEngine<Map<String, Object>> nested = run ? plain("inner-rule") : unloaded();
        RulesEngine<Map<String, Object>> engine = plain("outer-rule", onBeforeRun(() -> {
            if (run) {
                runWithOutputFact(nested);
            } else {
                loadDuplicates(nested);
            }
        }));
        String failure = run ? OUTPUT_REJECTED : DUPLICATE;

        Outcome outcome = returned(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(failure), outcome.errors(), outcome.logs());
        assertEquals(List.of(), outcome.warnings(), outcome.logs());
        assertTrue(outcome.logs().contains("DEBUG " + ENGINE_LOGGER + "Listener threw exception in beforeRun"),
                outcome.logs());
    }

    @Test
    @DisplayName("a nested run's listener that rethrows the rejection it's told of is described with its class")
    void toldRejectionDescribedWithItsClass() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule", new RuleListener() {
            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                throw error;
            }
        });
        RulesEngine<Map<String, Object>> engine = outer(() -> runWithOutputFact(nested));

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        assertEquals(List.of("Listener threw exception in onRunError: " + IllegalArgumentException.class.getName()
                + ": " + OUTPUT_REJECTED), outcome.warnings(), outcome.logs());
    }

    // Two runs deep

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"run()", "load()"})
    @DisplayName("through two actions, the failure names the innermost run() or load() once, logged once")
    void twoActionsDeep(String call) {
        boolean run = "run()".equals(call);
        RulesEngine<Map<String, Object>> nested = run ? plain("inner-rule") : unloaded();
        RulesEngine<Map<String, Object>> mid = engine("mid-rule", new StubExpressionLanguage().action(doing(() -> {
            if (run) {
                runWithOutputFact(nested);
            } else {
                loadDuplicates(nested);
            }
        })), HashMap::new);
        RulesEngine<Map<String, Object>> engine = outer(() -> mid.run(new FactMap<>()));
        String failure = run ? OUTPUT_REJECTED : DUPLICATE;

        Outcome outcome = failed(() -> engine.run(new FactMap<>()));

        assertFailed(outcome, OUTER_ACTION + (run ? NESTED_RUN : NESTED_LOAD) + failure, failure);
        assertFalse(outcome.logs().contains("mid-rule"), outcome.logs());
    }

    // Languages

    @Test
    @DisplayName("a language whose newSession() runs a run() that rejects its facts isn't logged again")
    void sessionNestedRejection() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().newSession(() -> {
            runWithOutputFact(nested);
            return Session.none();
        }), HashMap::new);

        assertFailed(failed(() -> engine.run(new FactMap<>())),
                "The 'stub' expression language failed to create a session: " + NESTED_RUN + OUTPUT_REJECTED,
                OUTPUT_REJECTED);
    }

    @Test
    @DisplayName("a load() whose language's compiler runs a run() that rejects its facts isn't logged again")
    void compileNestedRejection() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        RulesEngine<Map<String, Object>> engine = builder(new StubExpressionLanguage().compileAction(expression -> {
            runWithOutputFact(nested);
            return doing(() -> { });
        })).build();

        Throwable thrown = assertFailed(failed(() -> engine.load(List.of(rule("r")))),
                "Action for rule 'r' failed to compile: " + NESTED_RUN + OUTPUT_REJECTED, OUTPUT_REJECTED);

        assertInstanceOf(RuleCompilationException.class, thrown);
    }

    @Test
    @DisplayName("a load() whose language's check of a declared name runs a failing load() isn't logged again")
    void declaredNameNestedLoadFailure() {
        RulesEngine<Map<String, Object>> other = unloaded();
        RulesEngine<Map<String, Object>> engine = builder(new StubExpressionLanguage().checkFactName(
                name -> loadDuplicates(other))).fact("x", Integer.class).build();

        Throwable thrown = assertFailed(failed(() -> engine.load(List.of(rule("r")))),
                "Declared fact 'x' can't be used: " + NESTED_LOAD + DUPLICATE, DUPLICATE);

        assertInstanceOf(RuleCompilationException.class, thrown);
    }

    @Test
    @DisplayName("a run whose language's check of a name throws a nested run()'s rejection throws it as it came")
    void factNameCheckThrowsNestedRejection() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().checkFactName(
                name -> {
                    try {
                        runWithOutputFact(nested);
                    } catch (IllegalArgumentException e) {
                        caught.set(e);
                        throw e;
                    }
                }), HashMap::new);
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        Throwable thrown = assertFailed(failed(() -> engine.run(facts)), OUTPUT_REJECTED, OUTPUT_REJECTED);

        assertSame(caught.get(), thrown);
    }

    // What an action does with the nested failure

    @Test
    @DisplayName("an action that wraps a nested run()'s rejection keeps its own message, and names the rejection")
    void wrappedNestedRejection() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                runWithOutputFact(nested);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("wrapped", e);
            }
        });
        String failure = OUTER_ACTION + "wrapped (after " + NESTED_RUN + OUTPUT_REJECTED + ")";

        assertFailed(failed(() -> engine.run(new FactMap<>())), failure, OUTPUT_REJECTED, failure);
    }

    @Test
    @DisplayName("a run around an action that wrapped a nested load()'s failure in its own names a run() that failed")
    void wrappedNestedLoadFailureRunsDeep() {
        RulesEngine<Map<String, Object>> loader = unloaded();
        RulesEngine<Map<String, Object>> middle = engine("mid-rule", new StubExpressionLanguage().action(doing(() -> {
            try {
                loadDuplicates(loader);
            } catch (RuleCompilationException e) {
                throw new IllegalStateException("fallback pricing failed", e);
            }
        })), HashMap::new);
        RulesEngine<Map<String, Object>> engine = outer(() -> middle.run(new FactMap<>()));
        String midFailure = "Failed to execute action for rule 'mid-rule': fallback pricing failed (after "
                + NESTED_LOAD + DUPLICATE + ")";

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + NESTED_RUN + midFailure, DUPLICATE,
                midFailure);
    }

    @Test
    @DisplayName("an action that wraps a nested run()'s rejection in an exception that adds nothing names it, logged"
            + " once")
    void transparentlyWrappedNestedRejection() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                runWithOutputFact(nested);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(e);
            }
        });

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + NESTED_RUN + OUTPUT_REJECTED,
                OUTPUT_REJECTED);
    }

    @Test
    @DisplayName("an action that throws a failure of its own for a nested rejection has it logged too")
    void ownFailureForANestedRejection() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                runWithOutputFact(nested);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("own failure");
            }
        });

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + "own failure", OUTPUT_REJECTED,
                OUTER_ACTION + "own failure");
    }

    @Test
    @DisplayName("a nested rejection kept by one rule and thrown by another later in the same run isn't logged again")
    void keptRejectionThrownLaterInTheSameRun() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        AtomicReference<IllegalArgumentException> kept = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new StubExpressionLanguage().compileAction(expression -> doing(() -> {
                    if (kept.get() != null) {
                        throw kept.get();
                    }
                    try {
                        runWithOutputFact(nested);
                    } catch (IllegalArgumentException e) {
                        kept.set(e);
                    }
                }))).build();
        engine.load(List.of(rule("first"), rule("second")));

        assertFailed(failed(() -> engine.run(new FactMap<>())),
                "Failed to execute action for rule 'second': " + NESTED_RUN + OUTPUT_REJECTED, OUTPUT_REJECTED);
    }

    @Test
    @DisplayName("a nested rejection kept from one run and thrown in a later one is logged again by that run")
    void keptRejectionThrownInALaterRun() {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        AtomicReference<IllegalArgumentException> kept = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            if (kept.get() != null) {
                throw kept.get();
            }
            try {
                runWithOutputFact(nested);
            } catch (IllegalArgumentException e) {
                kept.set(e);
            }
        });

        Outcome first = returned(() -> engine.run(new FactMap<>()));
        Outcome second = failed(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(OUTPUT_REJECTED), first.errors(), first.logs());
        assertFailed(second, OUTER_ACTION + OUTPUT_REJECTED, OUTER_ACTION + OUTPUT_REJECTED);
    }

    // Guards: what the outer code still logs

    @Test
    @DisplayName("an action's nested validate() logs nothing")
    void nestedValidateLogsNothing() {
        RulesEngine<Map<String, Object>> other = unloaded();
        AtomicReference<List<RuleCompilationException>> problems = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> problems.set(
                other.validate(List.of(rule("dup"), rule("dup")))));

        Outcome outcome = returned(() -> engine.run(new FactMap<>()));

        assertEquals(List.of(), outcome.errors(), outcome.logs());
        assertEquals(List.of(DUPLICATE), problems.get().stream().map(Throwable::getMessage).toList());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"run() before load()", "a builder that fails", "load() on a closed engine"})
    @DisplayName("a nested call that fails before it logs anything is logged once, by the rule around it")
    void nestedMisuseLoggedByTheRule(String how) {
        RulesEngine<Map<String, Object>> other = unloaded();
        Runnable action = switch (how) {
            case "run() before load()" -> () -> other.run(new FactMap<>());
            case "a builder that fails" -> () -> builder(new StubExpressionLanguage()).copiesAtLoad(-1).build();
            default -> () -> {
                other.close();
                other.load(List.of());
            };
        };
        String failure = switch (how) {
            case "run() before load()" -> "load() must be called before run()";
            case "a builder that fails" -> "copiesAtLoad must not be negative, but was -1";
            default -> "The engine is closed";
        };
        RulesEngine<Map<String, Object>> engine = outer(action);

        assertFailed(failed(() -> engine.run(new FactMap<>())), OUTER_ACTION + failure, OUTER_ACTION + failure);
    }

    @Test
    @DisplayName("a top-level run() that rejects its facts, and a top-level load() that fails, log it once, as before")
    void topLevelUnchanged() {
        Outcome run = failed(() -> runWithOutputFact(plain("inner-rule")));
        Outcome load = failed(() -> loadDuplicates(unloaded()));

        assertInstanceOf(IllegalArgumentException.class, assertFailed(run, OUTPUT_REJECTED, OUTPUT_REJECTED));
        assertInstanceOf(RuleCompilationException.class, assertFailed(load, DUPLICATE, DUPLICATE));
    }
}
