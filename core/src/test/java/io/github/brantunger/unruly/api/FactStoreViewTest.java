package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a FactStore is read through its asMap() view")
class FactStoreViewTest {

    /** A FactStore that isn't a Map: only the four methods the interface asks for. */
    private static final class ListedFacts implements FactStore<Object> {

        private final Map<String, @Nullable FactReference<Object>> facts = new LinkedHashMap<>();

        @Override
        public @Nullable Object getValue(String name) {
            FactReference<Object> fact = facts.get(name);
            return fact != null ? fact.getValue() : null;
        }

        @Override
        public void setValue(String name, Object obj) {
            facts.put(name, new Fact<>(name, obj));
        }

        @Override
        public @Nullable FactReference<Object> put(FactReference<Object> ref) {
            return facts.put(ref.getName(), ref);
        }

        @Override
        public Map<String, @Nullable FactReference<Object>> asMap() {
            return Collections.unmodifiableMap(facts);
        }
    }

    private static RulesEngine<Map<String, Object>> engine(String condition, String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action(action).build()));
        return engine;
    }

    @Test
    @DisplayName("FactMap's view follows later changes")
    void viewFollowsChanges() {
        FactMap<Object> facts = new FactMap<>();
        Map<String, @Nullable FactReference<Object>> view = facts.asMap();

        facts.setValue("x", 1);

        assertEquals(Map.of("x", new Fact<>("x", 1)), view);
    }

    @Test
    @DisplayName("FactMap's view can't change the facts")
    void viewReadOnly() {
        FactMap<Object> facts = new FactMap<>(new Fact<>("x", 1));
        Map<String, @Nullable FactReference<Object>> view = facts.asMap();

        assertThrows(UnsupportedOperationException.class, () -> view.put("y", new Fact<>("y", 2)));
        assertThrows(UnsupportedOperationException.class,
                () -> view.entrySet().iterator().next().setValue(new Fact<>("x", 3)));
        assertThrows(UnsupportedOperationException.class, view::clear);
        assertEquals(1, facts.getValue("x"));
    }

    @Test
    @DisplayName("the rules run against a FactMap<Integer>")
    void typedFactMapRuns() {
        FactMap<Integer> facts = new FactMap<>();
        facts.setValue("x", 2);

        assertEquals(Map.of("hit", true), engine("x == 2", "put hit true").run(facts));
    }

    @Test
    @DisplayName("the rules run against a FactStore that isn't a Map")
    void storeThatIsNotAMapRuns() {
        ListedFacts facts = new ListedFacts();
        facts.setValue("x", 2);
        facts.put(new Fact<>("y", 3));

        // The condition reads the fact the store was told to set, the action the one it was handed as a reference.
        assertEquals(Map.of("hit", 3), engine("x == 2", "put hit y").run(facts));
        assertEquals(2, facts.getValue("x"));
        assertNull(facts.getValue("missing"));
    }
}
