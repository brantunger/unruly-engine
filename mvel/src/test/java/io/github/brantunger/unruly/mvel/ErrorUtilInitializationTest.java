package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a stack overflow while MVEL reports its first error doesn't break MVEL errors for the rest of the JVM")
class ErrorUtilInitializationTest {

    /**
     * Runs {@link DeepRuleScenario} in a new JVM, where no MVEL class has been initialized yet. The scenario doesn't
     * run in this JVM: other tests have initialized MVEL here already, and the coverage agent instruments classes as
     * they load, which changes how much stack the overflowing compile has left.
     */
    private static String runScenario(Path dir) throws IOException, InterruptedException {
        return ChildJvm.run(dir, DeepRuleScenario.class);
    }

    @Test
    @DisplayName("a deeply nested rule on a small stack fails alone, and a later syntax error is reported normally")
    void overflowOnlyFailsItsRule(@TempDir Path dir) throws Exception {
        String output = runScenario(dir);

        List<String> outcomes = output.lines().filter(line -> line.startsWith(DeepRuleScenario.OUTCOMES))
                .map(line -> line.substring(DeepRuleScenario.OUTCOMES.length()))
                .toList();
        assertEquals(List.of("RuleCompilationException,RuleCompilationException"), outcomes,
                "the deep rule, then the syntax error; scenario output:\n" + output);
    }
}
