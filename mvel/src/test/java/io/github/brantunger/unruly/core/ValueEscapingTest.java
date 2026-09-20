package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.LoggingRuleListener;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLoggingTest.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A message the engine logs carries text the engine didn't write: a language quotes the fact values a failing
 * expression read, and a language that rejects a fact name quotes the name. Escaping only the names the engine
 * itself puts in a message left both of those able to start a log line of their own.
 */
@DisplayName("text the engine didn't write is escaped too, so a fact value can't forge a log line")
class ValueEscapingTest {

    private static final String FORGED = "[main] INFO com.example.Audit - forged entry";

    /** A language that rejects every fact name, quoting the name the way {@code docs/languages/custom.md} shows. */
    private record PickyLanguage() implements ExpressionLanguage {

        @Override
        public String name() {
            return "picky";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }

                @Override
                public void checkFactName(String name) {
                    throw new IllegalArgumentException("'" + name + "' is not a valid picky fact name");
                }
            };
        }
    }

    private static List<String> lines(String logs) {
        return logs.lines().toList();
    }

    private static void assertNoForgedLine(String logs) {
        assertTrue(lines(logs).stream().noneMatch(line -> line.startsWith("[main] INFO com.example")), logs);
    }

    @Test
    @DisplayName("a fact value with a line break is escaped in the engine's message and in what it logs")
    void factValueWithLineBreak() {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(Rule.builder().ruleName("ssn-check").condition("Integer.parseInt(ssn) > 0")
                .action("output.put('ok', true)").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("ssn", "1\n" + FORGED);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(RuntimeException.class, () -> engine.run(facts))));

        String message = thrown.get().getMessage();
        assertFalse(message.contains("\n"), "the engine's own message still carries a line break: " + message);
        assertTrue(message.contains("\\n" + FORGED), message);
        assertTrue(lines(logs).stream().anyMatch(line -> line.contains("ERROR " + ENGINE_LOGGER + message)), logs);
        assertNoForgedLine(logs);
    }

    @Test
    @DisplayName("the language's own exception still reads exactly as the language wrote it")
    void theCauseIsUnchanged() {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(Rule.builder().ruleName("ssn-check").condition("Integer.parseInt(ssn) > 0")
                .action("output.put('ok', true)").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("ssn", "1\n" + FORGED);

        Throwable thrown = assertThrows(RuntimeException.class, () -> engine.run(facts));

        assertNotNull(thrown.getCause(), "the language's exception is the cause");
        assertTrue(thrown.getCause().getMessage().contains("1\n" + FORGED),
                "the cause's message was changed: " + thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("LoggingRuleListener logs a failed rule's message on one line")
    void listenerEscapesTheMessage() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .listener(new LoggingRuleListener()).build();
        engine.load(List.of(Rule.builder().ruleName("ssn-check").condition("Integer.parseInt(ssn) > 0")
                .action("output.put('ok', true)").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("ssn", "1\n" + FORGED);

        String logs = logsOf(() -> assertThrows(RuntimeException.class, () -> engine.run(facts)));

        assertTrue(lines(logs).stream().anyMatch(line -> line.contains("Failed rule: ssn-check | Error: ")), logs);
        assertNoForgedLine(logs);
    }

    @Test
    @DisplayName("a language that rejects a fact name with a line break can't forge a line either")
    void rejectedFactNameWithLineBreak() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new PickyLanguage()).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("a\n" + FORGED, 1);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(IllegalArgumentException.class, () -> engine.run(facts))));

        assertTrue(lines(logs).stream()
                        .anyMatch(line -> line.contains("ERROR " + ENGINE_LOGGER + "'a\\n" + FORGED
                                + "' is not a valid picky fact name")),
                logs);
        assertNoForgedLine(logs);
        // The language's exception is thrown as it came, so a caller still reads exactly what the language said.
        assertEquals("'a\n" + FORGED + "' is not a valid picky fact name", thrown.get().getMessage());
    }

    @Test
    @DisplayName("a message is shortened before it's escaped, so the count counts the language's own characters")
    void shortenedBeforeEscaping() {
        String text = "\n".repeat(Failures.MAX_DESCRIPTION_LENGTH + 5);

        String described = Failures.describe(new IllegalStateException(text));

        assertEquals("\\n".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (5 more characters)", described);
    }
}
