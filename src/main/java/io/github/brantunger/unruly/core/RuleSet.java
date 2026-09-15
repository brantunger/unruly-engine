package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionCompiler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.UnaryOperator;

/**
 * One loaded rule list: the rules as {@code setRuleList()} compiled them, the fact-name checks of the languages they
 * use, and the compiled copies that runs use. Keeping the checks here lets a reload swap in both with one write.
 *
 * <p>
 * MVEL caches an accessor in a compiled expression the first time it runs. When a later run binds the same name to a
 * different kind of object, such as a {@code Map} in one run and a record in the next, MVEL replaces that accessor
 * without synchronization, so two threads running one compiled expression can fail with a
 * {@code ClassCastException}. A compiled copy is therefore only used by one run at a time: a run borrows an idle
 * copy, or makes a new one when every copy is in use, and gives it back when it finishes. Without a limit, the number
 * of copies grows to the largest number of runs that have used the rule list at once.
 * </p>
 *
 * <p>
 * With a limit, at most that many copies are kept, and a run that finds all of them in use waits for one. A run nested
 * in another run on the same thread, for example started from an action or a listener, doesn't wait, because the copy
 * it would wait for may be the one its own thread holds: if no copy is free, it gets an extra copy that isn't kept.
 * The limit is per rule set, so while a reload replaces one rule set with another, runs still using the old one can
 * hold up to that many copies more.
 * </p>
 *
 * <p>
 * The rules {@code setRuleList()} compiled are only copied, never lent to a run, so a copy is never made from an
 * expression that is running. Several threads can copy them at the same time.
 * </p>
 */
final class RuleSet {

    /** The limit of a rule set that makes as many copies as its runs need. */
    static final int UNLIMITED = 0;

    private final List<CompiledRule> compiledRules;
    private final Map<String, ExpressionCompiler> factNameChecks;
    private final UnaryOperator<CompiledRule> copy;
    private final Queue<List<CompiledRule>> idle = new ConcurrentLinkedQueue<>();
    private final int copyLimit;
    private final boolean limited;
    // With a limit, one permit for each kept copy that a run holds.
    private final Semaphore permits;
    // How many kept copies the current thread holds, which tells a nested run apart. The entry is removed when the
    // count drops to zero, so pooled threads don't keep one for every rule set they have run.
    private final ThreadLocal<int[]> held = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * A copy of the rules lent to one run.
     *
     * @param rules The copied rules, in the order they run
     * @param kept  Whether {@link #release(Copy)} keeps the copy for a later run
     */
    record Copy(List<CompiledRule> rules, boolean kept) {
    }

    /**
     * Creates a rule set with no copies yet and no limit on them.
     *
     * @param compiledRules The compiled rules, in the order they run
     * @param factChecks    The compilers whose {@code checkFactName} every fact of a run is checked with, by language
     *                      name, in the order they check
     * @param copy          Makes a copy of one rule for a run
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> factChecks,
            UnaryOperator<CompiledRule> copy) {
        this(compiledRules, factChecks, copy, UNLIMITED);
    }

    /**
     * Creates a rule set with no copies yet.
     *
     * @param compiledRules The compiled rules, in the order they run
     * @param factChecks    The compilers whose {@code checkFactName} every fact of a run is checked with, by language
     *                      name, in the order they check
     * @param copy          Makes a copy of one rule for a run
     * @param limit         The most copies to keep, or {@link #UNLIMITED}
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> factChecks,
            UnaryOperator<CompiledRule> copy, int limit) {
        this.compiledRules = List.copyOf(compiledRules);
        this.factNameChecks = Collections.unmodifiableMap(new LinkedHashMap<>(factChecks));
        this.copy = copy;
        this.copyLimit = limit;
        this.limited = limit != UNLIMITED;
        this.permits = new Semaphore(limit);
    }

    /**
     * Returns the rules as they were compiled by {@code setRuleList()}, which no run uses.
     *
     * @return An unmodifiable list of the compiled rules
     */
    List<CompiledRule> rules() {
        return compiledRules;
    }

    /**
     * Returns the compilers that check the fact names of a run of these rules.
     *
     * @return An unmodifiable map of the compilers by language name, in the order they check
     */
    Map<String, ExpressionCompiler> factChecks() {
        return factNameChecks;
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
     * @return A copy for the caller alone, to give back with {@link #release(Copy)}
     * @throws InterruptedException if the thread is interrupted while waiting for a copy. It then holds no copy.
     */
    Copy borrow() throws InterruptedException {
        if (!limited) {
            return new Copy(take(), true);
        }
        int[] count = held.get();
        if (count[0] == 0) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                held.remove();
                throw e;
            }
        } else if (!permits.tryAcquire()) {
            return new Copy(newCopy(), false);
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

    /**
     * Gives back a copy taken with {@link #borrow()}, so a later run can reuse it if it's kept.
     *
     * @param borrowed The copy, which the caller must no longer use
     */
    void release(Copy borrowed) {
        if (borrowed.kept()) {
            // Added before the permit is released, so a run that was waiting finds this copy.
            idle.add(borrowed.rules());
            if (limited) {
                giveBack();
            }
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

    private List<CompiledRule> take() {
        List<CompiledRule> borrowed = idle.poll();
        return borrowed != null ? borrowed : newCopy();
    }

    private List<CompiledRule> newCopy() {
        return compiledRules.stream().map(copy).toList();
    }
}
