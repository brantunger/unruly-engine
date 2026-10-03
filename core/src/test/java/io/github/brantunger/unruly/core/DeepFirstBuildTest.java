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
 * #945: the JVM's first build checks that its stack has room for initializing the classes engines use before anything
 * else, its settings included, so a first build deep in a stack throws {@link StackOverflowError} before any class is
 * touched rather than leaving one unusable; and a build checks the room for preparing a language of a class no engine
 * has prepared before it prepares it. Each runs {@link DeepFirstBuildScenario} in a new JVM, so the build is the JVM's
 * first.
 */
@DisplayName("a first build deep in a stack checks the room for initializing classes before anything else (#945)")
class DeepFirstBuildTest {

    // The check's recursion kept interpreted, as it is when the JVM's first engine is built: the scenario builds many
    // times, and compiled, the check's frames are a third of the size, so it would no longer be a build's deepest step.
    private static final String INTERPRETED_CHECK =
            "-XX:CompileCommand=exclude," + StackHeadroom.class.getName() + "::descend";
    // And the scenario's recursion, so it reaches the same depth each time it looks for the end of the stack.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + DeepFirstBuildScenario.class.getName() + "::descend";

    @Test
    @DisplayName("deep, a first build whose settings are wrong overflows in the check; at the top, the settings fail")
    void checkedBeforeSettings(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "settings");

        assertEquals(DeepFirstBuildScenario.CHECK, value(lines, DeepFirstBuildScenario.LAST_OVERFLOW),
                "where the last build that overflowed overflowed:\n" + String.join("\n", lines));
        assertTrue(value(lines, DeepFirstBuildScenario.DEEP).startsWith(IllegalStateException.class.getName()),
                "the first build that didn't overflow:\n" + String.join("\n", lines));
        assertTrue(value(lines, DeepFirstBuildScenario.SHALLOW).startsWith(IllegalStateException.class.getName()),
                "the build at the top of the stack:\n" + String.join("\n", lines));
    }

    @Test
    @DisplayName("a language of a class not prepared before is prepared only once the room for it is checked")
    void checkedBeforePrepared(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "prepare");

        assertNotEquals("0", value(lines, DeepFirstBuildScenario.CHECKS_FAILED),
                "builds the check failed:\n" + String.join("\n", lines));
        assertEquals("0", value(lines, DeepFirstBuildScenario.PREPARED_WHEN_CHECK_FAILED),
                "times builds the check failed prepared the language:\n" + String.join("\n", lines));
        assertEquals("1", value(lines, DeepFirstBuildScenario.PREPARED),
                "times the language was prepared:\n" + String.join("\n", lines));
    }

    private static List<String> scenario(Path dir, String mode) throws IOException, InterruptedException {
        return ChildJvm.run(dir, DeepFirstBuildScenario.class, "-XX:CompileCommand=quiet", INTERPRETED_CHECK,
                INTERPRETED_SCENARIO, "-D" + DeepFirstBuildScenario.MODE + "=" + mode).lines().toList();
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
