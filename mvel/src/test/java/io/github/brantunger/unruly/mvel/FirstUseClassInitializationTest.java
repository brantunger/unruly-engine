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
 * initializes them, then evaluates a property read, a method call and a method call with a literal argument once each
 * through MVEL's public API, which loads classes MVEL evaluates them with ahead of a first run, so a first run deep in
 * a stack has fewer to load (#1042, #1066);
 * when the engine prepares MVEL, at {@code build()} or a first load, those evaluations initialize no class with a
 * static initializer that it hasn't, which the test says rather than checks. A bare {@code prepare()} call in an
 * otherwise empty JVM may also initialize JDK classes the reflective call needs. The test runs
 * {@link FirstUseScenario} in a new JVM, as this JVM's other tests have initialized them already, and reads the
 * {@code Initializing '...'} lines that {@code -Xlog:class+init} prints, which end in {@code (no method)} for a class
 * without a static initializer. With MVEL's JIT on and off, it checks that the classes without a static initializer
 * that MVEL's first run used to load for its read, its call and its literal argument are initialized before any step.
 *
 * <p>
 * Once the engines are built, MVEL's first steps may initialize no class with a static initializer at all, this
 * module's, MVEL's, the engine's or the JDK's, with these exceptions: the scenario's own classes, which stand for an
 * application's; the hidden classes the JDK makes for method handles when they are first needed, which have no name to
 * initialize ahead of time; and those that {@code prepare()} leaves to a first use, which the test says rather than
 * checks covered (#1012). A rule's first inline list, {@code new} and {@code soundslike} each initialize one class of
 * MVEL's: {@code CollectionParser}, {@code NewObjectNode} and {@code Soundex}. #1097: a first load that fails for an
 * import with too many parts initializes MVEL's {@code ImportNode}, and one for a package import too far into the
 * text for MVEL to read, the JDK's {@code Formatter}, its {@code FormatSpecifier} and {@code Locale.Category}, which
 * MVEL's own message uses. Its first read of a property through its
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
 *
 * <p>
 * #1093: linking a lambda or a method reference makes a class too, which the same lines show. The code that handles a
 * failure, this module's {@link ExceptionReads} and the engine's, runs only when something fails, so a lambda in it
 * was linked by the JVM's first failure, which could overflow deep in a stack. The scenario's first runs whose MVEL
 * condition and action fail may link no lambda or method reference of the library's. #1097: nor may its first load
 * that fails to compile, its first nested in a run's action, or its first {@code validate()} that fails, as
 * {@code MvelCompileErrors} describes the error only then; and in those steps and the failing runs, a class the JDK
 * makes for a lambda or a method reference of anyone's, the JDK's own included, counts as well, and so does a class of
 * the JDK's regular expressions, which the first compile error reads MVEL's message with.
 * </p>
 */
@DisplayName("building an engine with MVEL initializes the classes MVEL's first load and run use, but those of the "
        + "features and the JIT it leaves to their first use (#945, #1012, #1042, #1066)")
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
    // What a load that fails for an import initializes, which prepare() leaves to it, as it does MVEL's features
    // (#1097): MVEL's ImportNode, which reads an import, for one with too many parts; and the JDK's Formatter, which
    // MVEL's own code formats its message with, for a package import too far into the text for MVEL to read. Each only
    // in its own step, so no other step may initialize them.
    private static final List<String> BY_FAILED_IMPORTS = List.of(
            FirstUseScenario.IMPORT_TOO_LARGE + ": org/mvel2/ast/ImportNode",
            FirstUseScenario.PACKAGE_IMPORT_UNREAD + ": java/util/Formatter",
            FirstUseScenario.PACKAGE_IMPORT_UNREAD + ": java/util/Locale$Category",
            FirstUseScenario.PACKAGE_IMPORT_UNREAD + ": java/util/Formatter$FormatSpecifier");
    // What the first message logged initializes, with slf4j-simple, which building an engine leaves to it.
    private static final List<String> BY_LOGGING = List.of(
            FirstUseScenario.FAILING_NESTED_LOAD + ": org/slf4j/event/Level",
            FirstUseScenario.FAILING_NESTED_LOAD + ": org/slf4j/helpers/FormattingTuple");
    // Where whereInitialized says a class was initialized before the engines were built.
    private static final String WHEN_BUILT = "when built";
    // Classes without a static initializer that MVEL evaluates a property read and a method call with, which a JVM's
    // first run initialized until prepare() evaluated one of each (#1042), and the class a call's literal argument is
    // compiled to, which a JVM's first run with one loaded deep in a stack until prepare() made such a call (#1066),
    // with MVEL's JIT on and off. Some of them only: the evaluations initialize others too, such as DynamicGetAccessor
    // with the JIT on, and the classes MVEL compiles the expressions with.
    private static final List<String> BY_EVALUATING = List.of(
            "org/mvel2/compiler/ExecutableLiteral",
            "org/mvel2/integration/impl/BaseVariableResolverFactory",
            "org/mvel2/integration/impl/CachingMapVariableResolverFactory",
            "org/mvel2/integration/impl/SimpleSTValueResolver",
            "org/mvel2/optimizers/impl/refl/nodes/BaseAccessor",
            "org/mvel2/optimizers/impl/refl/nodes/InvokableAccessor",
            "org/mvel2/optimizers/impl/refl/nodes/MethodAccessor",
            "org/mvel2/optimizers/impl/refl/nodes/VariableAccessor",
            "org/mvel2/util/Varargs");
    // A class of the library's, as -Xlog:class+init names it when it initializes it, a class the JDK made for a lambda
    // or a method reference included, as in "Initializing 'io/github/brantunger/unruly/mvel/ExceptionReads$$Lambda+0x
    // ...'(no method)", and as -Xlog:class+load names it when it loads it; and the steps whose run is the JVM's first
    // to fail in an MVEL condition or action, which must load, initialize or link none: a first failure deep in a
    // stack could overflow doing so (#1066, #1093). The scenario's own are left out.
    private static final String LIBRARY_INITIALIZED = INITIALIZING + "io/github/brantunger/unruly/";
    private static final String LIBRARY_LOADED = "[class,load] io.github.brantunger.unruly.";
    private static final String SCENARIO_LOADED = "[class,load] " + FirstUseScenario.class.getName();
    private static final List<String> FAILING = List.of(FirstUseScenario.CONDITION_FAILS,
            FirstUseScenario.ACTION_FAILS, FirstUseScenario.CONDITION_ASSIGNS, FirstUseScenario.LOAD_FAILS,
            FirstUseScenario.CLASS_CALLED, FirstUseScenario.IMPORT_TOO_LARGE, FirstUseScenario.PACKAGE_IMPORT_UNREAD,
            FirstUseScenario.FAILING_NESTED_LOAD, FirstUseScenario.VALIDATE_FAILS);
    // What -Xlog:class+load begins a class's name with, any class's, and the part of a name the JDK gives the hidden
    // class it makes for a lambda or a method reference, which in a failing step is flagged whoever's it is, the JDK's
    // own included (#1097).
    private static final String LOADED = "[class,load] ";
    private static final String LAMBDA = "$$Lambda";
    // A class of the JDK's regular expressions, as -Xlog:class+load names it, which a failing step may not load either:
    // the first compile error reads MVEL's message with them, so prepare() reads one first (#1097).
    private static final String REGEX_LOADED = LOADED + "java.util.regex.";
    // Set by Unreachable's static initializer, and read here, as reading a field of Unreachable would initialize it.
    private static final AtomicBoolean UNREACHABLE_INITIALIZED = new AtomicBoolean();

    @Test
    @DisplayName("with MVEL's JIT on, MVEL's first steps initialize no class with a static initializer but those of "
            + "the features and the failed imports prepare() leaves to them, and its first compile with ASM ASM's")
    void initializedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> expected = new ArrayList<>(BY_FEATURES);
        expected.addAll(BY_FAILED_IMPORTS);
        assertInitialized(expected, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=false"));
    }

    @Test
    @DisplayName("with MVEL's JIT off, MVEL's first steps initialize no class with a static initializer but those of "
            + "the features and the failed imports prepare() leaves to them")
    void initializedWhenBuiltWithoutJit(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> expected = new ArrayList<>(BY_FEATURES);
        expected.addAll(BY_FAILED_IMPORTS);
        assertEquals(expected, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=true"), "initialized by first steps");
    }

    @Test
    @DisplayName("with the engine logging, the first message it logs initializes SLF4J's Level and FormattingTuple, "
            + "and MVEL's first steps nothing else but as with it off")
    void initializedWhenBuiltWithLogging(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> expected = new ArrayList<>(BY_FEATURES);
        expected.addAll(BY_LOGGING);
        expected.addAll(BY_FAILED_IMPORTS);
        assertInitialized(expected, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=false", LOGGING));
    }

    @Test
    @DisplayName("with MVEL's JIT on, building an engine with MVEL initializes these classes a first run evaluates a "
            + "property read, a method call and a literal argument with (#1042, #1066)")
    void evaluatingClassesLoadedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(whenBuilt(BY_EVALUATING), whereInitialized(dir, BY_EVALUATING, "-Dmvel2.disable.jit=false"));
    }

    @Test
    @DisplayName("with MVEL's JIT off, building an engine with MVEL initializes these classes a first run evaluates a "
            + "property read, a method call and a literal argument with (#1042, #1066)")
    void evaluatingClassesLoadedWhenBuiltWithoutJit(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(whenBuilt(BY_EVALUATING), whereInitialized(dir, BY_EVALUATING, "-Dmvel2.disable.jit=true"));
    }

    @Test
    @DisplayName("the JVM's first runs whose MVEL condition or action fails, and its first loads and validate() that "
            + "fail, load, initialize or link no class of the engine's or this module's, a lambda's or method "
            + "reference's included, nor any lambda, nor a class of the JDK's regular expressions (#1093, #1097)")
    void firstFailuresLoadNothing(@TempDir Path dir) throws IOException, InterruptedException {
        // With the steps of a load and a validate() that fail after the failing runs, so no class those use first hides
        // one that a failing run would otherwise be the first to load.
        List<String> lines = ChildJvm.run(dir, FirstUseScenario.class, "-D" + FirstUseScenario.FAILURES_FIRST + "=true",
                "-Xlog:class+init=info:stdout", "-Xlog:class+load=info:stdout").lines().toList();
        assertTrue(lines.contains(FirstUseScenario.BUILT) && lines.contains(FirstUseScenario.RAN),
                "markers missing:\n" + String.join("\n", lines));
        List<String> used = new ArrayList<>();
        String step = "before the first step";
        for (String line : lines) {
            int at = Math.max(line.indexOf(LIBRARY_INITIALIZED), line.indexOf(LIBRARY_LOADED));
            if (at < 0 && line.contains(LAMBDA)) {
                at = Math.max(line.indexOf(INITIALIZING), line.indexOf(LOADED));
            }
            if (at < 0) {
                at = line.indexOf(REGEX_LOADED);
            }
            if (line.startsWith(FirstUseScenario.STEP)) {
                step = line.substring(FirstUseScenario.STEP.length());
            } else if (at >= 0 && !line.contains(SCENARIO) && !line.contains(SCENARIO_LOADED)
                    && FAILING.contains(step)) {
                used.add("\n" + step + ": " + line.substring(at));
            }
        }

        assertEquals(List.of(), used, "loaded or initialized by the first failing runs");
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

    private static List<String> whenBuilt(List<String> classes) {
        return classes.stream().map(name -> name + ": " + WHEN_BUILT).toList();
    }

    // Each class, with or without a static initializer, as its name and where it was first initialized: before the
    // engines were built, in which step, or never.
    private static List<String> whereInitialized(Path dir, List<String> classes, String... options)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(options));
        command.add("-Xlog:class+init=info:stdout");
        List<String> lines = ChildJvm.run(dir, FirstUseScenario.class, command.toArray(String[]::new)).lines()
                .toList();
        assertTrue(lines.contains(FirstUseScenario.BUILT) && lines.contains(FirstUseScenario.RAN),
                "markers missing:\n" + String.join("\n", lines));
        List<String> where = new ArrayList<>();
        for (String name : classes) {
            String step = WHEN_BUILT;
            String found = "never";
            for (String line : lines) {
                if (line.startsWith(FirstUseScenario.STEP)) {
                    step = line.substring(FirstUseScenario.STEP.length());
                } else if (line.contains(INITIALIZING + name + "'")) {
                    found = step;
                    break;
                }
            }
            where.add(name + ": " + found);
        }
        return where;
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
