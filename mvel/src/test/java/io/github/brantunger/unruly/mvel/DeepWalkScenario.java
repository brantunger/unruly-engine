package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Run by {@link DeepWalkTest} in a new JVM: a step whose analysis pass or run asks MVEL's configuration for the class
 * loader so many times that its loop detection walks the stack (#1102), the one the system property {@value #STEP}
 * names. {@value #VALID_LOAD} loads a condition that reads a chain of {@value #PARTS} parts; {@value #VALID_RUN} makes
 * the first run of a rule list whose action passes such a chain as an argument, which the copy compiles as it first
 * runs; {@value #ANALYSIS_LOOP} loads an action MVEL's analysis goes round in a loop for (#840); and {@value #RUN_LOOP}
 * makes the first run of an action MVEL goes round in a loop running (#857). It first runs a rule that passes
 * {@value #WARM_UPS} times, then takes the step once at the top of the stack, so the classes it uses are loaded, and
 * prints {@link #AT_THE_TOP} and what it did: {@value #OK} if it loaded, or ran with the output expected,
 * {@value #LOOPED} if it failed with the message of MVEL's loop, or what it threw, with where the first
 * {@link StackOverflowError} in its cause chain overflowed. Then, on a thread with a small stack, it finds where the
 * stack ends, and takes the step at each depth from the end up, a frame at a time, until it does what it did at the
 * top. It prints {@link #CHECKS_FAILED} and how many steps overflowed in the engine's check of the stack's room,
 * {@link #FAILED_PAST_THE_CHECK} and how many past the check did something else, {@link #WALKS_OVERFLOWED} and how many
 * of those overflowed in a method of {@link CallSites} that walks the stack, then {@link #FIRST_PAST_THE_CHECK} and
 * what the first step past the check did, and {@link #LAST} and what the last did.
 */
final class DeepWalkScenario {

    static final String STEP = "scenario.step";
    static final String VALID_LOAD = "load";
    static final String VALID_RUN = "run";
    static final String ANALYSIS_LOOP = "analysisLoop";
    static final String RUN_LOOP = "runLoop";
    static final String AT_THE_TOP = "SCENARIO at the top: ";
    static final String CHECKS_FAILED = "SCENARIO checks failed: ";
    static final String FAILED_PAST_THE_CHECK = "SCENARIO failed past the check: ";
    static final String WALKS_OVERFLOWED = "SCENARIO overflowed walking: ";
    static final String FIRST_PAST_THE_CHECK = "SCENARIO first past the check: ";
    static final String LAST = "SCENARIO last: ";
    static final String OK = "ok";
    static final String LOOPED = "the loop's message";
    /** The parts of the chain: its analysis, or a copy's first run, asks twice for each, more than walks the stack. */
    static final int PARTS = 70;

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it takes the step at, at most. */
    private static final int MAX_STEPS = 100_000;
    /** How many steps past the check may fail before it stops. */
    private static final int MAX_FAILED = 1_000;
    /**
     * How many times it runs a rule that passes first, so the engine's check of its stack's room is compiled, as it is
     * in a JVM that has run engines for a while: compiled, its frames are a third of the size, so the room it makes is
     * less than a walk of the stack takes. The test runs it with {@code -Xbatch}, so the compiling is done before the
     * warm-up goes on, however busy the machine is.
     */
    private static final int WARM_UPS = 20_000;
    private static final String CHECK_CLASS = "io.github.brantunger.unruly.core.StackHeadroom";
    private static final String ENGINE_CORE = "io.github.brantunger.unruly.core.";
    private static final String MVEL = DeepWalkScenario.class.getPackageName() + ".";
    private static final String SCENARIO = DeepWalkScenario.class.getName();
    private static final String LOOP_MESSAGE = "went round in a loop";
    // The methods of CallSites that walk the stack, for MVEL's analysis and for a run.
    private static final List<String> WALKS = List.of("hashOfStack", "hashOfTopAndDepth");
    private static final String CHAIN = "t" + ".a".repeat(PARTS);

    // Linked here, at the top of the stack, as the scenario's own lambdas are: an application's lambda linked deep
    // could leave one of the JDK's classes unusable, which the engine can't help.
    private static final Supplier<Map<String, Object>> OUTPUT = HashMap::new;

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static String step;
    private static int deepest;
    private static int target;
    private static RulesEngine<Map<String, Object>> engine;
    private static FactMap<Map<String, Object>> facts;
    private static Map<String, Object> output;
    private static Throwable thrown;

    private DeepWalkScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        step = System.getProperty(STEP);
        engine = RulesEngineBuilder.firstMatch(OUTPUT).build();
        engine.load(List.of(Rule.builder().ruleName("passes").condition("true").action("1").build()));
        for (int i = 0; i < WARM_UPS; i++) {
            engine.run(new FactMap<>());
        }
        prepare();
        String atTheTop = outcome(takeStep());
        System.out.println(AT_THE_TOP + atTheTop);
        Throwable[] unexpected = new Throwable[1];
        // How many steps the check failed, how many failed past it, and of those, how many overflowed walking.
        int[] counts = new int[3];
        String[] firstAndLast = {"none", "none"};
        Thread deep = new Thread(null, () -> {
            try {
                for (int above = 0; above < MAX_STEPS; above++) {
                    deepest = 0;
                    target = -1;
                    try {
                        descend(0, 1, 2, 3, 4);
                    } catch (StackOverflowError expected) {
                        // The end of the stack is found.
                    }
                    prepare();
                    target = deepest - above;
                    thrown = null;
                    try {
                        descend(0, 1, 2, 3, 4);
                    } catch (StackOverflowError beforeTheTarget) {
                        // The recursion's own frames grew: the next depth is tried.
                        continue;
                    }
                    if (thrown instanceof StackOverflowError overflow && topClass(overflow).equals(CHECK_CLASS)) {
                        counts[0]++;
                        continue;
                    }
                    if (thrown instanceof StackOverflowError overflow && !reachedTheEngine(overflow)) {
                        // The overflow came before the engine's own code, in the scenario's or in a default method
                        // of the engine's interface: the next depth is tried.
                        continue;
                    }
                    String outcome = outcome(thrown);
                    if (counts[1] == 0) {
                        firstAndLast[0] = outcome;
                    }
                    firstAndLast[1] = outcome;
                    if (outcome.equals(atTheTop) || counts[1] == MAX_FAILED) {
                        return;
                    }
                    counts[1]++;
                    if (overflowedWalking(thrown)) {
                        counts[2]++;
                    }
                }
            } catch (RuntimeException | Error e) {
                unexpected[0] = e;
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        System.out.println(CHECKS_FAILED + counts[0]);
        System.out.println(FAILED_PAST_THE_CHECK + counts[1]);
        System.out.println(WALKS_OVERFLOWED + counts[2]);
        System.out.println(FIRST_PAST_THE_CHECK + firstAndLast[0]);
        System.out.println(LAST + (unexpected[0] == null ? firstAndLast[1] : "near the top: " + unexpected[0]));
    }

    // The facts, and for a run, a new engine with the rule loaded, so the run is its rule list's first, and the first
    // of each compiled copy. A load uses the engine built at the start, and compiles the rule each time.
    private static void prepare() {
        Map<String, Object> m = new HashMap<>();
        m.put("a", m);
        facts = new FactMap<>(new Fact<>("t", m));
        output = null;
        if (step.equals(VALID_RUN) || step.equals(RUN_LOOP)) {
            engine = RulesEngineBuilder.firstMatch(OUTPUT).build();
            engine.load(List.of(rule()));
        }
    }

    // The rule the step loads or runs.
    private static Rule rule() {
        return switch (step) {
            case VALID_LOAD -> Rule.builder().ruleName("long").condition(CHAIN + " != null")
                    .action("output.put('r', 1);").build();
            case VALID_RUN -> Rule.builder().ruleName("argument").condition("true")
                    .action("output.put('r', " + CHAIN + ".size());").build();
            case ANALYSIS_LOOP -> Rule.builder().ruleName("loops").condition("true")
                    .action("java.lang.Math.abs(1)x").build();
            case RUN_LOOP -> Rule.builder().ruleName("spins").condition("true")
                    .action("java.lang.String.class\u00a0(2)").build();
            default -> throw new IllegalArgumentException(step);
        };
    }

    // Loads the step's rule, or runs the rule list, and returns what it threw.
    private static Throwable takeStep() {
        try {
            if (step.equals(VALID_LOAD) || step.equals(ANALYSIS_LOOP)) {
                engine.load(List.of(rule()));
            } else {
                output = engine.run(facts);
            }
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // OK if the step loaded, or ran with the output expected, LOOPED if it failed with the loop's message, or else
    // what it threw, or what it output, and where an overflow in its cause chain overflowed.
    private static String outcome(Throwable t) {
        if (t == null) {
            return step.equals(VALID_RUN) && !Map.of("r", 1).equals(output) ? "output " + output : OK;
        }
        if (String.valueOf(t.getMessage()).contains(LOOP_MESSAGE)) {
            return LOOPED;
        }
        for (Throwable link = t; link != null; link = link.getCause()) {
            if (link instanceof StackOverflowError) {
                StackTraceElement[] frames = link.getStackTrace();
                return t + ", overflowed at " + (frames.length > 0 ? frames[0] : "no frame");
            }
        }
        return t.toString();
    }

    // Whether an overflow in the cause chain overflowed as MVEL's loop detection walked the stack, in the methods of
    // CallSites that walk it.
    private static boolean overflowedWalking(Throwable t) {
        for (Throwable link = t; link != null; link = link.getCause()) {
            if (link instanceof StackOverflowError) {
                for (StackTraceElement frame : link.getStackTrace()) {
                    if (frame.getClassName().equals(CallSites.class.getName())
                            && WALKS.contains(frame.getMethodName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Whether a frame of the engine's own code, its core's or this module's, is on the overflow's stack.
    private static boolean reachedTheEngine(Throwable t) {
        for (StackTraceElement frame : t.getStackTrace()) {
            String name = frame.getClassName();
            if ((name.startsWith(ENGINE_CORE) || name.startsWith(MVEL)) && !name.startsWith(SCENARIO)) {
                return true;
            }
        }
        return false;
    }

    private static String topClass(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        return frames.length > 0 ? frames[0].getClassName() : "";
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it takes the step.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            thrown = takeStep();
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
