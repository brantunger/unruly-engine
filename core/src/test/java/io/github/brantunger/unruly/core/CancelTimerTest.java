package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestSupport;
import io.github.brantunger.unruly.api.language.CancelRegistration;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestSupport.await;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1047: the cancel timer's own steps: the actions it keeps pending started in the order of their deadlines, however
 * many and in whatever order they're registered; a timer, or an action, that can't be started; an action registered
 * or closed just after the timer started another, or while it waits for a later one; a slow action; a timer thread
 * interrupted while it waits, and one that dies.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#1047: the cancel timer runs each pending action at its deadline, in their order")
class CancelTimerTest {

    @AfterEach
    void timerIdle() {
        Faults.clear();
        CancelTimer.slowAfter(CancelTimer.SLOW_NANOS);
        Thread.interrupted();
        awaitTimerExit();
    }

    // Waits for a running timer to exit, as it does once nothing is pending: an action's slow check is pending until it
    // returns.
    private static void awaitTimerExit() {
        Thread timer = CancelTimer.runningThread();
        if (timer != null) {
            try {
                timer.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertFalse(timer.isAlive(), "the timer is still running with nothing pending");
        }
    }

    private static EvaluationContext context(Duration fromNow) {
        return new EngineEvaluationContext(Map.of(), Instant.now().plus(fromNow));
    }

    /**
     * Hands an action to the timer as onCancel does once it has found the deadline not yet passed, for a test of what
     * the timer does rather than of that check: a deadline that has passed by then, on a slow machine, still leaves it
     * to the timer, where onCancel would run it at once on the calling thread.
     */
    private static CancelRegistration scheduled(Duration fromNow, Runnable action) {
        CancelTimer.Registration registration = CancelTimer.registration(action, Deadline.from(fromNow));
        CancelTimer.schedule(registration);
        return registration;
    }

    @Test
    @DisplayName("actions registered latest deadline first, more than the timer's first list holds, are started in the"
            + " order of their deadlines")
    void manyInDeadlineOrder() {
        int actions = 12;
        // Each action's thread id, which the timer's thread hands out in the order it starts them, whatever order the
        // threads then run in.
        Map<Long, Integer> startedAs = new ConcurrentSkipListMap<>();
        CountDownLatch all = new CountDownLatch(actions);
        // The timer is held once it has started an action of its own until all are registered, so none is started
        // before a later one with an earlier deadline is registered, however slowly the machine registers them.
        // Let go whatever happens, so a failure here leaves no timer held for the next test.
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        CountDownLatch registered = new CountDownLatch(1);
        try {
            Faults.watch(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> await(registered));
            CountDownLatch held = new CountDownLatch(1);
            scheduled(Duration.ZERO, held::countDown);
            await(held);
            // Every deadline from one clock reading, so their order is the order of i, however long registering takes.
            long first = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            for (int i = actions - 1; i >= 0; i--) {
                int at = i;
                CancelTimer.schedule(new CancelTimer.Registration(() -> {
                    startedAs.put(Thread.currentThread().threadId(), at);
                    all.countDown();
                }, first + TimeUnit.MILLISECONDS.toNanos(20L * i), CancelTimer.Registration.MADE));
            }

            registered.countDown();
            await(all);
        } finally {
            registered.countDown();
            keeper.close();
        }

        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < actions; i++) {
            expected.add(i);
        }
        assertEquals(expected, List.copyOf(startedAs.values()));
    }

    @Test
    @DisplayName("a timer that can't be started fails onCancel with what starting it threw, and leaves nothing pending")
    void timerFailsToStart() throws InterruptedException {
        OutOfMemoryError noThreads = new OutOfMemoryError("unable to create native thread");
        Faults.inject(Faults.Step.CANCEL_TIMER_STARTING, 1, noThreads);
        // An hour away, so onCancel hands it to the timer however slow the machine, rather than running it at once.
        EvaluationContext context = context(Duration.ofHours(1));

        assertSame(noThreads, assertThrows(OutOfMemoryError.class, () -> context.onCancel(() -> fail("ran"))));

        assertNull(CancelTimer.runningThread());
        // Nothing pending, so no timer ever runs the one that failed.
        assertEquals(0, CancelTimer.pendingCount());
        // The next registration starts the timer.
        CountDownLatch ran = new CountDownLatch(1);
        scheduled(Duration.ofMillis(200), ran::countDown);
        await(ran);
    }

    @Test
    @DisplayName("an action whose failure can't be logged is ignored all the same")
    void failureNotLogged() {
        Faults.inject(Faults.Step.CANCEL_FAILURE_LOGGED, 1, new StackOverflowError());

        Outcome<Throwable> outcome = capture(() -> context(Duration.ofSeconds(-1)).onCancel(() -> {
            throw new IllegalStateException("not logged");
        }));

        assertNull(outcome.thrown());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("a registration closed before the timer is handed it stays closed, and starts no timer")
    void closedBeforeScheduled() {
        CancelTimer.Registration registration = CancelTimer.registration(() -> fail("ran"),
                Deadline.from(Duration.ofMillis(10)));

        registration.close();
        CancelTimer.schedule(registration);

        assertTrue(registration.finished());
        assertNull(CancelTimer.runningThread());
    }

    @Test
    @DisplayName("an action registered, or closed, just after the timer has started another is still served")
    void registeredAsAnotherStarts() throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread timer = CancelTimer.runningThread();
        CountDownLatch second = new CountDownLatch(1);
        AtomicBoolean closedRan = new AtomicBoolean();
        // On the timer's thread, once it has started the first, before it looks for the next: it isn't waiting, so
        // neither registering nor closing notifies it.
        Faults.watch(timer, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
            scheduled(Duration.ofMillis(60), () -> closedRan.set(true)).close();
            scheduled(Duration.ofMillis(80), second::countDown);
        });
        CountDownLatch first = new CountDownLatch(1);

        scheduled(Duration.ofMillis(50), first::countDown);

        await(first);
        await(second);
        assertFalse(closedRan.get());
        keeper.close();
    }

    @Test
    @DisplayName("an action that can't be started on a virtual thread runs on a daemon platform thread that inherits"
            + " nothing")
    void actionFallsBackToAPlatformThread() {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Faults.inject(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_STARTING, 1,
                new OutOfMemoryError("no virtual threads"));
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<ClassLoader> loader = new AtomicReference<>(getClass().getClassLoader());
        CountDownLatch ran = new CountDownLatch(1);

        scheduled(Duration.ofMillis(50), () -> {
            ranOn.set(Thread.currentThread());
            loader.set(Thread.currentThread().getContextClassLoader());
            ran.countDown();
        });

        await(ran);
        assertFalse(ranOn.get().isVirtual());
        assertTrue(ranOn.get().isDaemon());
        assertEquals(CancelTimer.ACTION_THREAD_NAME, ranOn.get().getName());
        assertNull(loader.get());
        keeper.close();
    }

    @Test
    @DisplayName("an action that can't be started on a virtual thread nor a platform one is logged at WARN and"
            + " dropped, and the timer goes on")
    void actionFailsToStart() throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Faults.inject(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_STARTING, 1,
                new OutOfMemoryError("no virtual threads"));
        Faults.injectThen(Faults.Step.CANCEL_ACTION_FALLING_BACK, new OutOfMemoryError("no threads"));
        AtomicBoolean ran = new AtomicBoolean();
        String expected = "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the run's"
                + " deadline couldn't be started: java.lang.OutOfMemoryError: no threads";

        String logs = LogsUntil.logsUntil(expected, () -> scheduled(Duration.ofMillis(50), () -> ran.set(true)));

        assertTrue(logs.contains(expected), logs);
        CountDownLatch next = new CountDownLatch(1);
        scheduled(Duration.ofMillis(50), next::countDown);
        await(next);
        assertFalse(ran.get());
        keeper.close();
    }

    @Test
    @DisplayName("an action still running once it's slow is logged at WARN while it runs, once, even if it never"
            + " returns until told")
    void slowActionLoggedWhileItRuns() throws InterruptedException {
        CancelTimer.slowAfter(0);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        String slow = "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the run's deadline"
                + " has run for over 0 ms, and is still running: it should only tell the runtime to stop, such as by"
                + " setting a flag the runtime reads";

        // Blocks until the WARN has been logged, which shows it was logged while the action ran.
        String logs = LogsUntil.logsUntil(slow, () -> scheduled(Duration.ofMillis(50), () -> {
            await(release);
            returned.countDown();
        }), () -> {
            release.countDown();
            await(returned);
            awaitTimerExit();
        });

        assertEquals(1, logs.lines().filter(line -> line.contains("is still running")).count(), logs);
        assertEquals(TimeUnit.SECONDS.toNanos(1), CancelTimer.SLOW_NANOS);
    }

    @Test
    @DisplayName("a slow action whose WARN can't be logged leaves the timer serving the rest")
    void slowWarningNotLogged() throws InterruptedException {
        CancelTimer.slowAfter(0);
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread timer = CancelTimer.runningThread();
        Faults.inject(timer, Faults.Step.CANCEL_FAILURE_LOGGED, 1, new OutOfMemoryError("can't log"));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch next = new CountDownLatch(1);

        Outcome<Throwable> outcome = capture(() -> {
            scheduled(Duration.ofMillis(50), () -> {
                await(release);
                returned.countDown();
            });
            // Only the keeper is left once the timer has found the action slow, and failed to log it.
            TestSupport.await(() -> CancelTimer.pendingCount() == 1 && returned.getCount() == 1, 10,
                    "the action is found slow");
            // So the next action, which returns at once, isn't found slow too, however slow the machine.
            CancelTimer.slowAfter(TimeUnit.HOURS.toNanos(1));
            scheduled(Duration.ofMillis(50), next::countDown);
            await(next);
            release.countDown();
            await(returned);
        });

        assertNull(outcome.thrown());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
        assertSame(timer, CancelTimer.runningThread(), "the timer died");
        keeper.close();
    }

    @Test
    @DisplayName("an action that returns before it's slow isn't logged as slow")
    void quickActionNotLogged() {
        CountDownLatch ran = new CountDownLatch(1);
        // It returns before it's slow however slow the machine; if it were kept pending once it had returned, the timer
        // would wait for it to be slow, and not exit.
        CancelTimer.slowAfter(TimeUnit.HOURS.toNanos(1));

        Outcome<Throwable> quick = capture(() -> {
            scheduled(Duration.ofMillis(50), ran::countDown);
            await(ran);
            awaitTimerExit();
        });

        assertEquals(List.of(), quick.lines("WARN"), quick.logs());
    }

    @Test
    @DisplayName("a timer that dies once it has started an action is replaced at once, and the actions still pending"
            + " run without another registration")
    void timerDiesAndIsReplaced() throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread dying = CancelTimer.runningThread();
        CountDownLatch registered = new CountDownLatch(1);
        // Let go whatever happens, so a failure here leaves no timer held for the next test.
        try {
            Faults.watch(dying, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                await(registered);
                throw new AssertionError("the timer dies");
            });
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            String expected = "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed, and another has been"
                    + " started for the actions still pending: java.lang.AssertionError: the timer dies";

            // Both registered before the timer dies, once it has started the first, however slow the machine.
            String logs = LogsUntil.logsUntil(expected, () -> {
                scheduled(Duration.ofMillis(50), first::countDown);
                scheduled(Duration.ofMillis(150), second::countDown);
                registered.countDown();
            });

            assertTrue(logs.contains(expected), logs);
            await(first);
            await(second);
            dying.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(dying.isAlive());
            Thread replacement = CancelTimer.runningThread();
            assertNotNull(replacement, "no timer serves the action still pending");
            assertNotSame(dying, replacement);
        } finally {
            registered.countDown();
            keeper.close();
        }
    }

    @Test
    @DisplayName("a timer that dies before it has started any action isn't replaced: the next registration starts"
            + " another")
    void timerDiesWithoutProgress() throws InterruptedException {
        // A timer that starts an action, and so made progress, then exits: the next timer must start without it.
        CountDownLatch started = new CountDownLatch(1);
        scheduled(Duration.ofMillis(50), started::countDown);
        await(started);
        awaitTimerExit();
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread dying = CancelTimer.runningThread();
        TestSupport.await(() -> dying.getState() == Thread.State.TIMED_WAITING, 10, "the timer waits");
        Faults.watch(dying, Faults.Step.CANCEL_TIMER_WAITING, () -> {
            throw new AssertionError("the timer dies at once");
        });
        AtomicReference<CancelRegistration> sooner = new AtomicReference<>();
        String expected = "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed, and the actions still"
                + " pending wait for the next one registered to start another: java.lang.AssertionError: the timer"
                + " dies at once";

        // Due sooner than the one it waits for, so it wakes, looks again, and dies before it starts anything.
        String logs = LogsUntil.logsUntil(expected,
                () -> sooner.set(context(Duration.ofMinutes(30)).onCancel(() -> fail("ran early"))));

        assertTrue(logs.contains(expected), logs);
        dying.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(dying.isAlive());
        assertNull(CancelTimer.runningThread(), "a timer that did nothing was replaced");
        assertEquals(2, CancelTimer.pendingCount());
        CountDownLatch next = new CountDownLatch(1);
        scheduled(Duration.ofMillis(50), next::countDown);
        await(next);
        sooner.get().close();
        keeper.close();
    }

    @Test
    @DisplayName("a timer that dies with nothing pending is logged, and one whose replacement can't be started leaves"
            + " the actions pending to the next registration")
    void timerDiesLeavingNothingOrUnreplaced() throws InterruptedException {
        String nothing = "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed with no action pending:"
                + " java.lang.AssertionError: the timer dies";
        // The holder keeps the timer running until it's watched, however slow the machine, and the watch lets it go.
        CancelRegistration holder = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread first = CancelTimer.runningThread();
        // It dies once the action it started has returned, so nothing is pending.
        Faults.watch(first, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
            holder.close();
            awaitNothingPending();
            throw new AssertionError("the timer dies");
        });

        String logs = LogsUntil.logsUntil(nothing, () -> scheduled(Duration.ofMillis(200), CancelTimerTest::nothing));

        assertTrue(logs.contains(nothing), logs);
        first.join(TimeUnit.SECONDS.toMillis(10));
        assertNull(CancelTimer.runningThread());

        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread second = CancelTimer.runningThread();
        Faults.inject(second, Faults.Step.CANCEL_TIMER_STARTING, 1, new OutOfMemoryError("no threads"));
        Faults.watch(second, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
            throw new AssertionError("the timer dies");
        });
        String unreplaced = "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed, and the actions still"
                + " pending wait for the next one registered to start another: java.lang.AssertionError: the timer"
                + " dies";

        logs = LogsUntil.logsUntil(unreplaced, () -> scheduled(Duration.ofMillis(50), CancelTimerTest::nothing));

        assertTrue(logs.contains(unreplaced), logs);
        second.join(TimeUnit.SECONDS.toMillis(10));
        assertNull(CancelTimer.runningThread(), "a replacement was started");
        keeper.close();
    }

    private static void nothing() {
        // An action that does nothing.
    }

    private static void awaitNothingPending() {
        try {
            TestSupport.await(() -> CancelTimer.pendingCount() == 0, 10, "nothing is pending");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("an action due after the one the timer waits for, and closing one that isn't it, leave it serving the"
            + " rest")
    void laterActionsLeaveTheRestServed() {
        CancelRegistration first = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        CancelRegistration later = context(Duration.ofHours(2)).onCancel(() -> fail("ran early"));
        CountDownLatch fence = new CountDownLatch(1);

        later.close();
        scheduled(Duration.ofMillis(50), fence::countDown);
        await(fence);

        first.close();
    }

    @Test
    @DisplayName("a timer interrupted while it sleeps goes on waiting for its actions")
    void interruptedTimer() {
        CancelRegistration pending = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        CountDownLatch ran = new CountDownLatch(1);

        CancelTimer.runningThread().interrupt();
        scheduled(Duration.ofMillis(100), ran::countDown);

        await(ran);
        pending.close();
    }

    @Test
    @DisplayName("a run keeps only the registrations it may still have to close")
    void finishedRegistrationsLetGo() {
        EvaluationContext context = context(Duration.ofHours(1));
        CancelRegistration first = context.onCancel(() -> fail("ran early"));
        CancelRegistration second = context.onCancel(() -> fail("ran early"));
        second.close();

        CancelTimer.Registration third = (CancelTimer.Registration) context.onCancel(() -> fail("ran early"));

        // The closed one let go, the pending one kept.
        assertSame(first, third.before());
        third.close();
        first.close();
    }
}
