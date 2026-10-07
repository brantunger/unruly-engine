package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;

import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Builds a {@link RuleSet} for tests in this package that make one by hand rather than load it into an engine. A test
 * sets only the parts it cares about; the others keep the defaults below, so a part added to the rule set needs a
 * default here and no change to the tests.
 *
 * <p>
 * The defaults: no copy limit, new permits for the limit, shared with no other rule set, the default stall window, a
 * new idle queue, no fact names read and none reserved.
 * </p>
 */
final class TestRuleSets {

    private final List<CompiledRule> rules;
    private final Map<String, ExpressionCompiler> compilers;
    private CopyLimit limit = CopyLimit.none();
    private CopyPermits permits;
    private long stallWindowMillis = RuleSet.STALL_WINDOW_MILLIS;
    private Queue<Map<String, Session>> idle;
    private Map<String, String> reservedFactNames = Map.of();

    private TestRuleSets(List<CompiledRule> rules, Map<String, ExpressionCompiler> compilers) {
        this.rules = rules;
        this.compilers = compilers;
    }

    /**
     * Starts a rule set of the given rules and compilers, with every other part at its default.
     *
     * @param rules     The compiled rules, in the order they run
     * @param compilers The compilers of the languages the rules use, by language name
     * @return The builder
     */
    static TestRuleSets ruleSet(List<CompiledRule> rules, Map<String, ExpressionCompiler> compilers) {
        return new TestRuleSets(rules, compilers);
    }

    TestRuleSets withLimit(CopyLimit limit) {
        this.limit = limit;
        return this;
    }

    TestRuleSets withPermits(CopyPermits permits) {
        this.permits = permits;
        return this;
    }

    TestRuleSets withStallWindow(long millis) {
        this.stallWindowMillis = millis;
        return this;
    }

    TestRuleSets withIdle(Queue<Map<String, Session>> idle) {
        this.idle = idle;
        return this;
    }

    TestRuleSets withReservedFactNames(Map<String, String> reservedFactNames) {
        this.reservedFactNames = reservedFactNames;
        return this;
    }

    /**
     * Creates the rule set.
     *
     * @return The rule set
     * @throws IllegalStateException if both an idle queue and reserved names were set, which no constructor but the
     *                               private one takes together
     */
    RuleSet build() {
        if (idle != null && !reservedFactNames.isEmpty()) {
            throw new IllegalStateException("no RuleSet constructor takes both an idle queue and reserved names");
        }
        CopyPermits givenPermits = permits != null ? permits : new CopyPermits(limit.maxCopies());
        if (idle != null) {
            return new RuleSet(rules, compilers, limit, givenPermits, stallWindowMillis, idle);
        }
        return new RuleSet(rules, compilers, Map.of(), reservedFactNames, Map.of(), limit, givenPermits,
                stallWindowMillis);
    }
}
