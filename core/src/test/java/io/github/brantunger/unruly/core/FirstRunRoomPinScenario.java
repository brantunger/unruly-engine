package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run by {@link FirstRunRoomPinTest} in a new JVM, so the language it runs deep in a stack has had no run. Once a run
 * of another language has loaded the run's classes, it finds, on a thread with a small stack, where the stack ends,
 * then, from the end up, the first depth where {@link StackHeadroom#check()} fits and the first where
 * {@link StackHeadroom#checkFirstRun()} does. Between them only the larger check overflows: it runs the language's
 * engine once, in the middle, so though the run's own frames put each check a few frames deeper than where it was
 * found, both stay well inside the window, and prints what the run threw. The test keeps the recursions from being
 * compiled, so their frames are the same size each time.
 *
 * <p>
 * It prints {@link #JAVA} and the JVM's version, {@link #DEPTH} and the depth it ran at, {@link #WINDOW} and the two
 * depths found, then {@link #OUTCOME} and what the run threw, {@link #CHECK} and the check of {@link StackHeadroom} it
 * was thrown in, and {@link #BEFORE_RUNS} and how many times a listener was told the run began. What keeps it from
 * running the engine where only the larger check overflows, or anything the deep thread throws outside the run, it
 * prints after {@link #FAILED}, and it exits 0 either way, so the test shows what went wrong.
 * </p>
 */
final class FirstRunRoomPinScenario {

    static final String JAVA = "SCENARIO java: ";
    static final String DEPTH = "SCENARIO depth: ";
    static final String WINDOW = "SCENARIO window: ";
    static final String OUTCOME = "SCENARIO outcome: ";
    static final String CHECK = "SCENARIO check: ";
    static final String BEFORE_RUNS = "SCENARIO before runs: ";
    static final String FAILED = "SCENARIO failed: ";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How far above the end of the stack it looks for the depths where the checks fit, at most. */
    private static final int MAX_ABOVE = 2_000;

    // What the recursion does at its target depth.
    private static final int NOTHING = 0;
    private static final int PROBE = 1;
    private static final int RUN = 2;

    // Built and loaded here, at the top of the stack, as the scenario's facts are: nothing is linked deep but the run.
    private static final AtomicInteger BEFORE_RUN_COUNT = new AtomicInteger();
    private static final RulesEngine<Map<String, Object>> PINNED = engine(new Pinned(), BEFORE_RUN_COUNT);
    private static final FactMap<Object> FACTS = new FactMap<>();

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static int action;
    private static boolean reached;
    private static boolean checkFits;
    private static boolean firstRunFits;
    private static Throwable thrown;
    private static boolean returned;

    private FirstRunRoomPinScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println(JAVA + System.getProperty("java.version"));
        // Loads the run's classes, with a language of another class, so the pinned language's run stays its first.
        engine(new Warm(), new AtomicInteger()).run(FACTS);
        Thread deep = new Thread(null, () -> {
            try {
                String failure = pin();
                if (failure != null) {
                    System.out.println(FAILED + failure);
                }
            } catch (Throwable t) {
                System.out.println(FAILED + "the deep thread threw " + t + " outside the run");
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
    }

    /**
     * Finds the depths and runs the engine between them.
     *
     * @return Why it didn't run the engine where only the larger check overflows, or {@code null} if it did
     */
    private static String pin() {
        action = NOTHING;
        reachTarget(-1);
        int end = deepest;
        int checkFrom = -1;
        int firstRunFrom = -1;
        action = PROBE;
        for (int above = 0; above <= MAX_ABOVE && firstRunFrom < 0; above++) {
            if (!reachTarget(end - above)) {
                // The recursion's own frames grew, as the JIT compiled it differently: the next depth is tried.
                continue;
            }
            if (checkFits && checkFrom < 0) {
                checkFrom = end - above;
            }
            if (firstRunFits) {
                firstRunFrom = end - above;
            }
        }
        System.out.println(WINDOW + "the stack ends at depth " + end + ", check() fits from depth " + checkFrom
                + " and checkFirstRun() from depth " + firstRunFrom);
        if (checkFrom < 0 || firstRunFrom < 0) {
            return "no depth within " + MAX_ABOVE + " of the end where both checks fit";
        }
        if (checkFrom - firstRunFrom < 2) {
            return "no depth where checkFirstRun() overflows and check() fits, with a depth on each side of it";
        }
        int middle = (checkFrom + firstRunFrom) / 2;
        System.out.println(DEPTH + middle);
        action = RUN;
        if (!reachTarget(middle)) {
            return "the recursion overflowed before depth " + middle;
        }
        if (firstRunFits || !checkFits) {
            return "at depth " + middle + ", checkFirstRun() " + (firstRunFits ? "fit" : "overflowed") + " and check() "
                    + (checkFits ? "fit" : "overflowed") + ", so the engine wasn't run";
        }
        System.out.println(OUTCOME + (returned ? "returned" : thrown));
        System.out.println(CHECK + (thrown == null ? "none" : headroomCheck(thrown)));
        System.out.println(BEFORE_RUNS + BEFORE_RUN_COUNT.get());
        return null;
    }

    /**
     * Recurses to the target depth, and there does what {@link #action} says, or, for a target of {@code -1}, to the
     * end of the stack, recording how deep it got.
     *
     * @return Whether it reached the target
     */
    private static boolean reachTarget(int depth) {
        deepest = 0;
        target = depth;
        reached = false;
        try {
            descend(0, 1, 2, 3, 4);
        } catch (StackOverflowError expected) {
            // The end of the stack is found, or, with a target, it was before it.
        }
        return reached;
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it probes the checks
    // and, if told to, runs the engine.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            reached = true;
            if (action == NOTHING) {
                return a;
            }
            try {
                StackHeadroom.check();
                checkFits = true;
            } catch (StackOverflowError tooDeep) {
                checkFits = false;
            }
            try {
                StackHeadroom.checkFirstRun();
                firstRunFits = true;
            } catch (StackOverflowError tooDeep) {
                firstRunFits = false;
            }
            if (action == RUN && checkFits && !firstRunFits) {
                try {
                    PINNED.run(FACTS);
                    returned = true;
                } catch (Throwable t) {
                    thrown = t;
                }
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }

    /**
     * Names the check of {@link StackHeadroom} an overflow was thrown in: the method below the frames of the recursion
     * every check shares, as {@code DeepFirstBuildScenario} names it. The first run's check recurses
     * {@value StackHeadroom#FIRST_RUN_FRAMES} frames, fewer than the 1,024 the JVM keeps of a stack trace, so the
     * method is in it.
     *
     * @param e The overflow
     * @return The check's name, or {@code null} if the overflow wasn't thrown in one
     */
    private static String headroomCheck(Throwable e) {
        for (StackTraceElement frame : e.getStackTrace()) {
            if (!frame.getClassName().equals(StackHeadroom.class.getName())) {
                return null;
            }
            if (!"descend".equals(frame.getMethodName())) {
                return frame.getMethodName();
            }
        }
        return null;
    }

    private static RulesEngine<Map<String, Object>> engine(Lang language, AtomicInteger beforeRuns) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        beforeRuns.incrementAndGet();
                    }
                }).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("yes").action("done").build()));
        return engine;
    }

    /** A language whose every condition is true and whose every action does nothing. */
    private abstract static class Lang implements ExpressionLanguage {

        private final String name;

        Lang(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    // A language's first run is tracked per class, so the warm-up's language and the pinned one are of classes of
    // their own.
    private static final class Warm extends Lang {
        Warm() {
            super("warm");
        }
    }

    private static final class Pinned extends Lang {
        Pinned() {
            super("pinned");
        }
    }
}
