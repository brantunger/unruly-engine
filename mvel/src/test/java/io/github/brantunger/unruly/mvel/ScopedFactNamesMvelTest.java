package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.TestLogs;
import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.MessageText;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.core.EngineLogs;
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
     * A word glued to a minus sign, such as {@code my-fact-1}, adds each span of it between minus signs, so a fact
     * named {@code my-fact} is still checked, and rejected, rather than MVEL reading {@code my} minus {@code fact}
     * minus 1 from facts by those names (#1089).
     */
    @Test
    @DisplayName("a fact named my-fact is still rejected where the rule glues it to a minus, as my-fact-1 (#1089)")
    void wordGluedToAMinusStillChecked() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition("my-fact-1 == 0").action("output.put('r', 1)")
                    .build()));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(
                    new Fact<>("my-fact", 1), new Fact<>("my", 3), new Fact<>("fact", 2))));
            assertEquals("'my-fact' is not a valid fact name: rules can only refer to a fact named with a Java"
                    + " identifier", ex.getMessage());
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

    static Stream<Arguments> wordsAtTheLimits() {
        String ends = "a" + ",a".repeat(Imports.MAX_IMPORT_PARTS - 2);
        String chars = "a".repeat(Imports.MAX_IMPORT_LENGTH - 2);
        // The # just after isdef counts twice: with the quotes, the commas make the word's count the limit.
        String isdef = "isdef#a" + ",a".repeat(Imports.MAX_IMPORT_PARTS - 4);
        return Stream.of(
                Arguments.of(Imports.MAX_IMPORT_PARTS + " ends", "'" + ends + "' != null", true),
                Arguments.of(Imports.MAX_IMPORT_PARTS + 1 + " ends", "'" + ends + ",a' != null", false),
                Arguments.of(Imports.MAX_IMPORT_PARTS + " ends, one after isdef", "'" + isdef + "' != null", true),
                Arguments.of(Imports.MAX_IMPORT_PARTS + 1 + " ends, one after isdef", "'" + isdef + ",a' != null",
                        false),
                Arguments.of(Imports.MAX_IMPORT_LENGTH + " characters", "'" + chars + "' != null", true),
                Arguments.of(Imports.MAX_IMPORT_LENGTH + 1 + " characters", "'" + chars + "a' != null", false));
    }

    /**
     * A word's spans grow as the square of its ends, so a word with more ends than an import has parts, one just after
     * isdef counted twice, or more characters than an import may have, isn't scanned: MVEL then can't tell which facts
     * its rules read, and checks every fact, so a fact named with a keyword no rule names is rejected, though it's read
     * at the limit (#1089).
     */
    @ParameterizedTest(name = "a word with {0}")
    @MethodSource("wordsAtTheLimits")
    void wordPastTheLimitsChecksEveryFact(String word, String condition, boolean scoped) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition(condition).action("output.put('r', 1)")
                    .build()));
            assertEquals(scoped ? Map.of("r", 1) : "'in' cannot be used as a fact name: MVEL reads it as a keyword or"
                    + " class name, so rules would never see the fact", run(engine, "in"));
        }
    }

    /**
     * The load says at DEBUG why MVEL checks every fact, naming the rule and the limit its word went past, once for the
     * rule list: the action, past the limits too, compiles after the condition gave up, so it isn't scanned (#1089).
     */
    @Test
    @DisplayName("a word past the limits is logged at DEBUG once for the rule list, naming the rule and the limit")
    void wordPastTheLimitsLogged() {
        String word = "'" + "a".repeat(Imports.MAX_IMPORT_LENGTH) + "'";
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            String logs = TestLogs.logsOf(() -> engine.load(List.of(Rule.builder().ruleName("m\n")
                    .condition(word + " != null").action("output.put('r', " + word + ")").build())));

            String line = "DEBUG " + EngineLogs.ENGINE_LOGGER + "MVEL checks every fact for this rule list: the"
                    + " condition of rule 'm\\n' has a word of more than " + Imports.MAX_IMPORT_LENGTH
                    + " characters, too long to scan for the names it reads";
            assertTrue(logs.contains(line), logs);
            assertEquals(logs.indexOf("MVEL checks every fact"), logs.lastIndexOf("MVEL checks every fact"), logs);
        }
    }

    static Stream<Arguments> readWholeBetweenEnds() {
        String put = "output.put('r', 1)";
        return Stream.of(
                Arguments.of("glued to a minus sign", "true", "a.b--\n; " + put, "a.b", "a.b"),
                Arguments.of("read by isdef up to a comment", "isdef a!b/**/", put, "a!b", "a!b"),
                Arguments.of("a backslash read from two", "\\\\a == 5", put, "\\", "\\"),
                Arguments.of("a high surrogate read alone", "\ud835\udc65", put, "\ud835", "\\ud835"),
                Arguments.of("an operator on its left and a comma on its right", "java.lang.Math.max(1+\\a,2) == 6",
                        put, "\\a", "\\a"));
    }

    static Stream<Arguments> readWholeAfterIsdefOrBeforeAStop() {
        String put = "output.put('r', 1)";
        String x = Character.toString(0x1D465);
        return Stream.of(
                Arguments.of("glued to isdef", "isdef#a", put, "#a"),
                Arguments.of("glued to isdef", "isdef,,a", put, ",,a"),
                Arguments.of("glued to isdef", "isdef.a/**/", put, ".a"),
                Arguments.of("glued to isdef", "(isdef\\a)", put, "\\a"),
                Arguments.of("glued to isdef", "isdef¬a", put, "¬a"),
                Arguments.of("glued to isdef", "isdef" + x + "/**/", put, x),
                Arguments.of("before a character not part of an identifier", ",a#b", put, ",a"),
                Arguments.of("before a character not part of an identifier", ",a^b", put, ",a"),
                Arguments.of("before a character not part of an identifier", ",a\\", put, ",a"),
                Arguments.of("before a character not part of an identifier", ",a`", put, ",a"),
                Arguments.of("before a character not part of an identifier", ",a ", put, ",a"),
                Arguments.of("before a character not part of an identifier", ",a" + x, put, ",a"))
                .map(arguments -> Arguments.of(arguments.get()[0], arguments.get()[1], arguments.get()[2],
                        arguments.get()[3], MessageText.escape((String) arguments.get()[3])));
    }

    /**
     * The five kinds of text MVEL reads a fact by a whole name in that no word of the text was (#1089), and the two
     * the scan missed after: a name glued to {@code isdef}, and one that ends at a character neither an end nor part
     * of an identifier. Each name is now among the spans of a word, or a backslash or a high surrogate alone, so it's
     * checked, and the run rejected before any rule runs, rather than the rule reading the fact, or failing on it. The
     * message quotes the name as the engine does, with a surrogate escaped.
     */
    @ParameterizedTest(name = "{0}: {1} | {2}, fact {3}")
    @MethodSource({"readWholeBetweenEnds", "readWholeAfterIsdefOrBeforeAStop"})
    void nameReadWholeBetweenEndsChecked(String kind, String condition, String action, String fact,
                                         String quoted) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition(condition).action(action).build()));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(new FactMap<>(new Fact<>(fact, 5))));
            assertEquals("'" + quoted + "' is not a valid fact name: rules can only refer to a fact named with a Java"
                    + " identifier", ex.getMessage());
        }
    }

    /**
     * A span with an end inside it, such as {@code &!false} in {@code true&&!false}, is a name MVEL never reads, so it
     * isn't among the names the rules read, and a fact by it isn't checked: no rule reads it (#1129).
     */
    @Test
    @DisplayName("a fact named by a span with an end inside it, which MVEL never reads, isn't checked (#1129)")
    void spanWithAnEndInsideNotChecked() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("m").condition("true&&!false").action("output.put('r', 1)")
                    .build()));
            assertEquals(Map.of("r", 1), run(engine, "&!false"));
            assertEquals("'&' is not a valid fact name: rules can only refer to a fact named with a Java identifier",
                    run(engine, "&"));
        }
    }

    /**
     * The compiler keeps one set of the names its expressions read: factNamesRead() makes it unmodifiable once and
     * returns that set, so the engine keeps it without a copy of its own; an expression compiled after adds to a new
     * set, leaving the one returned as it was (#1129).
     */
    @Test
    @DisplayName("the compiler returns the same unmodifiable set of names each time, and a later compile a new one")
    void namesReadKeptOnce() {
        MvelExpressionCompiler compiler = new MvelExpressionCompiler(new Imports(Set.of(), Set.of(),
                ScopedFactNamesMvelTest.class.getClassLoader()));
        compiler.compileCondition(new Expression("m", ExpressionKind.CONDITION, "a == 1"));

        Set<String> read = compiler.factNamesRead();
        assertSame(read, compiler.factNamesRead());
        assertEquals(Set.of("a", "==", "1", "="), read);
        assertThrows(UnsupportedOperationException.class, () -> read.add("b"));

        compiler.compileCondition(new Expression("m", ExpressionKind.CONDITION, "b == 2"));
        assertEquals(Set.of("a", "==", "1", "="), read);
        assertEquals(Set.of("a", "b", "==", "1", "2", "="), compiler.factNamesRead());
    }
}
