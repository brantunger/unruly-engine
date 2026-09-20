package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RulesEngine's default methods")
class RulesEngineDefaultMethodTest {

    /** An implementation that overrides only the abstract methods. */
    private static final class MinimalEngine implements RulesEngine<Object> {

        private static final String CHECKSUM = "checksum";

        @Override
        public void load(List<Rule> ruleList) {
            throw new AssertionError("not called");
        }

        @Override
        public List<RuleCompilationException> validate(List<Rule> ruleList) {
            throw new AssertionError("not called");
        }

        private RunOptions received;

        @Override
        public RunResult<Object> runWithResult(FactStore<?> facts, RunOptions options) {
            received = options;
            return RunResult.of("output", List.of(), CHECKSUM);
        }

        @Override
        public RuleSetInfo rules() {
            return RuleSetInfo.of(List.of(), CHECKSUM, null);
        }
    }

    @Test
    @DisplayName("run() returns the result's output by default, so an implementation only writes runWithResult()")
    void runDelegatesToRunWithResult() {
        assertEquals("output", new MinimalEngine().run(new FactMap<>()));
    }

    @Test
    @DisplayName("runWithResult(facts) passes the default options, so an implementation writes one run method")
    void runWithResultPassesTheDefaultOptions() {
        MinimalEngine engine = new MinimalEngine();

        assertEquals("output", engine.runWithResult(new FactMap<>()).output());
        assertSame(RunOptions.defaults(), engine.received);
    }

    @Test
    @DisplayName("close() does nothing by default, so an existing implementation works in try-with-resources")
    void closeDoesNothingByDefault() {
        assertDoesNotThrow(() -> {
            try (RulesEngine<Object> engine = new MinimalEngine()) {
                assertNotNull(engine);
            }
        });
    }
}
