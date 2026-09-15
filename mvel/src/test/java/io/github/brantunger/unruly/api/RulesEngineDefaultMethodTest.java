package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RulesEngine's default methods")
class RulesEngineDefaultMethodTest {

    /** An implementation written before expression languages could be registered. */
    private static final class MvelOnlyEngine implements RulesEngine<Object> {
        @Override
        public void setRuleList(List<Rule> ruleList) {
            throw new AssertionError("not called");
        }

        @Override
        public Object run(FactStore<Object> facts) {
            throw new AssertionError("not called");
        }

        @Override
        public RulesEngine<Object> addImports(Set<String> packages) {
            throw new AssertionError("not called");
        }

        @Override
        public RulesEngine<Object> addImport(String packageString) {
            throw new AssertionError("not called");
        }

        @Override
        public RulesEngine<Object> registerListener(RuleListener listener) {
            throw new AssertionError("not called");
        }

        @Override
        public RulesEngine<Object> registerListeners(List<RuleListener> listeners) {
            throw new AssertionError("not called");
        }
    }

    @Test
    @DisplayName("registerLanguage throws on an engine that doesn't override it")
    void registerLanguageUnsupported() {
        MvelOnlyEngine engine = new MvelOnlyEngine();

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> engine.registerLanguage(new ToyExpressionLanguage()));

        assertTrue(ex.getMessage().endsWith("only supports MVEL rules"), ex.getMessage());
    }

    @Test
    @DisplayName("close() does nothing by default, so an existing implementation works in try-with-resources")
    void closeDoesNothingByDefault() {
        assertDoesNotThrow(() -> {
            try (RulesEngine<Object> engine = new MvelOnlyEngine()) {
                assertNotNull(engine);
            }
        });
    }
}
