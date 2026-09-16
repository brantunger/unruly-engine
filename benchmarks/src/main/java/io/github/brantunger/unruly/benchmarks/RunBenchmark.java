package io.github.brantunger.unruly.benchmarks;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What one {@code run()} costs, across the shapes an application actually builds: how many rules are loaded, which
 * hit policy the engine uses, whether facts are records or maps, whether a listener is registered, and whether the
 * rules are written in MVEL or in the cheapest possible language.
 *
 * <p>
 * Run the whole matrix with {@code ./gradlew :benchmarks:jmh}, or a slice of it with
 * {@code -PjmhArgs="-p rules=100 -prof gc"}. See {@code benchmarks/README.md}.
 * </p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class RunBenchmark {

    /** Creates the benchmark. JMH creates one for each combination of the parameters below. */
    public RunBenchmark() {
        // Nothing to set up: loadTheRules() builds the engine once the parameters are set.
    }

    /**
     * An applicant as a record, which is what the docs' examples use.
     *
     * @param creditScore The score a rule's condition compares
     * @param name        A field a condition doesn't read, so the record isn't unrealistically small
     * @param employed    A second field conditions read
     */
    public record Applicant(int creditScore, String name, boolean employed) {
    }

    /** A listener that does nothing, to measure what being called costs rather than what a listener does. */
    private static final class NoopListener implements RuleListener {
    }

    /** About one rule in ten matches, so a run does some work without firing everything. */
    private static final int MATCHING_SCORE = 700;

    private static final String MVEL = "mvel";
    private static final String FIRST_MATCH = "firstMatch";
    private static final String MAP = "map";

    @Param({"10", "100", "1000"})
    private int rules;

    @Param({FIRST_MATCH, "allMatches"})
    private String policy;

    @Param({"record", MAP})
    private String facts;

    @Param({"none", NoopLanguage.LANGUAGE_NAME})
    private String listener;

    @Param({MVEL, NoopLanguage.LANGUAGE_NAME})
    private String language;

    private RulesEngine<Map<String, Object>> engine;

    /** Builds the engine these parameters describe and compiles its rules, once for the whole trial. */
    @Setup(Level.Trial)
    public void loadTheRules() {
        engine = newEngine();
        engine.load(ruleList(rules, language));
    }

    /** Closes the engine, so its compiled copies and its languages' compilers are released between trials. */
    @TearDown(Level.Trial)
    public void closeTheEngine() {
        engine.close();
    }

    private RulesEngine<Map<String, Object>> newEngine() {
        RulesEngineBuilder<Map<String, Object>> builder;
        if (FIRST_MATCH.equals(policy)) {
            builder = RulesEngineBuilder.firstMatch(HashMap::new);
        } else {
            builder = RulesEngineBuilder.allMatches(HashMap::new);
        }
        builder.language(new MvelExpressionLanguage()).language(new NoopLanguage()).defaultLanguage(MVEL);
        if (NoopLanguage.LANGUAGE_NAME.equals(listener)) {
            builder.listener(new NoopListener());
        }
        return builder.build();
    }

    /**
     * Builds {@code count} rules whose thresholds are spread over the score range, so about one in ten matches the
     * facts each run uses, whichever language they're written in.
     *
     * @param count    How many rules to build
     * @param language The name of the language to write them in
     * @return The rules, in the order they're loaded
     */
    private static List<Rule> ruleList(int count, String language) {
        List<Rule> ruleList = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Thresholds from 620 to 1,020: the ten per cent at or below the facts' score match.
            int threshold = 620 + i % 100 * 4;
            String property = "rule" + i;
            ruleList.add(NoopLanguage.LANGUAGE_NAME.equals(language)
                    ? Rule.builder().ruleName(property).language(NoopLanguage.LANGUAGE_NAME)
                            .condition(Integer.toString(threshold)).action(property).priority(count - i).build()
                    : Rule.builder().ruleName(property)
                            .condition("applicant.creditScore >= " + threshold + " && employed")
                            .action("output.put('" + property + "', true)").priority(count - i).build());
        }
        return ruleList;
    }

    /**
     * The five facts a run starts with, in a fresh store, as a request handler would build them.
     *
     * @return The facts
     */
    private FactStore<Object> newFacts() {
        FactStore<Object> store = new FactMap<>();
        store.setValue("applicant", MAP.equals(facts)
                ? Map.of("creditScore", MATCHING_SCORE, "name", "Alex", "employed", true)
                : new Applicant(MATCHING_SCORE, "Alex", true));
        store.setValue(NoopLanguage.SCORE_FACT, MATCHING_SCORE);
        store.setValue("employed", true);
        store.setValue("amount", 25_000);
        store.setValue("term", 36);
        return store;
    }

    /**
     * One run with fresh facts, which is what a request costs.
     *
     * @return The output, so that nothing in the run can be optimised away
     */
    @Benchmark
    public Map<String, Object> run() {
        return engine.run(newFacts());
    }

    /** Compiling a rule list, which every {@code load()} and every reload pays. */
    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 3)
    @Measurement(iterations = 5)
    public static class Load {

        /** Creates the benchmark. JMH creates one for each combination of the parameters below. */
        public Load() {
            // Nothing to set up: buildTheEngine() runs before each iteration.
        }

        @Param({"10", "100", "1000"})
        private int rules;

        @Param({MVEL, NoopLanguage.LANGUAGE_NAME})
        private String language;

        private RulesEngine<Map<String, Object>> engine;
        private List<Rule> ruleList;

        /** Builds an engine with no rules, and the list to compile into it, before each iteration. */
        @Setup(Level.Iteration)
        public void buildTheEngine() {
            engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                    .language(new MvelExpressionLanguage()).language(new NoopLanguage()).defaultLanguage(MVEL)
                    .build();
            ruleList = ruleList(rules, language);
        }

        /** Closes the engine the iteration loaded, so the next one starts from nothing. */
        @TearDown(Level.Iteration)
        public void closeTheEngine() {
            engine.close();
        }

        /**
         * Compiles the whole rule list.
         *
         * @return The engine that now holds them, so the call can't be optimised away
         */
        @Benchmark
        public RulesEngine<Map<String, Object>> load() {
            engine.load(ruleList);
            return engine;
        }
    }
}
