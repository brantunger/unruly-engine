package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs a language's tests the way Maven's Surefire runs them when the language's main code is a named module that
 * requires only core (#582). Surefire puts the main module, core and its dependencies on the module path, patches the
 * test classes into the main module, and leaves the kit and JUnit on the class path, so the kit is in the unnamed
 * module. Core exports the package of the engine's contexts only to the kit's named module, so every
 * {@link LanguageTestContexts} call fails there, unless the package is exported to the unnamed module too.
 *
 * <p>
 * The language, its contract test and a {@code Main} that runs them are under {@code named-module/} in the test
 * resources. They're compiled against the built jars and run in a new JVM, with and without that export.
 * </p>
 */
@DisplayName("a language whose main code is a named module, with the kit on the class path")
class NamedModuleLayoutTest {

    /** The module of the language under {@code named-module/main}, which is also the package of its classes. */
    private static final String MODULE = "com.example.lang";

    /** What Surefire's argLine needs for the kit to reach the engine's contexts from the class path. */
    private static final List<String> EXPORT = List.of("--add-exports",
            "io.github.brantunger.unruly.core/io.github.brantunger.unruly.core=ALL-UNNAMED");

    @TempDir
    private static Path work;

    private static String modulePath;
    private static String classPath;

    private static List<Path> paths(String property) {
        return Arrays.stream(System.getProperty(property).split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.isEmpty())
                .map(Path::of)
                .toList();
    }

    private static String joined(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    /** Whether the jar goes on the module path: core and the two libraries it requires. */
    private static boolean onModulePath(Path jar) {
        String name = jar.getFileName().toString();
        return name.startsWith("unruly-engine-core-") || name.startsWith("jspecify-")
                || name.startsWith("slf4j-api-");
    }

    private static List<Path> sources(String directory) throws Exception {
        Path root = Path.of(NamedModuleLayoutTest.class.getResource("/named-module/" + directory).toURI());
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static void compile(List<Path> files, List<String> options) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "compiling the language needs a JDK, not a JRE");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = javac.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            // Left unset, the class path would be the CLASSPATH variable, and a stale entry there is a [path] warning
            // that -Werror turns into a failure.
            fileManager.setLocation(StandardLocation.CLASS_PATH, List.of());
            List<String> all = new ArrayList<>(List.of("--release", "21", "-Xlint:all", "-Werror", "-proc:none"));
            all.addAll(options);
            boolean compiled = javac.getTask(null, fileManager, diagnostics, all, null,
                    fileManager.getJavaFileObjectsFromPaths(files)).call();
            assertTrue(compiled, () -> "the language didn't compile: " + diagnostics.getDiagnostics());
        }
    }

    /** Compiles the language as its own module, and its tests as Surefire does, patched into that module. */
    @BeforeAll
    static void compileTheLanguage() throws Exception {
        List<Path> jars = paths("unruly.test-kit.named-module-jars");
        List<Path> modules = jars.stream().filter(NamedModuleLayoutTest::onModulePath).toList();
        assertEquals(3, modules.size(), "core, jspecify and slf4j-api among " + jars);
        List<Path> classes = new ArrayList<>(paths("unruly.test-kit.jar"));
        assertEquals(1, classes.size(), "the kit's jar: " + classes);
        classes.addAll(jars.stream().filter(jar -> !onModulePath(jar)).toList());

        Path main = work.resolve("main");
        Path test = work.resolve("test");
        compile(sources("main"), List.of("--module-path", joined(modules), "-d", main.toString()));
        modulePath = main + File.pathSeparator + joined(modules);
        classPath = joined(classes);
        Path testSources = Path.of(NamedModuleLayoutTest.class.getResource("/named-module/test").toURI());
        compile(sources("test"), List.of("--module-path", modulePath,
                "--patch-module", MODULE + "=" + testSources,
                "--add-reads", MODULE + "=ALL-UNNAMED",
                "--class-path", classPath,
                "-d", test.toString()));
    }

    /**
     * Runs the language's {@code Main} in a new JVM, laid out as Surefire lays it out.
     *
     * @param flags What Surefire's argLine adds
     * @return What it printed
     */
    private static String run(List<String> flags) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=off"));
        command.addAll(flags);
        command.addAll(List.of("--module-path", modulePath,
                "--patch-module", MODULE + "=" + work.resolve("test"),
                "--add-reads", MODULE + "=ALL-UNNAMED",
                // Surefire opens the tests' package to JUnit, which is on the class path too.
                "--add-opens", MODULE + "/" + MODULE + "=ALL-UNNAMED",
                "--class-path", classPath,
                "-m", MODULE + "/" + MODULE + ".Main"));
        // To a file, not a pipe: reading a pipe to its end would wait for as long as the JVM runs, past the timeout.
        Path log = Files.createTempFile(work, "output", ".txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertTrue(finished, "the language's tests didn't finish:\n" + output);
        assertEquals(0, process.exitValue(), "the language's tests failed to run:\n" + output);
        return output;
    }

    @Test
    @DisplayName("without the export, the kit can't create the engine's contexts, and the check that uses them fails")
    void withoutTheExport() throws Exception {
        String output = run(List.of());

        assertTrue(output.contains("compile(): java.lang.IllegalAccessError: "), output);
        assertTrue(output.lines().anyMatch(line -> line.equals("found 18, failed 1")), output);
        assertTrue(output.contains("FAILED evaluateAgreesWithDetail(): java.lang.IllegalAccessError: "), output);
    }

    @Test
    @DisplayName("with core's package exported to the unnamed module, the contexts are created and every check passes")
    void withTheExport() throws Exception {
        String output = run(EXPORT);

        assertTrue(output.contains("compile(): ok"), output);
        assertTrue(output.lines().anyMatch(line -> line.equals("found 18, failed 0")), output);
        assertFalse(output.contains("FAILED "), output);
    }
}
