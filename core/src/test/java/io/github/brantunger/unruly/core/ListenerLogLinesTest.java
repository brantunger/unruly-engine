package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.LoggingRuleListener;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A listener's exception is logged at WARN with its message escaped, like every message the engine logs, and its stack
 * trace at DEBUG. {@link LoggingRuleListener} logs a run stopped during a rule as a stop, not as the rule failing.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("listener log lines")
class ListenerLogLinesTest {

    private static final String LISTENER_LOGGER = "io.github.brantunger.unruly.api.LoggingRuleListener - ";

    /** A fact a condition calls to take longer than a short timeout. */
    public static final class Pause {

        /**
         * Sleeps, then answers {@code true}. It is a getter, so a rule reads it as the property {@code pause.longer}.
         *
         * @return {@code true}
         * @throws InterruptedException if the thread is interrupted while sleeping
         */
        public boolean getLonger() throws InterruptedException {
            Thread.sleep(600);
            return true;
        }
    }

    private static Rule rule(String name, String condition) {
        return Rule.builder().ruleName(name).condition(condition).action("put k 1").build();
    }

    @Test
    @DisplayName("a listener's message is escaped on the WARN line, and its stack trace is logged at DEBUG")
    void listenerExceptionIsEscaped() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matched) {
                        throw new IllegalStateException("bad\nWARN forged line");
                    }
                }).build();
        engine.load(List.of(rule("r", "true")));

        String logs = logsOf(() -> assertEquals(Map.of("k", 1), engine.run(new FactMap<>())));

        List<String> lines = logs.lines().toList();
        int warn = lines.indexOf(lines.stream().filter(line -> line.contains("WARN " + ENGINE_LOGGER)).findFirst()
                .orElseThrow(() -> new AssertionError(logs)));
        assertTrue(lines.get(warn).endsWith("WARN " + ENGINE_LOGGER + "Listener threw exception in afterEvaluate: "
                + "java.lang.IllegalStateException: bad\\nWARN forged line"), logs);
        assertTrue(warn + 1 < lines.size() && lines.get(warn + 1).contains("DEBUG " + ENGINE_LOGGER
                + "Listener threw exception in afterEvaluate"), "the WARN line was followed by: " + logs);
        assertEquals("java.lang.IllegalStateException: bad", warn + 2 < lines.size() ? lines.get(warn + 2) : null,
                "the DEBUG line has no stack trace: " + logs);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("\tat ")), "no stack frames: " + logs);
    }

    @Test
    @DisplayName("LoggingRuleListener logs a rule the run stopped in as stopped")
    void stoppedRule() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).runTimeout(Duration.ofMillis(200))
                .listener(new LoggingRuleListener()).build();
        engine.load(List.of(rule("slow", "pause.longer")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("pause", new Pause());

        String logs = logsOf(() -> assertThrows(RuleExecutionException.class, () -> engine.run(facts)));

        assertTrue(logs.contains("DEBUG " + LISTENER_LOGGER + "Stopped rule: slow | run() passed its deadline of "),
                logs);
        assertFalse(logs.contains("Failed rule: slow"), logs);
    }

    @Test
    @DisplayName("LoggingRuleListener tells a stop from a failure by the rule name and the cause")
    void stopOrFailure() {
        LoggingRuleListener listener = new LoggingRuleListener();
        Rule rule = rule("r", "true");

        String logs = logsOf(() -> {
            listener.onError(rule, new RuleExecutionException("interrupted", new InterruptedException()));
            listener.onError(rule, new RuleExecutionException("timed out", new TimeoutException()));
            listener.onError(rule, new RuleExecutionException("the rule's own", new TimeoutException(), "r"));
            listener.onError(rule, new RuleExecutionException("no cause"));
        });

        assertTrue(logs.contains("Stopped rule: r | interrupted"), logs);
        assertTrue(logs.contains("Stopped rule: r | timed out"), logs);
        assertTrue(logs.contains("Failed rule: r | Error: the rule's own"), logs);
        assertTrue(logs.contains("Failed rule: r | Error: no cause"), logs);
    }
}
