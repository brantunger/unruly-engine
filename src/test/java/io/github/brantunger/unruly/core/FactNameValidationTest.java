package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("fact names rules can't refer to are rejected at run()")
class FactNameValidationTest {

    private static StatelessRulesEngine<Map<String, Object>> engine(String condition) {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule(condition)));
        return engine;
    }

    private static Rule rule(String condition) {
        return Rule.builder().ruleName("r").condition(condition).action("output.put('hit', true)").build();
    }

    private static FactStore<Object> fact(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"my-fact", "a.b", "has space", "1x", ""})
    void nonIdentifiersRejected(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = engine("true");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(fact(name, 1)));

        assertEquals("'" + name + "' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier", ex.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"empty", "nil", "null", "true", "this", "isdef", "in", "with", "var", "def", "Math", "String"})
    void reservedNamesRejected(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = engine("true");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(fact(name, 5)));

        assertEquals("'" + name + "' cannot be used as a fact name: MVEL reads it as a keyword or class name, "
                + "so rules would never see the fact", ex.getMessage());
    }

    @Test
    @DisplayName("a class name from an imported package is rejected; without the import it is an ordinary fact")
    void importedClassNameRejected() {
        assertEquals(Map.of("hit", true), engine("Date == 5").run(fact("Date", 5)));

        StatelessRulesEngine<Map<String, Object>> imported = new StatelessRulesEngine<>(HashMap::new);
        imported.addImport("java.util");
        imported.setRuleList(List.of(rule("true")));

        assertThrows(IllegalArgumentException.class, () -> imported.run(fact("Date", 5)));
    }

    @Test
    @DisplayName("a class name found in two imported packages is rejected like any other class name")
    void ambiguousClassNameRejected() {
        StatelessRulesEngine<Map<String, Object>> imported = new StatelessRulesEngine<>(HashMap::new);
        imported.addImport("java.util").addImport("java.sql");
        imported.setRuleList(List.of(rule("true")));

        for (int i = 0; i < 2; i++) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> imported.run(fact("Date", 5)));
            assertEquals("'Date' cannot be used as a fact name: MVEL reads it as a keyword or class name, "
                    + "so rules would never see the fact", ex.getMessage());
        }
    }

    @Test
    @DisplayName("a null name from a FactStore that allows one is rejected with IllegalArgumentException")
    void nullNameRejected() {
        HashFactStore facts = new HashFactStore();
        facts.put((String) null, new Fact<>("x", 1));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine("true").run(facts));

        assertEquals("fact name must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("names are checked against the imports of the current rule list, like the rules themselves")
    void importsTakeEffectOnReload() {
        StatelessRulesEngine<Map<String, Object>> engine = engine("true");
        engine.addImport("java.util");

        assertEquals(Map.of("hit", true), engine.run(fact("Date", 5)), "rules compiled before the import");

        engine.setRuleList(List.of(rule("true")));
        assertThrows(IllegalArgumentException.class, () -> engine.run(fact("Date", 5)));
    }

    @Test
    @DisplayName("a hyphenated name no longer silently matches as a subtraction")
    void hyphenatedNameNotMisparsed() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("my-fact", 5);
        facts.setValue("my", 10);
        facts.setValue("fact", 9);

        assertThrows(IllegalArgumentException.class, () -> engine("my-fact == 1").run(facts));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"total", "_x", "$x", "claim", "Process", "Date"})
    void validNamesAccepted(String name) {
        assertEquals(Map.of("hit", true), engine(name + " == 5").run(fact(name, 5)));
    }

    @Test
    @DisplayName("repeated runs with the same names are answered from the cache")
    void repeatedRunsAccepted() {
        StatelessRulesEngine<Map<String, Object>> engine = engine("claim == 5");
        for (int i = 0; i < 3; i++) {
            assertEquals(Map.of("hit", true), engine.run(fact("claim", 5)));
        }
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
