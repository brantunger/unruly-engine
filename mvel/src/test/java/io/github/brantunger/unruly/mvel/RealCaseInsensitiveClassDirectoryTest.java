package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same three lookups as the fakes, on a real class directory: {@code Applicant.class} is compiled into a temporary
 * directory, and the engine is asked for the name {@code applicant}. On a case-insensitive file system (the default on
 * Windows and macOS) the lookup finds the class file, and the JVM throws
 * {@code NoClassDefFoundError: applicant (wrong name: Applicant)}, or
 * {@code NoClassDefFoundError: pkg/applicant (wrong name: pkg/Applicant)} for a name looked up in a package. That is
 * what the three places the engine decides this in have only ever been given by hand:
 * <ul>
 *     <li>{@link ExactNameClassLoader#loadClass(String)}, for a bare fact name, which
 *     {@link CaseInsensitiveClassDirectoryTest} fakes. MVEL doesn't catch the error there, so without the loader the
 *     rule wouldn't compile;</li>
 *     <li>{@code FactNames.isClass}, for a fact name looked up in an imported package, which {@code FactNamesTest}
 *     fakes: the class file is found in another case, and only loading it tells the engine the name isn't a class
 *     after all;</li>
 *     <li>{@code core.ImportResolver}, for an import string that only finds a class file in another case, which
 *     {@code core.UnloadableImportTest} fakes: it reads the message to tell this apart from a class that exists but
 *     can't be loaded.</li>
 * </ul>
 *
 * <p>
 * Whether the file system is case-insensitive is probed, not guessed from the operating system: the compiled class
 * files are looked up in the other case, and the tests are skipped when that finds nothing. So they also skip on a
 * case-sensitive volume on Windows or macOS, and run on a case-insensitive mount on Linux. The probe asks the file
 * system, not the class loader, on purpose: a JVM that stopped producing the "wrong name" message has to fail these
 * tests, not skip them. The fakes cover the same branches wherever these tests are skipped.
 * </p>
 */
@DisplayName("a class file found in another case, in a real class directory, is not a class of the name looked up")
class RealCaseInsensitiveClassDirectoryTest {

    /** In the default package, so the bare fact name {@code applicant} finds it at the directory's root. */
    private static final String DEFAULT_PACKAGE_APPLICANT = """
            public class Applicant {
                public int getCreditScore() {
                    return 780;
                }
            }
            """;

    /** In a package, for the lookups that go through {@code imports("pkg")} and {@code imports("pkg.applicant")}. */
    private static final String PACKAGED_APPLICANT = """
            package pkg;

            public class Applicant {
                public int getCreditScore() {
                    return 780;
                }
            }
            """;

    private static final Rule PRIME_RATE = Rule.builder().ruleName("prime-rate")
            .condition("applicant.creditScore >= 750").action("output.put('rate', 4.5)").build();

    /** Fails a test whose class loader no longer reads the class directory, where every assertion below would hold. */
    private static final String CONTROL = "the class loader doesn't find the compiled class in the other case,"
            + " so the lookups below are not the ones this test is about";

    @TempDir
    static Path classes;

    /**
     * The two class directories are separate, so each test's lookup is the one it is about: with both classes in one
     * directory, the bare name {@code applicant} would find the class file of the one in the default package first,
     * whatever the rule list imports.
     */
    private static Path bareClasses() {
        return classes.resolve("bare");
    }

    private static Path packagedClasses() {
        return classes.resolve("packaged");
    }

    /** Compiles both classes once, and skips every test on a case-sensitive file system. */
    @BeforeAll
    static void compileApplicant() {
        compile(bareClasses(), source("Applicant", DEFAULT_PACKAGE_APPLICANT));
        compile(packagedClasses(), source("pkg/Applicant", PACKAGED_APPLICANT));
        // Asserted, not assumed: a class file that isn't where it is expected misses in either case, on every file
        // system, and would skip the tests everywhere with a message claiming the volume is case-sensitive.
        assertTrue(Files.exists(bareClasses().resolve("Applicant.class")),
                () -> "javac wrote no Applicant.class in " + bareClasses());
        assertTrue(Files.exists(packagedClasses().resolve("pkg/Applicant.class")),
                () -> "javac wrote no pkg/Applicant.class in " + packagedClasses());

        Assumptions.assumeTrue(Files.exists(bareClasses().resolve("applicant.class"))
                        && Files.exists(packagedClasses().resolve("pkg/applicant.class")),
                () -> "the class directories under " + classes + " are on a case-sensitive file system:"
                        + " bare/applicant.class or packaged/pkg/applicant.class doesn't find the Applicant.class"
                        + " compiled beside it, so no class lookup there can produce the JVM's \"wrong name\" error");
    }

    private static void compile(Path directory, JavaFileObject file) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean compiled = javac.getTask(null, null, diagnostics,
                List.of("-proc:none", "-d", directory.toString()), null, List.of(file)).call();
        String errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        assertTrue(compiled, errors);
    }

    /** A class loader over a class directory, as an application that puts one on its class path has. */
    private static URLClassLoader classDirectory(Path directory) throws MalformedURLException {
        return new URLClassLoader(new URL[]{directory.toUri().toURL()},
                RealCaseInsensitiveClassDirectoryTest.class.getClassLoader());
    }

    private static <T> T withContextClassLoader(ClassLoader loader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static JavaFileObject source(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    private static FactStore<Object> applicantFact() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("applicant", Map.of("creditScore", 780));
        return facts;
    }

    @Test
    @DisplayName("the README quick start's rule compiles and runs when 'applicant' finds Applicant.class")
    void bareNameIsTheFact() throws IOException {
        try (URLClassLoader loader = classDirectory(bareClasses())) {
            assertNotNull(loader.findResource("applicant.class"), CONTROL);
            Map<String, Object> output = withContextClassLoader(loader, () -> {
                RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                        .<Map<String, Object>>firstMatch(HashMap::new).build();
                engine.load(List.of(PRIME_RATE));
                return engine.run(applicantFact());
            });

            assertEquals(Map.of("rate", 4.5), output);
        }
    }

    @Test
    @DisplayName("a fact named like a class of an imported package, in another case, is still the fact")
    void nameInAnImportedPackageIsTheFact() throws IOException {
        try (URLClassLoader loader = classDirectory(packagedClasses())) {
            assertNotNull(loader.findResource("pkg/applicant.class"), CONTROL);
            Map<String, Object> output = withContextClassLoader(loader, () -> {
                RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                        .<Map<String, Object>>firstMatch(HashMap::new).imports("pkg").build();
                engine.load(List.of(PRIME_RATE));
                return engine.run(applicantFact());
            });

            assertEquals(Map.of("rate", 4.5), output);
        }
    }

    @Test
    @DisplayName("an import that only finds a class file in another case is a package import, not a failed load")
    void importOfAnotherCaseIsAPackage() throws IOException {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder
                .<Map<String, Object>>firstMatch(HashMap::new).imports("pkg.applicant");

        try (URLClassLoader loader = classDirectory(packagedClasses())) {
            assertNotNull(loader.findResource("pkg/applicant.class"), CONTROL);
            assertDoesNotThrow(() -> withContextClassLoader(loader, builder::build));
        }
    }
}
