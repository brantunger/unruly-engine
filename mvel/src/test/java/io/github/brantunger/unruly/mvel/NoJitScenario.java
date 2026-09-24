package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer;

import java.util.Map;
import java.util.Set;

/**
 * Run by {@link CalledCodeCauseTest} in a JVM started with {@code -Dmvel2.disable.jit=true}, which MVEL reads once,
 * when it first loads: runs a condition and an action that call a method more times than the JIT would need, failing
 * in {@link #FAILING_RUNS}, and prints {@link #DONE} if MVEL's optimizer is the reflective one, every failure's cause
 * is what the method threw, and MVEL always called the method through reflection.
 */
final class NoJitScenario {

    static final String DONE = "SCENARIO runs done";

    /** The runs in which the method throws, those the JIT would compile the expression in among them. */
    private static final Set<Integer> FAILING_RUNS = Set.of(0, 1, 3, 4, 50, 51, 52, 53, 100, 500, 999);

    private NoJitScenario() {
    }

    public static void main(String[] args) {
        if (!(OptimizerFactory.getDefaultAccessorCompiler() instanceof ReflectiveAccessorOptimizer)) {
            System.out.println("MVEL's optimizer is " + OptimizerFactory.getDefaultAccessorCompiler());
            return;
        }
        boolean same = runs(CalledCodeCauseTest.engine("code.fail() == 1", "output.put('k', 1)"))
                && runs(CalledCodeCauseTest.engine("true", "code.fail()"));
        if (same) {
            System.out.println(DONE);
        }
    }

    private static boolean runs(RulesEngine<Map<String, Object>> engine) {
        try (engine) {
            for (int run = 0; run < 1_000; run++) {
                if (!FAILING_RUNS.contains(run)) {
                    engine.run(CalledCodeCauseTest.facts(null));
                    continue;
                }
                IllegalStateException failure = new IllegalStateException("run " + run);
                CalledCodeCauseTest.Code code = new CalledCodeCauseTest.Code(failure);
                try {
                    engine.run(CalledCodeCauseTest.factsWith(code));
                    System.out.println("run " + run + " didn't fail");
                    return false;
                } catch (RuleExecutionException e) {
                    CalledCodeCauseTest.Stage stage = CalledCodeCauseTest.stage(code);
                    if (e.getCause() != failure || stage == CalledCodeCauseTest.Stage.MIXED
                            || stage == CalledCodeCauseTest.Stage.COMPILED) {
                        System.out.println("run " + run + ", " + stage + ": " + e.getMessage());
                        e.printStackTrace(System.out);
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
