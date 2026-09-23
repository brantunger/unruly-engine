package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A missing or unreadable class means one rule is misconfigured, not that the JVM is failing, so the engine reports it
 * like any other failure instead of letting it escape. A {@link VirtualMachineError} still escapes unchanged.
 */
@DisplayName("a LinkageError from a rule, a language or a listener is reported with the rule's name")
class LinkageErrorTest {

    private static final String LANGUAGE = "failing";

    private static Stream<Error> linkageErrors() {
        return Stream.of(new NoClassDefFoundError("com/acme/Missing"),
                new IllegalAccessError("cannot access Applicant"), new IncompatibleClassChangeError("changed"),
                new ExceptionInInitializerError("static init"));
    }

    private static Rule rule(String name, String action) {
        return Rule.builder().ruleName(name).condition("true").action(action).build();
    }

    private static FactStore<Object> bomb(Error error) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("bomb", new WrappedFatalErrorTest.Bomb(error));
        return facts;
    }

    private static RulesEngine<Map<String, Object>> mvelEngine(RuleListener... listeners) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.firstMatch(HashMap::new);
        for (RuleListener listener : listeners) {
            builder.listener(listener);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(rule("r", "bomb.explode()")));
        return engine;
    }

    /** A language that fails, with the error given, wherever the engine calls it outside compiling. */
    private record Failing(Error checkingFactName, Error creatingSession, Error closingSession)
            implements ExpressionLanguage {

        @Override
        public String name() {
            return LANGUAGE;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    if (creatingSession != null) {
                        throw creatingSession;
                    }
                    return new Session() {
                        @Override
                        public void close() {
                            if (closingSession != null) {
                                throw closingSession;
                            }
                        }
                    };
                }

                @Override
                public void checkFactName(String name) {
                    if (checkingFactName != null) {
                        throw checkingFactName;
                    }
                }
            };
        }
    }

    private static RulesEngine<Map<String, Object>> failingEngine(Failing language) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(language).build();
        engine.load(List.of(Rule.builder().ruleName("r").language(LANGUAGE).condition("true").action("x").build()));
        return engine;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkageErrors")
    @DisplayName("from Java code a rule calls: a RuleExecutionException naming the rule, with the error as its cause")
    void fromARule(Error error) {
        RulesEngine<Map<String, Object>> engine = mvelEngine();

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(bomb(error)));

        assertEquals("r", ex.getRuleName());
        assertTrue(ex.getMessage().startsWith("Failed to execute action for rule 'r'"), ex.getMessage());
        assertTrue(Stream.iterate((Throwable) ex, t -> t != null, Throwable::getCause).anyMatch(t -> t == error),
                "the error should be in the cause chain: " + ex.getMessage());
    }

    @Test
    @DisplayName("a listener's missing class is contained like any listener failure, and the run finishes")
    void fromAListener() {
        RuleListener throwing = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                throw new NoClassDefFoundError("com/acme/Tracer");
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(throwing).build();
        engine.load(List.of(rule("r", "output.put('k', 1)")));

        String logs = logsOf(() -> assertEquals(Map.of("k", 1), engine.run(new FactMap<>())));

        assertTrue(logs.contains("Listener threw exception in beforeEvaluate"), logs);
    }

    @Test
    @DisplayName("from a language's fact-name check: an IllegalArgumentException naming the fact and the language")
    void fromAFactNameCheck() {
        RulesEngine<Map<String, Object>> engine =
                failingEngine(new Failing(new NoClassDefFoundError("com/acme/Names"), null, null));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(facts));

        assertTrue(ex.getMessage().startsWith("The 'failing' expression language failed to check fact name 'x'"),
                ex.getMessage());
    }

    @Test
    @DisplayName("from the output supplier: a RuleExecutionException")
    void fromTheOutputSupplier() {
        NoClassDefFoundError missing = new NoClassDefFoundError("com/acme/Decision");
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(() -> {
            throw missing;
        }).build();
        engine.load(List.of(rule("r", "output.put('k', 1)")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().startsWith("Output factory threw java.lang.NoClassDefFoundError"), ex.getMessage());
        assertSame(missing, ex.getCause());
    }

    @Test
    @DisplayName("from creating a language's session: a RuleExecutionException naming the language")
    void fromCreatingASession() {
        RulesEngine<Map<String, Object>> engine =
                failingEngine(new Failing(null, new NoClassDefFoundError("com/acme/Session"), null));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().startsWith("The 'failing' expression language failed to create a session"),
                ex.getMessage());
    }

    @Test
    @DisplayName("from closing a language's session: logged at WARN, and closing still succeeds")
    void fromClosingASession() {
        RulesEngine<Map<String, Object>> engine =
                failingEngine(new Failing(null, null, new NoClassDefFoundError("com/acme/Close")));
        engine.run(new FactMap<>());

        String logs = logsOf(() -> assertDoesNotThrow(engine::close));

        assertTrue(logs.contains("failed to close a session"), logs);
    }

    @Test
    @DisplayName("an OutOfMemoryError from a rule still escapes unchanged, because the JVM is the problem")
    void virtualMachineErrorStillEscapes() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        List<String> errors = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = mvelEngine(new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                errors.add(error.getMessage());
            }
        });

        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(bomb(oom))));
        assertEquals(1, errors.size(), "listeners are still told before it is rethrown");
    }

    @Test
    @DisplayName("a StackOverflowError is still handled like an exception, not as a failing JVM")
    void stackOverflowIsStillWrapped() {
        StackOverflowError overflow = new StackOverflowError("deep");
        RulesEngine<Map<String, Object>> engine = mvelEngine();

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(bomb(overflow)));

        assertEquals("r", ex.getRuleName());
    }
}
