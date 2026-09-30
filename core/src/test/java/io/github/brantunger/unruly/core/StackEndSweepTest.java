package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestLogs;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #839: {@code run()}, {@code load()}, {@code validate()} and {@code close()} called near the end of the caller's own
 * stack. Each counts, borrows and gives back engine-wide state in steps a {@link StackOverflowError} can strike, and a
 * step it struck on the way out left the state taken for good: the engine's copy permit, so every later run waited the
 * whole stall window; the rule list's count of runs using it, so its compilers were never closed; the thread's
 * deadline, so every later run on the thread failed as past it; the thread's counts of runs in progress, so later runs
 * were taken for nested ones; the engine's current run, so later runs reported a stale parent; and a rule list that a
 * reload or {@code close()} retired only in part.
 *
 * <p>
 * The sweep finds where this thread's stack ends with the very recursion it then walks back down, in the same JIT
 * state, so what it covers is measured from the end of the stack, whatever the stack's size, the OS's guard pages or
 * its page size. Walking back from the end {@value #STEP} frames at a time, it finds the deepest depth at which the
 * method returns, which a check of the stack's headroom at the method's start moves away from the end, and then calls
 * the method at each of the {@value #WINDOW} depths around it: there it runs as deep as the stack lets it. Each call
 * has a new engine, and after it the stack unwinds and the sweep checks that nothing was left behind. Each pass shifts
 * the last frame by a few {@code long} parameters, so the call lands at every offset, and the passes repeat so the
 * JIT's tiers settle. It uses only the public API and package-private methods the engine had before, so it compiles
 * against the engine it proves wrong.
 * </p>
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("#839: run(), load(), validate() and close() called at the end of the stack leave nothing behind")
class StackEndSweepTest {

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;

    /** How many frames apart the depths are at which a pass looks for the deepest one where the method returns. */
    private static final int STEP = 8;

    /** How many depths each pass calls the method at, half of them deeper than the deepest where it returned. */
    private static final int WINDOW = 300;

    /** How many passes, each with one of five last frames, so each offset is swept twice. */
    private static final int PASSES = 10;

    /** How many times each call is made at the top of the stack first, so none of its code is cold at the end. */
    private static final int WARM_UPS = 2_000;

    /** How long the test waits for the sweep before it fails rather than hang. */
    private static final long JOIN_MILLIS = TimeUnit.SECONDS.toMillis(100);

    /** A deadline that has passed, so a borrow that finds no permit free stops at once rather than waiting. */
    private static final Deadline PASSED = Deadline.from(Duration.ZERO);

    // The sweep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int reached;
    private static long sink;
    private static Runnable leaf;
    private static Throwable thrown;

    /** What the sweep calls at the end of the stack, and how the engine it calls it on is prepared. */
    enum Call {
        /** {@code run()} on an engine that has no copy yet: it takes the permit and makes one. */
        RUN_NEW_COPY,
        /** {@code run()} on an engine that has run once, so it borrows the idle copy. */
        RUN_IDLE_COPY,
        /** {@code load()} again, which retires the rules it replaces, on an engine that makes a copy at load. */
        RELOAD,
        /** {@code validate()}, which closes the compilers it made. */
        VALIDATE,
        /** {@code close()}, which retires the rules. */
        CLOSE
    }

    /**
     * A language that counts the compilers and sessions it makes and closes. Its conditions are true and its actions
     * do nothing, and each session is its own, so every run needs a copy.
     */
    private static final class Counting implements ExpressionLanguage {
        private final AtomicInteger compilers = new AtomicInteger();
        private final AtomicInteger compilersClosed = new AtomicInteger();
        private final AtomicInteger sessions = new AtomicInteger();
        private final AtomicInteger sessionsClosed = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            compilers.incrementAndGet();
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
                    sessions.incrementAndGet();
                    return new Session() {
                        @Override
                        public void close() {
                            sessionsClosed.incrementAndGet();
                        }
                    };
                }

                @Override
                public void close() {
                    compilersClosed.incrementAndGet();
                }
            };
        }

        /** What is still open, or {@code null} if everything made was closed. */
        String leftOpen() {
            if (compilersClosed.get() != compilers.get() || sessionsClosed.get() != sessions.get()) {
                return "compilers closed " + compilersClosed + " of " + compilers + ", sessions closed "
                        + sessionsClosed + " of " + sessions;
            }
            return null;
        }
    }

    /** One engine the sweep calls once, with its language and the parent its last run reported. */
    private record Target(AbstractRulesEngine<Object> engine, Counting language, AtomicReference<RunContext> parent) {
    }

    private static final List<Rule> RULES = List.of(
            Rule.builder().ruleName("r").priority(1).condition("c").action("a").build());

    @SuppressWarnings("unchecked")
    private static Target target(Call call) {
        Counting language = new Counting();
        AtomicReference<RunContext> parent = new AtomicReference<>();
        RulesEngine<Object> engine = RulesEngineBuilder.firstMatch(Object::new).language(language).maxCopies(1)
                .copiesAtLoad(call == Call.RELOAD ? 1 : 0).runTimeout(Duration.ofHours(1))
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        parent.set(run.parent());
                    }
                }).build();
        engine.load(RULES);
        if (call == Call.RUN_IDLE_COPY) {
            engine.run(new FactMap<>());
        }
        return new Target((AbstractRulesEngine<Object>) engine, language, parent);
    }

    private static Consumer<AbstractRulesEngine<Object>> action(Call call) {
        return switch (call) {
            case RUN_NEW_COPY, RUN_IDLE_COPY -> engine -> engine.run(new FactMap<>());
            case RELOAD -> engine -> engine.load(RULES);
            case VALIDATE -> engine -> engine.validate(RULES);
            case CLOSE -> AbstractRulesEngine::close;
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    @DisplayName("#839: called at each of the last depths of the stack, it leaves no permit, count, deadline, current"
            + " run or open compiler behind")
    void leavesNothingBehind(Call call) throws Exception {
        // The only permit of an engine a thread of its own holds, so a borrow on the sweep thread gets an extra copy
        // only if the thread still counts a run in progress, which makes the borrow nested.
        Target held = target(Call.RUN_NEW_COPY);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        ExecutorService checker = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int[] outcomes = new int[3];
        try {
            RuleSet.Copy holding = holder.submit(() -> held.engine().currentRules().borrow(Deadline.NONE)).get();
            Thread sweep = new Thread(null, () -> {
                try {
                    TestLogs.logsOf(() -> sweep(call, held.engine(), checker, outcomes));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "sweep", STACK_BYTES);
            sweep.setDaemon(true);
            sweep.start();
            sweep.join(JOIN_MILLIS);
            assertFalse(sweep.isAlive(), "the sweep finished");
            holder.submit(() -> held.engine().currentRules().release(holding)).get();
        } finally {
            holder.shutdown();
            checker.shutdown();
            held.engine().close();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get().getMessage(), failure.get());
        }
        assertTrue(outcomes[0] > 0, "some calls returned, so the window reached above where the method overflows");
        assertTrue(outcomes[1] > 0, "some calls overflowed, so the window reached where the method overflows");
    }

    private static void sweep(Call call, AbstractRulesEngine<Object> held, ExecutorService checker, int[] outcomes) {
        Consumer<AbstractRulesEngine<Object>> action = action(call);
        for (int i = 0; i < WARM_UPS; i++) {
            once(call, action, 0, i % 5, held, checker, null);
        }
        for (int pass = 0; pass < PASSES; pass++) {
            int pad = pass % 5;
            leaf = () -> {
            };
            try {
                down(0, Integer.MAX_VALUE, pad);
            } catch (StackOverflowError e) {
                // The stack's end, for this last frame, in this JIT state.
            }
            int end = reached;
            int edge = 0;
            for (int depth = end; depth > 0 && edge == 0; depth -= STEP) {
                if (once(call, action, depth, pad, held, checker, null)) {
                    edge = depth;
                }
            }
            for (int depth = Math.max(0, edge - WINDOW / 2); depth <= Math.min(end, edge + WINDOW / 2); depth++) {
                once(call, action, depth, pad, held, checker, outcomes);
            }
        }
    }

    // Any Throwable a check throws fails the sweep: the stack has unwound, so it's no overflow of the call's own.
    // Returns whether the call returned.
    private static boolean once(Call call, Consumer<AbstractRulesEngine<Object>> action, int depth, int pad,
                             AbstractRulesEngine<Object> held, ExecutorService checker, int[] outcomes) {
        Target target = target(call);
        leaf = () -> action.accept(target.engine());
        thrown = null;
        reached = 0;
        boolean called = true;
        try {
            down(0, depth, pad);
        } catch (StackOverflowError e) {
            // The descent itself overflowed before its last frame called anything.
            called = false;
        }
        if (outcomes != null && called) {
            outcomes[thrown == null ? 0 : thrown instanceof StackOverflowError ? 1 : 2]++;
        }
        List<String> left = leftBehind(target, held, checker);
        if (!left.isEmpty()) {
            throw new AssertionError(call + " called " + depth + " frames deep left behind: " + String.join(", ", left)
                    + (thrown == null ? "; it returned" : "; it threw " + thrown + " at " + where(thrown)), thrown);
        }
        return called && thrown == null;
    }

    private static List<String> leftBehind(Target target, AbstractRulesEngine<Object> held, ExecutorService checker) {
        List<String> left = new ArrayList<>();
        if (Cancellation.deadlineFrom(null) != Deadline.NONE) {
            left.add("the thread's deadline");
        }
        if (loggedFailuresRecordLeft()) {
            left.add("the thread's LoggedFailures record of runs in progress");
        }
        if (runCountedOnThread(held)) {
            left.add("the thread's count of runs in progress");
        }
        AbstractRulesEngine<Object> engine = target.engine();
        if (isOpen(engine)) {
            int permits = freePermits(engine, checker);
            if (permits != 1) {
                left.add(permits == 0 ? "the engine's copy permit" : "a second copy permit, given back twice");
            }
            if (engine.currentRules().waiters() != 0) {
                left.add("a run counted as waiting for a copy");
            }
            engine.run(new FactMap<>());
            if (target.parent().get() != null) {
                left.add("the engine's current run, which the next run took for its parent");
            }
        }
        engine.close();
        String open = target.language().leftOpen();
        if (open != null) {
            left.add("after close(), " + open);
        }
        return left;
    }

    // With no run in progress the thread has no record, and recording an exception the engine built then finds none
    // to record it in.
    private static boolean loggedFailuresRecordLeft() {
        try {
            LoggedFailures.builtByEngine(new IllegalStateException("probe"));
            return true;
        } catch (NullPointerException e) {
            return false;
        }
    }

    private static boolean runCountedOnThread(AbstractRulesEngine<Object> held) {
        RuleSet rules = held.currentRules();
        try {
            rules.release(rules.borrow(PASSED));
            return true;
        } catch (TimeoutException e) {
            Failures.throwIfPresent(rules.leaveAfterStop());
            return false;
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isOpen(AbstractRulesEngine<Object> engine) {
        try {
            engine.rules();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    // On a thread of its own, which counts no run in progress, so a borrow that finds no permit free stops.
    // A second borrow on the same thread is nested, and takes a permit only if one is free, so the engine's one permit
    // lent twice was given back twice.
    private static int freePermits(AbstractRulesEngine<Object> engine, ExecutorService checker) {
        try {
            return checker.submit(() -> {
                RuleSet rules = engine.currentRules();
                try {
                    RuleSet.Copy first = rules.borrow(PASSED);
                    RuleSet.Copy second = rules.borrow(PASSED);
                    int permits = (first.held() == RuleSet.Held.PERMIT ? 1 : 0)
                            + (second.held() == RuleSet.Held.PERMIT ? 1 : 0);
                    Failures.throwIfPresent(rules.release(second));
                    Failures.throwIfPresent(rules.release(first));
                    return permits;
                } catch (TimeoutException e) {
                    Failures.throwIfPresent(rules.leaveAfterStop());
                    return 0;
                }
            }).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The engine's frames on top of an overflow, innermost first, up to the method the sweep called. */
    private static String where(Throwable overflow) {
        StringBuilder frames = new StringBuilder();
        for (StackTraceElement frame : overflow.getStackTrace()) {
            if (frame.getClassName().equals(StackEndSweepTest.class.getName())) {
                break;
            }
            String type = frame.getClassName();
            frames.append(frames.length() == 0 ? "" : " < ").append(type.substring(type.lastIndexOf('.') + 1))
                    .append('.').append(frame.getMethodName());
        }
        return frames.toString();
    }

    private static void down(int depth, int target, int pad) {
        reached = depth;
        if (depth < target) {
            down(depth + 1, target, pad);
            return;
        }
        switch (pad) {
            case 1 -> pad1(1L);
            case 2 -> pad2(1L, 2L);
            case 3 -> pad3(1L, 2L, 3L);
            case 4 -> pad4(1L, 2L, 3L, 4L);
            default -> call();
        }
    }

    private static void pad1(long a) {
        call();
        sink += a;
    }

    private static void pad2(long a, long b) {
        call();
        sink += a + b;
    }

    private static void pad3(long a, long b, long c) {
        call();
        sink += a + b + c;
    }

    private static void pad4(long a, long b, long c, long d) {
        call();
        sink += a + b + c + d;
    }

    // The catch stores and nothing more: a call there could overflow in turn.
    private static void call() {
        try {
            leaf.run();
        } catch (StackOverflowError | RuntimeException e) {
            thrown = e;
        }
    }
}
