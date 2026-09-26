package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("build() rejects an import string too long or with too many dots before it is looked up as a class (#650)")
class ImportLimitsTest {

    /**
     * A class loader that records each name it is asked to load. It is parallel capable, as the JDK's application
     * class loader is: such a loader keeps an object for every name it is asked for, for as long as it lives.
     */
    private static final class RecordingLoader extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        private final List<String> names = new CopyOnWriteArrayList<>();

        RecordingLoader() {
            super(ImportLimitsTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            names.add(name);
            return super.loadClass(name, resolve);
        }

        // Every import string in these tests is longer than 100 characters, and no class the engine loads is.
        long importLookups() {
            return names.stream().filter(name -> name.length() > 100).count();
        }
    }

    private static void withContextClassLoader(ClassLoader loader, Executable action) throws Throwable {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            action.execute();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static RulesEngineBuilder<Map<String, Object>> importing(String name) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).imports(name);
    }

    @Test
    @DisplayName("an import with more than 64 dot-separated parts is rejected without a single class lookup")
    void tooManyPartsRejected() {
        String name = "a.".repeat(64) + "Z";
        RecordingLoader loader = new RecordingLoader();
        RulesEngineBuilder<Map<String, Object>> builder = importing(name);

        assertAll(
                () -> {
                    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                            () -> withContextClassLoader(loader, builder::build));
                    assertEquals("Can't import '" + name + "': it has 65 dot-separated parts, and an import may have"
                            + " at most 64", ex.getMessage());
                },
                () -> assertEquals(0, loader.importLookups(), "class lookups of the import"));
    }

    @Test
    @DisplayName("an import longer than 1,000 characters is rejected without a single class lookup, its name shortened")
    void tooLongRejected() {
        String name = "a".repeat(1001);
        RecordingLoader loader = new RecordingLoader();
        RulesEngineBuilder<Map<String, Object>> builder = importing(name);

        assertAll(
                () -> {
                    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                            () -> withContextClassLoader(loader, builder::build));
                    assertEquals("Can't import '" + "a".repeat(200) + "... (801 more characters)': it has 1001"
                            + " characters, and an import may have at most 1000", ex.getMessage());
                },
                () -> assertEquals(0, loader.importLookups(), "class lookups of the import"));
    }

    @Test
    @DisplayName("an import with exactly 64 dot-separated parts is looked up in each nested-class form, as before")
    void sixtyFourPartsAccepted() throws Throwable {
        RecordingLoader loader = new RecordingLoader();
        RulesEngineBuilder<Map<String, Object>> builder = importing("a.".repeat(63) + "Z");

        withContextClassLoader(loader, () -> builder.build().close());

        assertEquals(64, loader.importLookups(), "the name, then with each of its 63 dots replaced by $ in turn");
    }

    @Test
    @DisplayName("an import of exactly 1,000 characters is looked up and accepted, as before")
    void thousandCharactersAccepted() throws Throwable {
        RecordingLoader loader = new RecordingLoader();
        RulesEngineBuilder<Map<String, Object>> builder = importing("a".repeat(1000));

        withContextClassLoader(loader, () -> builder.build().close());

        assertEquals(1, loader.importLookups());
    }
}
