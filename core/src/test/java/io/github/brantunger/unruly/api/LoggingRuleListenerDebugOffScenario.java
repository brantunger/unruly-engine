package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Run by {@link LoggingRuleListenerDebugOffTest} in a JVM whose SLF4J logs are off: calls each of the listener's
 * callbacks with a {@code null} rule, which only naming the rule would touch, and prints {@link #DONE} followed by the
 * callbacks that touched it, if any.
 */
final class LoggingRuleListenerDebugOffScenario {

    static final String DONE = "SCENARIO callbacks done; touched the rule:";
    static final String DEBUG_ON = "SCENARIO DEBUG is on";

    private LoggingRuleListenerDebugOffScenario() {
    }

    public static void main(String[] args) {
        if (LoggerFactory.getLogger(LoggingRuleListener.class).isDebugEnabled()) {
            System.out.println(DEBUG_ON);
            return;
        }
        LoggingRuleListener listener = new LoggingRuleListener();
        Rule rule = null;
        Map<String, Object> facts = Map.of();
        List<String> touched = new ArrayList<>();
        call(touched, "beforeEvaluate", () -> listener.beforeEvaluate(rule, facts));
        call(touched, "afterEvaluate", () -> listener.afterEvaluate(rule, facts, true));
        call(touched, "beforeExecute", () -> listener.beforeExecute(rule, facts));
        call(touched, "afterExecute", () -> listener.afterExecute(rule, facts));
        call(touched, "onError", () -> listener.onError(rule, new RuleExecutionException("failed", null, "r")));
        System.out.println(DONE + " " + touched);
    }

    private static void call(List<String> touched, String callback, Runnable call) {
        try {
            call.run();
        } catch (NullPointerException e) {
            touched.add(callback);
        }
    }
}
