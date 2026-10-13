package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.MessageText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An expression's own {@code import a.B.*;} of a class with a public static field whose value is {@code null}, which
 * MVEL can't import, is rejected with a message that names the field, not as a malformed expression (#1198). A class
 * with no such field still imports, as {@code InlineStaticMemberFactNameTest.staticMemberNotLookedUpAccepted} shows.
 */
@DisplayName("an inline import of a class with a null public static field names the field (#1198)")
class InlineNullStaticFieldTest {

    private static final String MESSAGE = "Can't import 'probe.NullFieldHolder.*': its public static field 'X' is null";
    // A class whose null field's name is 250 Hangul fillers (U+3164), which javac takes for an identifier and the
    // engine escapes to six characters each.
    private static final String WIDE_HOLDER = "WideNullFieldHolder";
    private static final String WIDE_FIELD = "\u3164".repeat(250);

    /**
     * Compiles and defines, in this test's class loader and package, the class whose null field's name is too long
     * for a line of source here, once for the JVM.
     */
    @BeforeAll
    static void defineWideNullFieldHolder(@TempDir Path dir) throws Exception {
        String pkg = InlineNullStaticFieldTest.class.getPackageName();
        assertCompiles(List.of("-proc:none", "-d", dir.toString()), List.of(source(WIDE_HOLDER,
                "package " + pkg + ";\npublic class " + WIDE_HOLDER + " {\n    public static Object " + WIDE_FIELD
                        + " = null;\n}\n")));
        MethodHandles.lookup().defineClass(Files.readAllBytes(dir.resolve(pkg.replace('.', '/'))
                .resolve(WIDE_HOLDER + ".class")));
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    private static RulesEngine<Map<String, Object>> engine() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
    }

    private static void assertRejected(RuleCompilationException ex, String kind) {
        assertAll(
                () -> assertEquals(List.of(new InvalidExpressionException.Issue(
                        InvalidExpressionException.Issue.Severity.ERROR, 1, 8, MESSAGE)), ex.issues()),
                () -> assertEquals(kind + " for rule 'r' failed to compile at line 1, column 8: " + MESSAGE,
                        ex.getMessage()));
    }

    @ParameterizedTest(name = "{2}: {0} | {1}")
    @CsvSource(delimiter = '|', value = {
        "import probe.NullFieldHolder.*; A == 1 | output.put('r', 1)                                | Condition",
        "true                                   | import probe.NullFieldHolder.*; output.put('r', 1) | Action",
        "true                                   | import probe.NullFieldHolder.*; output.put('r', A) | Action",
    })
    @DisplayName("load() rejects the import, naming the field")
    void loadRejected(String condition, String action, String kind) {
        try (RulesEngine<Map<String, Object>> engine = engine()) {
            assertRejected(assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule(condition, action)))), kind);
        }
    }

    @ParameterizedTest(name = "{2}: {0} | {1}")
    @CsvSource(delimiter = '|', value = {
        "import probe.NullFieldHolder.*; A == 1 | output.put('r', 1)                                | Condition",
        "true                                   | import probe.NullFieldHolder.*; output.put('r', 1) | Action",
    })
    @DisplayName("validate() reports the import, naming the field")
    void validateReports(String condition, String action, String kind) {
        try (RulesEngine<Map<String, Object>> engine = engine()) {
            List<RuleCompilationException> problems = engine.validate(List.of(rule(condition, action)));

            assertEquals(1, problems.size(), () -> problems.toString());
            assertRejected(problems.get(0), kind);
        }
    }

    @Test
    @DisplayName("with strong typing and declared facts, load() rejects the import, naming the field")
    void typedLoadRejected() {
        try (RulesEngine<StringBuilder> engine = RulesEngineBuilder.allMatches(StringBuilder::new)
                .outputType(StringBuilder.class).fact("Z", Integer.class).requireDeclaredFacts()
                .option("mvel", "strongTyping", "true").build()) {
            assertRejected(assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule("true", "import probe.NullFieldHolder.*; output.append(Z)")))),
                    "Action");
        }
    }

    @Test
    @DisplayName("a class with a null public static field gives the message")
    void nullFieldNamed() {
        RuntimeException thrown = Imports.nullStaticField("probe.NullFieldHolder", new NullPointerException());

        assertInstanceOf(Imports.ImportRejected.class, thrown);
        assertEquals(MESSAGE, thrown.getMessage());
        assertEquals("probe.NullFieldHolder", ((Imports.ImportRejected) thrown).rejectedName());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        // Not a class.
        "probe.NoSuchClass",
        // An enum, whose constants MVEL imports.
        "java.time.DayOfWeek",
        // A class whose public static fields are none of them null.
        "java.lang.Math",
        // A class the engine can't read the null field of.
        "probe.HiddenNullField",
    })
    @DisplayName("any other NullPointerException from MVEL's import is thrown as it is")
    void otherExceptionKept(String packageName) {
        NullPointerException e = new NullPointerException();

        assertSame(e, Imports.nullStaticField(packageName, e));
    }

    @Test
    @DisplayName("a long field name that escapes is shortened, so the message keeps to 1,000 characters and names the "
            + "import")
    void longFieldNameShortened() {
        String imported = InlineNullStaticFieldTest.class.getPackageName() + "." + WIDE_HOLDER;
        try (RulesEngine<Map<String, Object>> engine = engine()) {
            RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule("true", "import " + imported + ".*; output.put('r', 1)"))));
            String description = ex.issues().get(0).message();
            String message = ex.getCause().getMessage();

            assertAll(
                    () -> assertTrue(message.length() <= MessageText.MAX_TEXT_LENGTH, () -> message.length() + ": "
                            + message),
                    () -> assertTrue(description.startsWith("Can't import '" + imported
                            + ".*': its public static field '\\u3164"), description),
                    () -> assertTrue(description.endsWith(" more characters)' is null"), description));
        }
    }
}
