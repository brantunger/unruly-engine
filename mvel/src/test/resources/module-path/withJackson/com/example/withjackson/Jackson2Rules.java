package com.example.withjackson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;

/** Reads rules with Jackson 2 through the documented mix-ins. */
final class Jackson2Rules {

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

    private Jackson2Rules() {
    }

    static List<Rule> read(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<List<Rule>>() { });
    }
}
