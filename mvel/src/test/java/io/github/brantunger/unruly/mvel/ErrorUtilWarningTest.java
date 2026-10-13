package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.CompileException;
import org.mvel2.MVEL;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MVEL's WARNING for its own failure to place a compile error is dropped while the engine compiles (#1126)")
class ErrorUtilWarningTest {

    // By name, as the engine's filter gets it; held, as java.util.logging keeps a logger only weakly.
    private static final Logger ERROR_UTIL = Logger.getLogger("org.mvel2.util.ErrorUtil");

    /**
     * Runs a task and returns the records MVEL's ErrorUtil logged meanwhile that its logger's filter passed. They
     * aren't passed on to the parent handlers, so a record the test expects doesn't print its stack trace in the build.
     */
    private static List<LogRecord> errorUtilRecordsOf(Runnable task) {
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
        boolean useParentHandlers = ERROR_UTIL.getUseParentHandlers();
        ERROR_UTIL.addHandler(handler);
        ERROR_UTIL.setUseParentHandlers(false);
        try {
            task.run();
        } finally {
            ERROR_UTIL.setUseParentHandlers(useParentHandlers);
            ERROR_UTIL.removeHandler(handler);
        }
        return records;
    }

    private static RulesEngine<Map<String, Object>> engine() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
    }

    private static List<Rule> rulesWith(String condition) {
        return List.of(Rule.builder().ruleName("r").condition(condition).action("output.put('k', 1)").build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x contains {1, +}", "{+}", "{,}"})
    @DisplayName("load() reports the typo as the rule's compile error, and MVEL's WARNING isn't logged")
    void loadLogsNothing(String condition) {
        RulesEngine<Map<String, Object>> engine = engine();

        List<LogRecord> records = errorUtilRecordsOf(() -> assertThrows(RuleCompilationException.class,
                () -> engine.load(rulesWith(condition))));

        assertEquals(List.of(), records, "load() logged MVEL's WARNING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x contains {1, +}", "{+}", "{,}"})
    @DisplayName("validate() returns the typo as the rule's compile error, and MVEL's WARNING isn't logged")
    void validateLogsNothing(String condition) {
        RulesEngine<Map<String, Object>> engine = engine();
        List<RuleCompilationException> errors = new CopyOnWriteArrayList<>();

        List<LogRecord> records = errorUtilRecordsOf(() -> errors.addAll(engine.validate(rulesWith(condition))));

        assertEquals(List.of(), records, "validate() logged MVEL's WARNING");
        assertEquals(1, errors.size(), "the rule's compile error");
    }

    @Test
    @DisplayName("MVEL compiling outside the engine, on a thread whose load just failed, still logs its WARNING")
    void mvelOutsideTheEngineStillLogs() {
        RulesEngine<Map<String, Object>> engine = engine();
        List<LogRecord> duringLoad = errorUtilRecordsOf(() -> assertThrows(RuleCompilationException.class,
                () -> engine.load(rulesWith("x contains {1, +}"))));

        List<LogRecord> afterLoad = errorUtilRecordsOf(() -> assertThrows(CompileException.class,
                () -> MVEL.compileExpression("x contains {1, +}")));

        assertEquals(List.of(), duringLoad, "the engine's load logged MVEL's WARNING");
        // Two, because MVEL 2.5.4 rewrites the sub-expression's CompileException twice on its way out of the outer
        // compile: in the parser (AbstractParser:1291) and in ExpressionCompiler._compile's catch (:316).
        assertEquals(2, afterLoad.size(), "MVEL used on its own lost its WARNING");
        afterLoad.forEach(record -> assertInstanceOf(StringIndexOutOfBoundsException.class, record.getThrown()));
    }
}
