package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact named after a class with no package a rule uses, such as {@code Unpackaged} for {@code Unpackaged.LIMIT}, is
 * rejected: MVEL would read the fact in the class's place.
 */
@DisplayName("a fact named after a class with no package that a rule uses is rejected")
class NoPackageClassFactTest {

    static final String NAME = "Unpackaged";

    /**
     * Rules that use the class {@code Unpackaged}, each with a name, a condition and an action, which put 5 in the
     * output under {@code r} when they read the class.
     */
    static final String[][] RULES = {
        {"static field in the condition", "Unpackaged.LIMIT == 5", "output.put('r', 5)"},
        {"static field in the action", "amount > 0", "output.put('r', Unpackaged.LIMIT)"},
        {"new in the condition", "new Unpackaged().v == 5", "output.put('r', 5)"},
        {"new in the action", "amount > 0", "output.put('r', new Unpackaged().v)"},
        {"static field in a def body", "amount > 0", "def g() { Unpackaged.LIMIT }; output.put('r', g())"},
        {"nested class", "Unpackaged.Inner.LIMIT == 5", "output.put('r', 5)"},
    };

    /** What a fact named {@code Unpackaged} holds in the class's place: each value is 7 through it. */
    static final Map<String, Object> CLASS_LIKE = Map.of("LIMIT", 7, "v", 7, "Inner", Map.of("LIMIT", 7));

    // MVEL's JIT, which the engine replaces as MVEL's default, is the default for these tests, as an application may
    // make it (#1161), so the runs about its threshold reach an accessor it compiled.
    @BeforeEach
    void jitOn() {
        MvelJit.on();
    }

    @AfterEach
    void jitOff() {
        MvelJit.off();
    }

    static String rejection(String name) {
        return "'" + name + "' cannot be used as a fact name: the rules use the class '" + name + "', and MVEL would "
                + "read the fact in the class's place";
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

    static Stream<Arguments> rulesAndCleanRuns() {
        return Arrays.stream(RULES).flatMap(rule -> Arrays.stream(NoPackageClassRunScenario.CLEAN_RUNS_FIRST)
                .mapToObj(cleanRunsFirst -> Arguments.of(rule[0], rule[1], rule[2], cleanRunsFirst)));
    }

    @ParameterizedTest(name = "{0}, after {3} runs without it")
    @MethodSource("rulesAndCleanRuns")
    @DisplayName("a run with the fact is rejected, and every run without it reads the class, with MVEL's JIT on")
    void rejectedAtAnyRun(String name, String condition, String action, int cleanRunsFirst) {
        String failure = NoPackageClassRunScenario.failure(condition, action, cleanRunsFirst);

        assertNull(failure, failure);
    }

    @Test
    @DisplayName("a run with the fact is rejected, and every run without it reads the class, with the engine's "
            + "default, MVEL's reflective optimizer")
    void rejectedAtAnyRunWithTheEnginesDefault(@TempDir Path dir) throws IOException, InterruptedException {
        String output = ChildJvm.run(dir, NoPackageClassRunScenario.class);

        assertTrue(output.contains(NoPackageClassRunScenario.DONE), output);
    }

    @Test
    @DisplayName("a declared fact is rejected when the rules load")
    void declaredFactRejectedAtLoad() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .fact(NAME, Object.class).build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule(RULES[0][1], RULES[0][2]))));

        assertEquals("Declared fact '" + NAME + "' can't be used: " + rejection(NAME), ex.getMessage());
    }

    @Test
    @DisplayName("a declared fact is a problem validate() reports")
    void declaredFactRejectedByValidate() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .fact(NAME, Object.class).build();

        List<RuleCompilationException> problems = engine.validate(List.of(rule(RULES[0][1], RULES[0][2])));

        assertEquals(List.of("Declared fact '" + NAME + "' can't be used: " + rejection(NAME)),
                problems.stream().map(RuleCompilationException::getMessage).toList());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "Unpackaged|amount < Unpackaged.LIMIT|the rules use the class 'Unpackaged'",
        "java|amount < java.lang.Integer.MAX_VALUE|the rules use a class whose package starts with 'java'",
    })
    @DisplayName("with strong typing, a declared fact is rejected when the rules load, with its own reason")
    void declaredFactRejectedWithStrongTyping(String name, String condition, String reason) {
        RulesEngine<MvelStrongTypingTest.Decision> engine = RulesEngineBuilder
                .allMatches(MvelStrongTypingTest.Decision::new)
                .outputType(MvelStrongTypingTest.Decision.class)
                .fact("amount", Integer.class)
                .fact(name, Integer.class)
                .requireDeclaredFacts()
                .option("mvel", "strongTyping", "true")
                .build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule(condition, "output.score = 1"))));

        assertEquals("Declared fact '" + name + "' can't be used: '" + name + "' cannot be used as a fact name: "
                + reason + ", and MVEL would read the fact in the class's place", ex.getMessage());
    }

    @Test
    @DisplayName("a rule that uses a class with a package and one without keeps a reason for each")
    void packageRootKeepsItsReason() {
        RulesEngine<Map<String, Object>> engine = engine("amount < java.lang.Integer.MAX_VALUE",
                "output.put('r', Unpackaged.LIMIT)");

        IllegalArgumentException root = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact("java", 1)));
        IllegalArgumentException noPackage = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact(NAME, CLASS_LIKE)));

        assertEquals(ClassNameRootFactTest.rejection("java"), root.getMessage());
        assertEquals(rejection(NAME), noPackage.getMessage());
        assertEquals(Map.of("r", 5), engine.run(amount()));
    }

    @Test
    @DisplayName("an imported class with no package keeps the class reason: MVEL never looks it up")
    void importedClassKeepsTheClassReason() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports(NAME).build();
        engine.load(List.of(rule(RULES[0][1], RULES[0][2])));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(withFact(NAME, CLASS_LIKE)));

        assertEquals("'" + NAME + "' cannot be used as a fact name: MVEL reads it as a keyword or class name, so "
                + "rules would never see the fact", ex.getMessage());
        assertEquals(Map.of("r", 5), engine.run(amount()));
    }

    @Test
    @DisplayName("a fact named like a class that doesn't exist is accepted, and the rule reads the fact")
    void noSuchClassAccepted() {
        RulesEngine<Map<String, Object>> engine = engine("NoSuchUnpackaged.LIMIT == 7", "output.put('r', 7)");

        assertEquals(Map.of("r", 7), engine.run(withFact("NoSuchUnpackaged", CLASS_LIKE)));
    }

    @Test
    @DisplayName("a rule that reads the fact and never uses the class leaves the name a fact's")
    void classUnusedAccepted() {
        RulesEngine<Map<String, Object>> engine = engine(NAME + " == 7", "output.put('r', " + NAME + ")");

        assertEquals(Map.of("r", 7), engine.run(withFact(NAME, 7)));
    }

    @Test
    @DisplayName("a rule list that doesn't use the class leaves the name a fact's, in the same engine")
    void otherRuleListAccepts() {
        RulesEngine<Map<String, Object>> engine = engine(RULES[0][1], RULES[0][2]);
        assertThrows(IllegalArgumentException.class, () -> engine.run(withFact(NAME, CLASS_LIKE)));

        engine.load(List.of(rule(NAME + " == 7", "output.put('r', 7)")));

        assertEquals(Map.of("r", 7), engine.run(withFact(NAME, 7)));
    }
}
