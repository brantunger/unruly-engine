package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.hidden.HiddenOutputs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default {@link OutputWriter} reaches an output's setters the way {@link FactProperties} reaches a fact's
 * getters, so an output class the engine can read it can also write: through a public interface the class implements,
 * or directly where its package is open to the engine's module (#363).
 */
@DisplayName("the default OutputWriter reaches a setter wherever FactProperties reaches a getter")
class OutputWriterAccessTest {

    /** A class whose setter throws. */
    public static final class Refusing {
        public void setScore(int score) {
            throw new IllegalStateException("no scores today");
        }
    }

    @Test
    @DisplayName("a class that isn't public is written through the public interface that declares the setter")
    void throughAPublicInterface() throws Exception {
        HiddenOutputs.Scored output = HiddenOutputs.scored();

        OutputWriter.beansAndMaps().set(output, "score", 7);

        assertEquals(7, output.getScore());
        assertEquals(7, FactProperties.read(output, "score"), "read the same way");
    }

    @Test
    @DisplayName("a class that isn't public and has no interface is written directly on the class path")
    void directlyOnTheClassPath() throws Exception {
        Object output = HiddenOutputs.plain();

        OutputWriter.beansAndMaps().set(output, "score", 8);

        assertEquals(8, FactProperties.read(output, "score"));
    }

    @Test
    @DisplayName("a setter that throws is still reported as an InvocationTargetException with its cause")
    void setterThatThrows() {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> OutputWriter.beansAndMaps().set(new Refusing(), "score", 1));

        assertEquals("no scores today", thrown.getCause().getMessage());
    }

    private static final String API = """
            package com.example.api;

            public interface Scored {
                int getScore();

                void setScore(int score);
            }
            """;

    private static final String IMPLEMENTATION = """
            package com.example.impl;

            public final class Implementation implements com.example.api.Scored {
                private int score;

                public int getScore() {
                    return score;
                }

                public void setScore(int score) {
                    this.score = score;
                }
            }
            """;

    private static final String HIDDEN = """
            package com.example.api;

            final class Hidden {
                private int score;

                public int getScore() {
                    return score;
                }

                public void setScore(int score) {
                    this.score = score;
                }
            }
            """;

    private static final String SETTABLE = """
            package com.example.api;

            public interface Settable<T> {
                void setValue(T value);
            }
            """;

    private static final String GENERIC = """
            package com.example.api;

            final class Generic implements Settable<Integer> {
                private Integer value;

                public void setValue(Integer value) {
                    this.value = value;
                }

                public Integer getValue() {
                    return value;
                }
            }
            """;

    private static final String FACTORY = """
            package com.example.api;

            public final class Outputs {
                private Outputs() {
                }

                public static Object implementation() {
                    return new com.example.impl.Implementation();
                }

                public static Object hidden() {
                    return new Hidden();
                }

                public static Object generic() {
                    return new Generic();
                }

                public static Object valueOf(Object generic) {
                    return ((Generic) generic).getValue();
                }
            }
            """;

    @Test
    @DisplayName("on the module path, a class in an unexported package is written through its exported interface")
    void exportedInterfaceOnTheModulePath(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("implementation").invoke(null);

        OutputWriter.beansAndMaps().set(output, "score", 9);

        assertEquals(9, FactProperties.read(output, "score"));
    }

    @Test
    @DisplayName("on the module path, a class that isn't public in an exported but not opened package can't be written")
    void exportedButNotOpened(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("hidden").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "score", 1));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertTrue(thrown.getMessage().contains("open it to io.github.brantunger.unruly.core for a type that isn't"
                + " public"), thrown.getMessage());
    }

    @Test
    @DisplayName("on the module path, a generic setter is written through the exported interface's bridge method")
    void genericInterfaceOnTheModulePath(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("generic").invoke(null);

        OutputWriter.beansAndMaps().set(output, "value", 5);

        assertEquals(5, factory.getMethod("valueOf", Object.class).invoke(null, output));
    }

    @Test
    @DisplayName("on the module path, a class that isn't public in an opened package is written directly")
    void opened(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "opens com.example.api;").getMethod("hidden").invoke(null);

        OutputWriter.beansAndMaps().set(output, "score", 4);

        assertEquals(4, FactProperties.read(output, "score"));
    }

    /**
     * Compiles a module named {@code outputs}, with the directive given for {@code com.example.api} and nothing for
     * {@code com.example.impl}, loads it in a new layer, and returns its public factory class.
     */
    private static Class<?> outputs(Path classes, String directive) throws ClassNotFoundException {
        String moduleInfo = "module outputs { " + directive + " }";
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean compiled = javac.getTask(null, null, diagnostics, List.of("-proc:none", "-d", classes.toString()),
                null, List.of(source("module-info", moduleInfo), source("com/example/api/Scored", API),
                        source("com/example/impl/Implementation", IMPLEMENTATION),
                        source("com/example/api/Hidden", HIDDEN), source("com/example/api/Settable", SETTABLE),
                        source("com/example/api/Generic", GENERIC), source("com/example/api/Outputs", FACTORY))).call();
        String errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        assertTrue(compiled, errors);

        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration()
                .resolve(ModuleFinder.of(classes), ModuleFinder.of(), Set.of("outputs"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        return layer.findLoader("outputs").loadClass("com.example.api.Outputs");
    }

    private static JavaFileObject source(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
