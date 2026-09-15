package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an engine's languages and default language are resolved when it's built")
class LanguageRegistryTest {

    /** A class loader that finds no language: it sees only the platform's classes and services. */
    private static URLClassLoader noLanguages() {
        return new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
    }

    @Test
    @DisplayName("finding no language fails, as does naming a default among none")
    void noLanguage() throws IOException {
        try (URLClassLoader loader = noLanguages()) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> LanguageRegistry.resolve(List.of(), null, List.of(loader)));
            assertEquals("The engine has no expression language: add one with language(), or put a language's jar "
                    + "on the class path", ex.getMessage());

            ex = assertThrows(IllegalStateException.class,
                    () -> LanguageRegistry.resolve(List.of(), "mvel", List.of(loader)));
            assertEquals("The default language 'mvel' isn't one of the engine's expression languages: []",
                    ex.getMessage());
        }
    }

    @Test
    @DisplayName("a language found by two class loaders counts once, and becomes the default")
    void foundOnce() {
        ClassLoader library = ImportResolver.LIBRARY_CLASS_LOADER;

        LanguageRegistry registry = LanguageRegistry.resolve(List.of(), null, List.of(library, library));

        assertEquals(Map.of(MvelExpressionLanguage.LANGUAGE_NAME, MvelExpressionLanguage.class),
                Map.of(registry.defaultLanguage(), registry.languages().get(registry.defaultLanguage()).getClass()));
        assertEquals(1, registry.languages().size());
    }
}
