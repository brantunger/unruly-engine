package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run by {@link FlightRecorderEventsTest} in a JVM started with {@code --limit-modules java.se}, so the
 * {@code jdk.jfr} module isn't there: loads and runs a rule whose condition matches and one whose condition fails, and
 * prints {@link #DONE} if both runs end as they would anywhere else.
 */
final class NoJfrScenario {

    static final String DONE = "SCENARIO runs done";

    private NoJfrScenario() {
    }

    public static void main(String[] args) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build()) {
            engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));
            Map<String, Object> output = engine.run(new FactMap<>());
            engine.load(List.of(Rule.builder().ruleName("broken").condition("missing.value > 1").action("x = 1")
                    .build()));
            boolean failed = false;
            try {
                engine.run(new FactMap<>());
            } catch (RuntimeException expected) {
                failed = true;
            }
            if (Map.of("k", 1).equals(output) && failed) {
                System.out.println(DONE);
            }
        }
    }
}
