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
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.ForwardingExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Run by {@link FirstRunClassInitializationTest} in a JVM that logs every class it initializes. It creates what an
 * application gives the JVM's first builder, without a lambda, so the JDK has linked none before it, and prints
 * {@link #BUILDING}, then builds that engine, with a language option, imports of the language's own and declared facts
 * given to its builder, then creates what it gives the other builders, then builds every other
 * engine and prints {@link #BUILT}. It then loads their rules and prints {@link #LOADED}, and gives each
 * its first run, and prints {@link #RAN} if every load and run ended as it should; from {@code BUILT} on, it does so
 * in steps that each begin with a {@link #STEP} line. Besides loads that succeed, the loads take these paths: a load
 * that fails to compile, with an exception and with an invalid expression, a rule with a validity window,
 * {@code validate()} of a valid list and of one that fails, and a fact name the builder rejects. The runs take these
 * paths: a map output, and a bean output with a setter that takes a primitive and one that takes a {@code String}; a
 * fact declared with a primitive type, a fact of the wrong type for its declaration and a declared fact missing from
 * an engine that requires them all; every listener callback; a condition that fails; an action that writes to its
 * read-only facts; a language that calls {@link FactProperties#toData}; a fatal error from a nested run; and a nested
 * run that passes its deadline. Last, it closes an engine, the JVM's first {@code close()}.
 */
final class FirstRunScenario {

    static final String BUILDING = "SCENARIO building";
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

    /**
     * The toy language under the name {@code found}, for a services file to list. It creates the toy language when
     * it's created, at build, as a language's own classes are its to initialize.
     */
    public static final class Found implements ExpressionLanguage {
        private final ToyExpressionLanguage toy = new ToyExpressionLanguage("found");

        @Override
        public String name() {
            return "found";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return toy.newCompiler(context);
        }
    }

    /**
     * Run by {@link FirstRunClassInitializationTest} as a scenario of its own: it builds the JVM's first engine, whose
     * only language, {@link Found}, it finds with {@link java.util.ServiceLoader} without the builder naming it, as an
     * application that has MVEL on its class path does, prints {@link #BUILT}, loads a rule, the language's first use,
     * and prints {@link #LOADED}. On its own, as an engine built with a named language would have made its first
     * use's work for it.
     */
    public static final class OnlyFound {

        private OnlyFound() {
        }

        public static void main(String[] args) throws IOException {
            Supplier<Map<String, Object>> maps = new Supplier<>() {
                @Override
                public Map<String, Object> get() {
                    return new HashMap<>();
                }
            };
            Path services = Files.createTempDirectory("services");
            Path file = services.resolve("META-INF/services/" + ExpressionLanguage.class.getName());
            Files.createDirectories(file.getParent());
            Files.writeString(file, Found.class.getName());
            List<Rule> rules = List.of(Rule.builder().ruleName("matches").condition("true").action("put k 1").build());
            "a b".split("\\s+");
            Thread.currentThread().setContextClassLoader(new URLClassLoader(new URL[]{services.toUri().toURL()},
                    OnlyFound.class.getClassLoader()));
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.firstMatch(maps).build();
            mark(BUILT);
            engine.load(rules);
            mark(LOADED);
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
        // What an application gives the first builder, created before it, so a class it initializes isn't the engine's.
        // Not a lambda: the first build is then the JVM's first use of the JDK's classes that link one, and the test
        // fails if the build links one before it makes room for initializing classes.
        Supplier<Map<String, Object>> maps = new Supplier<>() {
            @Override
            public Map<String, Object> get() {
                return new HashMap<>();
            }
        };
        ToyExpressionLanguage toy = new ToyExpressionLanguage();
        Listener listener = new Listener();
        Map<String, Class<?>> facts = Map.of("m", Object.class);
        // The toy language splits an expression with a regular expression, which is the application's use of the JDK.
        "a b".split("\\s+");
        // Its error messages are the JDK's string concatenations, as a test fixture is compiled with javac's default,
        // which the engine isn't (#965): its first one would initialize these two in the first loads. They are the
        // application's, so initialized here, by name, to link no concatenation and leave the JDK's others alone.
        for (String name : new String[]{"java.lang.invoke.StringConcatFactory",
                "java.lang.invoke.StringConcatFactory$InlineHiddenClassStrategy"}) {
            try {
                Class.forName(name);
            } catch (ClassNotFoundException e) {
                // Not in this JDK release.
            }
        }
        mark(BUILDING);
        RulesEngine<Map<String, Object>> map = RulesEngineBuilder.allMatches(maps).language(toy).listener(listener)
                .option("toy", "some", "value").languageImports("toy", "some.Name").facts(facts).build();

        Supplier<Output> beans = Output::new;
        ToyExpressionLanguage beanToy = new ToyExpressionLanguage("toy", true);
        StubExpressionLanguage toDataLanguage = new StubExpressionLanguage().action((context, session) -> {
            FactProperties.toData(Map.of("a", 1), 2);
            return ActionResult.done();
        });
        StubExpressionLanguage fatalLanguage = new StubExpressionLanguage().action((context, session) -> {
            throw new OutOfMemoryError("thrown by the scenario");
        });
        StubExpressionLanguage writingLanguage = new StubExpressionLanguage().action((context, session) -> {
            try {
                context.facts().put("x", 1);
                return ActionResult.done();
            } catch (UnsupportedOperationException expected) {
                return ActionResult.set(Map.of("rejected", true));
            }
        });
        StubExpressionLanguage slowLanguage = new StubExpressionLanguage().action((context, session) -> {
            spin();
            return ActionResult.done();
        });
        RulesEngine<Output> bean = RulesEngineBuilder.allMatches(beans).language(beanToy).build();
        RulesEngine<Map<String, Object>> declared = RulesEngineBuilder.firstMatch(maps).language(toy)
                .fact("n", long.class).build();
        RulesEngine<Map<String, Object>> failing = RulesEngineBuilder.firstMatch(maps).language(toy)
                .listener(listener).build();
        RulesEngine<Map<String, Object>> toData = RulesEngineBuilder.firstMatch(maps).language(toDataLanguage)
                .build();
        RulesEngine<Map<String, Object>> fatal = RulesEngineBuilder.firstMatch(maps).language(fatalLanguage).build();
        RulesEngine<Map<String, Object>> outer = RulesEngineBuilder.firstMatch(maps)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    fatal.run(new FactMap<>());
                    return ActionResult.done();
                })).build();
        RulesEngine<Map<String, Object>> strict = RulesEngineBuilder.firstMatch(maps).language(toy)
                .fact("n", long.class).requireDeclaredFacts().build();
        RulesEngine<Map<String, Object>> writing = RulesEngineBuilder.firstMatch(maps).language(writingLanguage)
                .build();
        RulesEngine<Map<String, Object>> slow = RulesEngineBuilder.allMatches(maps).language(slowLanguage).build();
        RulesEngine<Map<String, Object>> overrun = RulesEngineBuilder.firstMatch(maps)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    slow.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofMillis(1)));
                    return ActionResult.done();
                })).build();
        RulesEngine<Map<String, Object>> broken = RulesEngineBuilder.firstMatch(maps).language(toy).build();
        RulesEngine<Map<String, Object>> windowed = RulesEngineBuilder.firstMatch(maps).language(toy).build();
        RulesEngine<Map<String, Object>> reserving = RulesEngineBuilder.firstMatch(maps)
                .language(new ForwardingExpressionLanguage(new ToyExpressionLanguage()) {
                    @Override
                    public Set<String> reservedFactNames() {
                        return Set.of("ctx");
                    }
                }).build();
        mark(BUILT);

        // Each step is marked, so a class one initializes can be told by the step it came after. Every step runs, even
        // after one that didn't end as it should.
        mark(STEP + "a load that fails to compile, with an exception and with an invalid expression");
        boolean asExpected = throwsOnLoad(broken, Rule.builder().ruleName("syntax").condition("a b c d")
                .action("put k 1").build())
                && throwsOnLoad(broken, Rule.builder().ruleName("assigns").condition("a = 1").action("put k 1")
                .build());
        mark(STEP + "validate(), of a valid rule list and of one that fails");
        asExpected &= broken.validate(List.of(Rule.builder().ruleName("valid").condition("true").action("put k 1")
                .build())).isEmpty();
        asExpected &= !broken.validate(List.of(Rule.builder().ruleName("syntax").condition("a b c d")
                .action("put k 1").build())).isEmpty();
        mark(STEP + "a fact name the builder rejects, blank, and one build() rejects, output");
        asExpected &= rejectsName(" ") && buildRejectsName(maps, toy, "output");
        mark(STEP + "loads that succeed");
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
        reserving.load(List.of(Rule.builder().ruleName("reserves").condition("true").action("put k 1").build()));
        mark(STEP + "a rule with a validity window, loaded");
        // An instant from its parts: parsing one is what the application does, and initializes the JDK's formatter.
        windowed.load(List.of(Rule.builder().ruleName("dated").condition("true").action("put k 1")
                .validFrom(Instant.ofEpochSecond(0)).validTo(Instant.ofEpochSecond(32_503_680_000L, 5)).build()));
        mark(LOADED);

        mark(STEP + "a bean output, with setters taking an int and a String");
        Output written = bean.run(new FactMap<>());
        asExpected &= written.getCount() == 1 && "bob".equals(written.getName());
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
        mark(STEP + "a rule within its validity window");
        asExpected &= Map.of("k", 1).equals(windowed.run(new FactMap<>()));
        mark(STEP + "a fact with a name a language reserves: output, and ctx");
        asExpected &= throwsOnRun(windowed, new FactMap<>(new Fact<>("output", 1)), IllegalArgumentException.class)
                && throwsOnRun(reserving, new FactMap<>(new Fact<>("ctx", 1)), IllegalArgumentException.class);
        mark(STEP + "close(), the JVM's first");
        broken.close();
        if (asExpected) {
            mark(RAN);
        }
        for (RulesEngine<?> engine : List.of(map, bean, declared, failing, toData, fatal, outer, strict, writing, slow,
                overrun, windowed, reserving)) {
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

    private static boolean throwsOnLoad(RulesEngine<?> engine, Rule rule) {
        try {
            engine.load(List.of(rule));
            return false;
        } catch (RuleCompilationException expected) {
            return true;
        }
    }

    // Rejected by the builder, and so before an engine with it is built.
    private static boolean rejectsName(String name) {
        try {
            RulesEngineBuilder.firstMatch(HashMap::new).fact(name, Object.class);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean buildRejectsName(Supplier<Map<String, Object>> maps, ToyExpressionLanguage language,
                                            String name) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.firstMatch(maps).language(language)
                .fact(name, Object.class);
        try {
            builder.build();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
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
