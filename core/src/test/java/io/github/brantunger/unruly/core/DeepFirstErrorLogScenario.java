package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * Run by {@link DeepFirstErrorLogTest} in a new JVM, so the failure it makes deep in a stack is the first the JVM's
 * engines log. The first message logged loads and initializes classes of the logging provider's, which can take more
 * stack than the room the engine checks for before it starts, so the log call itself could overflow there (#1208). The
 * scenario takes the {@link Site} to fail from the system property {@value #SITE}. It builds an engine at the top of
 * the main thread's stack, loads a rule list that passes and runs it {@value #WARM_UPS} times, so the deep calls do no
 * more than fail. On a thread with a small stack, it finds where the stack ends, then makes the failure at each depth
 * from the end up, a frame at a time. A call that overflows before it reaches the engine's log call, as in the engine's
 * check of its stack's room, is tried a frame higher. It stops at the first call that reaches it: it prints
 * {@link #OVERFLOWED} and the frames of the overflow when the log call threw a {@link StackOverflowError} out of the
 * engine, and else {@link #DEEP} and what the call threw. The engine's log goes to standard output, so the lines it
 * logged come between {@link #BEGIN} and {@link #END}, after {@link #JAVA} and the JDK's version. The test keeps the
 * recursion that finds the end from being compiled, so its frames are the same size each time.
 */
final class DeepFirstErrorLogScenario {

    static final String SITE = "unruly.scenario.site";
    static final String BEGIN = "SCENARIO begin";
    static final String END = "SCENARIO end";
    static final String OVERFLOWED = "SCENARIO overflowed in the log call: ";
    static final String DEEP = "SCENARIO deep: ";
    static final String JAVA = "SCENARIO java: ";
    /** What {@link #DEEP} says of a call that threw nothing. */
    static final String NOTHING = "nothing";

    /** The engine's log calls the scenario can fail at, by what fails there. */
    enum Site {
        /** A rule's action fails to compile, logged as the load collects it (RuleListCompiler's failed). */
        COMPILE,
        /** A rule list with two rules of one name, logged before anything is compiled (RuleSetLifecycle). */
        LIST,
        /** A rule's action fails to compile with a fatal error, logged before it's rethrown (RuleListCompiler). */
        FATAL_COMPILE,
        /** A rule's action throws, logged as the rule's failure (ListenerNotifier's reportFailure). */
        RULE,
        /** A listener's {@code beforeRun} throws a fatal error, logged before it's rethrown (notifyRun). */
        BEFORE_RUN,
        /** A listener's {@code afterEvaluate} throws a fatal error, logged before it's rethrown (notifyAfter). */
        AFTER_EVALUATE,
        /**
         * A rule's action throws, and a listener's {@code onError} starts a run of another engine, whose rule throws a
         * fatal error, and wraps that in an exception of its own, logged once the listeners are told
         * (logWrappedFromOnError).
         */
        WRAPPED_ON_ERROR,
        /** Two rules match on a unique-match engine, logged as the run's failure (CoreRulesEngine's failedRun). */
        UNIQUE_MATCH,
        /**
         * The output factory returns {@code null}, logged before the run fails (RuleExecutor's createOutput).
         */
        NULL_OUTPUT,
        /**
         * The output factory throws, logged before the run fails (Failures' reportCalledCodeFailure).
         */
        OUTPUT_THROWS,
        /** A fact of a type other than the one declared, logged as the run rejects it (FactIntake's rejectedFact). */
        FACT_REJECTED,
        /** The language rejects a fact's name, logged as the run rejects it (FactIntake's factNameRejection). */
        NAME_REJECTED,
        /**
         * The language's check of a fact's name throws something else, logged as the run fails (FactIntake's
         * factNameRejection).
         */
        NAME_CHECK_FAILS,
        /** The language returns no session for the run's copy, logged before the run fails (CopyPool's newSession). */
        NULL_SESSION,
        /** A listener's {@code beforeRun} throws, logged at WARN as the run goes on (logListenerException). */
        LISTENER_THROWS,
        /**
         * A rule's action throws a fatal error, and a listener's {@code onError} another, logged at WARN once the
         * failure is (keepSecondFatal).
         */
        SECOND_FATAL_ON_ERROR,
        /**
         * Another thread holds the one copy the engine allows, and the run times out waiting for it, logged at WARN
         * (CoreRulesEngine's reportStop).
         */
        STOPPED_WAITING,
        /**
         * A rule's action interrupts the run's thread, logged at WARN as the run stops (RuleExecutor's cancelled).
         */
        CANCELLED,
        /** The language warns about an expression as it compiles it, logged at WARN (EngineCompileContext's warn). */
        COMPILE_WARNING,
        /** A value a rule's action keeps for the run fails to close as the run ends, logged at WARN (Closing). */
        VALUE_CLOSE_FAILS,
        /** A session fails to close as a load retires the rules it replaced, logged at WARN (Closing). */
        SESSION_CLOSE_FAILS,
        /**
         * Another thread holds the one copy the engine allows past its stall window, so the run makes an extra copy,
         * logged at WARN (CopyPool's warnAboutOverflow).
         */
        EXTRA_COPY
    }

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /**
     * How many times it runs the rules that pass first, so the engine's check of its stack's room is compiled, as it is
     * in a JVM that has run engines for a while: compiled, its frames are a third of the size, so the room it makes is
     * less than a first log call's loading of the logging provider's classes took. The test runs it with
     * {@code -Xbatch}, so the compiling is done before the warm-up goes on, however busy the machine is.
     */
    private static final int WARM_UPS = 20_000;
    /** How many depths it tries, at most. */
    private static final int MAX_TRIES = 100_000;

    // Made here, at the top of the stack, as the scenario's own lambdas are linked, so what fails builds nothing of the
    // scenario's deep in the stack.
    private static final IllegalStateException FAILURE = new IllegalStateException("thrown by the scenario");
    private static final OutOfMemoryError FATAL = new OutOfMemoryError("thrown by the scenario");
    private static final IllegalArgumentException REJECTED = new IllegalArgumentException("rejected by the scenario");
    private static final OutOfMemoryError FATAL_AGAIN = new OutOfMemoryError("thrown again by the scenario");
    private static final InvalidExpressionException.Issue ISSUE = new InvalidExpressionException.Issue(
            InvalidExpressionException.Issue.Severity.WARNING, 1, 1, "warned by the scenario");
    private static final String KEY = "kept by the scenario";
    // The value a rule's action keeps for the run, whose close() fails once the rules that pass have run.
    private static final AutoCloseable VALUE = DeepFirstErrorLogScenario::closeValue;
    private static final Supplier<AutoCloseable> KEEPING = () -> VALUE;
    private static final CompiledAction KEEPS_VALUE = (context, session) -> {
        context.runScopedClosing(KEY, KEEPING);
        return ActionResult.done();
    };
    private static final CompiledAction INTERRUPTS = (context, session) -> {
        Thread.currentThread().interrupt();
        return ActionResult.done();
    };
    // Hold the run's copy on the thread named holder, until the scenario lets it go.
    private static final CountDownLatch HELD = new CountDownLatch(1);
    private static final CountDownLatch RELEASED = new CountDownLatch(1);
    private static final CompiledAction HOLDS = (context, session) -> {
        if ("holder".equals(Thread.currentThread().getName())) {
            HELD.countDown();
            RELEASED.await();
        }
        return ActionResult.done();
    };
    // A session of the language's own, not Session.none(), so the engine limits the copies that hold one.
    private static final Session OWN_SESSION = new Session() {
    };
    // A session whose close() fails once the rules that pass have run.
    private static final Session CLOSING_SESSION = new Session() {
        @Override
        public void close() {
            if (failing) {
                throw FAILURE;
            }
        }
    };
    private static final CompiledAction PASSING = (context, session) -> ActionResult.done();
    private static final CompiledAction FAILING = (context, session) -> {
        throw FAILURE;
    };
    private static final CompiledAction FAILING_FATALLY = (context, session) -> {
        throw FATAL;
    };
    private static final Supplier<Object> OUTPUT = Object::new;
    private static final FactMap<Object> FACTS = new FactMap<>();
    // A fact the language checks the name of, which the runs at the top don't supply; and the same fact with a value
    // of another type than the one FACT_REJECTED declares.
    private static final FactMap<Object> NAMED = new FactMap<>(new Fact<>("f", 1));
    private static final FactMap<Object> MISTYPED = new FactMap<>(new Fact<>("f", "not a number"));

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static Runnable call;
    private static Throwable thrown;
    // The engine the listener's onError runs, for WRAPPED_ON_ERROR.
    private static RulesEngine<Object> nested;
    // The context the language compiles in, for COMPILE_WARNING's warning.
    private static CompileContext compileContext;
    // Whether the listener, the output factory or the language fails, which none does while the rules that pass run
    // at the top.
    private static boolean failing;
    private static Site site;

    private DeepFirstErrorLogScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        site = Site.valueOf(System.getProperty(SITE));
        Supplier<Object> output = DeepFirstErrorLogScenario::output;
        RulesEngineBuilder<Object> builder = site == Site.UNIQUE_MATCH ? RulesEngineBuilder.uniqueMatch(output)
                : RulesEngineBuilder.firstMatch(output);
        if (site == Site.FACT_REJECTED) {
            builder.fact("f", Integer.class);
        }
        if (site == Site.STOPPED_WAITING || site == Site.EXTRA_COPY) {
            builder.maxCopies(1);
        }
        RulesEngine<Object> engine = builder.language(new StubExpressionLanguage()
                .compileAction(expression -> switch (expression.text()) {
                    case "pass" -> PASSING;
                    case "fail" -> FAILING;
                    case "fail fatally" -> FAILING_FATALLY;
                    case "fail to compile" -> throw FAILURE;
                    case "warn" -> {
                        compileContext.warn(expression, ISSUE);
                        yield PASSING;
                    }
                    case "keep a value" -> KEEPS_VALUE;
                    case "interrupt" -> INTERRUPTS;
                    case "hold" -> HOLDS;
                    default -> throw FATAL;
                })
                .onNewCompiler(context -> compileContext = context)
                .checkFactName(DeepFirstErrorLogScenario::checkFactName)
                .newSession(DeepFirstErrorLogScenario::newSession))
                .listener(new Failing(site)).build();
        if (site == Site.EXTRA_COPY) {
            ((CoreRulesEngine<Object>) engine).stallWindow(1);
        }
        // Everything a load and a run do but fail, done at the top of the stack, so the deep calls reach the failure.
        List<Rule> passes = List.of(rule("passes", "pass"));
        engine.load(passes);
        for (int i = 0; i < WARM_UPS; i++) {
            engine.run(FACTS);
        }
        // A run with a timeout too, so the deep run that has one starts nothing a timeout needs for the first time.
        engine.runWithResult(FACTS, RunOptions.withTimeoutOf(Duration.ofMinutes(1)));
        call = failingCall(site, engine);
        failing = true;
        String[] result = new String[1];
        Thread deep = new Thread(null, () -> {
            for (int above = 0; above < MAX_TRIES; above++) {
                deepest = 0;
                target = -1;
                try {
                    descend(0, 1, 2, 3, 4);
                } catch (StackOverflowError expected) {
                    // The end of the stack is found.
                }
                target = deepest - above;
                thrown = null;
                try {
                    descend(0, 1, 2, 3, 4);
                } catch (StackOverflowError beforeTheTarget) {
                    continue;
                }
                StackOverflowError overflow = overflowIn(thrown);
                if (overflow == null && site == Site.WRAPPED_ON_ERROR && !(thrown instanceof OutOfMemoryError)) {
                    // The listener's run overflowed before its rule did, so nothing was wrapped: a frame higher.
                    continue;
                }
                if (overflow == null) {
                    result[0] = DEEP + (thrown == null ? NOTHING : thrown);
                    return;
                }
                if (inLogCall(overflow)) {
                    result[0] = OVERFLOWED + frames(overflow);
                    return;
                }
            }
        }, "deep", STACK_BYTES);
        System.out.println(JAVA + Runtime.version());
        System.out.println(BEGIN);
        deep.start();
        deep.join();
        RELEASED.countDown();
        System.out.println(END);
        System.out.println(result[0]);
    }

    private static Rule rule(String name, String action) {
        return Rule.builder().ruleName(name).condition("x").action(action).build();
    }

    // What fails at the site, deep in the stack: the rules it needs are built and loaded here, at the top.
    private static Runnable failingCall(Site site, RulesEngine<Object> engine) {
        switch (site) {
            case COMPILE -> {
                List<Rule> rules = List.of(rule("fails", "fail to compile"));
                return () -> engine.load(rules);
            }
            case LIST -> {
                List<Rule> rules = List.of(rule("twice", "pass"), rule("twice", "pass"));
                return () -> engine.load(rules);
            }
            case FATAL_COMPILE -> {
                List<Rule> rules = List.of(rule("fails", "fail fatally to compile"));
                return () -> engine.load(rules);
            }
            case RULE -> engine.load(List.of(rule("fails", "fail")));
            case WRAPPED_ON_ERROR -> {
                nested = RulesEngineBuilder.firstMatch(OUTPUT).language(new StubExpressionLanguage()
                        .compileAction(expression -> "pass".equals(expression.text()) ? PASSING
                                : FAILING_FATALLY)).build();
                nested.load(List.of(rule("passes", "pass")));
                nested.run(FACTS);
                nested.load(List.of(rule("nested", "fail fatally")));
                engine.load(List.of(rule("fails", "fail")));
            }
            case UNIQUE_MATCH -> engine.load(List.of(rule("one", "pass"), rule("another", "pass")));
            case NAME_REJECTED, NAME_CHECK_FAILS -> {
                return () -> engine.run(NAMED);
            }
            case FACT_REJECTED -> {
                return () -> engine.run(MISTYPED);
            }
            // A rule list of its own, with no copy made yet, so the deep run makes one, with a session.
            case NULL_SESSION -> engine.load(List.of(rule("passes", "pass")));
            case SECOND_FATAL_ON_ERROR -> engine.load(List.of(rule("fails", "fail fatally")));
            case STOPPED_WAITING, EXTRA_COPY -> {
                engine.load(List.of(rule("holds", "hold")));
                Thread holder = new Thread(() -> engine.run(FACTS), "holder");
                holder.setDaemon(true);
                holder.start();
                await(HELD);
                if (site == Site.STOPPED_WAITING) {
                    RunOptions timeout = RunOptions.withTimeoutOf(Duration.ofMillis(1));
                    return () -> engine.runWithResult(FACTS, timeout);
                }
            }
            case CANCELLED -> engine.load(List.of(rule("interrupts", "interrupt"), rule("passes", "pass")));
            case COMPILE_WARNING -> {
                List<Rule> rules = List.of(rule("warns", "warn"));
                return () -> engine.load(rules);
            }
            case VALUE_CLOSE_FAILS -> engine.load(List.of(rule("keeps", "keep a value")));
            // The rules that pass have a copy, with a session, which the deep load retires and closes.
            case SESSION_CLOSE_FAILS -> {
                List<Rule> rules = List.of(rule("replaces", "pass"));
                return () -> engine.load(rules);
            }
            default -> {
                // The rules pass: the listener or the output factory fails.
            }
        }
        return () -> engine.run(FACTS);
    }

    // What closing the value a rule's action keeps for the run does.
    private static void closeValue() {
        if (failing) {
            throw FAILURE;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // A session for a new copy: none for NULL_SESSION once the rules that pass have run, one whose close() fails for
    // SESSION_CLOSE_FAILS, and one of the language's own where the copies are limited.
    private static Session newSession() {
        if (site == Site.SESSION_CLOSE_FAILS) {
            return CLOSING_SESSION;
        }
        if (site == Site.STOPPED_WAITING || site == Site.EXTRA_COPY) {
            return OWN_SESSION;
        }
        return failing && site == Site.NULL_SESSION ? null : Session.none();
    }

    // The output factory, which fails for the sites that make it fail, once the rules that pass have run.
    private static Object output() {
        if (failing && site == Site.NULL_OUTPUT) {
            return null;
        }
        if (failing && site == Site.OUTPUT_THROWS) {
            throw FAILURE;
        }
        return new Object();
    }

    // The language's check of a fact's name, which fails for the sites that make it fail, once the rules that pass
    // have run.
    private static void checkFactName(String name) {
        if (failing && site == Site.NAME_REJECTED) {
            throw REJECTED;
        }
        if (failing && site == Site.NAME_CHECK_FAILS) {
            throw FAILURE;
        }
    }

    // The overflow among what a call threw and its causes, if it threw one.
    private static StackOverflowError overflowIn(Throwable failure) {
        for (Throwable link = failure; link != null; link = link.getCause()) {
            if (link instanceof StackOverflowError overflow) {
                return overflow;
            }
        }
        return null;
    }

    // Whether the logging provider's code was on the stack when it overflowed: the engine's log call.
    private static boolean inLogCall(StackOverflowError overflow) {
        for (StackTraceElement frame : overflow.getStackTrace()) {
            if (frame.getClassName().startsWith("org.slf4j.")) {
                return true;
            }
        }
        return false;
    }

    // The overflow's frames from the first of the logging provider's to the first of the engine's.
    private static String frames(StackOverflowError overflow) {
        StringBuilder text = new StringBuilder();
        boolean logging = false;
        for (StackTraceElement frame : overflow.getStackTrace()) {
            logging |= frame.getClassName().startsWith("org.slf4j.");
            if (logging) {
                text.append(" at ").append(frame);
                if (frame.getClassName().startsWith("io.github.brantunger.unruly.")) {
                    break;
                }
            }
        }
        return text.toString();
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it makes the call.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            try {
                call.run();
            } catch (Throwable t) {
                thrown = t;
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }

    /** A listener that throws at the site's callback once the rules that pass have run. */
    private static final class Failing implements RuleListener {

        private final Site site;

        Failing(Site site) {
            this.site = site;
        }

        @Override
        public void beforeRun(RunContext run) {
            if (failing && site == Site.BEFORE_RUN) {
                throw FATAL;
            }
            if (failing && site == Site.LISTENER_THROWS) {
                throw FAILURE;
            }
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, @Nullable Object> facts, boolean matchResult) {
            if (failing && site == Site.AFTER_EVALUATE) {
                throw FATAL;
            }
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            if (failing && site == Site.SECOND_FATAL_ON_ERROR) {
                throw FATAL_AGAIN;
            }
            if (failing && site == Site.WRAPPED_ON_ERROR) {
                try {
                    nested.run(FACTS);
                } catch (OutOfMemoryError e) {
                    throw new IllegalStateException("wrapped by the listener", e);
                }
            }
        }
    }
}
