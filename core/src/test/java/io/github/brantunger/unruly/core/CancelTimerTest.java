package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.TestSupport;
import io.github.brantunger.unruly.api.language.CancelRegistration;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestSupport.await;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1047: the cancel timer's own steps: the actions it keeps pending, due in different wakes, started in the order of
 * their deadlines, however many and in whatever order they're registered; a timer, or an action, that can't be started;
 * an action registered or closed just after the timer started another, or while it waits for a later one; a waiting
 * timer woken only for an action due sooner than the one it waits for; a slow action; a timer thread interrupted while
 * it waits, and one that dies. #1131: every action due when the timer wakes found in one scan, and started in the order
 * it found them, not that of their deadlines, with the slow ones logged in the same wake. #1117: an action the pool of
 * action threads can't make a thread for started on a virtual thread, and one no carrier is free to run there by its
 * slow time logged as not started. #1136: what the timer logs of a failure, at ERROR if it is, or carries, a fatal
 * error, and the actions a timer that dies part way through a wake never handed on started by the one that replaces it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#1047: the cancel timer runs each pending action at its deadline, those due in different wakes in their"
        + " order")
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
            + " order of their deadlines, due in different wakes")
    void manyInDeadlineOrder() throws InterruptedException {
        int actions = 12;
        // The registrations, by deadline. The timer marks each started before it hands it to a thread, so one that
        // runs before a registration with an earlier deadline is marked was started out of order, whatever order the
        // threads then run in, and however few threads the pool runs them on. That shows the order across wakes, not
        // the order the timer starts those due in one wake, which the pool's reused threads no longer tell apart.
        CancelTimer.Registration[] registrations = new CancelTimer.Registration[actions];
        List<String> outOfOrder = new CopyOnWriteArrayList<>();
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
            // Until its action has returned, it's pending: removing it from the list once the others are registered
            // would move the last one registered into its place. The actions are registered latest deadline
            // first, and the timer looks at the pending ones from the last registered down, so a wake late enough to
            // find several due still starts them in the order of their deadlines.
            TestSupport.await(() -> CancelTimer.pendingCount() == 1, 10, "the held action has returned");
            // Every deadline from one clock reading, so their order is the order of i, however long registering takes.
            long first = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            for (int i = actions - 1; i >= 0; i--) {
                int at = i;
                registrations[i] = new CancelTimer.Registration(() -> {
                    for (int earlier = 0; earlier < at; earlier++) {
                        if (!registrations[earlier].finished()) {
                            outOfOrder.add(at + " ran before " + earlier + " was started");
                        }
                    }
                    all.countDown();
                }, first + TimeUnit.MILLISECONDS.toNanos(20L * i), CancelTimer.Registration.MADE);
                CancelTimer.schedule(registrations[i]);
            }

            registered.countDown();
            await(all);
        } finally {
            registered.countDown();
            keeper.close();
        }

        assertEquals(List.of(), outOfOrder);
    }

    @Test
    @DisplayName("#1131: every action due when the timer wakes is marked in one scan, before the first is started,"
            + " however many others are pending")
    void dueTogetherFoundInOneScan() {
        int actions = 50;
        List<CancelTimer.Registration> due = new ArrayList<>();
        List<CancelRegistration> later = new ArrayList<>();
        CountDownLatch all = new CountDownLatch(actions);
        // How many of the due actions are marked started once the timer has started the first of them.
        AtomicInteger markedAtFirst = new AtomicInteger(-1);
        // The timer is held once it has started an action of its own until all are registered, so it wakes once to
        // find them all due, however slowly the machine registers them. Let go whatever happens, so a failure here
        // leaves no timer held for the next test.
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread timer = CancelTimer.runningThread();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch registered = new CountDownLatch(1);
        try {
            Faults.watch(timer, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                holding.countDown();
                await(registered);
            });
            scheduled(Duration.ZERO, CancelTimerTest::nothing);
            // Its watch is used up once it holds, so the next can be set.
            await(holding);
            long passed = System.nanoTime() - 1;
            // Each due one before one that isn't, so the scan passes over those too.
            for (int i = 0; i < actions; i++) {
                CancelTimer.Registration registration = new CancelTimer.Registration(all::countDown, passed,
                        CancelTimer.Registration.MADE);
                CancelTimer.schedule(registration);
                due.add(registration);
                later.add(scheduled(Duration.ofHours(1), () -> fail("ran early")));
            }
            Faults.watch(timer, Faults.Step.CANCEL_ACTION_HANDED_OFF,
                    () -> markedAtFirst.set((int) due.stream().filter(CancelTimer.Registration::finished).count()));

            registered.countDown();
            await(all);
        } finally {
            registered.countDown();
            later.forEach(CancelRegistration::close);
            keeper.close();
        }

        assertEquals(actions, markedAtFirst.get(), "the due actions marked when the first was started");
    }

    @Test
    @DisplayName("#1131: a wake that finds one action slow and another due starts the one and logs the other")
    void startsAndLogsSlowInOneWake() throws InterruptedException {
        CancelTimer.slowAfter(0);
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread timer = CancelTimer.runningThread();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch began = new CountDownLatch(1);
        // How many are pending once the due one has started: the keeper and it, the slow one gone.
        AtomicInteger pendingAtStart = new AtomicInteger(-1);
        String slow = "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the run's deadline"
                + " has run for over 3600000 ms, and is still running: it should only tell the runtime to stop, such"
                + " as by setting a flag the runtime reads";
        Runnable blocks = () -> await(release);
        // Let go whatever happens, so a failure here leaves no timer, nor action, held for the next test.
        try {
            String logs = LogsUntil.logsUntil(slow, () -> {
                Faults.watch(timer, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                    holding.countDown();
                    await(registered);
                });
                // Slow as soon as it has started, and held until the end; begun before the timer looks again, so it's
                // logged as running, however slowly its thread starts.
                scheduled(Duration.ZERO, () -> {
                    began.countDown();
                    blocks.run();
                });
                await(holding);
                await(began);
                // So the one due next isn't slow too, and the slow one's WARN gives the time read in that wake.
                CancelTimer.slowAfter(TimeUnit.HOURS.toNanos(1));
                CancelTimer.schedule(new CancelTimer.Registration(() -> {
                    ran.countDown();
                    blocks.run();
                }, System.nanoTime() - 1, CancelTimer.Registration.MADE));
                Faults.watch(timer, Faults.Step.CANCEL_ACTION_HANDED_OFF,
                        () -> pendingAtStart.set(CancelTimer.pendingCount()));
                registered.countDown();
                await(ran);
            });

            assertEquals(1, logs.lines().filter(line -> line.contains("is still running")).count(), logs);
            assertEquals(2, pendingAtStart.get());
        } finally {
            registered.countDown();
            release.countDown();
            keeper.close();
        }
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
        CountDownLatch first = new CountDownLatch(1);
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            // On the timer's thread, once it has started the first, before it looks for the next: it isn't waiting,
            // so neither registering nor closing notifies it.
            Faults.watch(timer, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                scheduled(Duration.ofMillis(60), () -> closedRan.set(true)).close();
                scheduled(Duration.ofMillis(80), second::countDown);
            });

            scheduled(Duration.ofMillis(50), first::countDown);

            await(first);
            await(second);
        } finally {
            keeper.close();
        }
        assertFalse(closedRan.get());
    }

    @Test
    @DisplayName("#1117: an action the pool can't take runs on a virtual thread of its own that inherits nothing")
    void actionFallsBackToAVirtualThread() throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<ClassLoader> loader = new AtomicReference<>(getClass().getClassLoader());
        CountDownLatch ran = new CountDownLatch(1);
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            awaitNoActionThreads();
            Faults.inject(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_STARTING, 1,
                    new OutOfMemoryError("unable to create native thread"));

            scheduled(Duration.ofMillis(50), () -> {
                ranOn.set(Thread.currentThread());
                loader.set(Thread.currentThread().getContextClassLoader());
                ran.countDown();
            });

            await(ran);
        } finally {
            keeper.close();
        }
        assertTrue(ranOn.get().isVirtual(), ranOn.get()::toString);
        assertEquals(CancelTimer.ACTION_THREAD_NAME, ranOn.get().getName());
        assertNull(loader.get());
    }

    @Test
    @DisplayName("#1136: an action that can't be started on a pooled thread nor a virtual one, for a fatal error, is"
            + " logged at ERROR and dropped, and the timer goes on")
    void actionFailsToStart() throws InterruptedException {
        failsToStart(new OutOfMemoryError("no threads"), "ERROR " + EngineLogs.ENGINE_LOGGER + "An action a language"
                + " registered for the run's deadline couldn't be started: java.lang.OutOfMemoryError: no threads");
    }

    @Test
    @DisplayName("#1136: an action that can't be started on a pooled thread nor a virtual one, for an error that isn't"
            + " fatal, is logged at WARN and dropped, and the timer goes on")
    void actionFailsToStartNotFatal() throws InterruptedException {
        failsToStart(new AssertionError("no threads"), "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language"
                + " registered for the run's deadline couldn't be started: java.lang.AssertionError: no threads");
    }

    // Has an action fail to start on a pooled thread, for want of memory, then on a virtual thread with fallingBack,
    // and checks it's logged as expected and dropped, and leaves the timer serving the next.
    private static void failsToStart(Error fallingBack, String expected) throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        AtomicBoolean ran = new AtomicBoolean();
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            awaitNoActionThreads();
            Faults.inject(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_STARTING, 1,
                    new OutOfMemoryError("unable to create native thread"));
            Faults.injectThen(Faults.Step.CANCEL_ACTION_FALLING_BACK, fallingBack);

            String logs = LogsUntil.logsUntil(expected, () -> scheduled(Duration.ofMillis(50), () -> ran.set(true)));

            assertTrue(logs.contains(expected), logs);
            CountDownLatch next = new CountDownLatch(1);
            scheduled(Duration.ofMillis(50), next::countDown);
            await(next);
        } finally {
            keeper.close();
        }
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("an action still running once it's slow is logged at WARN while it runs, once, even if it never"
            + " returns until told")
    void slowActionLoggedWhileItRuns() throws InterruptedException {
        CancelTimer.slowAfter(0);
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        String slow = "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the run's deadline"
                + " has run for over 0 ms, and is still running: it should only tell the runtime to stop, such as by"
                + " setting a flag the runtime reads";
        // Let go whatever happens, so a failure here leaves no timer, nor action, held for the next test.
        try {
            // The timer looks again only once the action has begun, however slowly its thread starts, so it's found
            // running.
            Faults.watch(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> await(began));

            // Blocks until the WARN has been logged, which shows it was logged while the action ran.
            String logs = LogsUntil.logsUntil(slow, () -> scheduled(Duration.ofMillis(50), () -> {
                began.countDown();
                await(release);
                returned.countDown();
            }), () -> {
                release.countDown();
                await(returned);
                keeper.close();
                awaitTimerExit();
            });

            assertEquals(1, logs.lines().filter(line -> line.contains("is still running")).count(), logs);
            assertEquals(List.of(), logs.lines().filter(line -> line.contains("hasn't started")).toList(), logs);
            assertEquals(TimeUnit.SECONDS.toNanos(1), CancelTimer.SLOW_NANOS);
        } finally {
            began.countDown();
            release.countDown();
            keeper.close();
        }
    }

    @Test
    @DisplayName("#1117: an action that no carrier is free to run, on the virtual thread it falls back to, is logged at"
            + " WARN as not started once it's slow, before it runs")
    void slowNotStartedLoggedAsNotStarted(@TempDir Path dir) throws IOException, InterruptedException {
        // One carrier, in the scenario's JVM only, so the spinning thread holds every carrier there is.
        String output = ChildJvm.run(dir, SlowNotStartedScenario.class, "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn");

        assertTrue(output.contains("WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the"
                + " run's deadline hasn't started after 200 ms: no thread was free"), output);
        assertTrue(output.contains(SlowNotStartedScenario.NOT_RUN), output);
        assertFalse(output.contains("is still running"), output);
    }

    @Test
    @DisplayName("#1117: an action that no carrier is free to run, on the virtual thread it falls back to, is logged at"
            + " WARN as not started once it's slow, in the tests' own JVM too")
    void slowNotStartedLoggedHere() throws InterruptedException {
        // As many virtual threads as there are carriers, each spinning without blocking, hold every carrier, so the
        // virtual thread the action falls back to can't begin; let go whatever happens.
        int carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
                Runtime.getRuntime().availableProcessors());
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        AtomicBoolean spin = new AtomicBoolean(true);
        CountDownLatch spinning = new CountDownLatch(carriers);
        List<Thread> spinners = new ArrayList<>();
        CountDownLatch ran = new CountDownLatch(1);
        String notStarted = "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the run's"
                + " deadline hasn't started after 0 ms: no thread was free";
        try {
            awaitNoActionThreads();
            for (int i = 0; i < carriers; i++) {
                spinners.add(Thread.ofVirtual().start(() -> {
                    spinning.countDown();
                    while (spin.get()) {
                        Thread.onSpinWait();
                    }
                }));
            }
            await(spinning);
            CancelTimer.slowAfter(0);
            Faults.inject(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_STARTING, 1,
                    new OutOfMemoryError("unable to create native thread"));

            String logs = LogsUntil.logsUntil(notStarted, () -> scheduled(Duration.ofMillis(50), ran::countDown),
                    () -> spin.set(false));

            assertTrue(logs.contains(notStarted), logs);
            assertFalse(logs.contains("is still running"), logs);
        } finally {
            spin.set(false);
            keeper.close();
            for (Thread spinner : spinners) {
                spinner.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
        // Once the carriers are free, the action runs after all.
        await(ran);
    }

    @Test
    @DisplayName("#1117, #1136: the actions a timer that dies part way through a wake never handed on are started by"
            + " the timer that replaces it, and aren't logged as not started")
    void timerDeathMidBatchHandsTheRestOn() throws InterruptedException {
        // So the stranded action, once handed on, isn't found slow before its thread begins, however slow the machine.
        // The timer's slow time is set back after each test.
        CancelTimer.slowAfter(TimeUnit.HOURS.toNanos(1));
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread dying = CancelTimer.runningThread();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch strandedRan = new CountDownLatch(1);
        String replaced = "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed, and another has been started"
                + " for the actions still pending: java.lang.AssertionError: the timer dies";
        // Let go whatever happens, so a failure here leaves no timer held for the next test.
        try {
            // The timer is held once it has started an action of its own until both are registered, so it wakes once
            // to find both due, and marks both started. It looks at them from the last registered down, so it starts
            // that one first, then dies before it hands on the one registered before it, which the timer that
            // replaces it at once must hand on.
            Faults.watch(dying, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                holding.countDown();
                await(registered);
            });
            scheduled(Duration.ZERO, CancelTimerTest::nothing);
            await(holding);
            // Until its action has returned, it's pending: removing it from the list once the others are registered
            // would move the last one registered into its place, and the timer would start the other first.
            TestSupport.await(() -> CancelTimer.pendingCount() == 1, 10, "the held action has returned");
            long passed = System.nanoTime() - 1;
            CancelTimer.schedule(new CancelTimer.Registration(strandedRan::countDown, passed,
                    CancelTimer.Registration.MADE));
            CancelTimer.schedule(new CancelTimer.Registration(CancelTimerTest::nothing, passed,
                    CancelTimer.Registration.MADE));
            Faults.watch(dying, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                throw new AssertionError("the timer dies");
            });

            // Captured until the stranded action has run, so a WARN that it hadn't started would be seen.
            String logs = LogsUntil.logsUntil(replaced, registered::countDown, () -> await(strandedRan));

            assertTrue(logs.contains(replaced), logs);
            assertEquals(List.of(), logs.lines().filter(line -> line.contains("hasn't started")).toList(), logs);
            dying.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(dying.isAlive());
        } finally {
            registered.countDown();
            keeper.close();
        }
    }

    @Test
    @DisplayName("#1136: a timer whose death can't be logged is replaced all the same")
    void timerDeathNotLogged() throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread dying = CancelTimer.runningThread();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        // Let go whatever happens, so a failure here leaves no timer held for the next test.
        try {
            Faults.inject(dying, Faults.Step.CANCEL_FAILURE_LOGGED, 1, new OutOfMemoryError("can't log"));
            Faults.watch(dying, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                await(first);
                throw new OutOfMemoryError("the timer dies");
            });

            // The second registered only once the first has run, so the timer dies before it's due, and the timer
            // that replaces it runs it.
            Outcome<Throwable> outcome = capture(() -> {
                scheduled(Duration.ofMillis(50), first::countDown);
                await(first);
                scheduled(Duration.ofMillis(150), second::countDown);
                await(second);
            });

            assertNull(outcome.thrown());
            assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
            assertEquals(List.of(), outcome.lines("ERROR"), outcome.logs());
            dying.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(dying.isAlive());
        } finally {
            keeper.close();
        }
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
        // Let go whatever happens, so a failure here leaves no timer, nor action, held for the next test.
        try {
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
        } finally {
            release.countDown();
            keeper.close();
        }
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
        diesAndIsReplaced(new AssertionError("the timer dies"), "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer"
                + " failed, and another has been started for the actions still pending: java.lang.AssertionError: the"
                + " timer dies");
    }

    @Test
    @DisplayName("#1136: a timer that dies of a fatal error is logged at ERROR, and replaced at once all the same")
    void timerDiesOfAFatalErrorAndIsReplaced() throws InterruptedException {
        diesAndIsReplaced(new OutOfMemoryError("the timer dies"), "ERROR " + EngineLogs.ENGINE_LOGGER + "The cancel"
                + " timer failed, and another has been started for the actions still pending:"
                + " java.lang.OutOfMemoryError: the timer dies");
    }

    // Has the timer die of death once it has started an action, and checks it's logged as expected and replaced at
    // once, the timer that replaces it running the action still pending.
    private static void diesAndIsReplaced(Error death, String expected) throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread dying = CancelTimer.runningThread();
        CountDownLatch registered = new CountDownLatch(1);
        // Let go whatever happens, so a failure here leaves no timer held for the next test.
        try {
            Faults.watch(dying, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                await(registered);
                throw death;
            });
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);

            // Both registered before the timer dies, once it has started the first, however slow the machine. The
            // second only once the first has run, so the scan that found the first is over: a wake late enough to find
            // both due would start both at once, and the timer would die before it had started the other.
            String logs = LogsUntil.logsUntil(expected, () -> {
                scheduled(Duration.ofMillis(50), first::countDown);
                await(first);
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
        AtomicReference<CancelRegistration> sooner = new AtomicReference<>(CancelTimer.NOTHING);
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            Thread dying = CancelTimer.runningThread();
            TestSupport.await(() -> dying.getState() == Thread.State.TIMED_WAITING, 10, "the timer waits");
            Faults.watch(dying, Faults.Step.CANCEL_TIMER_WAITING, () -> {
                throw new AssertionError("the timer dies at once");
            });
            String expected = "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed, and the actions still"
                    + " pending wait for the next one registered to start another: java.lang.AssertionError: the"
                    + " timer dies at once";

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
        } finally {
            sooner.get().close();
            keeper.close();
        }
    }

    @Test
    @DisplayName("an action registered while the timer waits for one due sooner doesn't wake it; one due before that"
            + " does")
    void laterRegistrationDoesNotWake() throws InterruptedException {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        CancelRegistration later = CancelTimer.NOTHING;
        // Counted down once the timer has woken and looked again, as it's about to wait once more.
        CountDownLatch woke = new CountDownLatch(1);
        CountDownLatch soonerRan = new CountDownLatch(1);
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            Thread timer = CancelTimer.runningThread();
            // Waiting for the keeper, however slowly the machine started it, so each registration below is compared
            // with the one it waits for.
            TestSupport.await(() -> timer.getState() == Thread.State.TIMED_WAITING, 10, "the timer waits");
            Faults.watch(timer, Faults.Step.CANCEL_TIMER_WAITING, woke::countDown);

            later = scheduled(Duration.ofHours(2), () -> fail("ran early"));
            // A wake would be seen at once; a machine too slow to show it in this time only lets this pass.
            assertFalse(woke.await(200, TimeUnit.MILLISECONDS), "the later action woke the timer");
            scheduled(Duration.ofMillis(50), soonerRan::countDown);
            await(woke);
            await(soonerRan);
        } finally {
            later.close();
            keeper.close();
        }
    }

    @Test
    @DisplayName("a timer that dies with nothing pending is logged, and one whose replacement can't be started leaves"
            + " the actions pending to the next registration, at ERROR for what kept it from starting")
    void timerDiesLeavingNothingOrUnreplaced() throws InterruptedException {
        String nothing = "WARN " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed with no action pending:"
                + " java.lang.AssertionError: the timer dies";
        // The holder keeps the timer running until it's watched, however slow the machine, and the watch lets it go.
        CancelRegistration holder = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            Thread first = CancelTimer.runningThread();
            // It dies once the action it started has returned, so nothing is pending.
            Faults.watch(first, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                holder.close();
                awaitNothingPending();
                throw new AssertionError("the timer dies");
            });

            String logs = LogsUntil.logsUntil(nothing,
                    () -> scheduled(Duration.ofMillis(200), CancelTimerTest::nothing));

            assertTrue(logs.contains(nothing), logs);
            first.join(TimeUnit.SECONDS.toMillis(10));
            assertNull(CancelTimer.runningThread());
        } finally {
            holder.close();
        }

        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            Thread second = CancelTimer.runningThread();
            Faults.inject(second, Faults.Step.CANCEL_TIMER_STARTING, 1, new OutOfMemoryError("no threads"));
            Faults.watch(second, Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> {
                throw new AssertionError("the timer dies");
            });
            // The OutOfMemoryError that kept another from starting is suppressed on what the timer died of, so it's
            // logged at ERROR (#1136).
            String unreplaced = "ERROR " + EngineLogs.ENGINE_LOGGER + "The cancel timer failed, and the actions still"
                    + " pending wait for the next one registered to start another: java.lang.AssertionError: the"
                    + " timer dies";

            String logs = LogsUntil.logsUntil(unreplaced,
                    () -> scheduled(Duration.ofMillis(50), CancelTimerTest::nothing));

            assertTrue(logs.contains(unreplaced), logs);
            second.join(TimeUnit.SECONDS.toMillis(10));
            assertNull(CancelTimer.runningThread(), "a replacement was started");
        } finally {
            keeper.close();
        }
    }

    private static void nothing() {
        // An action that does nothing.
    }

    // Waits for the actions' pool to end its idle threads, as it does a second after their last action, so it has to
    // make a thread for the next action, and the fault set for that step fails it. Its threads are the platform
    // threads with the actions' name.
    private static void awaitNoActionThreads() throws InterruptedException {
        TestSupport.await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(
                thread -> !thread.isVirtual() && CancelTimer.ACTION_THREAD_NAME.equals(thread.getName())), 10,
                "the pool has no thread");
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
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            later.close();
            scheduled(Duration.ofMillis(50), fence::countDown);
            await(fence);
        } finally {
            later.close();
            first.close();
        }
    }

    @Test
    @DisplayName("a timer interrupted while it sleeps goes on waiting for its actions")
    void interruptedTimer() {
        CancelRegistration pending = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        CountDownLatch ran = new CountDownLatch(1);
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            CancelTimer.runningThread().interrupt();
            scheduled(Duration.ofMillis(100), ran::countDown);

            await(ran);
        } finally {
            pending.close();
        }
    }

    @Test
    @DisplayName("a run keeps only the registrations it may still have to close")
    void finishedRegistrationsLetGo() {
        EvaluationContext context = context(Duration.ofHours(1));
        CancelRegistration first = context.onCancel(() -> fail("ran early"));
        CancelRegistration second = context.onCancel(() -> fail("ran early"));
        AtomicReference<CancelRegistration> third = new AtomicReference<>(CancelTimer.NOTHING);
        // Closed whatever happens, so a failure here leaves no timer kept running for the next test.
        try {
            second.close();

            third.set(context.onCancel(() -> fail("ran early")));

            // The closed one let go, the pending one kept.
            assertSame(first, ((CancelTimer.Registration) third.get()).before());
        } finally {
            third.get().close();
            second.close();
            first.close();
        }
    }
}
