package io.github.brantunger.unruly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs small applications on the module path, where the module declarations take effect. The other tests run on the
 * class path, which ignores them. Each application, under {@code module-path/} in the test resources, is a named module
 * that requires only one of the engine's modules. It's compiled against the built jars, run in a new JVM, and exits
 * with an error if one of its checks fails.
 */
@DisplayName("applications on the module path")
class ModulePathTest {

    /** The built jars of the three artifacts and their dependencies, which the build passes to the tests. */
    private static final List<Path> MODULE_PATH = Arrays.stream(
                    System.getProperty("unruly.module-path").split(Pattern.quote(File.pathSeparator)))
            .filter(entry -> !entry.isEmpty())
            .map(Path::of)
            .toList();

    @TempDir
    private Path work;

    private static boolean isMvel(Path jar) {
        String name = jar.getFileName().toString();
        return name.startsWith("mvel2-") || name.matches("unruly-engine-\\d.*\\.jar");
    }

    /**
     * Compiles the application in {@code module-path/<name>}, and runs its module's {@code Main} in a new JVM.
     *
     * @param name       The application's directory
     * @param module     The application's module, which is also the package of its {@code Main}
     * @param modulePath The jars on its module path
     * @return What it printed
     */
    private String run(String name, String module, List<Path> modulePath) throws Exception {
        Path sources = Path.of(getClass().getResource("/module-path/" + name).toURI());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sources)) {
            files = walk.filter(file -> file.toString().endsWith(".java")).toList();
        }
        Path classes = work.resolve(name);
        String path = modulePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = javac.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            boolean compiled = javac.getTask(null, fileManager, diagnostics,
                    List.of("--release", "21", "-proc:none", "--module-path", path, "-d", classes.toString()),
                    null, fileManager.getJavaFileObjectsFromPaths(files)).call();
            assertTrue(compiled, () -> "the application didn't compile: " + diagnostics.getDiagnostics());
        }

        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                "--module-path", classes + File.pathSeparator + path,
                "-m", module + "/" + module + ".Main")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the application didn't finish:\n" + output);
        assertEquals(0, process.exitValue(), "the application failed:\n" + output);
        return output;
    }

    @Test
    @DisplayName("a module that requires only io.github.brantunger.unruly runs MVEL rules on its own exported classes")
    void withMvel() throws Exception {
        String output = run("withMvel", "com.example.withmvel", MODULE_PATH);

        assertTrue(output.contains("Module path with MVEL: 1000 runs passed"), output);
    }

    @Test
    @DisplayName("a module that requires only io.github.brantunger.unruly.core runs its own language, with no MVEL "
            + "on the module path")
    void withoutMvel() throws Exception {
        List<Path> withoutMvel = MODULE_PATH.stream().filter(jar -> !isMvel(jar)).toList();
        assertEquals(2, MODULE_PATH.size() - withoutMvel.size(), "the MVEL jars to leave out: " + MODULE_PATH);

        String output = run("withoutMvel", "com.example.withoutmvel", withoutMvel);

        assertTrue(output.contains("Module path without MVEL: 1000 runs passed"), output);
    }

    @Test
    @DisplayName("a module that requires the test kit gets the engine's contexts, and runs the contract test with JUnit")
    void withTestKit() throws Exception {
        String output = run("withTestKit", "com.example.withtestkit", MODULE_PATH);

        assertTrue(output.contains("contract checks passed"), output);
    }
}
