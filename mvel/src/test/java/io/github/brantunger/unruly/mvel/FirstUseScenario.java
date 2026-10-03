package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Run by {@link FirstUseClassInitializationTest} in a JVM that logs every class it initializes. It builds an engine
 * that finds MVEL with {@link java.util.ServiceLoader}, as an application does, naming it the default language so
 * the build prepares it, and prints {@link #BUILT}. It then
 * takes MVEL's first steps, each begun with a {@link #STEP} line: a load, a run, a load that fails nested in a run's
 * action, a {@code validate()} that fails, and a run whose condition calls a method that throws. It prints
 * {@link #RAN} if each ended as it should, or else {@link #UNEXPECTED} and the steps that didn't.
 */
final class FirstUseScenario {

    static final String BUILT = "SCENARIO built";
    static final String RAN = "SCENARIO ran";
    static final String UNEXPECTED = "SCENARIO unexpected: ";
    /** What the line that comes before each step begins with; the step's description follows. */
    static final String STEP = "SCENARIO step: ";

    private FirstUseScenario() {
    }

    /** A fact whose method an action calls, which loads a rule list that fails into another engine. */
    public static final class NestedLoad {
        private final RulesEngine<?> engine;
        private boolean failed;

        NestedLoad(RulesEngine<?> engine) {
            this.engine = engine;
        }

        /** Loads a rule with a syntax error, and records whether the load failed as it should. */
        public void load() {
            try {
                engine.load(List.of(Rule.builder().ruleName("broken").condition("x >").action("1").build()));
            } catch (RuleCompilationException expected) {
                failed = true;
            }
        }
    }

    /** A fact whose method a condition calls, which throws. */
    public static final class Failing {
        /**
         * Throws.
         *
         * @return Never returns
         */
        public boolean now() {
            throw new IllegalStateException("thrown by the scenario");
        }
    }

    public static void main(String[] args) {
        Supplier<Map<String, Object>> maps = HashMap::new;
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.firstMatch(maps)
                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME).build();
        RulesEngine<Map<String, Object>> nested = RulesEngineBuilder.firstMatch(maps).build();
        NestedLoad nestedLoad = new NestedLoad(nested);
        mark(BUILT);

        List<String> unexpected = new ArrayList<>();
        mark(STEP + "a load");
        engine.load(List.of(Rule.builder().ruleName("doubles").condition("x > 1 && name == 'a'")
                .action("output.put('k', x * 2);").build()));
        mark(STEP + "a run");
        if (!Map.of("k", 4).equals(engine.run(new FactMap<>(new Fact<>("x", 2), new Fact<>("name", "a"))))) {
            unexpected.add("a run");
        }

        mark(STEP + "a load that fails, nested in a run's action");
        engine.load(List.of(Rule.builder().ruleName("loads").condition("true").action("nested.load();").build()));
        engine.run(new FactMap<>(new Fact<>("nested", nestedLoad)));
        if (!nestedLoad.failed) {
            unexpected.add("a load that fails");
        }

        mark(STEP + "a validate() that fails");
        if (engine.validate(List.of(Rule.builder().ruleName("broken").condition("y >=").action("1").build()))
                .isEmpty()) {
            unexpected.add("a validate() that fails");
        }

        mark(STEP + "a run whose condition fails");
        engine.load(List.of(Rule.builder().ruleName("calls").condition("failing.now()").action("1").build()));
        try {
            engine.run(new FactMap<>(new Fact<>("failing", new Failing())));
            unexpected.add("a run whose condition fails");
        } catch (RuleExecutionException expected) {
            // As it should.
        }
        mark(unexpected.isEmpty() ? RAN : UNEXPECTED + unexpected);
        engine.close();
        nested.close();
    }

    // Flushed, so the line comes before what the JVM logs next.
    private static void mark(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
