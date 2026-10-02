package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run by {@link FirstRunClassInitializationTest} in a JVM that logs every class it initializes. It builds every engine
 * and prints {@link #BUILT}, loads their rules and prints {@link #LOADED}, then gives each its first run, in steps
 * that each begin with a {@link #STEP} line, and prints {@link #RAN} if every run ended as it should. Between them the
 * runs take these paths: a map output, and a bean output with a setter that takes a primitive and one that takes a
 * {@code String}; a fact declared with a primitive type, a fact of the wrong type for its declaration and a declared
 * fact missing from an engine that requires them all; every listener callback; a condition that fails; an action that
 * writes to its read-only facts; a language that calls {@link FactProperties#toData}; a fatal error from a nested run;
 * and a nested run that passes its deadline.
 */
final class FirstRunScenario {

    static final String BUILT = "SCENARIO built";
    static final String LOADED = "SCENARIO loaded";
    static final String RAN = "SCENARIO ran";
    /** What the line that comes before each step of the runs begins with; the step's description follows. */
    static final String STEP = "SCENARIO step: ";

    private FirstRunScenario() {
    }

    /** An output object the engine writes through setters, one taking a primitive and one taking a reference. */
    public static final class Output {
        private int count;
        private String name;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Overrides every callback, so the runs call each one. */
    private static final class Listener implements RuleListener {
        @Override
        public void beforeRun(RunContext run) {
            // Nothing to do: being called is what counts.
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            // As above.
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            // As above.
        }

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            // As above.
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            // As above.
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            // As above.
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            // As above.
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            // As above.
        }
    }

    public static void main(String[] args) {
        RulesEngine<Map<String, Object>> map = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new Listener()).build();
        RulesEngine<Output> bean = RulesEngineBuilder.allMatches(Output::new)
                .language(new ToyExpressionLanguage("toy", true)).build();
        RulesEngine<Map<String, Object>> declared = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).fact("n", long.class).build();
        RulesEngine<Map<String, Object>> failing = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new Listener()).build();
        RulesEngine<Map<String, Object>> toData = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    FactProperties.toData(Map.of("a", 1), 2);
                    return ActionResult.done();
                })).build();
        RulesEngine<Map<String, Object>> fatal = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    throw new OutOfMemoryError("thrown by the scenario");
                })).build();
        RulesEngine<Map<String, Object>> outer = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    fatal.run(new FactMap<>());
                    return ActionResult.done();
                })).build();
        RulesEngine<Map<String, Object>> strict = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).fact("n", long.class).requireDeclaredFacts().build();
        RulesEngine<Map<String, Object>> writing = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    try {
                        context.facts().put("x", 1);
                        return ActionResult.done();
                    } catch (UnsupportedOperationException expected) {
                        return ActionResult.set(Map.of("rejected", true));
                    }
                })).build();
        RulesEngine<Map<String, Object>> slow = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    spin();
                    return ActionResult.done();
                })).build();
        RulesEngine<Map<String, Object>> overrun = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    slow.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofMillis(1)));
                    return ActionResult.done();
                })).build();
        mark(BUILT);

        map.load(List.of(Rule.builder().ruleName("matches").condition("true").action("put k 1").build(),
                Rule.builder().ruleName("doesn't").condition("false").action("put j 1").build()));
        bean.load(List.of(Rule.builder().ruleName("counts").condition("true").action("put count 1 ; put name 'bob'")
                .build()));
        failing.load(List.of(Rule.builder().ruleName("broken").condition("missing.value > 1").action("put k 1")
                .build()));
        for (RulesEngine<?> stub : List.of(toData, fatal, outer, writing, overrun)) {
            stub.load(List.of(Rule.builder().ruleName("acts").condition("x").action("x").build()));
        }
        slow.load(List.of(Rule.builder().ruleName("first").priority(2).condition("x").action("x").build(),
                Rule.builder().ruleName("second").priority(1).condition("x").action("x").build()));
        mark(LOADED);

        // Each step is marked, so a class one initializes can be told by the step it came after. Every step runs, even
        // after one that didn't end as it should.
        mark(STEP + "a bean output, with setters taking an int and a String");
        Output written = bean.run(new FactMap<>());
        boolean asExpected = written.getCount() == 1 && "bob".equals(written.getName());
        mark(STEP + "a map output, with every listener callback");
        asExpected &= Map.of("k", 1).equals(map.run(new FactMap<>()));
        mark(STEP + "a condition that fails");
        asExpected &= throwsOnRun(failing, new FactMap<>(), RuleExecutionException.class);
        mark(STEP + "an action that calls FactProperties.toData");
        asExpected &= toData.run(new FactMap<>()).isEmpty();
        mark(STEP + "a fatal error from a nested run");
        asExpected &= throwsOnRun(outer, new FactMap<>(), OutOfMemoryError.class);
        mark(STEP + "an action that writes to its read-only facts");
        asExpected &= Map.of("rejected", true).equals(writing.run(new FactMap<>()));
        mark(STEP + "a nested run that passes its deadline");
        asExpected &= throwsOnRun(overrun, new FactMap<>(), RuleExecutionException.class);
        mark(STEP + "a fact declared with a primitive type, loaded and widened");
        asExpected &= loadsAndRunsDeclared(declared);
        mark(STEP + "a fact of the wrong type, and a declared fact left out, with requireDeclaredFacts()");
        asExpected &= rejectsFacts(strict);
        if (asExpected) {
            mark(RAN);
        }
        for (RulesEngine<?> engine : List.of(map, bean, declared, failing, toData, fatal, outer, strict, writing, slow,
                overrun)) {
            engine.close();
        }
    }

    // Loaded only after the bean run: loading rules for a fact declared with a primitive type uses the class that
    // widens primitives, which the bean run, with no fact declared, must be the first to use, as in an application
    // that declares none. Its own first run then widens an Integer to a long.
    private static boolean loadsAndRunsDeclared(RulesEngine<Map<String, Object>> declared) {
        declared.load(List.of(Rule.builder().ruleName("positive").condition("n > 0").action("put n n").build()));
        return Map.of("n", 1L).equals(declared.run(new FactMap<>(new Fact<>("n", 1))));
    }

    // Loaded, as declared is, after the bean run. A fact of the wrong type is rejected, and so is a run that leaves a
    // declared fact out.
    private static boolean rejectsFacts(RulesEngine<Map<String, Object>> strict) {
        strict.load(List.of(Rule.builder().ruleName("positive").condition("n > 0").action("put n n").build()));
        return throwsOnRun(strict, new FactMap<>(new Fact<>("n", "one")), IllegalArgumentException.class)
                && throwsOnRun(strict, new FactMap<>(), IllegalArgumentException.class);
    }

    private static boolean throwsOnRun(RulesEngine<?> engine, FactStore<?> facts,
                                       Class<? extends Throwable> expected) {
        try {
            engine.run(facts);
            return false;
        } catch (RuntimeException | Error e) {
            return expected.isInstance(e);
        }
    }

    // Waits without sleeping, as Thread.sleep() has the JDK initialize a class of its own, until well past the 1 ms
    // the nested run may take, so the run stops at its next check.
    private static void spin() {
        long end = System.nanoTime() + Duration.ofMillis(20).toNanos();
        while (System.nanoTime() - end < 0) {
            Thread.onSpinWait();
        }
    }

    // Flushed, so the line comes before what the JVM logs next.
    private static void mark(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
