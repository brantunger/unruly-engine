package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLoggingTest.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("names in the engine's messages are escaped and shortened, so they can't forge log lines")
class NameEscapingTest {

    private static final String FORGED = "[main] INFO com.example.Audit - forged entry";

    private final RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);

    /** The exception {@code action} throws, and the engine's log lines while it ran. */
    private record Failure(Throwable thrown, List<String> logLines) {
    }

    private static Failure failure(Executable action) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(RuntimeException.class, action)));
        return new Failure(thrown.get(), Arrays.asList(logs.split("\\R")));
    }

    private static void assertNoForgedLine(Failure failure) {
        assertTrue(failure.logLines().stream().noneMatch(line -> line.startsWith("[main] INFO com.example")),
                String.join("\n", failure.logLines()));
    }

    @Test
    @DisplayName("a fact name with \\n or \\r\\n is logged on one line, with the line break escaped")
    void factNameWithLineBreak() {
        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
        for (String lineBreak : List.of("\n", "\r\n")) {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("a" + lineBreak + FORGED, 1);

            Failure failure = failure(() -> engine.run(facts));

            String escaped = lineBreak.equals("\n") ? "a\\n" : "a\\r\\n";
            assertEquals("'" + escaped + FORGED + "' is not a valid fact name: rules can only refer to a fact named "
                    + "with a Java identifier", failure.thrown().getMessage());
            assertTrue(failure.logLines().stream()
                            .anyMatch(line -> line.endsWith("ERROR " + ENGINE_LOGGER + failure.thrown().getMessage())),
                    String.join("\n", failure.logLines()));
            assertNoForgedLine(failure);
        }
    }

    @Test
    @DisplayName("a 10,000-character fact name is shortened in the message")
    void longFactName() {
        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("-".repeat(10_000), 1);

        Failure failure = failure(() -> engine.run(facts));

        assertEquals("'" + "-".repeat(Failures.MAX_NAME_LENGTH) + "... (9800 more characters)' is not a valid fact "
                + "name: rules can only refer to a fact named with a Java identifier", failure.thrown().getMessage());
    }

    @Test
    @DisplayName("a rule name with a line break is escaped in run-time and compile-time failures")
    void ruleNameWithLineBreak() {
        engine.setRuleList(List.of(Rule.builder().ruleName("bad\n" + FORGED).condition("missing > 1").action("x")
                .build()));
        Failure run = failure(() -> engine.run(new FactMap<>()));
        assertTrue(run.thrown().getMessage().startsWith("Failed to evaluate condition for rule 'bad\\n" + FORGED
                + "': "), run.thrown().getMessage());
        assertNoForgedLine(run);

        Failure duplicate = failure(() -> engine.setRuleList(List.of(
                Rule.builder().ruleName("dup\r\n" + FORGED).condition("true").action("x").build(),
                Rule.builder().ruleName("dup\r\n" + FORGED).condition("true").action("x").build())));
        assertInstanceOf(RuleCompilationException.class, duplicate.thrown());
        assertEquals("Duplicate rule name 'dup\\r\\n" + FORGED + "'", duplicate.thrown().getMessage());
        assertNoForgedLine(duplicate);

        Failure blank = failure(() -> engine.setRuleList(List.of(
                Rule.builder().ruleName("blank\n" + FORGED).condition(" ").action("x").build())));
        assertEquals("Rule 'blank\\n" + FORGED + "' has a blank condition expression",
                blank.thrown().getMessage());

        Failure language = failure(() -> engine.setRuleList(List.of(Rule.builder().ruleName("r")
                .language("lang\n" + FORGED).condition("true").action("x").build())));
        assertTrue(language.thrown().getMessage().startsWith("Rule 'r' is written in 'lang\\n" + FORGED + "', "),
                language.thrown().getMessage());
        assertNoForgedLine(language);
    }

    @Test
    @DisplayName("quote escapes control characters and line separators, and shortens names over the limit")
    void quote() {
        String controls = "a\tb" + (char) 0 + "c" + (char) 0x2028 + "d" + (char) 0x2029 + "e" + (char) 0x7f + "f"
                + (char) 0x85;

        assertEquals("a\\tb\\u0000c\\u2028d\\u2029e\\u007ff\\u0085", Failures.quote(controls));
        assertEquals("plain-name_1 é", Failures.quote("plain-name_1 é"));
        String limit = "x".repeat(Failures.MAX_NAME_LENGTH);
        assertEquals(limit, Failures.quote(limit));
        assertEquals(limit + "... (1 more characters)", Failures.quote(limit + "y"));
    }

    @Test
    @DisplayName("the rule name on the exception stays unescaped")
    void exceptionKeepsRawName() {
        engine.setRuleList(List.of(Rule.builder().ruleName("raw\nname").condition("missing > 1").action("x").build()));

        Failure failure = failure(() -> engine.run(new FactMap<>()));

        assertEquals("raw\nname", assertInstanceOf(RuleExecutionException.class, failure.thrown()).getRuleName());
    }
}
