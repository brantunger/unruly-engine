package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RulesEngine's default methods")
class RulesEngineDefaultMethodTest {

    /** An implementation that overrides only the abstract methods. */
    private static final class MinimalEngine implements RulesEngine<Object> {
        @Override
        public void load(List<Rule> ruleList) {
            throw new AssertionError("not called");
        }

        @Override
        public Object run(FactStore<?> facts) {
            throw new AssertionError("not called");
        }
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
