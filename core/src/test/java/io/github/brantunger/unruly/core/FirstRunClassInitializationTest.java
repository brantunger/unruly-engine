package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RunOptions;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #911: a run may start deep in another run's stack, and the stack-headroom check (see StackHeadroom) makes room for
 * the engine's own steps, not for initializing classes. A {@link StackOverflowError} inside a class's static
 * initializer leaves the class unusable for the life of the JVM: a first run that overflowed while it registered the
 * Flight Recorder events made every later run of every engine throw {@link NoClassDefFoundError}. So the classes with
 * a static initializer that a run uses are initialized when the JVM's first engine is built. The test runs
 * {@link FirstRunScenario} in a new JVM, as this JVM's other tests have initialized them already, and reads the
 * {@code Initializing '...'} lines that {@code -Xlog:class+init} prints, the same on JDK 21 and later, which end in
 * {@code (no method)} for a class without a static initializer.
 *
 * <p>
 * The scenario's first runs may initialize no class with a static initializer at all, the engine's, the JDK's or a
 * library's, with two exceptions. One is the scenario's own classes, which stand for an application's. The other is
 * the hidden classes the JDK makes for method handles when they are first needed, such as
 * {@code java/lang/invoke/LambdaForm$MH+0x...}, which have no name to initialize ahead of time.
 * </p>
 */
@DisplayName("building an engine initializes the classes with static initializers a run uses, not its first run (#911)")
class FirstRunClassInitializationTest {

    private static final String INITIALIZING = "Initializing '";
    // What -Xlog:class+init adds to the line of a class with no static initializer, no <clinit> method, as in
    // "Initializing 'java/lang/CharSequence'(no method)", unlike "Initializing 'java/lang/String' (0x...)".
    private static final String NO_INITIALIZER = "(no method)";
    private static final String SCENARIO = INITIALIZING + FirstRunScenario.class.getName().replace('.', '/');
    // A hidden class's name ends in '+' and its address, which no other class's has.
    private static final Pattern LAMBDA_FORM = Pattern.compile(Pattern.quote(INITIALIZING)
            + "java/lang/invoke/LambdaForm\\$[A-Za-z]+\\+0x");

    // The engine's, so the test fails if building an engine stops initializing one, rather than passing because no
    // first run reaches it any more. Three can't be named from here: RuleSet's Source and Warning are private, and
    // NoSession isn't public.
    private static final List<String> ENGINE_CLASSES = Stream.concat(Stream.of(FlightRecorderEvents.class,
                    RunEvent.class, RuleEvent.class, Cancellation.class, Cancellation.Reason.class, Deadline.class,
                    RuleSet.Kind.class, RuleSet.Held.class, Closing.class, Widening.class,
                    LoggedFailures.LoggedAt.class, RunOptions.class, RuleEvaluation.Outcome.class,
                    ConditionResult.class, ActionResult.class, FactProperties.class).map(Class::getName),
            Stream.of(RuleSet.class.getName() + "$Source", RuleSet.class.getName() + "$Warning",
                    "io.github.brantunger.unruly.api.language.NoSession")).toList();

    // The JDK's that RunClasses names. Those the JDK running the test doesn't have are left out.
    private static final List<String> JDK_CLASSES = List.of("java.util.stream.MatchOps$MatchKind",
            "java.util.stream.Collectors", "java.util.stream.Collector$Characteristics",
            "java.lang.ClassValue$ClassValueMap", "java.lang.invoke.MethodHandleImpl$BindCaller",
            "java.lang.invoke.MethodHandleImpl$ArrayAccess", "java.lang.invoke.MethodHandleImpl$ArrayAccessor",
            "java.util.Collections$CopiesList", "sun.invoke.util.ValueConversions$1",
            "java.lang.invoke.ClassSpecializer$Factory$1Var", "java.lang.ClassValue$RemovalToken",
            "java.lang.ClassValue$Entry", "java.lang.invoke.MethodHandles$1",
            "java.lang.invoke.ClassSpecializer$Factory$1$1Var", "java.lang.invoke.ClassSpecializer$Factory$1$5$1");

    @Test
    @DisplayName("the scenario's first runs initialize no class with a static initializer but the JDK's hidden ones")
    void initializedWhenBuilt(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir);
        List<String> jdkClasses = JDK_CLASSES.stream().filter(FirstRunClassInitializationTest::exists).toList();

        // Each class with the step of the scenario it was initialized in, so a failure on a JDK this machine doesn't
        // have can be told from the log alone.
        List<String> byRuns = new ArrayList<>();
        String step = "before the first step";
        for (String line : lines.subList(lines.indexOf(FirstRunScenario.LOADED),
                lines.indexOf(FirstRunScenario.RAN))) {
            int at = line.indexOf(INITIALIZING);
            if (line.startsWith(FirstRunScenario.STEP)) {
                step = line.substring(FirstRunScenario.STEP.length());
            } else if (at >= 0 && !line.contains(NO_INITIALIZER) && !line.contains(SCENARIO)
                    && !LAMBDA_FORM.matcher(line).find()) {
                byRuns.add("\n" + step + ": " + line.substring(at));
            }
        }

        assertEquals(List.of(), byRuns, "initialized by first runs");
        assertEquals(List.of(), notInitializedBy(lines.subList(0, lines.indexOf(FirstRunScenario.BUILT)),
                Stream.concat(ENGINE_CLASSES.stream(), jdkClasses.stream()).toList()),
                "not initialized when the first engine was built");
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
        int built = lines.indexOf(FirstRunScenario.BUILT);
        int loaded = lines.indexOf(FirstRunScenario.LOADED);
        int ran = lines.indexOf(FirstRunScenario.RAN);
        assertTrue(built >= 0 && loaded > built && ran > loaded, "markers missing:\n" + String.join("\n", lines));
        return lines;
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
