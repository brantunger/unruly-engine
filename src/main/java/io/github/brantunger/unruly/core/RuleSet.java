package io.github.brantunger.unruly.core;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.UnaryOperator;

/**
 * One loaded rule list, and the compiled copies of it that concurrent runs use.
 *
 * <p>
 * MVEL caches an accessor in a compiled expression the first time it runs. When a later run binds the same name to a
 * different kind of object, such as a {@code Map} in one run and a record in the next, MVEL replaces that accessor
 * without synchronization, so two threads running one compiled expression can fail with a
 * {@code ClassCastException}. A compiled copy is therefore only used by one run at a time: a run borrows an idle
 * copy, or compiles a new one when every copy is in use, and gives it back when it finishes. The number of copies
 * grows to the largest number of runs that have used the rule list at once.
 * </p>
 */
final class RuleSet {

    private final List<CompiledRule> compiledRules;
    private final UnaryOperator<CompiledRule> recompile;
    private final Queue<List<CompiledRule>> idle = new ConcurrentLinkedQueue<>();

    /**
     * Creates a rule set whose first copy is {@code compiledRules}.
     *
     * @param compiledRules The compiled rules, in the order they run
     * @param recompile     Compiles a new copy of one rule from its source
     */
    RuleSet(List<CompiledRule> compiledRules, UnaryOperator<CompiledRule> recompile) {
        this.compiledRules = List.copyOf(compiledRules);
        this.recompile = recompile;
        idle.add(this.compiledRules);
    }

    /**
     * Returns the rules as they were compiled by {@code setRuleList()}.
     *
     * @return An unmodifiable list of the compiled rules
     */
    List<CompiledRule> rules() {
        return compiledRules;
    }

    /**
     * Takes a copy of the rules that no other run is using, compiling a new one if necessary.
     *
     * @return A copy for the caller alone, to give back with {@link #release(List)}
     */
    List<CompiledRule> borrow() {
        List<CompiledRule> copy = idle.poll();
        return copy != null ? copy : compiledRules.stream().map(recompile).toList();
    }

    /**
     * Gives back a copy taken with {@link #borrow()}, so a later run can reuse it.
     *
     * @param copy The copy, which the caller must no longer use
     */
    void release(List<CompiledRule> copy) {
        idle.add(copy);
    }
}
