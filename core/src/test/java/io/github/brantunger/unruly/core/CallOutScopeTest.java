package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A failure a nested {@code run()} logged is a nested run's only to the code that started that run: each time a run
 * hands control to code it doesn't own, a rule's condition or action, a listener callback, the output supplier or
 * writer, a fact store or a fact's {@code getValue()} as the facts are read, or a language, is a call-out of its own;
 * a fact's getter a condition reads through {@code FactProperties} runs within the condition's call-out. A failure
 * one call-out kept from a run it started, and a later call-out of the same run throws, reads as logged already, as
 * one an earlier sibling run logged does, not as the later call-out's nested run's; a rule's condition and its action
 * are two call-outs. Each failure is still logged
 * once, and the code that started the nested run still names its failure as a nested run's.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#961: a failure is a nested run's only to the call-out that started that run")
class CallOutScopeTest {

    private static final String INNER_FAILURE = "Failed to execute action for rule 'inner-rule': inner rule failed";
    private static final String INNER_FATAL = "Failed to execute action for rule 'inner-rule': inner oom";
    private static final String OUTPUT_REJECTED = "'output' is reserved for the output object and cannot be used as "
            + "a fact name";
    private static final String OOM_TEXT = "java.lang.OutOfMemoryError: inner oom";
    private static final String SECOND = "Failed to execute action for rule 'second': ";
    private static final String OWN_WORDS = "own words";

    private final OutOfMemoryError oom = new OutOfMemoryError("inner oom");
    private final AtomicReference<Throwable> kept = new AtomicReference<>();

    private static Rule rule(String name) {
        return Rule.builder().ruleName(name).condition("c").action("a").build();
    }

    private static CompiledAction doing(Runnable what) {
        return (action, session) -> {
            what.run();
            return ActionResult.done();
        };
    }

    private static RulesEngine<Map<String, Object>> loaded(RulesEngineBuilder<Map<String, Object>> builder,
                                                           String... ruleNames) {
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(Arrays.stream(ruleNames).map(CallOutScopeTest::rule).toList());
        return engine;
    }

    private static RulesEngine<Map<String, Object>> inner(Runnable action) {
        return loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action(doing(action))), "inner-rule");
    }

    private static Runnable failingRun() {
        RulesEngine<Map<String, Object>> engine = inner(() -> {
            throw new IllegalStateException("inner rule failed");
        });
        return () -> engine.run(new FactMap<>());
    }

    private static Runnable rejectingRun() {
        RulesEngine<Map<String, Object>> engine = inner(() -> {
        });
        return () -> {
            FactMap<Object> facts = new FactMap<>();
            facts.setValue("output", 1);
            engine.run(facts);
        };
    }

    private Runnable fatalRun() {
        RulesEngine<Map<String, Object>> engine = inner(() -> {
            throw oom;
        });
        return () -> engine.run(new FactMap<>());
    }

    private static Runnable okRun() {
        RulesEngine<Map<String, Object>> engine = inner(() -> {
        });
        return () -> engine.run(new FactMap<>());
    }

    /** Runs {@code nested}, keeping what it throws. */
    private Runnable keeping(Runnable nested) {
        return () -> {
            try {
                nested.run();
            } catch (Throwable t) {
                kept.set(t);
            }
        };
    }

    /** Throws what was kept: as is, with no words of its own, or with words of its own. */
    private Runnable throwingKept(String how) {
        return () -> sneaky(wrapped(how, kept.get()));
    }

    private static Throwable wrapped(String how, Throwable kept) {
        if (how.endsWith("as is")) {
            return kept;
        }
        return how.endsWith("with no words") ? new RuntimeException(kept) : new IllegalStateException(OWN_WORDS, kept);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /** What a failure logged already, thrown on {@code how}, reads as in the failure of the code that threw it. */
    private static String loggedAlready(String how, String logged) {
        return how.endsWith("with words of its own") ? OWN_WORDS + " (caused by " + logged + ", already logged)"
                : logged + " (already logged)";
    }

    /** An all-matches engine whose rules 'first' and 'second' run the actions given, conditions before actions. */
    private static RulesEngine<Map<String, Object>> twoRules(Runnable first, Runnable second,
                                                             RuleListener... listeners) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new StubExpressionLanguage().compileAction(expression -> doing(
                        "first".equals(expression.ruleName()) ? first : second)));
        for (RuleListener listener : listeners) {
            builder.listener(listener);
        }
        return loaded(builder, "first", "second");
    }

    /** A language whose every rule has {@code condition} and {@code action}. */
    private static ExpressionLanguage language(CompiledCondition condition, CompiledAction action) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return StubExpressionLanguage.LANGUAGE_NAME;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return condition;
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return action;
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    /** A condition that runs {@code what} and is true. */
    private static CompiledCondition trueAfter(Runnable what) {
        return (evaluation, session) -> {
            what.run();
            return true;
        };
    }

    // One rule keeps what a nested run threw, and a later rule throws it

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rejected facts, with no words", "rejected facts, with words of its own",
        "a failure, as is", "a failure, with no words", "a failure, with words of its own"})
    @DisplayName("#961: a failure an earlier rule's nested run logged, thrown by a later rule, reads as logged already")
    void keptByAnEarlierRuleThrownByALaterOne(String how) {
        boolean rejected = how.startsWith("rejected facts");
        RulesEngine<Map<String, Object>> engine = twoRules(keeping(rejected ? rejectingRun() : failingRun()),
                throwingKept(how));

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        String logged = rejected ? OUTPUT_REJECTED : INNER_FAILURE;
        String failure = SECOND + loggedAlready(how, logged);
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        List<String> lines = how.endsWith("with words of its own") ? List.of(logged, failure) : List.of(logged);
        assertEquals(lines, outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a fatal Error an earlier rule's nested run logged, wrapped by a later rule, is noted as logged"
            + " already")
    void fatalKeptByAnEarlierRuleWrappedByALaterOne() {
        RulesEngine<Map<String, Object>> engine = twoRules(keeping(fatalRun()), throwingKept("with words of its own"));

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, SECOND + OWN_WORDS + " (caused by " + OOM_TEXT + ", already logged)"),
                outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a later rule that runs a nested run of its own first, and then throws a failure an earlier"
            + " rule kept, has it read as logged already")
    void laterRuleRunsANestedRunOfItsOwnFirst() {
        Runnable ok = okRun();
        Runnable throwing = throwingKept("as is");
        RulesEngine<Map<String, Object>> engine = twoRules(keeping(failingRun()), () -> {
            ok.run();
            throwing.run();
        });

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertEquals(SECOND + INNER_FAILURE + " (already logged)", outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    /**
     * The rule that started the nested run still names its failure as a nested run's: a guard for #961's fix, which
     * tells it from a later call-out.
     *
     * @param kind What the nested run did, and how the rule throws it on
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rejected facts, as is", "a failure, with words of its own",
        "a fatal Error, with words of its own"})
    @DisplayName("#961 guard: the rule that started a nested run still names its failure as a nested run's")
    void starterStillNamesItsNestedRun(String kind) {
        Runnable nested = kind.startsWith("rejected") ? rejectingRun()
                : kind.startsWith("a failure") ? failingRun() : fatalRun();
        Runnable throwing = throwingKept(kind);
        RulesEngine<Map<String, Object>> engine = twoRules(() -> {
            keeping(nested).run();
            throwing.run();
        }, () -> {
        });

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        String first = "Failed to execute action for rule 'first': ";
        if (kind.startsWith("rejected")) {
            assertEquals(first + "a nested run() failed: " + OUTPUT_REJECTED, outcome.thrown().getMessage());
            assertEquals(List.of(OUTPUT_REJECTED), outcome.lines("ERROR"), outcome.logs());
        } else if (kind.startsWith("a failure")) {
            String failure = first + OWN_WORDS + " (after a nested run() failed: " + INNER_FAILURE + ")";
            assertEquals(failure, outcome.thrown().getMessage());
            assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
        } else {
            assertSame(oom, outcome.thrown());
            assertEquals(List.of(INNER_FATAL, first + OWN_WORDS + " (after a nested run() failed: " + OOM_TEXT + ")"),
                    outcome.lines("ERROR"), outcome.logs());
        }
    }

    // A rule's condition and its action are two call-outs

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"as is", "with words of its own"})
    @DisplayName("#961: a failure a rule's condition's nested run logged, thrown by the same rule's action, reads as"
            + " logged already")
    void keptByAConditionThrownByTheSameRulesAction(String how) {
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(language(trueAfter(keeping(failingRun())), doing(throwingKept(how)))), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        String failure = "Failed to execute action for rule 'r': " + loggedAlready(how, INNER_FAILURE);
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        assertEquals("as is".equals(how) ? List.of(INNER_FAILURE) : List.of(INNER_FAILURE, failure),
                outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961 guard: a condition that throws its own nested run's failure names it as a nested run's")
    void conditionThrowsItsOwnNestedRunsFailure() {
        Runnable nested = failingRun();
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(language(trueAfter(nested), doing(() -> {
                }))), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertEquals("Failed to evaluate condition for rule 'r': a nested run() failed: " + INNER_FAILURE,
                outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    // Listeners, the output supplier and a language are call-outs too; a getter a condition reads is in its call-out

    @Test
    @DisplayName("#961: a failure a listener's nested run logged, wrapped by a rule, is noted as logged already")
    void keptByAListenerWrappedByARule() {
        Runnable keep = keeping(failingRun());
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new StubExpressionLanguage().action(doing(
                        throwingKept("with words of its own")))).listener(new RuleListener() {
                            @Override
                            public void beforeExecute(Rule rule, Object output) {
                                keep.run();
                            }
                        }), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        String failure = "Failed to execute action for rule 'r': " + OWN_WORDS + " (caused by " + INNER_FAILURE
                + ", already logged)";
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure a rule's nested run logged, wrapped by a listener, is noted as logged already")
    void keptByARuleWrappedByAListener() {
        Runnable throwing = throwingKept("with words of its own");
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new StubExpressionLanguage().action(doing(keeping(failingRun()))))
                .listener(new RuleListener() {
                    @Override
                    public void afterExecute(Rule rule, Object output) {
                        throwing.run();
                    }
                }), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertNull(outcome.thrown(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of("Listener threw exception in afterExecute: " + OWN_WORDS + " (caused by " + INNER_FAILURE
                + ", already logged)"), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure a condition's nested run logged, wrapped by the output supplier, is noted as logged"
            + " already")
    void keptByAConditionWrappedByTheOutputSupplier() {
        Runnable throwing = throwingKept("with words of its own");
        Supplier<Map<String, Object>> output = () -> {
            throwing.run();
            return new HashMap<>();
        };
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.firstMatch(output)
                .language(language(trueAfter(keeping(failingRun())), doing(() -> {
                }))), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        String failure = "Output factory threw: " + OWN_WORDS + " (caused by " + INNER_FAILURE + ", already logged)";
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a fatal Error a listener's nested run logged, thrown again by a fact's getter a condition"
            + " reads, is named with that read")
    void fatalKeptByAListenerThrownAgainByAGetter() {
        Runnable keep = keeping(fatalRun());
        FactPropertiesFailureLogTest.Item item = new FactPropertiesFailureLogTest.Item(() -> sneaky(kept.get()));
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                        keep.run();
                    }
                }).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("item.price == 5").action("let x = 1").build()));

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>(new Fact<>("item", item))));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, "Failed to evaluate condition for rule 'r': Reading 'price' on a "
                + FactPropertiesFailureLogTest.Item.class.getName() + " failed: inner oom (caused by " + OOM_TEXT
                + ", already logged)"), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure a listener's nested run logged, wrapped by a language checking a fact name, is"
            + " noted as logged already")
    void keptByAListenerWrappedByALanguage() {
        Runnable keep = keeping(failingRun());
        Runnable throwing = throwingKept("with words of its own");
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new StubExpressionLanguage().checkFactName(name -> throwing.run()))
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        keep.run();
                    }
                }), "r");
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        Outcome<Throwable> outcome = capture(() -> engine.run(facts));

        String failure = "The 'stub' expression language failed to check fact name 'x': " + OWN_WORDS + " (caused by "
                + INNER_FAILURE + ", already logged)";
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    /**
     * Each listener's callback is a call-out of its own, and the engine tells what the first listener that threw a
     * fatal {@link Error} wrapped it in only once every listener has had the callback: still as that listener's nested
     * run's. A guard for #961's fix, which marks a call-out for every listener.
     */
    @Test
    @DisplayName("#961 guard: a listener that wraps its own nested run's fatal Error, called before another listener,"
            + " still names it as a nested run's")
    void listenerWrapsItsOwnNestedFatalBeforeAnotherListener() {
        Runnable nested = fatalRun();
        Runnable ok = okRun();
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new StubExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeExecute(Rule rule, Object output) {
                        try {
                            nested.run();
                        } catch (OutOfMemoryError e) {
                            throw new IllegalStateException("audit failed", e);
                        }
                    }
                }).listener(new RuleListener() {
                    @Override
                    public void beforeExecute(Rule rule, Object output) {
                        ok.run();
                    }
                }), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of(INNER_FATAL, "A listener threw java.lang.OutOfMemoryError in beforeExecute for rule 'r':"
                + " audit failed (after a nested run() failed: " + OOM_TEXT + ")"), outcome.lines("ERROR"),
                outcome.logs());
    }

    // Every other call-out is a scope of its own too: what the one before it kept, it throws as logged already

    /** A language each of whose calls runs a hook first, for a test that keeps a failure in one and throws it later. */
    private static final class HookedLanguage implements ExpressionLanguage {
        private final String name;
        private Runnable newCompiler = () -> {
        };
        private Runnable compileCondition = () -> {
        };
        private Runnable compileAction = () -> {
        };
        private Runnable checkFactName = () -> {
        };
        private Runnable newSession;
        private Runnable warmUp = () -> {
        };
        private Runnable closeSession = () -> {
        };
        private Runnable closeCompiler = () -> {
        };
        private CompiledAction action = doing(() -> {
        });

        HookedLanguage(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            newCompiler.run();
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    compileCondition.run();
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    compileAction.run();
                    return action;
                }

                @Override
                public Session newSession() {
                    if (newSession == null) {
                        return Session.none();
                    }
                    newSession.run();
                    return new Session() {
                        @Override
                        public void close() {
                            closeSession.run();
                        }
                    };
                }

                @Override
                public void warmUp(Session session) {
                    warmUp.run();
                }

                @Override
                public void checkFactName(String fact) {
                    checkFactName.run();
                }

                @Override
                public void close() {
                    closeCompiler.run();
                }
            };
        }
    }

    private static RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage... languages) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.firstMatch(HashMap::new);
        for (ExpressionLanguage language : languages) {
            builder.language(language);
        }
        return builder;
    }

    private static String wrappedLoggedAlready(String what) {
        return what + OWN_WORDS + " (caused by " + INNER_FAILURE + ", already logged)";
    }

    @Test
    @DisplayName("#961: a failure an action's nested run logged, wrapped by the output writer setting what the action"
            + " returned, is noted as logged already")
    void keptByAnActionWrappedByTheOutputWriter() {
        Runnable keep = keeping(failingRun());
        Runnable throwing = throwingKept("with words of its own");
        HookedLanguage language = new HookedLanguage("hooked");
        language.action = (context, session) -> {
            keep.run();
            return ActionResult.set(Map.of("total", 1));
        };
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).outputWriter((output, property, value)
                -> throwing.run()), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        String failure = wrappedLoggedAlready("Failed to set 'total' on the output for rule 'r': ");
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure a fact's getValue()'s nested run logged, wrapped by a language creating a session,"
            + " is noted as logged already")
    void keptByAFactWrappedByANewSession() {
        Runnable keep = keeping(failingRun());
        HookedLanguage language = new HookedLanguage("hooked");
        language.newSession = throwingKept("with words of its own");
        RulesEngine<Map<String, Object>> engine = loaded(builder(language), "r");
        FactMap<Object> facts = new FactMap<>();
        facts.put(new FactReference<>() {
            @Override
            public String getName() {
                return "x";
            }

            @Override
            public Object getValue() {
                keep.run();
                return 1;
            }
        });

        Outcome<Throwable> outcome = capture(() -> engine.run(facts));

        String failure = wrappedLoggedAlready("The 'hooked' expression language failed to create a session: ");
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure a new session's nested run logged, wrapped by the language warming it up, is noted"
            + " as logged already")
    void keptByANewSessionWrappedByWarmUp() {
        HookedLanguage language = new HookedLanguage("hooked");
        language.newSession = keeping(failingRun());
        language.warmUp = throwingKept("with words of its own");

        Outcome<Throwable> outcome = capture(() -> loaded(builder(language).copiesAtLoad(1), "r"));

        String failure = wrappedLoggedAlready("The 'hooked' expression language failed to warm up a session: ");
        assertEquals(failure, outcome.thrown().getMessage(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure one language's compiling logged through a nested run, wrapped by the next language"
            + " creating its compiler, is noted as logged already")
    void keptByCompilingWrappedByTheNextNewCompiler() {
        HookedLanguage first = new HookedLanguage("first");
        first.compileAction = keeping(failingRun());
        HookedLanguage second = new HookedLanguage("second");
        second.newCompiler = throwingKept("with words of its own");
        RulesEngine<Map<String, Object>> engine = builder(first, second).defaultLanguage("first").build();
        List<Rule> rules = List.of(rule("a"), Rule.builder().ruleName("b").condition("c").action("a")
                .language("second").build());

        Outcome<Throwable> outcome = capture(() -> engine.load(rules));

        String failure = wrappedLoggedAlready("The 'second' expression language failed to create a compiler: ");
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure a language's new compiler logged through a nested run, wrapped by compiling a"
            + " condition, is noted as logged already")
    void keptByANewCompilerWrappedByCompiling() {
        HookedLanguage language = new HookedLanguage("hooked");
        language.newCompiler = keeping(failingRun());
        language.compileCondition = throwingKept("with words of its own");

        Outcome<Throwable> outcome = capture(() -> loaded(builder(language), "r"));

        String failure = wrappedLoggedAlready("Condition for rule 'r' failed to compile: ");
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure compiling logged through a nested run, wrapped by the language checking a declared"
            + " fact's name, is noted as logged already")
    void keptByCompilingWrappedByADeclaredNameCheck() {
        HookedLanguage language = new HookedLanguage("hooked");
        language.compileAction = keeping(failingRun());
        language.checkFactName = throwingKept("with words of its own");

        Outcome<Throwable> outcome = capture(() -> loaded(builder(language).fact("x", Integer.class), "r"));

        String failure = wrappedLoggedAlready("Declared fact 'x' can't be used: ");
        assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure an action logged through a nested run, wrapped by a value the run kept as it's"
            + " closed, is noted as logged already")
    void keptByAnActionWrappedByClosingARunValue() {
        Runnable keep = keeping(failingRun());
        Runnable throwing = throwingKept("with words of its own");
        HookedLanguage language = new HookedLanguage("hooked");
        language.action = (context, session) -> {
            keep.run();
            context.runScopedClosing("value", () -> throwing::run);
            return ActionResult.done();
        };
        RulesEngine<Map<String, Object>> engine = loaded(builder(language), "r");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertNull(outcome.thrown(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(wrappedLoggedAlready("A value a language kept for the run failed to close: ")),
                outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("#961: a failure a session's close() logged through a nested run, wrapped by closing the compiler"
            + " after it, is noted as logged already")
    void keptByClosingASessionWrappedByClosingTheCompiler() {
        HookedLanguage language = new HookedLanguage("hooked");
        language.newSession = () -> {
        };
        language.closeSession = keeping(failingRun());
        language.closeCompiler = throwingKept("with words of its own");
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).copiesAtLoad(1), "r");

        Outcome<Throwable> outcome = capture(engine::close);

        assertNull(outcome.thrown(), outcome.logs());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
        assertEquals(List.of(wrappedLoggedAlready("The 'hooked' expression language failed to close its compiler: ")),
                outcome.lines("WARN"), outcome.logs());
    }

    // A language's prepare() at its first use is a call-out of its own too (#945)

    /**
     * A language a services file lists, named {@code found}, whose {@code prepare()} and {@code newCompiler()} run the
     * hooks set for its class first. The engine prepares a language of a class once, so each test has a class of its
     * own; found with {@link java.util.ServiceLoader} and not named, it's prepared at its first use, in a load.
     */
    public abstract static class Found implements ExpressionLanguage {
        static final Map<Class<?>, Runnable> PREPARE = new ConcurrentHashMap<>();
        static final Map<Class<?>, Runnable> NEW_COMPILER = new ConcurrentHashMap<>();

        @Override
        public String name() {
            return "found";
        }

        @Override
        public void prepare() {
            PREPARE.getOrDefault(getClass(), () -> {
            }).run();
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            NEW_COMPILER.getOrDefault(getClass(), () -> {
            }).run();
            return new StubExpressionLanguage().newCompiler(context);
        }
    }

    /** Keeps a nested run's failure in prepare(), and throws it from newCompiler(). */
    public static final class FoundKeepingInPrepare extends Found {
    }

    /** Throws from prepare() what an earlier language's compiling kept. */
    public static final class FoundThrowingKeptInPrepare extends Found {
    }

    /** Throws from prepare() what its own nested run threw. */
    public static final class FoundThrowingOwnInPrepare extends Found {
    }

    /** A language a services file lists, named {@code first}, whose compiling of an action runs {@link #ACTION}. */
    public static final class FoundFirst implements ExpressionLanguage {
        static final AtomicReference<Runnable> ACTION = new AtomicReference<>(() -> {
        });

        @Override
        public String name() {
            return "first";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new StubExpressionLanguage().compileAction(expression -> {
                ACTION.get().run();
                return doing(() -> {
                });
            }).newCompiler(context);
        }
    }

    @TempDir
    Path servicesRoot;

    /** A class loader that sees the tests, and a services file listing {@code languages}. */
    private URLClassLoader listing(Class<?>... languages) {
        try {
            Path file = servicesRoot.resolve("META-INF/services/" + ExpressionLanguage.class.getName());
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", Arrays.stream(languages).map(Class::getName).toList()));
            return new URLClassLoader(new URL[]{servicesRoot.toUri().toURL()}, CallOutScopeTest.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Builds, with {@code loader} as the context class loader, an engine that finds its languages with it. */
    private static RulesEngine<Map<String, Object>> found(URLClassLoader loader, String defaultLanguage) {
        return withContextClassLoader(loader, () -> {
            RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.firstMatch(HashMap::new);
            return (defaultLanguage == null ? builder : builder.defaultLanguage(defaultLanguage)).build();
        });
    }

    @Test
    @DisplayName("#945: a failure a language's prepare() logged through a nested run, at its first use, wrapped by the"
            + " language creating its compiler, is noted as logged already")
    void keptByPrepareWrappedByNewCompiler() throws IOException {
        Found.PREPARE.put(FoundKeepingInPrepare.class, keeping(failingRun()));
        Found.NEW_COMPILER.put(FoundKeepingInPrepare.class, throwingKept("with words of its own"));
        try (URLClassLoader loader = listing(FoundKeepingInPrepare.class)) {
            RulesEngine<Map<String, Object>> engine = found(loader, null);

            Outcome<Throwable> outcome = capture(() -> engine.load(List.of(rule("r"))));

            String failure = wrappedLoggedAlready("The 'found' expression language failed to create a compiler: ");
            assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
        }
    }

    @Test
    @DisplayName("#945: a failure an earlier language's compiling logged through a nested run, wrapped by the next"
            + " language's prepare() at its first use, is noted as logged already")
    void keptByCompilingWrappedByTheNextPrepare() throws IOException {
        FoundFirst.ACTION.set(keeping(failingRun()));
        Found.PREPARE.put(FoundThrowingKeptInPrepare.class, throwingKept("with words of its own"));
        try (URLClassLoader loader = listing(FoundFirst.class, FoundThrowingKeptInPrepare.class)) {
            RulesEngine<Map<String, Object>> engine = found(loader, "first");
            List<Rule> rules = List.of(rule("a"), Rule.builder().ruleName("b").condition("c").action("a")
                    .language("found").build());

            Outcome<Throwable> outcome = capture(() -> engine.load(rules));

            String failure = wrappedLoggedAlready("The 'found' expression language failed to prepare: ");
            assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
        } finally {
            FoundFirst.ACTION.set(() -> {
            });
        }
    }

    @Test
    @DisplayName("#945 guard: a language's prepare() that throws its own nested run's failure names it as a nested"
            + " run's")
    void prepareThrowsItsOwnNestedRunsFailure() throws IOException {
        Runnable nested = keeping(failingRun());
        Runnable throwing = throwingKept("with words of its own");
        Found.PREPARE.put(FoundThrowingOwnInPrepare.class, () -> {
            nested.run();
            throwing.run();
        });
        try (URLClassLoader loader = listing(FoundThrowingOwnInPrepare.class)) {
            RulesEngine<Map<String, Object>> engine = found(loader, null);

            Outcome<Throwable> outcome = capture(() -> engine.load(List.of(rule("r"))));

            String failure = "The 'found' expression language failed to prepare: " + OWN_WORDS
                    + " (after a nested run() failed: " + INNER_FAILURE + ")";
            assertEquals(List.of(INNER_FAILURE, failure), outcome.lines("ERROR"), outcome.logs());
        }
    }
}
