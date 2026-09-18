package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an engine doesn't load MVEL until it loads a rule list")
class LazyLanguageTest {

    private static final String MVEL_CLASS = "org.mvel2.";

    /**
     * Runs {@link LazyLanguageScenario} in a new JVM that logs every class it loads. This JVM has loaded MVEL already,
     * for other tests.
     */
    private static List<String> runScenario() throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-Xlog:class+load=info:stdout",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                "-cp", System.getProperty("java.class.path"), LazyLanguageScenario.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the scenario didn't finish");
        assertEquals(0, process.exitValue(), "scenario output:\n" + output);
        return output.lines().toList();
    }

    @Test
    @DisplayName("building an engine loads no MVEL class; loading a rule list with an MVEL rule does")
    void mvelLoadedWithRules() throws Exception {
        List<String> lines = runScenario();
        int built = lines.indexOf(LazyLanguageScenario.BUILT);
        int loaded = lines.indexOf(LazyLanguageScenario.LOADED);
        assertTrue(built >= 0 && loaded > built, "markers missing");

        List<String> mvelBeforeBuilt = lines.subList(0, built).stream().filter(line -> line.contains(MVEL_CLASS))
                .toList();
        long mvelWhileLoading = lines.subList(built, loaded).stream().filter(line -> line.contains(MVEL_CLASS))
                .count();

        assertEquals(List.of(), mvelBeforeBuilt);
        assertTrue(mvelWhileLoading > 0, "no MVEL class loaded by load()");
    }
}
