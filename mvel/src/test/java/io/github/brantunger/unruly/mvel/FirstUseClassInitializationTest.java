package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #945: MVEL's first load, run or failing load may be nested deep in another run's stack, and a
 * {@link StackOverflowError} inside a class's static initializer leaves the class unusable for the life of the JVM: a
 * first load that overflowed in this module's {@code MvelFactNames} made every later MVEL engine fail, and a failing
 * load that overflowed in {@code MvelCompileErrors} made every later compile error report {@link NoClassDefFoundError}
 * instead. So {@link MvelExpressionLanguage#prepare()}, which the engine calls when it builds an engine with MVEL,
 * initializes them, then evaluates a property read, a method call and a method call with a literal argument once each
 * through MVEL's public API, which loads classes MVEL evaluates them with ahead of a first run, so a first run deep in
 * a stack has fewer to load (#1042, #1066), and compiles a comparison with {@code null}, a call of a method MVEL can't
 * find on the type it gives the value and an assignment, and runs all but the call, which loads the classes MVEL's
 * first compile of each and an assignment's first run load, ahead of a first load or run (#1103); when the engine
 * prepares MVEL, at {@code build()} or a first load, that warm-up initializes no class with a static initializer that
 * it hasn't, which the test says rather than checks. A bare {@code prepare()} call in an otherwise empty JVM may also
 * initialize JDK classes the reflective call needs. The test runs {@link FirstUseScenario} in a new JVM, as this JVM's
 * other tests have initialized them already, and reads the {@code Initializing '...'} lines that
 * {@code -Xlog:class+init} prints, which end in {@code (no method)} for a class without a static initializer, and, for
 * a class the JVM loads and never initializes, the lines {@code -Xlog:class+load} prints. With MVEL's JIT on and off,
 * it checks that the classes without a static initializer that MVEL's first run used to load for its read, its call and
 * its literal argument are initialized before any step, and that those its first compile of a comparison with
 * {@code null}, of a call MVEL can't find and of an assignment, and an assignment's first run, used to load are loaded
 * or initialized before any step, as the scenario's first steps would otherwise be the first to load them.
 *
 * <p>
 * Once the engines are built, MVEL's first steps may initialize no class with a static initializer at all, this
 * module's, MVEL's, the engine's or the JDK's, with these exceptions: the scenario's own classes, which stand for an
 * application's; the hidden classes the JDK makes for method handles when they are first needed, which have no name to
 * initialize ahead of time; and those that {@code prepare()} leaves to a first use, which the test says rather than
 * checks covered (#1012). A rule's first inline list, {@code new} and {@code soundslike} each initialize one class of
 * MVEL's: {@code CollectionParser}, {@code NewObjectNode} and {@code Soundex}. #1097: a first load that fails for an
 * import with too many parts initializes MVEL's {@code ImportNode}, and one for a package import too far into the text
 * for MVEL to read, the JDK's {@code Formatter}, its {@code FormatSpecifier} and {@code Locale.Category}, with which
 * the JDK formats the message of the out-of-bounds exception MVEL's read of the import throws. Its first read of a
 * property through its getter initializes none, as {@code prepare()} initializes {@code GetterAccessor}, whose empty
 * array every call of a method without arguments reads. #1115: nor does its first {@code BigDecimal} literal, as
 * {@code prepare()} initializes {@code BigDecimal} and {@code BigInteger}, nor its first read of a method's generic
 * return type, its first reflective call of a method of the JDK's with a runtime annotation, or a first write to an
 * output that overrides a generic setter, which reads the setter's generic parameter type, as building the first engine
 * reads the generic types and annotations of the JDK's methods: each could overflow deep in a stack in a static
 * initializer of the JDK's, and leave the class unusable for the life of the JVM. The steps that take them come before
 * any other step that could. With the engine logging, as an application's SLF4J provider may, the first message it logs
 * initializes SLF4J's {@code Level} and {@code FormattingTuple}, here with slf4j-simple. The test checks that each step
 * initializes exactly those, so it fails if one stops reaching its class, and if a step initializes any other. It is
 * checked with MVEL's JIT on, made MVEL's default again once the engines are built, as an application may; with it off,
 * as in a native image, which sets MVEL's optimizer up with other classes; with MVEL's reflective optimizer the
 * default, as the engine makes it (#1161); and with the engine logging.
 * </p>
 *
 * <p>
 * The scenario's last step is said rather than checked covered too: with the JIT on, MVEL's first compile of an
 * accessor with ASM, after more than 50 runs of it within 100 ms, initializes ASM's classes and the JDK's that MVEL's
 * JIT uses, which {@code prepare()} doesn't. Deep in a stack, that compile may fail, and isn't tried again: MVEL marks
 * an accessor compiled before it compiles it, so it stays reflective, slower but correct. If the overflow strikes a
 * class's static initializer, of ASM's or the JDK's, that class stays unusable for the JVM's life, and every other
 * accessor's first compile fails one run the same way. The test checks that one of ASM's classes was initialized there,
 * so it can't pass because the runs were too slow for the JIT, that none of the engine's or this module's was, and
 * (#1155) that no class was but ASM's and the JDK's {@code Math$RandomNumberGeneratorHolder} and {@code Random}, the
 * JDK's it was measured to initialize there on JDK 21, 24, 25 and 26, as MVEL names each accessor it compiles with
 * {@code Math.random()}: not one list, as {@code Random} is initialized there on 21 and 24 only, and on 25 and later
 * already by the Flight Recorder's {@code Throttler} when the first build registers the engine's events. With the JIT
 * off, or the engine's reflective optimizer the default, the step initializes nothing.
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
 *
 * <p>
 * #1099: nor may MVEL's first loop, in its analysis or in a run, nor its first load and first run that ask for the
 * class loader so many times that its loop detection walks the stack, though they succeed, nor its first lookup of a
 * name with a {@code $} and too many parts, which walks it too. Building the engine walks it once each way, so with
 * the failures last, none of them initializes the JDK's classes that walk it either, and the walks read the stack with
 * loops, so none of those steps, nor any failing one, may load a class of the JDK's streams.
 * </p>
 *
 * <p>
 * #1210: a class's first load reads it from a file, a jar or a class directory, which deep in a stack could overflow,
 * and on JDK 21 on a virtual thread could leave every later file read on one failing. So MVEL's first loads and its
 * first run, the JVM's, may load no class from a file, this module's, MVEL's, the engine's or anyone's, but the
 * application's and its languages': the scenario's own and the test fixtures'.
 * </p>
 */
@DisplayName("building an engine with MVEL initializes the classes MVEL's first load and run use, but those of the "
        + "features and the JIT it leaves to their first use (#945, #1012, #1042, #1066, #1103, #1115)")
class FirstUseClassInitializationTest {

    private static final String INITIALIZING = "Initializing '";
    // The engine logging as an application's SLF4J provider may: this test's logs at INFO and above.
    private static final String LOGGING = "-Dorg.slf4j.simpleLogger.defaultLogLevel=info";
    // MVEL's JIT made MVEL's default again once the engines are built, as an application may, in place of the
    // reflective optimizer the engine makes the default (#1161).
    private static final String JIT_ON = "-D" + FirstUseScenario.JIT_ON + "=true";
    // What -Xlog:class+init adds to the line of a class with no static initializer, no <clinit> method.
    private static final String NO_INITIALIZER = "(no method)";
    private static final String SCENARIO = INITIALIZING + FirstUseScenario.class.getName().replace('.', '/');
    // A hidden class's name ends in '+' and its address, which no other class's has.
    private static final Pattern LAMBDA_FORM = Pattern.compile(Pattern.quote(INITIALIZING)
            + "java/lang/invoke/LambdaForm\\$[A-Za-z]+\\+0x");
    // The start of what initializedByFirstSteps records for a class the last step, MVEL's JIT, initialized.
    private static final String IN_JIT_STEP = FirstUseScenario.JIT + ": ";
    // The package of ASM's classes, which MVEL's JIT compiles an accessor with, and initializes in the last step.
    private static final String ASM = "org/mvel2/asm/";
    // The JDK's classes the last step, MVEL's JIT, may initialize besides ASM's (#1155), measured on JDK 21, 24, 25 and
    // 26, as MVEL's ASMAccessorOptimizer names each accessor it compiles with Math.random(): Math's holder of the
    // random number generator on each, and Random on 21 and 24 only, as on 25 and later the Flight Recorder's
    // Throttler initializes Random first, when the first build registers the engine's events. Any other class it
    // initializes, the JDK's, MVEL's or another library's, fails the test.
    private static final List<String> BY_JIT = List.of("java/lang/Math$RandomNumberGeneratorHolder",
            "java/util/Random");
    // What the steps that use one of MVEL's features for the first time initialize, which prepare() leaves to them.
    private static final List<String> BY_FEATURES = List.of(
            FirstUseScenario.INLINE_LIST + ": org/mvel2/util/CollectionParser",
            FirstUseScenario.NEW_OBJECT + ": org/mvel2/ast/NewObjectNode",
            FirstUseScenario.SOUNDSLIKE + ": org/mvel2/util/Soundex");
    // What a load that fails for an import initializes, which prepare() leaves to it, as it does MVEL's features
    // (#1097): MVEL's ImportNode, which reads an import, for one with too many parts; and the JDK's Formatter, with
    // which the JDK formats the message of the out-of-bounds exception MVEL's read of the import throws, for a package
    // import too far into the text for MVEL to read. And for an import of a class with a null static field (#1198),
    // what MVEL's own import of the class's fields initializes before the engine reads them: the class, which MVEL's
    // Class.forName initializes, and the JDK's accessor for a static field, which MVEL's first read of one, of the
    // field before the null one, makes; the same on JDK 21 and 25. Each only in its own step, so no other step may
    // initialize them. Not Locale$Category, which the Formatter read until the first build's format of one instant
    // initialized it (#1302).
    private static final List<String> BY_FAILED_IMPORTS = List.of(
            FirstUseScenario.IMPORT_TOO_LARGE + ": org/mvel2/ast/ImportNode",
            FirstUseScenario.NULL_FIELD_IMPORT + ": probe/NullFieldHolder",
            FirstUseScenario.NULL_FIELD_IMPORT + ": java/lang/invoke/DirectMethodHandle$StaticAccessor",
            FirstUseScenario.PACKAGE_IMPORT_UNREAD + ": java/util/Formatter",
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
    // Classes without a static initializer that a JVM's first compile of a null literal and a comparison, of a call
    // of a method MVEL can't find on the type it gives the value, and of an assignment, and the first run of an
    // assignment, load or initialize, which a first load or run deep in a stack did until prepare() compiled one of
    // each and ran all but the call (#1103), with MVEL's JIT on and off. NullType, which a null literal is compiled to,
    // is loaded and never initialized, so it counts where it is loaded; the others where they are initialized.
    private static final List<String> BY_COMPILING = List.of(
            "org/mvel2/ast/BooleanNode",
            "org/mvel2/ast/BinaryOperation",
            "org/mvel2/util/StringAppender",
            "org/mvel2/ast/AssignmentNode",
            "org/mvel2/util/ArrayTools",
            "org/mvel2/compiler/CompiledAccExpression",
            "org/mvel2/UnresolveablePropertyException");
    private static final List<String> LOADED_BY_COMPILING = List.of("org/mvel2/util/NullType");
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
            FirstUseScenario.CLASS_CALLED, FirstUseScenario.IMPORT_TOO_LARGE, FirstUseScenario.NULL_FIELD_IMPORT,
            FirstUseScenario.PACKAGE_IMPORT_UNREAD, FirstUseScenario.FAILING_NESTED_LOAD,
            FirstUseScenario.VALIDATE_FAILS, FirstUseScenario.ANALYSIS_LOOP, FirstUseScenario.RUN_LOOP,
            FirstUseScenario.NESTED_NAME_TOO_LARGE);
    // The steps whose load or run is the JVM's first to ask for the class loader so many times that MVEL's loop
    // detection walks the stack, which succeed, and must load, initialize or link no more than a failing one (#1099).
    private static final List<String> WALKING = List.of(FirstUseScenario.MANY_CALLS_LOAD,
            FirstUseScenario.MANY_CALLS_RUN);
    // What -Xlog:class+load begins a class's name with, any class's, and the part of a name the JDK gives the hidden
    // class it makes for a lambda or a method reference, which in a failing step is flagged whoever's it is, the JDK's
    // own included (#1097).
    private static final String LOADED = "[class,load] ";
    private static final String LAMBDA = "$$Lambda";
    // A class of the JDK's regular expressions, as -Xlog:class+load names it, which a failing step may not load either:
    // the first compile error reads MVEL's message with them, so prepare() reads one first (#1097).
    private static final String REGEX_LOADED = LOADED + "java.util.regex.";
    // A class of the JDK's streams, as -Xlog:class+load names it, which a failing or walking step may not load either:
    // the walks read the stack with loops, not a stream's limit, count or anyMatch, whose first use loads classes
    // without a static initializer or a lambda, such as ReduceOps$CountingSink, deep in a stack (#1099).
    private static final String STREAM_LOADED = LOADED + "java.util.stream.";
    // A class -Xlog:class+load shows read from a file, by the URL of the jar or the class directory it was read from,
    // as in "source: file:/C:/.../unruly-engine-core.jar" or "source: file:/C:/.../classes/java/main/", or "source:
    // jar:file:/C:/...jar!/" from a class loader that reads a jar by a jar URL; the URL's file is the second group. Not
    // a class the JDK reads from its own image, "source: jrt:/java.base", or from the archive of classes it shares
    // between JVMs, "source: shared objects file", nor a class it makes, whose source is the class it makes it for or
    // "__JVM_LookupDefineClass__" (#1210).
    private static final Pattern FROM_FILE = Pattern.compile(Pattern.quote(LOADED)
            + "(\\S+) source: (?:jar:)?(file:[^!\\s]+)");
    // Set by Unreachable's static initializer, and read here, as reading a field of Unreachable would initialize it.
    private static final AtomicBoolean UNREACHABLE_INITIALIZED = new AtomicBoolean();

    @Test
    @DisplayName("with MVEL's JIT on, MVEL's first steps initialize no class with a static initializer but those of "
            + "the features and the failed imports prepare() leaves to them, and its first compile with ASM ASM's")
    void initializedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> expected = new ArrayList<>(BY_FEATURES);
        expected.addAll(BY_FAILED_IMPORTS);
        assertInitialized(expected, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=false", JIT_ON));
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
    @DisplayName("with MVEL's reflective optimizer the default, as the engine makes it, MVEL's first steps initialize "
            + "no class with a static initializer but those of the features and the failed imports prepare() leaves "
            + "to them, and the runs MVEL's JIT would compile in no class of ASM's (#1161)")
    void initializedWhenBuiltWithEngineOptimizer(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> expected = new ArrayList<>(BY_FEATURES);
        expected.addAll(BY_FAILED_IMPORTS);
        assertEquals(expected, initializedByFirstSteps(dir), "initialized by first steps");
    }

    @Test
    @DisplayName("with the engine logging, the first message it logs initializes SLF4J's Level and FormattingTuple, "
            + "and MVEL's first steps nothing else but as with it off")
    void initializedWhenBuiltWithLogging(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> expected = new ArrayList<>(BY_FEATURES);
        expected.addAll(BY_LOGGING);
        expected.addAll(BY_FAILED_IMPORTS);
        assertInitialized(expected, initializedByFirstSteps(dir, "-Dmvel2.disable.jit=false", JIT_ON, LOGGING));
    }

    @Test
    @DisplayName("with MVEL's JIT on, building an engine with MVEL initializes these classes a first run evaluates a "
            + "property read, a method call and a literal argument with (#1042, #1066)")
    void evaluatingClassesLoadedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(whenBuilt(BY_EVALUATING), whereInitialized(dir, BY_EVALUATING, "-Dmvel2.disable.jit=false",
                JIT_ON));
    }

    @Test
    @DisplayName("with MVEL's JIT off, building an engine with MVEL initializes these classes a first run evaluates a "
            + "property read, a method call and a literal argument with (#1042, #1066)")
    void evaluatingClassesLoadedWhenBuiltWithoutJit(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(whenBuilt(BY_EVALUATING), whereInitialized(dir, BY_EVALUATING, "-Dmvel2.disable.jit=true"));
    }

    @Test
    @DisplayName("with MVEL's JIT on, building an engine with MVEL loads these classes a first compile of a "
            + "comparison with null, of a call MVEL can't find and of an assignment, and an assignment's first run, "
            + "use (#1103)")
    void compilingClassesLoadedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(whenBuilt(BY_COMPILING, LOADED_BY_COMPILING),
                whereFirst(dir, BY_COMPILING, LOADED_BY_COMPILING, "-Dmvel2.disable.jit=false", JIT_ON));
    }

    @Test
    @DisplayName("with MVEL's JIT off, building an engine with MVEL loads these classes a first compile of a "
            + "comparison with null, of a call MVEL can't find and of an assignment, and an assignment's first run, "
            + "use (#1103)")
    void compilingClassesLoadedWhenBuiltWithoutJit(@TempDir Path dir) throws IOException, InterruptedException {
        assertEquals(whenBuilt(BY_COMPILING, LOADED_BY_COMPILING),
                whereFirst(dir, BY_COMPILING, LOADED_BY_COMPILING, "-Dmvel2.disable.jit=true"));
    }

    @Test
    @DisplayName("the JVM's first runs whose MVEL condition or action fails, and its first loads and validate() that "
            + "fail, load, initialize or link no class of the engine's or this module's, a lambda's or method "
            + "reference's included, nor any lambda, nor a class of the JDK's regular expressions or streams, nor do "
            + "its first loops and walks of the stack (#1093, #1097, #1099)")
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
                at = Math.max(line.indexOf(REGEX_LOADED), line.indexOf(STREAM_LOADED));
            }
            if (line.startsWith(FirstUseScenario.STEP)) {
                step = line.substring(FirstUseScenario.STEP.length());
            } else if (at >= 0 && !line.contains(SCENARIO) && !line.contains(SCENARIO_LOADED)
                    && (FAILING.contains(step) || WALKING.contains(step))) {
                used.add("\n" + step + ": " + line.substring(at));
            }
        }

        assertEquals(List.of(), used, "loaded or initialized by the first failing runs");
    }

    @Test
    @DisplayName("MVEL's first loads and its first run, the JVM's, load no class from a file but the application's "
            + "and its languages' (#1210)")
    void firstLoadsAndRunReadNoFile(@TempDir Path dir) throws IOException, InterruptedException, URISyntaxException {
        List<String> lines = ChildJvm.run(dir, FirstUseScenario.class, "-Xlog:class+load=info:stdout").lines()
                .toList();
        int built = lines.indexOf(FirstUseScenario.BUILT);
        int run = lines.indexOf(FirstUseScenario.STEP + FirstUseScenario.ASSIGNMENT_RUN);
        assertTrue(built >= 0 && run > built && lines.contains(FirstUseScenario.RAN),
                "markers missing:\n" + String.join("\n", lines));
        // The step after the first run's.
        int next = lines.indexOf(FirstUseScenario.STEP + FirstUseScenario.BIG_DECIMAL_LOAD);
        Set<Path> application = Set.of(location(FirstUseScenario.class), location(ToyExpressionLanguage.class));
        List<String> whenBuilt = readFromFile(lines.subList(0, built), application);

        // The engine's classes are read from its jar, and this module's from its jar or class directory, as the
        // scenario starts, so a criterion that matched no line the JVM prints would fail here rather than pass below.
        for (Class<?> type : List.of(RulesEngineBuilder.class, MvelExpressionLanguage.class)) {
            assertTrue(whenBuilt.stream().anyMatch(line -> line.contains(LOADED + type.getName() + " ")),
                    type.getName() + " not shown read from a file:\n" + String.join("\n", lines));
        }
        assertEquals(List.of(), readFromFile(lines.subList(built, next), application),
                "read from a file by the first loads and the first run");
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
    // so it did compile, none of the engine's or this module's, and none but ASM's and BY_JIT's (#1155). Which of the
    // JDK's it initializes differs between JDK releases, so it isn't checked exactly.
    private static void assertInitialized(List<String> expected, List<String> initialized) {
        List<String> byJit = initialized.stream().filter(entry -> entry.startsWith(IN_JIT_STEP)).toList();
        assertEquals(expected, initialized.stream().filter(entry -> !entry.startsWith(IN_JIT_STEP)).toList(),
                "initialized by first steps");
        assertTrue(byJit.stream().anyMatch(entry -> entry.startsWith(IN_JIT_STEP + ASM)),
                "no class of ASM's initialized by the runs that MVEL's JIT compiles in: " + byJit);
        assertEquals(List.of(), byJit.stream().filter(entry -> entry.startsWith(IN_JIT_STEP + "io/github/brantunger/"))
                .toList(), "the engine's or this module's, initialized by MVEL's JIT");
        assertEquals(List.of(), byJit.stream().filter(entry -> !entry.startsWith(IN_JIT_STEP + ASM)
                && !BY_JIT.contains(entry.substring(IN_JIT_STEP.length()))).toList(),
                "initialized by MVEL's JIT, but neither ASM's nor the JDK's it's known to initialize");
    }

    private static List<String> whenBuilt(List<String> classes) {
        return classes.stream().map(name -> name + ": " + WHEN_BUILT).toList();
    }

    private static List<String> whenBuilt(List<String> initialized, List<String> loaded) {
        List<String> classes = new ArrayList<>(initialized);
        classes.addAll(loaded);
        return whenBuilt(classes);
    }

    // Each class, with or without a static initializer, as its name and where it was first initialized: before the
    // engines were built, in which step, or never.
    private static List<String> whereInitialized(Path dir, List<String> classes, String... options)
            throws IOException, InterruptedException {
        return whereFirst(dir, classes, List.of(), options);
    }

    // As whereInitialized for the classes initialized, then each of the classes loaded as its name and where it was
    // first loaded, for a class the JVM loads and never initializes.
    private static List<String> whereFirst(Path dir, List<String> initialized, List<String> loaded, String... options)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(options));
        command.add("-Xlog:class+init=info:stdout");
        if (!loaded.isEmpty()) {
            command.add("-Xlog:class+load=info:stdout");
        }
        List<String> lines = ChildJvm.run(dir, FirstUseScenario.class, command.toArray(String[]::new)).lines()
                .toList();
        assertTrue(lines.contains(FirstUseScenario.BUILT) && lines.contains(FirstUseScenario.RAN),
                "markers missing:\n" + String.join("\n", lines));
        List<String> where = new ArrayList<>();
        for (String name : initialized) {
            where.add(name + ": " + firstStep(lines, INITIALIZING + name + "'"));
        }
        for (String name : loaded) {
            where.add(name + ": " + firstStep(lines, LOADED + name.replace('/', '.') + " "));
        }
        return where;
    }

    // The step of the first line that holds the text: before the engines were built, which step, or never.
    private static String firstStep(List<String> lines, String text) {
        String step = WHEN_BUILT;
        for (String line : lines) {
            if (line.startsWith(FirstUseScenario.STEP)) {
                step = line.substring(FirstUseScenario.STEP.length());
            } else if (line.contains(text)) {
                return step;
            }
        }
        return "never";
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

    // Each class the lines show read from a file but from one of the application's, with the step it was read in.
    private static List<String> readFromFile(List<String> lines, Set<Path> application) {
        List<String> read = new ArrayList<>();
        String step = "before the first step";
        for (String line : lines) {
            Matcher loaded = FROM_FILE.matcher(line);
            if (line.startsWith(FirstUseScenario.STEP)) {
                step = line.substring(FirstUseScenario.STEP.length());
            } else if (loaded.find() && !application.contains(Path.of(URI.create(loaded.group(2))))) {
                read.add("\n" + step + ": " + line.substring(loaded.start()));
            }
        }
        return read;
    }

    // The jar or the class directory a class was read from.
    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
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
