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
                arguments("BigDecimal total = 0", 1, 19, "BigDecimal"),
                arguments("Zzz z = null", 1, 8, "Zzz"),
                arguments("Zzz z", 1, 6, "Zzz"),
                arguments("com.acme.Missing m = null", 1, 21, "com.acme.Missing"),
                arguments("x y", 1, 4, "x"),
                arguments("x = 1;\nZzz z = 1", 2, 8, "Zzz"));
    }

    // #642: the description was "unknown class or illegal statement: org.mvel2.ParserContext@" and a hash that
    // differed between load() and validate(). #644: it named no type.
    @ParameterizedTest(name = "the declaration {0} is an unknown class, named {3}, with no parser context")
    @MethodSource("declarationsOfUnknownTypes")
    void declarationOfAnUnknownTypeHasNoParserContext(String action, int line, int column, String type) {
        assertReported(rule("true", action), "Action", line, column, "unknown class or illegal statement: " + type);
    }

    @Test
    @DisplayName("a condition that declares a variable of an unknown type is an unknown class, with no parser context")
    void conditionDeclaringAnUnknownTypeHasNoParserContext() {
        assertReported(rule("Zzz z == 1", "output.put('k', 1)"), "Condition", 1, 6,
                "unknown class or illegal statement: Zzz");
    }

    static Stream<Arguments> declarationsOfOtherShapes() {
        char noBreakSpace = (char) 0xa0;
        String scriptX = new String(Character.toChars(0x1d4b3));
        return Stream.of(
                arguments("Zzz" + noBreakSpace + "z = 1", ": Zzz"),
                arguments("x = 1;" + noBreakSpace + "String s = 1", ""),
                arguments("x = 1;" + (char) 0x2028 + "\nString s = 1", ""),
                arguments(scriptX + "String s = 'a'", ": " + scriptX + "String"),
                arguments("x = 1;,String s = 1", ""));
    }

    @ParameterizedTest(name = "the declaration {0} is an unknown class, with no parser context")
    @MethodSource("declarationsOfOtherShapes")
    void declarationWithOtherCharactersHasNoParserContext(String action, String named) {
        RuleCompilationException ex = loadAndValidate(rule("true", action));

        assertEquals("unknown class or illegal statement" + named, ex.issues().get(0).message());
        assertFalse(ex.getMessage().contains("ParserContext@"), ex.getMessage());
    }

    static Stream<Arguments> declarationsNamingAnotherToken() {
        return Stream.of(
                // #644: the text before MVEL's cursor, read again, named a token MVEL never read.
                arguments((char) 0xa0 + "String s = 1", 1, 12),
                arguments("foo().Zzz s = 1", 1, 14),
                arguments("com.acme .Missing m = null", 1, 22),
                arguments("x = 1, Missing m = 2", 1, 19),
                arguments("Foo [] a = null", 1, 11),
                // MVEL's last node is older than the declaration inside parentheses, brackets or a block.
                arguments("foo(Zzz s = 1)", 1, 12),
                arguments("foo(x Zzz s = 1)", 1, 10),
                arguments("[Zzz s = 1]", 1, 9),
                arguments("x = (Zzz s = 1)", 1, 13),
                arguments("if (true) { Zzz s = 1 }", 1, 20),
                arguments("y = 1; if (true) { Zzz s = 1 }", 1, 27),
                // MVEL's last node is the literal that ends the statement before.
                arguments("y = 1\nZzz s = 1", 2, 4),
                // What MVEL read after the node isn't a variable's name.
                arguments("foo()Zzz s = 1", 1, 9),
                arguments("a[0]Zzz s = 1", 1, 8),
                // MVEL's node is a word Java or MVEL reserves.
                arguments("this Zzz s = 1", 1, 9),
                arguments("public Zzz s = 1", 1, 11),
                arguments("a.class s = 1", 1, 12));
    }

    // #644: the type is named only from the node MVEL rejected, and only when that node is the declaration's type.
    @ParameterizedTest(name = "the declaration {0} is an unknown class, naming no other token")
    @MethodSource("declarationsNamingAnotherToken")
    void declarationNamesNoOtherToken(String action, int line, int column) {
        assertReported(rule("true", action), "Action", line, column, "unknown class or illegal statement");
    }

    // #644: a package may be named with a word a type can't have, such as function or record.
    @ParameterizedTest(name = "the declaration {0} is an unknown class, named ''{2}''")
    @CsvSource(delimiter = '|', value = {
            "java.util.function.Functon f = 1 | 31 | java.util.function.Functon",
            "com.acme.record.Msg m = 1        | 24 | com.acme.record.Msg",
    })
    void typeInAPackageNamedWithAReservedWordIsNamed(String action, int column, String type) {
        assertReported(rule("true", action), "Action", 1, column, "unknown class or illegal statement: " + type);
    }

    // #644: the chained expressions before a declaration: MVEL's token is named only when it's a type's name.
    @ParameterizedTest(name = "the declaration {0} is an unknown class, named ''{1}''")
    @CsvSource(delimiter = '|', value = {
            "fooZzz s = 1     | : fooZzz",
            "foo Zzz s = 1    | : foo",
            "foo.Zzz s = 1    | : foo.Zzz",
            "foo .Zzz s = 1   | ''",
            "foo. Zzz s = 1   | ''",
            "foo() Zzz s = 1  | ''",
            "foo().Zzz s = 1  | ''",
            "foo() .Zzz s = 1 | ''",
            "foo(). Zzz s = 1 | ''",
            "a[0] Zzz s = 1   | ''",
            "a[0].Zzz s = 1   | ''",
            "a[0] .Zzz s = 1  | ''",
            "a[0]. Zzz s = 1  | ''",
            "x.Zzz s = 1      | : x.Zzz",
            "x .Zzz s = 1     | ''",
    })
    void chainedDeclarationNamesOnlyATypesName(String action, String named) {
        RuleCompilationException ex = loadAndValidate(rule("true", action));

        assertEquals("unknown class or illegal statement" + named, ex.issues().get(0).message());
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

    static Stream<Arguments> descriptionsWithLineBreaks() {
        return Stream.of(
                arguments("import java.util.Lisst;\nx = 1", "class not found: import java.util.Lisst;\\nx = 1"),
                arguments("import java.util.Lisst;\nnote = '\n[Error: all good]';",
                        "class not found: import java.util.Lisst;\\nnote = '\\n[Error: all good]';"),
                arguments("import java.util.Lisst; y = a[0]\nx = 1",
                        "class not found: import java.util.Lisst; y = a[0]\\nx = 1"),
                arguments("import java.util.Lisst; y = a[0] + 1\nx = 1",
                        "class not found: import java.util.Lisst; y = a[0] + 1\\nx = 1"),
                arguments("import java.util.Lisst; note = ']\n[Near : {... x ....}]';\nx = 1",
                        "class not found: import java.util.Lisst; note = ']\\n[Near : {... x ....}]';\\nx = 1"));
    }

    // #651: a description with a line break was MVEL's whole message, a later line of the expression, or cut at a ].
    @ParameterizedTest(name = "MVEL''s description over a line break in {0} is read whole, and escaped")
    @MethodSource("descriptionsWithLineBreaks")
    void descriptionWithLineBreakReadWhole(String action, String description) {
        assertReported(rule("true", action), "Action", 1, 8, description);
    }

    // MVEL catches an out-of-bounds read in its parser and describes it itself, which is kept.
    @ParameterizedTest(name = "an out-of-bounds read MVEL describes itself, in {0}, keeps MVEL''s description")
    @CsvSource(delimiter = '|', value = {
            "if (x) y = 1     | 13",
            "if (x) y         | 9",
            "x = y.           | 7",
            "x = y.z.         | 9",
            "x = 1; while     | 13",
            "foreach          | 8",
            "import Zzz s = 1 | 17",
    })
    void unexpectedEndOfStatementKept(String action, int column) {
        assertReported(rule("true", action), "Action", 1, column, "unexpected end of statement");
    }

    // #651: MVEL wraps its out-of-bounds read in its own error, and the description was the JDK's
    // "Index 35 out of bounds for length 34", then, once HotSpot threw it without a message, "null (caused by ...)".
    @ParameterizedTest(name = "an out-of-bounds read MVEL wraps (condition {1}, action {2}) is a malformed expression")
    @CsvSource(delimiter = '|', value = {
            "Condition | x == 1 && in | output.put('k', 1) | 1 | 11",
            "Condition | in==         | output.put('k', 1) | 1 | 3",
            "Action    | true         | (int) -- in        | 1 | 10",
    })
    void wrappedOutOfBoundsIsMalformed(String part, String condition, String action, int line, int column) {
        RuleCompilationException ex = assertReported(rule(condition, action), part, line, column,
                "malformed expression");

        assertInstanceOf(CompileException.class, ex.getCause().getCause());
    }

    static Stream<Arguments> literalsBeforeDeclarations() {
        return Stream.of(
                arguments("output.n = 5\nBigDecimal total = 0", 11),
                arguments("x = true\nZzz z = 1", 4),
                arguments("x = null\nZzz z = 1", 4),
                arguments("x = 1.5\nZzz z = 1", 4));
    }

    // #651: MVEL named the literal that ends the statement before as the unknown class.
    @ParameterizedTest(name = "a literal before the declaration {0} isn''t named as the unknown class")
    @MethodSource("literalsBeforeDeclarations")
    void literalBeforeDeclarationIsNotNamed(String action, int column) {
        assertReported(rule("true", action), "Action", 2, column, "unknown class or illegal statement");
    }

    @ParameterizedTest(name = "the array type {0} MVEL names is named once")
    @CsvSource(delimiter = '|', value = {
            "Zzz[] z = null           | 10 | Zzz[]",
            "Foo[] a = null           | 10 | Foo[]",
            "Zzz[][] z = null         | 12 | Zzz[][]",
            "java.util.Zzz[] z = null | 20 | java.util.Zzz[]",
    })
    void arrayTypeNamedOnce(String action, int column, String type) {
        assertReported(rule("true", action), "Action", 1, column, "unknown class or illegal statement: " + type);
    }

    // #651: MVEL cast the imported class to a static method, and the ClassCastException reached the engine's catch-all,
    // with no issue.
    @ParameterizedTest(name = "with {0} imported, the rule (condition {2}, action {3}) calls a class like a method")
    @CsvSource(delimiter = '|', value = {
            "java.util           | Action    | true                 | x = ArrayList(y)",
            "java.util           | Action    | true                 | x = ArrayList()",
            "java.util           | Action    | true                 | foo(ArrayList(y))",
            "java.util           | Condition | ArrayList(y) != null | output.put('k', 1)",
            "java.util.ArrayList | Action    | true                 | x = ArrayList(y)",
            "java.util.ArrayList | Condition | ArrayList(y) != null | output.put('k', 1)",
    })
    void classCalledLikeMethod(String imported, String part, String condition, String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports(imported).build();
        Rule rule = rule(condition, action);

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(List.of(rule)));

        String description = "a class can't be called like a method: use new";
        assertEquals(part + " for rule 'syntax' failed to compile: " + description, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, description)), ex.issues());
        InvalidExpressionException cause = assertInstanceOf(InvalidExpressionException.class, ex.getCause());
        assertInstanceOf(ClassCastException.class, cause.getCause());
        assertEquals(ex.issues(), engine.validate(List.of(rule)).get(0).issues());
    }

    @ParameterizedTest(name = "with {0} imported, the action {1} still compiles")
    @CsvSource(delimiter = '|', value = {
            "java.util           | x = ArrayList",
            "java.util           | x = new ArrayList(y)",
            "java.lang.Math      | x = PI(1)",
            "java.time.DayOfWeek | x = MONDAY(1)",
            "java.lang.Math      | x = max(1, 2)",
    })
    void nameCalledLikeMethodStillCompiles(String imported, String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports(imported).build();

        assertEquals(List.of(), engine.validate(List.of(rule("true", action))));
    }

    @Test
    @DisplayName("a class called like a method without its import still compiles, as MVEL doesn't know the name")
    void classCalledLikeMethodWithoutItsImportStillCompiles() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        assertEquals(List.of(), engine.validate(List.of(rule("true", "x = ArrayList(y)"))));
    }
}
