package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #507: with DEBUG off, which is how most applications run, the listener's callbacks return before they name the
 * rule, so they cost a level check and nothing more. The tests' own JVM logs the listener at DEBUG, and SLF4J reads
 * its levels once, so this runs in a JVM of its own. That JVM gets this one's JaCoCo agent, when it has one, so the
 * callbacks' early return counts towards the coverage gate: the agent appends to the same execution data file.
 */
@DisplayName("LoggingRuleListener does nothing but check the level when DEBUG is off")
class LoggingRuleListenerDebugOffTest {

    @Test
    @DisplayName("with DEBUG off, no callback reads the rule")
    void callbacksDontReadTheRule(@TempDir Path dir) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // Output to a file, so waiting is bounded by waitFor and not by the child closing a pipe.
        Path log = dir.resolve("scenario.log");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        // No agent, as in an IDE run, adds nothing, and the scenario runs the same.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacocoagent"))
                .forEach(command::add);
        command.addAll(List.of("-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                "-cp", System.getProperty("java.class.path"), LoggingRuleListenerDebugOffScenario.class.getName()));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);

        assertTrue(finished, "the scenario didn't finish:\n" + output);
        assertEquals(0, process.exitValue(), "scenario output:\n" + output);
        assertTrue(output.contains(LoggingRuleListenerDebugOffScenario.DONE + " []"), output);
    }
}
