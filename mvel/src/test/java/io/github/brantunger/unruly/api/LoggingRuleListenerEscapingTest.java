package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LoggingRuleListener escapes and shortens rule names, as the engine's messages do")
class LoggingRuleListenerEscapingTest {

    private static String logsOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a rule name with line breaks and control characters is logged on one line, in every callback")
    void lineBreaksEscaped() {
        LoggingRuleListener listener = new LoggingRuleListener();
        Rule rule = Rule.builder().ruleName("r\n[main] INFO forged\r\tx" + (char) 0x2028 + (char) 1 + (char) 0x2029)
                .condition("true").action("x").build();

        String logs = logsOf(() -> {
            listener.beforeEvaluate(rule, Map.of());
            listener.afterEvaluate(rule, Map.of(), true);
            listener.beforeExecute(rule, new Object());
            listener.afterExecute(rule, new Object());
            listener.onError(rule, new RuleExecutionException("failed"));
        });

        String escaped = "r\\n[main] INFO forged\\r\\tx\\u2028\\u0001\\u2029";
        assertEquals(5, logs.lines().count(), logs);
        assertEquals(5, logs.lines().filter(line -> line.contains(escaped)).count(), logs);
    }

    @Test
    @DisplayName("a rule name over 200 characters is shortened")
    void longNameShortened() {
        Rule rule = Rule.builder().ruleName("n".repeat(250)).condition("true").action("x").build();

        String logs = logsOf(() -> new LoggingRuleListener().beforeExecute(rule, new Object()));

        assertTrue(logs.contains("Executing action for rule: " + "n".repeat(200) + "... (50 more characters)"), logs);
    }
}
