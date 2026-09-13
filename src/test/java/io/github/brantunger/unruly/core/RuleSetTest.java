package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleSet lends each run its own copy of the compiled rules")
class RuleSetTest {

    /** A compiled expression that is never run, named so a test can tell copies apart. */
    private record Stub(String name) implements CompiledCondition, CompiledAction {
        @Override
        public Object evaluate(EvaluationContext context) {
            throw new AssertionError("not run");
        }

        @Override
        public void execute(ActionContext context) {
            throw new AssertionError("not run");
        }

        @Override
        public Stub copy() {
            throw new AssertionError("not copied");
        }
    }

    private static final CompiledRule RULE = new CompiledRule(
            Rule.builder().ruleName("r").condition("true").action("1").build(), "r", new Stub("condition"),
            new Stub("action"));

    @Test
    @DisplayName("a copy in use is never lent twice, and a copy given back is reused without compiling")
    void copiesLentOneAtATime() {
        AtomicInteger compiles = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), rule -> {
            compiles.incrementAndGet();
            return new CompiledRule(rule.rule(), rule.displayName(), new Stub("condition copy"),
                    new Stub("action copy"));
        });

        List<CompiledRule> first = rules.borrow();
        List<CompiledRule> second = rules.borrow();

        assertSame(rules.rules(), first, "the rules compiled by setRuleList are lent first");
        assertEquals(new Stub("condition copy"), second.get(0).compiledCondition());
        assertEquals(1, compiles.get());

        rules.release(second);

        assertSame(second, rules.borrow());
        assertEquals(1, compiles.get());
    }
}
