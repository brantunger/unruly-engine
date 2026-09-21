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
 * closes the compilers, and then lends no more copies.
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
    // Runs in progress on this thread, whatever engine or rule list they use, so a nested run never waits for a copy
    // its own thread may be holding. Removed when the outermost run ends, so a pooled thread keeps nothing.
    // A plain ThreadLocal, not withInitial(): a lambda in a static initializer has to be bootstrapped while the
    // class is being initialized, which deadlocks when several threads load this class at once.
    private static final ThreadLocal<int[]> RUNS_ON_THREAD = new ThreadLocal<>();

    private final List<CompiledRule> compiledRules;
    private final Map<String, ExpressionCompiler> compilers;
    // Identify these rules, and when they were loaded, for RulesEngine.rules() and every run's result.
    private final String ruleChecksum;
    private final Instant loadTime;
    private final Queue<Map<String, Session>> idle = new ConcurrentLinkedQueue<>();
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
        /** Kept for a later run, unless the rule set has been retired. */
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
        this.compiledRules = List.copyOf(compiledRules);
        this.compilers = Collections.unmodifiableMap(new LinkedHashMap<>(compilers));
        this.ruleChecksum = Checksums.ofRules(this.compiledRules);
        this.loadTime = Instant.now();
        this.copyLimit = limit;
        this.permits = permits;
        this.stallWindow = stallWindowMillis;
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
     * kept is made instead. A copy that fails to be made isn't kept, and doesn't count toward the limit. Without a
     * limit, a run on a virtual thread that has to make a copy waits for a build slot first: with a deadline, for at
     * most half the time it has left; without one, for as long as slots keep coming back.
     *
     * @return A copy for the caller alone, to give back with {@link #release(Copy)}, or {@code null} if the rule set is
     *         closed: it was retired, and every copy was given back
     * @param deadline When the run must stop, or {@code null} if it has none. Waiting for a copy that is in use stops
     *                 there; waiting for a build slot gives up at half the time left, and the run makes its copy.
     * @throws InterruptedException   if the thread is interrupted while it waits for a copy that is in use, or for a
     *                                build slot. A thread whose interrupt status is already set still gets a free copy,
     *                                or makes one; the run then stops at its first rule. A thread that throws holds no
     *                                copy.
     * @throws TimeoutException       if the deadline passes while the thread waits for a copy that is in use, which
     *                                likewise leaves it holding none
     * @throws RuleExecutionException if a language fails to create a session for a new copy, which is logged at ERROR.
     *                                A fatal {@link Error} is then rethrown unchanged.
     */
    Copy borrow(Instant deadline) throws InterruptedException, TimeoutException {
        if (!enter()) {
            return null;
        }
        boolean lent = false;
        try {
            Copy borrowed = lend(deadline);
            lent = true;
            // Counted only once the copy is the caller's, so a failed borrow leaves nothing behind.
            runsOnThread()[0]++;
            return borrowed;
        } finally {
            if (!lent) {
                leave();
            }
        }
    }

    /**
     * Makes {@code count} idle copies of the rules, each session prepared with
     * {@link ExpressionCompiler#warmUp(Session)}, for runs to borrow. Called by {@code load()} on its own thread,
     * before any run can see the rule set. If the first copy shows that no language keeps state between runs, it
     * becomes the sessions every run shares, and no more are made.
     *
     * @param count How many copies to make; zero makes none
     * @throws RuleExecutionException if a language throws or returns {@code null} from {@code newSession()}, or
     *                                throws from {@code warmUp()}. It's logged at ERROR, and the copies already made
     *                                stay idle for {@link #retire()} to close. A fatal {@link Error} is rethrown
     *                                unchanged.
     */
    void prepareCopies(int count) {
        for (int made = 0; made < count; made++) {
            Map<String, Session> sessions = newSessions();
            if (statelessSessions(sessions)) {
                sharedSessions = sessions;
                return;
            }
            boolean warmed = false;
            try {
                warmUp(sessions);
                warmed = true;
            } finally {
                if (!warmed) {
                    Closing.sessions(sessions);
                }
            }
            idle.add(sessions);
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
        } catch (Exception | Error e) {
            Failures.keepInterruptStatus(e);
            String msg = "The '" + Failures.quote(language) + "' expression language failed to warm up a session: "
                    + Failures.describe(e);
            log.error(msg);
            Failures.throwIfPresent(Failures.fatalError(e));
            throw new ReportedFailure(msg, e);
        }
    }

    /**
     * Gives back a copy taken with {@link #borrow(Instant)}. A kept copy is kept for a later run, unless the rule set is
     * retired; any other copy's sessions are closed.
     *
     * @param borrowed The copy, which the caller must no longer use
     */
    void release(Copy borrowed) {
        try {
            if (borrowed.kind() == Kind.KEPT) {
                // Kept before the permit is released, so a run that was waiting finds this copy.
                keep(borrowed.sessions());
            } else if (borrowed.kind() == Kind.EXTRA) {
                Closing.sessions(borrowed.sessions());
            }
            // A shared copy needs nothing: its sessions belong to every run, and are Session.none(), which has
            // nothing to close.
        } finally {
            endRunOnThread();
            giveBack(borrowed.held());
            leave();
        }
    }

    private void keep(Map<String, Session> sessions) {
        if (retired) {
            Closing.sessions(sessions);
        } else {
            idle.add(sessions);
        }
    }

    /**
     * Whether this thread is already running rules. Read rather than {@link #runsOnThread()} so that a borrow which
     * fails leaves no entry behind on a thread that isn't running anything. An entry exists only while the count is
     * above zero, because {@link #endRunOnThread()} removes it when the outermost run ends.
     *
     * @return {@code true} if a run on this thread is in progress
     */
    private static boolean nestedRun() {
        return RUNS_ON_THREAD.get() != null;
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

    private static void endRunOnThread() {
        int[] runs = runsOnThread();
        runs[0]--;
        if (runs[0] == 0) {
            RUNS_ON_THREAD.remove();
        }
    }

    /**
     * Retires the rule set, which runs no longer start with: closes the idle copies, and the compilers too if no run
     * holds a copy. Otherwise, the compilers are closed when the last copy is given back. Calling it again does
     * nothing more.
     */
    void retire() {
        retired = true;
        try {
            closeIdle();
        } finally {
            closeIfUnused();
        }
    }

    private Copy lend(Instant deadline) throws InterruptedException, TimeoutException {
        Map<String, Session> shared = sharedSessions;
        if (shared != null) {
            return new Copy(shared, Kind.SHARED, Held.NOTHING);
        }
        if (!copyLimit.appliesToCurrentThread()) {
            // A thread pool's size bounds the copies its runs make, and virtual threads have nothing but the build
            // slots. A nested run never waits for one: its own thread may hold the slot it would wait for.
            return copyLimit.limits() || !Thread.currentThread().isVirtual() || nestedRun()
                    ? keptCopy(Held.NOTHING) : slottedCopy(deadline);
        }
        // A nested run on this thread never waits: the copy it would wait for may be the one its own thread holds.
        boolean nested = nestedRun();
        if (nested ? !permits.available().tryAcquire()
                : !awaitPermit(permits.available(), permits::returned, stallWindow, deadline)) {
            // Right after a reload, the permits may all be held by runs on the rules it replaced, before any run of
            // these rules has learned whether they need copies at all. An extra copy can learn it too, and then
            // nothing overflowed.
            Map<String, Session> sessions = newSessions();
            if (statelessSessions(sessions)) {
                sharedSessions = sessions;
                return new Copy(sessions, Kind.SHARED, Held.NOTHING);
            }
            if (!nested) {
                warnAboutOverflow();
            }
            return new Copy(sessions, Kind.EXTRA, Held.NOTHING);
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
                return keptCopy(sessions, held);
            }
            giveBack(held);
        }
        return keptCopy(sessions, Held.NOTHING);
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
        return keptCopy(sessions, held);
    }

    /**
     * Lends the given sessions as a copy the run keeps until it gives it back. The first copy decides whether the
     * rules need copies at all: when no language keeps state between runs, its sessions become the ones every run
     * shares, and the run gives back at once what it took for the copy.
     *
     * @param sessions The copy's sessions
     * @param held     What the run took for this copy
     * @return The copy
     */
    private Copy keptCopy(Map<String, Session> sessions, Held held) {
        if (statelessSessions(sessions)) {
            // Nothing in the rules changes while they run, so one set of sessions serves every run at once.
            sharedSessions = sessions;
            giveBack(held);
            return new Copy(sessions, Kind.SHARED, Held.NOTHING);
        }
        return new Copy(sessions, Kind.KEPT, held);
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

    // Uncounts a run that gave back its copy, closing a retired rule set that no run uses any more.
    private void leave() {
        if (users.decrementAndGet() == 0 && retired) {
            closeIfUnused();
        }
    }

    private void closeIfUnused() {
        if (users.compareAndSet(0, CLOSED)) {
            try {
                closeIdle();
            } finally {
                Closing.compilers(compilers);
            }
        }
    }

    private void closeIdle() {
        for (Map<String, Session> sessions = idle.poll(); sessions != null; sessions = idle.poll()) {
            Closing.sessions(sessions);
        }
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
     * copy are closed.
     */
    private Map<String, Session> newSessions() {
        Map<String, Session> sessions = new LinkedHashMap<>();
        boolean made = false;
        try {
            for (Map.Entry<String, ExpressionCompiler> compiler : compilers.entrySet()) {
                sessions.put(compiler.getKey(), newSession(compiler.getKey(), compiler.getValue()));
            }
            made = true;
            return sessions;
        } finally {
            if (!made) {
                Closing.sessions(sessions);
            }
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
        } catch (Exception | Error e) {
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
