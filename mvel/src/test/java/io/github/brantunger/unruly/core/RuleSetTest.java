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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

// A deadline for every test, because a rule set that never gives up waiting, or that closes while a run still holds
// a copy, would otherwise hold a test here for ever, and one test that waits for ever holds the whole suite. JUnit
// reports this deadline once the test returns, which catches a test that is merely slow, so every borrow that could
// wait is given a deadline of its own, and the one test of waiting with no deadline runs on a thread of its own.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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

    /**
     * A deadline for a borrow that shouldn't have to wait at all, so that one which does fails the test instead of
     * holding it: JUnit reports the test's own deadline only once the test has returned.
     */
    private static Instant deadline() {
        return Instant.now().plusSeconds(10);
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
        Thread holder = virtual ? Thread.ofVirtual().unstarted(holding) : Thread.ofPlatform().daemon().unstarted(holding);
        holder.start();
        assertTrue(lent.await(30, TimeUnit.SECONDS), "the only copy was never lent");
        if (failure.get() != null) {
            throw new AssertionError("the thread meant to hold a copy never got one", failure.get());
        }
        return new Holder(holder, askedBack, failure);
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
            logs = EngineLoggingTest.logsOf(() -> {
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
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())));

        RuleSet.Copy first = rules.borrow(null);
        try {
            RuleSet.Copy second = rules.borrow(null);
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

            RuleSet.Copy reused = rules.borrow(null);
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
                assertFalse(copy.permit(), "and holds no permit, because it took none");
                assertEquals(2, sessions.get(), "and it is a copy of its own, not the one held");
            } finally {
                rules.release(copy);
            }
        } finally {
            holder.giveBack();
        }
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
        RuleSet rules = new RuleSet(List.of(RULE), compilers);

        RuleSet.Copy copy = rules.borrow(null);
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

        assertTrue(RuleSet.awaitPermit(permits, returned, 10, deadline()),
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

        assertFalse(RuleSet.awaitPermit(permits, () -> 7L, 10, null),
                "nothing came back, so the run makes an extra copy");
    }

    @Test
    @DisplayName("a run that finds a free copy takes it without waiting at all")
    void takingAFreeCopy() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(1);

        assertTrue(RuleSet.awaitPermit(permits, () -> 0L, 10, null));
        assertEquals(0, permits.availablePermits());
    }

    @Test
    @DisplayName("a run stops waiting at its deadline when that comes before the window ends")
    void waitingStopsAtTheDeadline() {
        Semaphore permits = new Semaphore(0);
        Instant deadline = Instant.now().plusMillis(50);

        TimeoutException thrown = assertThrows(TimeoutException.class,
                () -> RuleSet.awaitPermit(permits, () -> 0L, 60_000, deadline));

        assertFalse(Instant.now().isBefore(deadline), "it gave up before the deadline");
        assertTrue(thrown.getMessage().contains(deadline.toString()), thrown.getMessage());
    }

    @Test
    @DisplayName("a run whose deadline has already passed doesn't wait at all")
    void aPassedDeadlineDoesntWait() {
        Semaphore permits = new Semaphore(0);

        assertThrows(TimeoutException.class,
                () -> RuleSet.awaitPermit(permits, () -> 0L, 60_000, Instant.now().minusSeconds(1)));
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

        assertTrue(RuleSet.awaitPermit(permits, () -> 0L, 60_000, deadline()));
        giver.join();
    }

    @Test
    @DisplayName("a deadline later than the window still lets a run give up after a window with nothing given back")
    void aLaterDeadlineKeepsTheWindow() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);

        assertFalse(RuleSet.awaitPermit(permits, () -> 7L, 10, deadline()));
    }

    @Test
    @DisplayName("a rule list that needs no copies learns it from an extra copy when the shared permits are all held")
    void statelessListLearnsFromAnExtraCopy() throws Exception {
        CopyPermits permits = new CopyPermits(1);
        RuleSet needsCopies = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(1), permits, 1);
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
            logs = EngineLoggingTest.logsOf(() -> {
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
    @DisplayName("the fact-name checks are kept with the rules they belong to")
    void factChecksKeptWithRules() {
        ExpressionCompiler check = compiler("x", new AtomicInteger(), new CopyOnWriteArrayList<>());

        RuleSet rules = new RuleSet(List.of(RULE), Map.of("x", check));

        assertEquals(Map.of("x", check), rules.factChecks());
    }
}
