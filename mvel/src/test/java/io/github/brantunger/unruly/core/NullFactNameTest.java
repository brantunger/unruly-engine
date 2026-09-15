package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedAtError;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a null fact name is rejected at run()")
class NullFactNameTest {

    @Test
    @DisplayName("a null name from a FactStore that allows one is rejected with IllegalArgumentException, logged at ERROR")
    void nullNameRejected() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('hit', true)")
                .build()));
        HashFactStore facts = new HashFactStore();
        facts.put((String) null, new Fact<>("x", 1));

        IllegalArgumentException ex = assertLoggedAtError(IllegalArgumentException.class, () -> engine.run(facts));

        assertEquals("fact name must not be null", ex.getMessage());
    }

    /** A FactStore that, unlike FactMap, accepts a null name. */
    private static final class HashFactStore extends HashMap<String, FactReference<Object>>
            implements FactStore<Object> {

        @Override
        public Object getValue(String name) {
            return get(name).getValue();
        }

        @Override
        public void setValue(String name, Object obj) {
            put(name, new Fact<>(name, obj));
        }

        @Override
        public FactReference<Object> put(FactReference<Object> ref) {
            return put(ref.getName(), ref);
        }
    }
}
