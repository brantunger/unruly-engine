package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RulesEngine imports feature")
class RulesEngineImportsTest {

    @Nested
    @DisplayName("imports(Collection)")
    class AddImports {

        @Test
        @DisplayName("rules can use imported packages in conditions")
        void rulesCanUseImportedPackages() {
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                    builder -> builder.imports(Set.of("java.util")));

            Rule rule = Rule.builder()
                    .ruleName("use-objects")
                    .condition("Objects.nonNull(name)")
                    .action("output.put(\"valid\", true)")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("name", "test");

            Map<String, Object> result = engine.run(facts);

            assertEquals(true, result.get("valid"));
        }
    }

    @Nested
    @DisplayName("imports(String...)")
    class AddImport {

        @Test
        @DisplayName("single import works for rules")
        void singleImportWorks() {
            StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                    builder -> builder.imports("java.util"));

            Rule rule = Rule.builder()
                    .ruleName("use-objects")
                    .condition("Objects.nonNull(name)")
                    .action("output.put(\"valid\", true)")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("name", "test");

            Map<String, Object> result = engine.run(facts);

            assertEquals(true, result.get("valid"));
        }

        @Test
        @DisplayName("multiple imports calls accumulate")
        void multipleAddImportCallsAccumulate() {
            StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                    builder -> builder.imports("java.util").imports("java.lang"));

            Rule rule = Rule.builder()
                    .ruleName("multi-import")
                    .condition("Objects.nonNull(name)")
                    .action("output.put(\"result\", \"found\")")
                    .priority(1)
                    .build();

            engine.load(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("name", "hello");

            Map<String, Object> result = engine.run(facts);

            assertEquals("found", result.get("result"));
        }
    }
}
