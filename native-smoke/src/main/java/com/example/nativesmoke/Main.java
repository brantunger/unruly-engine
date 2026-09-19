package com.example.nativesmoke;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs MVEL rules the way an application would, so CI can build it into a GraalVM native image and run it. It goes
 * through each way the engine and MVEL use reflection: a record fact read by property, a bean output an action assigns
 * to, a map output, and a JDK static method. Each engine runs more than MVEL's JIT threshold of about 50 runs, so a
 * native image meets whatever MVEL does after it. It prints one line and exits with 1 if a result is wrong.
 */
public final class Main {

    /** How many times each engine runs, well past the number of runs after which MVEL's JIT optimizer steps in. */
    private static final int RUNS = 200;

    /**
     * The fact the rules read.
     *
     * @param creditScore The applicant's credit score
     * @param income      The applicant's yearly income
     */
    public record Applicant(int creditScore, int income) {
    }

    /** The output a first-match engine's actions assign to, as a bean. */
    public static final class LoanDecision {

        private String rate;

        /** Creates a decision with no rate. */
        public LoanDecision() {
            // No rate until a rule sets one.
        }

        /**
         * Returns the rate a rule chose.
         *
         * @return The rate, or {@code null}
         */
        public String getRate() {
            return rate;
        }

        /**
         * Sets the rate.
         *
         * @param rate The rate
         */
        public void setRate(String rate) {
            this.rate = rate;
        }
    }

    private Main() {
    }

    /**
     * Runs the rules and checks the results.
     *
     * @param args Unused
     */
    // A command-line check: the line it prints is its result.
    @SuppressWarnings("PMD.SystemPrintln")
    public static void main(String[] args) {
        String bean = beanOutput();
        String map = mapOutput();
        boolean ok = "prime".equals(bean) && "standard,raised".equals(map);
        System.out.println("native smoke " + (ok ? "OK" : "FAILED") + ": bean=" + bean + " map=" + map
                + " jit=" + (Boolean.getBoolean("mvel2.disable.jit") ? "off" : "on"));
        if (!ok) {
            System.exit(1);
        }
    }

    private static String beanOutput() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName()).build()) {
            engine.load(List.of(
                    Rule.builder().ruleName("prime").priority(2).condition("applicant.creditScore >= 750")
                            .action("output.rate = 'prime'").build(),
                    Rule.builder().ruleName("standard").priority(1).condition("applicant.creditScore < 750")
                            .action("output.rate = 'standard'").build()));
            LoanDecision decision = new LoanDecision();
            for (int i = 0; i < RUNS; i++) {
                decision = engine.run(applicant(760 + i % 10, 50_000));
            }
            return decision == null ? "no match" : decision.getRate();
        }
    }

    private static String mapOutput() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build()) {
            engine.load(List.of(
                    Rule.builder().ruleName("standard").condition("applicant.creditScore < 750")
                            .action("output.put('rate', 'standard')").build(),
                    Rule.builder().ruleName("raised").condition("Math.max(applicant.income, 0) > 100000")
                            .action("output.put('limit', 'raised')").build()));
            Map<String, Object> output = Map.of();
            for (int i = 0; i < RUNS; i++) {
                output = engine.run(applicant(700, 150_000 + i));
            }
            return Objects.toString(output.get("rate")) + "," + Objects.toString(output.get("limit"));
        }
    }

    private static FactStore<Object> applicant(int creditScore, int income) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("applicant", new Applicant(creditScore, income));
        return facts;
    }
}
