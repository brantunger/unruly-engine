package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an import of a class that exists but can't be loaded fails build(), instead of importing it as a package")
class UnloadableImportTest {

    private static final String LONG_TEXT = "x".repeat(1500);

    private static final String PREFIX = "java.lang.NoClassDefFoundError: ";

    // Line breaks from the 996th character of the error's text to past the 1,000th.
    private static final String BREAKS_AT_LIMIT = "x".repeat(1000 - PREFIX.length() - 5) + "\n".repeat(20);

    /**
     * A class loader where these classes exist but can't be loaded: {@code p.A}, whose superclass {@code p.Base} is
     * missing; {@code p.B}, whose error has no message; and {@code p.C}, whose class file is malformed. And where
     * {@code p.a} finds {@code A}'s class file, as a class directory on a case-insensitive file system does. And
     * {@code p.D}, whose error text has a line break and a bidi control; {@code p.E}, whose error text is long;
     * {@code p.F}, whose error text has line breaks where it is cut; and {@code p.G}, whose error has no message and
     * a cause that has one.
     */
    private static final ClassLoader LOADER = new ClassLoader(UnloadableImportTest.class.getClassLoader()) {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            switch (name) {
                case "p.A" -> throw new NoClassDefFoundError("p/Base");
                case "p.B" -> throw new NoClassDefFoundError();
                case "p.C" -> throw new ClassFormatError("Incompatible magic value 16909060 in class file p/C");
                case "p.a" -> throw new NoClassDefFoundError("p/a (wrong name: p/A)");
                case "p.D" -> throw new NoClassDefFoundError("p/Base\nFORGED LOG LINE" + (char) 0x202e);
                case "p.E" -> throw new NoClassDefFoundError(LONG_TEXT);
                case "p.F" -> throw new NoClassDefFoundError(BREAKS_AT_LIMIT);
                case "p.G" -> throw (NoClassDefFoundError) new NoClassDefFoundError()
                        .initCause(new ClassNotFoundException("p.Base"));
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
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).imports(name);
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

    @Test
    @DisplayName("the linkage error's text is escaped in the message, so it can't start a log line of its own (#634)")
    void errorTextEscaped() {
        RulesEngineBuilder<Map<String, Object>> builder = importing("p.D");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> withContextClassLoader(builder::build));

        assertEquals("Can't import 'p.D': the class exists but can't be loaded: "
                + "java.lang.NoClassDefFoundError: p/Base\\nFORGED LOG LINE\\u202e", ex.getMessage());
    }

    @Test
    @DisplayName("the linkage error's text is shortened to 1,000 characters (#634)")
    void errorTextShortened() {
        RulesEngineBuilder<Map<String, Object>> builder = importing("p.E");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> withContextClassLoader(builder::build));

        String text = PREFIX + LONG_TEXT;
        assertEquals("Can't import 'p.E': the class exists but can't be loaded: " + text.substring(0, 1000)
                + "... (" + (text.length() - 1000) + " more characters)", ex.getMessage());
    }

    @Test
    @DisplayName("the linkage error's text is shortened before it is escaped, so no escape is cut in half (#634)")
    void errorTextShortenedThenEscaped() {
        RulesEngineBuilder<Map<String, Object>> builder = importing("p.F");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> withContextClassLoader(builder::build));

        assertEquals("Can't import 'p.F': the class exists but can't be loaded: " + PREFIX
                + BREAKS_AT_LIMIT.substring(0, 1000 - PREFIX.length() - 5) + "\\n".repeat(5)
                + "... (15 more characters)", ex.getMessage());
    }

    @Test
    @DisplayName("a root cause the linkage error's text hides is named (#634)")
    void hiddenRootCauseNamed() {
        RulesEngineBuilder<Map<String, Object>> builder = importing("p.G");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> withContextClassLoader(builder::build));

        assertEquals("Can't import 'p.G': the class exists but can't be loaded: java.lang.NoClassDefFoundError"
                + " (caused by java.lang.ClassNotFoundException: p.Base)", ex.getMessage());
    }
}
