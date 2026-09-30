package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestLogs;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ForwardingExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

// A deadline for every test, because a rule set that never gives up waiting, or that closes while a run still holds
// a copy, would otherwise hold a test here for ever, and one test that waits for ever holds the whole suite. JUnit
// reports this deadline once the test returns, which catches a test that is merely slow, so every borrow that could
// wait is given a deadline of its own, and the one test of waiting with no deadline runs on a thread of its own.
//
// SAME_THREAD, spelled out rather than left to junit-platform.properties, which makes SEPARATE_THREAD the default
// for every other timed class: RuleSet counts the runs in progress in a thread-local, and the @AfterEach below
// checks that each test gave its copies back on the thread that took them. A body JUnit ran on a timeout thread of
// its own would leave that check reading another thread's count, and it would pass whatever a test leaked.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SAME_THREAD)
@DisplayName("RuleSet lends each run its own sessions for the shared compiled rules")
class RuleSetTest {

    /** A compiled expression that is never run: RuleSet only lends sessions for it. */
    private record Stub(String name) implements CompiledCondition, CompiledAction {
        @Override
        public Object evaluate(EvaluationContext context, Session session) {
            throw new AssertionError("not run");
        }

        @Override
        public ActionResult execute(ActionContext context, Session session) {
            throw new AssertionError("not run");
        }
    }

    /** A session named after its language and numbered in the order sessions were created. */
    private record NumberedSession(String language, int number) implements Session {
    }

    /** A session that records when it's closed, by adding its number to the list every session of the copy shares. */
    private record RecordingSession(int number, List<Integer> closed) implements Session {
        @Override
        public void close() {
            closed.add(number);
        }
    }

    private static final CompiledRule RULE = new CompiledRule(
            Rule.builder().ruleName("r").condition("true").action("1").build(), "r", "a", new Stub("condition"),
            new Stub("action"));

    /** A compiler that only creates sessions, numbering them with {@code counter} and recording the language. */
    private static ExpressionCompiler compiler(String language, AtomicInteger counter, List<String> created) {
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
                created.add(language);
                return new NumberedSession(language, counter.incrementAndGet());
            }
        };
    }

    /** A compiler whose sessions record when they're closed, numbered as {@link #compiler}'s are. */
    private static ExpressionCompiler recordingCompiler(AtomicInteger counter, List<Integer> closed) {
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
                return new RecordingSession(counter.incrementAndGet(), closed);
            }
        };
    }

    /**
     * A compiler whose sessions record when they're closed, as {@link #recordingCompiler}'s do, and which records its
     * own close as {@code 0}, after them.
     */
    private static ExpressionCompiler closingCompiler(AtomicInteger counter, List<Integer> closed) {
        ExpressionCompiler sessions = recordingCompiler(counter, closed);
        return new ForwardingExpressionCompiler(sessions) {
            // Records its own close instead of forwarding it: the compiler it wraps has nothing to close.
            @Override
            public void close() {
                closed.add(0);
            }
        };
    }

    /** A compiler of rules that keep no state between runs: every session it creates is {@link Session#none()}. */
    private static ExpressionCompiler statelessCompiler() {
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
                return Session.none();
            }
        };
    }

    /**
     * Reads this thread's count of the runs in progress on it, which only RuleSet keeps.
     *
     * @return The count, or {@code null} if the thread keeps none
     */
    private static int[] runsCountedOnThisThread() throws ReflectiveOperationException {
        Field runs = RuleSet.class.getDeclaredField("RUNS_ON_THREAD");
        runs.setAccessible(true);
        return (int[]) ((ThreadLocal<?>) runs.get(null)).get();
    }

    /**
     * Asserts that each of the {@code made} copies, numbered from 1, was closed once, in any order, and the compiler
     * after them.
     */
    private static void assertEachCopyClosedOnceThenTheCompiler(int made, List<Integer> closed) {
        List<Integer> expected = new ArrayList<>(IntStream.rangeClosed(1, made).boxed().toList());
        expected.add(0);
        List<Integer> sorted = new ArrayList<>(closed.subList(0, Math.max(0, closed.size() - 1)).stream().sorted()
                .toList());
        sorted.addAll(closed.subList(Math.max(0, closed.size() - 1), closed.size()));
        assertEquals(expected, sorted, "each copy closed once, then the compiler: " + closed);
    }

    /**
     * An idle queue that runs {@code hook} each time a copy is added to it, before adding it, so a test can act while
     * the run giving the copy back still holds its permit. A retired rule set adds a copy only when it keeps it, so the
     * hook running tells the test that it did. The hook must not throw: the rule set would close the copy and log it.
     * With {@code pollFailure} set, the next look for an idle copy throws it, once. With {@code beforePoll} set, the
     * next look for an idle copy runs it first, once, so a test can act while a thread is about to take an idle copy.
     */
    private static final class HookedQueue extends AbstractQueue<Map<String, Session>> {
        final AtomicReference<Error> pollFailure = new AtomicReference<>();
        final AtomicReference<Runnable> beforePoll = new AtomicReference<>();
        private final Queue<Map<String, Session>> copies = new ConcurrentLinkedQueue<>();
        private final Runnable hook;

        HookedQueue(Runnable hook) {
            this.hook = hook;
        }

        @Override
        public boolean offer(Map<String, Session> sessions) {
            hook.run();
            return copies.offer(sessions);
        }

        @Override
        public Map<String, Session> poll() {
            Runnable before = beforePoll.getAndSet(null);
            if (before != null) {
                before.run();
            }
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
     * A deadline for a borrow that shouldn't have to wait at all, so that one which does fails the test instead of
     * holding it: JUnit reports the test's own deadline only once the test has returned.
     */
    private static Deadline deadline() {
        return Deadline.from(Duration.ofSeconds(10));
    }

    /** A thread holding one copy of a rule set until the test asks for it back. */
    private record Holder(Thread thread, CountDownLatch askedBack, AtomicReference<Throwable> failure) {

        /** Asks for the copy back, waits for the thread to end, and fails the test if the thread did. */
        void giveBack() throws InterruptedException {
            askedBack.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(thread.isAlive(), "the thread holding the copy never ended");
            if (failure.get() != null) {
                throw new AssertionError("the thread holding the copy failed", failure.get());
            }
        }
    }

    /**
     * Borrows one copy on a platform thread of its own and holds it until it's asked back, so that a borrow on the
     * test's own thread has to wait for a copy or make an extra one.
     *
     * @param rules The rule set to borrow from
     * @return The holder, once it has the copy
     */
    private static Holder holdOneCopy(RuleSet rules) throws InterruptedException {
        return holdOneCopy(rules, false);
    }

    /**
     * Borrows one copy on a thread of its own and holds it until it's asked back.
     *
     * @param rules   The rule set to borrow from
     * @param virtual Whether the holder is a virtual thread, which a limit for virtual threads applies to
     * @return The holder, once it has the copy
     */
    private static Holder holdOneCopy(RuleSet rules, boolean virtual) throws InterruptedException {
        CountDownLatch lent = new CountDownLatch(1);
        CountDownLatch askedBack = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Whatever goes wrong on the thread is kept for the test to report, because a thread of its own is otherwise
        // a thread nobody hears from; and the copy is given back whatever happens after it was taken.
        Runnable holding = () -> {
            RuleSet.Copy held;
            try {
                held = rules.borrow(deadline());
                if (held == null) {
                    failure.set(new AssertionError("the rule set to hold a copy of is closed"));
                    return;
                }
            } catch (Exception | Error e) {
                failure.set(e);
                return;
            } finally {
                lent.countDown();
            }
            try {
                assertTrue(askedBack.await(30, TimeUnit.SECONDS), "the copy was never asked for back");
            } catch (Exception | Error e) {
                failure.set(e);
            } finally {
                rules.release(held);
            }
        };
        // A daemon, so that a thread which never ended couldn't keep the JVM from exiting.
        Thread holder = virtual ? Thread.ofVirtual().unstarted(holding)
                : Thread.ofPlatform().daemon().unstarted(holding);
        holder.start();
        assertTrue(lent.await(30, TimeUnit.SECONDS), "the only copy was never lent");
        if (failure.get() != null) {
            throw new AssertionError("the thread meant to hold a copy never got one", failure.get());
        }
        return new Holder(holder, askedBack, failure);
    }

    /**
     * A run on a virtual thread that borrows one copy and holds it until it's asked back. Unlike {@link Holder}, it
     * is returned before its borrow ends, so a test can watch a borrow that has to wait, and it reports the copy it
     * got, so a test can tell one copy from another.
     */
    private record VirtualRun(Thread thread, CountDownLatch borrowed, CountDownLatch askedBack,
                              AtomicReference<RuleSet.Copy> held, AtomicReference<RuleSet.Held> heldWith,
                              AtomicReference<Throwable> failure) {

        /**
         * Waits for the borrow to end and returns what the run held with its copy when it got it, which it may have
         * given back since.
         */
        RuleSet.Held heldWhenBorrowed() throws InterruptedException {
            copy();
            return heldWith.get();
        }

        /** Waits for the borrow to end and returns the copy it took, failing the test if it took none. */
        RuleSet.Copy copy() throws InterruptedException {
            assertTrue(borrowed.await(30, TimeUnit.SECONDS), "the run never finished borrowing");
            if (failure.get() != null) {
                throw new AssertionError("the run borrowing a copy failed", failure.get());
            }
            return held.get();
        }

        /** Asks for the copy back, waits for the run to end, and fails the test if the run did. */
        void giveBack() throws InterruptedException {
            askedBack.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(thread.isAlive(), "the run holding the copy never ended");
            if (failure.get() != null) {
                throw new AssertionError("the run holding the copy failed", failure.get());
            }
        }
    }

    /**
     * Starts a run on a virtual thread that borrows one copy and holds it until it's asked back, and returns at once,
     * before the borrow has ended.
     *
     * @param rules The rule set to borrow from
     * @return The run
     */
    private static VirtualRun startVirtualRun(RuleSet rules) {
        CountDownLatch borrowed = new CountDownLatch(1);
        CountDownLatch askedBack = new CountDownLatch(1);
        AtomicReference<RuleSet.Copy> held = new AtomicReference<>();
        AtomicReference<RuleSet.Held> heldWith = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Whatever goes wrong on the thread is kept for the test to report, as in holdOneCopy, and the copy is given
        // back whatever happens after it was taken. A virtual thread is always a daemon.
        Thread thread = Thread.ofVirtual().start(() -> {
            RuleSet.Copy copy;
            try {
                copy = rules.borrow(deadline());
                if (copy == null) {
                    failure.set(new AssertionError("the rule set to borrow from is closed"));
                    return;
                }
                heldWith.set(copy.held());
                held.set(copy);
            } catch (Exception | Error e) {
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
        return new VirtualRun(thread, borrowed, askedBack, held, heldWith, failure);
    }

    /** Waits until {@code count} runs are waiting for a permit or a build slot of {@code rules}. */
    private static void awaitWaiters(RuleSet rules, int count) throws InterruptedException {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (rules.waiters() != count) {
            assertTrue(System.nanoTime() < giveUp, rules.waiters() + " of the " + count + " runs started waiting");
            Thread.sleep(2);
        }
    }

    /** Waits until {@code thread} is parked with a timeout, which is how a run waiting for a build slot waits. */
    private static void awaitParked(Thread thread) throws InterruptedException {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < giveUp, "the run never started waiting for a build slot");
            Thread.sleep(2);
        }
    }

    /**
     * Fails the test that has just run if it left a run counted on JUnit's thread, which every test that borrows a
     * copy has to give back. A thread that is already running rules borrows as a nested run: it takes an extra copy
     * rather than waiting for one, and warns about none of it, so a later test on the same thread that expects a run
     * to wait, or to warn, would pass or fail depending on the order the tests ran in.
     *
     * <p>
     * The check is the behaviour itself: a rule set of its own lends its one copy to another thread, and a borrow
     * here then has to wait for it and give up, warning about the extra copy it makes. A run left counted on this
     * thread makes that borrow a nested one, which takes an extra copy at once and says nothing. The check finds a
     * leak but can't undo it, so one test that leaks fails every test after it on the thread as well: the first
     * failure is the one to look at.
     * </p>
     */
    @AfterEach
    void noRunLeftCountedOnThisThread() throws InterruptedException {
        // Named apart, and cleared, so that a test which left its thread interrupted is told that, rather than
        // that the copy to hold was never lent, and the tests after it aren't interrupted as well.
        assertFalse(Thread.interrupted(), "the test left this thread interrupted");
        RuleSet probe = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(1), 1);
        Holder holder = holdOneCopy(probe);

        // With a deadline, so that this check ends even against a rule set that never gives up waiting: that is the
        // business of the tests, not of a check that runs after every one of them.
        String logs;
        try {
            logs = TestLogs.logsOf(() -> {
                try {
                    probe.release(probe.borrow(deadline()));
                } catch (InterruptedException e) {
                    throw new AssertionError("the borrow in this check was interrupted", e);
                } catch (TimeoutException e) {
                    throw new AssertionError("the borrow in this check waited to its deadline instead of giving up",
                            e);
                }
            });
        } finally {
            holder.giveBack();
        }
        assertTrue(logs.contains("made an extra copy"), "the test, or one before it on this thread, left a run"
                + " counted here, so this borrow was treated as a nested run: " + logs);
    }

    @Test
    @DisplayName("a copy in use is never lent twice, and one given back is reused instead of creating sessions again")
    void copiesLentOneAtATime() throws InterruptedException, TimeoutException {
        AtomicInteger sessions = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                CopyLimit.none(), new CopyPermits(RuleSet.UNLIMITED));

        RuleSet.Copy first = rules.borrow(Deadline.NONE);
        try {
            RuleSet.Copy second = rules.borrow(Deadline.NONE);
            try {
                assertEquals(Map.of("a", new NumberedSession("a", 1)), first.sessions());
                assertEquals(Map.of("a", new NumberedSession("a", 2)), second.sessions(),
                        "an overlapping run gets new sessions");
                assertTrue(first.kept() && second.kept(), "without a limit every copy is kept");
                assertEquals(List.of(RULE), rules.rules(), "every copy shares the compiled rules");
                assertEquals(RuleSet.UNLIMITED, rules.limit());
            } finally {
                rules.release(second);
            }

            RuleSet.Copy reused = rules.borrow(Deadline.NONE);
            rules.release(reused);
            assertSame(second.sessions(), reused.sessions());
            assertEquals(2, sessions.get(), "sessions created");
        } finally {
            rules.release(first);
        }
    }

    @Test
    @DisplayName("a limit for runs on virtual threads lets a run on a platform thread take a copy without waiting")
    void aPlatformThreadRunIgnoresAVirtualThreadLimit() throws InterruptedException, TimeoutException {
        AtomicInteger sessions = new AtomicInteger();
        // The default kind of limit: one copy, for runs on virtual threads only, and a run on one holds it. The window
        // is far longer than the borrow's deadline, so a run on a platform thread that waited for it at all would
        // fail the test.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                new CopyLimit(1, true), TimeUnit.MINUTES.toMillis(5));
        Holder holder = holdOneCopy(rules, true);
        try {
            RuleSet.Copy copy = rules.borrow(deadline());
            try {
                assertEquals(RuleSet.Kind.KEPT, copy.kind(), "a run the limit doesn't apply to gets a copy of its own");
                assertEquals(RuleSet.Held.NOTHING, copy.held(), "and holds no permit, because it took none");
                assertEquals(2, sessions.get(), "and it is a copy of its own, not the one held");
            } finally {
                rules.release(copy);
            }
        } finally {
            holder.giveBack();
        }
    }

    @Test
    @DisplayName("a run interrupted while it waits for a copy is no longer counted on its thread, so the thread's next"
            + " run waits for a copy under the limit rather than taking an extra one as a nested run")
    void interruptedWaitLeavesNoRunCountedOnTheThread() throws InterruptedException {
        AtomicInteger sessions = new AtomicInteger();
        // A window far longer than the test, so a run that waits for the copy held stops at its deadline, or when
        // it's interrupted, and never gives up to make an extra copy.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                CopyLimit.of(1), TimeUnit.MINUTES.toMillis(5));
        Holder holder = holdOneCopy(rules);
        try {
            // Interrupted before it borrows, so its wait for the copy held ends at once, whatever the timing: a
            // thread whose interrupt status is set still waits for a copy in use, and the wait throws. A copy it
            // takes anyway, as a run nested in one still counted on this thread does, is given back, and the status
            // is cleared whatever happens, so the holder can still be joined and the failure is this test's own.
            RuleSet.Copy taken = null;
            Throwable stopped = null;
            boolean interrupted;
            Thread.currentThread().interrupt();
            try {
                taken = rules.borrow(deadline());
            } catch (InterruptedException | TimeoutException e) {
                stopped = e;
            } finally {
                interrupted = Thread.interrupted();
                if (taken != null) {
                    rules.release(taken);
                } else {
                    rules.leaveAfterStop();
                }
            }
            assertNull(taken, "the interrupted run took a copy without waiting, as a run nested in one still counted"
                    + " on this thread does");
            assertInstanceOf(InterruptedException.class, stopped, "the wait for the copy held was interrupted");
            assertTrue(interrupted, "the interrupt status was set again");

            // A deadline already passed, so a run that waits stops at once, and one taken for a nested run takes an
            // extra copy instead. Either way the rule set is left as the run found it.
            RuleSet.Copy extra = null;
            Throwable thrown = null;
            try {
                extra = rules.borrow(Deadline.at(Instant.now().minusSeconds(1)));
            } catch (TimeoutException e) {
                thrown = e;
            } finally {
                if (extra != null) {
                    rules.release(extra);
                } else {
                    rules.leaveAfterStop();
                }
            }
            assertNull(extra, "the run took an extra copy without waiting, as a run nested in one still counted on"
                    + " this thread does");
            assertInstanceOf(TimeoutException.class, thrown, "it waited for the copy held, to its deadline");
            assertEquals(1, sessions.get(), "sessions created: only the held copy's");
        } finally {
            holder.giveBack();
        }
    }

    @Test
    @DisplayName("a thread's count of runs is removed when its outermost run gives its copy back, so a pooled thread"
            + " keeps nothing")
    void theOutermostRunLeavesNoCountOnTheThread() throws Exception {
        RuleSet rules = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED));

        RuleSet.Copy copy = rules.borrow(deadline());
        try {
            assertArrayEquals(new int[] {1}, runsCountedOnThisThread(), "the run is counted while it holds its copy");
        } finally {
            rules.release(copy);
        }

        assertNull(runsCountedOnThisThread(), "the thread still keeps a count, of no run");
    }

    @Test
    @DisplayName("a copy given back is counted as returned, and its permit is released")
    void copiesGivenBackAreCountedAndReleased() {
        CopyPermits permits = new CopyPermits(1);

        permits.giveBack();

        // What awaitPermit reads to tell an engine that is busy from one whose copies are never coming back: a run
        // that waits a whole window while this doesn't move gives up and makes an extra copy. That the count moves
        // before the permit is released is a race no test can pin; this checks that it moves at all.
        assertEquals(1, permits.returned(), "the copy that came back was counted");
        assertEquals(2, permits.available().availablePermits(), "and its permit was released");
    }

    @Test
    @DisplayName("a copy has one session for each language the rules use, created in the order of the compilers")
    void oneSessionPerLanguage() throws InterruptedException, TimeoutException {
        AtomicInteger sessions = new AtomicInteger();
        List<String> created = new CopyOnWriteArrayList<>();
        Map<String, ExpressionCompiler> compilers = new LinkedHashMap<>();
        compilers.put("b", compiler("b", sessions, created));
        compilers.put("a", compiler("a", sessions, created));
        RuleSet rules = new RuleSet(List.of(RULE), compilers, CopyLimit.none(), new CopyPermits(RuleSet.UNLIMITED));

        RuleSet.Copy copy = rules.borrow(Deadline.NONE);
        try {
            assertEquals(List.of("b", "a"), created, "sessions created, by language");
            assertEquals(List.of("b", "a"), List.copyOf(copy.sessions().keySet()));
            assertEquals(new NumberedSession("b", 1), copy.sessions().get("b"));
            assertEquals(new NumberedSession("a", 2), copy.sessions().get("a"));
        } finally {
            rules.release(copy);
        }
    }

    @Test
    @DisplayName("with a limit, a run nested on the same thread gets an extra copy that isn't kept")
    void nestedCopyNotKept() throws InterruptedException, TimeoutException {
        AtomicInteger sessions = new AtomicInteger();
        // A window far longer than the borrows' deadline, so a nested run that waited at all would fail the test
        // instead of reaching the extra copy the wait gives up on.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                CopyLimit.of(1), TimeUnit.MINUTES.toMillis(5));

        RuleSet.Copy outer = rules.borrow(deadline());
        try {
            RuleSet.Copy nested = rules.borrow(deadline());
            try {
                assertEquals(1, rules.limit());
                assertTrue(outer.kept(), "the copy within the limit is kept");
                assertFalse(nested.kept(), "the extra copy isn't kept");
                assertNotEquals(outer.sessions(), nested.sessions());
            } finally {
                rules.release(nested);
            }
        } finally {
            rules.release(outer);
        }

        RuleSet.Copy reused = rules.borrow(deadline());
        rules.release(reused);
        assertSame(outer.sessions(), reused.sessions(), "the kept copy is reused");
        assertEquals(2, sessions.get(), "sessions created");
    }

    @Test
    @DisplayName("a run that waits keeps waiting while copies are given back, however long that takes")
    void waitingWhileCopiesComeBack() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);
        AtomicLong copiesReturned = new AtomicLong();
        // Reports one copy coming back after the first window, and frees a permit with it, so the run waits again
        // instead of making an extra copy, and the second window finds the permit.
        LongSupplier returned = () -> {
            long value = copiesReturned.getAndIncrement();
            if (value == 1) {
                permits.release();
            }
            return value;
        };

        assertTrue(CopyPermits.awaitPermit(permits, returned, 10, deadline()),
                "the run should have taken the freed permit");
        assertEquals(0, permits.availablePermits(), "it took the permit it waited for");
    }

    @Test
    // On a thread of its own, because a wait with no deadline is what this tests: a rule set that never gives up
    // would hold this test for ever, and a deadline JUnit reports after the test returns never arrives.
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("a run gives up waiting when a whole window passes with no copy given back")
    void givingUpWhenNothingComesBack() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);

        assertFalse(CopyPermits.awaitPermit(permits, () -> 7L, 10, Deadline.NONE),
                "nothing came back, so the run makes an extra copy");
    }

    @Test
    @DisplayName("a run that saw a copy come back gives up once a later window passes with none")
    void givingUpOnceCopiesStopComingBack() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);
        AtomicLong calls = new AtomicLong();
        // One copy comes back, to another run, during the first window, and none after it. A deadline far past the
        // windows, so a run that kept waiting fails the test at it instead of holding it.
        LongSupplier returned = () -> calls.getAndIncrement() == 0 ? 0L : 1L;

        assertFalse(CopyPermits.awaitPermit(permits, returned, 10, deadline()),
                "nothing came back in the second window, so the run makes an extra copy");
    }

    @Test
    @DisplayName("a run that finds a free copy takes it without waiting at all")
    void takingAFreeCopy() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(1);

        assertTrue(CopyPermits.awaitPermit(permits, () -> 0L, 10, Deadline.NONE));
        assertEquals(0, permits.availablePermits());
    }

    @Test
    @DisplayName("a run on an interrupted thread that finds a free copy takes it, rather than failing as interrupted")
    void anInterruptedRunTakesAFreeCopy() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(1);

        // Cleared whatever happens, so the tests after this one on the thread aren't interrupted as well.
        Thread.currentThread().interrupt();
        try {
            assertTrue(CopyPermits.awaitPermit(permits, () -> 0L, 10, Deadline.NONE));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, permits.availablePermits());
    }

    @Test
    @DisplayName("a run stops waiting at its deadline when that comes before the window ends")
    void waitingStopsAtTheDeadline() {
        Semaphore permits = new Semaphore(0);
        Deadline deadline = Deadline.from(Duration.ofMillis(50));

        TimeoutException thrown = assertThrows(TimeoutException.class,
                () -> CopyPermits.awaitPermit(permits, () -> 0L, 60_000, deadline));

        // Asked of the deadline, which decides when a run stops: the system clock can be a moment either side of it.
        assertTrue(deadline.hasPassed(), "it gave up before the deadline");
        assertTrue(thrown.getMessage().contains(deadline.instant().toString()), thrown.getMessage());
    }

    @Test
    @DisplayName("a run whose deadline has already passed doesn't wait at all")
    void aPassedDeadlineDoesntWait() {
        Semaphore permits = new Semaphore(0);

        assertThrows(TimeoutException.class,
                () -> CopyPermits.awaitPermit(permits, () -> 0L, 60_000, Deadline.at(Instant.now().minusSeconds(1))));
    }

    @Test
    @DisplayName("a copy given back before the deadline is taken, even inside the last, shortened wait")
    void aCopyBeforeTheDeadlineIsTaken() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);
        Thread giver = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            permits.release();
        });
        giver.start();

        assertTrue(CopyPermits.awaitPermit(permits, () -> 0L, 60_000, deadline()));
        giver.join();
    }

    @Test
    @DisplayName("a deadline later than the window still lets a run give up after a window with nothing given back")
    void aLaterDeadlineKeepsTheWindow() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);

        assertFalse(CopyPermits.awaitPermit(permits, () -> 7L, 10, deadline()));
    }

    @Test
    @DisplayName("a rule list that needs no copies learns it from an extra copy when the shared permits are all held")
    void statelessListLearnsFromAnExtraCopy() throws Exception {
        CopyPermits permits = new CopyPermits(1);
        RuleSet needsCopies = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(1),
                permits, 1);
        ExpressionCompiler stateless = new ExpressionCompiler() {
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
                return Session.none();
            }
        };
        // The rule list a reload loaded, sharing the engine's permits with the one it replaced.
        RuleSet noCopies = new RuleSet(List.of(RULE), Map.of("n", stateless), CopyLimit.of(1), permits, 1);
        // Held on a thread of its own, so the runs below aren't nested ones: like any run after a reload, they wait
        // for the permit, a whole window, before giving up.
        Holder holder = holdOneCopy(needsCopies);
        try {
            // The only permit is held, so the first run of the new list can't take one and makes an extra copy.
            RuleSet.Copy first = noCopies.borrow(deadline());
            try {
                RuleSet.Copy second = noCopies.borrow(deadline());
                try {
                    assertEquals(RuleSet.Kind.SHARED, first.kind(), "the extra copy showed the rules need none");
                    assertEquals(RuleSet.Kind.SHARED, second.kind(), "later runs share the sessions without a permit");
                    assertSame(first.sessions(), second.sessions());
                } finally {
                    noCopies.release(second);
                }
            } finally {
                noCopies.release(first);
            }
        } finally {
            holder.giveBack();
        }
    }

    @Test
    @DisplayName("the first run of a rule list that needs no copies gives back the permit it took")
    void aStatelessFirstRunGivesItsPermitBack() throws InterruptedException, TimeoutException {
        CopyPermits permits = new CopyPermits(1);
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("n", statelessCompiler()), CopyLimit.of(1), permits, 1);

        RuleSet.Copy copy = rules.borrow(deadline());
        try {
            assertEquals(RuleSet.Kind.SHARED, copy.kind(), "the first copy showed the rules need none");
        } finally {
            rules.release(copy);
        }

        assertEquals(1, permits.available().availablePermits(), "the engine's only permit is free again");
    }

    @Test
    @DisplayName("a copy made at load that shows the rules need none is shared at once, so the first run takes no"
            + " permit")
    void aStatelessCopyMadeAtLoadIsShared() throws InterruptedException, TimeoutException {
        CopyPermits permits = new CopyPermits(1);
        // A window far longer than the test, so a first run that waited for the permit held would stop at its
        // deadline rather than give up and learn from an extra copy.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("n", statelessCompiler()), CopyLimit.of(1), permits,
                TimeUnit.MINUTES.toMillis(5));
        rules.prepareCopies(1);
        // The engine's only permit, held as a run of the rules a reload replaced holds it.
        assertTrue(permits.available().tryAcquire());
        try {
            RuleSet.Copy copy = rules.borrow(deadline());
            try {
                assertEquals(RuleSet.Kind.SHARED, copy.kind(), "the copy made at load showed the rules need none");
            } finally {
                rules.release(copy);
            }
        } finally {
            permits.available().release();
        }
    }

    @Test
    @DisplayName("a run waits five seconds without a copy given back before it makes an extra one")
    void stallWindowIsFiveSeconds() {
        // Pinned as a number, as a test that waited out the window would take five seconds of its own. A shorter
        // window would make extra copies whenever a run waits a little while for another run's copy.
        assertEquals(5000, RuleSet.STALL_WINDOW_MILLIS);
    }

    @Test
    @DisplayName("more copies than the limit are warned about once for each rule list")
    void overflowWarnedOnce() throws Exception {
        AtomicInteger sessions = new AtomicInteger();
        // A window of one millisecond, so the two runs that overflow don't wait the five seconds a real one does.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                CopyLimit.of(1), 1);
        RuleSet.Copy held = rules.borrow(deadline());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        String logs;
        try {
            logs = TestLogs.logsOf(() -> {
                try {
                    // Another thread, so the runs aren't nested: a nested run doesn't wait, and doesn't warn. Its
                    // borrows have a deadline, so a rule set that never gives up waiting fails this test instead of
                    // holding it.
                    Thread other = new Thread(() -> {
                        try {
                            rules.release(rules.borrow(deadline()));
                            rules.release(rules.borrow(deadline()));
                        } catch (Exception | Error e) {
                            failure.set(e);
                        }
                    });
                    other.start();
                    other.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } finally {
            rules.release(held);
        }
        assertNull(failure.get(), "an overflowing run never got its copy: " + failure.get());
        assertEquals(1, logs.lines().filter(line -> line.contains("made an extra copy")).count(), logs);
        assertEquals(3, sessions.get(), "the copy held, and one for each overflowing run");
    }

    @Test
    @DisplayName("a run that waited for a build slot and then took a copy given back gives its own slot back")
    void aWaitingRunGivesItsSlotBackWithTheCopyItTook() throws InterruptedException {
        AtomicInteger sessions = new AtomicInteger();
        // One build slot for the whole engine, so a slot that isn't given back is gone for good: every later run
        // that needs a copy would then wait out the whole window before making one. The window is far longer than
        // the borrows' deadlines, so a wait that ended any other way would fail the test.
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED, 1);
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                CopyLimit.none(), permits, TimeUnit.MINUTES.toMillis(5));

        VirtualRun holder = startVirtualRun(rules);
        assertEquals(RuleSet.Held.SLOT, holder.copy().held(), "the first run took the only build slot");
        assertFalse(permits.awaitSlot(0, Deadline.NONE), "the slot is held while the first run's new copy runs");
        VirtualRun waiter = startVirtualRun(rules);
        awaitParked(waiter.thread());

        // The copy is kept before the slot is released, so the run that waited finds it and needs no slot after all.
        holder.giveBack();

        RuleSet.Copy taken = waiter.copy();
        assertSame(holder.copy().sessions(), taken.sessions(), "the run that waited took the copy given back");
        assertEquals(1, sessions.get(), "so it made no copy of its own");
        assertEquals(RuleSet.Held.NOTHING, taken.held(), "and holds nothing to give back with it");
        waiter.giveBack();
        assertTrue(permits.awaitSlot(0, Deadline.NONE), "the slot the run took while waiting was never given back");
    }

    @Test
    @DisplayName("a rule set retired while runs hold copies closes each copy's sessions as its run gives it back")
    void aRetiredRuleSetClosesEachCopyAsItComesBack() throws InterruptedException {
        List<Integer> closed = new CopyOnWriteArrayList<>();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", recordingCompiler(new AtomicInteger(), closed)),
                CopyLimit.none(), new CopyPermits(RuleSet.UNLIMITED));
        // Two runs, because when only one holds a copy the rule set has no user left once it comes back, and closes
        // everything then in any case: a second run still in flight is what tells "closed when you gave it back"
        // from "closed when the slowest run still in flight finished".
        Holder first = holdOneCopy(rules);
        Holder second = holdOneCopy(rules);

        rules.retire();

        assertEquals(List.of(), closed, "no copy has been given back yet");
        first.giveBack();
        assertEquals(List.of(1), closed, "the copy given back was closed at once, not when the last run left");
        second.giveBack();
        assertEquals(List.of(1, 2), closed, "and the last copy when its own run gave it back");
    }

    @Test
    @DisplayName("runs waiting for a permit when the rule set is retired take the copies given back, so they make none,"
            + " and each copy is closed once, before the compilers, when the last run leaves")
    void waitingRunsShareTheCopiesOfARetiredRuleSet() throws InterruptedException {
        int limit = 2;
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        // A window far longer than the test, so a run that waits never gives up and makes an extra copy.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", closingCompiler(made, closed)), CopyLimit.of(limit),
                new CopyPermits(limit), TimeUnit.MINUTES.toMillis(5));
        List<Holder> holders = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            holders.add(holdOneCopy(rules));
        }
        // More runs waiting than there are copies, each giving its copy back as soon as it has it, so the runs that
        // take a copy late take one another run gave back.
        List<VirtualRun> waiting = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            VirtualRun run = startVirtualRun(rules);
            run.askedBack().countDown();
            waiting.add(run);
        }
        awaitWaiters(rules, waiting.size());

        assertNull(rules.retire());
        assertEquals(List.of(), closed, "no copy was idle, and none has been given back");
        for (Holder holder : holders) {
            holder.giveBack();
        }
        for (VirtualRun run : waiting) {
            assertEquals(RuleSet.Held.PERMIT, run.heldWhenBorrowed(), "the run took a kept copy with a permit");
            run.giveBack();
        }

        assertEquals(limit, made.get(), "the runs that waited made no copy of their own");
        assertEachCopyClosedOnceThenTheCompiler(limit, closed);
        assertEquals(0, rules.waiters(), "no run is still counted as waiting");
    }

    @Test
    @DisplayName("a run whose permit comes back without a copy takes one given back while it still counts as waiting,"
            + " on a retired rule set, rather than making one")
    void aRunTakesTheCopyGivenBackAsItGetsItsPermit() throws InterruptedException {
        AtomicInteger made = new AtomicInteger();
        HookedQueue idle = new HookedQueue(() -> { });
        CopyPermits permits = new CopyPermits(2);
        // A window far longer than the test, so the run that waits never gives up and makes an extra copy.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", made, new CopyOnWriteArrayList<>())),
                CopyLimit.of(2), permits, TimeUnit.MINUTES.toMillis(5), idle);
        // One permit taken with no copy, so giving it back wakes the waiting run with no copy kept for it.
        assertTrue(permits.available().tryAcquire());
        Holder holder = holdOneCopy(rules);
        VirtualRun waiter = startVirtualRun(rules);
        awaitWaiters(rules, 1);
        assertNull(rules.retire());
        // The copy held is given back as the run that got the permit looks for an idle one, while it still counts as
        // waiting, so the retired rule set keeps the copy for it.
        idle.beforePoll.set(() -> {
            try {
                holder.giveBack();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        permits.available().release();
        RuleSet.Copy copy = waiter.copy();
        waiter.giveBack();

        assertEquals(Map.of("a", new NumberedSession("a", 1)), copy.sessions(), "the run took the copy given back");
        assertEquals(1, made.get(), "sessions created: only the held copy's");
    }

    @Test
    @DisplayName("copies a retired rule set kept for runs that then stopped waiting are each closed once, before the"
            + " compilers, by the last run to leave")
    void copiesKeptForRunsThatStoppedAreClosedOnce() throws InterruptedException {
        int limit = 2;
        int waiting = 3;
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        CountDownLatch bothKept = new CountDownLatch(limit);
        CountDownLatch allStopped = new CountDownLatch(waiting);
        AtomicBoolean stopping = new AtomicBoolean();
        List<Thread> waiters = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> hookFailure = new AtomicReference<>();
        // Each copy given back waits, while its run still holds the permit, until both are being kept; then the runs
        // waiting are interrupted, once, and each copy waits until all of them have stopped, so that none can take
        // one. Never throws: the rule set would close the copy instead of keeping it.
        HookedQueue idle = new HookedQueue(() -> {
            try {
                bothKept.countDown();
                if (!bothKept.await(30, TimeUnit.SECONDS)) {
                    hookFailure.set(new AssertionError("the copies were never both kept"));
                    return;
                }
                if (stopping.compareAndSet(false, true)) {
                    waiters.forEach(Thread::interrupt);
                }
                if (!allStopped.await(30, TimeUnit.SECONDS)) {
                    hookFailure.set(new AssertionError("the runs waiting never stopped"));
                }
            } catch (InterruptedException e) {
                hookFailure.set(e);
            }
        });
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", closingCompiler(made, closed)), CopyLimit.of(limit),
                new CopyPermits(limit), TimeUnit.MINUTES.toMillis(5), idle);
        List<Holder> holders = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            holders.add(holdOneCopy(rules));
        }
        CountDownLatch leave = new CountDownLatch(1);
        List<Throwable> stops = new CopyOnWriteArrayList<>();
        List<Error> left = new CopyOnWriteArrayList<>();
        for (int i = 0; i < waiting; i++) {
            // Left only once the copies have been given back, so the last of these runs to leave closes them. The
            // interrupt status is cleared while the run waits to leave, and set again before it does, as the engine
            // leaves with it set.
            Thread waiter = Thread.ofPlatform().daemon().start(() -> {
                try {
                    rules.release(rules.borrow(deadline()));
                    stops.add(new AssertionError("the run got a copy"));
                } catch (InterruptedException | TimeoutException e) {
                    stops.add(e);
                    boolean interrupted = Thread.interrupted();
                    allStopped.countDown();
                    try {
                        assertTrue(leave.await(30, TimeUnit.SECONDS), "the run was never let leave");
                    } catch (InterruptedException | AssertionError failure) {
                        stops.add(failure);
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    left.add(rules.leaveAfterStop());
                }
            });
            waiters.add(waiter);
        }
        awaitWaiters(rules, waiting);

        assertNull(rules.retire());
        holders.forEach(holder -> holder.askedBack().countDown());
        for (Holder holder : holders) {
            holder.giveBack();
        }
        assertNull(hookFailure.get(), "the copies were kept, and the runs stopped");
        assertEquals(List.of(), closed, "both copies were kept for the runs waiting, and not closed as they came back");
        leave.countDown();
        for (Thread waiter : waiters) {
            waiter.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(waiter.isAlive(), "a run that stopped never left");
        }

        assertEquals(waiting, stops.stream().filter(InterruptedException.class::isInstance).count(), stops.toString());
        assertEquals(waiting, left.size(), "every run that stopped left");
        left.forEach(error -> assertNull(error, "no fatal Error from closing"));
        assertEquals(limit, made.get(), "the copies held, and no more");
        assertEachCopyClosedOnceThenTheCompiler(limit, closed);
    }

    /**
     * A compiler like {@link #closingCompiler}, whose session number {@code held} waits, as it's made, until
     * {@code letGo} is counted down, after counting down {@code making}, so a test can act while a run makes a copy.
     */
    private static ExpressionCompiler heldCompiler(AtomicInteger counter, List<Integer> closed, int held,
                                                   CountDownLatch making, CountDownLatch letGo) {
        ExpressionCompiler sessions = closingCompiler(counter, closed);
        return new ForwardingExpressionCompiler(sessions) {
            @Override
            public Session newSession() {
                if (counter.get() == held - 1) {
                    making.countDown();
                    try {
                        assertTrue(letGo.await(30, TimeUnit.SECONDS), "the copy being made was never let go");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return sessions.newSession();
            }
        };
    }

    /**
     * Borrows a copy of {@code rules} on a platform thread of its own and gives it back at once, recording the copy's
     * kind and what the thread threw.
     */
    private static Thread borrowAndGiveBack(RuleSet rules, AtomicReference<RuleSet.Kind> kind,
                                            AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().daemon().start(() -> {
            try {
                RuleSet.Copy copy = rules.borrow(deadline());
                kind.set(copy.kind());
                assertNull(rules.release(copy));
            } catch (Exception | Error e) {
                failure.set(e);
            }
        });
    }

    @Test
    @DisplayName("guard: a run that gave up waiting isn't counted as waiting while it makes its extra copy, so a"
            + " retired rule set closes the copies given back meanwhile at once")
    void aRunMakingAnExtraCopyIsntCountedAsWaiting() throws InterruptedException {
        int limit = 2;
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        CountDownLatch making = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        // A short window, which the run waits out, as nothing is given back until it has begun its extra copy.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", heldCompiler(made, closed, limit + 1, making, letGo)),
                CopyLimit.of(limit), new CopyPermits(limit), 100);
        List<Holder> holders = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            holders.add(holdOneCopy(rules));
        }
        assertNull(rules.retire());
        AtomicReference<RuleSet.Kind> kind = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread run = borrowAndGiveBack(rules, kind, failure);
        assertTrue(making.await(30, TimeUnit.SECONDS), "the run never began its extra copy");

        int counted = rules.waiters();
        for (Holder holder : holders) {
            holder.giveBack();
        }
        List<Integer> closedAsGivenBack = List.copyOf(closed);
        letGo.countDown();
        run.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(run.isAlive(), "the run never ended");
        assertNull(failure.get(), "the run failed: " + failure.get());
        assertEquals(RuleSet.Kind.EXTRA, kind.get(), "the run gave up waiting and made an extra copy");
        assertEquals(0, counted, "the run making its copy was still counted as waiting");
        assertEquals(List.of(1, 2), closedAsGivenBack.stream().sorted().toList(),
                "the copies given back were closed at once, not kept for a run that no longer needed them");
        assertEachCopyClosedOnceThenTheCompiler(limit + 1, closed);
    }

    @Test
    @DisplayName("guard: a run that got a permit and found no idle copy isn't counted as waiting while it makes one, so"
            + " a retired rule set closes a copy given back meanwhile at once")
    void aRunMakingAKeptCopyIsntCountedAsWaiting() throws InterruptedException {
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        CountDownLatch making = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        // Two permits, one held by a run of another rule set sharing them, as the rules a reload loaded would. It is
        // given back while the run waits, so the run gets a permit with no idle copy to take. The window is far
        // longer than the test, so the run never gives up.
        CopyPermits permits = new CopyPermits(2);
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", heldCompiler(made, closed, 2, making, letGo)),
                CopyLimit.of(2), permits, TimeUnit.MINUTES.toMillis(5));
        RuleSet next = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(2),
                permits, TimeUnit.MINUTES.toMillis(5));
        Holder holder = holdOneCopy(rules);
        Holder other = holdOneCopy(next);
        AtomicReference<RuleSet.Kind> kind = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread run = borrowAndGiveBack(rules, kind, failure);
        awaitWaiters(rules, 1);
        assertNull(rules.retire());
        other.giveBack();
        assertTrue(making.await(30, TimeUnit.SECONDS), "the run never began its copy");

        int counted = rules.waiters();
        holder.giveBack();
        List<Integer> closedAsGivenBack = List.copyOf(closed);
        letGo.countDown();
        run.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(run.isAlive(), "the run never ended");
        assertNull(failure.get(), "the run failed: " + failure.get());
        assertEquals(RuleSet.Kind.KEPT, kind.get(), "the run made a kept copy with the permit it got");
        assertEquals(0, counted, "the run making its copy was still counted as waiting");
        assertEquals(List.of(1), closedAsGivenBack, "the copy given back was closed at once, not kept for a run that no"
                + " longer needed it");
        assertEachCopyClosedOnceThenTheCompiler(2, closed);
    }

    @Test
    @DisplayName("guard: a run whose look for an idle copy fails once it has waited for its permit is no longer counted"
            + " as waiting, and gives the permit back")
    void aRunThatFailsToLookAfterItsWaitLeavesNothingBehind() throws InterruptedException {
        StackOverflowError failure = new StackOverflowError("looking for an idle copy");
        HookedQueue idle = new HookedQueue(() -> {
        });
        // A window far longer than the test, so the run waits for the copy held rather than giving up.
        CopyPermits permits = new CopyPermits(1);
        RuleSet rules = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(1),
                permits, TimeUnit.MINUTES.toMillis(5), idle);
        Holder holder = holdOneCopy(rules);
        // Only a run that has waited looks now: giving the held copy back adds it without looking.
        idle.pollFailure.set(failure);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread run = Thread.ofPlatform().daemon().start(() -> {
            try {
                rules.release(rules.borrow(deadline()));
            } catch (Exception | Error e) {
                thrown.set(e);
            }
        });
        awaitWaiters(rules, 1);

        holder.giveBack();
        run.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(run.isAlive(), "the run never ended");
        assertSame(failure, thrown.get(), "the run failed with what looking threw");
        assertEquals(0, rules.waiters(), "the run is no longer counted as waiting");
        assertEquals(1, permits.available().availablePermits(), "the permit the run took was given back");
    }

    @Test
    @DisplayName("guard: the build slot a run took before its look for an idle copy failed is free again")
    void theSlotTakenBeforeLookingFailedIsFreeAgain() throws InterruptedException {
        // The scenario SlotGivenBackTest proves, which compiles on releases whose deadlines differ, ending with the
        // check it can't make there: the slot itself is free, and nothing holds it.
        StackOverflowError failure = new StackOverflowError("looking for an idle copy");
        HookedQueue idle = new HookedQueue(() -> {
        });
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED, 1);
        RuleSet rules = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.none(),
                permits, TimeUnit.MINUTES.toMillis(5), idle);
        VirtualRun holder = startVirtualRun(rules);
        assertEquals(RuleSet.Held.SLOT, holder.copy().held(), "the first run took the only build slot");
        VirtualRun failing = startVirtualRun(rules);
        awaitWaiters(rules, 1);
        idle.pollFailure.set(failure);

        holder.giveBack();
        assertTrue(failing.borrowed().await(30, TimeUnit.SECONDS), "the run never finished borrowing");
        failing.thread().join(TimeUnit.SECONDS.toMillis(30));

        assertSame(failure, failing.failure().get(), "the run failed with what looking threw");
        assertTrue(permits.awaitSlot(0, Deadline.NONE), "the slot was given back");
    }

    @Test
    @DisplayName("a run waiting for a build slot when the rule set is retired takes the copy given back, rather than"
            + " making one of its own")
    void aRunWaitingForASlotTakesTheCopyOfARetiredRuleSet() throws InterruptedException {
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        // One build slot, held by the first run while its new copy runs, so the second run waits for it. The window is
        // far longer than the test, so the run that waits never gives up and makes a copy without a slot.
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED, 1);
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", closingCompiler(made, closed)), CopyLimit.none(),
                permits, TimeUnit.MINUTES.toMillis(5));
        VirtualRun holder = startVirtualRun(rules);
        assertEquals(RuleSet.Held.SLOT, holder.copy().held(), "the first run took the only build slot");
        VirtualRun waiter = startVirtualRun(rules);
        waiter.askedBack().countDown();
        awaitWaiters(rules, 1);

        assertNull(rules.retire());
        holder.giveBack();
        RuleSet.Copy taken = waiter.copy();
        waiter.giveBack();

        assertSame(holder.copy().sessions(), taken.sessions(), "the run that waited took the copy given back");
        assertEquals(1, made.get(), "so it made no copy of its own");
        assertEachCopyClosedOnceThenTheCompiler(1, closed);
        assertEquals(0, rules.waiters(), "no run is still counted as waiting");
        assertTrue(permits.awaitSlot(0, Deadline.NONE), "the slot was given back");
    }

    @Test
    @DisplayName("without a limit, a retired rule set that one run waits on keeps one copy given back for it, and"
            + " closes the rest, which runs that hold no build slot give back without waking it")
    void aRetiredRuleSetKeepsOneCopyForEachWaitingRun() throws InterruptedException {
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        // One build slot, held by a virtual run while its new copy runs, so a second virtual run waits for it. Runs on
        // platform threads take no slot, so giving their copies back wakes nobody. The window is far longer than the
        // test, so the run that waits never gives up.
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED, 1);
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", closingCompiler(made, closed)), CopyLimit.none(),
                permits, TimeUnit.MINUTES.toMillis(5));
        VirtualRun slotted = startVirtualRun(rules);
        assertEquals(RuleSet.Held.SLOT, slotted.copy().held(), "the virtual run took the only build slot");
        List<Holder> platform = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            platform.add(holdOneCopy(rules));
        }
        VirtualRun waiter = startVirtualRun(rules);
        waiter.askedBack().countDown();
        awaitWaiters(rules, 1);

        assertNull(rules.retire());
        for (Holder holder : platform) {
            holder.giveBack();
        }
        List<Integer> closedWhileWaiting = List.copyOf(closed);
        slotted.giveBack();
        RuleSet.Copy taken = waiter.copy();
        waiter.giveBack();

        assertEquals(List.of(3, 4), closedWhileWaiting, "the first copy given back was kept for the run waiting, and"
                + " the other two closed as they came back");
        assertEquals(new RecordingSession(2, closed), taken.sessions().get("a"),
                "the run that waited took the copy kept for it");
        assertEquals(4, made.get(), "so it made no copy of its own");
        assertEachCopyClosedOnceThenTheCompiler(4, closed);
    }

    @Test
    @DisplayName("a retired rule set keeps no more idle copies than the limit, however many of its runs wait")
    void aRetiredRuleSetKeepsNoMoreCopiesThanTheLimit() throws InterruptedException {
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        // A limit of one for virtual threads, as the default limit is: a virtual run holds the permit, and two more
        // wait for it, while runs on platform threads take copies without one, so giving them back wakes nobody.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", closingCompiler(made, closed)), new CopyLimit(1, true),
                new CopyPermits(1), TimeUnit.MINUTES.toMillis(5));
        VirtualRun permitted = startVirtualRun(rules);
        assertEquals(RuleSet.Held.PERMIT, permitted.copy().held(), "the virtual run took the only permit");
        List<Holder> platform = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            platform.add(holdOneCopy(rules));
        }
        List<VirtualRun> waiting = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            VirtualRun run = startVirtualRun(rules);
            run.askedBack().countDown();
            waiting.add(run);
        }
        awaitWaiters(rules, waiting.size());

        assertNull(rules.retire());
        for (Holder holder : platform) {
            holder.giveBack();
        }
        List<Integer> closedWhileWaiting = List.copyOf(closed);
        permitted.giveBack();
        for (VirtualRun run : waiting) {
            assertEquals(RuleSet.Held.PERMIT, run.heldWhenBorrowed(), "the run took a kept copy with a permit");
            run.giveBack();
        }

        assertEquals(List.of(3, 4), closedWhileWaiting, "one copy kept, as the limit is one, though two runs wait,"
                + " and the other two closed as they came back");
        assertEquals(4, made.get(), "the runs that waited made no copy of their own");
        assertEachCopyClosedOnceThenTheCompiler(4, closed);
    }

    @Test
    @DisplayName("a run that stalls on a retired rule set takes the copy kept for it, while a run on the new rules"
            + " holds the permit, rather than making an extra one")
    void aStalledRunTakesTheCopyKeptForIt() throws InterruptedException {
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        CountDownLatch kept = new CountDownLatch(1);
        AtomicReference<Throwable> hookFailure = new AtomicReference<>();
        // The run waiting on the old rules is the only virtual thread that uses them. Once it has stalled, it looks
        // for an idle copy only after the copy has been kept for it, so the test doesn't depend on which comes first.
        HookedQueue idle = new HookedQueue(() -> {
        });
        // The old and new rules share one permit for virtual threads, which a run on the new rules holds for the whole
        // test, so the run waiting on the old rules sees nothing come back, and stalls after one short window. A run
        // on a platform thread holds a copy of the old rules without a permit.
        CopyPermits permits = new CopyPermits(1);
        CopyLimit limit = new CopyLimit(1, true);
        RuleSet newRules = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), limit, permits,
                TimeUnit.MINUTES.toMillis(5));
        RuleSet oldRules = new RuleSet(List.of(RULE), Map.of("a", closingCompiler(made, closed)), limit, permits,
                100, idle);
        VirtualRun onNewRules = startVirtualRun(newRules);
        assertEquals(RuleSet.Held.PERMIT, onNewRules.copy().held(), "the run on the new rules took the only permit");
        Holder platform = holdOneCopy(oldRules);
        idle.beforePoll.set(() -> {
            try {
                if (Thread.currentThread().isVirtual() && !kept.await(30, TimeUnit.SECONDS)) {
                    hookFailure.set(new AssertionError("the copy was never kept"));
                }
            } catch (InterruptedException e) {
                hookFailure.set(e);
            }
        });
        VirtualRun waiter = startVirtualRun(oldRules);
        waiter.askedBack().countDown();
        awaitWaiters(oldRules, 1);

        assertNull(oldRules.retire());
        platform.giveBack();
        kept.countDown();
        RuleSet.Copy taken = waiter.copy();
        waiter.giveBack();
        onNewRules.giveBack();

        assertNull(hookFailure.get(), "the copy was kept before the run looked for one");
        assertEquals(RuleSet.Kind.KEPT, taken.kind(), "the run took an idle copy, not an extra one");
        assertEquals(RuleSet.Held.NOTHING, taken.held(), "holding no permit");
        assertEquals(1, made.get(), "so no copy of the old rules was made for it");
        assertEachCopyClosedOnceThenTheCompiler(1, closed);
    }

    @Test
    @DisplayName("a run that stalls on a rule set in use takes an idle copy of it rather than making one, and closes it"
            + " when it gives it back, so a run holding a permit meanwhile leaves no more kept copies than the limit")
    void aStalledRunTakesAnIdleCopy() throws InterruptedException, TimeoutException {
        AtomicInteger made = new AtomicInteger();
        List<Integer> closed = new CopyOnWriteArrayList<>();
        HookedQueue idle = new HookedQueue(() -> {
        });
        // Both rule sets share one permit, which a run on the other one holds until the run here has stalled, after
        // one short window whatever the timing, so nothing came back meanwhile. The copy at load is idle for it.
        CopyPermits permits = new CopyPermits(1);
        RuleSet other = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(1),
                permits, TimeUnit.MINUTES.toMillis(5));
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", recordingCompiler(made, closed)), CopyLimit.of(1),
                permits, 100, idle);
        rules.prepareCopies(1);
        Holder onOther = holdOneCopy(other);

        RuleSet.Copy stalled;
        try {
            stalled = rules.borrow(deadline());
        } finally {
            onOther.giveBack();
        }
        // The permit is back, and a run here takes it and makes a kept copy, as the idle one is in use.
        Holder permitted;
        try {
            permitted = holdOneCopy(rules);
        } finally {
            assertNull(rules.release(stalled));
        }
        permitted.giveBack();

        assertEquals(1, idle.size(), "no more copies are kept than the limit");
        assertEquals(RuleSet.Kind.EXTRA, stalled.kind(), "the idle copy was lent as extra, as the rule set is in use");
        assertEquals(RuleSet.Held.NOTHING, stalled.held(), "holding no permit");
        assertEquals(new RecordingSession(1, closed), stalled.sessions().get("a"),
                "the run took the copy made at load");
        assertEquals(2, made.get(), "the stalled run made no copy; the run holding the permit made one");
        assertEquals(List.of(1), closed, "the stalled run's copy was closed when it was given back");
    }

    /**
     * A language for the tests of who closes a retired rule set's compilers. Its sessions are numbered from 1, and
     * each one's close runs {@code onClose} with its number, and is then recorded, even if that throws. Its compiler
     * records its own close as {@code 0}, with the thread it closed on and how many sessions were still closing then,
     * and then throws {@code compilerFailure}, if it's set.
     */
    private static final class CloseRecorder {
        final AtomicInteger made = new AtomicInteger();
        final List<Integer> closed = new CopyOnWriteArrayList<>();
        final AtomicInteger closing = new AtomicInteger();
        final AtomicInteger compilerCloses = new AtomicInteger();
        volatile IntConsumer onClose = number -> {
        };
        volatile Error compilerFailure;
        volatile String compilerClosedOn;
        volatile int closingWhenCompilerClosed = -1;

        ExpressionCompiler compiler() {
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
                    int number = made.incrementAndGet();
                    return new Session() {
                        @Override
                        public void close() {
                            closing.incrementAndGet();
                            try {
                                onClose.accept(number);
                            } finally {
                                closed.add(number);
                                closing.decrementAndGet();
                            }
                        }
                    };
                }

                @Override
                public void close() {
                    compilerCloses.incrementAndGet();
                    closingWhenCompilerClosed = closing.get();
                    compilerClosedOn = Thread.currentThread().getName();
                    closed.add(0);
                    if (compilerFailure != null) {
                        throw compilerFailure;
                    }
                }
            };
        }
    }

    /**
     * What a session's close does in {@link CloseRecorder}: session number {@code held} counts down {@code closing},
     * then waits until {@code letGo} is counted down, so a test can act while a copy is being closed.
     */
    private static IntConsumer holdingClose(int held, CountDownLatch closing, CountDownLatch letGo) {
        return number -> {
            if (number == held) {
                closing.countDown();
                try {
                    assertTrue(letGo.await(30, TimeUnit.SECONDS), "the copy being closed was never let go");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        };
    }

    /**
     * A run on a named platform thread of its own that borrows one copy and holds it until it's asked back, as
     * {@link VirtualRun} does, and keeps what giving the copy back returned, so a test can tell where a fatal
     * {@link Error} from closing went.
     */
    private record KeepingRun(Thread thread, CountDownLatch borrowed, CountDownLatch askedBack,
                              AtomicReference<RuleSet.Copy> held, AtomicReference<Error> released,
                              AtomicReference<Throwable> failure) {

        /** Waits for the borrow to end and returns the copy it took, failing the test if it took none. */
        RuleSet.Copy copy() throws InterruptedException {
            assertTrue(borrowed.await(30, TimeUnit.SECONDS), "the run never finished borrowing");
            if (failure.get() != null) {
                throw new AssertionError("the run borrowing a copy failed", failure.get());
            }
            return held.get();
        }

        /**
         * Asks for the copy back, waits for the run to end, and fails the test if the run did.
         *
         * @return What giving the copy back returned: the fatal {@link Error} from closing, or {@code null}
         */
        Error giveBack() throws InterruptedException {
            askedBack.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(thread.isAlive(), "the run holding the copy never ended");
            if (failure.get() != null) {
                throw new AssertionError("the run holding the copy failed", failure.get());
            }
            return released.get();
        }
    }

    /**
     * Starts a run on a platform thread named {@code name} that borrows one copy and holds it until it's asked back,
     * and returns at once, before the borrow has ended.
     *
     * @param rules The rule set to borrow from
     * @param name  The name of the run's thread
     * @return The run
     */
    private static KeepingRun startKeepingRun(RuleSet rules, String name) {
        CountDownLatch borrowed = new CountDownLatch(1);
        CountDownLatch askedBack = new CountDownLatch(1);
        AtomicReference<RuleSet.Copy> held = new AtomicReference<>();
        AtomicReference<Error> released = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // As in startVirtualRun: whatever goes wrong on the thread is kept for the test to report, and the copy is
        // given back whatever happens after it was taken. A daemon, so a thread that never ended couldn't keep the JVM
        // from exiting.
        Thread thread = Thread.ofPlatform().daemon().name(name).start(() -> {
            RuleSet.Copy copy;
            try {
                copy = rules.borrow(deadline());
                if (copy == null) {
                    failure.set(new AssertionError("the rule set to borrow from is closed"));
                    return;
                }
                held.set(copy);
            } catch (Exception | Error e) {
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
                released.set(rules.release(copy));
            }
        });
        return new KeepingRun(thread, borrowed, askedBack, held, released, failure);
    }

    /** Retires {@code rules} on a platform thread named {@code loader}, keeping what it returned in {@code fatal}. */
    private static Thread retireOnLoader(RuleSet rules, AtomicReference<Error> fatal) {
        return Thread.ofPlatform().daemon().name("loader").start(() -> fatal.set(rules.retire()));
    }

    /** Waits for the thread retiring a rule set to end, failing the test if it never does. */
    private static void awaitRetired(Thread loader) throws InterruptedException {
        loader.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(loader.isAlive(), "retire() never returned");
    }

    @Test
    @DisplayName("when the last run leaves while retire() is still closing an idle copy, retire() closes the compilers"
            + " after every session, and gets their fatal Error")
    void retireClosesTheCompilersWhenTheLastRunLeavesDuringItsClosing() throws InterruptedException {
        CloseRecorder language = new CloseRecorder();
        InternalError compilerFailure = new InternalError("closing the compiler");
        language.compilerFailure = compilerFailure;
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED));
        // Copies 1 and 2, idle in that order: the run takes copy 1, and 2 stays idle for retire() to close.
        rules.prepareCopies(2);
        KeepingRun run = startKeepingRun(rules, "run");
        run.copy();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        language.onClose = holdingClose(2, closing, letGo);

        AtomicReference<Error> fromRetire = new AtomicReference<>();
        Thread loader = retireOnLoader(rules, fromRetire);
        assertTrue(closing.await(30, TimeUnit.SECONDS), "retire() never closed the idle copy");
        Error fromRun = run.giveBack();
        List<Integer> closedWhileRetireWasClosing = List.copyOf(language.closed);
        letGo.countDown();
        awaitRetired(loader);

        assertEquals(List.of(1), closedWhileRetireWasClosing,
                "the run closed its copy, and not the compilers while retire() was still closing a session");
        assertEquals(List.of(1, 2, 0), language.closed, "each copy closed once, then the compiler");
        assertEquals(0, language.closingWhenCompilerClosed, "no session was still closing when the compiler closed");
        assertEquals("loader", language.compilerClosedOn, "retire(), which finished second, closed the compilers");
        assertSame(compilerFailure, fromRetire.get(), "so it got their fatal Error");
        assertNull(fromRun, "and the run that left first didn't");
    }

    @Test
    @DisplayName("guard: when retire() finishes closing before the last run leaves, that run closes the compilers after"
            + " every session, and gets their fatal Error")
    void theLastRunClosesTheCompilersWhenRetireFinishedFirst() throws InterruptedException {
        CloseRecorder language = new CloseRecorder();
        InternalError compilerFailure = new InternalError("closing the compiler");
        language.compilerFailure = compilerFailure;
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED));
        rules.prepareCopies(2);
        KeepingRun run = startKeepingRun(rules, "run");
        run.copy();

        Error fromRetire = rules.retire();
        List<Integer> closedByRetire = List.copyOf(language.closed);
        Error fromRun = run.giveBack();

        assertNull(fromRetire, "retire() finished first, so it didn't close the compilers");
        assertEquals(List.of(2), closedByRetire, "retire() closed the idle copy only, while the run held its copy");
        assertEquals(List.of(2, 1, 0), language.closed, "each copy closed once, then the compiler");
        assertEquals(1, language.compilerCloses.get(), "the compiler was closed once");
        assertEquals("run", language.compilerClosedOn, "the run, which finished second, closed the compilers");
        assertSame(compilerFailure, fromRun, "so it got their fatal Error");
    }

    @Test
    @DisplayName("a copy kept for a waiting run while retire() is still closing the idle copies goes to that run, and"
            + " a fatal Error from closing it reaches a run, never retire()")
    void aCopyKeptForAWaitingRunDuringRetireGoesToThatRun() throws InterruptedException {
        CloseRecorder language = new CloseRecorder();
        // Two permits, one held by a run of another rule set sharing them, as the rules a reload loaded would, so a
        // third run of these rules waits for the one held here. The window is far longer than the test, so the run
        // that waits never gives up.
        CopyPermits permits = new CopyPermits(2);
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.of(2), permits,
                TimeUnit.MINUTES.toMillis(5));
        RuleSet next = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(2),
                permits, TimeUnit.MINUTES.toMillis(5));
        // Copies 1, 2 and 3, idle in that order: the first run takes copy 1, and 2 and 3 stay idle.
        rules.prepareCopies(3);
        KeepingRun holder = startKeepingRun(rules, "holder");
        RuleSet.Copy givenBack = holder.copy();
        KeepingRun other = startKeepingRun(next, "other");
        other.copy();
        KeepingRun waiter = startKeepingRun(rules, "waiter");
        awaitWaiters(rules, 1);
        InternalError keptFailure = new InternalError("closing the copy kept for the waiting run");
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        IntConsumer holdCopy2 = holdingClose(2, closing, letGo);
        language.onClose = number -> {
            if (number == 1) {
                throw keptFailure;
            }
            holdCopy2.accept(number);
        };

        AtomicReference<Error> fromRetire = new AtomicReference<>();
        Thread loader = retireOnLoader(rules, fromRetire);
        assertTrue(closing.await(30, TimeUnit.SECONDS), "retire() never closed the idle copies");
        // Kept for the run waiting, whose permit this gives back, while retire() is still closing copy 2.
        Error fromHolder = holder.giveBack();
        RuleSet.Copy taken = waiter.copy();
        letGo.countDown();
        awaitRetired(loader);
        Error fromWaiter = waiter.giveBack();
        Error fromOther = other.giveBack();

        assertNull(fromRetire.get(), "retire() didn't close the copy kept for the waiting run");
        assertSame(givenBack.sessions(), taken.sessions(), "the waiting run took the copy kept for it");
        assertNull(fromHolder, "the run that gave the copy back kept it rather than closing it");
        assertSame(keptFailure, fromWaiter, "the waiting run, the last to leave, closed it and got its fatal Error");
        assertNull(fromOther);
        assertEachCopyClosedOnceThenTheCompiler(3, language.closed);
        assertEquals(0, rules.waiters(), "no run is still counted as waiting");
    }

    @Test
    @DisplayName("guard: retiring a rule set again does nothing more, so the compilers are closed once, when the last"
            + " run leaves")
    void retiringAgainClosesTheCompilersOnce() throws InterruptedException, TimeoutException {
        CloseRecorder language = new CloseRecorder();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED));
        rules.prepareCopies(2);
        KeepingRun run = startKeepingRun(rules, "run");
        run.copy();

        Error first = rules.retire();
        Error second = rules.retire();
        List<Integer> closedWhileTheRunHeldItsCopy = List.copyOf(language.closed);
        Error fromRun = run.giveBack();
        Error third = rules.retire();

        assertNull(first);
        assertNull(second);
        assertNull(third);
        assertNull(fromRun);
        assertEquals(List.of(2), closedWhileTheRunHeldItsCopy, "the compilers stayed open while the run held a copy");
        assertEquals(List.of(2, 1, 0), language.closed, "each copy closed once, then the compiler");
        assertEquals(1, language.compilerCloses.get(), "the compiler was closed once");
        assertNull(rules.borrow(deadline()), "the rule set is closed");
    }

    /**
     * Sets {@code idle} to run, at its next look for an idle copy, {@code whileTaking} and then wait until
     * {@code letGo} is counted down, after counting down {@code taking}, so a test can act while retire() is taking
     * the idle copies.
     */
    private static void holdTaking(HookedQueue idle, Runnable whileTaking, CountDownLatch taking,
                                   CountDownLatch letGo) {
        idle.beforePoll.set(() -> {
            whileTaking.run();
            taking.countDown();
            try {
                assertTrue(letGo.await(30, TimeUnit.SECONDS), "retire() was never let take the idle copies");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("a copy given back while retire() is still taking the idle copies, before it has marked the rule set"
            + " retired, is idle when it's retired, so retire() closes it and gets its fatal Error, not the run")
    void aCopyGivenBackBeforeTheRuleSetIsMarkedRetiredIsClosedByRetire() throws InterruptedException {
        CloseRecorder language = new CloseRecorder();
        InternalError givenBackFailure = new InternalError("closing the copy given back");
        language.onClose = number -> {
            if (number == 1) {
                throw givenBackFailure;
            }
        };
        HookedQueue idle = new HookedQueue(() -> {
        });
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED), TimeUnit.MINUTES.toMillis(5), idle);
        // Copies 1 and 2, idle in that order: the run takes copy 1, and retire() takes copy 2.
        rules.prepareCopies(2);
        KeepingRun run = startKeepingRun(rules, "run");
        run.copy();
        CountDownLatch taking = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        holdTaking(idle, () -> {
        }, taking, letGo);

        AtomicReference<Error> fromRetire = new AtomicReference<>();
        Thread loader = retireOnLoader(rules, fromRetire);
        assertTrue(taking.await(30, TimeUnit.SECONDS), "retire() never looked for an idle copy");
        Error fromRun = run.giveBack();
        List<Integer> closedByTheRun = List.copyOf(language.closed);
        letGo.countDown();
        awaitRetired(loader);

        assertEquals(List.of(), closedByTheRun, "the run kept its copy, as the rule set wasn't retired yet");
        assertNull(fromRun, "so the run got no fatal Error");
        assertSame(givenBackFailure, fromRetire.get(), "retire() closed the copy given back, and got its fatal Error");
        assertEquals(List.of(2, 1, 0), language.closed, "each copy closed once, then the compiler");
        assertEquals("loader", language.compilerClosedOn, "retire(), which finished second, closed the compilers");
    }

    @Test
    @DisplayName("guard: a copy a run takes while retire() is still taking the idle copies is the run's to give back,"
            + " and closed then")
    void aCopyTakenWhileRetireIsTakingTheIdleCopiesIsTheRuns() throws InterruptedException {
        CloseRecorder language = new CloseRecorder();
        HookedQueue idle = new HookedQueue(() -> {
        });
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED), TimeUnit.MINUTES.toMillis(5), idle);
        rules.prepareCopies(1);
        // The run takes the only idle copy once retire() has counted it, and before retire() takes it.
        AtomicReference<KeepingRun> run = new AtomicReference<>();
        AtomicReference<Throwable> hookFailure = new AtomicReference<>();
        CountDownLatch taking = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        holdTaking(idle, () -> {
            try {
                run.set(startKeepingRun(rules, "run"));
                run.get().copy();
            } catch (InterruptedException | AssertionError e) {
                hookFailure.set(e);
            }
        }, taking, letGo);

        AtomicReference<Error> fromRetire = new AtomicReference<>();
        Thread loader = retireOnLoader(rules, fromRetire);
        assertTrue(taking.await(30, TimeUnit.SECONDS), "retire() never looked for an idle copy");
        letGo.countDown();
        awaitRetired(loader);
        assertNull(hookFailure.get(), "the run took the idle copy");
        List<Integer> closedByRetire = List.copyOf(language.closed);
        Error fromRun = run.get().giveBack();

        assertNull(fromRetire.get());
        assertEquals(List.of(), closedByRetire, "retire() found no idle copy left to close, and the run held one");
        assertNull(fromRun);
        assertEquals(List.of(1, 0), language.closed, "the run closed its copy as it gave it back, then the compiler");
        assertEquals("run", language.compilerClosedOn, "the run, which finished second, closed the compilers");
    }

    @Test
    @DisplayName("a copy kept for a waiting run that then stops waiting, while retire() is still closing the idle"
            + " copies, is closed by the last run to leave, which gets its fatal Error, never retire()")
    void aCopyKeptForARunThatStoppedIsClosedByTheLastRunNotRetire() throws InterruptedException {
        CloseRecorder language = new CloseRecorder();
        InternalError keptFailure = new InternalError("closing the copy kept for the waiting run");
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        IntConsumer holdCopy2 = holdingClose(2, closing, letGo);
        language.onClose = number -> {
            if (number == 1) {
                throw keptFailure;
            }
            holdCopy2.accept(number);
        };
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Thread> waiter = new AtomicReference<>();
        AtomicReference<Throwable> hookFailure = new AtomicReference<>();
        // The copy given back is kept while the run is still waiting; then, before it's added, the run is interrupted,
        // and the copy waits until the run has stopped, so that it can't take it. Never throws: the rule set would
        // close the copy instead of keeping it.
        HookedQueue idle = new HookedQueue(() -> {
            if (armed.get()) {
                waiter.get().interrupt();
                try {
                    if (!stopped.await(30, TimeUnit.SECONDS)) {
                        hookFailure.set(new AssertionError("the waiting run never stopped"));
                    }
                } catch (InterruptedException e) {
                    hookFailure.set(e);
                }
            }
        });
        // Two permits, one held by a run of another rule set sharing them, so the waiting run waits for the copy held
        // here. The window is far longer than the test, so the run never gives up by itself.
        CopyPermits permits = new CopyPermits(2);
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.of(2), permits,
                TimeUnit.MINUTES.toMillis(5), idle);
        RuleSet next = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(2),
                permits, TimeUnit.MINUTES.toMillis(5));
        // Copies 1 and 2, idle in that order: the holder takes copy 1, and retire() takes copy 2.
        rules.prepareCopies(2);
        armed.set(true);
        KeepingRun holder = startKeepingRun(rules, "holder");
        holder.copy();
        KeepingRun other = startKeepingRun(next, "other");
        other.copy();
        CountDownLatch leave = new CountDownLatch(1);
        AtomicReference<Throwable> stop = new AtomicReference<>();
        AtomicReference<Error> fromWaiter = new AtomicReference<>();
        // Left only once the holder has left, so it's the last run to leave. The interrupt status is cleared while the
        // run waits to leave, and set again before it does, as the engine leaves with it set.
        waiter.set(Thread.ofPlatform().daemon().name("waiter").start(() -> {
            try {
                rules.release(rules.borrow(deadline()));
                stop.set(new AssertionError("the run got a copy"));
            } catch (InterruptedException | TimeoutException e) {
                stop.set(e);
                boolean interrupted = Thread.interrupted();
                stopped.countDown();
                try {
                    assertTrue(leave.await(30, TimeUnit.SECONDS), "the run was never let leave");
                } catch (InterruptedException | AssertionError failure) {
                    stop.set(failure);
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                fromWaiter.set(rules.leaveAfterStop());
            }
        }));
        awaitWaiters(rules, 1);

        AtomicReference<Error> fromRetire = new AtomicReference<>();
        Thread loader = retireOnLoader(rules, fromRetire);
        assertTrue(closing.await(30, TimeUnit.SECONDS), "retire() never closed the idle copy");
        Error fromHolder = holder.giveBack();
        assertNull(hookFailure.get(), "the copy was kept, and the run waiting for it stopped");
        leave.countDown();
        waiter.get().join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(waiter.get().isAlive(), "the run that stopped never left");
        List<Integer> closedBeforeRetireFinished = List.copyOf(language.closed);
        letGo.countDown();
        awaitRetired(loader);
        Error fromOther = other.giveBack();

        assertInstanceOf(InterruptedException.class, stop.get(), "the waiting run stopped when interrupted");
        assertNull(fromHolder, "the holder kept its copy for the waiting run");
        assertEquals(List.of(1), closedBeforeRetireFinished, "the last run to leave closed the kept copy, and not the"
                + " compilers while retire() was still closing a session");
        assertSame(keptFailure, fromWaiter.get(), "so it got its fatal Error");
        assertNull(fromRetire.get(), "and retire() didn't");
        assertNull(fromOther);
        assertEquals(List.of(1, 2, 0), language.closed, "each copy closed once, then the compiler");
        assertEquals("loader", language.compilerClosedOn, "retire(), which finished second, closed the compilers");
    }

    @Test
    @DisplayName("guard: when retire() fails to take the idle copies, it still marks the rule set retired, so the last"
            + " run to leave closes every copy and then the compilers")
    void aRetireThatFailsToTakeTheIdleCopiesStillRetiresTheRuleSet() throws InterruptedException {
        CloseRecorder language = new CloseRecorder();
        StackOverflowError failure = new StackOverflowError("taking an idle copy");
        HookedQueue idle = new HookedQueue(() -> {
        });
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", language.compiler()), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED), TimeUnit.MINUTES.toMillis(5), idle);
        // Copies 1 and 2, idle in that order: the run takes copy 1, and retire() fails to take copy 2.
        rules.prepareCopies(2);
        KeepingRun run = startKeepingRun(rules, "run");
        run.copy();
        idle.pollFailure.set(failure);

        StackOverflowError thrown = assertThrows(StackOverflowError.class, rules::retire);
        List<Integer> closedByRetire = List.copyOf(language.closed);
        Error fromRun = run.giveBack();

        assertSame(failure, thrown, "retire() threw what taking the idle copies threw");
        assertEquals(List.of(), closedByRetire, "retire() took no copy to close, and the run held one");
        assertNull(fromRun);
        assertEquals(List.of(1, 2, 0), language.closed, "the run closed its copy as it gave it back, as the rule set"
                + " was retired, then the copy still idle, then the compiler");
        assertEquals("run", language.compilerClosedOn, "the run, which finished second, closed the compilers");
    }

    @Test
    @DisplayName("the fact-name checks are kept with the rules they belong to")
    void factChecksKeptWithRules() {
        ExpressionCompiler check = compiler("x", new AtomicInteger(), new CopyOnWriteArrayList<>());

        RuleSet rules = new RuleSet(List.of(RULE), Map.of("x", check), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED));

        assertEquals(Map.of("x", check), rules.factChecks());
    }
}
