package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact named after the first part of a class a rule uses, such as {@code java} for
 * {@code java.lang.Integer.MAX_VALUE}, is rejected: MVEL would read the fact in the class's place.
 */
@DisplayName("a fact named after the package of a class a rule names is rejected")
class ClassNameRootFactTest {

    static final String FIELD = "amount < java.lang.Integer.MAX_VALUE";

    /**
     * Rules that use {@code java.lang.Integer}, each with a name, a condition and an action that puts 1 in the output:
     * a condition that reads a field of it, and actions that name it only in the arguments of a call to a function
     * the action defines, which MVEL doesn't look up until the action runs, some on the line after a comment that
     * ends in a dot.
     */
    static final String[][] RULES = {
        {"field", FIELD, "output.put('r', 1)"},
        {"def argument", "amount > 0",
            "def f(x) { x > 0 ? 1 : 0 }; output.put('r', f(java.lang.Integer.MAX_VALUE));"},
        {"def call alone", "amount > 0",
            "def f(x) { output.put('r', x > 0 ? 1 : 0) }; f(java.lang.Integer.MAX_VALUE);"},
        {"nested def calls", "amount > 0",
            "def f(x) { x > 0 ? 1 : 0 }; def g(y) { y }; output.put('r', g(f(java.lang.Integer.MAX_VALUE)));"},
        {"two def arguments", "amount > 0",
            "def f(x, y) { x > 0 ? 1 : 0 }; output.put('r', f(java.lang.Integer.MAX_VALUE, 1));"},
        {"projection of a def call", "amount > 0",
            "def f(x) { x }; output.put('r', (f(java.lang.Integer.MAX_VALUE) in [1]).size());"},
        {"def argument after a line comment", "amount > 0",
            "def f(x) { x }; output.put('r', f(\n// the cap.\njava.lang.Integer.MAX_VALUE) > 0 ? 1 : 0);"},
        {"def argument after a line comment ending in .?", "amount > 0",
            "def f(x) { x }; output.put('r', f(\n// a.?\n   java.lang.Integer.MAX_VALUE) > 0 ? 1 : 0);"},
        {"def argument after a line comment, with CRLF", "amount > 0",
            "def f(x) { x }; output.put('r', f(\r\n// the cap.\r\njava.lang.Integer.MAX_VALUE) > 0 ? 1 : 0);"},
    };

    /** What a fact named java holds in the class's place: {@code java.lang.Integer.MAX_VALUE} is 0 through it. */
    static final Map<String, Object> PACKAGE_LIKE = Map.of("lang", Map.of("Integer", Map.of("MAX_VALUE", 0)));

    static String rejection(String name) {
        return "'" + name + "' cannot be used as a fact name: the rules use a class whose package starts with '"
                + name + "', and MVEL would read the fact in the class's place";
    }

    static RulesEngine<Map<String, Object>> engine(String condition, String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(rule(condition, action)));
        return engine;
    }

    static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    static FactMap<Object> amount() {
        return new FactMap<>(new Fact<>("amount", 5));
    }

    static FactMap<Object> withFact(String name, Object value) {
        FactMap<Object> facts = amount();
        facts.setValue(name, value);
        return facts;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "field|amount < java.lang.Integer.MAX_VALUE|output.put('r', 1)",
        "static call|java.lang.Math.max(amount, 1) > 0|output.put('r', 1)",
        "Objects.isNull|!java.util.Objects.isNull(amount)|output.put('r', 1)",
        "if branch|amount > 0|if (amount > 0) { output.put('r', java.lang.Integer.MAX_VALUE > 0 ? 1 : 0); }",
        "ternary|(amount > 0 ? java.lang.Integer.MAX_VALUE : 0) > 0|output.put('r', 1)",
        "def|amount > 0|def f() { java.lang.Integer.MAX_VALUE > 0 ? 1 : 0 }; output.put('r', f());",
        "foreach|amount > 0|foreach (x : [1, 2]) { output.put('r', java.lang.Integer.MAX_VALUE > 0 ? 1 : 0); }",
        "with|amount > 0|with (output) { put('r', java.lang.Integer.MAX_VALUE > 0 ? 1 : 0) }",
        "import_static|amount > 0|import_static java.lang.Math.max; output.put('r', max(amount, 1) > 0 ? 1 : 0);",
    })
    @DisplayName("whatever reads the class, and runs without the fact read the class")
    void rejectedWhereverTheClassIsRead(String form, String condition, String action) {
        RulesEngine<Map<String, Object>> engine = engine(condition, action);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact("java", PACKAGE_LIKE)), form);

        assertEquals(rejection("java"), ex.getMessage());
        for (int run = 0; run < 3; run++) {
            assertEquals(Map.of("r", 1), engine.run(amount()), form + ", run " + run);
        }
    }

    @Test
    @DisplayName("the first part of an imported package, once a rule finds a class in it, is rejected too")
    void importedPackageRootRejected() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("acme.orders").build();
        engine.load(List.of(rule("amount < Order.LIMIT", "output.put('r', 1)")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact("acme", 1)));

        assertEquals(rejection("acme"), ex.getMessage());
        assertEquals(Map.of("r", 1), engine.run(amount()));
    }

    @Test
    @DisplayName("the first part of a class nested in a class import, once a rule uses the nested class, is rejected")
    void nestedClassOfAClassImportRootRejected() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("java.util.Map").build();
        engine.load(List.of(rule("Map.Entry.comparingByKey() != null", "output.put('r', 1)")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact("java", 1)));

        assertEquals(rejection("java"), ex.getMessage());
        assertEquals(Map.of("r", 1), engine.run(amount()));
    }

    @Test
    @DisplayName("a class name found in two imported packages fails the load, even where MVEL reads it only as it runs")
    void ambiguousClassNameFailsTheLoad() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("java.util", "java.sql").build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(List.of(
                rule("amount > 0", "def f(x) { x }; if (amount > 100) { f(Date.class) }; output.put('r', 1);"))));

        assertTrue(ex.getMessage().contains("ambiguous class name: Date"), ex.getMessage());
    }

    @Test
    @DisplayName("a name both a package root and a class in an imported package gets the root's reason")
    void packageRootAndClassNameGetsThePackageReason() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("acme.orders", "acme.both").build();
        engine.load(List.of(rule("amount < Order.LIMIT", "output.put('r', 1)")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact("acme", 1)));

        assertEquals(rejection("acme"), ex.getMessage());
    }

    @Test
    @DisplayName("a class in an imported package that is no root the rules use keeps the class reason")
    void classNameOnlyKeepsTheClassReason() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("acme.both").build();
        engine.load(List.of(rule("amount > 1", "output.put('r', 1)")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact("acme", 1)));

        assertEquals("'acme' cannot be used as a fact name: MVEL reads it as a keyword or class name, so rules would "
                + "never see the fact", ex.getMessage());
    }

    @Test
    @DisplayName("an imported package no rule finds a class in leaves its first part a fact's name")
    void importedPackageUnusedAccepted() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("acme.orders").build();
        engine.load(List.of(rule("acme == 1", "output.put('r', 1)")));

        assertEquals(Map.of("r", 1), engine.run(withFact("acme", 1)));
    }

    @Test
    @DisplayName("a declared fact is rejected when the rules load")
    void declaredFactRejectedAtLoad() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .fact("java", Object.class).build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule(FIELD, "output.put('r', 1)"))));

        assertEquals("Declared fact 'java' can't be used: " + rejection("java"), ex.getMessage());
    }

    @Test
    @DisplayName("a declared fact is rejected when the rules load, when a class is named only in a def call")
    void declaredFactRejectedAtLoadForADefArgument() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .fact("java", Object.class).build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule(RULES[1][1], RULES[1][2]))));

        assertEquals("Declared fact 'java' can't be used: " + rejection("java"), ex.getMessage());
    }

    @Test
    @DisplayName("with requireDeclaredFacts(), declaring the fact fails the load")
    void requiredDeclaredFactRejectedAtLoad() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .fact("amount", Integer.class).fact("java", Map.class).requireDeclaredFacts().build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule(FIELD, "output.put('r', 1)"))));

        assertEquals("Declared fact 'java' can't be used: " + rejection("java"), ex.getMessage());
    }

    @Test
    @DisplayName("a rule list that names no such class leaves the name a fact's, in the same engine")
    void otherRuleListAccepts() {
        RulesEngine<Map<String, Object>> engine = engine(FIELD, "output.put('r', 1)");
        assertThrows(IllegalArgumentException.class, () -> engine.run(withFact("java", 1)));

        engine.load(List.of(rule("java == 1", "output.put('r', 1)")));

        assertEquals(Map.of("r", 1), engine.run(withFact("java", 1)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"javaVersion", "Java", "java_", "lang", "jav"})
    @DisplayName("a name that only starts like the package, or is another part of the class's name, is accepted")
    void otherNamesAccepted(String name) {
        RulesEngine<Map<String, Object>> engine = engine(FIELD + " && " + name + " == 1", "output.put('r', 1)");

        assertEquals(Map.of("r", 1), engine.run(withFact(name, 1)));
    }

    @Test
    @DisplayName("the engine's own check of a name in an imported package adds nothing, even once more compiles")
    void checkOfANameAddsNothing() {
        Imports imports = new Imports(Set.of("java.util"), Set.of(), ClassNameRootFactTest.class.getClassLoader());
        FactNames names = new FactNames(imports);
        MvelExpression.compile("java == 1", imports);
        names.check("java");
        // The check loads java.util.Date to find that the name is a class.
        assertThrows(IllegalArgumentException.class, () -> names.check("Date"));

        MvelExpression.compile("amount > 1", imports);

        names.check("java");
    }

    @Test
    @DisplayName("a rule whose only name with dots is a fact's property leaves the fact's name a fact's")
    void factPropertyAddsNothing() {
        RulesEngine<Map<String, Object>> engine = engine("applicant.score > 1", "output.put('r', 1)");

        assertEquals(Map.of("r", 1), engine.run(withFact("applicant", Map.of("score", 2))));
    }

    static Stream<Arguments> rulesAndCleanRuns() {
        return Arrays.stream(RULES).flatMap(rule -> Arrays.stream(ClassNameRootRunScenario.CLEAN_RUNS_FIRST)
                .mapToObj(cleanRunsFirst -> Arguments.of(rule[0], rule[1], rule[2], cleanRunsFirst)));
    }

    @ParameterizedTest(name = "{0}, after {3} runs without it")
    @MethodSource("rulesAndCleanRuns")
    @DisplayName("a run with the fact is rejected, and every run without it reads the class, with MVEL's JIT on")
    void rejectedAtAnyRun(String name, String condition, String action, int cleanRunsFirst) {
        String failure = ClassNameRootRunScenario.failure(condition, action, cleanRunsFirst);

        assertNull(failure, failure);
    }

    @Test
    @DisplayName("a run with the fact is rejected, and every run without it reads the class, with MVEL's JIT off")
    void rejectedAtAnyRunWithTheJitOff(@TempDir Path dir) throws IOException, InterruptedException {
        String output = ChildJvm.run(dir, ClassNameRootRunScenario.class, "-Dmvel2.disable.jit=true");

        assertTrue(output.contains(ClassNameRootRunScenario.DONE), output);
    }
}
