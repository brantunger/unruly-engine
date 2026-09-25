package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run as its own JVM by {@link SyntaxValidationTest}, with assertions off: prints whether MVEL's asserts are on, then
 * the messages {@code load()} and {@code validate()} give for the action {@code b = = 1}.
 */
final class AssertionsOffScenario {

    static final String MESSAGE = "MESSAGE ";

    private AssertionsOffScenario() {
    }

    public static void main(String[] args) {
        System.out.println(MESSAGE + org.mvel2.ast.OperatorNode.class.desiredAssertionStatus());
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        List<Rule> rules = List.of(Rule.builder().ruleName("syntax").condition("true").action("b = = 1").build());
        try {
            engine.load(rules);
            System.out.println(MESSAGE + "accepted");
        } catch (RuleCompilationException e) {
            System.out.println(MESSAGE + e.getMessage());
        }
        engine.validate(rules).forEach(e -> System.out.println(MESSAGE + e.getMessage()));
    }
}
