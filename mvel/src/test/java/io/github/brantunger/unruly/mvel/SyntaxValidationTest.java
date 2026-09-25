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
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.CompileException;
import org.mvel2.ast.OperatorNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
}
