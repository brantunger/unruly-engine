package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #857: MVEL goes round in a loop that never ends as it builds what reads a value the first time an expression runs,
 * for a call after a class named with its package and a non-ASCII space, such as {@code java.lang.String.class}, a
 * no-break space, then {@code (2)}. The expression passes validate() and loads, so each run is stopped once MVEL has
 * asked the parser configuration for its class loader more times than the expression allows, and the rule fails.
 *
 * <p>
 * Unfixed, each spinning run takes its thread for ever, and allocates about 1 GB a second while it keeps more and
 * more of it, so every run that can spin is on a daemon thread joined with a bound, and each test method spins at most
 * one thread: to prove the fix, run the methods that fail without it one at a time.
 * </p>
 */
@DisplayName("MVEL is stopped when it goes round in a loop while running an expression")
class RunTimeLoopTest {

    private static final String LOOP = "MVEL went round in a loop while running the expression, as it can for a call "
            + "after a class named with its package and a non-ASCII space, such as java.lang.String.class, then U+00A0 "
            + "(no-break space), then (2)";

    // The characters Java counts as a space and MVEL doesn't: every one above U+0020 Character.isSpaceChar accepts.
    private static final List<Character> SPACES = List.of((char) 0x00A0, (char) 0x1680, (char) 0x2000,
            (char) 0x2001, (char) 0x2002, (char) 0x2003, (char) 0x2004, (char) 0x2005, (char) 0x2006, (char) 0x2007,
            (char) 0x2008, (char) 0x2009, (char) 0x200A, (char) 0x2028, (char) 0x2029, (char) 0x202F, (char) 0x205F,
            (char) 0x3000);
    // No-break space, figure space, ideographic space and line separator.
    private static final List<Character> SOME_SPACES = List.of((char) 0x00A0, (char) 0x2007, (char) 0x3000,
            (char) 0x2028);

    // Each form MVEL goes round in a loop for as it runs, as the text before the space and the text after it.
    private static final List<List<String>> SPINNING = List.of(
            List.of("java.lang.String.class", "(2)"),
            List.of("java.lang.Integer.class", "(2)"),
            List.of("java.lang.String.class", "(2).toString()"),
            List.of("java.lang.String.class", " (2)"),
            List.of("(java.lang.String.class", "(2))"),
            List.of("[java.lang.String.class", "(2)]"),
            List.of("if (true) { java.lang.String.class", "(2) }"),
            List.of("for (int i = 0; i < 1; i++) { java.lang.String.class", "(2) }"),
            List.of("def f() { java.lang.String.class", "(2) }; f()"),
            List.of("new java.lang.StringBuilder(java.lang.String.class", "(2))"),
            List.of("x = java.lang.String.class", "(2); x"),
            List.of("java.lang.String.class", "(2)\tempty" + (char) 0x00A0 + ".toString()"));
    // The forms that are also conditions.
    private static final List<List<String>> SPINNING_CONDITIONS = List.of(
            List.of("java.lang.String.class", "(2)"),
            List.of("java.lang.String.class", "(2).toString()"),
            List.of("java.lang.String.class", "(2) == null"));

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").priority(1).condition(condition).action(action).build();
    }

    /** Records which callbacks a run makes, in order, and the rule's failure. */
    private static final class Callbacks implements RuleListener {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            calls.add("onError " + rule.getRuleName() + ": " + error.getMessage());
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            calls.add("onRunError: " + error.getMessage());
        }
    }

    /**
     * Runs an action on a daemon thread joined with a bound, so a run that never returns fails the test, and the
     * thread left doesn't keep the JVM alive.
     */
    private static void onDaemon(String name, AtomicReference<String> current, Executable body)
            throws InterruptedException {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                body.execute();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            assertFalse(thread.isAlive(), () -> "run() didn't return for " + current.get());
        } finally {
            thread.interrupt();
        }
        if (failed.get() != null) {
            fail("failed for " + current.get(), failed.get());
        }
    }

    /** Checks a rule loads, and that its run fails as MVEL went round in a loop, telling its listener so. */
    private static void failsTheRule(String kind, Rule rule) {
        Callbacks callbacks = new Callbacks();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(callbacks).build();

        assertEquals(List.of(), engine.validate(List.of(rule)));
        engine.load(List.of(rule));
        RuleExecutionException failure = assertThrows(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        String message = "Failed to " + kind + " for rule 'r': " + LOOP;
        assertEquals(message, failure.getMessage());
        assertEquals(List.of("onError r: " + message, "onRunError: " + message), callbacks.calls);
    }

    private static String spaced(List<String> form, char space) {
        return form.get(0) + space + form.get(1);
    }

    @Test
    @DisplayName("a call after a class named with its package and a non-ASCII space fails the rule, not runs for ever")
    void spinningFormsFailTheRule() throws InterruptedException {
        AtomicReference<String> current = new AtomicReference<>();
        onDaemon("spinning-forms", current, () -> {
            for (char space : SPACES) {
                current.set(String.format("the action with U+%04X", (int) space));
                failsTheRule("execute action", rule("true", spaced(SPINNING.get(0), space)));
            }
            for (char space : SOME_SPACES) {
                for (List<String> form : SPINNING) {
                    current.set(String.format("the action %s with U+%04X", form, (int) space));
                    failsTheRule("execute action", rule("true", spaced(form, space)));
                }
                for (List<String> form : SPINNING_CONDITIONS) {
                    current.set(String.format("the condition %s with U+%04X", form, (int) space));
                    failsTheRule("evaluate condition", rule(spaced(form, space), "output.put('a', 1)"));
                }
            }
        });
    }

    @Test
    @DisplayName("a rule that has gone round in a loop fails the same way in its next run")
    void loopedCopyFailsAgain() throws InterruptedException {
        AtomicReference<String> current = new AtomicReference<>("the action run twice");
        onDaemon("looped-copy", current, () -> {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .maxCopies(1).build();
            engine.load(List.of(rule("true", spaced(SPINNING.get(0), (char) 0x00A0))));
            RuleExecutionException first = assertThrows(RuleExecutionException.class,
                    () -> engine.run(new FactMap<>()));
            RuleExecutionException second = assertThrows(RuleExecutionException.class,
                    () -> engine.run(new FactMap<>()));

            assertEquals("Failed to execute action for rule 'r': " + LOOP, first.getMessage());
            assertEquals(first.getMessage(), second.getMessage());
            assertEquals(first.getCause().getClass(), second.getCause().getClass());
        });
    }

    @Test
    @DisplayName("a rule that went round in a loop in a branch runs when a later run doesn't take the branch")
    void loopedCopyRunsWithoutTheBranch() throws InterruptedException {
        AtomicReference<String> current = new AtomicReference<>("the branch taken, then not");
        onDaemon("looped-branch", current, () -> {
            // One copy, so the second run takes the copy the first went round in a loop in.
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .maxCopies(1).build();
            engine.load(List.of(rule("true", "if (x == 2) { " + spaced(SPINNING.get(0), (char) 0x00A0)
                    + " }; output.put('a', x)")));
            FactMap<Object> taken = new FactMap<>();
            taken.setValue("x", 2);
            FactMap<Object> notTaken = new FactMap<>();
            notTaken.setValue("x", 1);

            RuleExecutionException failure = assertThrows(RuleExecutionException.class, () -> engine.run(taken));
            assertEquals("Failed to execute action for rule 'r': " + LOOP, failure.getMessage());
            assertEquals(Map.of("a", 1), engine.run(notTaken));
        });
    }

    @Test
    @DisplayName("a rule that goes round in a loop after the run's deadline has passed fails, and doesn't time out")
    void loopPastTheDeadlineFailsTheRule() throws InterruptedException {
        // The listener waits in beforeExecute until the deadline has passed, and the engine checks for it only once
        // the action has returned or thrown.
        RuleListener late = new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        RunOptions timeout = RunOptions.withTimeoutOf(Duration.ofMillis(200));
        // A named guard for the set-up: an action that throws an exception past the deadline stops the run as timed
        // out, so what the loop throws decides it below.
        RulesEngine<Map<String, Object>> plain = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(late).build();
        plain.load(List.of(rule("true", "java.lang.String.class (2)")));
        RuleExecutionException stopped = assertThrows(RuleExecutionException.class,
                () -> plain.runWithResult(new FactMap<>(), timeout));
        assertInstanceOf(TimeoutException.class, stopped.getCause());

        AtomicReference<String> current = new AtomicReference<>("the action past the deadline");
        onDaemon("past-deadline", current, () -> {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .listener(late).build();
            engine.load(List.of(rule("true", spaced(SPINNING.get(0), (char) 0x00A0))));
            RuleExecutionException failure = assertThrows(RuleExecutionException.class,
                    () -> engine.runWithResult(new FactMap<>(), timeout));

            assertEquals("Failed to execute action for rule 'r': " + LOOP, failure.getMessage());
            assertFalse(failure.getCause() instanceof TimeoutException, () -> String.valueOf(failure.getCause()));
        });
    }

    @Test
    @DisplayName("a rule that fails as MVEL builds what reads a value keeps its own error, run after run")
    void failingRuleKeepsItsErrorAcrossManyRuns() throws Exception {
        // A named guard: MVEL asks for the class loader in every run of this expression, as each run fails where it
        // asks, so a limit for the expression's life, not for each run, would report a loop once it ran out.
        String text = "java.lang.String.class (2)";
        MvelExpression expression = MvelExpression.compile(text,
                new Imports(Set.of(), Set.of(), getClass().getClassLoader()));
        MvelSession session = new MvelSession();
        long runs = MvelAnalysis.classLoaderCallLimit(text.length()) + 100;

        for (long run = 0; run < runs; run++) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> expression.evaluate(LanguageTestContexts.evaluation(Map.of()), session));
            assertTrue(failure.getMessage().contains("unable to resolve method"), failure::getMessage);
        }
    }

    /** What the counting configuration throws, which MVEL can't catch where it asks, as it catches Exceptions. */
    private static final class Stop extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A plain configuration of MVEL's own that counts the calls, and stops the run after a number of them. */
    private static final class Counting extends ParserConfiguration {
        private static final long serialVersionUID = 1L;
        private long stopAfter = Long.MAX_VALUE;
        private long calls;

        @Override
        public ClassLoader getClassLoader() {
            if (++calls > stopAfter) {
                throw new Stop();
            }
            return super.getClassLoader();
        }
    }

    /** Compiles a text with a counting configuration, and counts the calls in each of its runs. */
    private static final class Counted {
        private final Counting configuration = new Counting();
        private final Serializable compiled;

        Counted(String text) {
            compiled = MVEL.compileExpression(text, new ParserContext(configuration));
        }

        long callsRunning(Map<String, Object> variables, long stopAfter) {
            configuration.calls = 0;
            configuration.stopAfter = stopAfter;
            try {
                MVEL.executeExpression(compiled, variables);
            } catch (RuntimeException | Stop e) {
                // Counted either way.
            }
            return configuration.calls;
        }
    }

    @Test
    @DisplayName("MVEL asks for the class loader on every round of the loop as it runs: an upgrade that changes this "
            + "fails here")
    void mvelAsksOnEveryRoundAtRunTime() throws InterruptedException {
        // The detector relies on this. If a new MVEL returns from the run instead, its loop may be fixed; if it stops
        // asking, the detector no longer stops the loop.
        AtomicReference<Long> calls = new AtomicReference<>();
        AtomicReference<String> current = new AtomicReference<>("MVEL alone");
        onDaemon("counted-run", current, () -> calls.set(new Counted(spaced(SPINNING.get(0), (char) 0x00A0))
                .callsRunning(Map.of(), 100_000)));
        assertTrue(calls.get() > 100_000, () -> calls.get() + " calls");
    }

    /** A list of 10,000 values of three types, which MVEL builds what reads each for again as the type changes. */
    private static List<Object> mixed() {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            values.add(i % 3 == 0 ? (Object) i : i % 3 == 1 ? (Object) ("s" + i) : (Object) List.of(i));
        }
        return values;
    }

    // Loops over many values, and calls through classes named with their packages, in them.
    private static final List<String> LOOPING = List.of(
            "foreach (e : l) { java.lang.String.valueOf(e.hashCode()) }",
            "foreach (e : l) { e.toString().length() }",
            "foreach (e : l) { e.getClass().getName() }",
            "foreach (e : l) { java.util.Objects.toString(e) }",
            "foreach (e : l) { new java.lang.StringBuilder(e.toString()).length() }",
            "foreach (e : l) { java.lang.Math.max(e.hashCode(), 0) }",
            "c = 0; foreach (e : l) { if (e instanceof java.lang.String) { c += e.length() } else { c += 1 } }; c",
            "t = 0; for (int i = 0; i < 10000; i++) { t += java.lang.Math.abs(i) }; t",
            "t = 0; for (int i = 0; i < 10000; i++) { t += java.lang.Integer.MAX_VALUE > i ? 1 : 0 }; t",
            "def f(v) { v == 0 ? 0 : java.lang.Math.abs(v) + f(v - 1) }; f(50)");

    @Test
    @DisplayName("MVEL asks for the class loader a few times in a run, however many rounds an expression's own loop "
            + "goes")
    void ordinaryExpressionsAskAFewTimes() {
        // A named guard for the limit: legitimate runs ask a handful of times, far below it.
        List<Object> values = mixed();
        for (String text : LOOPING) {
            Counted counted = new Counted(text);
            for (int run = 0; run < 60; run++) {
                long calls = counted.callsRunning(Map.of("l", values), Long.MAX_VALUE);
                assertTrue(calls < 100, () -> text + ": " + calls + " calls");
            }
        }
    }

    @Test
    @DisplayName("an action with loops over many values, and calls through classes named with their packages, runs")
    void ordinaryExpressionsStayWithinTheBudget() {
        // A named guard: the limit for each run doesn't stop an expression that loops over many values.
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("l", mixed());
        for (String text : LOOPING) {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .build();
            engine.load(List.of(rule("true", text + "; output.put('done', true)")));
            for (int run = 0; run < 60; run++) {
                assertEquals(Map.of("done", true), assertDoesNotThrow(() -> engine.run(facts), text), text);
            }
        }
    }

    /** Runs {@code count} copies of a text, joined by a separator. */
    private static String repeated(String text, int count, String separator) {
        return String.join(separator, java.util.Collections.nCopies(count, text));
    }

    /** Loads an action on an engine, with the thread's context class loader the rule list's while it loads. */
    private static RulesEngine<Map<String, Object>> loaded(String action, ClassLoader loader, String... imports) {
        AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();
        // Each run of the rule starts a run nested in it, which takes a copy of its own, compiled as it first runs.
        ThreadLocal<Boolean> nested = ThreadLocal.withInitial(() -> false);
        RuleListener nestedRun = new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                if (!nested.get()) {
                    nested.set(true);
                    try {
                        assertEquals(Map.of("r", false), self.get().run(facts()));
                    } finally {
                        nested.set(false);
                    }
                }
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports(imports).listener(nestedRun).build();
        self.set(engine);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            engine.load(List.of(rule("true", action)));
        } finally {
            thread.setContextClassLoader(previous);
        }
        return engine;
    }

    /** A map whose property a is the map itself, so a chain of any length reads it. */
    private static FactMap<Object> facts() {
        Map<String, Object> m = new HashMap<>();
        m.put("a", m);
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("s", "abc");
        facts.setValue("m", m);
        return facts;
    }

    @Test
    @DisplayName("long expressions whose first run compiles long chains of properties in arguments run, every time")
    void longExpressionsRun() {
        // A named guard for the limit: the first run of each copy compiles each argument that is a chain of
        // properties, asking about twice for each part, so these ask more than 10,000 times.
        ClassLoader own = getClass().getClassLoader();
        ClassLoader notJdk = new ClassLoader(own) {
        };
        List<RulesEngine<Map<String, Object>>> engines = List.of(
                loaded("r = s.equals(m" + ".a".repeat(5_100) + "); output.put('r', r)", own),
                loaded("r = " + repeated("s.equals(m" + ".a".repeat(90) + ")", 60, " || ") + "; output.put('r', r)",
                        own),
                loaded("r = " + repeated("s.equals(m.a.a.a.a.a)", 1_000, " || ") + "; output.put('r', r)", notJdk,
                        "java.util"));
        for (RulesEngine<Map<String, Object>> engine : engines) {
            for (int run = 0; run < 3; run++) {
                assertEquals(Map.of("r", false), engine.run(facts()));
            }
        }
    }
}
