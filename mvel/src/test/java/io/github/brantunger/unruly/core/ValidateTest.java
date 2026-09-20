package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedThenRethrown;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code validate()} compiles a rule list the way {@code load()} does, returns every problem instead of throwing, loads
 * nothing, logs nothing, and closes the compilers it created (#291).
 */
@DisplayName("validate reports every problem load() would, without loading or logging")
class ValidateTest {

    private static final Rule GOOD = Rule.builder().ruleName("good").priority(10)
            .condition("x == 1").action("output.put('k', 1)").build();
    private static final Rule BROKEN = Rule.builder().ruleName("broken").priority(5)
            .condition("x == == 1").action("output.put('k', 1)").build();

    private static Rule in(String language, String name) {
        return Rule.builder().ruleName(name).language(language).condition("c").action("a").build();
    }

    /** A language that counts the compilers it created and closed, warns on every expression, and compiles all. */
    private static final class CountingLanguage implements ExpressionLanguage {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            created.incrementAndGet();
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    context.warn(source, new InvalidExpressionException.Issue(
                            InvalidExpressionException.Issue.Severity.WARNING, 1, 1, "deprecated"));
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        }
    }

    private static ExpressionLanguage failing(String name, Error error) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throw error;
            }
        };
    }

    private static StatefulRulesEngine<Map<String, Object>> mvelEngine() {
        return TestEngines.allMatches(HashMap::new);
    }

    @Test
    @DisplayName("a list that would load returns no problem, and nothing is loaded")
    void validListLoadsNothing() {
        StatefulRulesEngine<Map<String, Object>> engine = mvelEngine();

        List<RuleCompilationException> problems = engine.validate(List.of(GOOD));

        assertEquals(List.of(), problems);
        assertEquals(List.of(), engine.rules().rules());
        IllegalStateException notLoaded = assertThrows(IllegalStateException.class,
                () -> engine.run(new FactMap<>()));
        assertEquals("load() must be called before run()", notLoaded.getMessage());
    }

    @Test
    @DisplayName("every problem is reported in the order load() finds them: list problems, rules, a language, "
            + "declared names")
    void everyProblemInOrder() {
        AtomicInteger calls = new AtomicInteger();
        ExpressionLanguage noCompiler = new ExpressionLanguage() {
            @Override
            public String name() {
                return "b";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                calls.incrementAndGet();
                throw new IllegalStateException("no engine");
            }
        };
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(new MvelExpressionLanguage()).language(noCompiler)
                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME)
                .fact("empty", String.class));
        List<Rule> rules = new ArrayList<>(List.of(
                Rule.builder().ruleName("dup").priority(9).condition("x == 1").action("a").build(),
                BROKEN,
                Rule.builder().ruleName("dup").priority(8).condition("x == 2").action("a").build(),
                in("b", "b1"), in("b", "b2"),
                Rule.builder().ruleName("unknown").priority(7).language("cel").condition("c").action("a").build(),
                Rule.builder().ruleName("blank").priority(6).condition(" ").action("a").build()));
        rules.add(2, null);

        String logs = logsOf(() -> {
            List<RuleCompilationException> problems = engine.validate(rules);

            // Priorities: dup 9 and 8 compile, unknown 7, blank 6, broken 5, then the b rules with none.
            assertEquals(Arrays.asList(null, "dup", "unknown", "blank", "broken", null, null),
                    problems.stream().map(RuleCompilationException::getRuleName).toList(), problems.toString());
            assertEquals("Rule at index 2 of the rule list is null", problems.get(0).getMessage());
            assertEquals("Duplicate rule name 'dup'", problems.get(1).getMessage());
            assertTrue(problems.get(2).getMessage().contains("isn't one of the engine's expression languages"),
                    problems.get(2).getMessage());
            assertEquals("Rule 'blank' has a blank condition expression", problems.get(3).getMessage());
            assertEquals(ExpressionKind.CONDITION, problems.get(4).getExpressionKind());
            assertEquals(1, problems.get(4).issues().size());
            assertEquals("The 'b' expression language failed to create a compiler: no engine",
                    problems.get(5).getMessage());
            assertTrue(problems.get(6).getMessage().startsWith("Declared fact 'empty' can't be used: "),
                    problems.get(6).getMessage());
        });

        assertEquals("", logs, "validate logs nothing");
        assertEquals(1, calls.get(), "a failed language is asked once");
    }

    @Test
    @DisplayName("the problems are the failures load() throws for the same list")
    void sameAsLoad() {
        StatefulRulesEngine<Map<String, Object>> validating = TestEngines.allMatches(HashMap::new,
                builder -> builder.fact("empty", String.class));
        StatefulRulesEngine<Map<String, Object>> loading = TestEngines.allMatches(HashMap::new,
                builder -> builder.fact("empty", String.class));
        List<Rule> rules = List.of(GOOD, BROKEN,
                Rule.builder().ruleName("also").priority(1).condition("y == == 2").action("a").build());

        List<String> validated = validating.validate(rules).stream().map(Throwable::getMessage).toList();
        RuleCompilationException thrown = assertThrows(RuleCompilationException.class, () -> loading.load(rules));

        assertEquals(thrown.failures().stream().map(Throwable::getMessage).toList(), validated);
        assertEquals(3, validated.size());
    }

    @Test
    @DisplayName("the compilers are closed before validate returns, and warnings aren't logged")
    void closesCompilersAndSwallowsWarnings() {
        CountingLanguage language = new CountingLanguage();
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.language(language));

        String logs = logsOf(() -> assertEquals(List.of(), engine.validate(List.of(in("counting", "r")))));

        assertEquals(1, language.created.get());
        assertEquals(1, language.closed.get());
        assertFalse(logs.contains("has a warning"), logs);
    }

    @Test
    @DisplayName("guard: load() still logs a language's warnings")
    void loadStillLogsWarnings() {
        CountingLanguage language = new CountingLanguage();
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.language(language));

        String logs = logsOf(() -> engine.load(List.of(in("counting", "r"))));

        assertTrue(logs.contains("WARN " + EngineLoggingTest.ENGINE_LOGGER
                + "Condition for rule 'r' has a warning at line 1, column 1: deprecated"), logs);
        assertEquals(0, language.closed.get(), "the loaded rules keep their compiler");
    }

    @Test
    @DisplayName("a fatal error while compiling is logged and rethrown, and the compilers are still closed")
    void fatalErrorRethrownAndCompilersClosed() {
        CountingLanguage counting = new CountingLanguage();
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(counting).language(failing("fatal", oom)).defaultLanguage("counting"));

        assertLoggedThenRethrown(oom, "The 'fatal' expression language failed to create a compiler: simulated",
                () -> engine.validate(List.of(in("counting", "r"), in("fatal", "f"))));

        assertEquals(1, counting.closed.get());
    }

    @Test
    @DisplayName("an empty list with a default language that can't create its compiler returns that one problem")
    void emptyListWithFailingDefault() {
        ExpressionLanguage broken = new ExpressionLanguage() {
            @Override
            public String name() {
                return "d";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throw new IllegalStateException("broken");
            }
        };
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.language(broken));

        List<RuleCompilationException> problems = engine.validate(List.of());

        assertEquals(1, problems.size());
        assertEquals("The 'd' expression language failed to create a compiler: broken", problems.get(0).getMessage());
        assertNull(problems.get(0).getRuleName());
    }

    @Test
    @DisplayName("a closed engine and a null list are rejected as load() rejects them")
    void closedEngineAndNullList() {
        RulesEngine<Map<String, Object>> engine = mvelEngine();

        assertThrows(NullPointerException.class, () -> engine.validate(null));
        engine.close();
        IllegalStateException closed = assertThrows(IllegalStateException.class, () -> engine.validate(List.of()));

        assertEquals("The engine is closed", closed.getMessage());
    }

    @Test
    @DisplayName("the returned list can't be changed")
    void resultIsUnmodifiable() {
        List<RuleCompilationException> problems = mvelEngine().validate(List.of(BROKEN));

        assertInstanceOf(RuleCompilationException.class, problems.get(0));
        assertThrows(UnsupportedOperationException.class, problems::clear);
    }
}
