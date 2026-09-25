package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

// Public, as is the fact class: MVEL's reflective accessors need to reach its method.
@DisplayName("error messages name the root cause and stay short")
public class ErrorMessageCauseTest {

    public static class Boom {
        public int fail() {
            throw new IllegalStateException("x".repeat(10_000));
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static int count(String text, String part) {
        Matcher matcher = Pattern.compile(Pattern.quote(part)).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    @Test
    @DisplayName("an exception without a message from a method the rule called is named by its class")
    void rootCauseNamed() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(Map::of);
        engine.load(List.of(rule("a", "true", "output.put('k', null)")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        // What put threw is the cause, without MVEL's exceptions over it, so their [Error: ...] text isn't there;
        // FailureReportingTest names a messageless root cause under them.
        assertEquals("Failed to execute action for rule 'a': java.lang.UnsupportedOperationException",
                ex.getMessage());
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }

    @Test
    @DisplayName("a failure five runs deep is described once and logged once")
    void nestedRunFailure() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(rule("rec", "true",
                "if (depth < 5) { store.setValue('depth', depth + 1); eng.run(store) } else { x.missing }")));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("eng", engine);
        facts.setValue("store", facts);
        facts.setValue("depth", 1);
        facts.setValue("x", 1);
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(assertThrows(RuleExecutionException.class, () -> engine.run(facts))));
        String message = thrown.get().getMessage();

        assertTrue(message.startsWith("Failed to execute action for rule 'rec': a nested run() failed: "
                + "Failed to execute action for rule 'rec': [Error: could not access: missing"), message);
        assertEquals(2, count(message, "Failed to execute action for rule 'rec'"), message);
        assertEquals(1, count(logs, "ERROR io.github.brantunger.unruly.engine"), logs);
    }

    @Test
    @DisplayName("a failure 150 runs deep is described once too, although each run adds a link to the cause chain")
    void deeplyNestedRunFailure() throws InterruptedException {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(rule("rec", "true",
                "if (depth < 150) { store.setValue('depth', depth + 1); eng.run(store) } else { x.missing }")));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("eng", engine);
        facts.setValue("store", facts);
        facts.setValue("depth", 1);
        facts.setValue("x", 1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        // Each nested run takes more stack than a test thread has to spare for 150 of them.
        Thread deep = new Thread(null, () -> {
            try {
                engine.run(facts);
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        }, "large-stack", 1L << 29);
        // A daemon joined with a bound, and interrupted on the way out, so a hang fails the test instead.
        deep.setDaemon(true);
        deep.start();
        try {
            deep.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(deep.isAlive(), "the nested runs didn't finish");
        } finally {
            deep.interrupt();
        }

        String message = assertInstanceOf(RuleExecutionException.class, thrown.get()).getMessage();
        assertTrue(message.startsWith("Failed to execute action for rule 'rec': a nested run() failed: "
                + "Failed to execute action for rule 'rec': [Error: could not access: missing"), message);
        assertEquals(2, count(message, "Failed to execute action for rule 'rec'"), message);
    }

    @Test
    @DisplayName("a condition too long to compile gets a short message")
    void expressionTooLongToCompile() throws InterruptedException {
        String condition = String.join(" || ", Collections.nCopies(20_000, "a == 1"));
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        // A small stack makes MVEL's recursive parser overflow however much stack the test JVM gives its threads.
        Thread compiler = new Thread(null, () -> {
            try {
                engine.load(List.of(rule("long", condition, "output.put('k', 1)")));
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        }, "small-stack", 256 * 1024);
        compiler.start();
        compiler.join();

        RuleCompilationException ex = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("Condition for rule 'long' failed to compile: the expression is too long or too deeply nested to "
                + "compile",
                ex.getMessage());
    }

    @Test
    @DisplayName("a very long exception message is cut short")
    void longMessageCut() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(rule("a", "true", "output.put('k', boom.fail())")));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("boom", new Boom());

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));

        assertTrue(ex.getMessage().length() < Failures.MAX_DESCRIPTION_LENGTH + 100, ex.getMessage());
        assertTrue(ex.getMessage().matches("(?s).*\\.\\.\\. \\(\\d+ more characters\\)$"), ex.getMessage());
    }

    @Test
    @DisplayName("a cause chain that loops back on itself is described without hanging")
    void causeLoop() {
        IllegalStateException inner = new IllegalStateException();
        RuntimeException outer = new RuntimeException("outer", inner);
        inner.initCause(outer);

        assertEquals("outer (caused by java.lang.IllegalStateException)", Failures.describe(outer));
    }
}
