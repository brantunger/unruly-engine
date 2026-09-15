package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * slf4j-simple writes to whatever {@link System#err} is at the time of each call, and the build enables DEBUG for
 * this listener's logger, so its messages can be captured here.
 */
@DisplayName("LoggingRuleListener rule names")
class LoggingRuleListenerNameTest {

    private static final String LISTENER_LOGGER = "io.github.brantunger.unruly.api.LoggingRuleListener - ";

    private static String logsOf(Rule rule) {
        return logsOf(rule, true);
    }

    private static String logsOf(Rule rule, boolean matchResult) {
        LoggingRuleListener listener = new LoggingRuleListener();
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            listener.beforeEvaluate(rule, Map.of());
            listener.afterEvaluate(rule, Map.of(), matchResult);
            listener.beforeExecute(rule, new Object());
            listener.afterExecute(rule, new Object());
            listener.onError(rule, new RuleExecutionException("boom"));
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an unnamed rule is logged as (unnamed), like the engine's error messages")
    void unnamedRule() {
        String logs = logsOf(Rule.builder().condition("true").action("x").build());

        assertTrue(logs.contains("Evaluating condition for rule: (unnamed)"), logs);
        assertTrue(logs.contains("Evaluated condition for rule: (unnamed) | Match: true"), logs);
        assertTrue(logs.contains("Executing action for rule: (unnamed)"), logs);
        assertTrue(logs.contains("Executed action for rule: (unnamed)"), logs);
        assertTrue(logs.contains("Failed rule: (unnamed) | Error: boom"), logs);
        assertFalse(logs.contains("rule: null"), logs);
    }

    @Test
    @DisplayName("a named rule is logged by its name")
    void namedRule() {
        String logs = logsOf(Rule.builder().ruleName("claim-rule").condition("true").action("x").build());

        assertTrue(logs.contains("Evaluating condition for rule: claim-rule"), logs);
        assertTrue(logs.contains("Failed rule: claim-rule | Error: boom"), logs);
    }

    @Test
    @DisplayName("every callback is logged at DEBUG, and a rule that didn't match is logged with Match: false")
    void debugLevelAndMatchResult() {
        String logs = logsOf(Rule.builder().ruleName("r").condition("false").action("x").build(), false);

        assertTrue(logs.contains("DEBUG " + LISTENER_LOGGER + "Evaluating condition for rule: r"), logs);
        assertTrue(logs.contains("DEBUG " + LISTENER_LOGGER + "Evaluated condition for rule: r | Match: false"), logs);
        assertTrue(logs.contains("DEBUG " + LISTENER_LOGGER + "Executing action for rule: r"), logs);
        assertTrue(logs.contains("DEBUG " + LISTENER_LOGGER + "Executed action for rule: r"), logs);
        assertTrue(logs.contains("DEBUG " + LISTENER_LOGGER + "Failed rule: r | Error: boom"), logs);
    }
}
