package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.TestSupport;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CancelRegistration;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestSupport.await;
import static io.github.brantunger.unruly.TestSupport.throwIfSet;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1047: {@link EvaluationContext#onCancel(Runnable)} has an action run once when the run passes its deadline, unless
 * its registration is closed first: at once if the deadline has passed already, never for a run without one, and never
 * once the run has ended or its engine is closed. The timer that waits for the deadlines starts only when an action is
 * registered, and exits once none is pending.
 *
 * <p>
 * Where a test shows that an action did not run, it waits for a fence instead of sleeping: an action registered for a
 * later deadline, which the timer reaches only after the earlier one's.
 * </p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#1047: a language's action registered with onCancel runs when the run passes its deadline")
class CancelCallbackTest {

    private static final List<Rule> RULES = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
    private static final String ENDED = "onCancel was called after the run ended, when its action could run into"
            + " whatever runs next";

    @AfterEach
    void timerIdle() throws InterruptedException {
        Faults.clear();
        CancelTimer.slowAfter(CancelTimer.SLOW_NANOS);
        // Each test closes what it registered, so the timer is gone before the next test starts.
        assertTimerExits();
    }

    private static EvaluationContext context(Duration fromNow) {
        return new EngineEvaluationContext(Map.of(), Instant.now().plus(fromNow));
    }

    private static void assertTimerExits() throws InterruptedException {
        Thread timer = CancelTimer.runningThread();
        if (timer != null) {
            timer.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(timer.isAlive(), "the timer is still running with nothing pending");
        }
        assertNull(CancelTimer.runningThread(), "a timer is running with nothing pending");
    }

    /** Waits for an action registered for a deadline after every one the test registered before, to have run. */
    private static void awaitFence(Duration fromNow) {
        CountDownLatch fence = new CountDownLatch(1);
        scheduled(fromNow, fence::countDown);
        await(fence);
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

    /**
     * Holds the timer, once it has started an action of its own, until what this returns is run, so it starts no
     * action meanwhile however slow the machine: what the test does in between happens before any deadline is acted
     * on. A watch of the test's own can't be set until then. Running what it returns again does nothing more.
     */
    private static Runnable holdTimer() {
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        CountDownLatch release = new CountDownLatch(1);
        Faults.watch(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_HANDED_OFF, () -> await(release));
        CountDownLatch held = new CountDownLatch(1);
        scheduled(Duration.ZERO, held::countDown);
        await(held);
        return () -> {
            release.countDown();
            keeper.close();
        };
    }

    private static RulesEngine<Map<String, Object>> engine(CompiledAction action, Duration timeout) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new StubExpressionLanguage().action(action));
        if (timeout != null) {
            builder.runTimeout(timeout);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(RULES);
        return engine;
    }

    @Test
    @DisplayName("#1117: the action runs once the deadline passes, on a pooled daemon platform thread, and doesn't"
            + " interrupt the thread that registered it")
    void runsAtTheDeadline() {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        long start = System.nanoTime();

        try (CancelRegistration registration = scheduled(Duration.ofMillis(200), () -> {
            ranOn.set(Thread.currentThread());
            runs.incrementAndGet();
            ran.countDown();
        })) {
            assertNotNull(registration);
            await(ran);
        }

        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(200), "ran before the deadline");
        assertEquals(1, runs.get());
        assertFalse(ranOn.get().isVirtual(), ranOn.get()::toString);
        assertTrue(ranOn.get().isDaemon(), ranOn.get()::toString);
        assertEquals(CancelTimer.ACTION_THREAD_NAME, ranOn.get().getName());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    @DisplayName("#1117: the action of a run on a virtual thread that never blocks runs at the deadline, though the run"
            + " holds the only carrier")
    void runsWhileVirtualCarriersAreBusy(@TempDir Path dir) throws IOException, InterruptedException {
        // One carrier, in the scenario's JVM only, so the spinning run holds every carrier there is.
        String output = ChildJvm.run(dir, BusyCarriersScenario.class, "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1");

        assertTrue(output.contains(BusyCarriersScenario.STOPPED), output);
    }

    @Test
    @DisplayName("an action that blocks delays no other action")
    void blockingActionDelaysNoOther() {
        CountDownLatch otherRan = new CountDownLatch(1);
        CountDownLatch blockerDone = new CountDownLatch(1);
        // Blocks until the action due after it has run: a timer that waited for it would never run that one.
        scheduled(Duration.ofMillis(50), () -> {
            await(otherRan);
            blockerDone.countDown();
        });
        scheduled(Duration.ofMillis(150), otherRan::countDown);

        await(otherRan);
        await(blockerDone);
    }

    @Test
    @DisplayName("an action registered through an action's context stops the action at the run's deadline, and"
            + " doesn't interrupt the run's thread")
    void stopsARunPastItsTimeout() {
        AtomicBoolean interrupted = new AtomicBoolean();
        RulesEngine<Map<String, Object>> engine = engine((context, session) -> {
            spinUntilCancelled(context);
            interrupted.set(Thread.currentThread().isInterrupted());
            return ActionResult.done();
        }, Duration.ofMillis(200));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertInstanceOf(TimeoutException.class, thrown.getCause(), thrown::toString);
        assertFalse(interrupted.get(), "the deadline interrupted the run's thread");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    @DisplayName("an action registered through a condition's context stops the condition at the run's deadline")
    void conditionContext() {
        AtomicBoolean interrupted = new AtomicBoolean();
        ExpressionLanguage spinning = new ExpressionLanguage() {
            @Override
            public String name() {
                return StubExpressionLanguage.LANGUAGE_NAME;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluation, session) -> {
                            spinUntilCancelled(evaluation);
                            interrupted.set(Thread.currentThread().isInterrupted());
                            return true;
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (action, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(spinning).runTimeout(Duration.ofMillis(200)).build();
        engine.load(RULES);

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertInstanceOf(TimeoutException.class, thrown.getCause(), thrown::toString);
        assertFalse(interrupted.get(), "the deadline interrupted the run's thread");
    }

    // A runtime that can only be stopped from outside: it reads the flag the action sets, and nothing else.
    private static void spinUntilCancelled(EvaluationContext context) {
        AtomicBoolean stop = new AtomicBoolean();
        try (CancelRegistration registration = context.onCancel(() -> stop.set(true))) {
            assertNotNull(registration);
            long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!stop.get()) {
                assertTrue(System.nanoTime() < giveUp, "the action never ran");
                Thread.onSpinWait();
            }
        }
    }

    @Test
    @DisplayName("closing the registration before the deadline means the action never runs; closing again does"
            + " nothing")
    void closedBeforeTheDeadline() throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean();
        // Held while it's closed, so its deadline can't be acted on first, however slow the machine; let go whatever
        // happens, so a failure here leaves no timer held for the next test.
        Runnable release = holdTimer();
        CancelRegistration registration;
        try {
            registration = scheduled(Duration.ofMillis(100), () -> ran.set(true));

            registration.close();
            registration.close();
        } finally {
            release.run();
        }
        awaitFence(Duration.ofMillis(300));

        assertFalse(ran.get(), "the action ran after its registration was closed");
        registration.close();
        assertTimerExits();
    }

    @Test
    @DisplayName("closing the registration while its action runs, or after, does nothing, and leaves nothing pending")
    void closedWhileAndAfterItRuns() throws InterruptedException {
        // So only its return can take it off the list: one kept pending until it's slow would still be there.
        CancelTimer.slowAfter(TimeUnit.HOURS.toNanos(1));
        AtomicReference<CancelRegistration> self = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        self.set(scheduled(Duration.ofMillis(100), () -> {
            await(registered);
            runs.incrementAndGet();
            self.get().close();
            ran.countDown();
        }));
        registered.countDown();

        await(ran);
        self.get().close();

        assertEquals(1, runs.get());
        TestSupport.await(() -> CancelTimer.pendingCount() == 0, 10, "nothing is pending once the action returned");
    }

    @Test
    @DisplayName("closing a registration is safe from many threads at once")
    void closedFromManyThreads() throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean();
        // Held while it's closed, so its deadline can't be acted on first, however slowly the threads start; let go
        // whatever happens, so a failure here leaves no timer held for the next test.
        Runnable release = holdTimer();
        try {
            CancelRegistration registration = scheduled(Duration.ofMillis(200), () -> ran.set(true));
            Thread[] closers = new Thread[8];
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < closers.length; i++) {
                closers[i] = new Thread(() -> {
                    await(go);
                    registration.close();
                });
                closers[i].start();
            }
            go.countDown();
            for (Thread closer : closers) {
                closer.join(TimeUnit.SECONDS.toMillis(10));
            }
        } finally {
            release.run();
        }

        awaitFence(Duration.ofMillis(400));
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("a deadline that has already passed runs the action on the calling thread before onCancel returns,"
            + " starting no timer")
    void deadlinePassedRunsAtOnce() throws InterruptedException {
        // A timer another test left to exit isn't this one's.
        assertTimerExits();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        CancelRegistration registration = context(Duration.ofSeconds(-1)).onCancel(
                () -> ranOn.set(Thread.currentThread()));

        assertSame(Thread.currentThread(), ranOn.get());
        assertNull(CancelTimer.runningThread());
        registration.close();
        registration.close();
    }

    @Test
    @DisplayName("what an action throws is logged at WARN and ignored, whether it ran at the deadline or at once")
    void throwLoggedAndIgnored() throws InterruptedException {
        String atTheDeadline = "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the run's"
                + " deadline failed: java.lang.IllegalStateException: at the deadline";

        String logs = LogsUntil.logsUntil(atTheDeadline, () -> context(Duration.ofMillis(50)).onCancel(() -> {
            throw new IllegalStateException("at the deadline");
        }));
        Outcome<Throwable> atOnce = capture(() -> context(Duration.ofSeconds(-1)).onCancel(() -> {
            throw new IllegalArgumentException("at once");
        }));

        assertTrue(logs.contains(atTheDeadline), logs);
        assertNull(atOnce.thrown());
        assertEquals(List.of("An action a language registered for the run's deadline failed:"
                + " java.lang.IllegalArgumentException: at once"), atOnce.lines("WARN"), atOnce.logs());
        // Later actions still run.
        awaitFence(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("a fatal error an action run at once throws, or carries, is thrown from onCancel; a StackOverflowError"
            + " is logged and ignored")
    void fatalErrorAtOnceThrown() {
        OutOfMemoryError fatal = new OutOfMemoryError("at once");
        OutOfMemoryError carried = new OutOfMemoryError("carried");
        EvaluationContext context = context(Duration.ofSeconds(-1));

        Outcome<OutOfMemoryError> thrown = capture(OutOfMemoryError.class, () -> context.onCancel(() -> {
            throw fatal;
        }));
        Outcome<OutOfMemoryError> wrapped = capture(OutOfMemoryError.class, () -> context.onCancel(() -> {
            throw new IllegalStateException("wraps", carried);
        }));
        Outcome<Throwable> overflow = capture(() -> context.onCancel(() -> {
            throw new StackOverflowError("deep");
        }));

        assertSame(fatal, thrown.thrown());
        assertEquals(List.of(), thrown.lines("WARN"), thrown.logs());
        assertSame(carried, wrapped.thrown());
        assertNull(overflow.thrown());
        assertEquals(List.of("An action a language registered for the run's deadline failed:"
                + " java.lang.StackOverflowError: deep"), overflow.lines("WARN"), overflow.logs());
    }

    @Test
    @DisplayName("#1117: a fatal error an action throws at the deadline is logged at WARN, and its pooled thread lives"
            + " on, idle in the pool, for the next action")
    void fatalErrorAtTheDeadlineLogged() throws InterruptedException {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        String expected = "WARN " + EngineLogs.ENGINE_LOGGER + "An action a language registered for the run's"
                + " deadline failed: java.lang.OutOfMemoryError: at the deadline";

        String logs = LogsUntil.logsUntil(expected, () -> scheduled(Duration.ofMillis(50), () -> {
            ranOn.set(Thread.currentThread());
            throw new OutOfMemoryError("at the deadline");
        }));

        assertTrue(logs.contains(expected), logs);
        // Back in the pool, waiting for its next action, within the second it waits for one before it ends.
        Thread thread = ranOn.get();
        TestSupport.await(() -> thread.getState() == Thread.State.TIMED_WAITING, 10, "its thread waits in the pool");
        assertTrue(thread.isAlive());
    }

    @Test
    @DisplayName("a run without a deadline registers nothing and starts no timer; closing what it returns does"
            + " nothing")
    void noDeadline() {
        AtomicReference<Thread> timer = new AtomicReference<>(Thread.currentThread());
        AtomicReference<CancelRegistration> fromRun = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine((context, session) -> {
            fromRun.set(context.onCancel(() -> fail("ran without a deadline")));
            timer.set(CancelTimer.runningThread());
            return ActionResult.done();
        }, null);

        engine.run(new FactMap<>());
        CancelRegistration fromContext = new EngineEvaluationContext(Map.of(), (Instant) null).onCancel(
                () -> fail("ran without a deadline"));

        assertNull(timer.get(), "a timer started");
        assertSame(fromRun.get(), fromContext, "a registration was made");
        fromContext.close();
        assertNull(CancelTimer.runningThread());
    }

    @Test
    @DisplayName("the run's end closes the registrations still open, and onCancel after it throws")
    void runEndClosesLeftovers() throws InterruptedException {
        AtomicReference<EvaluationContext> kept = new AtomicReference<>();
        AtomicReference<CancelRegistration> left = new AtomicReference<>();
        AtomicReference<Thread> timer = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();
        RulesEngine<Map<String, Object>> engine = engine((context, session) -> {
            kept.set(context);
            left.set(context.onCancel(() -> ran.set(true)));
            timer.set(CancelTimer.runningThread());
            return ActionResult.done();
        }, Duration.ofHours(1));

        engine.run(new FactMap<>());

        // An hour away, so only the run's end closing it lets the timer exit now.
        assertNotNull(timer.get(), "no timer started for a pending action");
        timer.get().join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(timer.get().isAlive(), "the run's end left its action pending");
        assertFalse(ran.get());
        left.get().close();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> kept.get().onCancel(() -> fail("registered after the run")));
        assertEquals(ENDED, thrown.getMessage());
    }

    @Test
    @DisplayName("onCancel after the run ended throws for a run without a deadline too, and for a test context once"
            + " ended")
    void afterTheRunWithoutDeadline() throws Exception {
        AtomicReference<EvaluationContext> kept = new AtomicReference<>();
        engine((context, session) -> {
            kept.set(context);
            return ActionResult.done();
        }, null).run(new FactMap<>());
        EvaluationContext test = context(Duration.ofHours(1));
        CancelRegistration open = test.onCancel(() -> fail("ran after its run ended"));
        EngineEvaluationContext.endRun(test);

        assertEquals(ENDED, assertThrows(IllegalStateException.class, () -> kept.get().onCancel(() -> {
        })).getMessage());
        assertEquals(ENDED, assertThrows(IllegalStateException.class, () -> test.onCancel(() -> {
        })).getMessage());
        open.close();
        assertTimerExits();
    }

    @Test
    @DisplayName("onCancel rejects a null action")
    void nullAction() {
        EvaluationContext context = context(Duration.ofHours(1));

        assertEquals("action must not be null",
                assertThrows(NullPointerException.class, () -> context.onCancel(null)).getMessage());
    }

    @Test
    @DisplayName("an action registered in a nested run runs at the outer run's deadline when that comes first")
    void nestedRunUsesTheEarliestDeadline() {
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> innerFailure = new AtomicReference<>();
        RulesEngine<Map<String, Object>> inner = engine((context, session) -> {
            spinUntilCancelled(context);
            interrupted.set(Thread.currentThread().isInterrupted());
            return ActionResult.done();
        }, Duration.ofHours(1));
        RulesEngine<Map<String, Object>> outer = engine((context, session) -> {
            try {
                inner.run(new FactMap<>());
            } catch (RuntimeException e) {
                innerFailure.set(e);
                throw e;
            }
            return ActionResult.done();
        }, Duration.ofMillis(200));
        long start = System.nanoTime();

        assertThrows(RuleExecutionException.class, () -> outer.run(new FactMap<>()));

        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "the inner run's own hour was used");
        assertInstanceOf(RuleExecutionException.class, innerFailure.get());
        assertFalse(interrupted.get());
    }

    @Test
    @DisplayName("the timer starts only for a pending action, and exits once none is pending")
    void timerExitsWhenIdle() throws InterruptedException {
        // A timer another test left to exit isn't this one's.
        assertTimerExits();

        CancelRegistration registration = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread timer = CancelTimer.runningThread();

        assertNotNull(timer);
        assertTrue(timer.isDaemon());
        assertFalse(timer.isVirtual());
        assertEquals(Thread.NORM_PRIORITY, timer.getPriority());
        assertNull(timer.getContextClassLoader());
        assertEquals(CancelTimer.THREAD_NAME, timer.getName());
        registration.close();
        timer.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(timer.isAlive(), "the timer didn't exit");
        assertNull(CancelTimer.runningThread());
    }

    @Test
    @DisplayName("neither the timer nor an action's thread inherits the thread-locals or the context class loader of"
            + " the run that started the timer")
    void nothingInherited() throws InterruptedException {
        assertTimerExits();
        InheritableThreadLocal<String> tenant = new InheritableThreadLocal<>();
        AtomicReference<CancelRegistration> keeper = new AtomicReference<>();
        AtomicReference<String> seen = new AtomicReference<>("not run");
        AtomicReference<ClassLoader> loader = new AtomicReference<>(getClass().getClassLoader());
        CountDownLatch ran = new CountDownLatch(1);
        // A run of tenant A, with a class loader of its own, starts the timer, and registers an action itself too.
        Thread tenantA = new Thread(() -> {
            tenant.set("A");
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) {
            });
            keeper.set(context(Duration.ofHours(1)).onCancel(() -> fail("ran early")));
            scheduled(Duration.ofMillis(50), () -> {
                seen.set(tenant.get());
                loader.set(Thread.currentThread().getContextClassLoader());
                ran.countDown();
            });
        });
        tenantA.start();
        tenantA.join(TimeUnit.SECONDS.toMillis(10));
        // A later run's action, on a timer tenant A started.
        AtomicReference<String> seenLater = new AtomicReference<>("not run");
        CountDownLatch later = new CountDownLatch(1);
        scheduled(Duration.ofMillis(50), () -> {
            seenLater.set(tenant.get());
            later.countDown();
        });

        await(ran);
        await(later);

        assertNull(seen.get());
        assertNull(loader.get());
        assertNull(seenLater.get());
        keeper.get().close();
    }

    @Test
    @DisplayName("an action registered just as the timer exits still runs, on a timer started for it")
    void registeredAsTheTimerExits() throws InterruptedException {
        CancelRegistration first = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        Thread exiting = CancelTimer.runningThread();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Thread> started = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        // Once the exiting timer has given up its place, from another thread, as a run's would be. The action reads
        // which timer serves it: it is pending while it runs, so that timer can't exit before the read, as it can
        // before a read after the registration.
        Faults.watch(exiting, Faults.Step.CANCEL_TIMER_EXITED, () -> {
            Thread registering = new Thread(() -> {
                try {
                    scheduled(Duration.ofMillis(50), () -> {
                        started.set(CancelTimer.runningThread());
                        ran.countDown();
                    });
                } catch (Throwable t) {
                    failed.set(t);
                }
            });
            registering.start();
            try {
                registering.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                failed.set(e);
            }
        });

        first.close();

        await(ran);
        exiting.join(TimeUnit.SECONDS.toMillis(10));
        throwIfSet(failed.get());
        assertFalse(exiting.isAlive());
        assertNotNull(started.get(), "no timer was started for the registration");
        assertNotSame(exiting, started.get());
    }

    @Test
    @DisplayName("an action a run registered still runs at the run's deadline when the engine is closed while the run"
            + " goes on")
    void engineClosedWhileTheRunGoesOn() throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        RulesEngine<Map<String, Object>> engine = engine((context, session) -> {
            context.onCancel(ran::countDown);
            registered.countDown();
            await(closed);
            await(ran);
            return ActionResult.done();
        }, Duration.ofMillis(300));
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread runner = new Thread(() -> failed.set(assertThrows(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()))));
        runner.start();
        await(registered);

        engine.close();
        closed.countDown();

        await(ran);
        runner.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(runner.isAlive());
        assertInstanceOf(TimeoutException.class, failed.get().getCause(), failed.get()::toString);
    }

    @Test
    @DisplayName("registering and closing an action allocates one small object, once a timer is running")
    void registeringAllocatesLittle() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        // Keeps the timer running, so no call starts it.
        CancelRegistration keeper = context(Duration.ofHours(1)).onCancel(() -> fail("ran early"));
        EvaluationContext context = context(Duration.ofHours(1));
        Runnable action = () -> fail("ran early");
        int calls = 100_000;
        for (int i = 0; i < calls; i++) {
            context.onCancel(action).close();
        }

        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < calls; i++) {
            context.onCancel(action).close();
        }
        long bytes = threads.getCurrentThreadAllocatedBytes() - before;
        keeper.close();

        System.out.println("#1047: onCancel and close(): " + (double) bytes / calls + " bytes a call");
        // The registration itself, 40 bytes with compressed references, and nothing else.
        assertTrue(bytes < 64L * calls, bytes + " bytes for " + calls + " calls");
    }
}
