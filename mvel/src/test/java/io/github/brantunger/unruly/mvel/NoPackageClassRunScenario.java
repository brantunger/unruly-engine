package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.RulesEngine;
import org.jspecify.annotations.Nullable;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer;

import java.util.Map;

/**
 * Run by {@link NoPackageClassFactTest}, in the test's JVM with MVEL's JIT made the default again, as an application
 * may, and in a new JVM with the engine's default, MVEL's reflective optimizer: for each rule of
 * {@link NoPackageClassFactTest#RULES}, runs it with a fact named {@code Unpackaged} after some runs without it, and
 * prints {@link #DONE} if that run is rejected with the class's reason and every run without the fact puts 5 in the
 * output.
 */
final class NoPackageClassRunScenario {

    static final String DONE = "SCENARIO runs done";

    /** How many runs without the fact come before the one with it, the JIT's compile of the rule among them. */
    static final int[] CLEAN_RUNS_FIRST = {0, 1, 50, 51, 52, 100};

    private static final int RUNS_AFTER = 300;

    private NoPackageClassRunScenario() {
    }

    public static void main(String[] args) {
        for (String[] rule : NoPackageClassFactTest.RULES) {
            for (int cleanRunsFirst : CLEAN_RUNS_FIRST) {
                String failure = failure(rule[1], rule[2], cleanRunsFirst);
                if (failure != null) {
                    System.out.println(rule[0] + ": " + failure);
                    return;
                }
            }
        }
        // The engine made MVEL's reflective optimizer the default as it built the first engine, and it stays so.
        if (!(OptimizerFactory.getDefaultAccessorCompiler() instanceof ReflectiveAccessorOptimizer)) {
            System.out.println("MVEL's optimizer is " + OptimizerFactory.getDefaultAccessorCompiler());
            return;
        }
        System.out.println(DONE);
    }

    /**
     * Runs a rule {@code cleanRunsFirst} times without the fact, once with it, then {@value #RUNS_AFTER} times without
     * it again.
     *
     * @param condition      The rule's condition
     * @param action         The rule's action, which puts 5 in the output under {@code r}
     * @param cleanRunsFirst How many runs without the fact come first
     * @return What went wrong, or {@code null} if the run with the fact was rejected and every other run put 5 in the
     *         output
     */
    static @Nullable String failure(String condition, String action, int cleanRunsFirst) {
        String name = NoPackageClassFactTest.NAME;
        try (RulesEngine<Map<String, Object>> engine = NoPackageClassFactTest.engine(condition, action)) {
            String failure = cleanRuns(engine, cleanRunsFirst, "before");
            if (failure != null) {
                return failure;
            }
            try {
                engine.run(NoPackageClassFactTest.withFact(name, NoPackageClassFactTest.CLASS_LIKE));
                return "the run with '" + name + "' after " + cleanRunsFirst + " wasn't rejected";
            } catch (IllegalArgumentException e) {
                if (!NoPackageClassFactTest.rejection(name).equals(e.getMessage())) {
                    return "the run with '" + name + "' after " + cleanRunsFirst + ": " + e.getMessage();
                }
            }
            return cleanRuns(engine, RUNS_AFTER, "after the run with '" + name + "' after " + cleanRunsFirst + ",");
        }
    }

    private static @Nullable String cleanRuns(RulesEngine<Map<String, Object>> engine, int runs, String when) {
        for (int run = 0; run < runs; run++) {
            Map<String, Object> output;
            try {
                output = engine.run(NoPackageClassFactTest.amount());
            } catch (RuntimeException e) {
                return when + " run " + run + " failed: " + e;
            }
            if (!Map.of("r", 5).equals(output)) {
                return when + " run " + run + " returned " + output;
            }
        }
        return null;
    }
}
