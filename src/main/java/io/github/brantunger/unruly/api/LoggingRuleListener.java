package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * An out-of-the-box {@link RuleListener} that logs lifecycle events via SLF4J at the DEBUG level.
 * Useful for auditing or tracing which rules evaluate to true and when their actions execute.
 */
public class LoggingRuleListener implements RuleListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingRuleListener.class);

    /** Names a rule the way the engine's error messages do, so an unnamed rule isn't logged as {@code null}. */
    private static String nameOf(Rule rule) {
        return rule.getRuleName() != null ? rule.getRuleName() : "(unnamed)";
    }

    @Override
    public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
        log.debug("Evaluating condition for rule: {}", nameOf(rule));
    }

    @Override
    public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
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
