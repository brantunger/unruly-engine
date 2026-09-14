package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionCompiler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * copy, or makes a new one when every copy is in use, and gives it back when it finishes. The number of copies grows
 * to the largest number of runs that have used the rule list at once.
 * </p>
 *
 * <p>
 * The rules {@code setRuleList()} compiled are only copied, never lent to a run, so a copy is never made from an
 * expression that is running. Several threads can copy them at the same time.
 * </p>
 */
final class RuleSet {

    private final List<CompiledRule> compiledRules;
    private final Map<String, ExpressionCompiler> factNameChecks;
    private final UnaryOperator<CompiledRule> copy;
    private final Queue<List<CompiledRule>> idle = new ConcurrentLinkedQueue<>();

    /**
     * Creates a rule set with no copies yet.
     *
     * @param compiledRules The compiled rules, in the order they run
     * @param factChecks    The compilers whose {@code checkFactName} every fact of a run is checked with, by language
     *                      name, in the order they check
     * @param copy          Makes a copy of one rule for a run
     */
    RuleSet(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> factChecks,
            UnaryOperator<CompiledRule> copy) {
        this.compiledRules = List.copyOf(compiledRules);
        this.factNameChecks = Collections.unmodifiableMap(new LinkedHashMap<>(factChecks));
        this.copy = copy;
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
     * Takes a copy of the rules that no other run is using, making a new one if necessary. A copy that fails to be
     * made isn't kept.
     *
     * @return A copy for the caller alone, to give back with {@link #release(List)}
     */
    List<CompiledRule> borrow() {
        List<CompiledRule> borrowed = idle.poll();
        return borrowed != null ? borrowed : compiledRules.stream().map(copy).toList();
    }

    /**
     * Gives back a copy taken with {@link #borrow()}, so a later run can reuse it.
     *
     * @param borrowed The copy, which the caller must no longer use
     */
    void release(List<CompiledRule> borrowed) {
        idle.add(borrowed);
    }
}
