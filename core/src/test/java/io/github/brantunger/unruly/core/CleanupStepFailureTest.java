package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestLogs;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #839 and #841: one step of a run's, a load's or a close's set-up or clean-up fails, as a {@link StackOverflowError}
 * or an {@link OutOfMemoryError} can at any call, made to fail at a normal depth through {@link Faults}. Each test
 * names the state the failure used to leave taken: the step after the one that fails must still run, a step whose
 * failure left something taken must be tried again, and a retirement that failed part way must be finished later.
 * {@code StackEndSweepTest} shows the same with real overflows, at the end of the stack.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#839/#841: a step of set-up or clean-up that fails leaves no state taken")
class CleanupStepFailureTest {

    /** A deadline that has passed, so a borrow that finds no permit free stops at once rather than waiting. */
    private static final Deadline PASSED = Deadline.from(Duration.ZERO);

    private static final List<Rule> RULES = List.of(
            Rule.builder().ruleName("r").priority(1).condition("c").action("a").build());

    /**
     * A language that counts the compilers and sessions it makes and closes. Its conditions are true and its actions
     * do nothing, each session is its own, so every run needs a copy, and it fails to make a session while
     * {@link #failSessions} or {@link #sessionFailure} is set.
     */
    private static final class Counting implements ExpressionLanguage {
        private final AtomicInteger compilers = new AtomicInteger();
        private final AtomicInteger compilersClosed = new AtomicInteger();
        private final AtomicInteger sessions = new AtomicInteger();
        private final AtomicInteger sessionsClosed = new AtomicInteger();
        private volatile boolean failSessions;
        // What making a session throws, while set.
        private volatile Error sessionFailure;
        // What closing a session runs, once it has counted it, while set.
        private volatile Runnable onSessionClose = () -> {
        };
        // What closing a compiler throws, once it has counted it, while set.
        private volatile Error closeFailure;

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            compilers.incrementAndGet();
            return compiler();
        }

        ExpressionCompiler compiler() {
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
                    if (failSessions) {
                        throw new IllegalStateException("no session");
                    }
                    Error failure = sessionFailure;
                    if (failure != null) {
                        throw failure;
                    }
                    sessions.incrementAndGet();
                    return new Session() {
                        @Override
                        public void close() {
                            sessionsClosed.incrementAndGet();
                            onSessionClose.run();
                        }
                    };
                }

                @Override
                public void close() {
                    compilersClosed.incrementAndGet();
                    Error failure = closeFailure;
                    if (failure != null) {
                        throw failure;
                    }
                }
            };
        }

        void assertAllClosed(String when) {
            assertEquals(compilers.get(), compilersClosed.get(), when + ": every compiler is closed");
            assertEquals(sessions.get(), sessionsClosed.get(), when + ": every session is closed");
        }
    }

    @AfterEach
    void takeBackTheFault() {
        Faults.clear();
    }

    @SuppressWarnings("unchecked")
    private static AbstractRulesEngine<Object> engine(Counting language, int copiesAtLoad, RuleListener listener) {
        RulesEngine<Object> engine = RulesEngineBuilder.firstMatch(Object::new).language(language).maxCopies(1)
                .copiesAtLoad(copiesAtLoad).listener(listener).build();
        return (AbstractRulesEngine<Object>) engine;
    }

    private static AbstractRulesEngine<Object> engine(Counting language) {
        AbstractRulesEngine<Object> engine = engine(language, 0, new RuleListener() {
        });
        engine.load(RULES);
        return engine;
    }

    // How many permits a thread of its own takes, up to two. It counts no run in progress, so a borrow that finds no
    // permit free stops at once; a second borrow on it is nested, and takes a permit only if one is free, so an engine
    // of one permit that lends two gave one back twice.
    private static int freePermits(AbstractRulesEngine<Object> engine) throws InterruptedException {
        AtomicInteger free = new AtomicInteger(-1);
        Thread checker = new Thread(() -> {
            RuleSet rules = engine.currentRules();
            try {
                RuleSet.Copy first = rules.borrow(PASSED);
                RuleSet.Copy second = rules.borrow(PASSED);
                free.set((first.held() == RuleSet.Held.PERMIT ? 1 : 0)
                        + (second.held() == RuleSet.Held.PERMIT ? 1 : 0));
                Failures.throwIfPresent(rules.release(second));
                Failures.throwIfPresent(rules.release(first));
            } catch (TimeoutException e) {
                Failures.throwIfPresent(rules.leaveAfterStop());
                free.set(0);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        checker.start();
        checker.join(TimeUnit.SECONDS.toMillis(10));
        assertNotEquals(-1, free.get(), "the check for free permits finished");
        return free.get();
    }

    @Test
    @DisplayName("#841 deadline: an OutOfMemoryError counting a run as it starts leaves no deadline on the thread")
    void outOfMemoryCountingTheRunLeavesNoDeadline() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> timed = engine(language);
        AbstractRulesEngine<Object> untimed = engine(language);
        OutOfMemoryError failure = new OutOfMemoryError("counting the run");
        Faults.inject(Faults.Step.RUN_COUNTED, 1, failure);

        assertSame(failure, assertThrows(OutOfMemoryError.class,
                () -> timed.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofNanos(1)))));

        assertSame(Deadline.NONE, Cancellation.deadlineFrom(null), "the thread has no deadline");
        assertDoesNotThrow(() -> untimed.run(new FactMap<>()), "a run without a timeout on the same thread");
        timed.close();
        untimed.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#841 deadline: setting a run's deadline that fails once it has stored it leaves no deadline behind")
    void settingTheDeadlineThatFailsLeavesNoDeadline() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> timed = engine(language);
        AbstractRulesEngine<Object> untimed = engine(language);
        Faults.inject(Faults.Step.DEADLINE_SET, 1, new OutOfMemoryError("setting the deadline"));

        assertThrows(OutOfMemoryError.class,
                () -> timed.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofNanos(1))));

        assertSame(Deadline.NONE, Cancellation.deadlineFrom(null), "the thread has no deadline");
        assertDoesNotThrow(() -> untimed.run(new FactMap<>()), "a run without a timeout on the same thread");
    }

    @Test
    @DisplayName("#839 deadline: uncounting a run that fails on the way out still puts back the thread's deadline")
    void uncountingTheRunThatFailsStillPutsBackTheDeadline() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> timed = engine(language);
        AbstractRulesEngine<Object> untimed = engine(language);
        Faults.inject(Faults.Step.RUN_UNCOUNTED, 1, new StackOverflowError("uncounting the run"));

        assertThrows(StackOverflowError.class,
                () -> timed.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofNanos(1))));

        assertSame(Deadline.NONE, Cancellation.deadlineFrom(null), "the thread has no deadline");
        assertDoesNotThrow(() -> untimed.run(new FactMap<>()), "a run without a timeout on the same thread");
    }

    @Test
    @DisplayName("#839 current run: a run whose scope fails part way as it opens still closes it, leaving no parent")
    void openingTheScopeThatFailsStillClosesIt() {
        AtomicReference<RunContext> parent = new AtomicReference<>();
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 0, new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                parent.set(run.parent());
            }
        });
        engine.load(RULES);
        // The first deadline set is the run's own, as it starts; the second opens the scope listeners hear it in.
        Faults.inject(Faults.Step.DEADLINE_SET, 2, new StackOverflowError("opening the run's scope"));

        assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>()));

        engine.run(new FactMap<>());
        assertNull(parent.get(), "the next run on the thread has no parent");
    }

    @Test
    @DisplayName("#839 current run: a run whose scope fails part way as it closes still makes the parent current again")
    void closingTheScopeThatFailsStillPutsBackTheParent() {
        AtomicReference<RunContext> parent = new AtomicReference<>();
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 0, new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                parent.set(run.parent());
            }
        });
        engine.load(RULES);
        // The first deadline put back is the scope's, as it closes; the run's own is put back after it.
        Faults.inject(Faults.Step.DEADLINE_PUT_BACK, 1, new StackOverflowError("closing the run's scope"));

        assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>()));

        engine.run(new FactMap<>());
        assertNull(parent.get(), "the next run on the thread has no parent");
        assertSame(Deadline.NONE, Cancellation.deadlineFrom(null), "the thread has no deadline");
    }

    @Test
    @DisplayName("#839 permit: a failed borrow gives back the permit a failing give-back left it holding")
    void aFailedBorrowGivesBackThePermitAGiveBackFailedToGiveBack() throws InterruptedException {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        language.failSessions = true;
        // The run takes the only permit, fails to make its copy, and the first give-back of the permit fails.
        Faults.inject(Faults.Step.GIVING_BACK, 1, new StackOverflowError("giving back the permit"));

        TestLogs.logsOf(() -> assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>())));

        language.failSessions = false;
        assertEquals(1, freePermits(engine), "the permit is back, once");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 permit and rule-list count: a run whose permit fails to go back as it gives back its copy"
            + " still leaves the rules, so close() closes them, and gives the permit back itself")
    void aRunWhosePermitFailsToGoBackStillLeavesTheRulesAndGivesItBack() throws InterruptedException {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        Faults.inject(Faults.Step.GIVING_BACK, 1, new StackOverflowError("giving back the permit"));

        assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>()));

        assertEquals(1, freePermits(engine), "the permit is back, once");
        assertEquals(0, engine.currentRules().waiters(), "no run is counted as waiting");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 permit: a give-back that fails once the permit is back never gives it back twice")
    void aGiveBackThatFailsOnceThePermitIsBackNeverGivesItBackTwice() throws InterruptedException {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        Faults.inject(Faults.Step.PERMIT_RELEASED, 1, new StackOverflowError("waking a waiting run"));

        assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>()));
        assertEquals(1, freePermits(engine), "a run that gives back its copy gives the permit back once");

        language.failSessions = true;
        Faults.inject(Faults.Step.PERMIT_RELEASED, 1, new StackOverflowError("waking a waiting run"));
        TestLogs.logsOf(() -> assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>())));
        language.failSessions = false;
        assertEquals(1, freePermits(engine), "a failed borrow gives the permit back once");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 build slot: a failed borrow gives back the build slot a failing give-back left it holding")
    void aFailedBorrowGivesBackTheSlotAGiveBackFailedToGiveBack() throws InterruptedException {
        Counting language = new Counting();
        language.failSessions = true;
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED, 1);
        RuleSet rules = new RuleSet(List.of(), Map.of("counting", language.compiler()), CopyLimit.none(), permits,
                TimeUnit.MINUTES.toMillis(5));
        // A virtual thread without a limit takes the only build slot to make its copy, fails to make it, and the
        // first give-back of the slot fails.
        Throwable failure = failVirtualBorrow(rules, Faults.Step.GIVING_BACK);

        assertInstanceOf(StackOverflowError.class, failure, "the borrow failed as the give-back did");
        assertOneSlot(permits);
    }

    @Test
    @DisplayName("#839 build slot: a give-back that fails once the slot is back never gives it back twice")
    void aGiveBackThatFailsOnceTheSlotIsBackNeverGivesItBackTwice() throws InterruptedException {
        Counting language = new Counting();
        language.failSessions = true;
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED, 1);
        RuleSet rules = new RuleSet(List.of(), Map.of("counting", language.compiler()), CopyLimit.none(), permits,
                TimeUnit.MINUTES.toMillis(5));

        Throwable failure = failVirtualBorrow(rules, Faults.Step.SLOT_RELEASED);

        assertInstanceOf(StackOverflowError.class, failure, "the borrow failed as the give-back did");
        assertOneSlot(permits);
    }

    // Borrows on a virtual thread, which takes a build slot, with the step failing on that thread; returns what the
    // borrow threw.
    private static Throwable failVirtualBorrow(RuleSet rules, Faults.Step step) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread run = Thread.ofVirtual().unstarted(() -> TestLogs.logsOf(() -> {
            try {
                rules.borrow(Deadline.NONE);
            } catch (Throwable t) {
                failure.set(t);
            }
        }));
        Faults.inject(run, step, 1, new StackOverflowError("giving back the slot"));
        run.start();
        run.join(TimeUnit.SECONDS.toMillis(10));
        return failure.get();
    }

    private static void assertOneSlot(CopyPermits permits) throws InterruptedException {
        assertTrue(permits.awaitSlot(0, Deadline.NONE), "the slot is back");
        assertFalse(permits.awaitSlot(0, Deadline.NONE), "once");
        permits.giveBackSlot();
    }

    @Test
    @DisplayName("#839 sessions and permit: lending a copy that fails before it's lent closes its sessions and gives"
            + " back the permit")
    void lendingThatFailsClosesTheSessionsAndGivesBackThePermit() throws InterruptedException {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        Faults.inject(Faults.Step.COPY_LENT, 1, new StackOverflowError("lending the copy"));

        assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>()));

        assertEquals(1, language.sessions.get(), "the run made its copy's session");
        assertEquals(1, language.sessionsClosed.get(), "and closed it");
        assertEquals(1, freePermits(engine), "the permit is back, once");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 retire: a retire() that fails before it marks the rule set retired retires it when called again")
    void aRetireThatFailsBeforeMarkingCanBeCalledAgain() {
        Counting language = new Counting();
        RuleSet rules = new RuleSet(List.of(), Map.of("counting", language.compiler()), CopyLimit.of(1),
                TimeUnit.MINUTES.toMillis(5));
        rules.prepareCopies(1);
        StackOverflowError failure = new StackOverflowError("marking the rule set retired");
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, failure);

        assertSame(failure, assertThrows(StackOverflowError.class, rules::retire));
        assertFalse(rules.retiredForGood(), "the rule set isn't retired");
        assertEquals(1, language.sessionsClosed.get(), "the idle copy it took is closed");
        assertEquals(0, language.compilersClosed.get(), "the compilers aren't");

        assertNull(rules.retire(), "no fatal error");
        assertTrue(rules.retiredForGood(), "the rule set is retired");
        assertEquals(1, language.compilersClosed.get(), "and its compilers closed");
        assertNull(rules.retire(), "calling it once more does nothing");
        assertEquals(1, language.compilersClosed.get(), "the compilers are closed once");
    }

    @Test
    @DisplayName("#839 close: a close() whose retiring fails leaves the engine closed, and closing it again closes the"
            + " rules")
    void aCloseWhoseRetiringFailsCanBeCalledAgain() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        engine.run(new FactMap<>());
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the rules retired"));

        assertThrows(StackOverflowError.class, engine::close);

        assertThrows(IllegalStateException.class, () -> engine.run(new FactMap<>()), "the engine is closed");
        assertEquals(0, language.compilersClosed.get(), "the compilers aren't closed yet");
        engine.close();
        language.assertAllClosed("after close() again");
    }

    @Test
    @DisplayName("#839 load: a reload whose retiring of the rules it replaced fails succeeds, and the next load()"
            + " retires them")
    void aReloadWhoseRetiringFailsSucceeds() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        List<Rule> reloaded = List.of(Rule.builder().ruleName("r2").priority(1).condition("c").action("a").build());
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the replaced rules retired"));

        String logs = TestLogs.logsOf(() -> assertDoesNotThrow(() -> engine.load(reloaded)));

        assertTrue(logs.contains("Rules this engine no longer uses couldn't all be retired, so the next load() or"
                + " close() tries again: "), logs);
        assertEquals("r2", engine.rules().rules().get(0).getRuleName(), "the new rules are loaded");
        engine.run(new FactMap<>());
        assertEquals(0, language.compilersClosed.get(), "the replaced rules aren't closed yet");
        engine.load(RULES);
        assertEquals(2, language.compilersClosed.get(), "the next load() retires both rule lists it replaced");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 load: a reload whose retiring of the rules it replaced throws a fatal error throws it once the"
            + " new rules are in, and close() retires them")
    void aReloadWhoseRetiringThrowsAFatalErrorThrowsIt() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        List<Rule> reloaded = List.of(Rule.builder().ruleName("r2").priority(1).condition("c").action("a").build());
        OutOfMemoryError failure = new OutOfMemoryError("marking the replaced rules retired");
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, failure);

        assertSame(failure, assertThrows(OutOfMemoryError.class, () -> engine.load(reloaded)));

        assertEquals("r2", engine.rules().rules().get(0).getRuleName(), "the new rules are loaded");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 load: a load whose call to make its copies fails still closes the rules' compilers")
    void aLoadWhoseCallToMakeCopiesFailsClosesTheCompilers() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        Faults.inject(Faults.Step.COPIES_PREPARED, 1, new StackOverflowError("making the copies"));

        assertThrows(StackOverflowError.class, () -> engine.load(RULES));

        language.assertAllClosed("after the load failed");
    }

    @Test
    @DisplayName("#839 load: a rule list that fails to load and whose retiring fails is retired by close()")
    void aRuleListThatFailedToLoadAndToRetireIsRetiredByClose() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        language.failSessions = true;
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the failed rules retired"));

        String logs = TestLogs.logsOf(() -> assertThrows(StackOverflowError.class, () -> engine.load(RULES)));

        assertEquals(0, retireAgainLines(logs), "what load() throws says it: " + logs);
        assertEquals(0, language.compilersClosed.get(), "the compilers aren't closed yet");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#893 load: a load whose making of copies throws a fatal error, and whose retiring then fails, throws"
            + " its own fatal error, carrying the retire failure")
    void aLoadsOwnFatalErrorComesBeforeARetireFailure() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        OutOfMemoryError failure = new OutOfMemoryError("making a session");
        language.sessionFailure = failure;
        StackOverflowError retiring = new StackOverflowError("marking the failed rules retired");
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, retiring);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = TestLogs.logsOf(() -> thrown.set(assertThrows(Throwable.class, () -> engine.load(RULES))));

        assertSame(failure, thrown.get(), "the load's own fatal error isn't what load() threw");
        assertArrayEquals(new Throwable[] {retiring}, failure.getSuppressed(), "the retire failure isn't kept");
        assertEquals(1, retireAgainLines(logs), logs);
        assertEquals(0, language.compilersClosed.get(), "the compilers aren't closed yet");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#893 load: a load whose making of copies throws a fatal error, and whose retiring throws one too,"
            + " throws its own, carrying the retire failure, which is logged once")
    void aLoadsOwnFatalErrorCarriesAFatalRetireFailure() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        OutOfMemoryError failure = new OutOfMemoryError("making a session");
        language.sessionFailure = failure;
        OutOfMemoryError retiring = new OutOfMemoryError("marking the failed rules retired");
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, retiring);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = TestLogs.logsOf(() -> thrown.set(assertThrows(Throwable.class, () -> engine.load(RULES))));

        assertSame(failure, thrown.get(), "the load's own fatal error isn't what load() threw");
        assertArrayEquals(new Throwable[] {retiring}, failure.getSuppressed(), "the retire failure isn't kept");
        assertEquals(1, retireAgainLines(logs), logs);
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#893 load: a fatal error closing the failed load's compilers, carried by the load's own, is logged"
            + " once, as it was closed, and the rules aren't said to be left to retire")
    void aFatalErrorClosingTheFailedRulesIsLoggedOnce() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        OutOfMemoryError failure = new OutOfMemoryError("making a session");
        language.sessionFailure = failure;
        OutOfMemoryError closing = new OutOfMemoryError("closing the compiler");
        language.closeFailure = closing;

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = TestLogs.logsOf(() -> thrown.set(assertThrows(Throwable.class, () -> engine.load(RULES))));

        assertSame(failure, thrown.get(), "the load's own fatal error isn't what load() threw");
        assertArrayEquals(new Throwable[] {closing}, failure.getSuppressed(), "the close failure isn't kept");
        assertEquals(0, retireAgainLines(logs), logs);
        assertEquals(1, logs.lines().filter(line -> line.contains("WARN ") && line.contains("failed to close")).count(),
                logs);
        language.assertAllClosed("after the load failed");
    }

    @Test
    @DisplayName("#893 load: a retire failure the load's own error can't carry is logged once, as not carried")
    void aRetireFailureTheLoadsErrorCantCarryIsLoggedOnce() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        // Suppression disabled, as on an OutOfMemoryError the JVM keeps ready, with a fatal error in its chain.
        Error failure = new Error("making the copies", new OutOfMemoryError("a session"), false, false) {
        };
        Faults.inject(Faults.Step.COPIES_PREPARED, 1, failure);
        language.closeFailure = new OutOfMemoryError("closing the compiler");

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = TestLogs.logsOf(() -> thrown.set(assertThrows(Throwable.class, () -> engine.load(RULES))));

        assertSame(failure, thrown.get(), "the load's own failure isn't what load() threw");
        assertEquals(0, retireAgainLines(logs), logs);
        assertEquals(1, logs.lines().filter(line -> line.contains("WARN ") && line.contains("can't carry")).count(),
                logs);
        language.closeFailure = null;
        engine.close();
        language.assertAllClosed("after close()");
    }

    // How many times the engine logged that rules couldn't all be retired, so the next load() or close() tries again.
    private static int retireAgainLines(String logs) {
        return (int) logs.lines().filter(line -> line.contains("WARN ")
                && line.contains("couldn't all be retired, so the next load() or close() tries again")).count();
    }

    @Test
    @DisplayName("#893 load: a load whose making of copies throws a fatal error, and whose retiring fails before it"
            + " begins, throws its own fatal error, carrying the retire failure")
    void aLoadsOwnFatalErrorSurvivesRetiringThatFailsBeforeItBegins() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        OutOfMemoryError failure = new OutOfMemoryError("making a session");
        language.sessionFailure = failure;
        StackOverflowError retiring = new StackOverflowError("retiring the claimed rules");
        Faults.inject(Faults.Step.CLAIMED_RETIRING, 1, retiring);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = TestLogs.logsOf(() -> thrown.set(assertThrows(Throwable.class, () -> engine.load(RULES))));

        assertSame(failure, thrown.get(), "the load's own fatal error isn't what load() threw");
        assertArrayEquals(new Throwable[] {retiring}, failure.getSuppressed(), "the retire failure isn't kept");
        assertEquals(1, retireAgainLines(logs), logs);
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#893 load: a load whose own failure isn't fatal, and whose retiring fails before it begins, throws"
            + " the retire failure, carrying the load's failure")
    void aLoadsFailureIsKeptWhenRetiringFailsBeforeItBegins() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        language.failSessions = true;
        StackOverflowError retiring = new StackOverflowError("retiring the claimed rules");
        Faults.inject(Faults.Step.CLAIMED_RETIRING, 1, retiring);

        String logs = TestLogs.logsOf(
                () -> assertSame(retiring, assertThrows(StackOverflowError.class, () -> engine.load(RULES))));

        assertEquals(1, retiring.getSuppressed().length, "the load's own failure isn't kept");
        assertInstanceOf(RuleCompilationException.class, retiring.getSuppressed()[0]);
        assertEquals(0, retireAgainLines(logs), "what load() throws says it: " + logs);
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#893 load: a load whose own failure isn't fatal, and whose retiring throws a fatal error, throws that"
            + " error, carrying the load's failure")
    void aRetireFatalErrorComesBeforeALoadsOwnFailure() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language, 1, new RuleListener() {
        });
        language.failSessions = true;
        OutOfMemoryError retiring = new OutOfMemoryError("marking the failed rules retired");
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, retiring);

        TestLogs.logsOf(() -> assertSame(retiring, assertThrows(OutOfMemoryError.class, () -> engine.load(RULES))));

        assertEquals(1, retiring.getSuppressed().length, "the load's own failure isn't kept");
        assertInstanceOf(RuleCompilationException.class, retiring.getSuppressed()[0]);
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 retire: a retire() that failed part way keeps others out until it has closed the copies it"
            + " took, so the compilers close after them")
    void aRetireThatFailedPartWayKeepsOthersOutWhileItClosesItsCopies() {
        AtomicInteger compilersClosed = new AtomicInteger();
        AtomicReference<RuleSet> rules = new AtomicReference<>();
        AtomicInteger closedMeanwhile = new AtomicInteger(-1);
        ExpressionCompiler compiler = new ExpressionCompiler() {
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
                return new Session() {
                    @Override
                    public void close() {
                        // A retire() started while the copy is being closed, as one on another thread could be.
                        assertNull(rules.get().retire(), "no fatal error");
                        closedMeanwhile.set(compilersClosed.get());
                    }
                };
            }

            @Override
            public void close() {
                compilersClosed.incrementAndGet();
            }
        };
        rules.set(new RuleSet(List.of(), Map.of("a", compiler), CopyLimit.of(1), TimeUnit.MINUTES.toMillis(5)));
        rules.get().prepareCopies(1);
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the rule set retired"));

        assertThrows(StackOverflowError.class, rules.get()::retire);

        assertEquals(0, closedMeanwhile.get(), "a retire() started while the copy was closed closed no compiler");
        assertNull(rules.get().retire(), "no fatal error");
        assertEquals(1, compilersClosed.get(), "the next retire() closes the compilers");
    }

    @Test
    @DisplayName("#839 retire: a retire() that fails once it has marked the rule set retired is finished by the next")
    void aRetireThatFailsAfterMarkingIsFinishedByTheNext() {
        Counting language = new Counting();
        RuleSet rules = new RuleSet(List.of(), Map.of("counting", language.compiler()), CopyLimit.of(1),
                TimeUnit.MINUTES.toMillis(5));
        rules.prepareCopies(1);
        Faults.inject(Faults.Step.RETIRED_COPIES_CLOSED, 1, new StackOverflowError("counting the copies closed"));

        assertThrows(StackOverflowError.class, rules::retire);
        assertFalse(rules.retiredForGood(), "the rule set isn't retired for good");
        assertEquals(1, language.sessionsClosed.get(), "the idle copy it took is closed");
        assertEquals(0, language.compilersClosed.get(), "the compilers aren't");

        assertNull(rules.retire(), "no fatal error");
        assertTrue(rules.retiredForGood(), "the rule set is retired for good");
        assertEquals(1, language.compilersClosed.get(), "and its compilers closed");
        assertEquals(1, language.sessionsClosed.get(), "without closing the copy again");
        assertNull(rules.retire(), "calling it once more does nothing");
        assertEquals(1, language.compilersClosed.get(), "the compilers are closed once");
    }

    @Test
    @DisplayName("#839 close: a close() whose retiring fails once the rules are marked retired is finished by the next")
    void aCloseWhoseRetiringFailsAfterMarkingIsFinishedByTheNext() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        engine.run(new FactMap<>());
        Faults.inject(Faults.Step.RETIRED_COPIES_CLOSED, 1, new StackOverflowError("counting the copies closed"));

        assertThrows(StackOverflowError.class, engine::close);

        assertEquals(0, language.compilersClosed.get(), "the compilers aren't closed yet");
        engine.close();
        language.assertAllClosed("after close() again");
    }

    @Test
    @DisplayName("#839 load: a fatal error closing the rules a reload replaced is thrown even when retiring rules an"
            + " earlier load() left then fails")
    void aFatalErrorIsThrownWhenALaterRetireFails() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        List<Rule> second = List.of(Rule.builder().ruleName("r2").priority(1).condition("c").action("a").build());
        List<Rule> third = List.of(Rule.builder().ruleName("r3").priority(1).condition("c").action("a").build());
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the first rules retired"));
        TestLogs.logsOf(() -> engine.load(second));
        OutOfMemoryError closing = new OutOfMemoryError("closing a compiler");
        language.closeFailure = closing;
        // The third load retires the rules it replaced first, whose compiler throws the fatal error, and then the
        // first rules, left by the second load, whose retiring fails again.
        Faults.inject(Faults.Step.RETIRE_MARKED, 2, new StackOverflowError("marking the first rules retired again"));

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class,
                () -> TestLogs.logsOf(() -> engine.load(third)));

        assertSame(closing, thrown, "the fatal error is thrown");
        assertInstanceOf(StackOverflowError.class, thrown.getSuppressed()[0], "carrying the later failure");
        assertEquals("r3", engine.rules().rules().get(0).getRuleName(), "the new rules are loaded");
        language.closeFailure = null;
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 faults: a fault set for one thread fails the step on that thread alone")
    void aFaultFailsTheStepOnItsOwnThreadAlone() throws InterruptedException {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                engine.run(new FactMap<>());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        StackOverflowError fault = new StackOverflowError("counting the run");
        Faults.inject(other, Faults.Step.RUN_COUNTED, 1, fault);

        assertDoesNotThrow(() -> engine.run(new FactMap<>()), "the step doesn't fail on this thread");
        other.start();
        other.join(TimeUnit.SECONDS.toMillis(10));

        assertSame(fault, failure.get(), "it fails on the thread it was set for");
        engine.close();
    }

    @Test
    @DisplayName("#839 rule-list count: a run that stops waiting for a copy and fails to set its interrupt status"
            + " again still leaves the rules, so close() closes them")
    void aStoppedRunThatFailsToKeepItsInterruptStillLeavesTheRules() throws Exception {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            // The engine's only permit, held on a thread of its own, so the run waits for it until its deadline.
            RuleSet.Copy held = holder.submit(() -> engine.currentRules().borrow(Deadline.NONE)).get();
            Faults.inject(Faults.Step.INTERRUPT_KEPT, 1, new StackOverflowError("setting the interrupt status"));

            TestLogs.logsOf(() -> assertThrows(StackOverflowError.class,
                    () -> engine.runWithResult(new FactMap<>(), RunOptions.withTimeoutOf(Duration.ofMillis(1)))));

            holder.submit(() -> Failures.throwIfPresent(engine.currentRules().release(held))).get();
        } finally {
            holder.shutdown();
        }
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 sessions: a retire() that fails taking the idle copies closes every copy it had taken")
    void aRetireThatFailsTakingItsCopiesClosesThoseItTook() {
        Counting language = new Counting();
        RuleSet rules = new RuleSet(List.of(), Map.of("counting", language.compiler()), CopyLimit.of(3),
                TimeUnit.MINUTES.toMillis(5));
        rules.prepareCopies(3);
        // The second copy taken fails to be set aside, with the first set aside and the third still idle.
        Faults.inject(Faults.Step.COPY_TAKEN, 2, new StackOverflowError("setting a copy aside"));

        assertThrows(StackOverflowError.class, rules::retire);
        assertEquals(3, language.sessionsClosed.get(), "every copy is closed: those taken, and the one still idle");

        assertNull(rules.retire(), "no fatal error");
        assertTrue(rules.retiredForGood(), "the rule set is retired for good");
        assertEquals(1, language.compilersClosed.get(), "and its compilers closed");
        assertEquals(3, language.sessionsClosed.get(), "without closing a copy twice");
    }

    @Test
    @DisplayName("#839 sessions: a retire() that fails closing the copies it took leaves the compilers open until a"
            + " later call has closed them")
    void aRetireThatFailsClosingItsCopiesLeavesTheCompilersOpen() {
        Counting language = new Counting();
        RuleSet rules = new RuleSet(List.of(), Map.of("counting", language.compiler()), CopyLimit.of(1),
                TimeUnit.MINUTES.toMillis(5));
        rules.prepareCopies(1);
        // Closing the copies it took fails both times it's tried.
        Faults.inject(Thread.currentThread(), Faults.Step.COPIES_CLOSING, 1, 2,
                new StackOverflowError("closing the copies"));

        assertThrows(StackOverflowError.class, rules::retire);
        assertEquals(0, language.sessionsClosed.get(), "the copy isn't closed");
        assertEquals(0, language.compilersClosed.get(), "so neither are the compilers");

        assertNull(rules.retire(), "no fatal error");
        assertEquals(1, language.sessionsClosed.get(), "the next retire() closes the copy");
        assertEquals(1, language.compilersClosed.get(), "and then the compilers");
    }

    @Test
    @DisplayName("#839 close: a close() that fails before it retires the rules it detached leaves them for the next")
    void aCloseThatFailsBeforeRetiringLeavesTheRulesForTheNext() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        engine.run(new FactMap<>());
        Faults.inject(Faults.Step.CLAIMED_RETIRING, 1, new StackOverflowError("retiring the rules claimed"));

        assertThrows(StackOverflowError.class, engine::close);

        assertEquals(0, language.compilersClosed.get(), "the compilers aren't closed yet");
        engine.close();
        language.assertAllClosed("after close() again");
    }

    @Test
    @DisplayName("#839 load: a reload that fails before it retires the rules it replaced succeeds, and leaves them for"
            + " the next load() or close()")
    void aReloadThatFailsBeforeRetiringSucceeds() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        List<Rule> reloaded = List.of(Rule.builder().ruleName("r2").priority(1).condition("c").action("a").build());
        Faults.inject(Faults.Step.CLAIMED_RETIRING, 1, new StackOverflowError("retiring the rules claimed"));

        String logs = TestLogs.logsOf(() -> assertDoesNotThrow(() -> engine.load(reloaded)));

        assertTrue(logs.contains("Rules this engine no longer uses couldn't all be retired, so the next load() or"
                + " close() tries again: "), logs);
        assertEquals("r2", engine.rules().rules().get(0).getRuleName(), "the new rules are loaded");
        assertEquals(0, language.compilersClosed.get(), "the replaced rules aren't closed yet");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 load: a load() started while another retires the rules it replaced leaves those to it, and"
            + " retires its own")
    void aNestedLoadLeavesTheRulesAnotherCallClaimedToIt() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        engine.run(new FactMap<>());
        List<Rule> second = List.of(Rule.builder().ruleName("r2").priority(1).condition("c").action("a").build());
        List<Rule> third = List.of(Rule.builder().ruleName("r3").priority(1).condition("c").action("a").build());
        // The first rules' idle copy is closed as the second load retires them, and closing it loads the third
        // rules, which replaces the second, while the second load still holds its claim on the first.
        AtomicBoolean nested = new AtomicBoolean();
        language.onSessionClose = () -> {
            if (nested.compareAndSet(false, true)) {
                engine.load(third);
            }
        };

        engine.load(second);

        assertTrue(nested.get(), "a load ran while the first rules were retired");
        assertEquals("r3", engine.rules().rules().get(0).getRuleName(), "the third rules are loaded");
        assertEquals(2, language.compilersClosed.get(), "both rule lists replaced are closed, each by its own load");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 load: rules whose retiring failed before are retired by a later reload even when retiring the"
            + " rules that reload replaced fails")
    void earlierRulesAreRetiredWhenTheReplacedRulesFailAgain() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        List<Rule> second = List.of(Rule.builder().ruleName("r2").priority(1).condition("c").action("a").build());
        List<Rule> third = List.of(Rule.builder().ruleName("r3").priority(1).condition("c").action("a").build());
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the first rules retired"));
        TestLogs.logsOf(() -> engine.load(second));
        // The third load's own rules to retire, the second, fail this time, and the first, behind them, don't.
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the second rules retired"));

        TestLogs.logsOf(() -> engine.load(third));

        assertEquals(1, language.compilersClosed.get(), "the first rules are closed");
        engine.close();
        language.assertAllClosed("after close()");
    }

    @Test
    @DisplayName("#839 close: a close() that fails part way through taking the rules it retired out of the engine's"
            + " list leaves the rest of the list for the next")
    void aCloseThatFailsSettlingLeavesTheRestForTheNext() {
        Counting language = new Counting();
        AbstractRulesEngine<Object> engine = engine(language);
        List<Rule> second = List.of(Rule.builder().ruleName("r2").priority(1).condition("c").action("a").build());
        Faults.inject(Faults.Step.RETIRE_MARKED, 1, new StackOverflowError("marking the first rules retired"));
        TestLogs.logsOf(() -> engine.load(second));
        // close() retires the second rules, and then fails at the first, behind them in the list, as it takes the
        // second out of it: the first stays in the list.
        Faults.inject(Faults.Step.SETTLING, 2, new StackOverflowError("taking the rules out of the list"));

        assertThrows(StackOverflowError.class, engine::close);

        engine.close();
        language.assertAllClosed("after close() again");
    }
}
