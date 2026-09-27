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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A message the engine logs carries text the engine didn't write: a language quotes the fact values a failing
 * expression read, and a language that rejects a fact name quotes the name. Escaping only the names the engine
 * itself puts in a message left both of those able to start a log line of their own.
 */
// Public, as is Nested: MVEL's reflective accessors need to reach its method.
@DisplayName("text the engine didn't write is escaped too, so a fact value can't forge a log line")
public class ValueEscapingTest {

    private static final String FORGED = "[main] INFO com.example.Audit - forged entry";
    // The logger MVEL logs a value it fails to convert for a method to, through java.util.logging.
    private static final String MVEL_OPTIMIZER_LOGGER = "org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer";

    /** A fact whose method runs another engine, from inside the action that calls it. */
    public static class Nested {

        private final RulesEngine<Map<String, Object>> engine;
        private Map<String, Object> output;

        Nested(RulesEngine<Map<String, Object>> engine) {
            this.engine = engine;
        }

        public void run() {
            output = engine.run(new FactMap<>());
        }
    }

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

    /**
     * Runs a task and returns the records MVEL's optimizer logged meanwhile, as a handler on its logger sees them: a
     * handler only gets the records the logger's filter passes. They aren't passed on to the parent handlers, so a
     * record doesn't print its stack trace in the build.
     */
    private static List<LogRecord> mvelRecordsOf(Runnable task) {
        Logger logger = Logger.getLogger(MVEL_OPTIMIZER_LOGGER);
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        boolean useParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            task.run();
        } finally {
            logger.setUseParentHandlers(useParentHandlers);
            logger.removeHandler(handler);
        }
        return records;
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Runs a rule list, in a new engine so MVEL has built no accessor yet, with a String index that can't be
     * converted to the int {@code items.get} takes, and checks MVEL logged nothing while the run failed as it would.
     */
    private static void assertFailsWithoutMvelRecord(Rule rule, Map<String, Object> extraFacts) {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(rule));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("items", List.of(1));
        facts.setValue("index", "0\n" + FORGED);
        extraFacts.forEach(facts::setValue);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        List<LogRecord> records =
                mvelRecordsOf(() -> thrown.set(assertThrows(RuntimeException.class, () -> engine.run(facts))));

        assertEquals(List.of(), records.stream().map(LogRecord::getThrown).toList(), "MVEL logged the value itself");
        String message = thrown.get().getMessage();
        assertFalse(message.contains("\n"), "the engine's own message still carries a line break: " + message);
        assertTrue(message.contains("\\n" + FORGED), message);
        assertInstanceOf(NumberFormatException.class, rootCause(thrown.get()));
    }

    @Test
    @DisplayName("a condition MVEL fails to convert a fact for doesn't log the value through MVEL's own logger")
    void conditionConversionNotLoggedByMvel() {
        assertFailsWithoutMvelRecord(Rule.builder().ruleName("r").condition("items.get(index) == 1")
                .action("output.put('k', 1)").build(), Map.of());
    }

    @Test
    @DisplayName("an action MVEL fails to convert a fact for doesn't log the value through MVEL's own logger")
    void actionConversionNotLoggedByMvel() {
        assertFailsWithoutMvelRecord(Rule.builder().ruleName("r").condition("true")
                .action("output.put('k', items.get(index))").build(), Map.of());
    }

    @Test
    @DisplayName("a run nested in an action leaves MVEL's record dropped for the rest of the outer action")
    void nestedRunKeepsTheOuterRunsFilter() {
        RulesEngine<Map<String, Object>> inner =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        inner.load(List.of(Rule.builder().ruleName("inner").condition("true").action("output.put('x', 1)").build()));

        Nested nested = new Nested(inner);

        assertFailsWithoutMvelRecord(Rule.builder().ruleName("r").condition("true")
                .action("nested.run(); output.put('k', items.get(index))").build(), Map.of("nested", nested));
        assertEquals(Map.of("x", 1), nested.output, "the nested run didn't run its rule");
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

    @Test
    @DisplayName("a lone surrogate in a fact value a language quotes is escaped, so the log can't show it as ?")
    void loneSurrogateInValue() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true")
                .action("output.put('a', Integer.parseInt(v))").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("v", "x" + (char) 0xd800);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> engine.run(facts));

        assertTrue(thrown.getMessage().contains("For input string: \"x\\ud800\""), thrown.getMessage());
    }
}
