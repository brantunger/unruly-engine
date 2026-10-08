package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1102: once an analysis pass or a run has asked for the class loader more than
 * {@value CallSites#UNCOUNTED_CALLS} times, MVEL's loop detection walks the stack, and a walk needs more room than the
 * engine's checks make sure of: it overflows in the JDK's code that walks it, usually as the walk starts, sometimes as
 * it fetches more frames, and on JDK 25 and 26 the JDK may wrap the overflow in an {@link InternalError}. Deep in a
 * stack, a valid rule whose analysis or run walks failed as an overflow, and a loop lost its own message. A walk
 * with no room is now skipped, its calls counted at no place, so the valid rule loads and runs, and the limit in all
 * stops the loop, with its message. It runs {@link DeepWalkScenario} in a new JVM, with the scenario's recursion kept
 * interpreted and the engine's check of the room compiled, as {@code DeepFirstFailureTest} in the core module has
 * them, and checks that no step past the check overflowed walking the stack, and that the steps from the end of the
 * stack up end in what the step does at the top. {@link LoopDetectionLambdaTest} fails if the methods of
 * {@link CallSites} that walk it, which the scenario looks for, aren't there.
 *
 * <p>
 * The steps past the check may still overflow elsewhere, at depths close to it: a load or a first run of a long chain
 * looks each part's name up as a class, in {@link ExactNameClassLoader}, which takes more room than the check makes
 * sure of. On JDK 21, up to 10 steps did, and before #1102, 82 to 100, of which 72 to 90 overflowed walking.
 * </p>
 */
@DisplayName("deep in a stack, a walk of the stack with no room is skipped, so a valid rule that walks loads and runs, "
        + "and a loop fails with its own message (#1102)")
class DeepWalkTest {

    // The scenario's recursion kept interpreted, so it reaches the same depth each time it looks for the end of the
    // stack. The engine's check is left to be compiled, as the scenario has it be before it runs deep.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + DeepWalkScenario.class.getName() + "::descend";
    // Each method compiled as soon as it is queued, while its caller waits, so the scenario's warm-up compiles the
    // check even on a busy machine, where the JIT's threads could otherwise fall behind it.
    private static final String COMPILED_IN_TIME = "-Xbatch";

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
        DeepWalkScenario.VALID_LOAD + ", " + DeepWalkScenario.OK,
        DeepWalkScenario.VALID_RUN + ", " + DeepWalkScenario.OK,
        DeepWalkScenario.ANALYSIS_LOOP + ", " + DeepWalkScenario.LOOPED,
        DeepWalkScenario.RUN_LOOP + ", " + DeepWalkScenario.LOOPED})
    @DisplayName("deep, no step past the engine's check of the room overflows walking the stack, and the steps end in "
            + "what the step does at the top of the stack")
    void noWalkOverflows(String step, String expected, @TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, DeepWalkScenario.class, "-XX:CompileCommand=quiet",
                "-D" + DeepWalkScenario.STEP + "=" + step, INTERPRETED_SCENARIO, COMPILED_IN_TIME).lines().toList();
        String output = String.join("\n", lines);

        assertEquals(expected, value(lines, DeepWalkScenario.AT_THE_TOP), "at the top of the stack:\n" + output);
        assertNotEquals("0", value(lines, DeepWalkScenario.CHECKS_FAILED), "steps the check failed:\n" + output);
        assertEquals("0", value(lines, DeepWalkScenario.WALKS_OVERFLOWED), "steps that overflowed walking:\n" + output);
        assertEquals(expected, value(lines, DeepWalkScenario.LAST), "the last step:\n" + output);
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
