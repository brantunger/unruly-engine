package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run as its own JVM by {@link SyntaxValidationTest}, with assertions on and {@code -XX:-StackTraceInThrowable}:
 * prints the length of a new exception's stack trace, then, for the actions {@code int in = 1}, which MVEL rejects
 * with a plain {@link RuntimeException}, and {@code b = = 1}, which trips an assert inside MVEL, the message
 * {@code load()} gives, its issues and the class of its cause.
 */
final class NoStackTracesScenario {

    static final String MESSAGE = "MESSAGE ";

    private NoStackTracesScenario() {
    }

    public static void main(String[] args) {
        System.out.println(MESSAGE + new RuntimeException().getStackTrace().length);
        for (String action : List.of("int in = 1", "b = = 1")) {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                    .build();
            try {
                engine.load(List.of(Rule.builder().ruleName("syntax").condition("true").action(action).build()));
                System.out.println(MESSAGE + "accepted");
            } catch (RuleCompilationException e) {
                System.out.println(MESSAGE + e.getMessage());
                System.out.println(MESSAGE + e.issues());
                System.out.println(MESSAGE + e.getCause().getClass().getName());
            }
        }
    }
}
