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
 * module's, MVEL's, the engine's or the JDK's, with these exceptions: the scenario's own classes, which stand for an
 * application's; the hidden classes the JDK makes for method handles when they are first needed, which have no name to
 * initialize ahead of time; and those that {@code prepare()} leaves to a first use, which the test says rather than
 * checks covered (#1012). A rule's first inline list, {@code new} and {@code soundslike} each initialize one class of
 * MVEL's: {@code CollectionParser}, {@code NewObjectNode} and {@code Soundex}. Its first read of a property through its
 * getter initializes none, as {@code prepare()} initializes {@code GetterAccessor}, whose empty array every call of a
 * method without arguments reads. With the engine logging, as an application's SLF4J provider may, the first message it
 * logs initializes SLF4J's {@code Level} and {@code FormattingTuple}, here with slf4j-simple. The test checks that each
 * step initializes exactly those, so it fails if one stops reaching its class, and if a step initializes any other. It
 * is checked with MVEL's JIT on, as it is by default, and off, as in a native image, which sets MVEL's optimizer up
 * with other classes, and with the engine logging.
 * </p>
 *
 * <p>
 * The scenario's last step is said rather than checked covered too: with the JIT on, MVEL's first compile of an
 * accessor with ASM, after more than 50 runs of it within 100 ms, initializes ASM's classes and the JDK's that MVEL's
 * JIT uses, which {@code prepare()} doesn't. Deep in a stack, that compile may fail, and isn't tried again: MVEL marks
 * an accessor compiled before it compiles it, so it stays reflective, slower but correct. If the overflow strikes a
 * class's static initializer, of ASM's or the JDK's, that class stays unusable for the JVM's life, and every other
 * accessor's first compile fails one run the same way. The test checks that one of ASM's classes was initialized there,
 * so it can't pass because the runs were too slow for the JIT, and that none of the engine's or this module's was. With
 * the JIT off, the step initializes nothing.
 * </p>
 */
@DisplayName("building an engine with MVEL initializes the classes MVEL's first load and run use, but those of the "
        + "features and the JIT it leaves to their first use (#945, #1012)")
class FirstUseClassInitializationTest {

    private static final String INITIALIZING = "Initializing '";
    // The engine logging as an application's SLF4J provider may: this test's logs at INFO and above.
    private static final String LOGGING = "-Dorg.slf4j.simpleLogger.defaultLogLevel=info";
    // What -Xlog:class+init adds to the line of a class with no static initializer, no <clinit> method.
    private static final String NO_INITIALIZER = "(no method)";
    private static final String SCENARIO = INITIALIZING + FirstUseScenario.class.getName().replace('.', '/');
    // A hidden class's name ends in '+' and its address, which no other class's has.
    private static final Pattern LAMBDA_FORM = Pattern.compile(Pattern.quote(INITIALIZING)
            + "java/lang/invoke/LambdaForm\\$[A-Za-z]+\\+0x");
    // The start of what initializedByFirstSteps records for a class the last step, MVEL's JIT, initialized.
    private static final String IN_JIT_STEP = FirstUseScenario.JIT + ": ";
    // What the steps that use one of MVEL's features for the first time initialize, which prepare() leaves to them.
    private static final List<String> BY_FEATURES = List.of(
            FirstUseScenario.INLINE_LIST + ": org/mvel2/util/CollectionParser",
            FirstUseScenario.NEW_OBJECT + ": org/mvel2/ast/NewObjectNode",
            FirstUseScenario.SOUNDSLIKE + ": org/mvel2/util/Soundex");
    // What the first message logged initializes, with slf4j-simple, which building an engine leaves to it.
    private static final List<String> BY_LOGGING = List.of(
            FirstUseScenario.FAILING_NESTED_LOAD + ": org/slf4j/event/Level",
            FirstUseScenario.FAILING_NESTED_LOAD + ": org/slf4j/helpers/FormattingTuple");
    // Set by Unreachable's static initializer, and read here, as reading a field of Unreachable would initialize it.
    private static final AtomicBoolean UNREACHABLE_INITIALIZED = new AtomicBoolean();

    @Test
    @DisplayName("with MVEL's JIT on, MVEL's first steps initialize no class with a static initializer but those of "
            + "the features prepare() leaves to them, and its first compile with ASM ASM's")
    void initializedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        assertInitialized(BY_FEATURES, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=false"));
    }

    @Test
    @DisplayName("with MVEL's JIT off, MVEL's first steps initialize no class with a static initializer but those of "
            + "the features prepare() leaves to them")
    void initializedWhenBuiltWithoutJit(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(BY_FEATURES, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=true"),
                "initialized by first steps");
    }

    @Test
    @DisplayName("with the engine logging, the first message it logs initializes SLF4J's Level and FormattingTuple, "
            + "and MVEL's first steps nothing else but as with it off")
    void initializedWhenBuiltWithLogging(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> expected = new ArrayList<>(BY_FEATURES);
        expected.addAll(BY_LOGGING);
        assertInitialized(expected, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=false", LOGGING));
    }

    @Test
    @DisplayName("a class the lookup can't reach is skipped, and left to its first use")
    void unreachableClassSkipped() {
        // A lookup with public access only can't reach this module's package-private classes.
        assertDoesNotThrow(() -> MvelExpressionLanguage.initialize(MethodHandles.publicLookup(),
                List.of(Unreachable.class)));
        assertFalse(UNREACHABLE_INITIALIZED.get(), "initialized");
    }

    // The steps before the last initialized just what was expected, and the last, MVEL's JIT, one of ASM's classes,
    // so it did compile, and none of the engine's or this module's. Which of the JDK's it initializes differs between
    // JDK releases.
    private static void assertInitialized(List<String> expected, List<String> initialized) {
        List<String> byJit = initialized.stream().filter(entry -> entry.startsWith(IN_JIT_STEP)).toList();
        assertEquals(expected, initialized.stream().filter(entry -> !entry.startsWith(IN_JIT_STEP)).toList(),
                "initialized by first steps");
        assertTrue(byJit.stream().anyMatch(entry -> entry.startsWith(IN_JIT_STEP + "org/mvel2/asm/")),
                "no class of ASM's initialized by the runs that MVEL's JIT compiles in: " + byJit);
        assertEquals(List.of(), byJit.stream().filter(entry -> entry.startsWith(IN_JIT_STEP + "io/github/brantunger/"))
                .toList(), "the engine's or this module's, initialized by MVEL's JIT");
    }

    // Each class with a static initializer that the scenario's steps initialized, but the scenario's own and the JDK's
    // hidden ones, as the step it was initialized in and its name, without the address that changes each run.
    private static List<String> initializedByFirstSteps(Path dir, String... options)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(options));
        command.add("-Xlog:class+init=info:stdout");
        List<String> lines = ChildJvm.run(dir, FirstUseScenario.class, command.toArray(String[]::new)).lines()
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
                int name = at + INITIALIZING.length();
                initialized.add(step + ": " + line.substring(name, line.indexOf('\'', name)));
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
