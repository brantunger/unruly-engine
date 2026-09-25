package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.CompileException;
import org.mvel2.ast.OperatorNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("syntax validation at load()")
class SyntaxValidationTest {

    private static final String BADLY_FORMED = "Action for rule 'syntax' failed to compile at line 1, column 5: "
            + "not a statement, or badly formed structure";

    private static Rule rule(String condition, String action) {
        return Rule.builder()
                .ruleName("syntax")
                .condition(condition)
                .action(action)
                .priority(1)
                .build();
    }

    /** Loads a rule that doesn't compile, and checks validate() reports it with the same message and issues. */
    private static RuleCompilationException loadAndValidate(Rule rule) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        RuleCompilationException loaded = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule)));
        List<RuleCompilationException> validated = engine.validate(List.of(rule));

        assertEquals(1, validated.size());
        assertEquals(loaded.getMessage(), validated.get(0).getMessage());
        assertEquals(loaded.issues(), validated.get(0).issues());
        return loaded;
    }

    /** A class loader that throws {@code thrown} when asked for the class {@code Widget}. */
    private static ClassLoader loaderThrowing(RuntimeException thrown) {
        return new ClassLoader(SyntaxValidationTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("Widget")) {
                    throw thrown;
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test
    @DisplayName("a doubled operator in a condition is rejected at compile time")
    void doubledOperatorInConditionRejected() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("x == == 1", "output.put('k', 1)"))));
        assertTrue(ex.getMessage().contains("'syntax'"));
    }

    @Test
    @DisplayName("a doubled operator in an action is rejected at compile time")
    void doubledOperatorInActionRejected() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("true", "x == == 1"))));
    }

    @Test
    @DisplayName("a compile error keeps MVEL's exception as its cause")
    void compileErrorKeepsCause() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("x >= ", "output.put('k', 1)"))));

        assertInstanceOf(CompileException.class, ex.getCause().getCause());
    }

    @ParameterizedTest(name = "valid expression {0} still compiles")
    @ValueSource(strings = {
            "Objects.nonNull(name) && name.startsWith('J')",
            "status in ['A', 'B']",
            "name ~= '[a-z]+'",
            "claim.?address == null",
            "x + 1 > 1 && x - 1 < 5",
            "($ in list if $ > 1).size() > 0",
    })
    void validConditionsStillCompile(String condition) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports("java.util").build();

        assertDoesNotThrow(() -> engine.load(List.of(rule(condition, "output.put('k', 1)"))));
    }

    @ParameterizedTest(name = "valid action {0} still compiles")
    @ValueSource(strings = {
            "if (x > 1) { output.put('a', 1); } else { output.put('b', 2); }",
            "foreach (i : list) { output.put(i, i); }",
            "def f(a) { a * 2 }; output.put('d', f(x))",
            "with (output) { put('a', 1), put('b', 2) }",
            "score = 10; output.put('s', score)",
    })
    void validActionsStillCompile(String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        assertDoesNotThrow(() -> engine.load(List.of(rule("true", action))));
    }

    @Test
    @DisplayName("the engine's imports are visible to the analysis pass")
    void importsVisibleToAnalysis() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports("java.util").build();
        engine.load(List.of(rule("Objects.nonNull(name)", "output.put('k', 1)")));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("name", "n");
        assertEquals(1, engine.run(facts).get("k"));
    }

    /** MVEL's parser still accepts this; pinned so a future MVEL upgrade that catches it is noticed. */
    @Test
    @DisplayName("known limitation: a stray closing parenthesis is only reported at run()")
    void strayParenthesisOnlyFailsAtRun() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        engine.load(List.of(rule("true)", "output.put('k', 1)")));

        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
    }

    // #637: MVEL's parser reads out of bounds for these, and the message was its IndexOutOfBoundsException's.
    @ParameterizedTest(name = "an action the MVEL parser reads out of bounds for, {0}, is a malformed expression")
    @ValueSource(strings = {"b.", "a.b.", "this.", "output.put('k', a.)", "?", "? ,", "b. == 1", "b.; x = 1", "foo(?)",
            "( ) + 1", "( )", "foo(( ))"})
    void outOfBoundsInMvelIsMalformed(String action) {
        RuleCompilationException ex = loadAndValidate(rule("true", action));

        assertEquals("Action for rule 'syntax' failed to compile: malformed expression", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "malformed expression")), ex.issues());
        InvalidExpressionException cause = assertInstanceOf(InvalidExpressionException.class, ex.getCause());
        assertEquals("failed to compile: malformed expression", cause.getMessage());
        assertInstanceOf(IndexOutOfBoundsException.class, cause.getCause());
    }

    @Test
    @DisplayName("a condition ending in a dot is a malformed expression")
    void conditionEndingInADotIsMalformed() {
        RuleCompilationException ex = loadAndValidate(rule("x > 1 && x.", "output.put('k', 1)"));

        assertEquals("Condition for rule 'syntax' failed to compile: malformed expression", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "malformed expression")), ex.issues());
    }

    @Test
    @DisplayName("a line separator in MVEL's description is escaped, and doesn't end the description")
    void lineSeparatorInDescriptionIsEscaped() {
        char lineSeparator = (char) 0x2028;
        RuleCompilationException ex = loadAndValidate(rule("true", "import java.util.X" + lineSeparator + "Y"));

        // The engine escapes the message again, which leaves an escaped description as it is.
        String description = "class not found: import java.util.X\\u2028Y";
        assertEquals("Action for rule 'syntax' failed to compile at line 1, column 8: " + description,
                ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, description)), ex.issues());
        assertEquals("failed to compile at line 1, column 8: " + description, ex.getCause().getMessage());
    }

    @Test
    @DisplayName("a format character in MVEL's description is escaped in the issue as in the message")
    void formatCharacterInDescriptionIsEscaped() {
        char rightToLeftOverride = (char) 0x202e;
        RuleCompilationException ex = loadAndValidate(rule("true", "import java.util." + rightToLeftOverride));

        String description = "class not found: import java.util.\\u202e";
        assertEquals("Action for rule 'syntax' failed to compile at line 1, column 8: " + description,
                ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, description)), ex.issues());
        assertEquals("failed to compile at line 1, column 8: " + description, ex.getCause().getMessage());
    }

    @Test
    @DisplayName("an IndexOutOfBoundsException from other code MVEL calls isn't a malformed expression")
    void outOfBoundsFromOtherCodeIsReportedAsIs() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        IndexOutOfBoundsException fromLoader = new IndexOutOfBoundsException("from the application's class loader");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(loaderThrowing(fromLoader));
        RuleCompilationException ex;
        try {
            ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule("true", "new Widget()"))));
        } finally {
            thread.setContextClassLoader(original);
        }

        assertEquals("Action for rule 'syntax' failed to compile: from the application's class loader",
                ex.getMessage());
        assertSame(fromLoader, ex.getCause());
        assertEquals(List.of(), ex.issues());
    }

    // #637: with assertions on, as in this suite, the message was "null (caused by java.lang.AssertionError)".
    @Test
    @DisplayName("a badly formed statement reads as MVEL's own description, though an assert inside MVEL fails")
    void badlyFormedStatementWithAssertionsOn() {
        assumeTrue(OperatorNode.class.desiredAssertionStatus(), "this test needs MVEL's asserts on");

        RuleCompilationException ex = loadAndValidate(rule("true", "b = = 1"));

        assertEquals(BADLY_FORMED, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 5, "not a statement, or badly formed structure")),
                ex.issues());
        assertEquals("failed to compile at line 1, column 5: not a statement, or badly formed structure",
                ex.getCause().getMessage());
    }

    @Test
    @DisplayName("a badly formed statement reads the same with assertions off")
    // Run in a new JVM with assertions off, as they usually are in production: this one has MVEL's asserts on.
    void badlyFormedStatementWithAssertionsOff(@TempDir Path dir) throws Exception {
        String output = ChildJvm.run(dir, AssertionsOffScenario.class, "-da");

        List<String> messages = output.lines().filter(line -> line.startsWith(AssertionsOffScenario.MESSAGE))
                .map(line -> line.substring(AssertionsOffScenario.MESSAGE.length()))
                .toList();
        assertEquals(List.of("false", BADLY_FORMED, BADLY_FORMED), messages,
                "whether MVEL's asserts are on, then load()'s and validate()'s messages; scenario output:\n" + output);
    }

    /**
     * Loads and validates a rule whose action or condition doesn't compile, and checks it's reported with one issue:
     * the description and, if the line isn't 0, the line and column.
     */
    private static RuleCompilationException assertReported(Rule rule, String part, int line, int column,
            String description) {
        RuleCompilationException ex = loadAndValidate(rule);

        String where = line == 0 ? "" : " at line " + line + ", column " + column;
        assertEquals(part + " for rule 'syntax' failed to compile" + where + ": " + description, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, line, column, description)), ex.issues());
        InvalidExpressionException cause = assertInstanceOf(InvalidExpressionException.class, ex.getCause());
        assertEquals("failed to compile" + where + ": " + description, cause.getMessage());
        return ex;
    }

    @Test
    @DisplayName("a class name two imported packages have is reported with MVEL's description and no position")
    void ambiguousClassNameHasNoPosition() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports("java.util", "java.awt").build();
        Rule ambiguous = rule("true", "x = List");

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(ambiguous)));

        assertEquals("Action for rule 'syntax' failed to compile: ambiguous class name: List", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "ambiguous class name: List")), ex.issues());
        InvalidExpressionException cause = assertInstanceOf(InvalidExpressionException.class, ex.getCause());
        assertEquals(RuntimeException.class, cause.getCause().getClass());
        assertEquals(ex.getMessage(), engine.validate(List.of(ambiguous)).get(0).getMessage());
    }

    static Stream<Arguments> declarationsOfUnknownTypes() {
        return Stream.of(
                arguments("BigDecimal total = 0", 1, 19),
                arguments("Zzz z = null", 1, 8),
                arguments("Zzz z", 1, 6),
                arguments("com.acme.Missing m = null", 1, 21),
                arguments("x y", 1, 4),
                arguments("x = 1;\nZzz z = 1", 2, 8));
    }

    // #642: the description was "unknown class or illegal statement: org.mvel2.ParserContext@" and a hash that
    // differed between load() and validate().
    @ParameterizedTest(name = "the declaration {0} is an unknown class, with no parser context")
    @MethodSource("declarationsOfUnknownTypes")
    void declarationOfAnUnknownTypeHasNoParserContext(String action, int line, int column) {
        assertReported(rule("true", action), "Action", line, column, "unknown class or illegal statement");
    }

    @Test
    @DisplayName("a condition that declares a variable of an unknown type is an unknown class, with no parser context")
    void conditionDeclaringAnUnknownTypeHasNoParserContext() {
        assertReported(rule("Zzz z == 1", "output.put('k', 1)"), "Condition", 1, 6,
                "unknown class or illegal statement");
    }

    static Stream<Arguments> declarationsOfOtherShapes() {
        char noBreakSpace = (char) 0xa0;
        return Stream.of(
                arguments("Zzz" + noBreakSpace + "z = 1"),
                arguments("x = 1;" + noBreakSpace + "String s = 1"),
                arguments("x = 1;" + (char) 0x2028 + "\nString s = 1"),
                arguments(new String(Character.toChars(0x1d4b3)) + "String s = 'a'"),
                arguments("x = 1;,String s = 1"));
    }

    @ParameterizedTest(name = "the declaration {0} is an unknown class, with no parser context")
    @MethodSource("declarationsOfOtherShapes")
    void declarationWithOtherCharactersHasNoParserContext(String action) {
        RuleCompilationException ex = loadAndValidate(rule("true", action));

        assertEquals("unknown class or illegal statement", ex.issues().get(0).message());
        assertFalse(ex.getMessage().contains("ParserContext@"), ex.getMessage());
    }

    // #643: MVEL's plain RuntimeException reached the engine's catch-all, with no InvalidExpressionException and no
    // issue.
    @ParameterizedTest(name = "the declaration {0} is reported with MVEL''s description and no position")
    @CsvSource(delimiter = '|', value = {
            "int in = 1                | illegal use of reserved word: in",
            "String null = 1           | illegal use of reserved word: null",
            "var soundslike x          | illegal use of reserved word: soundslike",
            "int 1x = 2                | not an identifier: 1x",
            "int a = 1; String a = 'x' | statically-typed variable already defined in scope: a",
    })
    void declarationMvelRejectsWithoutAPosition(String action, String description) {
        RuleCompilationException ex = assertReported(rule("true", action), "Action", 0, 0, description);

        assertEquals(RuntimeException.class, ex.getCause().getCause().getClass());
    }

    @Test
    @DisplayName("a plain RuntimeException from other code MVEL calls is reported as is")
    void plainRuntimeExceptionFromOtherCodeIsReportedAsIs() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        RuntimeException fromLoader = new RuntimeException("from the application's class loader");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(loaderThrowing(fromLoader));
        RuleCompilationException ex;
        try {
            ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule("true", "new Widget()"))));
        } finally {
            thread.setContextClassLoader(original);
        }

        assertEquals("Action for rule 'syntax' failed to compile: from the application's class loader",
                ex.getMessage());
        assertSame(fromLoader, ex.getCause());
        assertEquals(List.of(), ex.issues());
    }

    static Stream<Arguments> danglingOperators() {
        return Stream.of(
                arguments("x = y &&", 1, 7),
                arguments("x = y\n&&", 2, 1),
                arguments("x = foo\n&&", 2, 1));
    }

    // #643: the description was a NullPointerException's, "Cannot invoke ... because "node" is null".
    @ParameterizedTest(name = "a dangling operator in {0} is a badly formed structure")
    @MethodSource("danglingOperators")
    void danglingOperatorIsBadlyFormed(String action, int line, int column) {
        assertReported(rule("true", action), "Action", line, column, "not a statement, or badly formed structure");
    }

    static Stream<Arguments> positionsQuotedInTheExpression() {
        return Stream.of(
                arguments("true", "x = '[Line: 9, Column: 9]' +", "Action", 1, 28, "not a statement"),
                arguments("true", "x.foo('[Line: 9, Column: 9]') ++ )", "Action", 1, 34,
                        "expected end of statement but encountered: )"),
                arguments("true", "x = \"[Line: 9, Column: 9]\"; unknownFn(", "Action", 1, 38,
                        "unbalanced braces ( ... )"),
                arguments("true", "y = = 1 // [Line: 9, Column: 9]", "Action", 1, 5,
                        "not a statement, or badly formed structure"),
                arguments("name == '[Line: 9, Column: 9]' &&", "output.put('k', 1)", "Condition", 1, 32,
                        "Malformed expression"));
    }

    // #643: the position was the first "[Line: n, Column: n]" in MVEL's message, which quotes the expression.
    @ParameterizedTest(name = "a position quoted in the {2} (condition {0}, action {1}) isn''t MVEL''s")
    @MethodSource("positionsQuotedInTheExpression")
    void positionQuotedInTheExpressionIsNotMvels(String condition, String action, String part, int line, int column,
            String description) {
        assertReported(rule(condition, action), part, line, column, description);
    }

    static Stream<Arguments> danglingOperatorsAfterANumber() {
        return Stream.of(
                arguments("x = 1 &&", 1, 7),
                arguments("x = 1\n&&", 2, 1),
                arguments("var x = 1\n&&", 2, 1));
    }

    // #643: MVEL nests one error's message in another's, and the description began with the inner "[Error: ", at the
    // inner position, line 1, column 0.
    @ParameterizedTest(name = "a nested error in {0} has the inner description at the outer position")
    @MethodSource("danglingOperatorsAfterANumber")
    void nestedErrorHasInnerDescriptionAtOuterPosition(String action, int line, int column) {
        assertReported(rule("true", action), "Action", line, column,
                "was expecting type: java.lang.Boolean; but found type: java.lang.Integer");
    }
}
