package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #945: a language the engine finds but wasn't built to use, one the builder doesn't name, even if it's the only
 * language found and so the default, is prepared when a rule list first uses it, once the room for that is checked. A
 * first use deep in a stack throws {@link StackOverflowError} from the check, before any of the language's classes is
 * touched, so none is left unusable: a first MVEL load that overflowed in MVEL's own classes used to fail every later
 * MVEL engine. It runs {@link DeepFirstUseScenario} in a new JVM, so the load is MVEL's first, with the check's
 * recursion and the scenario's kept interpreted, as {@code DeepFirstBuildTest} does in the core module.
 */
@DisplayName("a deep first use of a language the engine wasn't built to use checks the room for preparing it (#945)")
class DeepFirstUseTest {

    @Test
    @DisplayName("deep, MVEL's first load overflows in the check; then MVEL loads, runs and reports errors")
    void checkedBeforeFirstUse(@TempDir Path dir) throws IOException, InterruptedException {
        assertCheckedBeforeFirstUse(dir, "false");
    }

    @Test
    @DisplayName("deep, MVEL's first load overflows in the check when MVEL is the only language and isn't named")
    void onlyFoundCheckedBeforeFirstUse(@TempDir Path dir) throws IOException, InterruptedException {
        assertCheckedBeforeFirstUse(dir, "true");
    }

    private static void assertCheckedBeforeFirstUse(Path dir, String onlyFound)
            throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, DeepFirstUseScenario.class, "-XX:CompileCommand=quiet",
                "-D" + DeepFirstUseScenario.ONLY_FOUND + "=" + onlyFound,
                "-XX:CompileCommand=exclude,io.github.brantunger.unruly.core.StackHeadroom::descend",
                "-XX:CompileCommand=exclude," + DeepFirstUseScenario.class.getName() + "::descend").lines().toList();
        String output = String.join("\n", lines);

        assertNotEquals("0", value(lines, DeepFirstUseScenario.CHECKS_FAILED), "loads the check failed:\n" + output);
        assertEquals(DeepFirstUseScenario.CHECK, value(lines, DeepFirstUseScenario.LAST_OVERFLOW),
                "where the last load that overflowed overflowed:\n" + output);
        assertEquals("nothing", value(lines, DeepFirstUseScenario.DEEP),
                "the first load that didn't overflow:\n" + output);
        assertEquals("ok", value(lines, DeepFirstUseScenario.SHALLOW), "MVEL at the top of the stack:\n" + output);
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
