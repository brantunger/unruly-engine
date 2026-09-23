package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.core.Failures;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * A {@link RuleListener} that logs each rule's callbacks through SLF4J at DEBUG: {@code beforeEvaluate},
 * {@code afterEvaluate}, {@code beforeExecute}, {@code afterExecute} and {@code onError}. It doesn't log the run
 * callbacks, {@code beforeRun}, {@code afterRun} and {@code onRunError}. The logger is named after this class,
 * {@code io.github.brantunger.unruly.api.LoggingRuleListener}.
 *
 * <p>
 * Rule names are logged as the engine's error messages show them: line breaks and other control characters are
 * escaped, so a name can't start a log line of its own, and a name longer than 200 characters is shortened. A failed
 * rule's message is escaped the same way and not shortened, because it carries text the engine didn't write, such as
 * the fact values a language quotes in its own message.
 * </p>
 *
 * <p>
 * <b>Threads:</b> it holds no state, so one instance is thread-safe and can be given to several engines.
 * </p>
 *
 * @see <a href=
 *      "https://github.com/brantunger/unruly-engine/blob/main/docs/listeners-and-logging.md#-loggingrulelistener">
 *      LoggingRuleListener in the listener guide</a>
 */
public class LoggingRuleListener implements RuleListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingRuleListener.class);

    /**
     * Creates a listener that logs through the SLF4J logger named after this class.
     */
    public LoggingRuleListener() {
        // Nothing to set up: the logger is shared by every instance.
    }

    /** Names a rule the way the engine's error messages do. */
    private static String nameOf(Rule rule) {
        return Failures.quote(rule.getRuleName());
    }

    @Override
    public void beforeEvaluate(Rule rule, Map<String, @Nullable Object> facts) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("Evaluating condition for rule: {}", nameOf(rule));
    }

    @Override
    public void afterEvaluate(Rule rule, Map<String, @Nullable Object> facts, boolean matchResult) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("Evaluated condition for rule: {} | Match: {}", nameOf(rule), matchResult);
    }

    @Override
    public void beforeExecute(Rule rule, Object output) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("Executing action for rule: {}", nameOf(rule));
    }

    @Override
    public void afterExecute(Rule rule, Object output) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("Executed action for rule: {}", nameOf(rule));
    }

    /**
     * Logs a rule's failure as {@code Failed rule: <name> | Error: <message>}, or, when the run stopped during the rule
     * because its thread was interrupted or it passed its deadline, as {@code Stopped rule: <name> | <message>}. A stop
     * is recognised as the engine documents it: no rule name, and an {@link InterruptedException} or a
     * {@link TimeoutException} as the cause.
     */
    @Override
    public void onError(Rule rule, RuleExecutionException error) {
        if (!log.isDebugEnabled()) {
            return;
        }
        // The engine's own messages arrive escaped, and escaping them again changes nothing; an engine of your own
        // may not have escaped its message, and its fact values are the ones most likely to come from request data.
        String message = error.getMessage();
        String escaped = message == null ? null : Failures.escape(message);
        if (isStop(error)) {
            log.debug("Stopped rule: {} | {}", nameOf(rule), escaped);
        } else {
            log.debug("Failed rule: {} | Error: {}", nameOf(rule), escaped);
        }
    }

    private static boolean isStop(RuleExecutionException error) {
        Throwable cause = error.getCause();
        return error.getRuleName() == null
                && (cause instanceof InterruptedException || cause instanceof TimeoutException);
    }
}
