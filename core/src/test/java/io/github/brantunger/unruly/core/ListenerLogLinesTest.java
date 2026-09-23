package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.LoggingRuleListener;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
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
    @DisplayName("the WARN line names the root cause a listener's exception hides when it has no message")
    void listenerExceptionNamesHiddenCause() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        throw new IllegalStateException((String) null, new IOException("disk full"));
                    }
                }).build();
        engine.load(List.of(rule("r", "true")));

        String logs = logsOf(() -> assertEquals(Map.of("k", 1), engine.run(new FactMap<>())));

        assertTrue(logs.lines().anyMatch(line -> line.endsWith("WARN " + ENGINE_LOGGER
                + "Listener threw exception in beforeRun: java.lang.IllegalStateException"
                + " (caused by java.io.IOException: disk full)")), logs);
    }

    /** A fact whose getter fails with an exception that has no message, so its cause is hidden. */
    public static final class Broken {

        private final Exception cause;

        Broken(Exception cause) {
            this.cause = cause;
        }

        /**
         * Throws an exception with no message, caused by the one this fact was given.
         *
         * @return never
         */
        public boolean getX() {
            throw new IllegalStateException((String) null, cause);
        }
    }

    @ParameterizedTest(name = "root cause message {0}")
    @ValueSource(strings = {"disk full", ""})
    @DisplayName("the WARN line for a nested run() a listener started names the hidden cause once")
    void nestedRunCauseNamedOnce(String rootMessage) {
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        inner.load(List.of(rule("inner", "broken.x")));
        IOException root = rootMessage.isEmpty() ? new IOException() : new IOException(rootMessage);
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        FactStore<Object> facts = new FactMap<>();
                        facts.setValue("broken", new Broken(root));
                        inner.run(facts);
                    }
                }).build();
        engine.load(List.of(rule("r", "true")));

        String logs = logsOf(() -> assertEquals(Map.of("k", 1), engine.run(new FactMap<>())));

        String warn = logs.lines().filter(line -> line.contains("Listener threw exception in beforeRun: "))
                .findFirst().orElseThrow(() -> new AssertionError(logs));
        assertEquals(1, warn.split("caused by java.io.IOException", -1).length - 1, warn);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"wrapped", "wrapped without a message", "shortened"})
    @DisplayName("the WARN line names the hidden cause once when a nested run()'s failure is wrapped or shortened")
    void wrappedOrShortenedNestedRunCauseNamedOnce(String how) {
        String message = "shortened".equals(how) ? "m".repeat(1100) : "audit";
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((action, session) -> {
                    throw new IllegalStateException(message, new IOException());
                })).build();
        inner.load(List.of(Rule.builder().ruleName("inner").condition("c").action("a").build()));
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        try {
                            inner.run(new FactMap<>());
                        } catch (RuleExecutionException nested) {
                            throw switch (how) {
                                case "wrapped" -> new IllegalStateException("audit failed", nested);
                                case "wrapped without a message" -> new IllegalStateException((String) null, nested);
                                default -> nested;
                            };
                        }
                    }
                }).build();
        engine.load(List.of(rule("r", "true")));

        String logs = logsOf(() -> assertEquals(Map.of("k", 1), engine.run(new FactMap<>())));

        String warn = logs.lines().filter(line -> line.contains("Listener threw exception in beforeRun: "))
                .findFirst().orElseThrow(() -> new AssertionError(logs));
        assertEquals(1, warn.split("caused by java.io.IOException", -1).length - 1, warn);
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
