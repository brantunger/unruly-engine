package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1046, the issue's reproduction: MVEL rules beside a language that stands in for a Lua adapter, which rejects the
 * keyword {@code end} as a fact name and reserves {@code self}; and MVEL, which opts in too.
 */
@DisplayName("a language that opts in has its fact-name rules apply only to the facts its rules can see, beside MVEL"
        + " (#1046)")
class ScopedFactNamesMvelTest {

    /**
     * Stands in for a Lua adapter: 'end' is a keyword, so its rules can't name a fact 'end'; it binds 'self'. Its
     * conditions are always true, its actions do nothing, and they read no fact.
     */
    private static final class Keywordy implements ExpressionLanguage {

        private final boolean everyRuleList;
        private final boolean tellsNamesRead;

        Keywordy(boolean everyRuleList, boolean tellsNamesRead) {
            this.everyRuleList = everyRuleList;
            this.tellsNamesRead = tellsNamesRead;
        }

        @Override
        public String name() {
            return "kw";
        }

        @Override
        public Set<String> reservedFactNames() {
            return Set.of("self");
        }

        @Override
        public boolean reservesForEveryRuleList() {
            return everyRuleList;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }

                @Override
                public void checkFactName(String name) {
                    if (name.equals("end")) {
                        throw new IllegalArgumentException("'end' is a keyword: a kw rule can't refer to it");
                    }
                }

                @Override
                public Set<String> factNamesRead() {
                    return tellsNamesRead ? Set.of() : null;
                }
            };
        }
    }

    private static RulesEngine<Map<String, Object>> engine(Keywordy kw) {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new MvelExpressionLanguage()).language(kw).defaultLanguage("mvel").build();
    }

    private static Object run(RulesEngine<Map<String, Object>> engine, String fact) {
        try {
            return engine.run(new FactMap<>(new Fact<>(fact, 1)));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static final Rule READS_SELF = Rule.builder().ruleName("m").priority(2).condition("self == 1")
            .action("output.put(\"seen\", self)").build();
    private static final Rule READS_END = Rule.builder().ruleName("m").priority(2).condition("end == 1")
            .action("output.put(\"seen\", end)").build();
    private static final Rule KW = Rule.builder().ruleName("k").priority(1).language("kw").condition("true")
            .action("noop").build();

    @Test
    @DisplayName("by default, the issue's output: kw's reserved name and keyword block facts only MVEL rules read")
    void defaultsKeepTodaysBehaviour() {
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(true, false))) {
            engine.load(List.of(READS_SELF));
            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));

            engine.load(List.of(READS_END, KW));
            assertEquals("'end' is a keyword: a kw rule can't refer to it", run(engine, "end"));
        }
    }

    @Test
    @DisplayName("an MVEL-only rule list reads facts named self and end once kw reserves its names only where used")
    void mvelOnlyListWhenKwOptsOut() {
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(false, false))) {
            engine.load(List.of(READS_SELF));
            assertEquals(Map.of("seen", 1), run(engine, "self"));

            engine.load(List.of(READS_END));
            assertEquals(Map.of("seen", 1), run(engine, "end"));
            // A list with a kw rule rejects self again, and end, as kw can't tell what its rules read.
            engine.load(List.of(READS_SELF, KW));
            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
        }
    }

    @Test
    @DisplayName("a mixed rule list reads a fact named end when kw says its rules read no fact")
    void mixedListWhenKwSaysWhatItReads() {
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(true, true))) {
            engine.load(List.of(READS_END, KW));
            assertEquals(Map.of("seen", 1), run(engine, "end"));
            // Its reserved name still applies, to every rule list, as kw reserves it so.
            engine.load(List.of(READS_SELF));
            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
        }
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(false, true))) {
            engine.load(List.of(READS_SELF));
            assertEquals(Map.of("seen", 1), run(engine, "self"));
            engine.load(List.of(READS_END, KW));
            assertEquals(Map.of("seen", 1), run(engine, "end"));
        }
    }

    @Test
    @DisplayName("MVEL reserves output only for a rule list with an MVEL rule")
    void mvelReservesOutputWhereUsed() {
        assertFalse(new MvelExpressionLanguage().reservesForEveryRuleList());
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).language(new Keywordy(false, false))
                .defaultLanguage("kw").build()) {
            engine.load(List.of(KW));
            assertEquals(Map.of(), run(engine, "output"));

            engine.load(List.of(Rule.builder().ruleName("m").language("mvel").condition("true")
                    .action("output.put('r', 1)").build(), KW));
            assertEquals("'output' is reserved for the output object and cannot be used as a fact name",
                    run(engine, "output"));
        }
    }

    @Test
    @DisplayName("a fact declared output is rejected by the load of a rule list with an MVEL rule, not by build()")
    void declaredOutputRejectedByLoad() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).fact("output", Integer.class).build()) {
            RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(READS_END)));
            assertTrue(ex.getMessage().contains(
                    "'output' is reserved for the output object and cannot be declared as a fact"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("a fact named with an MVEL keyword is read by kw's rules when no MVEL rule names it")
    void mvelKeywordReadByAnotherLanguage() {
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(true, true))) {
            engine.load(List.of(READS_END, KW));
            assertEquals(Map.of("seen", 1), engine.run(new FactMap<>(new Fact<>("end", 1), new Fact<>("in", 2))));
        }
    }

    /**
     * MVEL rules that name the class {@code Math} where MVEL resolves names only as the expression runs, or not at
     * all: each still has MVEL check a fact named {@code Math}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "output.put('r', Math.abs(-1))",
        "def f(x) { x + Math.abs(-1) }; output.put('r', f(1))",
        "def f(x) { x }; output.put('r', f(Math.abs(-1)))",
        "def f() { g() }; def g() { Math.abs(-1) }; output.put('r', f())",
        "t = 0; foreach (x : [1]) { t += Math.abs(x) }; output.put('r', t)",
        "with (output) { put('r', Math.abs(-1)) }",
        "output.put('r', isdef Math)",
        "output.put('r', 'Math')",
        "// Math\noutput.put('r', 1)",
    })
    void lateReadNamesStillChecked(String action) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition("true").action(action).build()));
            assertEquals("'Math' cannot be used as a fact name: MVEL reads it as a keyword or class name, so rules"
                    + " would never see the fact", run(engine, "Math"));
        }
    }

    @Test
    @DisplayName("the issue's reproduction: an MVEL rule list reads a fact named with a keyword no rule names")
    void keywordNoRuleNamesAccepted() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition("true").action("output.put('r', 1)").build()));
            assertEquals(Map.of("r", 1), run(engine, "in"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"my-fact == 1", "(my-fact) == 1", "my-fact.equals(1)"})
    @DisplayName("a fact named as a word MVEL reads as several names, such as my-fact, is still rejected")
    void wordWrittenAsAFactStillChecked(String condition) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition(condition).action("output.put('r', 1)")
                    .build()));
            assertEquals("'my-fact' is not a valid fact name: rules can only refer to a fact named with a Java"
                    + " identifier", run(engine, "my-fact"));
        }
    }

    @Test
    @DisplayName("a fact named my-fact is still rejected where the rule glues it to an operator, as my-fact*2")
    void wordGluedToAnOperatorStillChecked() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition("my-fact*2 == -1").action("output.put('r', 1)")
                    .build()));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(
                    new Fact<>("my-fact", 1), new Fact<>("my", 3), new Fact<>("fact", 2))));
            assertEquals("'my-fact' is not a valid fact name: rules can only refer to a fact named with a Java"
                    + " identifier", ex.getMessage());
        }
    }

    /**
     * The known gap of the words MVEL takes a fact's name from, which are only its best guess at what a rule's author
     * meant: a word glued to a minus sign, such as {@code my-fact-1}, isn't split there, as that would split
     * {@code my-fact} too, so a fact named {@code my-fact} isn't checked, and MVEL reads {@code my} minus {@code fact}
     * minus 1 from facts by those names.
     */
    @Test
    @DisplayName("best effort: a fact named my-fact isn't checked where the rule glues it to a minus, as my-fact-1")
    void wordGluedToAMinusNotChecked() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition("my-fact-1 == 0").action("output.put('r', 1)")
                    .build()));
            assertEquals(Map.of("r", 1), engine.run(new FactMap<>(new Fact<>("my-fact", 1), new Fact<>("my", 3),
                    new Fact<>("fact", 2))));
        }
    }

    static Stream<Arguments> readWhole() {
        String put = "output.put('r', 1)";
        return Stream.of(
                Arguments.of("1+,a == 6", put, ",a"),
                Arguments.of("1%,a == 1", put, ",a"),
                Arguments.of(",a/**/ == 5", put, ",a"),
                Arguments.of("isdef a-b/**/", put, "a-b"),
                Arguments.of("1+\\a == 6", put, "\\a"),
                Arguments.of("1+\u00a0a == 6", put, "\u00a0a"),
                Arguments.of("true", "a.b++; " + put, "a.b"));
    }

    /**
     * Rules MVEL reads a fact of each name by, whole, though no identifier is that name: the name is a word between
     * operators, comment marks or MVEL's whitespace, so it's checked, and the run rejected, rather than the rule firing
     * on the fact (#1074 review, F1).
     */
    @ParameterizedTest(name = "{0} | {1}, fact {2}")
    @MethodSource("readWhole")
    void nameMvelReadsWholeStillChecked(String condition, String action, String fact) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition(condition).action(action).build()));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(new FactMap<>(new Fact<>(fact, 5))));
            assertTrue(ex.getMessage().endsWith("' is not a valid fact name: rules can only refer to a fact named with"
                    + " a Java identifier"), ex.getMessage());
        }
    }
}
