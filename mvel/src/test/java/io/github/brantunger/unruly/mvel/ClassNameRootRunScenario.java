package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.RulesEngine;
import org.jspecify.annotations.Nullable;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer;

import java.util.Map;

/**
 * Run by {@link ClassNameRootFactTest}, in its own JVM and in a JVM started with {@code -Dmvel2.disable.jit=true},
 * which MVEL reads once, when it first loads: for each rule of {@link ClassNameRootFactTest#RULES}, runs it with a fact
 * named {@code java} after some runs without it, and prints {@link #DONE} if that run is rejected with the package's
 * reason and every run without the fact puts 1 in the output.
 */
final class ClassNameRootRunScenario {

    static final String DONE = "SCENARIO runs done";

    /** How many runs without the fact come before the one with it, the JIT's compile of the rule among them. */
    static final int[] CLEAN_RUNS_FIRST = {0, 1, 50, 51, 52, 100};

    private static final int RUNS_AFTER = 300;

    private ClassNameRootRunScenario() {
    }

    public static void main(String[] args) {
        if (!(OptimizerFactory.getDefaultAccessorCompiler() instanceof ReflectiveAccessorOptimizer)) {
            System.out.println("MVEL's optimizer is " + OptimizerFactory.getDefaultAccessorCompiler());
            return;
        }
        for (String[] rule : ClassNameRootFactTest.RULES) {
            for (int cleanRunsFirst : CLEAN_RUNS_FIRST) {
                String failure = failure(rule[1], rule[2], cleanRunsFirst);
                if (failure != null) {
                    System.out.println(rule[0] + ": " + failure);
                    return;
                }
            }
        }
        System.out.println(DONE);
    }

    /**
     * Runs a rule {@code cleanRunsFirst} times without the fact, once with it, then {@value #RUNS_AFTER} times without
     * it again.
     *
     * @param condition      The rule's condition
     * @param action         The rule's action, which puts 1 in the output under {@code r}
     * @param cleanRunsFirst How many runs without the fact come first
     * @return What went wrong, or {@code null} if the run with the fact was rejected and every other run put 1 in the
     *         output
     */
    static @Nullable String failure(String condition, String action, int cleanRunsFirst) {
        try (RulesEngine<Map<String, Object>> engine = ClassNameRootFactTest.engine(condition, action)) {
            String failure = cleanRuns(engine, cleanRunsFirst, "before");
            if (failure != null) {
                return failure;
            }
            try {
                engine.run(ClassNameRootFactTest.withFact("java", ClassNameRootFactTest.PACKAGE_LIKE));
                return "the run with 'java' after " + cleanRunsFirst + " wasn't rejected";
            } catch (IllegalArgumentException e) {
                if (!ClassNameRootFactTest.rejection("java").equals(e.getMessage())) {
                    return "the run with 'java' after " + cleanRunsFirst + ": " + e.getMessage();
                }
            }
            return cleanRuns(engine, RUNS_AFTER, "after the run with 'java' after " + cleanRunsFirst + ",");
        }
    }

    private static @Nullable String cleanRuns(RulesEngine<Map<String, Object>> engine, int runs, String when) {
        for (int run = 0; run < runs; run++) {
            Map<String, Object> output;
            try {
                output = engine.run(ClassNameRootFactTest.amount());
            } catch (RuntimeException e) {
                return when + " run " + run + " failed: " + e;
            }
            if (!Map.of("r", 1).equals(output)) {
                return when + " run " + run + " returned " + output;
            }
        }
        return null;
    }
}
