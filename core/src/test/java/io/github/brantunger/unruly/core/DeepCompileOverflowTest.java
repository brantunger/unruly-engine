package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1013: a load called deep in a stack, past the check of its stack's room, can still run out of stack while it
 * compiles a short, valid expression, as MVEL's first load does while it loads its compiler's classes. It was reported
 * as "the expression is too long or too deeply nested to compile", which sent the reader looking for a fault in the
 * rule rather than in where the load was called. The engine now checks the room left where it caught the overflow,
 * and reports a stack that ran out when too little is left. It runs {@link DeepCompileOverflowScenario} in a new JVM,
 * whose language overflows on every compile, at the top of a stack and at the first depth a load gets through its
 * check: the room the check makes sure of, {@value StackHeadroom#FRAMES} frames, is a sixth of the
 * {@value StackHeadroom#INITIALIZING_FRAMES} the engine then checks for, in the same recursion, so it is short there
 * whatever the JIT has compiled.
 */
@DisplayName("a compile that overflows is reported as a stack that ran out when the load was called too deep (#1013)")
class DeepCompileOverflowTest {

    // The scenario's recursion kept interpreted, so it reaches the same depth each time it looks for the end of the
    // stack.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + DeepCompileOverflowScenario.class.getName() + "::descend";

    @Test
    @DisplayName("at the top of a stack, the expression is too long; deep, the stack ran out")
    void toldApart(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, DeepCompileOverflowScenario.class, "-XX:CompileCommand=quiet",
                INTERPRETED_SCENARIO).lines().toList();
        String output = String.join("\n", lines);

        assertEquals("Action for rule 'r' failed to compile: the expression is too long or too deeply nested to "
                + "compile", value(lines, DeepCompileOverflowScenario.SHALLOW), "the load at the top:\n" + output);
        String checksFailed = value(lines, DeepCompileOverflowScenario.CHECKS_FAILED);
        assertTrue(checksFailed.matches("\\d+") && Integer.parseInt(checksFailed) > 0,
                "loads that overflowed before the compile:\n" + output);
        assertEquals("Action for rule 'r' failed to compile: the stack ran out: it was compiled too deep in the stack, "
                + "or on a thread whose stack is too small", value(lines, DeepCompileOverflowScenario.DEEP),
                "the first load that got to the compile:\n" + output);
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
