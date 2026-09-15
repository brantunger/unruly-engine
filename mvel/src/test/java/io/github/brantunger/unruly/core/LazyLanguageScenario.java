package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run as its own JVM with class-load logging by {@link LazyLanguageTest}: builds an engine, then loads an MVEL rule,
 * printing a marker after each step.
 */
final class LazyLanguageScenario {

    static final String BUILT = "SCENARIO engine built";
    static final String LOADED = "SCENARIO rules loaded";

    private LazyLanguageScenario() {
    }

    public static void main(String[] args) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        System.out.println(BUILT);
        System.out.flush();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build()));
        System.out.println(LOADED);
        System.out.flush();
    }
}
