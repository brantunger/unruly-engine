package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.language.ImportLimits;
import io.github.brantunger.unruly.api.language.Throwables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1241: MVEL reads what it catches with the engine's {@link Throwables}, in a {@code catch} after an overflow too,
 * and bounds an inline import with {@link ImportLimits}, where a class's first load deep in a stack could overflow
 * itself (#1097). An engine preloads both when it's built, which hides whether {@code prepare()} does; a language
 * used without an engine, as through the contract kit's contexts, has only {@code prepare()}. So this runs
 * {@link BarePrepareScenario} in a new JVM, where no engine is built, and reads the {@code Initializing '...'} lines
 * {@code -Xlog:class+init} prints, a class without a static initializer included, to check {@code prepare()}
 * initializes both, and the engine's classes they only delegate to, {@code internal.Causes}, {@code internal.Lookups}
 * and {@code internal.ImportSize}, which this module can't name and which have no static initializer either, so only a
 * call loads them. {@code ImportLimits}' limits are compile-time constants, which javac copies into the classes that
 * read them, and nothing else in {@code prepare()} imports or looks a class up.
 */
@DisplayName("a bare prepare(), with no engine, initializes the engine's readers MVEL calls after a failure (#1241)")
class BarePrepareInitializationTest {

    private static final String INITIALIZING = "Initializing '";
    // The facades, and the engine's classes they delegate to, which this module can't name in its own code.
    private static final List<String> CLASSES = List.of(Throwables.class.getName(), ImportLimits.class.getName(),
            "io.github.brantunger.unruly.internal.Causes", "io.github.brantunger.unruly.internal.Lookups",
            "io.github.brantunger.unruly.internal.ImportSize");

    @Test
    @DisplayName("Throwables and ImportLimits, and the engine's classes they delegate to, are initialized by prepare(),"
            + " not before it")
    void readersInitializedByPrepare(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, BarePrepareScenario.class, "-Xlog:class+init=info:stdout").lines()
                .toList();
        int before = lines.indexOf(BarePrepareScenario.BEFORE);
        int after = lines.indexOf(BarePrepareScenario.AFTER);
        assertTrue(before >= 0 && after > before, "markers missing:\n" + String.join("\n", lines));

        List<String> where = new ArrayList<>();
        for (String type : CLASSES) {
            String initializing = INITIALIZING + type.replace('.', '/') + "'";
            int at = -1;
            for (int i = 0; i < lines.size() && at < 0; i++) {
                if (lines.get(i).contains(initializing)) {
                    at = i;
                }
            }
            where.add(type.substring(type.lastIndexOf('.') + 1) + ": " + (at < 0 ? "never"
                    : at < before ? "before prepare()" : at < after ? "by prepare()" : "after prepare()"));
        }

        assertEquals(List.of("Throwables: by prepare()", "ImportLimits: by prepare()", "Causes: by prepare()",
                "Lookups: by prepare()", "ImportSize: by prepare()"), where);
    }
}
