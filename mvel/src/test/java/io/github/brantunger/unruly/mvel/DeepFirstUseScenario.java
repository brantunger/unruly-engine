package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Run by {@link DeepFirstUseTest} in a new JVM. It builds an engine that finds MVEL and {@link Other} with
 * {@link java.util.ServiceLoader}, with {@code other} as its default language, so building it prepares only that one;
 * or, with the system property {@value #ONLY_FOUND} set to {@code true}, one that finds MVEL alone and names no
 * language, so MVEL is its default without being named, and building it prepares no language. On a thread with a small
 * stack, it then finds where the stack ends and loads an MVEL rule at each depth from the end up, a frame at a time,
 * until a load doesn't overflow: MVEL's first use. It prints {@link #CHECKS_FAILED} and how many loads overflowed in
 * the check of the stack's room, {@link #LAST_OVERFLOW} and where the last load that overflowed did, {@link #CHECK} if
 * in the check, {@link #DEEP} and what the first load that didn't overflow threw, or {@code nothing}, and {@link
 * #SHALLOW} and {@code ok} if MVEL then loads, runs and reports a compile error as it should at the top of the main
 * thread's stack, or what it threw instead.
 */
final class DeepFirstUseScenario {

    static final String CHECKS_FAILED = "SCENARIO checks failed: ";
    static final String LAST_OVERFLOW = "SCENARIO last overflow: ";
    static final String CHECK = "the check";
    static final String DEEP = "SCENARIO deep: ";
    static final String SHALLOW = "SCENARIO shallow: ";
    static final String ONLY_FOUND = "scenario.onlyFound";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it loads at, at most. */
    private static final int MAX_LOADS = 100_000;
    private static final String CHECK_CLASS = "io.github.brantunger.unruly.core.StackHeadroom";
    private static final String SERVICES_FILE = "META-INF/services/" + ExpressionLanguage.class.getName();

    // Linked here, at the top of the stack, as the scenario's own lambdas are: an application's lambda linked deep
    // could leave one of the JDK's classes unusable, which the engine can't help.
    private static final Supplier<Map<String, Object>> OUTPUT = HashMap::new;
    private static final List<Rule> RULES = List.of(Rule.builder().ruleName("doubles").language("mvel")
            .condition("x > 1").action("output.put('k', x * 2);").build());

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static RulesEngine<Map<String, Object>> engine;
    private static Throwable thrown;

    private DeepFirstUseScenario() {
    }

    /** The engine's default language, which a services file lists: the toy language. */
    public static final class Other implements ExpressionLanguage {
        @Override
        public String name() {
            return "other";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ToyExpressionLanguage("other").newCompiler(context);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (Boolean.getBoolean(ONLY_FOUND)) {
            engine = RulesEngineBuilder.firstMatch(OUTPUT).build();
        } else {
            engine = builtWithOther();
        }
        loadDeep();
    }

    private static RulesEngine<Map<String, Object>> builtWithOther() throws IOException {
        Path services = Files.createTempDirectory("services");
        Path file = services.resolve(SERVICES_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Other.class.getName());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{services.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            return RulesEngineBuilder.firstMatch(OUTPUT).defaultLanguage("other").build();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void loadDeep() throws InterruptedException {
        int[] checksFailed = {0};
        String[] lastOverflow = {"none"};
        Throwable[] got = new Throwable[1];
        Thread deep = new Thread(null, () -> {
            for (int above = 0; above < MAX_LOADS; above++) {
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
                    return;
                }
                StackTraceElement[] frames = overflow.getStackTrace();
                if (frames.length > 0 && frames[0].getClassName().equals(CHECK_CLASS)) {
                    checksFailed[0]++;
                    lastOverflow[0] = CHECK;
                } else {
                    lastOverflow[0] = String.valueOf(frames.length > 0 ? frames[0] : null);
                }
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        System.out.println(CHECKS_FAILED + checksFailed[0]);
        System.out.println(LAST_OVERFLOW + lastOverflow[0]);
        System.out.println(DEEP + (got[0] == null ? "nothing" : got[0]));
        System.out.println(SHALLOW + shallow());
    }

    // MVEL's load, run and compile error, at the top of the stack, on a new engine.
    private static String shallow() {
        try {
            RulesEngine<Map<String, Object>> fresh = RulesEngineBuilder.firstMatch(OUTPUT).build();
            fresh.load(RULES);
            if (!Map.of("k", 4).equals(fresh.run(new FactMap<>(new Fact<>("x", 2))))) {
                return "a wrong output";
            }
            try {
                fresh.load(List.of(Rule.builder().ruleName("broken").condition("x >").action("1").build()));
                return "no compile error";
            } catch (RuleCompilationException expected) {
                return expected.getMessage().contains("Could not initialize class") ? expected.getMessage() : "ok";
            }
        } catch (RuntimeException | Error e) {
            return e.toString();
        }
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it loads.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            try {
                engine.load(RULES);
            } catch (Throwable t) {
                thrown = t;
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
