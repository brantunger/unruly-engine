package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("compile failures carry each rule's failure, the expression kind and the language's issues")
class CompileFailureDetailsTest {

    private final RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);

    /** A language that records what it compiles, and warns about expressions that start with {@code warn}. */
    private static final class RecordingLanguage implements ExpressionLanguage {
        final List<Expression> compiled = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    record(context, source);
                    return (evaluation, session) -> true;
                }

                @SuppressWarnings("unchecked")
                @Override
                public CompiledAction compileAction(Expression source) {
                    record(context, source);
                    return (action, session) -> {
                        ((Map<String, Object>) action.output()).put("ran", source.text());
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }

        private void record(CompileContext context, Expression source) {
            compiled.add(source);
            switch (source.text()) {
                case "warn at 2:5" -> context.warn(source, new Issue(Severity.WARNING, 2, 5, "deprecated"));
                case "warn at 3" -> context.warn(source, new Issue(Severity.WARNING, 3, 0, "odd spacing"));
                case "warn" -> context.warn(source, new Issue(Severity.ERROR, 0, 0, "reported as a warning"));
                default -> {
                    // No warning.
                }
            }
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    @Test
    @DisplayName("failures() has each broken rule's failure, with its kind and issues; the exception takes the first's")
    void failuresOfEveryRule() {
        List<Rule> rules = List.of(
                rule("r1", "x >= ", "output.put('k', 1)"),
                rule("r2", "true", "output.put('k', "));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        List<RuleCompilationException> failures = ex.failures();
        assertEquals(2, failures.size());
        assertEquals(List.of("r1", "r2"), failures.stream().map(RuleCompilationException::getRuleName).toList());
        assertEquals(List.of(ExpressionKind.CONDITION, ExpressionKind.ACTION),
                failures.stream().map(RuleCompilationException::getExpressionKind).toList());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 6, "Malformed expression")), failures.get(0).issues());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 11, "unbalanced braces ( ... )")), failures.get(1).issues());
        assertSame(failures.get(0), ex.getCause());
        assertEquals(ExpressionKind.CONDITION, ex.getExpressionKind());
        assertEquals(failures.get(0).issues(), ex.issues());
        assertInstanceOf(InvalidExpressionException.class, failures.get(0).getCause());
    }

    @Test
    @DisplayName("one broken rule is thrown as its own failure, whose failures() is itself")
    void oneBrokenRule() {
        List<Rule> rules = List.of(rule("r", "x >= ", "output.put('k', 1)"), rule("ok", "true", "output.put('k', 1)"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertEquals(List.of(ex), ex.failures());
        assertSame(ex, ex.failures().get(0));
    }

    @Test
    @DisplayName("blank expressions name their kind, and a rule in an unknown language, which has none, is reported too")
    void blankAndUnknownLanguageRulesCollected() {
        List<Rule> rules = List.of(
                rule("blank condition", " ", "output.put('k', 1)"),
                rule("blank action", "true", ""),
                Rule.builder().ruleName("unknown").language("nope").condition("c").action("a").build());

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertTrue(ex.getMessage().startsWith("3 rules failed to compile: Rule 'blank condition' has a null or blank "
                + "condition expression; Rule 'blank action' has a null or blank action expression; Rule 'unknown' is "
                + "written in 'nope'"), ex.getMessage());
        assertEquals(java.util.Arrays.asList(ExpressionKind.CONDITION, ExpressionKind.ACTION, null),
                ex.failures().stream().map(RuleCompilationException::getExpressionKind).toList());
    }

    @Test
    @DisplayName("a language that can't create its compiler fails the rule list at once, however many rules failed before")
    void languageFailureThrownAtOnce() {
        engine.registerLanguage(new ExpressionLanguage() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throw new IllegalStateException("no interpreter");
            }
        });
        List<Rule> rules = List.of(
                rule("r1", "x >= ", "output.put('k', 1)"),
                Rule.builder().ruleName("b").language("broken").condition("c").action("a").build());

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertEquals("The 'broken' expression language failed to create a compiler: no interpreter", ex.getMessage());
        assertNull(ex.getExpressionKind());
        assertEquals(List.of(ex), ex.failures());
    }

    @Test
    @DisplayName("a language gets each expression with its rule's name and kind")
    void languageGetsExpressions() {
        RecordingLanguage language = new RecordingLanguage();
        engine.registerLanguage(language);

        engine.setRuleList(List.of(
                Rule.builder().ruleName("named").language("recording").condition("c1").action("a1").build(),
                Rule.builder().language("recording").condition("c2").action("a2").build()));

        assertEquals(List.of(
                new Expression("named", ExpressionKind.CONDITION, "c1"),
                new Expression("named", ExpressionKind.ACTION, "a1"),
                new Expression(null, ExpressionKind.CONDITION, "c2"),
                new Expression(null, ExpressionKind.ACTION, "a2")), language.compiled);
    }

    @Test
    @DisplayName("a warning is logged at WARN with the rule, the expression and its position, and loading carries on")
    void warningsLogged() {
        engine.registerLanguage(new RecordingLanguage());
        List<Rule> rules = List.of(
                Rule.builder().ruleName("w").language("recording").condition("warn at 2:5").action("warn at 3").build(),
                Rule.builder().language("recording").condition("true").action("warn").build());

        String logs = logsOf(() -> engine.setRuleList(rules));

        assertTrue(logs.contains("WARN io.github.brantunger.unruly.engine - Condition for rule 'w' has a warning at "
                + "line 2, column 5: deprecated"), logs);
        assertTrue(logs.contains("WARN io.github.brantunger.unruly.engine - Action for rule 'w' has a warning at line 3: "
                + "odd spacing"), logs);
        assertTrue(logs.contains("WARN io.github.brantunger.unruly.engine - Action for rule '(unnamed)' has a warning: "
                + "reported as a warning"), logs);
        FactStore<Object> facts = new FactMap<>();
        assertEquals(Map.of("ran", "warn"), engine.run(facts));
    }

    @Test
    @DisplayName("a compile context rejects a null expression or issue")
    void warnRejectsNulls() {
        EngineCompileContext context = new EngineCompileContext(java.util.Set.of(), java.util.Set.of(),
                getClass().getClassLoader());
        Expression source = new Expression("r", ExpressionKind.CONDITION, "c");
        Issue issue = new Issue(Severity.WARNING, 0, 0, "w");

        assertThrows(NullPointerException.class, () -> context.warn(null, issue));
        assertThrows(NullPointerException.class, () -> context.warn(source, null));
    }

    @Test
    @DisplayName("a run-time failure says whether the condition or the action failed")
    void runFailuresNameTheirKind() {
        List<Rule> conditionFails = List.of(rule("c", "x.missing", "output.put('k', 1)"));
        engine.setRuleList(conditionFails);
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        assertEquals(ExpressionKind.CONDITION,
                assertThrows(RuleExecutionException.class, () -> engine.run(facts)).getExpressionKind());

        engine.setRuleList(List.of(rule("n", "null", "output.put('k', 1)")));
        assertEquals(ExpressionKind.CONDITION,
                assertThrows(RuleExecutionException.class, () -> engine.run(facts)).getExpressionKind());

        engine.setRuleList(List.of(rule("s", "'yes'", "output.put('k', 1)")));
        assertEquals(ExpressionKind.CONDITION,
                assertThrows(RuleExecutionException.class, () -> engine.run(facts)).getExpressionKind());

        engine.setRuleList(List.of(rule("a", "true", "x.missing()")));
        assertEquals(ExpressionKind.ACTION,
                assertThrows(RuleExecutionException.class, () -> engine.run(facts)).getExpressionKind());

        RulesEngine<Map<String, Object>> nullOutput = RulesEngineBuilder.stateful(() -> null);
        nullOutput.setRuleList(List.of(rule("o", "true", "output.put('k', 1)")));
        assertNull(assertThrows(RuleExecutionException.class, () -> nullOutput.run(facts)).getExpressionKind());
    }
}
