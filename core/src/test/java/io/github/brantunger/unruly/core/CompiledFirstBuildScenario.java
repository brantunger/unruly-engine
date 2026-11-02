package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Run by {@link CompiledFirstBuildTest} in a new JVM started with {@code -Xcomp}, so the build it makes deep in a stack
 * is the JVM's first, with {@link StackHeadroom}'s check compiled before its first run. On a thread with a small
 * stack, it finds where the stack ends, then builds an engine at each depth from the end up, a frame at a time, until a
 * build doesn't overflow, and prints {@link #VM_INFO} and the JVM's {@code java.vm.info}, {@link #OVERFLOWED} and how
 * many builds overflowed, {@link #IN_THE_CHECK} and how many of those overflowed in the check's recursion, and
 * {@link #DEEP} and what the first build that didn't overflow threw, or {@link #NOTHING}. Then, at the top of the main
 * thread's stack, it prints what the deep builds may have left broken for the life of the JVM (#1305):
 * {@link #CLOCK} and whether the JDK's clock gives an instant, {@link #FORMATTER} and an instant formatted by
 * {@code Instant.toString()}, {@link #ERROR} and the message of a new {@link ExceptionInInitializerError}, and
 * {@link #ENGINE} and what an engine built, loaded and run after them returned. The test keeps the recursion that
 * finds the end from being compiled, so its frames are the same size each time. Its checks are anonymous classes, not
 * lambdas, made at the top: on JDK 21 a deep build that overflowed left the JDK's {@code MethodType} broken, which the
 * first lambda linked after it would have thrown on.
 */
final class CompiledFirstBuildScenario {

    static final String VM_INFO = "SCENARIO vm.info: ";
    static final String OVERFLOWED = "SCENARIO overflowed: ";
    static final String IN_THE_CHECK = "SCENARIO in the check: ";
    static final String BUILT = "SCENARIO built: ";
    static final String DEEP = "SCENARIO deep: ";
    static final String CLOCK = "SCENARIO clock: ";
    static final String FORMATTER = "SCENARIO formatter: ";
    static final String ERROR = "SCENARIO error: ";
    static final String ENGINE = "SCENARIO engine: ";
    /** What {@link #DEEP} says of a build that threw nothing. */
    static final String NOTHING = "nothing";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it builds at, at most. */
    private static final int MAX_BUILDS = 100_000;

    private static final Supplier<Map<String, Object>> OUTPUT = new Supplier<>() {
        @Override
        public Map<String, Object> get() {
            return new HashMap<>();
        }
    };
    private static final ToyExpressionLanguage TOY = new ToyExpressionLanguage();
    private static final Rule RULE = Rule.builder().ruleName("r").condition("x == 1").action("put hit true").build();
    private static final Check CLOCKS = new Check() {
        @Override
        public Object run() {
            return Clock.systemUTC().instant() != null;
        }
    };
    private static final Check FORMATS = new Check() {
        @Override
        public Object run() {
            return Instant.ofEpochSecond(1).toString();
        }
    };
    private static final Check ERRORS = new Check() {
        @Override
        public Object run() {
            return new ExceptionInInitializerError("probe").getMessage();
        }
    };
    private static final Check ENGINES = new Check() {
        @Override
        public Object run() {
            try (RulesEngine<Map<String, Object>> engine = build()) {
                engine.load(List.of(RULE));
                return engine.run(new FactMap<>(new Fact<Object>("x", 1)));
            }
        }
    };

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static Throwable thrown;

    private CompiledFirstBuildScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        int[] overflowed = new int[2];
        Throwable[] got = new Throwable[1];
        boolean[] built = new boolean[1];
        Thread deep = new Thread(null, new Runnable() {
            @Override
            public void run() {
                for (int above = 0; above < MAX_BUILDS; above++) {
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
                        // The recursion's own frames grew: the next depth is tried.
                        continue;
                    }
                    if (!(thrown instanceof StackOverflowError overflow)) {
                        got[0] = thrown;
                        built[0] = true;
                        return;
                    }
                    overflowed[0]++;
                    if (inTheCheck(overflow)) {
                        overflowed[1]++;
                    }
                }
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        print(VM_INFO, System.getProperty("java.vm.info"));
        print(OVERFLOWED, overflowed[0]);
        print(IN_THE_CHECK, overflowed[1]);
        print(BUILT, built[0]);
        print(DEEP, got[0] == null ? NOTHING : got[0]);
        check(CLOCK, CLOCKS);
        check(FORMATTER, FORMATS);
        check(ERROR, ERRORS);
        check(ENGINE, ENGINES);
    }

    // Whether an overflow was thrown in the recursion StackHeadroom's checks share: by its top frame, as the check of
    // the room for initializing classes recurses more frames under -Xcomp than the JVM keeps of a stack trace.
    private static boolean inTheCheck(StackOverflowError overflow) {
        StackTraceElement[] trace = overflow.getStackTrace();
        return trace.length > 0 && StackHeadroom.class.getName().equals(trace[0].getClassName())
                && "descend".equals(trace[0].getMethodName());
    }

    /** What a check at the top of the stack does, after the deep builds. */
    private interface Check {
        Object run() throws Exception;
    }

    // Prints what the check returned, or what it threw, with its cause.
    private static void check(String label, Check check) {
        try {
            print(label, check.run());
        } catch (Exception | Error e) {
            print(label, e.getCause() == null ? e : String.valueOf(e).concat(" <- ")
                    .concat(String.valueOf(e.getCause())));
        }
    }

    // Joined with concat, not +, whose call site the JDK links with method handles, which a deep build may have
    // broken: the line is printed whatever it left.
    private static void print(String label, Object value) {
        System.out.println(label.concat(String.valueOf(value)));
    }

    private static RulesEngine<Map<String, Object>> build() {
        return RulesEngineBuilder.firstMatch(OUTPUT).language(TOY).build();
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it builds.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            try {
                build();
            } catch (Throwable t) {
                thrown = t;
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
