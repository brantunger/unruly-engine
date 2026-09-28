package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

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
        // No agent, as in an IDE run, adds nothing, and the scenario runs the same.
        String[] agent = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacocoagent"))
                .toArray(String[]::new);

        String output = ChildJvm.run(dir, LoggingRuleListenerDebugOffScenario.class, agent);

        assertTrue(output.contains(LoggingRuleListenerDebugOffScenario.DONE + " []"), output);
    }
}
