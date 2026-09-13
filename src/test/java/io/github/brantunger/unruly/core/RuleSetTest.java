package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleSet lends each run its own copy of the compiled rules")
class RuleSetTest {

    private static final CompiledRule RULE = new CompiledRule(
            Rule.builder().ruleName("r").condition("true").action("1").build(), "r", "condition", "action");

    @Test
    @DisplayName("a copy in use is never lent twice, and a copy given back is reused without compiling")
    void copiesLentOneAtATime() {
        AtomicInteger compiles = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), rule -> {
            compiles.incrementAndGet();
            return new CompiledRule(rule.rule(), rule.displayName(), "condition copy", "action copy");
        });

        List<CompiledRule> first = rules.borrow();
        List<CompiledRule> second = rules.borrow();

        assertSame(rules.rules(), first, "the rules compiled by setRuleList are lent first");
        assertEquals("condition copy", second.get(0).compiledCondition());
        assertEquals(1, compiles.get());

        rules.release(second);

        assertSame(second, rules.borrow());
        assertEquals(1, compiles.get());
    }
}
