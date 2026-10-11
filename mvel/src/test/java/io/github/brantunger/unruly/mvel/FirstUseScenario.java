package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Run by {@link FirstUseClassInitializationTest} in a JVM that logs every class it initializes. It builds six
 * engines: five that find MVEL with {@link java.util.ServiceLoader}, as an application does, four of them naming it
 * the default language so the build prepares it, and one in a language of the test's own; and prints
 * {@link #BUILT}. It then takes MVEL's first steps, each begun with a {@link #STEP} line:
 * a load of a condition that compares with {@code null}, a load of an action that calls a method MVEL can't find on
 * the output's type, a load and a run of an action that assigns, a load of a condition with a {@code BigDecimal}
 * literal, a load of one that reads a value's class, a load and a run of a rule that calls {@code getClass()} and
 * {@code Class.forName}, a load and a run, in a language of the test's own, whose action writes to an output that
 * overrides a generic setter, a load, a run, a load and a run of a rule that reads a fact's property, loads and runs of
 * an inline list, of
 * {@code new} and of {@code soundslike}, a load that fails nested in a run's action, a {@code validate()} that fails, a
 * load that fails, not nested, a load and a run that each ask for the class loader many times, a load in which MVEL
 * goes round in a loop, a load and a run of a rule it goes round in a loop running, a load of a chain MVEL looks up as
 * a nested class with too many parts, loads of rules that call a method that throws, a run whose condition calls it and
 * one whose action does, and last, {@value #JIT_RUNS} more runs of the rule that reads a property, enough for MVEL's
 * JIT, if it's on, to compile the property's accessor: MVEL compiles one once more than 50 runs have used it within 100
 * ms. It prints {@link #RAN} if each ended as it should, or else {@link #UNEXPECTED} and the steps that didn't.
 */
final class FirstUseScenario {

    static final String BUILT = "SCENARIO built";
    static final String RAN = "SCENARIO ran";
    static final String UNEXPECTED = "SCENARIO unexpected: ";
    /** What the line that comes before each step begins with; the step's description follows. */
    static final String STEP = "SCENARIO step: ";
    /** The step whose load is the first to compile a {@code null} literal and a comparison (#1103). */
    static final String NULL_COMPARISON = "a load of a condition that compares with null";
    /**
     * The step whose load is the first to compile a call of a method MVEL can't find on the type it gives the value,
     * {@code Object} for the output, as no output type is declared (#1103).
     */
    static final String UNFOUND_CALL = "a load of an action that calls a method MVEL can't find on the output's type";
    /** The step whose load is the first to compile an assignment (#1103). */
    static final String ASSIGNMENT_LOAD = "a load of an action that assigns";
    /** The step whose run is the first to run an assignment (#1103). */
    static final String ASSIGNMENT_RUN = "a first run of an action that assigns";
    /**
     * The step whose load is the first to compile a {@code BigDecimal} literal, which MVEL's analysis makes a
     * {@code BigDecimal} of (#1115).
     */
    static final String BIG_DECIMAL_LOAD = "a load of a condition with a BigDecimal literal";
    /**
     * The step whose load is the first to read the generic return type of a method, {@code getClass()}'s, which MVEL's
     * analysis reads for the method a rule calls (#1115).
     */
    static final String GENERIC_RETURN_LOAD = "a load of a condition that reads a value's class";
    /** The step that loads the rule that calls {@code getClass()} and {@code Class.forName}. */
    static final String ANNOTATED_CALLS_LOAD = "a load of a rule that calls getClass() and Class.forName";
    /**
     * The step whose run is the first to call a method of the JDK's with a runtime annotation reflectively, as MVEL
     * calls a method: {@code getClass()}, which is native and an intrinsic, and {@code Class.forName}, which is
     * caller-sensitive (#1115).
     */
    static final String ANNOTATED_CALLS_RUN = "a first run of a rule that calls getClass() and Class.forName";
    /**
     * The step whose run is the first to write, through the default output writer, to an output that overrides a
     * generic setter, so the writer reads the generic setter's parameter type and its type variable's declaration
     * (#1115). In a language of the test's own, as MVEL's actions change the output themselves.
     */
    static final String GENERIC_SETTER_WRITE = "a load and a run that write to an output that overrides a generic "
            + "setter";
    /** The step whose run is the first to read a property through its getter. */
    static final String PROPERTY_RUN = "a first run of the rule that reads a property";
    /** The step that loads and runs the first inline list. */
    static final String INLINE_LIST = "a load and a run of an inline list";
    /** The step that loads and runs the first new. */
    static final String NEW_OBJECT = "a load and a run of new";
    /** The step that loads and runs the first soundslike. */
    static final String SOUNDSLIKE = "a load and a run of soundslike";
    /** The step whose failing load is the first to log a message, if the engine logs. */
    static final String FAILING_NESTED_LOAD = "a load that fails, nested in a run's action";
    /** The step whose load is the first to fail to compile, not nested in a run (#1097). */
    static final String LOAD_FAILS = "a load that fails to compile";
    /** The step whose load is the first to fail for a condition that assigns, as {@code x = 1} (#1097). */
    static final String CONDITION_ASSIGNS = "a load that fails, a condition that assigns";
    /** The step whose load is the first to fail for a class called like a method, as {@code ArrayList(y)} (#1097). */
    static final String CLASS_CALLED = "a load that fails, a class called like a method";
    /** The step whose load is the first to fail for an import with too many parts (#1097). */
    static final String IMPORT_TOO_LARGE = "a load that fails, an import with too many parts";
    /** The step whose load is the first to fail for a package import too far into the text for MVEL to read (#1097). */
    static final String PACKAGE_IMPORT_UNREAD = "a load that fails, a package import MVEL can't read";
    /**
     * The step whose load is the first whose MVEL analysis asks for the class loader more times than
     * {@code CallSites.UNCOUNTED_CALLS}, which walks the stack (#1099).
     */
    static final String MANY_CALLS_LOAD = "a load whose analysis asks for the class loader many times";
    /**
     * The step whose run is the first to ask for the class loader more times than {@code CallSites.UNCOUNTED_CALLS},
     * in a copy's first run, which walks the stack (#1099).
     */
    static final String MANY_CALLS_RUN = "a load and a run that asks for the class loader many times";
    /** The step whose load is the first to fail as MVEL's analysis goes round in a loop (#1099). */
    static final String ANALYSIS_LOOP = "a load that fails, MVEL's analysis goes round in a loop";
    /**
     * The step that loads the rule MVEL goes round in a loop running, whose load reads the generic type of a class's
     * class, which no step before it reads (#1099).
     */
    static final String RUN_LOOP_LOAD = "a load of a rule MVEL goes round in a loop running";
    /** The step whose run is the first to fail as MVEL goes round in a loop (#1099). */
    static final String RUN_LOOP = "a run that fails, MVEL goes round in a loop";
    /**
     * The step whose load is the first in which MVEL's lookup of a nested class asks for a name with a {@code $} and
     * too many parts, which walks the stack (#1099).
     */
    static final String NESTED_NAME_TOO_LARGE = "a load of a chain MVEL looks up as a nested class with too many parts";
    /** The step whose validate() is the first to find a rule that fails to compile. */
    static final String VALIDATE_FAILS = "a validate() that fails";
    /** The step whose run is the first whose MVEL condition fails. */
    static final String CONDITION_FAILS = "a run whose condition fails";
    /** The step whose run is the first whose MVEL action fails. */
    static final String ACTION_FAILS = "a run whose action fails";
    /**
     * The system property that, set to {@code true}, has the scenario take the steps of a load and a
     * {@code validate()} that fail after the runs whose condition and action fail rather than before them, so none
     * of the classes those use first is loaded before the failing runs, where it would hide that a failing run would
     * otherwise be the first to load it.
     */
    static final String FAILURES_FIRST = "unruly.scenario.failuresFirst";
    /** The last step's description: the runs that MVEL's JIT, if it's on, compiles the property's accessor in. */
    static final String JIT = "runs of the rule that reads a property, enough for MVEL's JIT";
    /** How many runs the last step makes: many more than the 51 that MVEL's JIT compiles an accessor after. */
    static final int JIT_RUNS = 2_000;

    // The actions of the loads that fail for an import, built when the scenario's class is initialized: this test
    // fixture is compiled with javac's default string concatenation, whose first use would initialize the JDK's
    // classes in a step. An import of 65 parts, one more than an import may have, and one whose last '.' is at index
    // 32,768, which MVEL keeps in a short (see MvelCompileErrors).
    private static final String TOO_MANY_PARTS = "import " + "a.".repeat(64) + "a.*; x = 1";
    private static final String IMPORT_TOO_FAR = " ".repeat(32_768 - "import java.util".length())
            + "import java.util.*; output.put('k', new ArrayList().size())";
    // The rules that ask for the class loader many times (#1099), built here for the same reason. A chain in 12 levels
    // of brackets, which MVEL's analysis asks more than 100 times for; a chain of 70 parts passed as an argument, which
    // a copy's first run asks more than 100 times for; a call with a letter glued to it, and one after a class named
    // with its package and a no-break space, which MVEL goes round in a loop over, in its analysis and as it runs; and
    // a chain of 90 parts, which MVEL's lookup of a nested class asks for with a '$' and 90 parts. CallSitesTest
    // checks that the first two ask more than CallSites.UNCOUNTED_CALLS times.
    static final String WRAPPED_CHAIN = "(".repeat(12) + "m" + ".a".repeat(12) + ")".repeat(12) + " == m";
    static final String CHAIN_ARGUMENT = "s.equals(m" + ".a".repeat(70) + ")";
    private static final String ANALYSIS_LOOPS = "java.lang.Math.abs(1)x";
    private static final String RUN_LOOPS = "java.lang.String.class\u00a0(2)";
    private static final String LONG_CHAIN = "a" + ".a".repeat(89) + " == 1";
    private static final String LOOP_MESSAGE = "went round in a loop";

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

    /**
     * Declares a setter with a type variable, which {@link IterableBox} overrides.
     *
     * @param <T> The type of its content
     */
    public static class Box<T> {
        Object content;

        /**
         * Sets the content.
         *
         * @param content The content
         */
        public void setContent(T content) {
            this.content = content;
        }
    }

    /** An output that overrides a generic setter for an {@link Iterable}, which the compiler adds a bridge for. */
    public static final class IterableBox extends Box<Iterable<?>> {
        @Override
        public void setContent(Iterable<?> content) {
            this.content = content;
        }
    }

    /** A fact with a property, which a rule reads through its getter. */
    public static final class Applicant {
        /**
         * Returns the applicant's age.
         *
         * @return 30
         */
        public int getAge() {
            return 30;
        }
    }

    /** A fact whose method a condition or an action calls, which throws. */
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
        // The engine the rule that reads a property stays loaded in, for the last step.
        RulesEngine<Map<String, Object>> reads = RulesEngineBuilder.firstMatch(maps)
                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME).build();
        RulesEngine<Map<String, Object>> nested = RulesEngineBuilder.firstMatch(maps).build();
        // The engine whose rule's action fails.
        RulesEngine<Map<String, Object>> acts = RulesEngineBuilder.firstMatch(maps)
                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME).build();
        // The engine whose rules import java.util, so one can call ArrayList like a method.
        RulesEngine<Map<String, Object>> imported = RulesEngineBuilder.firstMatch(maps)
                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME).imports("java.util").build();
        // The engine whose action writes to an output that overrides a generic setter.
        Supplier<IterableBox> boxes = IterableBox::new;
        RulesEngine<IterableBox> writes = RulesEngineBuilder.firstMatch(boxes)
                .language(new ToyExpressionLanguage(ToyExpressionLanguage.LANGUAGE_NAME, true))
                .defaultLanguage(ToyExpressionLanguage.LANGUAGE_NAME).build();
        NestedLoad nestedLoad = new NestedLoad(nested);
        List<Integer> items = List.of(1);
        mark(BUILT);

        List<String> unexpected = new ArrayList<>();
        // First, so no other step compiles a null literal, a comparison, a call MVEL can't find or an assignment before
        // them (#1103).
        mark(STEP + NULL_COMPARISON);
        engine.load(List.of(rule("null", "t != null", "1")));
        mark(STEP + UNFOUND_CALL);
        engine.load(List.of(rule("put", "true", "output.put('r', 1);")));
        mark(STEP + ASSIGNMENT_LOAD);
        engine.load(List.of(rule("assigns", "true", "x = 1;")));
        mark(STEP + ASSIGNMENT_RUN);
        if (!engine.run(new FactMap<>()).isEmpty()) {
            unexpected.add(ASSIGNMENT_RUN);
        }
        // Before any other step, so none of them reads a generic type, calls a method of the JDK's with a runtime
        // annotation or makes a BigDecimal first (#1115).
        mark(STEP + BIG_DECIMAL_LOAD);
        engine.load(List.of(rule("decimal", "x > 1.5B", "output.put('k', 1);")));
        mark(STEP + GENERIC_RETURN_LOAD);
        engine.load(List.of(rule("class", "s.getClass().getName() == 'java.lang.String'", "output.put('k', 1);")));
        mark(STEP + ANNOTATED_CALLS_LOAD);
        engine.load(List.of(rule("calls", "s.getClass() == java.lang.Class.forName('java.lang.String')",
                "output.put('k', 1);")));
        mark(STEP + ANNOTATED_CALLS_RUN);
        if (!Map.of("k", 1).equals(engine.run(new FactMap<>(new Fact<>("s", "a"))))) {
            unexpected.add(ANNOTATED_CALLS_RUN);
        }
        mark(STEP + GENERIC_SETTER_WRITE);
        writes.load(List.of(rule("writes", "true", "put content items")));
        if (!items.equals(writes.run(new FactMap<>(new Fact<>("items", items))).content)) {
            unexpected.add(GENERIC_SETTER_WRITE);
        }
        mark(STEP + "a load");
        engine.load(List.of(Rule.builder().ruleName("doubles").condition("x > 1 && name == 'a'")
                .action("output.put('k', x * 2);").build()));
        mark(STEP + "a run");
        if (!Map.of("k", 4).equals(engine.run(new FactMap<>(new Fact<>("x", 2), new Fact<>("name", "a"))))) {
            unexpected.add("a run");
        }

        mark(STEP + "a load of a rule that reads a property");
        reads.load(List.of(rule("adult", "ap.age > 18", "output.put('k', ap.age);")));
        mark(STEP + PROPERTY_RUN);
        FactMap<Applicant> applicant = new FactMap<>(new Fact<>("ap", new Applicant()));
        if (!Map.of("k", 30).equals(reads.run(applicant))) {
            unexpected.add("a run of a rule that reads a property");
        }
        mark(STEP + INLINE_LIST);
        loadAndRun(engine, rule("list", "[1, 2, 3].size() == 3", "output.put('k', 1);"), unexpected);
        mark(STEP + NEW_OBJECT);
        loadAndRun(engine, rule("new", "new java.util.ArrayList().isEmpty()", "output.put('k', 1);"), unexpected);
        mark(STEP + SOUNDSLIKE);
        loadAndRun(engine, rule("sound", "'robert' soundslike 'rupert'", "output.put('k', 1);"), unexpected);

        boolean failuresFirst = Boolean.getBoolean(FAILURES_FIRST);
        if (!failuresFirst) {
            failsToLoad(engine, imported, nestedLoad, unexpected, false);
        }

        // Loaded in a step of their own, so the steps that fail do nothing else.
        mark(STEP + "loads of rules that call a method that throws");
        engine.load(List.of(Rule.builder().ruleName("calls").condition("failing.now()").action("1").build()));
        acts.load(List.of(Rule.builder().ruleName("acts").condition("true").action("failing.now();").build()));
        mark(STEP + CONDITION_FAILS);
        failsToRun(engine, unexpected, CONDITION_FAILS);
        mark(STEP + ACTION_FAILS);
        failsToRun(acts, unexpected, ACTION_FAILS);
        if (failuresFirst) {
            failsToLoad(engine, imported, nestedLoad, unexpected, true);
        }

        // Last, so the property's accessor is the one its first run made, and only the JIT is left to initialize.
        mark(STEP + JIT);
        for (int i = 0; i < JIT_RUNS; i++) {
            reads.run(applicant);
        }
        mark(unexpected.isEmpty() ? RAN : UNEXPECTED + unexpected);
        engine.close();
        reads.close();
        nested.close();
        acts.close();
        imported.close();
        writes.close();
    }

    // A load that fails, nested in a run's action, and a validate() that fails, in steps of their own; and loads that
    // fail, not nested, each for a reason of its own, first when failures come first, so no step before them compiles
    // an expression that fails, and otherwise last, so the nested load stays the first to log a message (#1097).
    private static void failsToLoad(RulesEngine<Map<String, Object>> engine, RulesEngine<Map<String, Object>> imported,
                                    NestedLoad nestedLoad, List<String> unexpected, boolean failuresFirst) {
        if (failuresFirst) {
            failsToCompile(engine, imported, unexpected);
            walksTheStack(engine, unexpected);
        }
        mark(STEP + FAILING_NESTED_LOAD);
        engine.load(List.of(Rule.builder().ruleName("loads").condition("true").action("nested.load();").build()));
        engine.run(new FactMap<>(new Fact<>("nested", nestedLoad)));
        if (!nestedLoad.failed) {
            unexpected.add("a load that fails");
        }

        mark(STEP + VALIDATE_FAILS);
        if (engine.validate(List.of(Rule.builder().ruleName("broken").condition("y >=").action("1").build()))
                .isEmpty()) {
            unexpected.add(VALIDATE_FAILS);
        }
        if (!failuresFirst) {
            failsToCompile(engine, imported, unexpected);
            walksTheStack(engine, unexpected);
        }
    }

    // Loads of a rule that fails to compile, each in a step of its own, for each way MVEL's compile errors are
    // described: a condition that assigns, which the engine rejects before MVEL compiles it; a syntax error; a class
    // called like a method; an import with too many parts; and a package import whose last '.' is too far into the
    // text for MVEL to read (#1097). The condition that assigns comes first, as every failed load creates what it does.
    private static void failsToCompile(RulesEngine<Map<String, Object>> engine,
                                       RulesEngine<Map<String, Object>> imported, List<String> unexpected) {
        failsToLoadRule(engine, CONDITION_ASSIGNS, rule("assigns", "x = 1", "1"), unexpected);
        failsToLoadRule(engine, LOAD_FAILS, rule("broken", "x >", "1"), unexpected);
        failsToLoadRule(imported, CLASS_CALLED, rule("called", "true", "x = ArrayList(y)"), unexpected);
        failsToLoadRule(engine, IMPORT_TOO_LARGE, rule("imports", "true", TOO_MANY_PARTS), unexpected);
        failsToLoadRule(engine, PACKAGE_IMPORT_UNREAD, rule("far", "true", IMPORT_TOO_FAR), unexpected);
    }

    // Loads and runs of rules that walk the stack, each in a step of its own (#1099): MVEL's loop detection walks it
    // once an analysis pass or a run has asked for the class loader more than CallSites.UNCOUNTED_CALLS times, and
    // the rule list's class loader for a name with a '$' and too many parts. The valid load and run that ask many
    // times come first, so they are the first to walk the stack each way, then the loops, which are stopped once they
    // have asked too many times from one place, and last the long chain, the first lookup of such a name.
    private static void walksTheStack(RulesEngine<Map<String, Object>> engine, List<String> unexpected) {
        Map<String, Object> m = new HashMap<>();
        m.put("a", m);
        mark(STEP + MANY_CALLS_LOAD);
        engine.load(List.of(rule("wrapped", WRAPPED_CHAIN, "1")));
        mark(STEP + MANY_CALLS_RUN);
        engine.load(List.of(rule("argument", CHAIN_ARGUMENT, "output.put('k', 1);")));
        if (!Map.of("k", 1).equals(engine.run(new FactMap<>(new Fact<>("s", m), new Fact<>("m", m))))) {
            unexpected.add(MANY_CALLS_RUN);
        }
        mark(STEP + ANALYSIS_LOOP);
        try {
            engine.load(List.of(rule("loops", "true", ANALYSIS_LOOPS)));
            unexpected.add(ANALYSIS_LOOP);
        } catch (RuleCompilationException expected) {
            if (!expected.getMessage().contains(LOOP_MESSAGE)) {
                unexpected.add(ANALYSIS_LOOP);
            }
        }
        mark(STEP + RUN_LOOP_LOAD);
        engine.load(List.of(rule("spins", "true", RUN_LOOPS)));
        mark(STEP + RUN_LOOP);
        try {
            engine.run(new FactMap<>());
            unexpected.add(RUN_LOOP);
        } catch (RuleExecutionException expected) {
            if (!expected.getMessage().contains(LOOP_MESSAGE)) {
                unexpected.add(RUN_LOOP);
            }
        }
        mark(STEP + NESTED_NAME_TOO_LARGE);
        engine.load(List.of(rule("chain", LONG_CHAIN, "1")));
    }

    // Loads the rule in a step of its own, and records the step unless the load failed as it should.
    private static void failsToLoadRule(RulesEngine<Map<String, Object>> engine, String step, Rule rule,
                                        List<String> unexpected) {
        mark(STEP + step);
        try {
            engine.load(List.of(rule));
            unexpected.add(step);
        } catch (RuleCompilationException expected) {
            // As it should.
        }
    }

    // Runs the engine with a fact whose method throws, and records the step unless the run failed as it should.
    private static void failsToRun(RulesEngine<Map<String, Object>> engine, List<String> unexpected, String step) {
        try {
            engine.run(new FactMap<>(new Fact<>("failing", new Failing())));
            unexpected.add(step);
        } catch (RuleExecutionException expected) {
            // As it should.
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    // Loads the rule and runs it with no facts, and records it unless it put 1 under k.
    private static void loadAndRun(RulesEngine<Map<String, Object>> engine, Rule rule, List<String> unexpected) {
        engine.load(List.of(rule));
        if (!Map.of("k", 1).equals(engine.run(new FactMap<>()))) {
            unexpected.add(rule.getRuleName());
        }
    }

    // Flushed, so the line comes before what the JVM logs next.
    private static void mark(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
