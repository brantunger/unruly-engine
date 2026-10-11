package com.example.withmvel;

import com.example.withmvel.model.Applicant;
import com.example.withmvel.model.LoanDecision;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.List;

public final class Main {

    /**
     * Well past the 50 or so runs after which MVEL's JIT, were it MVEL's default, would replace its reflective
     * accessors with classes outside the mvel2 module, which the export to mvel2 alone doesn't reach. The engine makes
     * MVEL's reflective optimizer the default, so every run reads the classes from mvel2.
     */
    private static final int RUNS = 1_000;

    private Main() {
    }

    public static void main(String[] args) {
        RulesEngine<LoanDecision> engine = RulesEngineBuilder.<LoanDecision>firstMatch(LoanDecision::new).build();
        engine.load(List.of(
                Rule.builder()
                        .ruleName("prime-rate")
                        .priority(10)
                        .condition("applicant.creditScore >= 750")
                        .action("output.approved = true; output.interestRate = 4.5")
                        .build(),
                Rule.builder()
                        .ruleName("standard-rate")
                        .priority(5)
                        .condition("applicant.creditScore >= 650")
                        .action("output.approved = true; output.interestRate = 6.9")
                        .build()));

        for (int i = 0; i < RUNS; i++) {
            boolean prime = i % 2 == 0;
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("applicant", new Applicant("Ada", prime ? 780 : 700));

            LoanDecision decision = engine.run(facts);

            check(decision != null && decision.isApproved() && decision.getInterestRate() == (prime ? 4.5 : 6.9),
                    "run " + i + " returned " + decision);
        }

        Module engineModule = RulesEngine.class.getModule();
        check(Main.class.getModule().isNamed(), "the application isn't a named module");
        check("io.github.brantunger.unruly.core".equals(engineModule.getName()),
                "the engine is in the module " + engineModule.getName());
        check(!engineModule.isExported("io.github.brantunger.unruly.core"), "the engine's core package is exported");
        check(ModuleLayer.boot().findModule("mvel2").isPresent(), "mvel2 isn't in the module graph");
        check(ModuleLayer.boot().findModule("org.slf4j.simple").isPresent(), "SLF4J's provider isn't in the module graph");
        System.out.println("Module path with MVEL: " + RUNS + " runs passed");
    }

    private static void check(boolean passed, String failure) {
        if (!passed) {
            throw new IllegalStateException(failure);
        }
    }
}
