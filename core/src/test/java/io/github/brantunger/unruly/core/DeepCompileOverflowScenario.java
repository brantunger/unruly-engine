package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Run by {@link DeepCompileOverflowTest} in a new JVM, so nothing a load deep in its stack does can leave a class
 * unusable for other tests. It builds an engine whose language throws {@link StackOverflowError} when it compiles an
 * action, as MVEL's parser does when it runs out of stack, loads a rule at the top of the main thread's stack and
 * prints {@link #SHALLOW} and the message of what the load threw. That load also leaves nothing for a later load to
 * initialize. On a thread with a small stack, it then finds where the stack ends and loads the rule at each depth from
 * the end up, a frame at a time, until a load gets past the check of its stack's room to the compile, and prints
 * {@link #CHECKS_FAILED} and how many loads overflowed before that, and {@link #DEEP} and the message of what the first
 * load that didn't threw, or what else it threw. The test keeps the recursion that finds the end from being compiled,
 * so its frames are the same size each time.
 */
final class DeepCompileOverflowScenario {

    static final String CHECKS_FAILED = "SCENARIO overflowed before the compile: ";
    static final String DEEP = "SCENARIO deep: ";
    static final String SHALLOW = "SCENARIO shallow: ";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it loads at, at most. */
    private static final int MAX_LOADS = 100_000;

    // Linked here, at the top of the stack, as the scenario's own lambdas are: an application's lambda linked deep
    // could leave one of the JDK's classes unusable, which the engine can't help.
    private static final Supplier<Map<String, Object>> OUTPUT = HashMap::new;
    private static final List<Rule> RULES = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static RulesEngine<Map<String, Object>> engine;
    private static Throwable thrown;

    private DeepCompileOverflowScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        engine = RulesEngineBuilder.firstMatch(OUTPUT).language(new StubExpressionLanguage().compileAction(
                expression -> {
                    throw new StackOverflowError();
                })).build();
        System.out.println(SHALLOW + messageOf(load()));
        int[] checksFailed = {0};
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
                if (!(thrown instanceof StackOverflowError)) {
                    got[0] = thrown;
                    return;
                }
                checksFailed[0]++;
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        System.out.println(CHECKS_FAILED + checksFailed[0]);
        System.out.println(DEEP + messageOf(got[0]));
    }

    private static Throwable load() {
        try {
            engine.load(RULES);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // A RuleCompilationException caused by the language's StackOverflowError is told by its message alone.
    private static String messageOf(Throwable failure) {
        if (failure instanceof RuleCompilationException && failure.getCause() instanceof StackOverflowError) {
            return failure.getMessage();
        }
        return String.valueOf(failure);
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it loads.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            thrown = load();
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
