package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an import of a class that exists but can't be loaded fails build(), instead of importing it as a package")
class UnloadableImportTest {

    /**
     * A class loader where these classes exist but can't be loaded: {@code p.A}, whose superclass {@code p.Base} is
     * missing; {@code p.B}, whose error has no message; and {@code p.C}, whose class file is malformed. And where
     * {@code p.a} finds {@code A}'s class file, as a class directory on a case-insensitive file system does.
     */
    private static final ClassLoader LOADER = new ClassLoader(UnloadableImportTest.class.getClassLoader()) {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            switch (name) {
                case "p.A" -> throw new NoClassDefFoundError("p/Base");
                case "p.B" -> throw new NoClassDefFoundError();
                case "p.C" -> throw new ClassFormatError("Incompatible magic value 16909060 in class file p/C");
                case "p.a" -> throw new NoClassDefFoundError("p/a (wrong name: p/A)");
                default -> {
                    return super.loadClass(name, resolve);
                }
            }
        }
    };

    private static void withContextClassLoader(Runnable action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(LOADER);
        try {
            action.run();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static RulesEngineBuilder<Map<String, Object>> importing(String name) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).imports(name);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "p.A | java.lang.NoClassDefFoundError: p/Base",
            "p.B | java.lang.NoClassDefFoundError",
            "p.C | java.lang.ClassFormatError: Incompatible magic value 16909060 in class file p/C"})
    @DisplayName("a class that exists but can't be loaded is rejected, with the linkage error as the cause")
    void unloadableClassRejected(String name, String error) {
        RulesEngineBuilder<Map<String, Object>> builder = importing(name);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> withContextClassLoader(builder::build));

        assertEquals("Can't import '" + name + "': the class exists but can't be loaded: " + error, ex.getMessage());
        assertEquals(error, assertInstanceOf(LinkageError.class, ex.getCause()).toString());
    }

    @Test
    @DisplayName("a name that only finds a class file in a different case is still a package import")
    void wrongNameIsAPackage() {
        RulesEngineBuilder<Map<String, Object>> builder = importing("p.a");

        assertDoesNotThrow(() -> withContextClassLoader(builder::build));
    }
}
