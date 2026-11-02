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
 * #1305: under {@code -Xcomp}, which compiles every method before its first run, the JVM's first build ran
 * {@link StackHeadroom#checkInitializing()} compiled, at a third of the bytes a frame it takes interpreted, so a first
 * build deep in a stack passed the check and then overflowed inside the JDK clock's static initializer, which left
 * the clock, and every later build, failing with {@link NoClassDefFoundError} for the life of the JVM. Now the check
 * reads from the JVM's {@code java.vm.info} whether it compiles every method first, and if so recurses
 * {@link StackHeadroom#COMPILED_INITIALIZING_FRAMES} frames. The test runs {@link CompiledFirstBuildScenario} in a new
 * JVM with {@code -Xcomp}, so the deep build is the JVM's first, and checks that each build either overflowed or
 * threw nothing, and that the clock, the instant formatter, {@code ExceptionInInitializerError} and engine builds
 * work after them.
 */
@DisplayName("under -Xcomp, a first build deep in a stack leaves the clock and later builds working (#1305)")
class CompiledFirstBuildTest {

    // The scenario's recursion kept interpreted, so it reaches the same depth each time it looks for the end of the
    // stack; the engine's own methods, the check's among them, are compiled before their first run.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + CompiledFirstBuildScenario.class.getName() + "::descend";

    @Test
    @DisplayName("java.vm.info that starts with \"compiled mode\" is the mode that compiles every method first")
    void compiledMode() {
        assertTrue(StackHeadroom.compilesEverything("compiled mode"));
        assertTrue(StackHeadroom.compilesEverything("compiled mode, sharing"));
        assertTrue(StackHeadroom.compilesEverything("compiled mode, emulated-client, sharing"));
    }

    @Test
    @DisplayName("any other java.vm.info, or none, is not")
    void otherModes() {
        assertFalse(StackHeadroom.compilesEverything(null));
        assertFalse(StackHeadroom.compilesEverything(""));
        assertFalse(StackHeadroom.compilesEverything("compiled"));
        assertFalse(StackHeadroom.compilesEverything("Compiled mode"));
        assertFalse(StackHeadroom.compilesEverything("mixed mode, sharing"));
        assertFalse(StackHeadroom.compilesEverything("interpreted mode, sharing"));
        assertFalse(StackHeadroom.compilesEverything("mixed mode, compiled mode"));
    }

    @Test
    @DisplayName("deep, the JVM's first build under -Xcomp overflows in the check or builds, and leaves nothing broken")
    void deepFirstBuild(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, CompiledFirstBuildScenario.class, "-Xcomp",
                "-XX:CompileCommand=quiet", INTERPRETED_SCENARIO).lines().toList();
        String output = String.join("\n", lines);

        assertTrue(StackHeadroom.compilesEverything(value(lines, CompiledFirstBuildScenario.VM_INFO)),
                "the scenario's JVM doesn't compile every method first:\n" + output);
        assertNotEquals("0", value(lines, CompiledFirstBuildScenario.IN_THE_CHECK),
                "builds that overflowed in the check:\n" + output);
        assertEquals("true", value(lines, CompiledFirstBuildScenario.BUILT),
                "whether a deep build got past the check, before its depths ran out:\n" + output);
        assertEquals(CompiledFirstBuildScenario.NOTHING, value(lines, CompiledFirstBuildScenario.DEEP),
                "what the first build that didn't overflow threw:\n" + output);
        assertEquals("true", value(lines, CompiledFirstBuildScenario.CLOCK), "the clock after the builds:\n" + output);
        assertEquals("1970-01-01T00:00:01Z", value(lines, CompiledFirstBuildScenario.FORMATTER),
                "Instant.toString() after the builds:\n" + output);
        assertEquals("probe", value(lines, CompiledFirstBuildScenario.ERROR),
                "a new ExceptionInInitializerError after the builds:\n" + output);
        assertEquals("{hit=true}", value(lines, CompiledFirstBuildScenario.ENGINE),
                "an engine built, loaded and run after the builds:\n" + output);
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
