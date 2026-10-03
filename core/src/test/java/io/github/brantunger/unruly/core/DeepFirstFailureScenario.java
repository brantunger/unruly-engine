package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;

import java.util.List;
import java.util.function.Supplier;

/**
 * Run by {@link DeepFirstFailureTest} in a new JVM, so the failure it makes deep in a stack is the JVM's first. It
 * builds an engine and runs a rule that passes {@value #WARM_UPS} times at the top of the main thread's stack, then
 * loads a rule whose action throws. On a thread with a small stack, it finds where the stack ends, then runs that rule
 * at each depth from the end up, a frame at a time, until a run doesn't overflow, and prints {@link #OVERFLOWED} and
 * how many runs overflowed, {@link #DEEP} and what the first run that didn't overflow threw, then {@link #SHALLOW} and
 * what the same run throws at the top of the main thread's stack. The test keeps the recursion that finds the end from
 * being compiled, so its frames are the same size each time.
 */
final class DeepFirstFailureScenario {

    static final String OVERFLOWED = "SCENARIO overflowed: ";
    static final String DEEP = "SCENARIO deep: ";
    static final String SHALLOW = "SCENARIO shallow: ";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it runs at, at most. */
    private static final int MAX_RUNS = 100_000;
    /**
     * How many times it runs the passing rule first, so the engine's check of its stack's room is compiled, as it is in
     * a JVM that has run engines for a while: compiled, its frames are a third of the size, so the room it makes is
     * less than linking a failure's message of several values took before #965. The test runs it with
     * {@code -Xbatch}, so the compiling is done before the warm-up goes on, however busy the machine is.
     */
    private static final int WARM_UPS = 20_000;

    // Made here, at the top of the stack, as the scenario's own lambdas are linked, so what the action throws builds no
    // message deep in the stack, and nothing of the scenario's own is linked there.
    private static final IllegalStateException FAILURE = new IllegalStateException("thrown by the scenario");
    private static final CompiledAction FAILING = (context, session) -> {
        throw FAILURE;
    };
    private static final CompiledAction PASSING = (context, session) -> ActionResult.done();
    private static final Supplier<Object> OUTPUT = Object::new;
    private static final FactMap<Object> FACTS = new FactMap<>();

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static RulesEngine<Object> engine;
    private static Throwable thrown;

    private DeepFirstFailureScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        engine = RulesEngineBuilder.firstMatch(OUTPUT).language(new StubExpressionLanguage()
                .compileAction(expression -> "fail".equals(expression.text()) ? FAILING : PASSING)).build();
        // Everything a run does but fail, done at the top of the stack, so the deep runs reach the failure.
        engine.load(List.of(Rule.builder().ruleName("passes").condition("x").action("pass").build()));
        for (int i = 0; i < WARM_UPS; i++) {
            engine.run(FACTS);
        }
        engine.load(List.of(Rule.builder().ruleName("fails").condition("x").action("fail").build()));
        int[] overflowed = new int[1];
        Throwable[] got = new Throwable[1];
        Thread deep = new Thread(null, () -> {
            for (int above = 0; above < MAX_RUNS; above++) {
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
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        System.out.println(OVERFLOWED + overflowed[0]);
        System.out.println(DEEP + got[0]);
        try {
            engine.run(FACTS);
            System.out.println(SHALLOW + "nothing");
        } catch (RuntimeException | Error e) {
            System.out.println(SHALLOW + e);
        }
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it runs the rule.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            try {
                engine.run(FACTS);
            } catch (Throwable t) {
                thrown = t;
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
