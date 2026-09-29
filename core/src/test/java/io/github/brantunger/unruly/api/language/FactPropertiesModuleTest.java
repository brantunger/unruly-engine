package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads facts from a named module, which is the only place a package can refuse this class deep reflection: on the
 * class path every package is open. The module is compiled and loaded in its own layer, in this JVM.
 */
@DisplayName("a fact in a named module is read as far as the module lets the engine reflect into its package")
class FactPropertiesModuleTest {

    private static final String HIDDEN_RECORD = """
            package com.example.facts;

            record Hidden(int score) {
            }
            """;

    private static final String SHOWN_RECORD = """
            package com.example.facts;

            public record Shown(int score) {
            }
            """;

    private static final String FACTORY = """
            package com.example.facts;

            public final class Facts {
                private Facts() {
                }

                public static Object hidden() {
                    return new Hidden(7);
                }

                public static Object shown() {
                    return new Shown(8);
                }
            }
            """;

    @Test
    @DisplayName("a record that isn't public, in a package that is exported but not opened, can't be read")
    void exportedButNotOpened(@TempDir Path classes) throws ReflectiveOperationException {
        Object hidden = factory(classes, "exports").getMethod("hidden").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(hidden, "score"));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertTrue(thrown.getMessage().contains("open it to io.github.brantunger.unruly.core for a type that isn't"
                + " public"), thrown.getMessage());
    }

    @Test
    @DisplayName("a record that isn't public, in a package that is opened, is read directly")
    void opened(@TempDir Path classes) throws ReflectiveOperationException {
        Object hidden = factory(classes, "opens").getMethod("hidden").invoke(null);

        assertEquals(7, FactProperties.read(hidden, "score"));
        assertEquals(Map.of("score", 7), FactProperties.toData(hidden, 1));
    }

    @Test
    @DisplayName("a public record in a package that is opened but not exported is read, as an exported one is")
    void publicInAnOpenedPackage(@TempDir Path classes) throws ReflectiveOperationException {
        Object shown = factory(classes, "opens").getMethod("shown").invoke(null);

        assertEquals(8, FactProperties.read(shown, "score"));
    }

    /**
     * Compiles a module named {@code facts} whose package {@code com.example.facts} is exported or opened, loads it
     * in a new layer, and returns its public factory class.
     */
    private static Class<?> factory(Path classes, String directive) throws ClassNotFoundException {
        String moduleInfo = "module facts { " + directive + " com.example.facts; }";
        assertCompiles(List.of("-proc:none", "-d", classes.toString()),
                List.of(source("module-info", moduleInfo), source("com/example/facts/Hidden", HIDDEN_RECORD),
                        source("com/example/facts/Shown", SHOWN_RECORD),
                        source("com/example/facts/Facts", FACTORY)));

        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration()
                .resolve(ModuleFinder.of(classes), ModuleFinder.of(), Set.of("facts"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        return layer.findLoader("facts").loadClass("com.example.facts.Facts");
    }
}
