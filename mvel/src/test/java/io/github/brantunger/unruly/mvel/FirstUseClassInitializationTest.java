package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #945: MVEL's first load, run or failing load may be nested deep in another run's stack, and a
 * {@link StackOverflowError} inside a class's static initializer leaves the class unusable for the life of the JVM: a
 * first load that overflowed in this module's {@code FactNames} made every later MVEL engine fail, and a failing load
 * that overflowed in {@code MvelCompileErrors} made every later compile error report {@link NoClassDefFoundError}
 * instead. So {@link MvelExpressionLanguage#prepare()}, which the engine calls when it builds an engine with MVEL,
 * initializes them. The test runs {@link FirstUseScenario} in a new JVM, as this JVM's other tests have initialized
 * them already, and reads the {@code Initializing '...'} lines that {@code -Xlog:class+init} prints, which end in
 * {@code (no method)} for a class without a static initializer.
 *
 * <p>
 * Once the engines are built, MVEL's first steps may initialize no class with a static initializer at all, this
 * module's, MVEL's, the engine's or the JDK's, with two exceptions: the scenario's own classes, which stand for an
 * application's, and the hidden classes the JDK makes for method handles when they are first needed, which have no
 * name to initialize ahead of time. It is checked with MVEL's JIT on, as it is by default, and off, as in a native
 * image, which sets MVEL's optimizer up with other classes.
 * </p>
 */
@DisplayName("building an engine with MVEL initializes the classes MVEL's first load and run use (#945)")
class FirstUseClassInitializationTest {

    private static final String INITIALIZING = "Initializing '";
    // What -Xlog:class+init adds to the line of a class with no static initializer, no <clinit> method.
    private static final String NO_INITIALIZER = "(no method)";
    private static final String SCENARIO = INITIALIZING + FirstUseScenario.class.getName().replace('.', '/');
    // A hidden class's name ends in '+' and its address, which no other class's has.
    private static final Pattern LAMBDA_FORM = Pattern.compile(Pattern.quote(INITIALIZING)
            + "java/lang/invoke/LambdaForm\\$[A-Za-z]+\\+0x");
    // Set by Unreachable's static initializer, and read here, as reading a field of Unreachable would initialize it.
    private static final AtomicBoolean UNREACHABLE_INITIALIZED = new AtomicBoolean();

    @Test
    @DisplayName("with MVEL's JIT on, MVEL's first steps initialize no class with a static initializer")
    void initializedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(List.of(), initializedByFirstSteps(dir, "-Dmvel2.disable.jit=false"),
                "initialized by first steps");
    }

    @Test
    @DisplayName("with MVEL's JIT off, MVEL's first steps initialize no class with a static initializer")
    void initializedWhenBuiltWithoutJit(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(List.of(), initializedByFirstSteps(dir, "-Dmvel2.disable.jit=true"),
                "initialized by first steps");
    }

    @Test
    @DisplayName("a class the lookup can't reach is skipped, and left to its first use")
    void unreachableClassSkipped() {
        // A lookup with public access only can't reach this module's package-private classes.
        assertDoesNotThrow(() -> MvelExpressionLanguage.initialize(MethodHandles.publicLookup(),
                List.of(Unreachable.class)));
        assertFalse(UNREACHABLE_INITIALIZED.get(), "initialized");
    }

    // Each class with a static initializer that the scenario's steps initialized, but the scenario's own and the JDK's
    // hidden ones, with the step it was initialized in.
    private static List<String> initializedByFirstSteps(Path dir, String option)
            throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, FirstUseScenario.class, option, "-Xlog:class+init=info:stdout").lines()
                .toList();
        int built = lines.indexOf(FirstUseScenario.BUILT);
        int ran = lines.indexOf(FirstUseScenario.RAN);
        assertTrue(built >= 0 && ran > built, "markers missing:\n" + String.join("\n", lines));
        List<String> initialized = new ArrayList<>();
        String step = "before the first step";
        for (String line : lines.subList(built, ran)) {
            int at = line.indexOf(INITIALIZING);
            if (line.startsWith(FirstUseScenario.STEP)) {
                step = line.substring(FirstUseScenario.STEP.length());
            } else if (at >= 0 && !line.contains(NO_INITIALIZER) && !line.contains(SCENARIO)
                    && !LAMBDA_FORM.matcher(line).find()) {
                initialized.add("\n" + step + ": " + line.substring(at));
            }
        }
        return initialized;
    }

    /** A class no lookup but this package's can reach, whose static initializer records that it ran. */
    static final class Unreachable {
        static {
            UNREACHABLE_INITIALIZED.set(true);
        }

        private Unreachable() {
        }
    }
}
