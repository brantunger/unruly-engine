package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #965: on JDK 25 and later, a string concatenation of several values whose first link overflows the stack fails for
 * good: every later use of that call site, at any depth, throws {@link BootstrapMethodError}. A rule's failure message
 * was one, so a JVM's first failing condition or action run deep in a stack could leave every later failing rule of
 * every engine throwing {@code BootstrapMethodError} instead of {@link RuleExecutionException}. The engine's code is
 * now compiled without such concatenations. The test runs {@link DeepFirstFailureScenario} in a new JVM, so the deep
 * failure is the JVM's first, and checks that the same failure at the top of the stack still fails the rule.
 */
@DisplayName("a first failing rule deep in a stack leaves later failures failing the rule (#965)")
class DeepFirstFailureTest {

    // The scenario's recursion kept interpreted, so it reaches the same depth each time it looks for the end of the
    // stack. The engine's check is left to be compiled, as the scenario has it be before it runs deep.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + DeepFirstFailureScenario.class.getName() + "::descend";
    // Each method compiled as soon as it is queued, while its caller waits, so the scenario's warm-up compiles the
    // check even on a busy machine, where the JIT's threads could otherwise fall behind it.
    private static final String COMPILED_IN_TIME = "-Xbatch";

    @Test
    @DisplayName("deep, a rule's first failure overflows or fails the rule; at the top, the same one fails the rule")
    void laterFailuresFailTheRule(@TempDir Path dir) throws IOException, InterruptedException {
        assumeTrue(Runtime.version().feature() >= 25,
                "only JDK 25 and later keep a string concatenation's failed first link for good");
        List<String> lines = ChildJvm.run(dir, DeepFirstFailureScenario.class, "-XX:CompileCommand=quiet",
                INTERPRETED_SCENARIO, COMPILED_IN_TIME).lines().toList();

        assertNotEquals("0", value(lines, DeepFirstFailureScenario.OVERFLOWED),
                "runs that overflowed, so the walk started at the end of the stack:\n" + String.join("\n", lines));
        assertTrue(failsTheRule(value(lines, DeepFirstFailureScenario.DEEP)),
                "the first run that didn't overflow:\n" + String.join("\n", lines));
        assertTrue(failsTheRule(value(lines, DeepFirstFailureScenario.SHALLOW)),
                "the run at the top of the stack:\n" + String.join("\n", lines));
    }

    // Whether what a run threw, as the scenario printed it, is a RuleExecutionException, of any of its classes.
    private static boolean failsTheRule(String thrown) {
        try {
            return RuleExecutionException.class.isAssignableFrom(Class.forName(thrown.split(":", 2)[0]));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
