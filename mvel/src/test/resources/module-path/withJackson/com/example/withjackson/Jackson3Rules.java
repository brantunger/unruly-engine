package com.example.withjackson;

import io.github.brantunger.unruly.api.Rule;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Reads rules with Jackson 3 through the documented mix-ins. */
final class Jackson3Rules {

    @JsonDeserialize(builder = Rule.RuleBuilder.class)
    abstract static class RuleMixIn {
    }

    @JsonPOJOBuilder(withPrefix = "")
    abstract static class RuleBuilderMixIn {
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addMixIn(Rule.class, RuleMixIn.class)
            .addMixIn(Rule.RuleBuilder.class, RuleBuilderMixIn.class)
            .build();

    private Jackson3Rules() {
    }

    static List<Rule> read(String json) {
        return MAPPER.readValue(json, new TypeReference<List<Rule>>() { });
    }
}
