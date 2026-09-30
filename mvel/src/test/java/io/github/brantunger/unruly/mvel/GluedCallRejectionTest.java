package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

// #840: MVEL's analysis pass never returned for a call through a class named with its package with something glued to
// it, such as java.lang.Math.abs(1)x, so load() and validate() never returned either.
@DisplayName("a call through a class named with its package, with something glued to it, at load()")
class GluedCallRejectionTest {

    private static final String LOOP = "MVEL's analysis went round in a loop, as it does for a call through a class "
            + "named with its package with something glued to it, such as java.lang.Math.abs(1)x";

    // Each form MVEL's analysis never returns for, as an action. The first thirteen are also conditions.
    private static final List<String> SPINNING = List.of(
            "java.lang.Math.abs(1)x",
            "java.lang.Math.abs(1)2",
            "java.lang.Math.abs(1)(2)",
            "java.util.Objects.hash(1)x",
            "java.lang.Boolean.valueOf(true)x",
            "java.util.List.of().size()x",
            "java.util.List.of(1)[0]x",
            "java.lang.Math.abs(1) {1:2}",
            "java.util.List.of()[1,2] {1:2}",
            // Comments inside the chain.
            "java.lang.Math./*c*/abs(1)x",
            "java.lang.Math.//c\nabs(1)x",
            "java.lang.Math.abs(1)./*c*/toString()x",
            // A no-break space between a name and its call, with nothing after the call.
            "java.util.List.class\u00a0()",
            "java.lang.Math.abs(1)@",
            "java.lang.Math.abs(1)]",
            "java.lang.Math.abs(1) (2)",
            "java.lang.Math.abs (1)x",
            // A no-break space pasted between the method's name and its call, or before an operator.
            "java.lang.Math.abs\u00a0(2)",
            "java.lang.Math.abs(1)\u00a0> 1",
            // Java's whitespace, but not MVEL's.
            "java.lang.Math.abs(1)\u3000> 1",
            // MVEL's whitespace, and a word's character to Java, which MVEL trims off the token.
            "java.lang.Math.abs(1)\u0001x",
            "java.util.Map.Entry.comparingByKey()x",
            "java.lang.String.class.getName()x",
            "java.lang.Integer.MAX_VALUE(1)x",
            "java.lang.Math.abs(1)MAX_VALUE",
            "output.put(1, java.lang.Math.abs(1)x)",
            "if (java.lang.Math.abs(1)x) {}",
            "a = 1;\n  java.lang.Math.abs(1)x",
            "java.lang.Math.max (1, 2)){}",
            "java.lang.Math.abs(1)[0]{1:2}");

    private static RulesEngine<Map<String, Object>> engine() {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("glued").priority(1).condition(condition).action(action).build();
    }

    private static FactMap<Object> facts() {
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", new HashMap<>(Map.of("k", 1, "list", List.of("xyz"))));
        facts.setValue("s", "abc");
        facts.setValue("n", 5);
        facts.setValue("applicant", Map.of("tags", List.of("ab"), "name", "abc"));
        return facts;
    }

    /** Loads a rule MVEL's analysis goes round in a loop over, and checks validate() reports it the same way. */
    private static void rejected(String kind, Rule rule) {
        RulesEngine<Map<String, Object>> engine = engine();

        RuleCompilationException loaded = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule)));
        List<RuleCompilationException> validated = engine.validate(List.of(rule));

        assertEquals(kind + " for rule 'glued' failed to compile: " + LOOP, loaded.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, LOOP)), loaded.issues());
        assertEquals(1, validated.size());
        assertEquals(loaded.getMessage(), validated.get(0).getMessage());
        assertEquals(loaded.issues(), validated.get(0).issues());
    }

    @Test
    @DisplayName("a call glued to a word, a number, a group, a block or another character is rejected, not read for "
            + "ever")
    void spinningFormsRejected() throws InterruptedException {
        AtomicReference<String> current = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        // MVEL's analysis never checks for an interrupt, so a form it reads for ever takes its thread with it: a
        // daemon joined with a bound, so that fails the test instead, and the thread left doesn't keep the JVM alive.
        Thread loader = new Thread(() -> {
            try {
                for (int index = 0; index < SPINNING.size(); index++) {
                    String text = SPINNING.get(index);
                    current.set("the action " + text);
                    rejected("Action", rule("true", text));
                    if (index < 13) {
                        current.set("the condition " + text);
                        rejected("Condition", rule(text, "output.put('a', 1)"));
                    }
                }
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "glued-calls");
        loader.setDaemon(true);
        loader.start();
        try {
            loader.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(loader.isAlive(), () -> "load() or validate() didn't return for " + current.get());
        } finally {
            loader.interrupt();
        }
        if (failed.get() != null) {
            fail("failed for " + current.get(), failed.get());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("a call glued to a member of what it returns loads and runs as before: MVEL reads the member as if a "
            + "'.' were before it")
    void gluedMembersRun(String condition, boolean matched) {
        RulesEngine<Map<String, Object>> engine = engine();
        engine.load(List.of(rule(condition, "output.put('a', 1)")));

        // A first-match engine returns null when no rule matched.
        assertEquals(matched ? Map.of("a", 1) : null, engine.run(facts()));
    }

    static Stream<Arguments> gluedMembersRun() {
        return Stream.of(
                arguments("java.util.Objects.toString(s)length == 3", true),
                arguments("java.util.List.of()empty", true),
                arguments("java.lang.String.valueOf(n)empty", false),
                arguments("java.util.Arrays.asList(1, 2)empty", false),
                // A fact's chain glued to a member.
                arguments("applicant.tags.get(0)length() == 2", true),
                arguments("applicant.name.substring(1)length() == 2", true),
                arguments("m.list.get(0)length() == 3", true),
                arguments("s.toUpperCase()length == 3", true),
                // A control character MVEL counts as whitespace before a word operator.
                arguments("java.util.Arrays.asList(1) \u0001contains 1", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("an action close to a glued call loads and runs as before")
    void nearMissesRun(String action, Object expected) {
        RulesEngine<Map<String, Object>> engine = engine();
        engine.load(List.of(rule("true", action)));

        assertEquals(expected, engine.run(facts()).get("r"));
    }

    static Stream<Arguments> nearMissesRun() {
        return Stream.of(
                arguments("output.put('r', java.lang.Math.abs(-1) + n)", 6),
                arguments("output.put('r', java.lang.Math.abs(-1).toString())", "1"),
                // The forms inside string literals and comments.
                arguments("output.put('r', 'java.lang.Math.abs(1)x')", "java.lang.Math.abs(1)x"),
                arguments("output.put('r', \"java.lang.Math.abs(1)(2)\")", "java.lang.Math.abs(1)(2)"),
                arguments("// java.lang.Math.abs(1)x\noutput.put('r', 1)", 1),
                arguments("/* java.lang.Math.abs(1) {1:2} */ output.put('r', 1)", 1),
                arguments("output.put('r', '\u00a0' + java.lang.Math.abs(-1))", "\u00a01"),
                // Operators glued to the call, and line breaks and comments after it.
                arguments("output.put('r', java.lang.Math.abs(-1)==1)", true),
                arguments("output.put('r', java.lang.Math.abs(-1)>0&&true)", true),
                arguments("output.put('r', java.lang.Math.abs(-1)>0?1:2)", 1),
                arguments("output.put('r', java.lang.Math.abs(-1)\n== 1)", true),
                arguments("output.put('r', java.lang.Math.abs(-1)/* c */+ 1)", 2),
                arguments("output.put('r', java.lang.Math.abs(-1) // c\n+ 1)", 2),
                arguments("output.put('r', java.lang.Math.abs(-1));output.put('q', 1)", 1),
                // Groups, blocks and whitespace a chain may have.
                arguments("output.put('r', java.util.List.of(1, 2)[0])", 1),
                arguments("output.put('r', java.util.List.of(1).size() > 0 && true)", true),
                arguments("output.put('r', java.lang.Math.abs(\n-1\n)\n+ 1)", 2),
                arguments("output.put('r', java.lang.Math.abs (-1))", 1),
                arguments("output.put('r', java.util.Arrays.asList(1, 2).get(0))", 1),
                arguments("output.put('r', (java.lang.Integer) java.lang.Math.abs(-1))", 1),
                arguments("x = new java.lang.String[] {\"a\"}; output.put('r', x[0])", "a"),
                arguments("output.put('r', m.?get('k'))", 1),
                arguments("output.put('r', java.lang.Math.abs(-1) instanceof java.lang.Integer)", true),
                arguments("def f(a) { java.lang.Math.abs(a) }; output.put('r', f(-3))", 3),
                arguments("output.{ put('r', 2) }", 2));
    }

    @Test
    @DisplayName("a call through a class named without its package glued to a word still loads")
    void unqualifiedChainsLoad() {
        // MVEL's analysis returns for it, and it fails when it runs, as before.
        RulesEngine<Map<String, Object>> engine = engine();
        engine.load(List.of(rule("true", "Math.abs(1)x")));
        assertThrows(RuleExecutionException.class, () -> engine.run(facts()));
    }
}
