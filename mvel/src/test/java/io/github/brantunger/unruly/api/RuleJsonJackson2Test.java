package io.github.brantunger.unruly.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads rules with Jackson 2 through exactly the mix-ins docs/writing-rules.md, the {@link Rule} Javadoc and the
 * migration guide show. Keep them in step.
 */
@DisplayName("reading rules from JSON with Jackson 2")
class RuleJsonJackson2Test {

    // The documented mix-ins: one for Rule, and one for its builder, because Jackson reads @JsonPOJOBuilder from the
    // builder class.
    @JsonDeserialize(builder = Rule.RuleBuilder.class)
    abstract static class RuleMixIn {
    }

    @JsonPOJOBuilder(withPrefix = "")
    abstract static class RuleBuilderMixIn {
    }

    /** Both annotations on one mix-in for Rule, which doesn't work. */
    @JsonDeserialize(builder = Rule.RuleBuilder.class)
    @JsonPOJOBuilder(withPrefix = "")
    abstract static class CombinedMixIn {
    }

    private static final String JSON = """
            [
              {"ruleName": "prime-rate", "priority": 10, "condition": "applicant.score >= 750",
               "action": "output.put('rate', 4.5)", "description": "Prime", "language": "mvel"},
              {"ruleName": "standard-rate", "condition": "true", "action": "output.put('rate', 6.9)"}
            ]
            """;

    private static final List<Rule> EXPECTED = List.of(
            Rule.builder().ruleName("prime-rate").priority(10).condition("applicant.score >= 750")
                    .action("output.put('rate', 4.5)").description("Prime").language("mvel").build(),
            Rule.builder().ruleName("standard-rate").condition("true").action("output.put('rate', 6.9)").build());

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addMixIn(Rule.class, RuleMixIn.class)
            .addMixIn(Rule.RuleBuilder.class, RuleBuilderMixIn.class)
            .build();

    private static List<Rule> read(ObjectMapper mapper, String json) throws Exception {
        return mapper.readValue(json, new TypeReference<List<Rule>>() { });
    }

    @Test
    @DisplayName("the two documented mix-ins read every field, and the rules run")
    void readsRules() throws Exception {
        List<Rule> rules = read(MAPPER, JSON);

        assertEquals(EXPECTED, rules);
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build()) {
            engine.load(rules);
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("applicant", Map.of("score", 780));
            assertEquals(Map.of("rate", 4.5), engine.run(facts));
        }
    }

    @Test
    @DisplayName("rules written with the same mapper read back equal")
    void roundTrip() throws Exception {
        assertEquals(EXPECTED, read(MAPPER, MAPPER.writeValueAsString(EXPECTED)));
    }

    @Test
    @DisplayName("a rule without a name fails while it's read, naming the missing field")
    void missingNameRejected() {
        JsonMappingException ex = assertThrows(JsonMappingException.class,
                () -> read(MAPPER, "[{\"condition\": \"true\", \"action\": \"x\"}]"));

        assertTrue(ex.getMessage().contains("ruleName must not be null"), ex.getMessage());
    }

    @Test
    @DisplayName("one mix-in with both annotations doesn't work, which is why the docs show two")
    void combinedMixInFails() {
        ObjectMapper combined = JsonMapper.builder().addMixIn(Rule.class, CombinedMixIn.class).build();

        assertThrows(UnrecognizedPropertyException.class, () -> read(combined, JSON));
    }
}
