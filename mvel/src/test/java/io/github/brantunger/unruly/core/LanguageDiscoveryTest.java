package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("expression languages are found with ServiceLoader when an engine is built")
class LanguageDiscoveryTest {

    private static final String SERVICES_FILE = "META-INF/services/" + ExpressionLanguage.class.getName();

    @TempDir
    Path servicesRoot;

    /** A language that a services file can list: {@link ToyExpressionLanguage} under the given name. */
    public static class NamedLanguage implements ExpressionLanguage {
        private final String languageName;

        NamedLanguage(String languageName) {
            this.languageName = languageName;
        }

        @Override
        public String name() {
            return languageName;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ToyExpressionLanguage(languageName).newCompiler(context);
        }
    }

    /** A language named {@code found}. */
    public static final class Found extends NamedLanguage {
        /** Creates it. */
        public Found() {
            super("found");
        }
    }

    /** Another language named {@code found}. */
    public static final class AlsoFound extends NamedLanguage {
        /** Creates it. */
        public AlsoFound() {
            super("found");
        }
    }

    /** A language with a blank name. */
    public static final class BlankName extends NamedLanguage {
        /** Creates it. */
        public BlankName() {
            super(" ");
        }
    }

    /** A language with no name. */
    public static final class NoName extends NamedLanguage {
        /** Creates it. */
        public NoName() {
            super(null);
        }
    }

    /** A language that can't be created. */
    public static final class CantBeCreated extends NamedLanguage {
        /** Always throws. */
        public CantBeCreated() {
            super("broken");
            throw new IllegalStateException("no licence for this language");
        }
    }

    /** A class that a services file lists, but that isn't a language. */
    public static final class NotALanguage {
    }

    private static Rule rule(String name, String language, String condition, String action) {
        return Rule.builder().ruleName(name).language(language).condition(condition).action(action).build();
    }

    private static RulesEngineBuilder<Map<String, Object>> builder() {
        return RulesEngineBuilder.allMatches(HashMap::new);
    }

    /** A class loader that sees the library, and a services file listing {@code languages}. */
    private URLClassLoader listing(Class<?>... languages) throws IOException {
        return new URLClassLoader(new URL[]{servicesListing(Arrays.stream(languages).map(Class::getName)
                .toArray(String[]::new))}, LanguageDiscoveryTest.class.getClassLoader());
    }

    /** The root of a services file listing the classes {@code names}. */
    private URL servicesListing(String... names) throws IOException {
        Path file = servicesRoot.resolve(SERVICES_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", names));
        return servicesRoot.toUri().toURL();
    }

    /** Where a class of the library was loaded from: a jar, or a directory of classes. */
    private static URL codeOf(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    /**
     * A child-first class loader, as plug-in hosts use: it defines its own copy of a class it holds before asking its
     * parent.
     */
    private static class ChildFirst extends URLClassLoader {
        ChildFirst(ClassLoader parent, URL... urls) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && !name.startsWith("java.")) {
                    try {
                        type = findClass(name);
                    } catch (ClassNotFoundException e) {
                        // Not in the copy, so the parent's.
                        type = null;
                    }
                }
                return type != null ? type : super.loadClass(name, resolve);
            }
        }
    }

    @Test
    @DisplayName("a language listed in a services file the building thread's context class loader sees is used")
    void foundWithContextClassLoader() throws IOException {
        List<Rule> rules = List.of(rule("toy", "found", "true", "put k 1"),
                rule("mvel", null, "true", "output.put('m', 2)"));
        RulesEngine<Map<String, Object>> engine;

        try (URLClassLoader loader = listing(Found.class)) {
            engine = withContextClassLoader(loader, () -> builder().defaultLanguage("mvel").build());
        }
        engine.load(rules);

        assertEquals(Map.of("k", 1, "m", 2), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("languages are found once, when the engine is built, not when rules are loaded")
    void foundWhenBuilt() throws IOException {
        List<Rule> rules = List.of(rule("toy", "found", "true", "put k 1"));
        RulesEngine<Map<String, Object>> builtWithout = builder().build();

        try (URLClassLoader loader = listing(Found.class)) {
            assertThrows(RuleCompilationException.class,
                    () -> withContextClassLoader(loader, () -> {
                        builtWithout.load(rules);
                        return null;
                    }), "the language is on the loading thread's class loader, but wasn't when the engine was built");

            RulesEngine<Map<String, Object>> builtWith = withContextClassLoader(loader,
                    () -> builder().defaultLanguage("mvel").build());
            builtWith.load(rules);

            assertEquals(Map.of("k", 1), builtWith.run(new FactMap<>()));
        }
    }

    @Test
    @DisplayName("MVEL is found with this library's class loader when the thread has no context class loader")
    void mvelFoundWithoutContextClassLoader() {
        RulesEngine<Map<String, Object>> engine = withContextClassLoader(null, () -> builder().build());
        engine.load(List.of(rule("mvel", null, "true", "output.put('m', 1)")));

        assertEquals(Map.of("m", 1), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("languages given to the builder are used instead of the languages found")
    void givenLanguagesReplaceFound() throws IOException {
        List<Rule> rules = List.of(rule("toy", "found", "true", "put k 1"));

        try (URLClassLoader loader = listing(Found.class)) {
            RulesEngine<Map<String, Object>> engine = withContextClassLoader(loader, () -> builder()
                    .language(new NamedLanguage("found") {
                        @Override
                        public ExpressionCompiler newCompiler(CompileContext context) {
                            throw new IllegalStateException("the given one");
                        }
                    })
                    .build());

            RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

            assertEquals("The 'found' expression language failed to create a compiler: the given one",
                    ex.getMessage());
            List<Rule> mvel = List.of(rule("mvel", "mvel", "true", "output.put('m', 1)"));
            assertThrows(RuleCompilationException.class, () -> engine.load(mvel), "MVEL wasn't found either");
        }
    }

    @Test
    @DisplayName("two found languages with the same name fail build(), naming both")
    void sameNameTwice() throws IOException {
        try (URLClassLoader loader = listing(Found.class, AlsoFound.class)) {
            IllegalStateException ex = withContextClassLoader(loader,
                    () -> assertThrows(IllegalStateException.class, () -> builder().build()));

            assertEquals("The expression languages " + Found.class.getName() + " and " + AlsoFound.class.getName()
                    + " found with ServiceLoader are both named 'found'", ex.getMessage());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {BlankName.class, NoName.class})
    @DisplayName("a found language with a null or blank name fails build()")
    void nullOrBlankName(Class<?> language) throws IOException {
        try (URLClassLoader loader = listing(language)) {
            IllegalStateException ex = withContextClassLoader(loader,
                    () -> assertThrows(IllegalStateException.class, () -> builder().build()));

            assertEquals("The expression language " + language.getName()
                    + " found with ServiceLoader has a null or blank name", ex.getMessage());
        }
    }

    @Test
    @DisplayName("a found language that can't be created fails build() with ServiceLoader's error, unchanged")
    void languageCantBeCreated() throws IOException {
        try (URLClassLoader loader = listing(CantBeCreated.class)) {
            ServiceConfigurationError error = withContextClassLoader(loader,
                    () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals("no licence for this language", error.getCause().getMessage());
        }
    }

    /** Builds an engine with {@code loader} as the context class loader, and checks that it runs MVEL's rules. */
    private static String buildsWithMvel(ClassLoader loader) {
        AtomicReference<RulesEngine<Map<String, Object>>> engine = new AtomicReference<>();
        String logs = logsOf(() -> engine.set(withContextClassLoader(loader, () -> builder().build())));
        engine.get().load(List.of(rule("mvel", null, "true", "output.put('m', 1)")));

        assertEquals(Map.of("m", 1), engine.get().run(new FactMap<>()));
        return logs;
    }

    @Test
    @DisplayName("a context class loader with its own copy of the library is left at its first error, so MVEL is found"
            + " once")
    void secondCopySkipped() throws IOException {
        try (URLClassLoader loader = new ChildFirst(LanguageDiscoveryTest.class.getClassLoader(),
                servicesListing(MvelExpressionLanguage.class.getName()), codeOf(MvelExpressionLanguage.class),
                codeOf(ExpressionLanguage.class))) {
            String logs = buildsWithMvel(loader);

            assertTrue(logs.contains("Stopped finding expression languages with " + loader + ": that class loader has"
                    + " its own copy of this library, so the languages it lists are a second copy: "
                    + ExpressionLanguage.class.getName() + ": " + MvelExpressionLanguage.class.getName()
                    + " not a subtype"), logs);
        }
    }

    @Test
    @DisplayName("a context class loader with its own copy of the library whose listings can't be read doesn't stop"
            + " build()")
    void secondCopyWithUnreadableListings() throws IOException {
        try (URLClassLoader loader = new ChildFirst(LanguageDiscoveryTest.class.getClassLoader(),
                codeOf(ExpressionLanguage.class)) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("the plug-in's jar index is broken");
            }
        }) {
            // ServiceLoader reports the same error on every call, so going on to the next listing never ends.
            String logs = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> buildsWithMvel(loader));

            assertEquals(1, logs.split("Stopped finding expression languages", -1).length - 1, logs);
        }
    }

    @Test
    @DisplayName("a second copy of a language, in a context class loader without its own copy of the library, is"
            + " skipped, and the language found first is kept")
    void copyOfALanguageAloneSkipped() throws IOException {
        // The plug-in holds its own copy of MVEL, which implements the library's ExpressionLanguage.
        try (URLClassLoader loader = new ChildFirst(LanguageDiscoveryTest.class.getClassLoader(),
                servicesListing(MvelExpressionLanguage.class.getName()), codeOf(MvelExpressionLanguage.class))) {
            String logs = buildsWithMvel(loader);

            assertTrue(logs.contains("Skipped the expression language " + MvelExpressionLanguage.class.getName()
                    + " that " + loader + " found with ServiceLoader: it's a second copy of one already found with"
                    + " another class loader"), logs);
            LanguageRegistry registry = LanguageRegistry.resolve(Map.of(), null,
                    List.of(ImportResolver.LIBRARY_CLASS_LOADER, loader));
            assertSame(MvelExpressionLanguage.class,
                    registry.languages().get(MvelExpressionLanguage.LANGUAGE_NAME).getClass());
        }
    }

    @Test
    @DisplayName("a listed class that isn't a language fails build() unchanged, whether or not its loader sees the"
            + " library")
    void notALanguage() throws IOException {
        // ServiceLoader skips a listed class in a named module, such as String, so the class is one of these tests'.
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader seesLibrary = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader());
             URLClassLoader seesNothing = new URLClassLoader(urls, null)) {
            for (URLClassLoader loader : List.of(seesLibrary, seesNothing)) {
                ServiceConfigurationError error = withContextClassLoader(loader,
                        () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

                assertEquals(ExpressionLanguage.class.getName() + ": " + NotALanguage.class.getName()
                        + " not a subtype", error.getMessage());
            }
        }
    }

    @ParameterizedTest(name = "a linkage error: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a listed class that isn't a language fails build() unchanged when its loader can't look up"
            + " ExpressionLanguage")
    void notALanguageWhereTheApiCantBeLookedUp(boolean linkageError) throws IOException {
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader loader = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(ExpressionLanguage.class.getName())) {
                    if (linkageError) {
                        throw new NoClassDefFoundError("the plug-in's loader is closed");
                    }
                    throw new IllegalStateException("the plug-in's loader is closed");
                }
                return super.loadClass(name, resolve);
            }
        }) {
            ServiceConfigurationError error = withContextClassLoader(loader,
                    () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

            assertEquals(ExpressionLanguage.class.getName() + ": " + NotALanguage.class.getName() + " not a subtype",
                    error.getMessage());
        }
    }
}
