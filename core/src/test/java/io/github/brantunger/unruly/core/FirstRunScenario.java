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
import io.github.brantunger.unruly.api.language.ForwardingExpressionCompiler;
import io.github.brantunger.unruly.api.language.ForwardingExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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
 * read-only facts; a language that calls {@link FactProperties#toData}; a fatal error from a nested run; a nested
 * run that passes its deadline; an action that throws an exception, and one that throws an {@link Error}; a setter
 * that throws; a listener that throws; an action that throws an exception with a suppressed exception; a listener
 * that throws an {@link OutOfMemoryError}; and a unique-match engine whose two rules match. Then, #1097, a load of one
 * rule that fails to compile and one of two; a write of a {@code Long} that no setter accepts; a run that waits for
 * the only copy, which a run on another thread holds; a run on a virtual thread that finds no idle copy, and takes a
 * build slot; and a borrow that fails as the last user of rules a load replaced, whose retiring failed part way. The
 * steps whose runs, loads, writes or borrows fail, or whose runs wait, are listed in {@link #FAILING}, and
 * {@link #FAILURES_FIRST} takes them before the steps that could hide what they load first. Last, it closes an engine,
 * the JVM's first {@code close()}.
 */
final class FirstRunScenario {

    static final String BUILDING = "SCENARIO building";
    static final String BUILT = "SCENARIO built";
    static final String LOADED = "SCENARIO loaded";
    static final String RAN = "SCENARIO ran";
    /** What the line that comes before each step of the runs begins with; the step's description follows. */
    static final String STEP = "SCENARIO step: ";
    static final String ACTION_THROWS = "an action that throws a RuntimeException";
    static final String ACTION_ERRS = "an action that throws an Error";
    static final String SETTER_THROWS = "a setter that throws";
    static final String LISTENER_THROWS = "a listener that throws";
    static final String ACTION_SUPPRESSES = "an action that throws an exception with a suppressed exception";
    static final String LISTENER_FATAL = "a listener that throws an OutOfMemoryError";
    static final String DEADLINE = "a nested run that passes its deadline";
    static final String UNIQUE = "a unique-match engine, two rules match";
    static final String LOAD_FAILS = "a load of one rule that fails to compile";
    static final String LOAD_FAILS_TWICE = "a load of two rules that fail to compile";
    static final String WRITE_REFUSED = "a write of a Long that no setter accepts, to setCount(int)";
    static final String PERMIT_WAIT = "a run that waits for the only copy, which another thread's run holds";
    static final String SLOT_WAIT = "a run on a virtual thread that finds no idle copy, and takes a build slot";
    static final String RETIRED_BORROW_FAILS = "a failed borrow, the last user of rules a load replaced, whose "
            + "retiring failed";
    /**
     * The system property that, set to {@code true}, has the scenario take the steps that load rules that fail to
     * compile, validate them, and load and run a rule with a validity window after the failing runs rather than before
     * them, so none of the classes those use first is loaded before the failing runs, where it would hide that a
     * failing run would otherwise be the first to load it.
     */
    static final String FAILURES_FIRST = "unruly.scenario.failuresFirst";
    /**
     * The steps whose runs fail, or whose listener fails, each the JVM's first of its kind, which the engine handles in
     * code that runs only when something fails; and, #1097, the steps whose load fails, whose write fails, whose run
     * waits for a copy or looks for a build slot, and whose borrow fails, each the JVM's first, which the engine
     * handles in code that runs only when something fails or waits.
     */
    static final List<String> FAILING = List.of("a condition that fails", "a fatal error from a nested run",
            DEADLINE, "a fact of the wrong type, and a declared fact left out, with requireDeclaredFacts()",
            "a fact with a name a language reserves: output, and ctx", ACTION_THROWS, ACTION_ERRS, SETTER_THROWS,
            LISTENER_THROWS, ACTION_SUPPRESSES, LISTENER_FATAL, UNIQUE, LOAD_FAILS, LOAD_FAILS_TWICE, WRITE_REFUSED,
            PERMIT_WAIT, SLOT_WAIT, RETIRED_BORROW_FAILS);

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

    /** An output object whose setter throws, so writing what an action returns fails the rule. */
    public static final class Refusing {

        public int getCount() {
            return 0;
        }

        public void setCount(int count) {
            throw new IllegalStateException("refused by the scenario");
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

    /** Throws an {@link OutOfMemoryError} from a callback, which the run rethrows once every listener has had it. */
    private static final class FatalListener implements RuleListener {
        @Override
        public void beforeExecute(Rule rule, Object output) {
            throw new OutOfMemoryError("thrown by the scenario's listener");
        }
    }

    /** Throws from a callback, so the run logs what a listener threw, and goes on. */
    private static final class ThrowingListener implements RuleListener {
        @Override
        public void beforeExecute(Rule rule, Object output) {
            throw new IllegalStateException("thrown by the scenario's listener");
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

    /**
     * Holds a copy of an engine's rules in its rule's action, on a thread of its own, until it's let go, or, once it's
     * given a thread to wait for, until that thread waits, so a run on that thread has to wait for the copy. The
     * rule's action calls {@link #hold()}, which holds only on the holder's own thread.
     */
    private static final class Holder {
        private final CountDownLatch inside = new CountDownLatch(1);
        private volatile Thread thread;
        private volatile Thread waiter;
        private volatile boolean released;

        void hold() {
            if (Thread.currentThread() != thread) {
                return;
            }
            inside.countDown();
            // Spins rather than waits, so the only thread waiting while the copy is held is the waiter.
            while (!released && (waiter == null || waiter.getState() != Thread.State.TIMED_WAITING)) {
                Thread.onSpinWait();
            }
        }

        // Starts a run of the engine on a thread of its own, and returns once the run holds its copy.
        void start(RulesEngine<?> engine) {
            // Named, as an unnamed thread's number has the JDK initialize a class of its own.
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    engine.run(new FactMap<>());
                }
            }, "holder");
            thread.start();
            await(inside);
        }

        // Lets the run give its copy back once the thread given waits, as a run waiting for the copy does.
        void releaseWhenWaiting(Thread waiting) {
            waiter = waiting;
        }

        // Lets the run give its copy back now, if it hasn't, and waits for it to end.
        void release() {
            released = true;
            join(thread);
        }
    }

    /**
     * Makes the sessions of an engine's copies: on the thread it's told to refuse on, a session that refuses once that
     * thread has waited in it until it's let go; otherwise one that keeps state, as with {@link Session#none()} no
     * run takes a copy.
     */
    private static final class Sessions {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch go = new CountDownLatch(1);
        private volatile Thread refusing;

        Session newSession() {
            if (Thread.currentThread() != refusing) {
                return new Session() {
                };
            }
            entered.countDown();
            await(go);
            throw new IllegalStateException("refused by the scenario");
        }
    }

    /** Forwards to a language, and counts the compilers of its that the engine closes. */
    private static final class CountingCloses extends ForwardingExpressionLanguage {
        private final AtomicInteger closed = new AtomicInteger();

        CountingCloses(ExpressionLanguage language) {
            super(language);
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ForwardingExpressionCompiler(super.newCompiler(context)) {
                @Override
                public void close() {
                    closed.incrementAndGet();
                    super.close();
                }
            };
        }
    }

    /** Runs an engine on a thread of its own, and records what the run threw. */
    private static final class Borrower implements Runnable {
        private final RulesEngine<?> engine;
        private volatile Throwable thrown;

        Borrower(RulesEngine<?> engine) {
            this.engine = engine;
        }

        @Override
        public void run() {
            try {
                engine.run(new FactMap<>());
            } catch (RuntimeException | Error e) {
                thrown = e;
            }
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
        StubExpressionLanguage throwingLanguage = new StubExpressionLanguage().action((context, session) -> {
            throw new IllegalStateException("thrown by the scenario");
        });
        StubExpressionLanguage erringLanguage = new StubExpressionLanguage().action((context, session) -> {
            throw new Error("thrown by the scenario");
        });
        StubExpressionLanguage suppressingLanguage = new StubExpressionLanguage().action((context, session) -> {
            IllegalStateException thrown = new IllegalStateException("thrown by the scenario");
            thrown.addSuppressed(new IllegalArgumentException("suppressed by the scenario"));
            throw thrown;
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
        RulesEngine<Map<String, Object>> throwing = RulesEngineBuilder.firstMatch(maps).language(throwingLanguage)
                .listener(listener).build();
        RulesEngine<Map<String, Object>> erring = RulesEngineBuilder.firstMatch(maps).language(erringLanguage)
                .listener(listener).build();
        RulesEngine<Refusing> refusing = RulesEngineBuilder.firstMatch(Refusing::new)
                .language(new ToyExpressionLanguage("toy", true)).listener(listener).build();
        RulesEngine<Map<String, Object>> listened = RulesEngineBuilder.firstMatch(maps).language(toy)
                .listener(new ThrowingListener()).build();
        RulesEngine<Map<String, Object>> suppressing = RulesEngineBuilder.firstMatch(maps)
                .language(suppressingLanguage).listener(listener).build();
        RulesEngine<Map<String, Object>> fatalListened = RulesEngineBuilder.firstMatch(maps).language(toy)
                .listener(new FatalListener()).build();
        RulesEngine<Map<String, Object>> unique = RulesEngineBuilder.uniqueMatch(maps).language(toy)
                .listener(listener).build();
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
        // #1097: an engine whose action sets a Long where only setCount(int) is, and those whose runs wait for a
        // copy, find no idle copy on a virtual thread, and fail to borrow once the rules they use are retired.
        RulesEngine<Output> writesLong = RulesEngineBuilder.firstMatch(beans)
                .language(new StubExpressionLanguage().action((context, session) -> ActionResult.set(
                        Map.of("count", 1L)))).build();
        Holder limitedHolder = new Holder();
        RulesEngine<Map<String, Object>> limited = RulesEngineBuilder.firstMatch(maps).maxCopies(1)
                .language(holding(limitedHolder, new Sessions())).build();
        Holder unlimitedHolder = new Holder();
        RulesEngine<Map<String, Object>> unlimited = RulesEngineBuilder.firstMatch(maps).unlimitedCopies()
                .language(holding(unlimitedHolder, new Sessions())).build();
        Holder retiringHolder = new Holder();
        Sessions refusingSessions = new Sessions();
        CountingCloses retiringLanguage = new CountingCloses(holding(retiringHolder, refusingSessions));
        RulesEngine<Map<String, Object>> retiring = RulesEngineBuilder.firstMatch(maps)
                .language(retiringLanguage).build();
        // A virtual thread of the application's, started and joined, as the JDK initializes classes of its own for the
        // first, which a run on one would otherwise be the first to.
        Thread application = Thread.ofVirtual().unstarted(new Runnable() {
            @Override
            public void run() {
                // Nothing to do: being run is what counts.
            }
        });
        application.start();
        join(application);
        mark(BUILT);

        // Each step is marked, so a class one initializes can be told by the step it came after. Every step runs, even
        // after one that didn't end as it should.
        boolean failuresFirst = Boolean.getBoolean(FAILURES_FIRST);
        boolean asExpected = failuresFirst || failsToLoad(broken);
        mark(STEP + "a fact name the builder rejects, blank, and one build() rejects, output");
        asExpected &= rejectsName(" ") && buildRejectsName(maps, toy, "output");
        mark(STEP + "loads that succeed");
        map.load(List.of(Rule.builder().ruleName("matches").condition("true").action("put k 1").build(),
                Rule.builder().ruleName("doesn't").condition("false").action("put j 1").build()));
        bean.load(List.of(Rule.builder().ruleName("counts").condition("true").action("put count 1 ; put name 'bob'")
                .build()));
        failing.load(List.of(Rule.builder().ruleName("broken").condition("missing.value > 1").action("put k 1")
                .build()));
        for (RulesEngine<?> stub : List.of(toData, fatal, outer, writing, overrun, throwing, erring)) {
            stub.load(List.of(Rule.builder().ruleName("acts").condition("x").action("x").build()));
        }
        slow.load(List.of(Rule.builder().ruleName("first").priority(2).condition("x").action("x").build(),
                Rule.builder().ruleName("second").priority(1).condition("x").action("x").build()));
        reserving.load(List.of(Rule.builder().ruleName("reserves").condition("true").action("put k 1").build()));
        refusing.load(List.of(Rule.builder().ruleName("refused").condition("true").action("put count 1").build()));
        for (RulesEngine<?> listenedTo : List.of(listened, fatalListened)) {
            listenedTo.load(List.of(Rule.builder().ruleName("matches").condition("true").action("put k 1").build()));
        }
        suppressing.load(List.of(Rule.builder().ruleName("acts").condition("x").action("x").build()));
        unique.load(List.of(Rule.builder().ruleName("one").condition("true").action("put k 1").build(),
                Rule.builder().ruleName("two").condition("true").action("put k 2").build()));
        for (RulesEngine<?> stub : List.of(writesLong, limited, unlimited, retiring)) {
            stub.load(List.of(Rule.builder().ruleName("acts").condition("x").action("x").build()));
        }
        if (!failuresFirst) {
            loadWindowed(windowed);
        }
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
        mark(STEP + DEADLINE);
        asExpected &= throwsOnRun(overrun, new FactMap<>(), RuleExecutionException.class);
        mark(STEP + "a fact declared with a primitive type, loaded and widened");
        asExpected &= loadsAndRunsDeclared(declared);
        mark(STEP + "a fact of the wrong type, and a declared fact left out, with requireDeclaredFacts()");
        asExpected &= rejectsFacts(strict);
        if (!failuresFirst) {
            asExpected &= runsWindowed(windowed);
        }
        mark(STEP + "a fact with a name a language reserves: output, and ctx");
        asExpected &= throwsOnRun(map, new FactMap<>(new Fact<>("output", 1)), IllegalArgumentException.class)
                && throwsOnRun(reserving, new FactMap<>(new Fact<>("ctx", 1)), IllegalArgumentException.class);
        mark(STEP + ACTION_THROWS);
        asExpected &= throwsOnRun(throwing, new FactMap<>(), RuleExecutionException.class);
        mark(STEP + ACTION_ERRS);
        asExpected &= throwsOnRun(erring, new FactMap<>(), RuleExecutionException.class);
        mark(STEP + SETTER_THROWS);
        asExpected &= throwsOnRun(refusing, new FactMap<>(), RuleExecutionException.class);
        mark(STEP + LISTENER_THROWS);
        asExpected &= Map.of("k", 1).equals(listened.run(new FactMap<>()));
        mark(STEP + ACTION_SUPPRESSES);
        asExpected &= throwsOnRun(suppressing, new FactMap<>(), RuleExecutionException.class);
        mark(STEP + LISTENER_FATAL);
        asExpected &= throwsOnRun(fatalListened, new FactMap<>(), OutOfMemoryError.class);
        mark(STEP + UNIQUE);
        asExpected &= throwsOnRun(unique, new FactMap<>(), RuleExecutionException.class);
        asExpected &= failsToLoadRules(broken);
        mark(STEP + WRITE_REFUSED);
        asExpected &= throwsOnRun(writesLong, new FactMap<>(), RuleExecutionException.class);
        asExpected &= waitsForCopies(limited, limitedHolder, unlimited, unlimitedHolder);
        asExpected &= failsToBorrowRetired(retiring, retiringHolder, refusingSessions, retiringLanguage);
        if (failuresFirst) {
            asExpected &= failsToLoad(broken);
            loadWindowed(windowed);
            asExpected &= runsWindowed(windowed);
        }
        mark(STEP + "close(), the JVM's first");
        broken.close();
        if (asExpected) {
            mark(RAN);
        }
        for (RulesEngine<?> engine : List.of(map, bean, declared, failing, toData, fatal, outer, strict, writing, slow,
                overrun, windowed, reserving, throwing, erring, refusing, listened, suppressing, fatalListened,
                unique, writesLong, limited, unlimited, retiring)) {
            engine.close();
        }
    }

    // Loads rules that fail to compile, and validates a valid rule list and one that fails, in steps of their own.
    private static boolean failsToLoad(RulesEngine<Map<String, Object>> broken) {
        mark(STEP + "a load that fails to compile, with an exception and with an invalid expression");
        boolean asExpected = throwsOnLoad(broken, Rule.builder().ruleName("syntax").condition("a b c d")
                .action("put k 1").build())
                && throwsOnLoad(broken, Rule.builder().ruleName("assigns").condition("a = 1").action("put k 1")
                .build());
        mark(STEP + "validate(), of a valid rule list and of one that fails");
        asExpected &= broken.validate(List.of(Rule.builder().ruleName("valid").condition("true").action("put k 1")
                .build())).isEmpty();
        return asExpected && !broken.validate(List.of(Rule.builder().ruleName("syntax").condition("a b c d")
                .action("put k 1").build())).isEmpty();
    }

    // #1097: loads of rules that fail to compile, one rule and then two, each in a step of its own.
    private static boolean failsToLoadRules(RulesEngine<Map<String, Object>> broken) {
        mark(STEP + LOAD_FAILS);
        boolean asExpected = throwsOnLoad(broken, List.of(Rule.builder().ruleName("assigns").condition("a = 1")
                .action("put k 1").build()));
        mark(STEP + LOAD_FAILS_TWICE);
        return asExpected && throwsOnLoad(broken, List.of(
                Rule.builder().ruleName("first").condition("a b c d").action("put k 1").build(),
                Rule.builder().ruleName("second").condition("a = 1").action("put k 1").build()));
    }

    // #1097: a run that waits for a permit while another thread's run holds the only copy, until it waits; and a run
    // on a virtual thread that finds no idle copy, as another thread's run holds it, and so looks for a build slot.
    // That run is the JVM's first to look for a slot, and takes one at once: the JVM's first to look can't be one that
    // waits, as every run on a virtual thread that finds no idle copy looks for a slot first, so the runs holding every
    // slot, which a run that waits needs, would have looked before it.
    private static boolean waitsForCopies(RulesEngine<Map<String, Object>> limited, Holder limitedHolder,
                                          RulesEngine<Map<String, Object>> unlimited, Holder unlimitedHolder) {
        mark(STEP + "a run on another thread that holds the only copy");
        limitedHolder.start(limited);
        limitedHolder.releaseWhenWaiting(Thread.currentThread());
        mark(STEP + PERMIT_WAIT);
        boolean asExpected = limited.run(new FactMap<>()).isEmpty();
        mark(STEP + "the other thread's run ends, and another holds the only copy of an engine without a limit");
        limitedHolder.release();
        unlimitedHolder.start(unlimited);
        Borrower virtual = new Borrower(unlimited);
        Thread thread = Thread.ofVirtual().unstarted(virtual);
        mark(STEP + SLOT_WAIT);
        thread.start();
        join(thread);
        mark(STEP + "the other thread's run ends");
        unlimitedHolder.release();
        return asExpected && virtual.thrown == null;
    }

    // #1097: a borrow that makes a new copy waits in the language's session while a load replaces the rules, whose
    // retiring fails before it counts its part done, as one that runs out of stack can. The borrow, the last user of
    // those rules, counts its part as it fails, as it would anyway; what the fault changes is that no retire() has
    // counted a part before it, so the failing borrow is the JVM's first to do so. The replaced rules' compiler is then
    // still open, as only one of the two parts it waits for was counted: had the fault not been thrown, retire() would
    // have counted its part first, and the borrow would have closed the compiler, so the step checks that it's open.
    private static boolean failsToBorrowRetired(RulesEngine<Map<String, Object>> retiring, Holder holder,
                                                Sessions sessions, CountingCloses language) {
        mark(STEP + "a borrow that waits in a new session while a load replaces the rules, whose retiring fails");
        holder.start(retiring);
        Borrower borrower = new Borrower(retiring);
        Thread thread = new Thread(borrower, "borrower");
        sessions.refusing = thread;
        thread.start();
        await(sessions.entered);
        Faults.inject(Faults.Step.RETIRED_COPIES_CLOSED, 1, new StackOverflowError("thrown by the scenario"));
        retiring.load(List.of(Rule.builder().ruleName("replaces").condition("x").action("x").build()));
        Faults.clear();
        holder.release();
        mark(STEP + RETIRED_BORROW_FAILS);
        sessions.go.countDown();
        join(thread);
        return borrower.thrown instanceof RuntimeException && language.closed.get() == 0;
    }

    // A language whose rules hold the holder's copy on its thread, with the sessions given.
    private static StubExpressionLanguage holding(Holder holder, Sessions sessions) {
        return new StubExpressionLanguage().action((context, session) -> {
            holder.hold();
            return ActionResult.done();
        }).newSession(sessions::newSession);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void loadWindowed(RulesEngine<Map<String, Object>> windowed) {
        mark(STEP + "a rule with a validity window, loaded");
        // An instant from its parts: parsing one is what the application does, and initializes the JDK's formatter.
        windowed.load(List.of(Rule.builder().ruleName("dated").condition("true").action("put k 1")
                .validFrom(Instant.ofEpochSecond(0)).validTo(Instant.ofEpochSecond(32_503_680_000L, 5)).build()));
    }

    private static boolean runsWindowed(RulesEngine<Map<String, Object>> windowed) {
        mark(STEP + "a rule within its validity window");
        return Map.of("k", 1).equals(windowed.run(new FactMap<>()));
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
        return throwsOnLoad(engine, List.of(rule));
    }

    private static boolean throwsOnLoad(RulesEngine<?> engine, List<Rule> rules) {
        try {
            engine.load(rules);
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
