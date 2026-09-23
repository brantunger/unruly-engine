package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a stack overflow while MVEL reports its first error doesn't break MVEL errors for the rest of the JVM")
class ErrorUtilInitializationTest {

    /**
     * Runs {@link DeepRuleScenario} in a new JVM, where no MVEL class has been initialized yet. The scenario doesn't
     * run in this JVM: other tests have initialized MVEL here already, and the coverage agent instruments classes as
     * they load, which changes how much stack the overflowing compile has left.
     */
    private static String runScenario() throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                "-cp", System.getProperty("java.class.path"), DeepRuleScenario.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the scenario didn't finish");
        return output;
    }

    @Test
    @DisplayName("a deeply nested rule on a small stack fails alone, and a later syntax error is reported normally")
    void overflowOnlyFailsItsRule() throws Exception {
        String output = runScenario();

        List<String> outcomes = output.lines().filter(line -> line.startsWith(DeepRuleScenario.OUTCOMES))
                .map(line -> line.substring(DeepRuleScenario.OUTCOMES.length()))
                .toList();
        assertEquals(List.of("RuleCompilationException,RuleCompilationException"), outcomes,
                "the deep rule, then the syntax error; scenario output:\n" + output);
    }
}
