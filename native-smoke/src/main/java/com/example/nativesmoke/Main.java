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
 * to, a map output, and a JDK static method. It also pins down what the engine does with a fact named after a class
 * in an imported package, which is the one path that looks a class file up as a resource and loads it: the name is
 * rejected on the JVM, and accepted in a native image, a known defect this application now holds the image to. The
 * bean and map engines each run more than MVEL's JIT threshold of about 50 runs, so a native image meets whatever
 * MVEL does after it. It prints one line and exits with 1 if a result is wrong.
 */
public final class Main {

    /**
     * How many times the bean and map engines run, well past the number of runs after which MVEL's JIT optimizer
     * steps in.
     */
    private static final int RUNS = 200;

    /** The part of the message the engine rejects a fact name with, which the fact-name check looks for. */
    private static final String REJECTION = "cannot be used as a fact name";

    /** What the fact-name check does on the JVM, and what it is meant to do everywhere: it rejects the name. */
    private static final String FACT_NAME_ON_JVM = "rejected,prime";

    /**
     * What the fact-name check does in a native image, which this application asserts so that a change either way is
     * seen at once. It is a known defect and not the right answer: an image serves no class file as a resource, so
     * the check finds no {@code java.util.Date} in the imported package and accepts a fact named {@code Date} in
     * silence. Fixing that flips this expectation to {@link #FACT_NAME_ON_JVM}, {@code "rejected,prime"}, which is
     * how the fix is proved.
     */
    private static final String FACT_NAME_IN_IMAGE = "accepted,prime";

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
        String factName = factNameOutcome();
        boolean image = nativeImage();
        boolean ok = "prime".equals(bean) && "standard,raised".equals(map)
                && (image ? FACT_NAME_IN_IMAGE : FACT_NAME_ON_JVM).equals(factName);
        System.out.println("native smoke " + (ok ? "OK" : "FAILED") + ": bean=" + bean + " map=" + map
                + " factName=" + factName + " dateResource=" + dateResource()
                + " dateFactValue=" + dateFactValue() + " image=" + image
                + " jit=" + (Boolean.getBoolean("mvel2.disable.jit") ? "off" : "on"));
        if (!ok) {
            System.exit(1);
        }
    }

    // Which expectation the fact-name check is held to, because the JVM and a native image disagree about it and
    // both run this binary: CI runs the image, and :native-smoke:installDist runs the same code on the JVM. GraalVM
    // sets this property only in an image, so no run has to be told which it is; the status line says which it chose.
    private static boolean nativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    // The java.util import is here, and not only on the fact-name engines below, because those run once each: this is
    // the only engine that resolves a package import past MVEL's JIT threshold, and the only one that works the
    // check's cache of names that aren't classes over many runs.
    private static String beanOutput() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName(), "java.util").build()) {
            engine.load(decisionRules());
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

    // Both halves of the fact-name check, in the same binary, so a pass means the package import is what rejects the
    // name and not something else about it: a fact named Date is rejected on an engine that imports the java.util
    // package, and accepted on one that imports only the Applicant class. Only the package import reaches the two
    // steps the check takes for a class in an imported package: it looks the class file up as a resource first, and
    // loads the class only if that found it. A native image need not do either. The rejection is logged at ERROR, so
    // a green run prints that line before its "native smoke OK:" line.
    private static String factNameOutcome() {
        return packageImportRejects() + "," + classImportAccepts();
    }

    private static String packageImportRejects() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName(), "java.util").build()) {
            engine.load(decisionRules());
            FactStore<Object> facts = datedApplicant();
            try {
                engine.run(facts);
                return "accepted";
            } catch (IllegalArgumentException e) {
                // The message must name the fact too: the engine rejects an output fact with a message that ends the
                // same way, and a run's facts are checked in no particular order.
                String message = String.valueOf(e.getMessage());
                return message.contains(REJECTION) && message.contains("'Date'")
                        ? "rejected" : "unexpected: " + message;
            }
        }
    }

    private static String classImportAccepts() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName()).build()) {
            engine.load(decisionRules());
            FactStore<Object> facts = datedApplicant();
            try {
                LoanDecision decision = engine.run(facts);
                return decision == null ? "no match" : decision.getRate();
            } catch (IllegalArgumentException e) {
                return "unexpected: " + e.getMessage();
            }
        }
    }

    // A diagnostic, never an assertion: it must not feed into ok or the exit code. It tells which of the two steps
    // above a factName=accepted would have stopped at: whether the image served the class file as a resource at all.
    // It asks the loader the engine resolves package imports with, the thread's context loader, as core's
    // ImportResolver.contextClassLoader does, so the two see the same class path here; the fallback for a thread
    // without one stands in for that method's library loader, which this application shares a class path with. It is
    // still read separately from the engine, and what the loader answers is nothing the engine promises.
    private static String dateResource() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = context != null ? context : ClassLoader.getSystemClassLoader();
        return String.valueOf(loader.getResource("java/util/Date.class") != null);
    }

    // A diagnostic, like dateResource, and for the same reason: it must not feed into ok or the exit code, because
    // what it finds is exactly what nobody knows yet. It settles what an accepted name costs. FactNames says the
    // check mirrors MVEL, which ignores every error when it looks a name up in an imported package and then reads
    // the name as the fact; so an image that accepts the name is harmless if MVEL fails to resolve java.util.Date
    // there too, and hides the fact from every rule if MVEL resolves it anyway. A rule copies whatever MVEL reads
    // 'Date' as into the output: the fact's own value means rules see the fact, "class java.util.Date" means MVEL
    // read the class and the fact is invisible. A value, not a null check — the class isn't null either, so
    // Date != null would settle nothing. Its own engine, so neither half of the check above is disturbed.
    private static String dateFactValue() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName(), "java.util").build()) {
            engine.load(List.of(Rule.builder().ruleName("dateFact").condition("applicant.creditScore >= 0")
                    .action("output.rate = '' + Date").build()));
            LoanDecision decision = engine.run(datedApplicant());
            return decision == null ? "no match" : String.valueOf(decision.getRate());
        } catch (RuntimeException e) {
            // The status line is the deliverable, so anything thrown is reported on it, in one line, not thrown on.
            String message = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            return message.contains(REJECTION) ? "rejected" : e.getClass().getSimpleName() + ": " + message;
        }
    }

    private static List<Rule> decisionRules() {
        return List.of(
                Rule.builder().ruleName("prime").priority(2).condition("applicant.creditScore >= 750")
                        .action("output.rate = 'prime'").build(),
                Rule.builder().ruleName("standard").priority(1).condition("applicant.creditScore < 750")
                        .action("output.rate = 'standard'").build());
    }

    // An applicant, and a fact named after java.util.Date. The check above needs no rule to read it — having it in
    // the store is enough — while the probe's rule reads it on purpose, to see what MVEL makes of the name.
    private static FactStore<Object> datedApplicant() {
        FactStore<Object> facts = applicant(760, 50_000);
        facts.setValue("Date", "2026-01-01");
        return facts;
    }

    private static FactStore<Object> applicant(int creditScore, int income) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("applicant", new Applicant(creditScore, income));
        return facts;
    }
}
