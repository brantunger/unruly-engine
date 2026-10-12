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
 * #1123: a language's first run also checks for {@value StackHeadroom#FIRST_RUN_FRAMES} frames, on top of the
 * {@value StackHeadroom#FRAMES} every run checks for (#1066). {@code FirstRunRoomTest} reaches that branch through a
 * fault injected just before the check, so it would pass with the check gone; these tests fail if the check isn't
 * made, or if its frames fall below what was measured.
 */
@DisplayName("a language's first run is refused by the larger check of its stack's room (#1123)")
class FirstRunRoomPinTest {

    // The checks' recursion kept interpreted, as it is in the scenario's probes of them, so the run's checks overflow
    // where the probes found they would.
    private static final String INTERPRETED_CHECK =
            "-XX:CompileCommand=exclude," + StackHeadroom.class.getName() + "::descend";
    // And the scenario's recursion, so it reaches the same depth each time it looks for where the checks fit.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + FirstRunRoomPinScenario.class.getName() + "::descend";

    @Test
    @DisplayName("the first run's check reserves more than the run's by the frames measured")
    void firstRunReservesWhatWasMeasured() {
        // The "up to 104 frames more" that FIRST_RUN_FRAMES' Javadoc records first runs needed with the check at
        // FRAMES: if the reserve is measured again, this changes with that Javadoc.
        assertTrue(StackHeadroom.FIRST_RUN_FRAMES >= StackHeadroom.FRAMES + 104,
                StackHeadroom.FIRST_RUN_FRAMES + " < " + StackHeadroom.FRAMES + " + 104");
    }

    @Test
    @DisplayName("deep, where only the larger check overflows, a language's first run overflows in it, before any "
            + "listener is told it began")
    void refusedByTheLargerCheck(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, FirstRunRoomPinScenario.class, "-XX:CompileCommand=quiet",
                INTERPRETED_CHECK, INTERPRETED_SCENARIO).lines().toList();
        String output = String.join("\n", lines);
        // Shown in the test's report, so how deep the run was made on each JVM can be compared.
        System.out.println(value(lines, FirstRunRoomPinScenario.JAVA) + ": "
                + value(lines, FirstRunRoomPinScenario.WINDOW) + "; ran at depth "
                + value(lines, FirstRunRoomPinScenario.DEPTH));

        assertEquals("missing", value(lines, FirstRunRoomPinScenario.FAILED), "what kept the run from being made:\n"
                + output);
        assertTrue(value(lines, FirstRunRoomPinScenario.OUTCOME).startsWith(StackOverflowError.class.getName()),
                "what the run threw:\n" + output);
        assertEquals("checkFirstRun", value(lines, FirstRunRoomPinScenario.CHECK),
                "the check the run overflowed in:\n" + output);
        assertEquals("0", value(lines, FirstRunRoomPinScenario.BEFORE_RUNS),
                "times a listener was told the run began:\n" + output);
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
