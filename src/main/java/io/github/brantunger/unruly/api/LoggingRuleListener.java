package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * An out-of-the-box {@link RuleListener} that logs lifecycle events via SLF4J at the DEBUG level.
 * Useful for auditing or tracing which rules evaluate to true and when their actions execute.
 *
 * <p>
 * Rule names are logged as the engine's error messages show them: line breaks and other control characters are
 * escaped, so a name can't start a log line of its own, and a name longer than 200 characters is shortened.
 * </p>
 */
public class LoggingRuleListener implements RuleListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingRuleListener.class);

    // The same limit as the engine's messages.
    private static final int MAX_NAME_LENGTH = 200;

    /** Names a rule the way the engine's error messages do, so an unnamed rule isn't logged as {@code null}. */
    private static String nameOf(Rule rule) {
        return rule.getRuleName() != null ? quote(rule.getRuleName()) : "(unnamed)";
    }

    /** Escapes and shortens a name as the engine's {@code core.Failures.quote} does. */
    private static String quote(String name) {
        int shown = Math.min(name.length(), MAX_NAME_LENGTH);
        StringBuilder quoted = new StringBuilder(shown);
        for (int i = 0; i < shown; i++) {
            char c = name.charAt(i);
            switch (c) {
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    int type = Character.getType(c);
                    if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        if (name.length() > MAX_NAME_LENGTH) {
            quoted.append("... (").append(name.length() - MAX_NAME_LENGTH).append(" more characters)");
        }
        return quoted.toString();
    }

    @Override
    public void beforeEvaluate(Rule rule, Map<String, @Nullable Object> facts) {
        log.debug("Evaluating condition for rule: {}", nameOf(rule));
    }

    @Override
    public void afterEvaluate(Rule rule, Map<String, @Nullable Object> facts, boolean matchResult) {
        log.debug("Evaluated condition for rule: {} | Match: {}", nameOf(rule), matchResult);
    }

    @Override
    public void beforeExecute(Rule rule, Object output) {
        log.debug("Executing action for rule: {}", nameOf(rule));
    }

    @Override
    public void afterExecute(Rule rule, Object output) {
        log.debug("Executed action for rule: {}", nameOf(rule));
    }

    @Override
    public void onError(Rule rule, RuleExecutionException error) {
        log.debug("Failed rule: {} | Error: {}", nameOf(rule), error.getMessage());
    }
}
