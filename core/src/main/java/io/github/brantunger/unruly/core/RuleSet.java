package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * One loaded rule list: the rules as {@code load()} compiled them, the compilers of the languages they use, and
 * the copies of the rules that runs use. Keeping the compilers here lets a reload swap in the rules and their fact-name
 * checks with one write.
 *
 * <p>
 * Every run shares the compiled rules. What a language changes while its expressions run lives in a {@link Session},
 * and a copy of the rules is one session for each language the rules use. MVEL, for example, caches an accessor in a
 * compiled expression the first time it runs, and replaces it without synchronization when a later run binds the same
 * name to a different kind of object, so its session compiles its own expressions. A copy is therefore only used by
 * one run at a time: a run borrows an idle copy, or makes a new one when every copy is in use, and gives it back when
 * it finishes. Without a limit, the number of copies grows to the largest number of runs that have used the rule list
 * at once. {@link #prepareCopies(int)} makes idle copies before the rule set is published, so the first runs find
 * them ready.
 * </p>
 *
 * <p>
 * A run on a virtual thread that no limit covers, because the engine was built with {@code unlimitedCopies()}, makes
 * its new copy only once it holds one of the engine's build slots, and holds the slot until that first run ends (see
 * {@link CopyPermits}). The slots pace how many new copies are in their first run at once, without bounding how many
 * copies exist, and a run that waits too long for one makes and runs its copy without it. A run on a platform thread
 * takes no slot: its thread pool's size bounds the copies its runs make.
 * </p>
 *
 * <p>
 * With a limit, at most that many copies are kept, and a run that finds all of them in use waits for one. Two
 * kinds of run don't wait, because the copy they would wait for may be one that has to finish first:
 * </p>
 *
 * <ul>
 *     <li>a run nested in another run <b>on the same thread</b>, started from an action or a listener, whatever
 *     engine or rule list the run around it uses;</li>
 *     <li>a run that has waited five seconds without one single copy being given back, which is what a run
 *     waiting for another thread's run of this engine looks like. An overloaded engine keeps giving copies
 *     back, so it keeps waiting and the limit holds.</li>
 * </ul>
 *
 * <p>
 * Either gets an extra copy, whose sessions are closed when it's given back, so the limit is a limit on runs that
 * can make progress rather than a hard ceiling. The engine's rule sets share one set of {@link CopyPermits}, so while a
 * reload replaces one rule set with another, runs still using the old one count against the same limit as runs on
 * the new one.
 * </p>
 *
 * <p>
 * A rule list whose languages all return {@link Session#none()} keeps nothing between runs, so there is nothing to
 * copy: every run shares one set of sessions, waits for nothing and counts against no limit.
 * </p>
 *
 * <p>
 * Once {@link #retire()} is called, because a reload replaced the rule list or the engine was closed, idle copies are
 * closed at once, and a copy given back is closed instead of kept. When no run holds a copy any more, the rule set
 * closes the compilers, and then lends no more copies. A fatal {@link Error} from closing one copy doesn't stop the
 * others being closed, nor the compilers after them: the first is returned for the caller to throw once they have.
 * </p>
 */
final class RuleSet {

    /** The limit of a rule set that makes as many copies as its runs need. */
    static final int UNLIMITED = 0;

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);
    // The number of users once the rule set is closed.
    private static final int CLOSED = -1;
    // How long a run waits without one copy being given back before it decides they aren't coming back. Long
    // enough that only a rule slower than this, or a run waiting for another thread's run, reaches it.
    private static final long STALL_WINDOW_MILLIS = 5000;
    // Runs in progress on this thread, whatever engine or rule list they use, each counted from when it starts to get
    // its copy, so a nested run never waits for a copy its own thread may be holding. Removed when the outermost run
    // ends, so a pooled thread keeps nothing.
    // A plain ThreadLocal, not withInitial(): a lambda in a static initializer has to be bootstrapped while the
    // class is being initialized, which deadlocks when several threads load this class at once.
    private static final ThreadLocal<int[]> RUNS_ON_THREAD = new ThreadLocal<>();

    private final List<CompiledRule> compiledRules;
    private final Map<String, ExpressionCompiler> compilers;
    // Identify these rules, and when they were loaded, for RulesEngine.rules() and every run's result.
    private final String ruleChecksum;
    private final Instant loadTime;
    private final Queue<Map<String, Session>> idle;
    private final CopyLimit copyLimit;
    // With a limit, one permit for each kept copy that a limited run holds, shared with the engine's other rule sets.
    private final CopyPermits permits;
    private final long stallWindow;
    // Whether the "more copies than the limit" warning has been logged for this rule list.
    private final AtomicBoolean warnedAboutOverflow = new AtomicBoolean();
    // The sessions every run shares, once a copy has shown that no language keeps state between runs.
    private volatile Map<String, Session> sharedSessions;
    // How many copies runs hold, or CLOSED.
    private final AtomicInteger users = new AtomicInteger();
    private volatile boolean retired;

    /** What {@link #release(Copy)} does with a copy when the run that borrowed it gives it back. */
    enum Kind {
        /** Kept for a later run, unless the rule set has been retired or the idle queue can't take it. */
        KEPT,
        /** An extra copy made above the limit: its sessions are closed. */
        EXTRA,
        /** The sessions every run shares, because no language keeps state between runs: nothing to do. */
        SHARED
    }

    /** What a run gives back to the engine's {@link CopyPermits} with its copy. */
    enum Held {
        /** Nothing. */
        NOTHING,
        /** A permit, which a limited run holds for as long as it uses a kept copy. */
        PERMIT,
        /** A build slot, which a run holds while it runs a new copy for the first time. */
        SLOT
    }

    /**
     * A copy of the rules lent to one run.
     *
     * @param sessions The sessions of the languages the rules use, by language name
     * @param kind     What happens to it when it's given back
     * @param held     What the run holds with it, given back with it
     */
    record Copy(Map<String, Session> sessions, Kind kind, Held held) {

        /**
         * Returns whether the copy is kept for a later run.
         *
         * @return {@code true} if it goes back into the idle queue
         */
        boolean kept() {
            return kind == Kind.KEPT;
        }
    }

    /**
     * Creates a rule set with no copies yet, whose limited runs take the permits of the engine that loaded it.
     *
     * @param compiledRules The compiled rules, in the order they run
     * @param compilers     The compilers of the languages the rules use, by language name, in the order they check
     *                      fact names
     * @param limit         How many copies runs may hold at once, and which runs that applies to
     * @param permits       The engine's permits for {@code limit}, which its other rule sets share
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers, CopyLimit limit,
            CopyPermits permits) {
        this(compiledRules, compilers, limit, permits, STALL_WINDOW_MILLIS);
    }

    /**
     * Creates a rule set whose runs give up waiting for a copy after {@code stallWindowMillis}, for tests that would
     * otherwise wait the whole stall window. It is a deliberate test seam: the engine never passes a stall window of
     * its own, so keep it even if no test uses it today.
     *
     * @param compiledRules     The compiled rules, in the order they run
     * @param compilers         The compilers of the languages the rules use, by language name
     * @param limit             How many copies runs may hold at once, and which runs that applies to
     * @param stallWindowMillis How long a run waits without one copy being given back before it makes an extra one
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers, CopyLimit limit,
            long stallWindowMillis) {
        this(compiledRules, compilers, limit, new CopyPermits(limit.maxCopies()), stallWindowMillis);
    }

    /**
     * Creates a rule set whose limited runs take the given permits and give up waiting after
     * {@code stallWindowMillis}. It is a deliberate test seam too: it lets a test share permits between rule sets
     * <em>and</em> shorten the stall window, which nothing but a test needs.
     *
     * @param compiledRules     The compiled rules, in the order they run
     * @param compilers         The compilers of the languages the rules use, by language name
     * @param limit             How many copies runs may hold at once, and which runs that applies to
     * @param permits           The permits for {@code limit}, which other rule sets may share
     * @param stallWindowMillis How long a run waits without one copy being given back before it makes an extra one
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers, CopyLimit limit,
            CopyPermits permits, long stallWindowMillis) {
        this(compiledRules, compilers, limit, permits, stallWindowMillis, new ConcurrentLinkedQueue<>());
    }

    /**
     * Creates a rule set whose idle copies wait in {@code idle}. It is a deliberate test seam too: it lets a test hand
     * the rule set a queue that fails, as one that can't allocate room for a copy does, which nothing but a test needs.
     *
     * @param compiledRules     The compiled rules, in the order they run
     * @param compilers         The compilers of the languages the rules use, by language name
     * @param limit             How many copies runs may hold at once, and which runs that applies to
     * @param permits           The permits for {@code limit}, which other rule sets may share
     * @param stallWindowMillis How long a run waits without one copy being given back before it makes an extra one
     * @param idle              The queue the idle copies wait in, which runs share, so it must be thread-safe
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers, CopyLimit limit,
            CopyPermits permits, long stallWindowMillis, Queue<Map<String, Session>> idle) {
        this.compiledRules = List.copyOf(compiledRules);
        this.compilers = Collections.unmodifiableMap(new LinkedHashMap<>(compilers));
        this.ruleChecksum = Checksums.ofRules(this.compiledRules);
        this.loadTime = Instant.now();
        this.copyLimit = limit;
        this.permits = permits;
        this.stallWindow = stallWindowMillis;
        this.idle = idle;
    }

    /**
     * Returns the rules as {@code load()} compiled them, which every run shares.
     *
     * @return An unmodifiable list of the compiled rules
     */
    List<CompiledRule> rules() {
        return compiledRules;
    }

    /**
     * Returns the checksum that identifies these rules, as
     * {@link io.github.brantunger.unruly.api.RuleSetInfo#checksum()} describes it.
     *
     * @return The checksum
     */
    String checksum() {
        return ruleChecksum;
    }

    /**
     * Returns when these rules were compiled.
     *
     * @return The time the rule set was created
     */
    Instant loadedAt() {
        return loadTime;
    }

    /**
     * Returns the compilers that check the fact names of a run of these rules, and create its sessions.
     *
     * @return An unmodifiable map of the compilers by language name, in the order they check
     */
    Map<String, ExpressionCompiler> factChecks() {
        return compilers;
    }

    /**
     * Returns the most copies the runs the limit applies to hold at once.
     *
     * @return The limit, or {@link #UNLIMITED}
     */
    int limit() {
        return copyLimit.maxCopies();
    }

    /**
     * Takes a copy of the rules that no other run is using, making a new one if necessary. With a limit, waits for a
     * copy when all of them are in use, unless the current thread already holds one: then an extra copy that isn't
     * kept is made instead. So is a run started on the same thread while another run is getting its copy, as a
     * language creating a session may start one: it counts as nested in that run. A copy that fails to be made isn't
     * kept, and doesn't count toward the limit. Without a limit, a run on a virtual thread that has to make a copy
     * waits for a build slot first: with a deadline, for at most half the time it has left; without one, for as long
     * as slots keep coming back.
     *
     * @return A copy for the caller alone, to give back with {@link #release(Copy)}, or {@code null} if the rule set is
     *         closed: it was retired, and every copy was given back
     * @param deadline When the run must stop, or {@code null} if it has none. Waiting for a copy that is in use stops
     *                 there; waiting for a build slot gives up at half the time left, and the run makes its copy.
     * @throws InterruptedException   if the thread is interrupted while it waits for a copy that is in use, or for a
     *                                build slot; its interrupt status is set again before this is thrown. A thread
     *                                whose interrupt status is already set still gets a free copy, or makes one; the
     *                                run then stops at its first rule. A thread that throws holds no copy, and its
     *                                run no longer counts as in progress on it, but the run is still counted on the
     *                                rule set, so the caller can report the stop before a retired rule set closes:
     *                                the caller then calls {@link #leaveAfterStop()}.
     * @throws TimeoutException       if the deadline passes while the thread waits for a copy that is in use, which
     *                                likewise leaves it holding none, and counted on the rule set until it leaves
     * @throws RuleExecutionException if a language fails to create a session for a new copy, which is logged at ERROR.
     *                                A fatal {@link Error} is then rethrown unchanged.
     * @throws Error                  a fatal {@link Error} from closing a retired rule set that this failed borrow was
     *                                the last to use, in place of a failure that isn't fatal, which it carries as
     *                                suppressed, or logs at WARN if the error can't carry one; never in place of an
     *                                {@link InterruptedException} or a {@link TimeoutException}, which leave later
     */
    // Any Throwable: a failed borrow must leave however it ends, as a finally would, and a failure that isn't fatal is
    // kept under a fatal Error from closing. A stopped wait is the exception: the run leaves once it's reported it.
    Copy borrow(Instant deadline) throws InterruptedException, TimeoutException {
        // Found or made before anything is held, so that failing to make it leaves nothing to give back.
        int[] runs = runsOnThread();
        // A run already in progress on this thread, whatever engine or rule list it uses, makes this one nested.
        boolean nested = runs[0] > 0;
        if (!enter()) {
            forgetIfIdle(runs[0]);
            return null;
        }
        // Counted before the copy is lent, by a step that can't fail, and uncounted if lending fails. So a run that a
        // language starts on this thread while this one gets its copy finds this one in progress, and when it ends it
        // leaves the thread's count in place rather than removing it from under this run.
        runs[0]++;
        try {
            return lend(deadline, nested);
        } catch (InterruptedException e) {
            // Uncounted first, as after any failed borrow, but not left: a fatal Error from closing the rules as the
            // run leaves would otherwise be thrown before the run could report that it stopped. Nothing here
            // allocates, so nothing can fail before the caller takes over, which leaves once it has reported the stop.
            endRunOnThread();
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            endRunOnThread();
            throw e;
        } catch (Throwable t) {
            Failures.throwIfPresent(failedBorrow(t));
            throw t;
        }
    }

    /**
     * Leaves the rule set after a borrow that stopped waiting, with an {@link InterruptedException} or a
     * {@link TimeoutException}, once the caller has reported the stop: closes a retired rule set that no run uses any
     * more. Called once for each such borrow.
     *
     * @return The first fatal {@link Error} closing the rule set threw, for the caller to throw in place of the stop,
     *         or {@code null} if none did
     */
    Error leaveAfterStop() {
        return leave();
    }

    // Uncounts a run whose borrow failed, and leaves the rule set, returning the fatal Error to throw in place of
    // failure, if closing the rule set as it leaves threw one (see Failures.fatalInsteadOf). Uncounted first, which
    // allocates nothing and can't fail, so the run never stays counted. The interrupt status is set again next, if an
    // interrupt caused the failure, so that closing the rules as the run leaves sees it.
    private Error failedBorrow(Throwable failure) {
        endRunOnThread();
        Failures.keepInterruptStatus(failure);
        return Failures.fatalInsteadOf(failure, leave());
    }

    /**
     * Makes {@code count} idle copies of the rules, each session prepared with
     * {@link ExpressionCompiler#warmUp(Session)}, for runs to borrow. Called by {@code load()} on its own thread,
     * before any run can see the rule set. If the first copy shows that no language keeps state between runs, it
     * becomes the sessions every run shares, and no more are made.
     *
     * @param count How many copies to make; zero makes none
     * @throws RuleExecutionException if a language throws or returns {@code null} from {@code newSession()}, or
     *                                throws from {@code warmUp()}. It's logged at ERROR, and the copies already made,
     *                                the one that failed too, even if only some of its sessions were made, stay idle
     *                                for {@link #retire()} to close. A fatal {@link Error} is rethrown unchanged.
     */
    void prepareCopies(int count) {
        for (int made = 0; made < count; made++) {
            Map<String, Session> sessions = new LinkedHashMap<>();
            // Idle before any of its sessions is made, and filled in place, so the sessions made before a language
            // failed, and a copy that fails to warm up, are closed with the other copies when load() retires the rule
            // set, and a fatal Error from closing them is weighed against the load's failure there. A queue that can't
            // take the copy fails before anything is made. No run can see the rule set yet.
            idle.add(sessions);
            addSessions(sessions);
            if (statelessSessions(sessions)) {
                // Left idle, which is harmless: no run takes an idle copy once the sessions are shared, and closing
                // Session.none() does nothing.
                sharedSessions = sessions;
                return;
            }
            warmUp(sessions);
        }
    }

    // Session.none() is one shared instance with nothing to prepare, and identity is the question, as in
    // statelessSessions().
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void warmUp(Map<String, Session> sessions) {
        for (Map.Entry<String, Session> session : sessions.entrySet()) {
            if (session.getValue() != Session.none()) {
                warmUp(session.getKey(), compilers.get(session.getKey()), session.getValue());
            }
        }
    }

    private static void warmUp(String language, ExpressionCompiler compiler, Session session) {
        try {
            compiler.warmUp(session);
        } catch (Throwable e) {
            Failures.keepInterruptStatus(e);
            String msg = "The '" + Failures.quote(language) + "' expression language failed to warm up a session: "
                    + Failures.describe(e);
            log.error(msg);
            Failures.throwIfPresent(Failures.fatalError(e));
            throw new ReportedFailure(msg, e);
        }
    }

    /**
     * Gives back a copy taken with {@link #borrow(Instant)}. A kept copy is kept for a later run, unless the rule set
     * is retired, or the idle queue can't take it; any other copy's sessions are closed. The last copy given back to a
     * retired rule set closes its compilers too.
     *
     * @param borrowed The copy, which the caller must no longer use
     * @return The first fatal {@link Error} keeping the copy, or closing its sessions or the compilers, threw, for the
     *         caller to throw, or {@code null} if none did
     */
    Error release(Copy borrowed) {
        Error fatal = null;
        try {
            if (borrowed.kind() == Kind.KEPT) {
                // Kept before the permit is released, so a run that was waiting finds this copy.
                fatal = keep(borrowed.sessions());
            } else if (borrowed.kind() == Kind.EXTRA) {
                fatal = Closing.sessions(borrowed.sessions());
            }
            // A shared copy needs nothing: its sessions belong to every run, and are Session.none(), which has
            // nothing to close.
        } finally {
            // Uncounted first, which allocates nothing and can't fail, so the run is uncounted however giving the copy
            // back ends, and a run that closing the rules starts on this thread isn't nested in it, as after a failed
            // borrow.
            endRunOnThread();
            giveBack(borrowed.held());
            fatal = Failures.first(fatal, leave());
        }
        return fatal;
    }

    // Keeps a copy given back for a later run, or closes its sessions if the rule set is retired, returning the first
    // fatal Error from closing them. A copy the idle queue can't take, as when it can't allocate room for it, is closed
    // too, rather than lost with its sessions open, and what the queue threw is logged at WARN, and returned first if
    // it's fatal.
    // Any Throwable: the sessions must be closed however keeping them fails, and the run must still leave.
    private Error keep(Map<String, Session> sessions) {
        if (retired) {
            return Closing.sessions(sessions);
        }
        try {
            idle.add(sessions);
        } catch (Throwable t) {
            Error closeFatal = Closing.sessions(sessions);
            Failures.keepInterruptStatus(t);
            log.warn("A copy of the rules couldn't be kept for a later run, so its sessions were closed: {}",
                    Failures.describe(t));
            return Failures.first(Failures.fatalError(t), closeFatal);
        }
        return null;
    }

    /** How many runs this thread has in progress, whatever engine or rule list they use. */
    private static int[] runsOnThread() {
        int[] runs = RUNS_ON_THREAD.get();
        if (runs == null) {
            runs = new int[1];
            RUNS_ON_THREAD.set(runs);
        }
        return runs;
    }

    // Uncounts a run that borrow() counted on this thread. Its count is there until then, so reading it allocates
    // nothing, and can't fail.
    private static void endRunOnThread() {
        int[] runs = RUNS_ON_THREAD.get();
        runs[0]--;
        forgetIfIdle(runs[0]);
    }

    // Removes this thread's count once no run is in progress on it: when the outermost run ends, and after a borrow
    // that got no copy, or found the rule set closed, on a thread that isn't running anything, so a pooled thread
    // keeps nothing.
    private static void forgetIfIdle(int runs) {
        if (runs == 0) {
            RUNS_ON_THREAD.remove();
        }
    }

    /**
     * Retires the rule set, which runs no longer start with: closes the idle copies, and the compilers too if no run
     * holds a copy. Otherwise, the compilers are closed when the last copy is given back. Calling it again does
     * nothing more. A fatal {@link Error} from closing one copy doesn't stop the others being closed, nor the
     * compilers after them.
     *
     * @return The first fatal {@link Error} closing threw, for the caller to throw, or {@code null} if none did
     */
    Error retire() {
        retired = true;
        Error fatal = null;
        try {
            fatal = closeIdle();
        } finally {
            // Also if closing the idle copies throws, so the compilers are still closed.
            fatal = Failures.first(fatal, closeIfUnused());
        }
        return fatal;
    }

    private Copy lend(Instant deadline, boolean nested) throws InterruptedException, TimeoutException {
        Map<String, Session> shared = sharedSessions;
        if (shared != null) {
            return new Copy(shared, Kind.SHARED, Held.NOTHING);
        }
        if (!copyLimit.appliesToCurrentThread()) {
            // A thread pool's size bounds the copies its runs make, and virtual threads have nothing but the build
            // slots. A nested run never waits for one: its own thread may hold the slot it would wait for.
            return copyLimit.limits() || !Thread.currentThread().isVirtual() || nested
                    ? keptCopy(Held.NOTHING) : slottedCopy(deadline);
        }
        // A nested run on this thread never waits: the copy it would wait for may be the one its own thread holds.
        if (nested ? !permits.available().tryAcquire()
                : !awaitPermit(permits.available(), permits::returned, stallWindow, deadline)) {
            // Right after a reload, the permits may all be held by runs on the rules it replaced, before any run of
            // these rules has learned whether they need copies at all. An extra copy can learn it too, and then
            // nothing overflowed.
            return copy(newSessions(), Kind.EXTRA, Held.NOTHING, !nested);
        }
        return keptCopy(Held.PERMIT);
    }

    /**
     * Takes a copy for a run on a virtual thread that no limit covers. An idle copy is taken at once. Otherwise the run
     * waits for a build slot, and holds it while it runs its new copy for the first time, which is when a language
     * such as MVEL compiles the expressions and loads classes. So at most one new copy for each slot is in its first
     * run at once, apart from those of runs that gave up waiting. A run that gets a slot looks for an idle copy again
     * first, so copies given back while it waited are used before a new one is made. A copy given back without a slot
     * doesn't wake a waiting run: runs arriving meanwhile take it.
     *
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return The copy
     * @throws InterruptedException if the thread is interrupted while it waits for a slot
     */
    private Copy slottedCopy(Instant deadline) throws InterruptedException {
        Map<String, Session> sessions = idle.poll();
        if (sessions == null) {
            // A run that gives up waiting still makes its copy: an engine without a limit never fails a run for want
            // of one.
            Held held = permits.awaitSlot(stallWindow, deadline) ? Held.SLOT : Held.NOTHING;
            sessions = idle.poll();
            if (sessions == null) {
                boolean made = false;
                try {
                    sessions = newSessions();
                    made = true;
                } finally {
                    if (!made) {
                        giveBack(held);
                    }
                }
                return copy(sessions, Kind.KEPT, held, false);
            }
            giveBack(held);
        }
        return copy(sessions, Kind.KEPT, Held.NOTHING, false);
    }

    /**
     * Waits for a permit while copies are still being given back. A run that waits a whole {@code window} without one
     * single permit coming back gives up: either every copy is held by a run that is itself waiting for this one, or
     * the rules are so slow that an extra copy costs less than waiting. A run with a deadline never waits past it.
     *
     * @param permits  The permits to wait for
     * @param returned How many permits have been given back so far
     * @param window   How long to wait for progress, in milliseconds
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return {@code true} if a permit was taken, {@code false} if nothing came back within one window
     * @throws InterruptedException if the thread is interrupted while it waits
     * @throws TimeoutException     if the deadline comes before a permit does
     */
    static boolean awaitPermit(Semaphore permits, LongSupplier returned, long window, Instant deadline)
            throws InterruptedException, TimeoutException {
        // tryAcquire() first: acquire() throws at once on a thread whose interrupt status is already set, even when
        // copies are free, and the run would fail saying every copy was in use when none was.
        if (permits.tryAcquire()) {
            return true;
        }
        Duration windowLength = Duration.ofMillis(window);
        long seen = returned.getAsLong();
        while (true) {
            Duration left = Cancellation.timeLeft(deadline);
            if (left != null && left.compareTo(windowLength) < 0) {
                // The deadline comes before the window ends, so this is the last wait: a whole window never passes,
                // and a run past its deadline makes no extra copy either.
                if (permits.tryAcquire(Math.max(0, left.toNanos()), TimeUnit.NANOSECONDS)) {
                    return true;
                }
                throw Cancellation.timedOut(deadline);
            }
            if (permits.tryAcquire(window, TimeUnit.MILLISECONDS)) {
                return true;
            }
            long now = returned.getAsLong();
            if (now == seen) {
                return false;
            }
            seen = now;
        }
    }

    /**
     * Takes an idle copy, or makes one, that the run keeps until it gives it back.
     *
     * @param held What the run took for this copy, given back if no copy can be made
     * @return The copy
     */
    private Copy keptCopy(Held held) {
        Map<String, Session> sessions;
        boolean taken = false;
        try {
            sessions = take();
            taken = true;
        } finally {
            if (!taken) {
                giveBack(held);
            }
        }
        return copy(sessions, Kind.KEPT, held, false);
    }

    /**
     * Lends the given sessions as a copy. The first copy decides whether the rules need copies at all: when no
     * language keeps state between runs, its sessions become the ones every run shares, and the run gives back at once
     * what it took for the copy. If lending fails, as when the copy can't be allocated, its sessions are closed and
     * what the run took for it is given back, so a failed borrow holds nothing; a fatal {@link Error} from closing
     * them is thrown in place of a failure that isn't fatal.
     *
     * @param sessions The copy's sessions, which only this run holds
     * @param kind     What happens to the copy when it's given back, unless its sessions are shared
     * @param held     What the run took for this copy
     * @param overflow Whether to warn, once, that the run made an extra copy after waiting for a kept one, unless its
     *                 sessions are shared
     * @return The copy
     */
    // Any Throwable: the sessions, and what the run took for them, must be given up however lending ends, as a finally
    // would, and a failure that isn't fatal is kept under a fatal Error from closing.
    private Copy copy(Map<String, Session> sessions, Kind kind, Held held, boolean overflow) {
        Copy copy;
        try {
            if (statelessSessions(sessions)) {
                copy = new Copy(sessions, Kind.SHARED, Held.NOTHING);
            } else {
                if (overflow) {
                    warnAboutOverflow();
                }
                copy = new Copy(sessions, kind, held);
            }
        } catch (Throwable t) {
            // Given back first, as it can't fail, so that closing the sessions can't lose it for good.
            giveBack(held);
            Failures.throwIfPresent(Failures.fatalInsteadOf(t, Closing.sessions(sessions)));
            throw t;
        }
        if (copy.kind() == Kind.SHARED) {
            // Nothing in the rules changes while they run, so one set of sessions serves every run at once. Given back
            // only once nothing can fail, so that a failure never gives back the same thing twice.
            sharedSessions = sessions;
            giveBack(held);
        }
        return copy;
    }

    /** Whether every language of these rules returned {@link Session#none()}, so a copy holds nothing of its own. */
    // Session.none() is one shared instance, and identity is the question: a session that merely equals it still
    // belongs to one run at a time.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean statelessSessions(Map<String, Session> sessions) {
        return sessions.values().stream().allMatch(session -> session == Session.none());
    }

    private void warnAboutOverflow() {
        if (warnedAboutOverflow.compareAndSet(false, true)) {
            log.warn("All {} compiled copies of the rules were in use for {} ms without one being given back, so this"
                            + " run made an extra copy instead of waiting for ever. A rule or listener that waits for"
                            + " a run of this engine on another thread causes that; so does a rule slower than the"
                            + " wait. If runs are meant to wait for each other, build the engine with a larger"
                            + " maxCopies(n), or with unlimitedCopies() if it runs on a thread pool.",
                    copyLimit.maxCopies(), stallWindow);
        }
    }

    // Counts a run that holds a copy, unless the rule set is closed.
    private boolean enter() {
        return users.getAndUpdate(count -> count == CLOSED ? CLOSED : count + 1) != CLOSED;
    }

    // Uncounts a run that gave back its copy, closing a retired rule set that no run uses any more. Returns the first
    // fatal Error from closing it.
    private Error leave() {
        if (users.decrementAndGet() == 0 && retired) {
            return closeIfUnused();
        }
        return null;
    }

    // Closes the idle copies and then the compilers, once, if no run holds a copy. Returns the first fatal Error from
    // closing them.
    private Error closeIfUnused() {
        if (!users.compareAndSet(0, CLOSED)) {
            return null;
        }
        Error fatal = null;
        try {
            fatal = closeIdle();
        } finally {
            fatal = Failures.first(fatal, Closing.compilers(compilers));
        }
        return fatal;
    }

    // Closes every idle copy, even after one throws a fatal Error, and returns the first such error.
    private Error closeIdle() {
        Error fatal = null;
        for (Map<String, Session> sessions = idle.poll(); sessions != null; sessions = idle.poll()) {
            fatal = Failures.first(fatal, Closing.sessions(sessions));
        }
        return fatal;
    }

    // Gives back what a run held with its copy, which tells a run that is waiting that they are still coming back.
    private void giveBack(Held held) {
        if (held == Held.PERMIT) {
            permits.giveBack();
        } else if (held == Held.SLOT) {
            permits.giveBackSlot();
        }
    }

    private Map<String, Session> take() {
        Map<String, Session> sessions = idle.poll();
        return sessions != null ? sessions : newSessions();
    }

    /**
     * Creates a session for each language the rules use. If a language fails, the sessions already created for the
     * copy are closed, and a fatal {@link Error} from closing them is thrown in place of a failure that isn't fatal.
     */
    // Any Throwable: the sessions already made must be closed however this ends, as a finally would, and a failure that
    // isn't fatal is kept under a fatal Error from closing.
    private Map<String, Session> newSessions() {
        Map<String, Session> sessions = new LinkedHashMap<>();
        try {
            addSessions(sessions);
            return sessions;
        } catch (Throwable t) {
            Failures.throwIfPresent(Failures.fatalInsteadOf(t, Closing.sessions(sessions)));
            throw t;
        }
    }

    /**
     * Adds a session for each language the rules use to {@code sessions}, stopping at the first language that fails.
     *
     * @param sessions The copy's sessions so far, by language name
     */
    private void addSessions(Map<String, Session> sessions) {
        for (Map.Entry<String, ExpressionCompiler> compiler : compilers.entrySet()) {
            sessions.put(compiler.getKey(), newSession(compiler.getKey(), compiler.getValue()));
        }
    }

    /**
     * Creates one language's session for a new copy. A failure fails the run that needed the copy, and is logged at
     * ERROR first. A fatal {@link Error}, thrown or found among the causes of what the language throws, is then
     * rethrown unchanged. No listener is told: no callback has been sent for the run yet.
     */
    private static Session newSession(String language, ExpressionCompiler compiler) {
        String failed = "The '" + Failures.quote(language) + "' expression language ";
        Session session;
        try {
            session = compiler.newSession();
        } catch (Throwable e) {
            Failures.keepInterruptStatus(e);
            String msg = failed + "failed to create a session: " + Failures.describe(e);
            log.error(msg);
            Failures.throwIfPresent(Failures.fatalError(e));
            throw new ReportedFailure(msg, e);
        }
        if (session == null) {
            String msg = failed + "returned no session";
            log.error(msg);
            throw new ReportedFailure(msg, null);
        }
        return session;
    }
}
