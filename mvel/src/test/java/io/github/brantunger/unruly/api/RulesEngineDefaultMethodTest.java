package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
        public RunResult<Object> runWithResult(FactStore<?> facts) {
            return RunResult.of("output", List.of(), CHECKSUM);
        }

        @Override
        public RunResult<Object> runWithResult(FactStore<?> facts, Duration timeout) {
            return runWithResult(facts);
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
    @DisplayName("close() does nothing by default, so an existing implementation works in try-with-resources")
    void closeDoesNothingByDefault() {
        assertDoesNotThrow(() -> {
            try (RulesEngine<Object> engine = new MinimalEngine()) {
                assertNotNull(engine);
            }
        });
    }
}
