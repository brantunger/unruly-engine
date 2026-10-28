package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleSetInfo;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Run by {@link DeepFirstToStringTest} in a new JVM, so the {@code toString()} it calls deep in a stack is the JVM's
 * first of its kind. The system property {@value #MODE} chooses which: a rule's with a validity window ({@code rule}),
 * with no engine built; a run's result's ({@code result}) or a loaded rule set's ({@code rules}), after an engine was
 * built, loaded and run at the top of the main thread's stack; a result's or a rule set's that the application made
 * itself with {@code RunResult.of} or {@code RuleSetInfo.of}, as a test double does ({@code ownResult},
 * {@code ownRules}), with no engine built; or run options' with tags ({@code options}), with no engine built and
 * {@code withTags} called at the top. Everything the call needs is made at the top of the stack, but the rule set
 * {@code engine.rules()} makes for the {@code rules} mode, which the deep call makes; and with no engine built the
 * same {@code toString()} is called at the top once on a rule with no window, or on options with no tags, so the code
 * path is linked and only what the instant or the tags need is new to the deep call. After a build, and for the
 * application's own result and rule set, the deep call is the JVM's first {@code toString()} of its kind, as an
 * application's would be. On a thread with a small stack, it finds where the stack ends, then calls at each depth
 * from the end up, a frame at a time, until a call doesn't overflow, and prints {@link #OVERFLOWED} and how many
 * calls overflowed, {@link #DEEP} and what the first call that didn't overflow threw, or {@link #NOTHING}, and
 * {@link #TEXT} and what it returned. Then, at the top of the main thread's stack, it prints what the deep call may
 * have left broken for the life of the JVM (#1302): {@link #FORMATTER} and an instant formatted by
 * {@code Instant.toString()}, {@link #STREAM} and a stream pipeline's result, {@link #ERROR} and the message of a new
 * {@link ExceptionInInitializerError}, and {@link #ENGINE} and what an engine built, loaded and run after the call
 * returned. The test keeps the recursion that finds the end from being compiled, so its frames are the same size each
 * time. With the system property {@value #PRELINK} set to {@code true}, it first loads and links the engine's
 * {@code internal.Text} and {@code api.Names} without initializing them, as a class-data archive or the JDK's AOT
 * cache leaves an application's classes (#1313): on a plain class path the deep call must load and verify them before
 * it can initialize them, which needs more room than their initialization, so a depth that gets through the load has
 * room to spare, and the pre-link takes that shield away.
 */
final class DeepFirstToStringScenario {

    /** The system property that chooses what the scenario calls. */
    static final String MODE = "deep.toString";
    /** The system property that, set to {@code true}, has the scenario pre-link {@link #PRELINKED} first. */
    static final String PRELINK = "deep.prelink";
    /** The name of the thread the deep call runs on. */
    static final String DEEP_THREAD = "deep";
    static final String OVERFLOWED = "SCENARIO overflowed: ";
    static final String DEEP = "SCENARIO deep: ";
    static final String TEXT = "SCENARIO text: ";
    static final String FORMATTER = "SCENARIO formatter: ";
    static final String STREAM = "SCENARIO stream: ";
    static final String ERROR = "SCENARIO error: ";
    static final String ENGINE = "SCENARIO engine: ";
    /** What {@link #DEEP} says of a call that threw nothing. */
    static final String NOTHING = "nothing";
    /** The engine's clock, so a run's start is a known instant; a load's time is the system clock's. */
    static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L, 123_000_000L);
    /** What a result or a rule set of the application's own says its rules' checksum is. */
    static final String CHECKSUM = "9f2c";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it calls at, at most. */
    private static final int MAX_CALLS = 100_000;
    /** The classes it pre-links, named by their names, as a class literal would load them. */
    private static final List<String> PRELINKED = List.of("io.github.brantunger.unruly.internal.Text",
            "io.github.brantunger.unruly.api.Names");

    // Made here, at the top of the stack, as the scenario's own lambdas are linked: a deep call that left a class of
    // the JDK's method handles broken, as linking a method reference deep can, would otherwise break the checks too.
    private static final Supplier<Map<String, Object>> OUTPUT = HashMap::new;
    private static final Rule PLAIN = Rule.builder().ruleName("plain").condition("x == 1").action("put hit true")
            .build();
    private static final Rule DATED = PLAIN.toBuilder().ruleName("dated").validFrom(NOW).validTo(NOW.plusSeconds(60))
            .build();
    private static final Check FORMATS = () -> Instant.ofEpochSecond(1_700_000_000L, 123_456_789L).toString();
    private static final Check STREAMS = () -> List.of("a", "bb").stream().map(String::length).toList();
    private static final Check ERRORS = () -> new ExceptionInInitializerError("probe").getMessage();
    private static final Check ENGINES = () -> {
        try (RulesEngine<Map<String, Object>> engine = engine()) {
            engine.load(List.of(PLAIN));
            return engine.run(facts());
        }
    };

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static Supplier<String> call;
    private static Throwable thrown;
    private static String text;

    private DeepFirstToStringScenario() {
    }

    public static void main(String[] args) throws InterruptedException, ClassNotFoundException {
        if (Boolean.getBoolean(PRELINK)) {
            prelink();
        }
        String mode = System.getProperty(MODE);
        if ("rule".equals(mode)) {
            PLAIN.toString();
            call = DATED::toString;
        } else if ("result".equals(mode) || "rules".equals(mode)) {
            RulesEngine<Map<String, Object>> engine = engine();
            engine.load(List.of(DATED));
            RunResult<Map<String, Object>> result = engine.runWithResult(facts());
            call = "result".equals(mode) ? result::toString : () -> engine.rules().toString();
        } else if ("ownResult".equals(mode)) {
            // A result and a rule set of the application's own, as a test double's, which no engine made.
            RunResult<Map<String, Object>> own = RunResult.of(Map.of("hit", true), List.of(DATED), CHECKSUM);
            call = own::toString;
        } else if ("ownRules".equals(mode)) {
            RuleSetInfo own = RuleSetInfo.of(List.of(DATED), CHECKSUM, NOW);
            call = own::toString;
        } else {
            Set<String> tags = new HashSet<>();
            tags.add("eu");
            RunOptions tagged = RunOptions.defaults().withTags(tags);
            RunOptions.defaults().toString();
            call = tagged::toString;
        }
        int[] overflowed = new int[1];
        Throwable[] got = new Throwable[1];
        Thread deep = new Thread(null, () -> {
            for (int above = 0; above < MAX_CALLS; above++) {
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
                    // The recursion's own frames grew, as the JIT compiled it differently: the next depth is tried.
                    continue;
                }
                if (!(thrown instanceof StackOverflowError)) {
                    got[0] = thrown;
                    return;
                }
                overflowed[0]++;
            }
        }, DEEP_THREAD, STACK_BYTES);
        deep.start();
        deep.join();
        print(OVERFLOWED, overflowed[0]);
        print(DEEP, got[0] == null ? NOTHING : got[0]);
        print(TEXT, text);
        check(FORMATTER, FORMATS);
        check(STREAM, STREAMS);
        check(ERROR, ERRORS);
        check(ENGINE, ENGINES);
    }

    // Loads each class and links it, as reading its methods verifies it, without initializing it (#1313).
    private static void prelink() throws ClassNotFoundException {
        for (String name : PRELINKED) {
            Class.forName(name, false, DeepFirstToStringScenario.class.getClassLoader()).getDeclaredMethods();
        }
    }

    /** What a check at the top of the stack does, after the deep call. */
    private interface Check {
        Object run() throws Exception;
    }

    // Prints what the check returned, or what it threw.
    private static void check(String label, Check check) {
        try {
            print(label, check.run());
        } catch (Exception | Error e) {
            print(label, e);
        }
    }

    // Joined with concat, not +, whose call site the JDK links with method handles, which the deep call may have
    // broken: the line is printed whatever it left.
    private static void print(String label, Object value) {
        System.out.println(label.concat(String.valueOf(value)));
    }

    private static RulesEngine<Map<String, Object>> engine() {
        return RulesEngineBuilder.firstMatch(OUTPUT).language(new ToyExpressionLanguage())
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)).build();
    }

    private static FactMap<Object> facts() {
        return new FactMap<>(new Fact<Object>("x", 1));
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it calls.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            try {
                text = call.get();
            } catch (Throwable t) {
                thrown = t;
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
