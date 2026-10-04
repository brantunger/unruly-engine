package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An application whose class path holds the engine but not mvel2. Each test runs one in a class loader of its own,
 * which sees the engine's two modules, SLF4J and the application, and not the tests' class path, so MVEL's classes
 * there are first used, and fail, in that test alone.
 */
@DisplayName("without mvel2 on the class path, every use of MVEL names the missing mvel2 class")
class MissingMvelTest {

    /** What resolving mvel2's ErrorUtil throws when mvel2 isn't on the class path. */
    private static final String MISSING = NoClassDefFoundError.class.getName() + ": org/mvel2/util/ErrorUtil";

    /** What a load fails with when preparing MVEL threw {@link #MISSING}. */
    private static final String FAILED_TO_PREPARE = RuleCompilationException.class.getName()
            + ": The 'mvel' expression language failed to prepare: org/mvel2/util/ErrorUtil";

    @TempDir
    private Path work;

    /** Where a class was loaded from: a jar, or a directory of classes. */
    private static URL codeOf(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    /**
     * Compiles an application whose {@code app.Main} has a method for each use of MVEL, each saying how it went:
     * {@code ok}, what it returned, or what it threw.
     *
     * @return The application's directory of classes
     */
    private Path application() throws URISyntaxException {
        Path app = work.resolve("app");
        String classPath = Path.of(codeOf(ExpressionLanguage.class).toURI()) + File.pathSeparator
                + Path.of(codeOf(MvelExpressionLanguage.class).toURI());
        assertCompiles(List.of("-cp", classPath, "-d", app.toString()), List.of(source("app/Main", """
                package app;

                import io.github.brantunger.unruly.api.RulesEngine;
                import io.github.brantunger.unruly.api.RulesEngineBuilder;
                import io.github.brantunger.unruly.core.EngineCompileContext;
                import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                public final class Main {
                    private static RulesEngineBuilder<Map<String, Object>> builder() {
                        return RulesEngineBuilder.allMatches(HashMap::new);
                    }

                    public static String buildNamingMvel() {
                        try (RulesEngine<Map<String, Object>> engine = builder().defaultLanguage("mvel").build()) {
                            return "ok";
                        } catch (Throwable t) {
                            return t.toString();
                        }
                    }

                    public static String load() {
                        try (RulesEngine<Map<String, Object>> engine = builder().build()) {
                            engine.load(List.of());
                            return "ok";
                        } catch (Throwable t) {
                            return t.toString();
                        }
                    }

                    public static String validate() {
                        try (RulesEngine<Map<String, Object>> engine = builder().build()) {
                            return engine.validate(List.of()).toString();
                        } catch (Throwable t) {
                            return t.toString();
                        }
                    }

                    public static String newCompiler() {
                        try {
                            new MvelExpressionLanguage().newCompiler(
                                    new EngineCompileContext(Set.of(), Set.of(), Main.class.getClassLoader()));
                            return "ok";
                        } catch (Throwable t) {
                            return t.toString();
                        }
                    }
                }
                """)));
        return app;
    }

    /**
     * Calls each of {@code app.Main}'s {@code methods} in turn, in a new class loader that sees the engine, SLF4J, the
     * application and a services file listing MVEL, but not mvel2, and that is the context class loader meanwhile.
     *
     * @param methods The names of {@code app.Main}'s methods
     * @return What each returned, in order
     */
    private List<String> run(String... methods) throws IOException, URISyntaxException {
        Path services = work.resolve("services").resolve("META-INF/services/" + ExpressionLanguage.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, MvelExpressionLanguage.class.getName());
        URL[] classPath = {codeOf(ExpressionLanguage.class), codeOf(MvelExpressionLanguage.class), codeOf(Logger.class),
                application().toUri().toURL(), work.resolve("services").toUri().toURL()};

        try (URLClassLoader application = new URLClassLoader("application", classPath,
                ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> application.loadClass("org.mvel2.util.ErrorUtil"),
                    "the application's class loader sees mvel2");
            return withContextClassLoader(application, () -> Arrays.stream(methods).map(method -> {
                try {
                    return (String) application.loadClass("app.Main").getMethod(method).invoke(null);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }).toList());
        }
    }

    @Test
    @DisplayName("every build() of an engine whose builder names MVEL throws NoClassDefFoundError naming mvel2's"
            + " class, as do the loads that follow")
    void buildNamingMvel() throws Exception {
        assertEquals(List.of(MISSING, MISSING, FAILED_TO_PREPARE, "[" + FAILED_TO_PREPARE + "]"),
                run("buildNamingMvel", "buildNamingMvel", "load", "validate"));
    }

    @Test
    @DisplayName("every load() of an engine whose builder doesn't name MVEL fails naming mvel2's class, as do the"
            + " builds that follow")
    void loadWithoutNamingMvel() throws Exception {
        assertEquals(List.of(FAILED_TO_PREPARE, FAILED_TO_PREPARE, "[" + FAILED_TO_PREPARE + "]", MISSING),
                run("load", "load", "validate", "buildNamingMvel"));
    }

    @Test
    @DisplayName("every compiler created without an engine fails naming mvel2's class")
    void newCompiler() throws Exception {
        assertEquals(List.of(MISSING, MISSING), run("newCompiler", "newCompiler"));
    }
}
