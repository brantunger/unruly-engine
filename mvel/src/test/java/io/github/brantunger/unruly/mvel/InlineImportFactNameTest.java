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

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact named like a class, or a static method, that an expression imports in its own text is rejected when the same
 * expression names it, as MVEL reads the name in that expression as the import, never as the fact (#1118).
 */
@DisplayName("a fact named like what an expression imports inline is rejected when that expression names it")
class InlineImportFactNameTest {

    // A word too long for MVEL to scan for the names its rules read, so it checks every fact.
    private static final String LONG_WORD = "'" + "a".repeat(Imports.MAX_IMPORT_LENGTH) + "'";

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static RulesEngine<Map<String, Object>> engine(Rule... rules) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        engine.load(List.of(rules));
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
        "Date  | import java.util.Date; Date == 1 | output.put('r', 1)",
        "Date  | true                             | import java.util.Date; output.put('r', new Date(0))",
        "Date  | true                             | import java.util.*; output.put('r', new Date(0))",
        "Date  | true                             | import java.util.*; Date d = new Date(0); output.put('r', d)",
        "abs   | true                             | import_static java.lang.Math.abs; output.put('r', abs(-1))",
    })
    @DisplayName("an inline class, package or static method import the expression names is rejected")
    void namedInlineImportRejected(String name, String condition, String action) {
        try (RulesEngine<Map<String, Object>> engine = engine(rule("r", condition, action))) {
            assertEquals(rejection(name), run(engine, name, 42));
        }
    }

    @Test
    @DisplayName("a class in an inline-imported package the expression doesn't name is a fact's name")
    void packageClassNotNamedAccepted() {
        try (RulesEngine<Map<String, Object>> engine = engine(
                rule("a", "true", "import java.util.*; output.put('a', new ArrayList().size())"))) {
            assertEquals(Map.of("a", 0), run(engine, "Date", 42));
        }
    }

    /**
     * The scan finds {@code Date} in a string literal or a comment, as it finds every word, but MVEL never looks it
     * up as a class, so the package import brings in no class by that name and the fact is accepted. A check that
     * looked every name the scan found up in the expression's packages would reject it. The last two rows bring in a
     * class, {@code ArrayList}, so the names the expression imports are kept only where it names them too: it names
     * {@code Date} in its text, but MVEL never brought that in.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "import java.util.*; output.put('r', 'Date')",
        "import java.util.*; /* Date */ output.put('r', 'x')",
        "import java.util.*; /* Date */ output.put('r', new ArrayList().size())",
        "import java.util.*; output.put('r', new ArrayList().size() + 'Date'.length())",
    })
    @DisplayName("a class in an inline-imported package named only in a string or comment is a fact's name")
    void packageClassNamedOnlyInTextAccepted(String action) {
        try (RulesEngine<Map<String, Object>> engine = engine(rule("r", "true", action))) {
            Object output = run(engine, "Date", 42);
            assertTrue(output instanceof Map, () -> "rejected: " + output);
        }
    }

    @Test
    @DisplayName("another rule reads the fact when no expression that imports the class names it")
    void otherRuleReadsFact() {
        try (RulesEngine<Map<String, Object>> engine = engine(
                rule("a", "true", "import java.util.*; output.put('a', new ArrayList().size())"),
                rule("b", "Date == 42", "output.put('b', Date)"))) {
            assertEquals(Map.of("a", 0, "b", 42), run(engine, "Date", 42));
        }
    }

    @Test
    @DisplayName("an expression that imports a class and names it rejects the fact another rule reads")
    void otherRuleCantReadFactNamedByImporter() {
        try (RulesEngine<Map<String, Object>> engine = engine(
                rule("a", "true", "import java.util.*; output.put('a', new Date(0))"),
                rule("b", "Date == 42", "output.put('b', Date)"))) {
            assertEquals(rejection("Date"), run(engine, "Date", 42));
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
        "Date | import java.util.Date; output.put('r', LONG.length())",
        "Date | import java.util.*; output.put('r', new Date(0) != null && LONG.length() > 0)",
        "abs  | import_static java.lang.Math.abs; output.put('r', abs(LONG.length()))",
    })
    @DisplayName("an expression too long to scan rejects what it imports by name, and what MVEL found in its packages")
    void tooLongToScanRejectsImports(String name, String action) {
        try (RulesEngine<Map<String, Object>> engine = engine(
                rule("r", "true", action.replace("LONG", LONG_WORD)))) {
            assertEquals(rejection(name), run(engine, name, 42));
            // Each row names what it imports, in the import's own words or, for the package, by the class MVEL found
            // in it, so a scan that hadn't given up would reject the fact too. A fact no rule names is checked only
            // once the scan gives up, so a keyword no rule names is rejected only then.
            assertEquals(rejection("empty"), run(engine, "empty", 42));
        }
    }

    @Test
    @DisplayName("an expression too long to scan doesn't reject a class in its packages MVEL never looked up")
    void tooLongToScanAcceptsUnresolvedPackageClass() {
        try (RulesEngine<Map<String, Object>> engine = engine(rule("r", "true",
                "import java.util.*; output.put('r', new ArrayList().size() + " + LONG_WORD + ".length())"))) {
            assertEquals(Map.of("r", Imports.MAX_IMPORT_LENGTH), run(engine, "Date", 42));
            // The scan gave up, so a keyword no rule names is checked, and rejected.
            assertEquals(rejection("empty"), run(engine, "empty", 42));
        }
    }

    /**
     * Once one expression is too long to scan, MVEL checks every fact, and stops scanning the rule list's later
     * expressions for the names they read, but still scans one that imports names of its own.
     */
    @Test
    @DisplayName("an expression compiled after another was too long to scan still rejects the names it imports")
    void importAfterTooLongToScanRejected() {
        try (RulesEngine<Map<String, Object>> engine = engine(
                rule("long", LONG_WORD + " != null", "output.put('long', 1)"),
                rule("named", "true", "import java.util.*; output.put('named', new Date(0))"),
                rule("unnamed", "true", "import java.util.*; output.put('unnamed', new ArrayList().size())"),
                rule("both", "true", "import_static java.lang.Math.abs; output.put('both', abs(" + LONG_WORD
                        + ".length()))"))) {
            assertEquals(rejection("Date"), run(engine, "Date", 42));
            assertEquals(rejection("abs"), run(engine, "abs", 42));
            assertEquals(Map.of("long", 1, "named", new Date(0), "unnamed", 0, "both", Imports.MAX_IMPORT_LENGTH),
                    run(engine, "amount", 42));
        }
    }

    @Test
    @DisplayName("a declared fact named like a class an expression imports inline and names fails the load")
    void declaredFactRejectedAtLoad() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .fact("Date", Object.class).build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(List.of(
                rule("r", "true", "import java.util.Date; output.put('r', new Date(0))"))));

        assertEquals("Declared fact 'Date' can't be used: " + rejection("Date"), ex.getMessage());
    }
}
