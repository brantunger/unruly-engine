package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

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
 * Either gets an extra copy, whose sessions are closed when it's given back, so the limit is a limit on runs that can
 * make progress rather than a hard ceiling. A run that stalls takes an idle copy first, if the rule set has one, as it
 * may when runs on another rule set hold the permits, or a retired rule set kept the copy for it: it holds no permit
 * with it, but the copy is one that already exists. On a rule set in use, that copy is closed when it's given back, as
 * an extra copy is, so the kept copies never outnumber the limit. The engine's rule sets share one set of
 * {@link CopyPermits}, so while a reload replaces one rule set with another, runs still using the old one count against
 * the same limit as runs on the new one. The idle copies the old one keeps for its runs still waiting, described below,
 * hold no permit, so for a short time they come on top of the limit: no more than one for each of its runs still
 * waiting, never more than the limit, and only until its last run leaves.
 * </p>
 *
 * <p>
 * A rule list whose languages all return {@link Session#none()} keeps nothing between runs, so there is nothing to
 * copy: every run shares one set of sessions, waits for nothing and counts against no limit.
 * </p>
 *
 * <p>
 * Once {@link #retire()} is called, because a reload replaced the rule list or the engine was closed, idle copies are
 * closed at once, and a copy given back is closed instead of kept, unless a run of this rule list is still waiting for
 * a permit or a build slot and fewer copies are idle than runs are waiting, or than the limit if there is one: it's
 * kept for that run then, so the runs that were waiting when the rule list was replaced take the copies given back
 * rather than each making one of its own. That isn't exact: a copy given back just as a run starts or stops waiting may
 * be closed when a run could have used it, or kept when none can, which costs a copy and leaves none open; and no
 * waiting run saves the copies that were idle when the rule list was retired, which are closed then. A copy given back
 * while {@code retire()} is still taking the idle copies, before it has marked the rule set retired, is kept as on a
 * rule set in use: {@code retire()} closes it too if no run is using the rule set by the time it marks the rule set
 * retired. Only those copies' fatal {@link Error}s reach {@code retire()}; a copy kept after that for a waiting run
 * never does. When no run is using the rule set any more, it closes the copies still idle, and then lends no more
 * copies; a fatal {@link Error} from that reaches the last run to leave. The compilers are closed once both
 * {@code retire()} has closed the copies that were idle and no run is using the rule set, by whichever of the two
 * finishes second, which gets their fatal {@link Error}: so when the last run leaves while {@code retire()} is still
 * closing, {@code retire()} closes them, after every session. A fatal {@link Error} from closing one copy doesn't stop
 * the others being closed, nor the compilers after them: the first is returned for the caller to throw once they have.
 * </p>
 */
final class RuleSet {

    /** The limit of a rule set that makes as many copies as its runs need. */
    static final int UNLIMITED = 0;

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);
    // The number of users once the rule set is closed.
    private static final int CLOSED = -1;
    // Set in the number of users once the rule set is retired, so that a run leaving reads whether it's retired in the
    // same step as it uncounts itself. A retired rule set that no run uses is CLOSED, never this alone. So the count
    // is bounded: a rule set that 2^30 runs were using at once would read as retired, which no JVM's threads reach.
    private static final int RETIRED = 1 << 30;
    // The copies retire() closes if it fails to make room for those it takes: none. Shared, and nothing adds to it.
    private static final Queue<Map<String, Session>> NO_COPIES = new ConcurrentLinkedQueue<>();
    // The bits of partsDone: retire() has closed the copies it took, and the copies still idle once no run used the
    // rule set have been closed.
    private static final int COPIES_TAKEN_CLOSED = 1;
    private static final int IDLE_CLOSED = 2;
    private static final int BOTH_PARTS = COPIES_TAKEN_CLOSED | IDLE_CLOSED;
    // How long a run waits without one copy being given back before it decides they aren't coming back. Long
    // enough that only a rule slower than this, or a run waiting for another thread's run, reaches it.
    static final long STALL_WINDOW_MILLIS = 5000;
    // Runs in progress on this thread, whatever engine or rule list they use, each counted from when it starts to get
    // its copy, so a nested run never waits for a copy its own thread may be holding. Removed when the outermost run
    // ends, so a pooled thread keeps nothing.
    // A plain ThreadLocal, not withInitial(): a lambda in a static initializer has to be bootstrapped while the
    // class is being initialized, which deadlocks when several threads load this class at once.
    private static final ThreadLocal<int[]> RUNS_ON_THREAD = new ThreadLocal<>();
    // Claims retire() for one caller at a time: see retiring.
    private static final AtomicIntegerFieldUpdater<RuleSet> RETIRING =
            AtomicIntegerFieldUpdater.newUpdater(RuleSet.class, "retiring");

    private final List<CompiledRule> compiledRules;
    private final Map<String, ExpressionCompiler> compilers;
    // What a run checks its facts' names against besides the compilers: the facts the rules of each language that can
    // tell read, and the names the languages the rules use reserve only for the rule lists that use them.
    private final Map<String, Set<String>> namesRead;
    private final Map<String, String> reservedNames;
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
    // How many runs hold a copy or are getting one, with RETIRED set once the rule set is retired, or CLOSED.
    private final AtomicInteger users = new AtomicInteger();
    // How many runs are waiting for a permit or a build slot, each until it stops waiting: once it has looked for an
    // idle copy, whether it got a permit or stalled, or when it is interrupted or passes its deadline. A retired rule
    // set keeps a copy given back for them rather than closing it and leaving them to make another, one for each.
    private final AtomicInteger waiting = new AtomicInteger();
    // 1 while a retire() call is under way, and for good once one has done all its work, so that another call does
    // nothing. Set before retire() takes the idle copies, and RETIRED after it has taken them, so a copy kept after the
    // rule set was retired is never among them. Set back to 0, with a plain write that makes no call, once a call that
    // failed part way, as one that runs out of stack can, has done all it could, so a later call finishes the work.
    // Claimed through RETIRING, which PMD doesn't see.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private volatile int retiring;
    // What retire() has done, for a later call to go on from where one that failed stopped. Only the call holding the
    // claim above reads or writes them, and the claim's volatile writes publish them to the next.
    // The idle copies retire() took before it marked the rule set retired, until each is closed, and the one it has
    // just taken from the idle queue, until it's among them: each is reachable from here from the moment it's taken.
    private Queue<Map<String, Session>> retiredCopies = NO_COPIES;
    private Map<String, Session> copyTaken;
    // Whether retire() has marked the rule set retired, and whether no run was using it then, so retire() closes it.
    private boolean marked;
    private boolean unused;
    // Whether retire() has done all its work, so the engine needn't call it again.
    private volatile boolean retireDone;
    // The two parts that must finish before the compilers close: retire() closing the copies that were idle when it
    // retired the rule set, and closing the copies still idle once no run uses it (closeUnused). Each sets its bit,
    // which setting again changes nothing, and whichever finds both set closes the compilers still open, one at a time
    // from a queue, so a part that is done again after failing part way closes only the ones still open.
    private final AtomicInteger partsDone = new AtomicInteger();
    private final Queue<Map.Entry<String, ExpressionCompiler>> openCompilers;
    // The next rule set in the engine's list of those still to retire, while this one is in it, and the load() or
    // close() that has claimed it for retiring, if one has: that call's number, or 0. The lock of the engine's
    // RuleSetLifecycle guards both.
    RuleSet nextUnretired;
    long retiringClaim;

    /** What {@link #release(Copy)} does with a copy when the run that borrowed it gives it back. */
    enum Kind {
        /**
         * Kept for a later run, unless the rule set has been retired and already keeps an idle copy for each run of it
         * waiting for one, up to the limit, or the idle queue can't take it.
         */
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

    // Where a kept copy comes from when the run hasn't taken one already. Not private, so RunClasses can name it.
    enum Source {
        // An idle copy if there is one, or else a new one.
        IDLE_OR_NEW,
        // A new one: the run has just looked for an idle copy and found none.
        NEW
    }

    // Whether lending a copy warns that the run made it after waiting for a kept one. Not private, as Source isn't.
    enum Warning {
        NONE,
        OVERFLOW
    }

    /**
     * A copy of the rules lent to one run.
     *
     * @param sessions The sessions of the languages the rules use, by language name
     * @param kind     What happens to it when it's given back
     * @param loan     What the run holds with it, given back with it, once, and the count of runs in progress on the
     *                 thread that borrowed it, which giving it back lowers without a call that could fail first
     */
    record Copy(Map<String, Session> sessions, Kind kind, Loan loan) {

        /**
         * Returns what the run still holds with the copy: nothing once it has given it back.
         *
         * @return The permit or build slot it holds, or {@link Held#NOTHING}
         */
        Held held() {
            return loan.held;
        }

        /**
         * Returns whether the copy is kept for a later run.
         *
         * @return {@code true} if it goes back into the idle queue
         */
        boolean kept() {
            return kind == Kind.KEPT;
        }

        // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods,
        // which can fail for good when first called deep in the stack (#996). equals compares the components last
        // first, as ObjectMethods does.
        @Override
        public final boolean equals(Object other) {
            return this == other || other instanceof Copy that && Objects.equals(loan, that.loan)
                    && Objects.equals(kind, that.kind) && Objects.equals(sessions, that.sessions);
        }

        @Override
        public final int hashCode() {
            int hash = Objects.hashCode(sessions);
            hash = hash * 31 + Objects.hashCode(kind);
            return hash * 31 + Objects.hashCode(loan);
        }

        @Override
        public final String toString() {
            return "Copy[sessions=" + sessions + ", kind=" + kind + ", loan=" + loan + "]";
        }
    }

    /**
     * What one borrow holds before it lends its copy: the thread's count of runs in progress, the permit or build slot
     * it took, and the sessions it took or made. Each is recorded as it's taken, with no call in between, and a
     * permit or slot is forgotten only once it has been given back, so a borrow that fails anywhere, even in a handler
     * that was giving them back, as a {@link StackOverflowError} near the end of the stack can make it, gives back and
     * closes what is still recorded here, from the frame that borrowed. The copy lent keeps it, so giving the copy
     * back gives back what it holds once only, and a give-back that failed can be tried again (see
     * {@link #giveBackLeft(Copy)}).
     */
    static final class Loan {
        private final int[] runs;
        private Held held = Held.NOTHING;
        // Taken from the idle queue or made, and neither lent nor closed yet.
        private Map<String, Session> sessions;

        // The thread's own count, not a copy of it: the copy lent with this loan lowers it when it's given back.
        @SuppressWarnings({"PMD.ArrayIsStoredDirectly", "PMD.UseVarargs"})
        Loan(int[] runs) {
            this.runs = runs;
        }
    }

    /**
     * Creates a rule set whose idle copies wait in {@code idle}. It is a deliberate test seam: it lets a test hand
     * the rule set a queue that fails, as one that can't allocate room for a copy does, which nothing but a test needs.
     *
     * @param compiledRules     The compiled rules, in the order they run
     * @param compilers         The compilers of the languages the rules use, by language name, in the order they
     *                          check fact names
     * @param limit             How many copies runs may hold at once, and which runs that applies to
     * @param permits           The permits for {@code limit}, which other rule sets may share
     * @param stallWindowMillis How long a run waits without one copy being given back before it makes an extra one
     * @param idle              The queue the idle copies wait in, which runs share, so it must be thread-safe
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers, CopyLimit limit,
            CopyPermits permits, long stallWindowMillis, Queue<Map<String, Session>> idle) {
        this(compiledRules, compilers, Map.of(), Map.of(), limit, permits, stallWindowMillis, idle);
    }

    /**
     * Creates a rule set whose languages say which facts their rules read, or reserve names only for the rule lists
     * that use them, whose limited runs take the given permits and give up waiting after {@code stallWindowMillis}.
     * The engine loads its rule lists with it.
     *
     * @param compiledRules     The compiled rules, in the order they run
     * @param compilers         The compilers of the languages the rules use, by language name, in the order they
     *                          check fact names
     * @param namesRead         The names of the facts each compiler's rules read, by language name, for the compilers
     *                          that can tell; empty when none can
     * @param reservedFactNames The language that reserves each name, by name, of the languages the rules use that
     *                          reserve their names only for the rule lists that use them; empty when none does
     * @param limit             How many copies runs may hold at once, and which runs that applies to
     * @param permits           The permits for {@code limit}, which other rule sets may share
     * @param stallWindowMillis How long a run waits without one copy being given back before it makes an extra one
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers,
            Map<String, Set<String>> namesRead, Map<String, String> reservedFactNames, CopyLimit limit,
            CopyPermits permits, long stallWindowMillis) {
        this(compiledRules, compilers, namesRead, reservedFactNames, limit, permits, stallWindowMillis,
                new ConcurrentLinkedQueue<>());
    }

    private RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers,
                    Map<String, Set<String>> namesRead, Map<String, String> reservedFactNames, CopyLimit limit,
                    CopyPermits permits, long stallWindowMillis, Queue<Map<String, Session>> idle) {
        this.compiledRules = List.copyOf(compiledRules);
        this.compilers = Collections.unmodifiableMap(new LinkedHashMap<>(compilers));
        this.namesRead = Map.copyOf(namesRead);
        this.reservedNames = Map.copyOf(reservedFactNames);
        this.ruleChecksum = Checksums.ofRules(this.compiledRules);
        this.loadTime = Instant.now();
        this.copyLimit = limit;
        this.permits = permits;
        this.stallWindow = stallWindowMillis;
        this.idle = idle;
        this.openCompilers = new ConcurrentLinkedQueue<>(this.compilers.entrySet());
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
     * Returns the names of the facts the rules of each language read, for the languages whose compilers can tell (see
     * {@link ExpressionCompiler#factNamesRead()}): a run asks such a language to check only those names.
     *
     * @return An unmodifiable map of the names by language name; empty when no compiler can tell
     */
    Map<String, Set<String>> factNamesRead() {
        return namesRead;
    }

    /**
     * Returns the names that the languages these rules use reserve only for the rule lists that use them (see
     * {@link io.github.brantunger.unruly.api.language.ExpressionLanguage#reservesForEveryRuleList()}), which no fact
     * of a run of these rules may have.
     *
     * @return An unmodifiable map of the language that reserves each name, by name; empty when no language does
     */
    Map<String, String> reservedFactNames() {
        return reservedNames;
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
     * Returns how many runs are waiting for a permit or a build slot. It is a deliberate test seam: a test reads it to
     * know that its runs are waiting before it retires the rule set, which nothing but a test needs.
     *
     * @return The number of runs waiting, each counted until it stops waiting: once it has looked for an idle copy,
     *         whether it got what it waited for or gave up, or when it is interrupted or passes its deadline
     */
    int waiters() {
        return waiting.get();
    }

    /**
     * Takes a copy of the rules that no other run is using, making a new one if necessary. With a limit, waits for a
     * copy when all of them are in use, unless the current thread already holds one: then an extra copy that isn't kept
     * is made instead. So is a run started on the same thread while another run is getting its copy, as a language
     * creating a session may start one: it counts as nested in that run. A run that waits a whole stall window without
     * a copy being given back takes an idle copy of these rules first, if there is one, which is closed when it's given
     * back unless the rule set is retired; if there is none, it makes an extra copy. A copy that fails to be made isn't
     * kept, and doesn't count toward the limit. Without a limit, a run on a virtual thread that has to make a copy
     * waits for a build slot first: with a deadline, for at most half the time it has left; without one, for as long as
     * slots keep coming back.
     *
     * @return A copy for the caller alone, to give back with {@link #release(Copy)}, or {@code null} if the rule set is
     *         closed: it was retired, and every copy was given back
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none. Waiting for a copy that is in use
     *                 stops there; waiting for a build slot gives up at half the time left, and the run makes its
     *                 copy.
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
    Copy borrow(Deadline deadline) throws InterruptedException, TimeoutException {
        // Found or made before anything is held, as the loan is, so that failing to make either leaves nothing to give
        // back.
        int[] runs = runsOnThread();
        // A run already in progress on this thread, whatever engine or rule list it uses, makes this one nested.
        boolean nested = runs[0] > 0;
        Loan loan = new Loan(runs);
        if (!enter()) {
            forgetIfIdle(runs[0]);
            return null;
        }
        // Counted before the copy is lent, by a step that can't fail, and uncounted if lending fails. So a run that a
        // language starts on this thread while this one gets its copy finds this one in progress, and when it ends it
        // leaves the thread's count in place rather than removing it from under this run.
        runs[0]++;
        try {
            return lend(deadline, nested, loan);
        } catch (InterruptedException e) {
            // Uncounted first, as after any failed borrow, but not left: a fatal Error from closing the rules as the
            // run leaves would otherwise be thrown before the run could report that it stopped. Nothing here
            // allocates, so nothing can fail before the caller takes over, which leaves once it has reported the stop.
            // A wait that stops holds nothing to give back.
            runs[0]--;
            try {
                forgetIfIdle(runs[0]);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw e;
        } catch (TimeoutException e) {
            runs[0]--;
            forgetIfIdle(runs[0]);
            throw e;
        } catch (Throwable t) {
            Failures.throwIfPresent(failedBorrow(t, loan));
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

    // Uncounts a run whose borrow failed, gives back and closes what the loan still holds, and leaves the rule set,
    // returning the fatal Error to throw in place of failure, if closing threw one (see Failures.fatalInsteadOf).
    // Uncounted first, with no call, so the run never stays counted. The interrupt status is set again before anything
    // is closed, if an interrupt caused the failure, so that closing sees it. Each step is in a finally of the one
    // before, so one that fails, as one that runs out of stack does, doesn't keep the next from running.
    private Error failedBorrow(Throwable failure, Loan loan) {
        int[] runs = loan.runs;
        runs[0]--;
        Error fatal = null;
        try {
            forgetIfIdle(runs[0]);
        } finally {
            try {
                giveBack(loan);
            } finally {
                try {
                    Failures.keepInterruptStatus(failure);
                } finally {
                    try {
                        fatal = closeTaken(loan);
                    } finally {
                        fatal = Failures.first(fatal, leave());
                    }
                }
            }
        }
        return Failures.fatalInsteadOf(failure, fatal);
    }

    // Closes the sessions a failed borrow took or made and neither lent nor closed, forgetting them first so they are
    // never closed twice. Returns the first fatal Error from closing them.
    @SuppressWarnings("PMD.NullAssignment")
    private static Error closeTaken(Loan loan) {
        Map<String, Session> sessions = loan.sessions;
        if (sessions == null) {
            return null;
        }
        loan.sessions = null;
        return Closing.sessions(sessions);
    }

    /**
     * Makes {@code count} idle copies of the rules, each session prepared with
     * {@link ExpressionCompiler#warmUp(Session)}, for runs to borrow. Called by {@code load()} on its own thread,
     * before any run can see the rule set. If the first copy shows that no language keeps state between runs, it
     * becomes the sessions every run shares, and no more are made.
     *
     * @param count How many copies to make; zero makes none
     * @throws RuleExecutionException if a language throws or returns {@code null} from {@code newSession()}, or
     *                                throws from {@code warmUp()}. It's logged at ERROR, unless a {@code run()} the
     *                                language started logged it (see {@link LoggedFailures}), and the copies already
     *                                made, the one that failed too, even if only some of its sessions were made, stay
     *                                idle for {@link #retire()} to close. A fatal {@link Error} is rethrown
     *                                unchanged.
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
            LoggedFailures.callOut();
            compiler.warmUp(session);
        } catch (Throwable e) {
            Failures.keepInterruptStatus(e);
            String msg = "The '" + Failures.quote(language) + "' expression language failed to warm up a session: "
                    + Failures.describe(e);
            AbstractRulesEngine.reportCalledCodeFailure(msg, e);
            throw new ReportedFailure(msg, e);
        }
    }

    /**
     * Gives back a copy taken with {@link #borrow(Instant)}. A kept copy is kept for a later run, unless the rule set
     * is retired and already keeps an idle copy for each run of it waiting for one, up to the limit, or the idle queue
     * can't take it; any other copy's sessions are closed. The last copy given back to a retired rule set closes the
     * copies still idle, and its compilers too, unless {@link #retire()} is still closing the copies that were idle
     * when it retired the rule set: then {@code retire()} closes the compilers once it has.
     *
     * @param borrowed The copy, which the caller must no longer use
     * @return The first fatal {@link Error} keeping the copy, or closing its sessions or the compilers, threw, for the
     *         caller to throw, or {@code null} if none did
     */
    Error release(Copy borrowed) {
        Error fatal = null;
        try {
            if (borrowed.kind() == Kind.KEPT) {
                // Kept before the permit is released, so a run that was waiting finds this copy, and before the run
                // leaves, so a copy is never kept into a rule set the last run to leave has already closed.
                fatal = keep(borrowed.sessions());
            } else if (borrowed.kind() == Kind.EXTRA) {
                fatal = Closing.sessions(borrowed.sessions());
            }
            // A shared copy needs nothing: its sessions belong to every run, and are Session.none(), which has
            // nothing to close.
        } finally {
            // Uncounted first, with no call, so the run is uncounted however giving the copy back ends, and a run that
            // closing the rules starts on this thread isn't nested in it, as after a failed borrow. Each step after it
            // is in a finally of the one before, so one that fails, as one that runs out of stack does, doesn't keep
            // the run from leaving.
            int[] runs = borrowed.loan.runs;
            runs[0]--;
            try {
                forgetIfIdle(runs[0]);
            } finally {
                try {
                    giveBack(borrowed.loan);
                } finally {
                    fatal = Failures.first(fatal, leave());
                }
            }
        }
        return fatal;
    }

    // Keeps a copy given back for a later run, or closes its sessions if the rule set is retired and already keeps as
    // many idle copies as keptForWaiters() allows, which is none when no run of it is waiting for a copy, returning the
    // first fatal Error from closing them. Counting the idle copies and adding one isn't one step, so copies given back
    // at the same instant may keep one or two more than that; each is still closed by the last run to leave. A copy
    // kept for a run that is waiting is never left open: the run giving it back is counted until it leaves, after this,
    // so if no run takes it, the last run to leave closes it, even one that stopped waiting. A copy the idle queue
    // can't take, as when it can't allocate room for it, is closed too, rather than lost with its sessions open, and
    // what the queue threw is logged at WARN, and returned first if it's fatal.
    // Any Throwable: the sessions must be closed however keeping them fails, and the run must still leave.
    private Error keep(Map<String, Session> sessions) {
        if (isRetired() && idle.size() >= keptForWaiters()) {
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

    // How many idle copies a retired rule set keeps: one for each run still waiting, and never more than the limit. A
    // copy given back without a permit or a build slot wakes no run, so without this bound a retired rule set that one
    // run waits on would keep every copy that runs on platform threads give back. The idle queue holds no more than
    // this, so counting it is cheap.
    private int keptForWaiters() {
        int waiters = waiting.get();
        return copyLimit.limits() ? Math.min(waiters, copyLimit.maxCopies()) : waiters;
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

    // Removes this thread's count once no run is in progress on it: when the outermost run ends, and after a borrow
    // that got no copy, or found the rule set closed, on a thread that isn't running anything, so a pooled thread
    // keeps nothing.
    private static void forgetIfIdle(int runs) {
        if (runs == 0) {
            RUNS_ON_THREAD.remove();
        }
    }

    /**
     * Retires the rule set, which runs no longer start with: takes the idle copies, then marks the rule set retired,
     * and closes the copies it took. If no run is using the rule set when it marks it retired (holding a copy, getting
     * one, or not yet left after a stopped wait), it then closes the copies given back while it was taking them, which
     * are still idle. Otherwise a copy given back is kept only while fewer copies are idle than runs of the rule set
     * are waiting for one, and than the limit if there is one, and the last run to leave closes the copies still idle,
     * so a fatal {@link Error} from closing a copy kept for a waiting run reaches a run, never this method. The
     * compilers are closed once both this method has closed the copies it took and no run is using the rule set, by
     * whichever of the two finishes second, which gets their fatal {@link Error}. A call made while another is still
     * retiring the rule set does nothing, and so does a call after one that did all its work. A call that fails part
     * way, as one that runs out of stack can, does what it still can, and the next call goes on from where it stopped,
     * never repeating what it did: see {@link #retiredForGood()}. A fatal {@link Error} from closing one copy doesn't
     * stop the others being closed, nor the compilers after them.
     *
     * @return The first fatal {@link Error} closing threw, for the caller to throw, or {@code null} if none did
     */
    Error retire() {
        if (!RETIRING.compareAndSet(this, 0, 1)) {
            return null;
        }
        Error fatal;
        try {
            try {
                // Taken before the rule set is marked retired: a copy given back until then is kept as on a rule set
                // in use, so it's among them or still idle for closeUnused() to close, and one kept for a waiting run
                // after it never is. A call that goes on from one that failed before marking takes them again, with
                // any that one took and couldn't close.
                if (!marked) {
                    takeIdleCopies();
                }
            } finally {
                // Also if taking the idle copies throws, so copies given back are no longer kept for later runs, and
                // the compilers are still closed. In one step with the count of runs, so that either no run uses the
                // rule set and this method closes it now, or the last run to leave does: never both, and never this
                // method once a run has kept a copy for a waiting run. Recorded with no call after the step, so a
                // later call never marks it twice.
                try {
                    if (!marked) {
                        Faults.at(Faults.Step.RETIRE_MARKED);
                        unused = users.getAndUpdate(count -> count == 0 ? CLOSED : count | RETIRED) == 0;
                        marked = true;
                    }
                } finally {
                    fatal = closeRetired();
                }
            }
            retireDone = true;
        } finally {
            // Only once everything this call could do is done, closing the copies it took too, so a later call can't
            // close the compilers while this one is still closing a copy.
            if (!retireDone) {
                retiring = 0;
            }
        }
        return fatal;
    }

    // Takes the idle copies, after any an earlier call took and couldn't close, into a queue made with room for them
    // all before the first is taken, and held by retiredCopies from then on; no more are taken than it has room for,
    // so setting one aside allocates nothing. Each copy is held by copyTaken from the moment it leaves the idle
    // queue until it's set aside, so a failure at any step leaves every copy taken where closeRetired() closes it.
    // One given back meanwhile past those stays idle.
    @SuppressWarnings("PMD.NullAssignment")
    private void takeIdleCopies() {
        int left = idle.size();
        Queue<Map<String, Session>> taken = new ArrayDeque<>(retiredCopies.size() + left);
        taken.addAll(retiredCopies);
        retiredCopies = taken;
        for (; left > 0; left--) {
            copyTaken = idle.poll();
            if (copyTaken == null) {
                break;
            }
            Faults.at(Faults.Step.COPY_TAKEN);
            taken.add(copyTaken);
            copyTaken = null;
        }
    }

    // Closes the copies retire() took, which it does even when marking the rule set failed, as a retired rule set's
    // idle copies are closed; and once it's marked, and every copy it took is closed, its part of what the compilers
    // wait for, and the rule set if no run was using it. If closing the copies fails, as it can when it runs out of
    // stack, the compilers wait: a later call closes the copies left, and then the rest, so no compiler is closed
    // before a session of it. Each step is one that a later call can do again without doing anything twice. Returns the
    // first fatal Error closing threw.
    @SuppressWarnings("PMD.NullAssignment")
    private Error closeRetired() {
        Error fatal = null;
        try {
            Map<String, Session> taken = copyTaken;
            if (taken != null) {
                copyTaken = null;
                fatal = Closing.sessions(taken);
            }
        } finally {
            try {
                fatal = Failures.first(fatal, closeAll(retiredCopies));
            } finally {
                // Nothing more, unless closing one of them threw: then the rest.
                fatal = Failures.first(fatal, closeAll(retiredCopies));
            }
        }
        if (marked) {
            try {
                Faults.at(Faults.Step.RETIRED_COPIES_CLOSED);
                fatal = Failures.first(fatal, partDone(COPIES_TAKEN_CLOSED));
            } finally {
                if (unused) {
                    fatal = Failures.first(fatal, closeUnused());
                }
            }
        }
        return fatal;
    }

    /**
     * Returns whether {@link #retire()} has done all its work, so it needs calling no more. Until then a call that
     * failed part way, as one that runs out of stack can, left work that a later call does.
     *
     * @return {@code true} once a call to {@link #retire()} has done all its work and returned; a call that returned
     *         at once, because another was still retiring the rule set, hasn't
     */
    boolean retiredForGood() {
        return retireDone;
    }

    // Whether retire() has marked the rule set retired.
    private boolean isRetired() {
        return (users.get() & RETIRED) != 0;
    }

    private Copy lend(Deadline deadline, boolean nested, Loan loan) throws InterruptedException, TimeoutException {
        Map<String, Session> shared = sharedSessions;
        if (shared != null) {
            return new Copy(shared, Kind.SHARED, loan);
        }
        if (!copyLimit.appliesToCurrentThread()) {
            // A thread pool's size bounds the copies its runs make, and virtual threads have nothing but the build
            // slots. A nested run never waits for one: its own thread may hold the slot it would wait for.
            return copyLimit.limits() || !Thread.currentThread().isVirtual() || nested
                    ? keptCopy(loan, Source.IDLE_OR_NEW) : slottedCopy(deadline, loan);
        }
        // A run that finds a permit free doesn't wait, so it isn't counted as waiting. The permit is recorded before
        // anything else is called, so the borrow gives it back however it fails from here on.
        if (permits.tryTake()) {
            loan.held = Held.PERMIT;
            return keptCopy(loan, Source.IDLE_OR_NEW);
        }
        // Right after a reload, the permits may all be held by runs on the rules it replaced, before any run of these
        // rules has learned whether they need copies at all. An extra copy can learn it too, and then nothing
        // overflowed.
        if (nested) {
            // A nested run on this thread never waits: the copy it would wait for may be the one its own thread holds.
            loan.sessions = newSessions();
            return copy(loan, Kind.EXTRA);
        }
        // Counted while it waits, and until it has looked for an idle copy, so a copy given back meanwhile is kept for
        // it, even once the rule set is retired; uncounted before it makes a copy of its own, so that copies given back
        // while it makes one aren't kept for it. Uncounted once however the wait ends, and the permit given back if
        // looking fails, so a failure leaves neither behind. A run that stalls looks too: the copy kept for it may be
        // idle while a run on another rule set holds the permit given back with it.
        boolean looked = false;
        waiting.incrementAndGet();
        try {
            if (permits.awaitPermit(stallWindow, deadline)) {
                loan.held = Held.PERMIT;
            }
            loan.sessions = idle.poll();
            looked = true;
        } finally {
            try {
                waiting.decrementAndGet();
            } finally {
                if (!looked) {
                    giveBack(loan);
                }
            }
        }
        if (loan.held == Held.PERMIT) {
            return keptCopy(loan, Source.IDLE_OR_NEW);
        }
        // Stalled: an idle copy is lent, holding no permit, before an extra one is made. The copy already existed, so
        // nothing overflowed. On a rule set in use it's lent as extra, and closed when it's given back: a run holding a
        // permit may make a kept copy meanwhile, and keeping both would leave more idle copies than the limit for as
        // long as the rule set serves. A retired rule set kept it for a waiting run, and keeps it again only for one.
        if (loan.sessions != null) {
            return copy(loan, isRetired() ? Kind.KEPT : Kind.EXTRA);
        }
        loan.sessions = newSessions();
        return extraCopy(loan);
    }

    /**
     * Takes a copy for a run on a virtual thread that no limit covers. An idle copy is taken at once. Otherwise the run
     * waits for a build slot, and holds it while it runs its new copy for the first time, which is when a language
     * such as MVEL compiles the expressions and loads classes. So at most one new copy for each slot is in its first
     * run at once, apart from those of runs that gave up waiting. A run that gets a slot looks for an idle copy again
     * first, so copies given back while it waited are used before a new one is made: a retired rule set keeps those
     * given back after it was retired for it, though not those it closed as it was retired. A copy given back without
     * a slot doesn't wake a waiting run: runs arriving meanwhile take it.
     *
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @param loan     What the borrow holds, which records the slot and the copy's sessions as it takes them
     * @return The copy
     * @throws InterruptedException if the thread is interrupted while it waits for a slot
     */
    private Copy slottedCopy(Deadline deadline, Loan loan) throws InterruptedException {
        loan.sessions = idle.poll();
        if (loan.sessions == null) {
            // A run that gives up waiting still makes its copy: an engine without a limit never fails a run for want
            // of one. Counted until it has looked for a copy again, so a copy given back while it waited is kept for
            // it, even once the rule set is retired, and the slot given back if looking fails, as lend() gives back a
            // permit, so a failure leaves neither behind. A copy found then needs no slot either: it has run before.
            boolean looked = false;
            waiting.incrementAndGet();
            try {
                if (permits.awaitSlot(stallWindow, deadline)) {
                    loan.held = Held.SLOT;
                }
                loan.sessions = idle.poll();
                looked = true;
            } finally {
                try {
                    waiting.decrementAndGet();
                } finally {
                    if (!looked || loan.sessions != null) {
                        giveBack(loan);
                    }
                }
            }
            if (loan.sessions == null) {
                return keptCopy(loan, Source.NEW);
            }
        }
        return copy(loan, Kind.KEPT);
    }

    /**
     * Takes an idle copy, or makes one, that the run keeps until it gives it back, unless the run has taken one
     * already.
     *
     * @param loan   What the borrow holds: what the run took for this copy, given back if no copy can be made, and an
     *               idle copy the run has already taken, if it has
     * @param source Where the copy comes from when the run hasn't taken one
     * @return The copy
     */
    private Copy keptCopy(Loan loan, Source source) {
        if (loan.sessions == null) {
            boolean taken = false;
            try {
                loan.sessions = source == Source.NEW ? newSessions() : take();
                taken = true;
            } finally {
                if (!taken) {
                    giveBack(loan);
                }
            }
        }
        return copy(loan, Kind.KEPT);
    }

    /**
     * Lends the sessions the run took or made as a copy. The first copy decides whether the rules need copies at all:
     * when no language keeps state between runs, its sessions become the ones every run shares, and the run gives back
     * at once what it took for the copy. If lending fails, as when the copy can't be allocated, its sessions are closed
     * and what the run took for it is given back, so a failed borrow holds nothing; a fatal {@link Error} from closing
     * them is thrown in place of a failure that isn't fatal.
     *
     * @param loan What the borrow holds: the copy's sessions, which only this run holds, and what it took for them
     * @param kind What happens to the copy when it's given back, unless its sessions are shared
     * @return The copy
     */
    private Copy copy(Loan loan, Kind kind) {
        return copy(loan, kind, Warning.NONE);
    }

    /**
     * Lends the sessions the run made as an extra copy that holds nothing, as {@link #copy(Loan, Kind)} does, and
     * warns, once, that the run made an extra copy after waiting for a kept one, unless its sessions are shared.
     *
     * @param loan What the borrow holds: the copy's sessions, which only this run holds
     * @return The copy
     */
    private Copy extraCopy(Loan loan) {
        return copy(loan, Kind.EXTRA, Warning.OVERFLOW);
    }

    // Any Throwable: the sessions, and what the run took for them, must be given up however lending ends, as a finally
    // would, and a failure that isn't fatal is kept under a fatal Error from closing. Warning is inside the try for the
    // same reason. Sessions closed here are forgotten, so the failed borrow doesn't close them again.
    private Copy copy(Loan loan, Kind kind, Warning warning) {
        Faults.at(Faults.Step.COPY_LENT);
        Map<String, Session> sessions = loan.sessions;
        Copy copy;
        try {
            if (statelessSessions(sessions)) {
                copy = new Copy(sessions, Kind.SHARED, loan);
            } else {
                if (warning == Warning.OVERFLOW) {
                    warnAboutOverflow();
                }
                copy = new Copy(sessions, kind, loan);
            }
        } catch (Throwable t) {
            // Given back first, so that closing the sessions can't lose it for good, and the sessions closed even if
            // giving it back fails: the borrow then gives it back itself.
            try {
                giveBack(loan);
            } finally {
                Failures.throwIfPresent(Failures.fatalInsteadOf(t, closeTaken(loan)));
            }
            throw t;
        }
        if (copy.kind() == Kind.SHARED) {
            // Nothing in the rules changes while they run, so one set of sessions serves every run at once. Given back
            // only once nothing can fail, so that a failure never gives back the same thing twice.
            sharedSessions = sessions;
            giveBack(loan);
        }
        return copy;
    }

    /** Whether every language of these rules returned {@link Session#none()}, so a copy holds nothing of its own. */
    // Session.none() is one shared instance, and identity is the question: a session that merely equals it still
    // belongs to one run at a time. A loop, not a stream: a run calls it, and may be deep in another run's stack, where
    // a stream's first allMatch would initialize JDK classes (see StackHeadroom). It only compares the sessions, so
    // it has none to close, whatever PMD says. It reads values(), where a test injects a failure.
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.CloseResource"})
    private static boolean statelessSessions(Map<String, Session> sessions) {
        for (Session session : sessions.values()) {
            if (session != Session.none()) {
                return false;
            }
        }
        return true;
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
    // fatal Error from closing it. Whether the rule set is retired is read in the same step, so a run that leaves last
    // closes it, and retire() never does once a run was counted when it retired the rule set.
    private Error leave() {
        if (users.updateAndGet(count -> count - 1 == RETIRED ? CLOSED : count - 1) == CLOSED) {
            return closeUnused();
        }
        return null;
    }

    // Closes the copies still idle, once no run uses the retired rule set, and then the compilers, unless retire() is
    // still closing the copies that were idle when it retired the rule set: then retire() closes them once it has.
    // Called by whichever of retire() and the last run to leave closed the rule set, and again by a later retire() if
    // that one failed part way. Returns the first fatal Error from closing them.
    private Error closeUnused() {
        Error fatal = null;
        try {
            fatal = closeAll(idle);
        } finally {
            fatal = Failures.first(fatal, partDone(IDLE_CLOSED));
        }
        return fatal;
    }

    // Marks one of the two parts done that the compilers wait for, retire()'s closing or the last run leaving, and
    // closes the compilers still open if both are. Marking a part again changes nothing, and closes only the
    // compilers a call that failed part way left open. Returns the first fatal Error from closing them.
    private Error partDone(int part) {
        return partsDone.accumulateAndGet(part, (done, more) -> done | more) == BOTH_PARTS ? closeCompilers() : null;
    }

    // Closes the compilers still open, each taken from the queue as it's closed, so none is closed twice.
    private Error closeCompilers() {
        Error fatal = null;
        for (Map.Entry<String, ExpressionCompiler> compiler = openCompilers.poll(); compiler != null;
                compiler = openCompilers.poll()) {
            fatal = Failures.first(fatal, Closing.compiler(compiler.getKey(), compiler.getValue()));
        }
        return fatal;
    }

    // Closes every copy in the queue, even after one throws a fatal Error, and returns the first such error. A copy is
    // taken from the queue as it's closed, so if closing one throws, the rest are still there to close.
    private static Error closeAll(Queue<Map<String, Session>> copies) {
        Faults.at(Faults.Step.COPIES_CLOSING);
        Error fatal = null;
        for (Map<String, Session> sessions = copies.poll(); sessions != null; sessions = copies.poll()) {
            fatal = Failures.first(fatal, Closing.sessions(sessions));
        }
        return fatal;
    }

    // Gives back the permit or build slot a run held with its copy, which tells a run that is waiting that they are
    // still coming back. Never called with nothing to give back.
    private void giveBack(Held held) {
        if (held == Held.PERMIT) {
            permits.giveBack();
        } else {
            permits.giveBackSlot();
        }
    }

    // Gives back what a borrow or its copy holds, once. It first makes sure the stack has room for the whole
    // give-back, which fails, as the stack running out does, before anything is given back, and leaves it with the
    // loan for the failed borrow, or the run, to give back from further up. The loan forgets it before the give-back
    // starts, so it's never given back twice, even when giving back fails once the permit is back, as waking a
    // waiting run can: then it's lost rather than doubled, which the check ahead makes as unlikely as it can.
    private void giveBack(Loan loan) {
        Held held = loan.held;
        if (held != Held.NOTHING) {
            StackHeadroom.checkGiveBack();
            Faults.at(Faults.Step.GIVING_BACK);
            loan.held = Held.NOTHING;
            giveBack(held);
        }
    }

    /**
     * Gives back what a copy still holds because giving it back failed in {@link #release(Copy)} before anything was
     * given back, as it can when the stack runs out. The run calls it once it has given the copy back, so the permit
     * or build slot isn't lost for good; it does nothing when the copy holds nothing.
     *
     * @param copy The copy the run gave back
     */
    void giveBackLeft(Copy copy) {
        giveBack(copy.loan);
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
     * ERROR first, unless it's the failure of a {@code run()} or a {@code load()} the language started, which that
     * run or load logged, or a fatal error that run logged, unless the language wrapped it in an exception with a
     * message of its own (see {@link LoggedFailures}). A fatal {@link Error}, thrown or found among the causes of what
     * the language throws or suppressed on them (see {@link Failures#fatalError}), is then rethrown unchanged. No
     * listener is told: no callback has been sent for the run yet.
     */
    private static Session newSession(String language, ExpressionCompiler compiler) {
        String failed = "The '" + Failures.quote(language) + "' expression language ";
        Session session;
        try {
            LoggedFailures.callOut();
            session = compiler.newSession();
        } catch (Throwable e) {
            Failures.keepInterruptStatus(e);
            String msg = failed + "failed to create a session: " + Failures.describe(e);
            AbstractRulesEngine.reportCalledCodeFailure(msg, e);
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
