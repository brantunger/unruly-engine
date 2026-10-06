package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run on a virtual thread that took a build slot and then failed to look for an idle copy gives the slot back. The
 * slots are fixed for an engine's life, so one that isn't given back is gone for good: every later run that needs a
 * new copy then waits as long as its deadline lets it, and makes its copy without a slot.
 *
 * <p>
 * Kept apart from {@code RuleSetTest}, and borrowing through {@link #borrow(RuleSet)}, which gives the deadline as
 * whatever type {@code RuleSet.borrow} takes, so the same test compiles against releases whose deadlines differ.
 * </p>
 */
// A deadline for the test, as RuleSetTest has, on JUnit's own thread for the same reason.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SAME_THREAD)
@DisplayName("a run whose look for an idle copy fails once it has taken a build slot gives the slot back")
class SlotGivenBackTest {

    /** A compiled expression that is never run: RuleSet only lends sessions for it. */
    private record Stub() implements CompiledCondition, CompiledAction {
        @Override
        public Object evaluate(EvaluationContext context, Session session) {
            throw new AssertionError("not run");
        }

        @Override
        public ActionResult execute(ActionContext context, Session session) {
            throw new AssertionError("not run");
        }
    }

    /** A session numbered in the order sessions were created. */
    private record NumberedSession(int number) implements Session {
    }

    private static final CompiledRule RULE = new CompiledRule(
            Rule.builder().ruleName("r").condition("true").action("1").build(), "r", "a", new Stub(), new Stub());

    /** A compiler that only creates sessions. */
    private static ExpressionCompiler compiler(AtomicInteger counter) {
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression expression) {
                throw new AssertionError("not compiled");
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                throw new AssertionError("not compiled");
            }

            @Override
            public Session newSession() {
                return new NumberedSession(counter.incrementAndGet());
            }
        };
    }

    /** An idle queue whose next look for an idle copy throws {@code pollFailure}, once it's set. */
    private static final class FailingQueue extends AbstractQueue<Map<String, Session>> {
        final AtomicReference<Error> pollFailure = new AtomicReference<>();
        private final Queue<Map<String, Session>> copies = new ConcurrentLinkedQueue<>();

        @Override
        public boolean offer(Map<String, Session> sessions) {
            return copies.offer(sessions);
        }

        @Override
        public Map<String, Session> poll() {
            Error failure = pollFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return copies.poll();
        }

        @Override
        public Map<String, Session> peek() {
            return copies.peek();
        }

        @Override
        public Iterator<Map<String, Session>> iterator() {
            return copies.iterator();
        }

        @Override
        public int size() {
            return copies.size();
        }
    }

    /**
     * Borrows a copy with a deadline ten seconds away, so a borrow that waits fails the test rather than holding it.
     *
     * @param rules The rule set to borrow from
     * @return The copy
     */
    private static RuleSet.Copy borrow(RuleSet rules) throws Throwable {
        // Found by reflection so this compiles whatever type the deadline is, which the proof on main needs.
        List<Method> borrows = Arrays.stream(RuleSet.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("borrow") && method.getParameterCount() == 1).toList();
        assertEquals(1, borrows.size(), "RuleSet should have exactly one borrow method with one parameter: " + borrows);
        Method borrow = borrows.get(0);
        Class<?> type = borrow.getParameterTypes()[0];
        Object deadline;
        if (type == Instant.class) {
            deadline = Instant.now().plusSeconds(10);
        } else {
            Method from = type.getDeclaredMethod("from", Duration.class);
            from.setAccessible(true);
            deadline = from.invoke(null, Duration.ofSeconds(10));
        }
        borrow.setAccessible(true);
        try {
            return (RuleSet.Copy) borrow.invoke(rules, deadline);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** A run on a virtual thread that borrows one copy and holds it until it's asked back. */
    private record VirtualRun(Thread thread, CountDownLatch borrowed, CountDownLatch askedBack,
                              AtomicReference<RuleSet.Copy> held, AtomicReference<Throwable> failure) {

        /** Waits for the borrow to end and returns the copy it took, failing the test if it took none. */
        RuleSet.Copy copy() throws InterruptedException {
            assertTrue(borrowed.await(30, TimeUnit.SECONDS), "the run never finished borrowing");
            if (failure.get() != null) {
                throw new AssertionError("the run borrowing a copy failed", failure.get());
            }
            return held.get();
        }

        /** Asks for the copy back and waits for the run to end. */
        void giveBack() throws InterruptedException {
            askedBack.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(thread.isAlive(), "the run holding the copy never ended");
        }
    }

    /** Starts a run on a virtual thread that borrows one copy and holds it, returning before the borrow ends. */
    private static VirtualRun startVirtualRun(RuleSet rules) {
        CountDownLatch borrowed = new CountDownLatch(1);
        CountDownLatch askedBack = new CountDownLatch(1);
        AtomicReference<RuleSet.Copy> held = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            RuleSet.Copy copy;
            try {
                copy = borrow(rules);
                held.set(copy);
            } catch (Throwable e) {
                failure.set(e);
                return;
            } finally {
                borrowed.countDown();
            }
            try {
                assertTrue(askedBack.await(30, TimeUnit.SECONDS), "the copy was never asked for back");
            } catch (Exception | Error e) {
                failure.set(e);
            } finally {
                rules.release(copy);
            }
        });
        return new VirtualRun(thread, borrowed, askedBack, held, failure);
    }

    /** Waits until {@code count} runs are waiting for a build slot of {@code rules}. */
    private static void awaitWaiters(RuleSet rules, int count) throws InterruptedException {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (rules.waiters() != count) {
            assertTrue(System.nanoTime() < giveUp, rules.waiters() + " of the " + count + " runs started waiting");
            Thread.sleep(2);
        }
    }

    @Test
    @DisplayName("a run whose look for an idle copy fails once it has taken a build slot gives the slot back")
    void aRunThatFailsToLookAfterItsSlotGivesTheSlotBack() throws InterruptedException {
        StackOverflowError failure = new StackOverflowError("looking for an idle copy");
        FailingQueue idle = new FailingQueue();
        // One build slot for the whole engine, so a slot that isn't given back is gone for good: every later run that
        // needs a new copy would then wait as long as its deadline lets it and make its copy without one. The window
        // is far longer than the test, so the run that waits never gives up and makes a copy without a slot.
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED, 1);
        RuleSet rules = TestRuleSets.ruleSet(List.of(RULE), Map.of("a", compiler(new AtomicInteger())))
                .withPermits(permits).withStallWindow(TimeUnit.MINUTES.toMillis(5)).withIdle(idle).build();
        VirtualRun holder = startVirtualRun(rules);
        assertEquals(RuleSet.Held.SLOT, holder.copy().held(), "the first run took the only build slot");
        VirtualRun failing = startVirtualRun(rules);
        awaitWaiters(rules, 1);
        // The run has looked once, found nothing and is waiting for the slot, so the look that fails is the one it
        // takes once it has the slot: giving the held copy back adds it without looking.
        idle.pollFailure.set(failure);

        holder.giveBack();
        assertTrue(failing.borrowed().await(30, TimeUnit.SECONDS), "the run never finished borrowing");
        failing.thread().join(TimeUnit.SECONDS.toMillis(30));

        assertSame(failure, failing.failure().get(), "the run failed with what looking threw");
        assertEquals(0, rules.waiters(), "the run is no longer counted as waiting");
        // The copy given back is taken first, so the run after it needs a new copy, and a build slot to make it.
        VirtualRun reusing = startVirtualRun(rules);
        assertEquals(RuleSet.Held.NOTHING, reusing.copy().held(), "the run took the copy given back");
        VirtualRun making = startVirtualRun(rules);
        assertEquals(RuleSet.Held.SLOT, making.copy().held(),
                "the slot taken before looking failed was never given back");
        reusing.giveBack();
        making.giveBack();
    }
}
