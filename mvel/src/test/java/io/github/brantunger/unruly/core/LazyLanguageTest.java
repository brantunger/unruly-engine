package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an engine doesn't load MVEL until it loads a rule list")
class LazyLanguageTest {

    private static final String MVEL_CLASS = "org.mvel2.";

    /**
     * Runs {@link LazyLanguageScenario} in a new JVM that logs every class it loads. This JVM has loaded MVEL already,
     * for other tests.
     */
    private static List<String> runScenario(Path dir) throws IOException, InterruptedException {
        return ChildJvm.run(dir, LazyLanguageScenario.class, "-Xlog:class+load=info:stdout").lines().toList();
    }

    @Test
    @DisplayName("building an engine loads no MVEL class; loading a rule list with an MVEL rule does")
    void mvelLoadedWithRules(@TempDir Path dir) throws Exception {
        List<String> lines = runScenario(dir);
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
