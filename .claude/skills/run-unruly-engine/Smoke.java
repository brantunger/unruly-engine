import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The default smoke scenario of the run-unruly-engine skill: loads and runs a small MVEL rule set through the public
 * API, prints one status line, and exits with 1 if a result is wrong. Run it with run.sh, or directly with
 * {@code java -cp 'native-smoke/build/install/native-smoke/lib/*' .claude/skills/run-unruly-engine/Smoke.java}.
 * Copy it to write another scenario: anything the engine accepts goes, and run.sh passes the file through.
 */
public final class Smoke {

    /** The fact the rules read. */
    public record Applicant(String name, int creditScore) {
    }

    /** The output a first-match engine's actions assign to. */
    public static final class LoanDecision {
        private String rate;

        public String getRate() {
            return rate;
        }

        public void setRate(String rate) {
            this.rate = rate;
        }
    }

    private Smoke() {
    }

    public static void main(String[] args) {
        int score = args.length > 0 ? Integer.parseInt(args[0]) : 780;
        String rate = firstMatch(score);
        String none = firstMatch(600);
        String tags = allMatches(score);
        boolean ok = "prime".equals(rate) && none == null && "[high, ok]".equals(tags);
        System.out.println("smoke " + (ok ? "OK" : "FAILED") + ": rate=" + rate + " noMatch=" + none + " tags=" + tags
                + " java=" + Runtime.version() + " (" + System.getProperty("java.vendor") + ")"
                + " slf4j=" + LoggerFactory.getILoggerFactory().getClass().getSimpleName());
        if (!ok) {
            System.exit(1);
        }
    }

    // One engine, loaded once, run twice: the highest-priority match fires, and no match returns null.
    private static String firstMatch(int score) {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new).build()) {
            engine.load(List.of(
                    Rule.builder().ruleName("prime").priority(10).condition("applicant.creditScore >= 750")
                            .action("output.rate = 'prime'").build(),
                    Rule.builder().ruleName("standard").priority(5).condition("applicant.creditScore >= 650")
                            .action("output.rate = 'standard'").build()));
            LoanDecision decision = engine.run(facts(score));
            return decision == null ? null : decision.getRate();
        }
    }

    // An all-matches engine with a map output: every matching rule fires, highest priority first.
    private static String allMatches(int score) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build()) {
            engine.load(List.of(
                    Rule.builder().ruleName("high").priority(1).condition("applicant.creditScore >= 750")
                            .action("output.put('high', true)").build(),
                    Rule.builder().ruleName("ok").priority(2).condition("applicant.name.length() > 0")
                            .action("output.put('ok', true)").build()));
            Map<String, Object> output = engine.run(facts(score));
            return output == null ? null : output.keySet().stream().sorted().toList().toString();
        }
    }

    private static FactStore<Object> facts(int score) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("applicant", new Applicant("Ada", score));
        return facts;
    }
}
