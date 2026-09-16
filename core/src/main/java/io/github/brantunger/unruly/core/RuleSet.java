package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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
 * at once.
 * </p>
 *
 * <p>
 * With a limit, at most that many copies are kept, and a run that finds all of them in use waits for one. A run nested
 * in another run on the same thread, for example started from an action or a listener, doesn't wait, because the copy
 * it would wait for may be the one its own thread holds: if no copy is free, it gets an extra copy, whose sessions are
 * closed when it's given back. The limit is per rule set, so while a reload replaces one rule set with another, runs
 * still using the old one can hold up to that many copies more.
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

    private final List<CompiledRule> compiledRules;
    private final Map<String, ExpressionCompiler> compilers;
    // Identify these rules, and when they were loaded, for RulesEngine.rules() and every run's result.
    private final String ruleChecksum;
    private final Instant loadTime;
    private final Queue<Map<String, Session>> idle = new ConcurrentLinkedQueue<>();
    private final int copyLimit;
    private final boolean limited;
    // With a limit, one permit for each kept copy that a run holds.
    private final Semaphore permits;
    // How many kept copies the current thread holds, which tells a nested run apart. The entry is removed when the
    // count drops to zero, so pooled threads don't keep one for every rule set they have run.
    private final ThreadLocal<int[]> held = ThreadLocal.withInitial(() -> new int[1]);
    // How many copies runs hold, or CLOSED.
    private final AtomicInteger users = new AtomicInteger();
    private volatile boolean retired;

    /**
     * A copy of the rules lent to one run.
     *
     * @param sessions The sessions of the languages the rules use, by language name
     * @param kept     Whether {@link #release(Copy)} keeps the copy for a later run
     */
    record Copy(Map<String, Session> sessions, boolean kept) {
    }

    /**
     * Creates a rule set with no copies yet and no limit on them.
     *
     * @param compiledRules The compiled rules, in the order they run
     * @param compilers     The compilers of the languages the rules use, by language name, in the order they check
     *                      fact names
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers) {
        this(compiledRules, compilers, UNLIMITED);
    }

    /**
     * Creates a rule set with no copies yet.
     *
     * @param compiledRules The compiled rules, in the order they run
     * @param compilers     The compilers of the languages the rules use, by language name, in the order they check
     *                      fact names
     * @param limit         The most copies to keep, or {@link #UNLIMITED}
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers, int limit) {
        this.compiledRules = List.copyOf(compiledRules);
        this.compilers = Collections.unmodifiableMap(new LinkedHashMap<>(compilers));
        this.ruleChecksum = Checksums.ofRules(this.compiledRules);
        this.loadTime = Instant.now();
        this.copyLimit = limit;
        this.limited = limit != UNLIMITED;
        this.permits = new Semaphore(limit);
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
     * Returns the most copies this rule set keeps.
     *
     * @return The limit, or {@link #UNLIMITED}
     */
    int limit() {
        return copyLimit;
    }

    /**
     * Takes a copy of the rules that no other run is using, making a new one if necessary. With a limit, waits for a
     * copy when all of them are in use, unless the current thread already holds one: then an extra copy that isn't
     * kept is made instead. A copy that fails to be made isn't kept, and doesn't count toward the limit.
     *
     * @return A copy for the caller alone, to give back with {@link #release(Copy)}, or {@code null} if the rule set is
     *         closed: it was retired, and every copy was given back
     * @throws InterruptedException   if the thread is interrupted while it waits for a copy that is in use. A thread
     *                                whose interrupt status is already set still gets a free copy; the run then stops
     *                                at its first rule. A thread that throws holds no copy.
     * @throws RuleExecutionException if a language fails to create a session for a new copy, which is logged at ERROR.
     *                                A fatal {@link Error} is then rethrown unchanged.
     */
    Copy borrow() throws InterruptedException {
        if (!enter()) {
            return null;
        }
        boolean lent = false;
        try {
            Copy borrowed = lend();
            lent = true;
            return borrowed;
        } finally {
            if (!lent) {
                leave();
            }
        }
    }

    /**
     * Gives back a copy taken with {@link #borrow()}. A kept copy is kept for a later run, unless the rule set is
     * retired; any other copy's sessions are closed.
     *
     * @param borrowed The copy, which the caller must no longer use
     */
    void release(Copy borrowed) {
        try {
            if (borrowed.kept() && !retired) {
                // Added before the permit is released, so a run that was waiting finds this copy.
                idle.add(borrowed.sessions());
            } else {
                Closing.sessions(borrowed.sessions());
            }
        } finally {
            if (borrowed.kept() && limited) {
                giveBack();
            }
            leave();
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

    private Copy lend() throws InterruptedException {
        if (!limited) {
            return new Copy(take(), true);
        }
        int[] count = held.get();
        if (count[0] == 0) {
            // tryAcquire() first: acquire() throws at once on a thread whose interrupt status is already set, even
            // when copies are free, and the run would fail saying every copy was in use when none was.
            if (!permits.tryAcquire()) {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    held.remove();
                    throw e;
                }
            }
        } else if (!permits.tryAcquire()) {
            return new Copy(newSessions(), false);
        }
        count[0]++;
        boolean lent = false;
        try {
            Copy borrowed = new Copy(take(), true);
            lent = true;
            return borrowed;
        } finally {
            if (!lent) {
                giveBack();
            }
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

    // Releases a kept copy's permit, which the current thread holds.
    private void giveBack() {
        permits.release();
        int[] count = held.get();
        count[0]--;
        if (count[0] == 0) {
            held.remove();
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
            throw new RuleExecutionException(msg, e);
        }
        if (session == null) {
            String msg = failed + "returned no session";
            log.error(msg);
            throw new RuleExecutionException(msg);
        }
        return session;
    }
}
