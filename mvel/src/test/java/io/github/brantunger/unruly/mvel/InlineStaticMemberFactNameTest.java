package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact named like a public static field, or an enum constant, of a class an expression imports with
 * {@code import a.B.*;} is rejected when MVEL looks the name up as it compiles that expression, as MVEL reads the name
 * in that expression as the field, never as the fact (#1195). MVEL imports those names without calling
 * {@code addImport}, so they aren't among #1118's names on their own.
 */
@DisplayName("a fact named like a static field or enum constant an expression imports inline is rejected when named")
class InlineStaticMemberFactNameTest {

    // A word too long for MVEL to scan for the names its rules read, so it checks every fact.
    private static final String LONG_WORD = "'" + "a".repeat(Imports.MAX_IMPORT_LENGTH) + "'";

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    private static RulesEngine<Map<String, Object>> engine(String condition, String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        engine.load(List.of(rule(condition, action)));
        return engine;
    }

    private static String rejection(String name) {
        return "'" + name + "' cannot be used as a fact name: MVEL reads it as a keyword or class name, so rules would"
                + " never see the fact";
    }

    private static Object run(RulesEngine<Map<String, Object>> engine, String name, Object value) {
        try {
            return engine.run(new FactMap<>(new Fact<>(name, value)));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "PI     | import java.lang.Math.*; PI == 7       | output.put('r', 1)",
        "PI     | true                                   | import java.lang.Math.*; output.put('r', PI)",
        "PI     | true                                   | import java.lang.Math.*; x = PI; output.put('r', x)",
        "MONDAY | true                                   | import java.time.DayOfWeek.*; output.put('r', MONDAY)",
        "MONDAY | import java.time.DayOfWeek.*; MONDAY != null | output.put('r', 1)",
    })
    @DisplayName("a static field or enum constant of an inline-imported class the expression names is rejected")
    void namedStaticMemberRejected(String name, String condition, String action) {
        try (RulesEngine<Map<String, Object>> engine = engine(condition, action)) {
            assertEquals(rejection(name), run(engine, name, 42));
        }
    }

    /**
     * MVEL looks a name up among the imports only once it is there, so it takes a field's name the expression uses
     * before the import as an input, and one it assigns to after the import as a variable. The expression still reads
     * the field by a name used before the import as it runs, not the fact.
     */
    @ParameterizedTest(name = "{0} | {1}")
    @CsvSource(delimiter = '|', value = {
        "true                                          | output.put('r', PI); import java.lang.Math.*;",
        "(PI == 42) && (import java.lang.Math.*; true) | output.put('r', 1)",
        "true                                          | import java.lang.Math.*; PI += 1; output.put('r', 1)",
        "true                                          | import java.lang.Math.*; PI = 1; output.put('r', 1)",
    })
    @DisplayName("a static field of an inline-imported class used before the import, or assigned to, is rejected")
    void staticMemberUsedBeforeImportRejected(String condition, String action) {
        try (RulesEngine<Map<String, Object>> engine = engine(condition, action)) {
            assertEquals(rejection("PI"), run(engine, "PI", 42));
        }
    }

    private static RulesEngine<StringBuilder> typedEngine(String fact) {
        return RulesEngineBuilder.allMatches(StringBuilder::new).outputType(StringBuilder.class)
                .fact(fact, Double.class).requireDeclaredFacts().option("mvel", "strongTyping", "true").build();
    }

    private static String typedLoadFailure(String fact, String condition, String action) {
        RulesEngine<StringBuilder> engine = typedEngine(fact);
        return assertThrows(RuleCompilationException.class, () -> engine.load(List.of(rule(condition, action))))
                .getMessage();
    }

    @ParameterizedTest(name = "{0} | {1}")
    @CsvSource(delimiter = '|', value = {
        "true                                          | output.append(PI); import java.lang.Math.*;",
        "(PI == 42) && (import java.lang.Math.*; true) | output.append(1)",
        "true                                          | import java.lang.Math.*; PI += 1; output.append(1)",
        "true                                          | import java.lang.Math.*; PI = 1; output.append(1)",
    })
    @DisplayName("with strong typing, a declared fact named like a static field the expression uses before the "
            + "import, or assigns to, fails the load")
    void typedStaticMemberUsedBeforeImportRejected(String condition, String action) {
        assertEquals("Declared fact 'PI' can't be used: " + rejection("PI"), typedLoadFailure("PI", condition, action));
    }

    /**
     * With strong typing, MVEL takes every declared fact as an input before it reads the expression, so a declared
     * fact named like a field of a class the expression imports with {@code import a.B.*;} counts as the expression's
     * own wherever its text names it, even only in a comment or a string. One its text doesn't name is accepted.
     */
    @Test
    @DisplayName("with strong typing, a declared fact named like a field of an inline-imported class the text names is "
            + "rejected, even only in a comment")
    void typedDeclaredFieldNamedInCommentRejected() {
        assertEquals("Declared fact 'E' can't be used: " + rejection("E"),
                typedLoadFailure("E", "true", "import java.lang.Math.*; /* E */ output.append(PI)"));

        try (RulesEngine<StringBuilder> engine = typedEngine("E")) {
            engine.load(List.of(rule("true", "import java.lang.Math.*; output.append(PI)")));
            assertEquals(String.valueOf(Math.PI), engine.run(new FactMap<>(new Fact<>("E", 42.0))).toString());
        }
    }

    /**
     * Each row looks up {@code PI}, so the expression's own imports brought a name in, and its names are kept only
     * where they are among those: {@code E} is a field of the same class, which the scan finds in a string literal or
     * a comment, or not at all, but MVEL never looks it up.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "import java.lang.Math.*; output.put('r', PI)",
        "import java.lang.Math.*; output.put('r', PI + 'E'.length())",
        "import java.lang.Math.*; /* E */ output.put('r', PI)",
        "import java.util.Date; import java.lang.Math.*; /* E */ output.put('r', PI + new Date(0).getTime())",
    })
    @DisplayName("a static field of an inline-imported class named only in a string or comment, or not at all, is a "
            + "fact's name")
    void staticMemberNotLookedUpAccepted(String action) {
        try (RulesEngine<Map<String, Object>> engine = engine("true", action)) {
            Object output = run(engine, "E", 42);
            assertTrue(output instanceof Map, () -> "rejected: " + output);
        }
    }

    @Test
    @DisplayName("an enum constant of an inline-imported enum the expression doesn't name is a fact's name")
    void enumConstantNotNamedAccepted() {
        try (RulesEngine<Map<String, Object>> engine = engine("true",
                "import java.time.DayOfWeek.*; output.put('r', MONDAY)")) {
            assertEquals(Map.of("r", DayOfWeek.MONDAY), run(engine, "TUESDAY", 42));
        }
    }

    @Test
    @DisplayName("an expression too long to scan rejects a static field MVEL looked up, but not one it never did")
    void tooLongToScanRejectsOnlyLookedUpFields() {
        try (RulesEngine<Map<String, Object>> engine = engine("true",
                "import java.lang.Math.*; output.put('r', PI + " + LONG_WORD + ".length())")) {
            assertEquals(rejection("PI"), run(engine, "PI", 42));
            assertEquals(Map.of("r", Math.PI + Imports.MAX_IMPORT_LENGTH), run(engine, "E", 42));
            // The scan gave up, so a keyword no rule names is checked, and rejected.
            assertEquals(rejection("empty"), run(engine, "empty", 42));
        }
    }

    @Test
    @DisplayName("a class nested in a class imported with import a.B.* is still rejected when the expression names it")
    void nestedClassStillRejected() {
        try (RulesEngine<Map<String, Object>> engine = engine("true",
                "import java.util.Map.*; output.put('r', Entry.class.getSimpleName())")) {
            assertEquals(rejection("Entry"), run(engine, "Entry", 42));
        }
    }
}
