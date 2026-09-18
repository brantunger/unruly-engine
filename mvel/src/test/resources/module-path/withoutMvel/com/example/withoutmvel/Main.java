package com.example.withoutmvel;

import com.example.withoutmvel.model.Applicant;
import com.example.withoutmvel.model.LoanDecision;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    private static final int RUNS = 1_000;

    private Main() {
    }

    public static void main(String[] args) {
        Module engineModule = RulesEngine.class.getModule();
        check(Main.class.getModule().isNamed(), "the application isn't a named module");
        check("io.github.brantunger.unruly.core".equals(engineModule.getName()),
                "the engine is in the module " + engineModule.getName());
        check(!engineModule.isExported("io.github.brantunger.unruly.core"), "the engine's core package is exported");
        check(ModuleLayer.boot().findModule("io.github.brantunger.unruly").isEmpty(),
                "the MVEL module is in the module graph");
        check(ModuleLayer.boot().findModule("mvel2").isEmpty(), "mvel2 is in the module graph");

        runsOnAMapOutput();
        runsOnARecordFactAndABeanOutput();
    }

    /** Facts and an output the language itself reads and writes: a boolean fact, and a map the engine puts into. */
    private static void runsOnAMapOutput() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(
                Rule.builder()
                        .ruleName("vip-discount")
                        .language("key")
                        .condition("vip")
                        .action("discount=10")
                        .build()));

        for (int i = 0; i < RUNS; i++) {
            boolean vip = i % 2 == 0;
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("vip", vip);

            Map<String, Object> output = engine.run(facts);

            check(vip ? Map.of("discount", "10").equals(output) : output == null, "run " + i + " returned " + output);
        }
        System.out.println("Module path without MVEL: " + RUNS + " runs passed");
    }

    /**
     * The engine's own reflection over this application's classes, which only the module path puts a boundary
     * across: it reads the record fact's components for the language, and calls the output bean's setters for the
     * properties the action returned. Both classes are public, in a package exported to the engine's module alone.
     */
    private static void runsOnARecordFactAndABeanOutput() {
        RulesEngine<LoanDecision> engine = RulesEngineBuilder.<LoanDecision>firstMatch(LoanDecision::new).build();
        engine.load(List.of(
                Rule.builder()
                        .ruleName("prime-rate")
                        .priority(10)
                        .language("key")
                        .condition("applicant.prime")
                        .action("approved=true;interestRate=4.5")
                        .build(),
                Rule.builder()
                        .ruleName("standard-rate")
                        .priority(5)
                        .language("key")
                        .condition("applicant.member")
                        .action("approved=true;interestRate=6.9")
                        .build()));

        for (int i = 0; i < RUNS; i++) {
            boolean prime = i % 2 == 0;
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("applicant", new Applicant("Ada", prime, true));

            LoanDecision decision = engine.run(facts);

            check(decision != null && decision.isApproved() && decision.getInterestRate() == (prime ? 4.5 : 6.9),
                    "run " + i + " returned " + decision);
        }
        System.out.println("Module path without MVEL: " + RUNS + " runs on a record fact and a bean output passed");
    }

    private static void check(boolean passed, String failure) {
        if (!passed) {
            throw new IllegalStateException(failure);
        }
    }
}
