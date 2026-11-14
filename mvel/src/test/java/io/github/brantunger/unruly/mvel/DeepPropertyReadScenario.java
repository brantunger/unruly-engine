package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Run by {@link PropertyReadGuardTest} in a new JVM: the first run of each of {@link #ACTIONS}, which reads a property
 * from a value, with a supplied fact named like the property, at every depth near the end of a stack. To tell where the
 * name stands, {@link GuardedVariables} walks the stack, and a walk with no room overflows. It first runs a rule that
 * passes {@value #WARM_UPS} times, then makes each action's run once at the top of the stack, so the classes it uses
 * are loaded, and prints {@link #AT_THE_TOP} and what the run did: {@value #REFUSED} if the rule failed because the
 * fact can't be read in place of a property, the output if it gave one, or else what it threw. Then, on a thread with a
 * small stack, it finds where the stack ends and, for each action, makes the run at each depth from the end up, a
 * frame at a time, each on a new engine, so the run is its copy's first, until {@value #ENOUGH} runs in a row were
 * refused. For each action it prints {@link #READ} and how many runs gave an output, {@link #WALKS_OVERFLOWED} and how
 * many failed with an error thrown in the JDK's code that walks a stack, as {@link GuardedVariables} walked it, and
 * {@link #LAST} and what the last run did. After each line's name comes the action, then {@link #IS}.
 *
 * <p>
 * It stops at the first run that read the fact: one at the top of the stack that wasn't refused, or a deep one that
 * gave an output. It prints {@link #FIRST_READ}, the action, what the run did and where, and makes no run after it, so
 * an engine that reads the fact fails the test at once, with the place that read it. It names
 * {@link GuardedVariables} and its refusal by their class names, so it compiles where they don't exist.
 * </p>
 */
final class DeepPropertyReadScenario {

    static final String AT_THE_TOP = "SCENARIO at the top: ";
    static final String FIRST_READ = "SCENARIO first read the fact: ";
    static final String READ = "SCENARIO read the fact: ";
    static final String WALKS_OVERFLOWED = "SCENARIO overflowed walking: ";
    static final String LAST = "SCENARIO last: ";
    static final String IS = " -> ";
    static final String REFUSED = "refused";
    /**
     * A name after a value in parentheses and after a new object, a with block's value, a projection's element and a
     * function called from a with block: a run that supplies {@code tier} or {@code size} is refused at each.
     */
    static final List<String> ACTIONS = List.of(
            "output.put('r', (order).tier)",
            "output.put('r', new java.util.ArrayList().size)",
            "with (order) { note = tier }; output.put('r', order.note)",
            "output.put('r', (tier in orders))",
            "def f() { tier }; with (order) { note = f() }; output.put('r', order.note)");

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it makes an action's run at, at most. */
    private static final int MAX_STEPS = 100_000;
    /** How many runs in a row must be refused before it stops: from there up, the stack has room. */
    private static final int ENOUGH = 20;
    /**
     * How many times it runs a rule that passes first, so the engine's check of its stack's room is compiled, as it is
     * in a JVM that has run engines for a while: compiled, its frames are a third of the size, so the room it makes is
     * less than a walk of the stack takes. The test runs it with {@code -Xbatch}, so the compiling is done before the
     * warm-up goes on, however busy the machine is.
     */
    private static final int WARM_UPS = 20_000;
    private static final String GUARD = DeepPropertyReadScenario.class.getPackageName() + ".GuardedVariables";
    private static final String REFUSAL = GUARD + "$FactReadAsProperty";
    private static final String QUESTION = "isResolveable";
    // The JDK's classes that walk a stack: StackWalker and StackStreamFactory, with its nested classes.
    private static final List<String> JDK_WALK = List.of("java.lang.StackWalker", "java.lang.StackStreamFactory");

    // Linked here, at the top of the stack, as the scenario's own lambdas are: an application's lambda linked deep
    // could leave one of the JDK's classes unusable, which the engine can't help.
    private static final Supplier<Map<String, Object>> OUTPUT = TreeMap::new;

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static RulesEngine<Map<String, Object>> engine;
    private static FactMap<Object> facts;
    private static Map<String, Object> output;
    private static Throwable thrown;

    private DeepPropertyReadScenario() {
    }

    /** What the rules read their properties from. */
    public static class Order {

        private String note = "n";

        public String getTier() {
            return "gold";
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        try (RulesEngine<Map<String, Object>> warm = RulesEngineBuilder.firstMatch(OUTPUT).build()) {
            warm.load(List.of(Rule.builder().ruleName("passes").condition("true").action("1").build()));
            for (int i = 0; i < WARM_UPS; i++) {
                warm.run(new FactMap<>());
            }
        }
        for (String action : ACTIONS) {
            prepare(action);
            run();
            String atTheTop = outcome();
            System.out.println(AT_THE_TOP + action + IS + atTheTop);
            if (!atTheTop.equals(REFUSED)) {
                System.out.println(FIRST_READ + action + IS + atTheTop + ", at the top of the stack");
                return;
            }
        }
        Throwable[] unexpected = new Throwable[1];
        Thread deep = new Thread(null, () -> {
            try {
                for (String action : ACTIONS) {
                    if (!sweep(action)) {
                        return;
                    }
                }
            } catch (RuntimeException | Error e) {
                unexpected[0] = e;
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        if (unexpected[0] != null) {
            System.out.println("SCENARIO near the top: " + unexpected[0]);
        }
    }

    // Makes the action's run at each depth from the end of the stack up, until one reads the fact, and prints what the
    // runs did. Returns whether none read it.
    private static boolean sweep(String action) {
        int read = 0;
        int walksOverflowed = 0;
        int refusedInARow = 0;
        String last = "none";
        for (int above = 0; above < MAX_STEPS && refusedInARow < ENOUGH && read == 0; above++) {
            deepest = 0;
            target = -1;
            try {
                descend(0, 1, 2, 3, 4);
            } catch (StackOverflowError expected) {
                // The end of the stack is found.
            }
            prepare(action);
            target = deepest - above;
            try {
                descend(0, 1, 2, 3, 4);
            } catch (StackOverflowError beforeTheTarget) {
                // The recursion's own frames grew: the next depth is tried.
                continue;
            }
            last = outcome();
            refusedInARow = last.equals(REFUSED) ? refusedInARow + 1 : 0;
            if (thrown == null) {
                read++;
                System.out.println(FIRST_READ + action + IS + last + ", " + above + " frames above the stack's end");
            } else if (overflowedWalking(thrown)) {
                walksOverflowed++;
            }
        }
        System.out.println(READ + action + IS + read);
        System.out.println(WALKS_OVERFLOWED + action + IS + walksOverflowed);
        System.out.println(LAST + action + IS + last);
        return read == 0;
    }

    // A new engine with the action's rule loaded, so the run is its rule list's first, and the first of each compiled
    // copy, and the facts: those the rules may read, and two named like the properties they read from a value.
    private static void prepare(String action) {
        if (engine != null) {
            engine.close();
        }
        engine = RulesEngineBuilder.firstMatch(OUTPUT).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action(action).build()));
        facts = new FactMap<>();
        facts.setValue("order", new Order());
        facts.setValue("orders", List.of(new Order()));
        facts.setValue("tier", "other");
        facts.setValue("size", 9);
        output = null;
        thrown = null;
    }

    // Runs the rule list, and keeps its output or what it threw.
    private static void run() {
        try {
            output = engine.run(facts);
        } catch (Throwable t) {
            thrown = t;
        }
    }

    // REFUSED if the run failed because the fact can't be read in place of a property, the output if it gave one, or
    // else what it threw.
    private static String outcome() {
        if (thrown == null) {
            return "output " + output;
        }
        for (Throwable link = thrown; link != null; link = link.getCause()) {
            if (link.getClass().getName().equals(REFUSAL)) {
                return REFUSED;
            }
        }
        return thrown.toString();
    }

    // Whether an error in the cause chain was thrown as GuardedVariables walked the stack, in the JDK's code that
    // walks it, under the question MVEL asked: the overflow, or the InternalError JDK 25 and 26 may wrap it in.
    private static boolean overflowedWalking(Throwable t) {
        for (Throwable link = t; link != null; link = link.getCause()) {
            if (link instanceof Error) {
                boolean walking = false;
                for (StackTraceElement frame : link.getStackTrace()) {
                    String name = frame.getClassName();
                    if (name.equals(GUARD) && frame.getMethodName().equals(QUESTION)) {
                        if (walking) {
                            return true;
                        }
                        break;
                    }
                    walking |= JDK_WALK.stream().anyMatch(name::startsWith);
                }
            }
        }
        return false;
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it makes the run.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            run();
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
