package com.example.withjackson;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public final class Main {

    private static final String JSON = """
            [
              {"ruleName": "prime-rate", "priority": 10, "condition": "score >= 750",
               "action": "output.put('rate', 4.5)", "description": "Prime", "language": "mvel"},
              {"ruleName": "standard-rate", "condition": "true", "action": "output.put('rate', 6.9)"}
            ]
            """;

    private static final String UNNAMED = "[{\"condition\": \"true\", \"action\": \"x\"}]";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        List<Rule> expected = List.of(
                Rule.builder().ruleName("prime-rate").priority(10).condition("score >= 750")
                        .action("output.put('rate', 4.5)").description("Prime").language("mvel").build(),
                Rule.builder().ruleName("standard-rate").condition("true").action("output.put('rate', 6.9)")
                        .build());

        List<Rule> jackson2 = Jackson2Rules.read(JSON);
        check(expected.equals(jackson2), "Jackson 2 read " + jackson2);
        List<Rule> jackson3 = Jackson3Rules.read(JSON);
        check(expected.equals(jackson3), "Jackson 3 read " + jackson3);

        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateless(HashMap::new)) {
            engine.setRuleList(jackson3);
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("score", 780);
            Map<String, Object> output = engine.run(facts);
            check(Map.of("rate", 4.5).equals(output), "the rules returned " + output);
        }

        checkRejected("Jackson 2", () -> Jackson2Rules.read(UNNAMED));
        checkRejected("Jackson 3", () -> Jackson3Rules.read(UNNAMED));
        System.out.println("Module path with Jackson: rules read with Jackson 2 and Jackson 3");
    }

    private static void checkRejected(String reader, Callable<List<Rule>> read) {
        try {
            List<Rule> rules = read.call();
            throw new IllegalStateException(reader + " read a rule without a name: " + rules);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            check(String.valueOf(e.getMessage()).contains("ruleName must not be null"),
                    reader + " failed with " + e);
        }
    }

    private static void check(boolean passed, String failure) {
        if (!passed) {
            throw new IllegalStateException(failure);
        }
    }
}
