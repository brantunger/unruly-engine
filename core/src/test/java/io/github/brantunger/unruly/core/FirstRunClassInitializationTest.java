package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.FactProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #911, #945: a run, a load, {@code validate()} or {@code close()} may be called deep in another run's stack, and the
 * stack-headroom check (see StackHeadroom) makes room for the engine's own steps, not for initializing classes. A
 * {@link StackOverflowError} inside a class's static initializer leaves the class unusable for the life of the JVM: a
 * first run that overflowed while it registered the Flight Recorder events made every later run of every engine throw
 * {@link NoClassDefFoundError}, and a first build that overflowed while it created the engine's logger did the same to
 * every later build, and could leave SLF4J unusable for the whole application. So the classes with a static
 * initializer that engines use are initialized when the JVM's first engine is built, once the room for that is
 * checked, before anything else the build does. The test runs {@link FirstRunScenario} in a new JVM, as this JVM's
 * other tests have initialized them already, and reads the {@code Initializing '...'} lines that
 * {@code -Xlog:class+init} prints, the same on JDK 21 and later, which end in {@code (no method)} for a class without
 * a static initializer.
 *
 * <p>
 * The scenario's first loads and runs, and its first builds until they initialize RunClasses, may initialize no class
 * with a static initializer at all, the engine's, the JDK's or a library's, with two exceptions. One is the scenario's
 * own classes, which stand for an application's. The other is the hidden classes the JDK makes for method handles
 * when they are first needed, such as {@code java/lang/invoke/LambdaForm$MH+0x...}, which have no name to initialize
 * ahead of time.
 * </p>
 */
@DisplayName("building an engine initializes the classes with static initializers engines use, before anything else "
        + "(#911, #945)")
class FirstRunClassInitializationTest {

    private static final String INITIALIZING = "Initializing '";
    // What -Xlog:class+init adds to the line of a class with no static initializer, no <clinit> method, as in
    // "Initializing 'java/lang/CharSequence'(no method)", unlike "Initializing 'java/lang/String' (0x...)".
    private static final String NO_INITIALIZER = "(no method)";
    private static final String SCENARIO = INITIALIZING + FirstRunScenario.class.getName().replace('.', '/');
    // A hidden class's name ends in '+' and its address, which no other class's has.
    private static final Pattern LAMBDA_FORM = Pattern.compile(Pattern.quote(INITIALIZING)
            + "java/lang/invoke/LambdaForm\\$[A-Za-z]+\\+0x");

    private static final String RUN_CLASSES = INITIALIZING + RunClasses.class.getName().replace('.', '/') + "'";

    // The engine's, so the test fails if building an engine stops initializing one, rather than passing because no
    // first load or run reaches it any more. Five can't be named from here: RuleSet's Source and Warning are private,
    // NoSession isn't public, and the holders of EngineCompileContext's logger and of RunClasses' prepared languages
    // are named by their names, so the test compiles without them.
    private static final List<String> ENGINE_CLASSES = Stream.concat(Stream.of(AbstractRulesEngine.class,
                    LanguageRegistry.class, ImportResolver.class, LanguageNames.Problem.class, FactNames.Problem.class,
                    RuleListCompiler.Mode.class, LoggedFailures.class, Faults.Step.class, Failures.class,
                    ExpressionKind.class, InvalidExpressionException.Issue.Severity.class, RuleSet.class,
                    FlightRecorderEvents.class, RunEvent.class, RuleEvent.class, Cancellation.class,
                    Cancellation.Reason.class, Deadline.class, RuleSet.Kind.class, RuleSet.Held.class, Closing.class,
                    Widening.class, LoggedFailures.LoggedAt.class, RunOptions.class, RuleEvaluation.Outcome.class,
                    ConditionResult.class, ActionResult.class, FactProperties.class).map(Class::getName),
            Stream.of(RuleSet.class.getName() + "$Source", RuleSet.class.getName() + "$Warning",
                    "io.github.brantunger.unruly.api.language.NoSession",
                    EngineCompileContext.class.getName() + "$Warnings", RunClasses.class.getName() + "$Prepared"))
            .toList();

    // The JDK's that RunClasses names. Those the JDK running the test doesn't have are left out.
    private static final List<String> JDK_CLASSES = List.of("java.util.stream.MatchOps$MatchKind",
            "java.util.stream.Collectors", "java.util.stream.Collector$Characteristics",
            "java.lang.ClassValue$ClassValueMap", "java.lang.invoke.MethodHandleImpl$BindCaller",
            "java.lang.invoke.MethodHandleImpl$ArrayAccess", "java.lang.invoke.MethodHandleImpl$ArrayAccessor",
            "java.util.Collections$CopiesList", "sun.invoke.util.ValueConversions$1",
            "java.lang.invoke.ClassSpecializer$Factory$1Var", "java.lang.ClassValue$RemovalToken",
            "java.lang.ClassValue$Entry", "java.lang.invoke.MethodHandles$1",
            "java.lang.invoke.ClassSpecializer$Factory$1$1Var", "java.lang.invoke.ClassSpecializer$Factory$1$5$1",
            "java.lang.invoke.DirectMethodHandle$Interface");

    @Test
    @DisplayName("the scenario's first loads and runs initialize no class with a static initializer but the JDK's "
            + "hidden ones")
    void initializedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir);
        List<String> jdkClasses = JDK_CLASSES.stream().filter(FirstRunClassInitializationTest::exists).toList();

        assertEquals(List.of(), initializedBy(lines.subList(lines.indexOf(FirstRunScenario.BUILT),
                lines.indexOf(FirstRunScenario.RAN))), "initialized by first loads and runs");
        assertEquals(List.of(), notInitializedBy(lines.subList(0, lines.indexOf(FirstRunScenario.BUILT)),
                Stream.concat(ENGINE_CLASSES.stream(), jdkClasses.stream()).toList()),
                "not initialized when the first engine was built");
    }

    @Test
    @DisplayName("the first build initializes no class with a static initializer before RunClasses but the "
            + "application's")
    void nothingInitializedBeforeTheCheck(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir);
        int building = lines.indexOf(FirstRunScenario.BUILDING);
        int check = IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains(RUN_CLASSES)).findFirst()
                .orElse(-1);

        assertTrue(check > building, "RunClasses not initialized once the builds started:\n"
                + String.join("\n", lines));
        assertEquals(List.of(), initializedBy(lines.subList(building, check)), "initialized before RunClasses");
    }

    @Test
    @DisplayName("the first use of a language found but not named, by the JVM's first load, initializes no class with "
            + "a static initializer but the JDK's hidden ones")
    void firstUseInitializesNothing(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, FirstRunScenario.OnlyFound.class, "-Xlog:class+init=info:stdout")
                .lines().toList();
        int built = lines.indexOf(FirstRunScenario.BUILT);
        int loaded = lines.indexOf(FirstRunScenario.LOADED);
        assertTrue(built >= 0 && loaded > built, "markers missing:\n" + String.join("\n", lines));

        assertEquals(List.of(), initializedBy(lines.subList(built, loaded)), "initialized by the first load");
    }

    @Test
    @DisplayName("in a native image, which names no class, building the first engine initializes the engine's")
    void initializedWhenBuiltInImage(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "-Dorg.graalvm.nativeimage.imagecode=runtime");

        assertEquals(List.of(), notInitializedBy(lines.subList(0, lines.indexOf(FirstRunScenario.BUILT)),
                ENGINE_CLASSES), "not initialized when the first engine was built");
    }

    // Runs the scenario in a new JVM with the options given, and returns what it printed once it has printed every
    // marker in order.
    private static List<String> scenario(Path dir, String... options) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(options));
        command.add("-Xlog:class+init=info:stdout");
        List<String> lines = ChildJvm.run(dir, FirstRunScenario.class, command.toArray(String[]::new)).lines()
                .toList();
        int building = lines.indexOf(FirstRunScenario.BUILDING);
        int built = lines.indexOf(FirstRunScenario.BUILT);
        int loaded = lines.indexOf(FirstRunScenario.LOADED);
        int ran = lines.indexOf(FirstRunScenario.RAN);
        assertTrue(building >= 0 && built > building && loaded > built && ran > loaded,
                "markers missing:\n" + String.join("\n", lines));
        return lines;
    }

    // Each class with a static initializer that the lines show initialized, but the scenario's own and the JDK's hidden
    // ones, with the step of the scenario it was initialized in, so a failure on a JDK this machine doesn't have can be
    // told from the log alone.
    private static List<String> initializedBy(List<String> lines) {
        List<String> initialized = new ArrayList<>();
        String step = "before the first step";
        for (String line : lines) {
            int at = line.indexOf(INITIALIZING);
            if (line.startsWith(FirstRunScenario.STEP)) {
                step = line.substring(FirstRunScenario.STEP.length());
            } else if (at >= 0 && !line.contains(NO_INITIALIZER) && !line.contains(SCENARIO)
                    && !LAMBDA_FORM.matcher(line).find()) {
                initialized.add("\n" + step + ": " + line.substring(at));
            }
        }
        return initialized;
    }

    private static List<String> notInitializedBy(List<String> lines, List<String> classNames) {
        return classNames.stream()
                .filter(name -> lines.stream().noneMatch(line -> line.contains(logged(name))))
                .toList();
    }

    // With the closing quote, so a class's name doesn't match a longer one it begins.
    private static String logged(String className) {
        return INITIALIZING + className.replace('.', '/') + "'";
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
