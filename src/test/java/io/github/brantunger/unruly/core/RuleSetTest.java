package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    @DisplayName("the compiled rules are never lent; a copy in use is never lent twice, and one given back is reused")
    void copiesLentOneAtATime() throws InterruptedException {
        AtomicInteger copies = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of(), rule -> {
            int n = copies.incrementAndGet();
            return new CompiledRule(rule.rule(), rule.displayName(), new Stub("condition copy " + n),
                    new Stub("action copy " + n));
        });

        RuleSet.Copy first = rules.borrow();
        RuleSet.Copy second = rules.borrow();

        assertEquals(new Stub("condition copy 1"), first.rules().get(0).compiledCondition(),
                "the rules compiled by setRuleList are copied, not lent");
        assertEquals(new Stub("condition copy 2"), second.rules().get(0).compiledCondition());
        assertTrue(first.kept() && second.kept(), "without a limit every copy is kept");
        assertEquals(List.of(RULE), rules.rules());
        assertEquals(RuleSet.UNLIMITED, rules.limit());

        rules.release(second);

        assertSame(second.rules(), rules.borrow().rules());
        assertEquals(2, copies.get());
    }

    @Test
    @DisplayName("the fact-name checks are kept with the rules they belong to")
    void factChecksKeptWithRules() {
        ExpressionCompiler check = new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(String source) {
                throw new AssertionError("not compiled");
            }

            @Override
            public CompiledAction compileAction(String source) {
                throw new AssertionError("not compiled");
            }
        };

        RuleSet rules = new RuleSet(List.of(RULE), Map.of("x", check), rule -> rule);

        assertEquals(Map.of("x", check), rules.factChecks());
    }
}
