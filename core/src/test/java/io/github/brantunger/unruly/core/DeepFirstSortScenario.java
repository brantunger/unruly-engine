package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Run by {@link DeepFirstSortTest} in a new JVM, with no engine built, so the sort it calls deep in a stack is the
 * JVM's first of its kind: a rule's build with tags ({@code rule}), run options' {@code withTags} ({@code options}),
 * or the first read of a fact class's properties ({@code facts}), chosen by the system property {@value #MODE}. The
 * JVM is put in the state #1298 needs first, at the top of the main thread's stack: {@code java.util.TreeSet} and
 * {@code TreeMap} are loaded through the application's class loader and left uninitialized, as a class literal or an
 * {@code instanceof} anywhere in an application leaves them, and each path is called once shallow in a way that
 * returns before it sorts, a build with a blank name and {@code withTags} with no tags, which fail, and a read of a
 * class the engine gives no properties, which answers that it has none, so the path's classes up to the sort are
 * resolved and the sort is new to the deep call. Everything the deep call
 * needs is made at the top: the builder, the options, the tags and the fact. On a thread with a small stack, it finds
 * where the stack ends, then calls at each depth from the end up, a frame at a time, until a call doesn't overflow,
 * and prints {@link #OVERFLOWED} and how many calls overflowed, {@link #DEEP} and what the first call that didn't
 * overflow threw, or {@link #NOTHING}, and {@link #TEXT} and what it returned. Then, at the top of the main thread's
 * stack, it prints what the deep call may have left broken for the life of the JVM: {@link #TREE_SET} and
 * {@link #TREE_MAP}, a new one of each; {@link #RULE} and {@link #TAGLESS}, the tags of a rule built with and without
 * them; {@link #OPTIONS}, a run's tags; {@link #FACTS}, a fact's properties; and {@link #ENGINE}, what an engine
 * built, loaded and run with tags returned. {@link #WALKING} is printed before the thread starts, so the test can
 * tell what was initialized before the walk from what the walk initialized. No lambda or method reference is linked
 * before the walk, so nothing the JDK makes for one is counted to it, and the checks after it are plain calls, which
 * run whatever the deep call left. The test keeps the recursion that finds the end from being compiled, so its frames
 * are the same size each time.
 */
final class DeepFirstSortScenario {

    /** The system property that chooses what the scenario calls. */
    static final String MODE = "deep.sort";
    /** The name of the thread that calls deep, which {@code -Xlog:class+init} names in what it initialized. */
    static final String DEEP_THREAD = "deep";
    static final String WALKING = "SCENARIO walking";
    static final String OVERFLOWED = "SCENARIO overflowed: ";
    static final String DEEP = "SCENARIO deep: ";
    static final String TEXT = "SCENARIO text: ";
    static final String TREE_SET = "SCENARIO treeSet: ";
    static final String TREE_MAP = "SCENARIO treeMap: ";
    static final String RULE = "SCENARIO rule: ";
    static final String TAGLESS = "SCENARIO tagless: ";
    static final String OPTIONS = "SCENARIO options: ";
    static final String FACTS = "SCENARIO facts: ";
    static final String ENGINE = "SCENARIO engine: ";
    /** What {@link #DEEP} says of a call that threw nothing. */
    static final String NOTHING = "nothing";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it calls at, at most. */
    private static final int MAX_CALLS = 100_000;

    // What the deep call is given, made at the top of the stack: the tags out of String order, so the call sorts.
    private static String mode;
    private static List<String> tags;
    private static Rule.RuleBuilder builder;
    private static RunOptions options;
    private static Bean bean;

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static Throwable thrown;
    private static Object result;

    private DeepFirstSortScenario() {
    }

    /** A fact of the application's, with two getters, which reflection lists in no promised order. */
    public static final class Bean {

        public String getName() {
            return "bob";
        }

        public int getAge() {
            return 42;
        }
    }

    public static void main(String[] args) throws ClassNotFoundException, InterruptedException {
        mode = System.getProperty(MODE);
        // Loaded through the application's loader and not initialized, as a class literal or an instanceof leaves
        // them: the deep call then finds each in the loader's records without running the loader, so its static
        // initializer is the first thing on the path that can overflow (#1298).
        ClassLoader loader = DeepFirstSortScenario.class.getClassLoader();
        Class.forName("java.util.TreeSet", false, loader);
        Class.forName("java.util.TreeMap", false, loader);
        tags = new ArrayList<>();
        tags.add("retail");
        tags.add("eu");
        builder = Rule.builder().ruleName("tagged").condition("x == 1").action("put hit true").tags(tags);
        options = RunOptions.defaults();
        bean = new Bean();
        // Each path once, shallow, returning before it sorts, so its classes are resolved and only the sort is new to
        // the deep call: a build with a blank name, withTags with no tags, and a read of a class the engine gives no
        // properties, which fills nothing to sort.
        try {
            Rule.builder().ruleName(" ").condition("x == 1").action("put hit true").tags(tags).build();
        } catch (IllegalStateException expected) {
            // The blank name, checked before the tags.
        }
        try {
            options.withTags(List.of());
        } catch (IllegalArgumentException expected) {
            // No tags, checked before the sort.
        }
        FactProperties.has(String.class, "name");

        System.out.println(WALKING);
        Walk walk = new Walk();
        Thread deep = new Thread(null, walk, DEEP_THREAD, STACK_BYTES);
        deep.start();
        deep.join();
        print(OVERFLOWED, walk.overflowed);
        print(DEEP, walk.got == null ? NOTHING : walk.got);
        print(TEXT, result);
        check(TREE_SET);
        check(TREE_MAP);
        check(RULE);
        check(TAGLESS);
        check(OPTIONS);
        check(FACTS);
        check(ENGINE);
    }

    // Calls at each depth from the end of the stack up, until a call doesn't overflow.
    private static final class Walk implements Runnable {

        private int overflowed;
        private Throwable got;

        @Override
        public void run() {
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
                    got = thrown;
                    return;
                }
                overflowed++;
            }
        }
    }

    // The deep call: the mode's path, with what was made at the top.
    private static Object call() {
        if ("rule".equals(mode)) {
            return builder.build();
        }
        if ("options".equals(mode)) {
            return options.withTags(tags);
        }
        return FactProperties.propertyNames(bean);
    }

    // Prints what a check at the top of the stack, after the deep call, returned, or what it threw.
    private static void check(String label) {
        try {
            print(label, checked(label));
        } catch (Exception | Error e) {
            print(label, e);
        }
    }

    private static Object checked(String label) {
        switch (label) {
            case TREE_SET:
                return new TreeSet<>(List.of("b", "a"));
            case TREE_MAP:
                return new TreeMap<>(Map.of("b", 1, "a", 2));
            case RULE:
                return Rule.builder().ruleName("r").condition("x == 1").action("put hit true").tags(List.of("b", "a"))
                        .build().getTags();
            case TAGLESS:
                return Rule.builder().ruleName("r").condition("x == 1").action("put hit true").build().getTags();
            case OPTIONS:
                return RunOptions.defaults().withTags(List.of("b", "a")).tags();
            case FACTS:
                return FactProperties.propertyNames(new Bean());
            default:
                return engine();
        }
    }

    // An engine built, loaded with a tagged rule and run with the rule's tag, after the deep call.
    private static Map<String, Object> engine() {
        Supplier<Map<String, Object>> output = new Supplier<>() {
            @Override
            public Map<String, Object> get() {
                return new HashMap<>();
            }
        };
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.firstMatch(output)
                .language(new ToyExpressionLanguage()).build()) {
            engine.load(List.of(Rule.builder().ruleName("tagged").condition("x == 1").action("put hit true").tags(tags)
                    .build()));
            return engine.runWithResult(new FactMap<>(new Fact<Object>("x", 1)), RunOptions.defaults()
                    .withTags(List.of("eu"))).output();
        }
    }

    // Joined with concat, not +, whose call site the JDK links with method handles, which the deep call may have
    // broken: the line is printed whatever it left.
    private static void print(String label, Object value) {
        System.out.println(label.concat(String.valueOf(value)));
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it calls.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            try {
                result = call();
            } catch (Throwable t) {
                thrown = t;
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
