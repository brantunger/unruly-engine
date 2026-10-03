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
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("expression languages are found with ServiceLoader when an engine is built")
class LanguageDiscoveryTest {

    private static final String SERVICES_FILE = "META-INF/services/" + ExpressionLanguage.class.getName();

    /** The language a plug-in builds against its own copy of the library, which this library's loader can't see. */
    private static final String PLUGIN_LANGUAGE = "plugin.PluginLanguage";

    @TempDir
    Path servicesRoot;

    @TempDir
    Path pluginRoot;

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

    /** A language named {@code listed}. */
    public static final class Listed extends NamedLanguage {
        /** Creates it. */
        public Listed() {
            super("listed");
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
    @DisplayName("a context class loader with its own copy of the library has its errors skipped, so MVEL is found"
            + " once")
    void secondCopySkipped() throws IOException {
        try (URLClassLoader loader = new ChildFirst(LanguageDiscoveryTest.class.getClassLoader(),
                servicesListing(MvelExpressionLanguage.class.getName()), codeOf(MvelExpressionLanguage.class),
                codeOf(ExpressionLanguage.class))) {
            String logs = buildsWithMvel(loader);

            assertTrue(logs.contains("Skipped an error finding expression languages with " + loader + ": that class"
                    + " loader sees another copy of this library, so the error may belong to that copy: "
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
            // ServiceLoader reports the same error on every call, so only the most errors skipped ends the search.
            String logs = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> buildsWithMvel(loader));

            assertEquals(1, logs.split("Stopped finding expression languages", -1).length - 1, logs);
            assertEquals(LanguageRegistry.MAX_ERRORS_SKIPPED,
                    logs.split("Skipped an error finding expression languages", -1).length - 1, logs);
            assertTrue(logs.contains("Stopped finding expression languages with " + loader + ": ServiceLoader reported"
                    + " more than " + LanguageRegistry.MAX_ERRORS_SKIPPED + " errors: "
                    + ExpressionLanguage.class.getName() + ": Error locating configuration files: "
                    + IOException.class.getName() + ": the plug-in's jar index is broken"), logs);
        }
    }

    /**
     * A plug-in host's class loader: it asks each plug-in's loader in turn, for classes and for resources, so it
     * resolves a class to the first loader's and lists the resources of all of them.
     */
    private static final class AskEachInTurn extends ClassLoader {
        private final List<ClassLoader> loaders;

        AskEachInTurn(ClassLoader... loaders) {
            super("host", null);
            this.loaders = List.of(loaders);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            for (ClassLoader loader : loaders) {
                try {
                    return loader.loadClass(name);
                } catch (ClassNotFoundException e) {
                    // Not this plug-in's, so the next one's.
                    continue;
                }
            }
            throw new ClassNotFoundException(name);
        }

        @Override
        protected URL findResource(String name) {
            return loaders.stream().map(loader -> loader.getResource(name)).filter(url -> url != null).findFirst()
                    .orElse(null);
        }

        @Override
        protected Enumeration<URL> findResources(String name) throws IOException {
            List<URL> all = new ArrayList<>();
            for (ClassLoader loader : loaders) {
                all.addAll(Collections.list(loader.getResources(name)));
            }
            return Collections.enumeration(all);
        }
    }

    /**
     * A plug-in's class loader that holds the library found at {@code library} and a language built against it, which
     * a services file lists. The plug-in doesn't see the tests' class path.
     */
    private URLClassLoader plugin(Path library) throws IOException {
        return plugin(library, library.toUri().toURL());
    }

    /**
     * A plug-in's class loader, as {@link #plugin(Path)} makes, that opens the library at {@code library} with the URL
     * {@code libraryUrl}.
     */
    private URLClassLoader plugin(Path library, URL libraryUrl) throws IOException {
        Path classes = pluginRoot.resolve("classes");
        assertCompiles(List.of("-cp", library.toString(), "-d", classes.toString()), List.of(source(
                PLUGIN_LANGUAGE.replace('.', '/'), """
                        package plugin;

                        import io.github.brantunger.unruly.api.language.CompileContext;
                        import io.github.brantunger.unruly.api.language.ExpressionCompiler;
                        import io.github.brantunger.unruly.api.language.ExpressionLanguage;

                        public final class PluginLanguage implements ExpressionLanguage {
                            public String name() {
                                return "plugin";
                            }

                            public ExpressionCompiler newCompiler(CompileContext context) {
                                throw new UnsupportedOperationException();
                            }
                        }
                        """)));
        Path services = classes.resolve(SERVICES_FILE);
        Files.createDirectories(services.getParent());
        Files.writeString(services, PLUGIN_LANGUAGE);
        return new URLClassLoader("plugin", new URL[]{libraryUrl, classes.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
    }

    /** The URL {@link java.io.File#toURL()} makes for {@code path}: a space in it isn't encoded. */
    @SuppressWarnings("deprecation")
    private static URL unencodedUrl(Path path) throws IOException {
        return path.toFile().toURL();
    }

    /** Where this library was loaded from: its jar, or its directory of classes. */
    private static Path library() throws URISyntaxException {
        return Path.of(codeOf(ExpressionLanguage.class).toURI());
    }

    /** A copy of this library, in another jar or directory: a plug-in's own copy of it. */
    private Path copyOfTheLibrary() throws IOException, URISyntaxException {
        return copyOfTheLibrary("copy");
    }

    /** A copy of this library, as {@link #copyOfTheLibrary()} makes, in the directory {@code dir}. */
    private Path copyOfTheLibrary(String dir) throws IOException, URISyntaxException {
        Path library = library();
        Path copy = pluginRoot.resolve(dir).resolve(library.getFileName());
        Files.createDirectories(copy.getParent());
        copyTree(library, copy);
        return copy;
    }

    @ParameterizedTest(name = "at a path with a space, left unencoded: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a context class loader that resolves the library's API but sees another copy of the library has its"
            + " errors skipped, so MVEL is found")
    void hostLoaderSeeingAnotherCopySkipped(boolean unencodedSpace) throws IOException, URISyntaxException {
        Path copy = copyOfTheLibrary(unencodedSpace ? "a plug-in" : "copy");
        try (URLClassLoader plugin = plugin(copy, unencodedSpace ? unencodedUrl(copy) : copy.toUri().toURL())) {
            ClassLoader host = new AskEachInTurn(LanguageDiscoveryTest.class.getClassLoader(), plugin);
            String logs = buildsWithMvel(host);

            assertTrue(logs.contains("Skipped an error finding expression languages with " + host + ": that class"
                    + " loader sees another copy of this library, so the error may belong to that copy: "
                    + ExpressionLanguage.class.getName() + ": " + PLUGIN_LANGUAGE + " not a subtype"), logs);
        }
    }

    @ParameterizedTest(name = "the other copy listed first: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("a language that a context class loader seeing another copy of the library lists is found, whether"
            + " it's listed before or after the other copy's language")
    void languageListedWithAnotherCopyFound(boolean otherCopyFirst) throws IOException, URISyntaxException {
        try (URLClassLoader plugin = plugin(copyOfTheLibrary());
             URLClassLoader toyPlugin = listing(Found.class)) {
            ClassLoader host = otherCopyFirst
                    ? new AskEachInTurn(LanguageDiscoveryTest.class.getClassLoader(), plugin, toyPlugin)
                    : new AskEachInTurn(LanguageDiscoveryTest.class.getClassLoader(), toyPlugin, plugin);
            RulesEngine<Map<String, Object>> engine = withContextClassLoader(host,
                    () -> builder().defaultLanguage("found").build());
            engine.load(List.of(rule("toy", null, "true", "put k 1")));

            assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
        }
    }

    @ParameterizedTest(name = "spelled another way: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a context class loader that sees the library's own jar or directory twice fails build() at a language"
            + " it can't use, however the second URL spells it")
    void hostLoaderSeeingTheLibraryTwiceFails(boolean spelledAnotherWay) throws IOException, URISyntaxException {
        // The plug-in opens the very jar the application uses, so it lists no other copy of the library.
        Path library = library();
        Path parent = library.getParent();
        try (URLClassLoader plugin = plugin(spelledAnotherWay
                ? parent.resolve("..").resolve(parent.getFileName()).resolve(library.getFileName())
                : library)) {
            ClassLoader host = new AskEachInTurn(LanguageDiscoveryTest.class.getClassLoader(), plugin);
            ServiceConfigurationError error = withContextClassLoader(host,
                    () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

            assertEquals(ExpressionLanguage.class.getName() + ": " + PLUGIN_LANGUAGE + " not a subtype",
                    error.getMessage());
        }
    }

    @Test
    @DisplayName("a listed class that isn't a language fails build() unchanged on this library's loader, even with a"
            + " second copy of the library on the class path")
    void notALanguageWithTwoCopiesOnTheClassPath() throws Exception {
        // This library's loader is the tests' class path, so the class path with two copies is an application's
        // loader of its own, which loads this library, its copy and the application.
        Path app = application();
        Path services = app.resolve(SERVICES_FILE);
        Files.createDirectories(services.getParent());
        Files.writeString(services, "app.Main");
        URL[] classPath = {library().toUri().toURL(), copyOfTheLibrary().toUri().toURL(), codeOf(Logger.class),
                app.toUri().toURL()};

        try (URLClassLoader application = new URLClassLoader("application", classPath,
                ClassLoader.getPlatformClassLoader())) {
            assertEquals(ServiceConfigurationError.class.getName() + ": " + ExpressionLanguage.class.getName()
                    + ": app.Main not a subtype", buildIn(application, application));
        }
    }

    @Test
    @DisplayName("a context class loader that sees the library's own jar again at a URL with a space left unencoded"
            + " fails build() at a listed class that isn't a language")
    void notALanguageWhereTheLibraryIsSeenAtAnUnencodedUrl() throws Exception {
        Path library = copyOfTheLibrary("a library");
        Path listing = pluginRoot.resolve("listing");
        Path services = listing.resolve(SERVICES_FILE);
        Files.createDirectories(services.getParent());
        Files.writeString(services, "app.Main");
        URL[] classPath = {library.toUri().toURL(), codeOf(Logger.class), application().toUri().toURL()};

        try (URLClassLoader application = new URLClassLoader("application", classPath,
                ClassLoader.getPlatformClassLoader());
             URLClassLoader context = new URLClassLoader(
                     new URL[]{unencodedUrl(library), listing.toUri().toURL()}, application)) {
            assertEquals(ServiceConfigurationError.class.getName() + ": " + ExpressionLanguage.class.getName()
                    + ": app.Main not a subtype", buildIn(application, context));
        }
    }

    /**
     * Opens a URL of the scheme {@code x-plugin} as the {@code file:} URL with the same path: a scheme no file system
     * serves, as a plug-in framework's own URLs are.
     */
    private static final URLStreamHandler PLUGIN_URLS = new URLStreamHandler() {
        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            return URI.create("file" + url.toExternalForm().substring("x-plugin".length())).toURL().openConnection();
        }
    };

    /** A copy of this library as a directory of classes, whether it was loaded from a jar or a directory. */
    private Path libraryClasses() throws IOException, URISyntaxException {
        Path library = library();
        Path classes = pluginRoot.resolve("classes");
        if (Files.isDirectory(library)) {
            copyTree(library, classes);
        } else {
            try (FileSystem jar = FileSystems.newFileSystem(library)) {
                copyTree(jar.getPath("/"), classes);
            }
        }
        return classes;
    }

    /** Copies the directory {@code from}, and everything in it, to {@code to}. */
    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
    }

    @Test
    @DisplayName("a context class loader that lists the library's API at the library's own URL fails build() at a"
            + " listed class that isn't a language, even where that URL is neither a jar nor a file")
    void notALanguageWhereTheLibraryIsAtAnotherKindOfUrl() throws Exception {
        // The application loads the library from a URL no file system serves, so the context class loader, which
        // asks the application's loader, lists the library's API at that same URL, and at no other.
        URL library = URL.of(URI.create("x-plugin" + libraryClasses().toUri().toString().substring("file".length())),
                PLUGIN_URLS);
        Path listing = pluginRoot.resolve("listing");
        Path services = listing.resolve(SERVICES_FILE);
        Files.createDirectories(services.getParent());
        Files.writeString(services, "app.Main");
        URL[] classPath = {library, codeOf(Logger.class), application().toUri().toURL()};

        try (URLClassLoader application = new URLClassLoader("application", classPath,
                ClassLoader.getPlatformClassLoader());
             URLClassLoader context = new URLClassLoader(new URL[]{listing.toUri().toURL()}, application)) {
            String classFile = ExpressionLanguage.class.getName().replace('.', '/') + ".class";
            assertEquals(List.of(library + classFile),
                    Collections.list(context.getResources(classFile)).stream().map(URL::toExternalForm).toList());
            assertEquals(ServiceConfigurationError.class.getName() + ": " + ExpressionLanguage.class.getName()
                    + ": app.Main not a subtype", buildIn(application, context));
        }
    }

    /**
     * Compiles an application whose {@code app.Main.build()} builds an engine and says how that went: {@code built},
     * or what it threw.
     *
     * @return The application's directory of classes
     */
    private Path application() throws URISyntaxException {
        Path app = pluginRoot.resolve("app");
        assertCompiles(List.of("-cp", library().toString(), "-d", app.toString()), List.of(source("app/Main", """
                package app;

                import io.github.brantunger.unruly.api.RulesEngineBuilder;
                import java.util.HashMap;
                import java.util.Map;

                public final class Main {
                    public static String build() {
                        try {
                            RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
                            return "built";
                        } catch (Throwable t) {
                            return t.toString();
                        }
                    }
                }
                """)));
        return app;
    }

    /** Runs {@code app.Main.build()} of {@code application} with {@code context} as the context class loader. */
    private static Object buildIn(ClassLoader application, ClassLoader context) {
        return withContextClassLoader(context, () -> {
            try {
                return application.loadClass("app.Main").getMethod("build").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
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

    // #951: GraalVM for JDK 21's error for a class an image built with strict reachability metadata has no metadata
    // for is an Error, not a LinkageError, so it escaped instead of ServiceLoader's.
    @Test
    @DisplayName("a listed class that isn't a language fails build() unchanged when its loader is a native image's with"
            + " no metadata for ExpressionLanguage (#951)")
    void notALanguageWhereTheApiHasNoMetadata() throws IOException {
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader loader = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(ExpressionLanguage.class.getName())) {
                    throw new MissingReflectionRegistrationError(name);
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

    @Test
    @DisplayName("an error that isn't a LinkageError from looking ExpressionLanguage up fails build() itself (#951)")
    void otherErrorWhereTheApiIsLookedUp() throws IOException {
        Error other = new Error("not the image's");
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader loader = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(ExpressionLanguage.class.getName())) {
                    throw other;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            assertSame(other, withContextClassLoader(loader, () -> assertThrows(Error.class, () -> builder().build())));
        }
    }

    @ParameterizedTest(name = "after a missing location: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a context class loader that lists the library's API at a URL that is neither a jar nor a file sees"
            + " another copy, so MVEL is found")
    void anotherCopyAtAnotherKindOfUrlSkipped(boolean afterAMissingFile) throws IOException {
        String classFile = ExpressionLanguage.class.getName().replace('.', '/') + ".class";
        URL missing = pluginRoot.resolve("missing").resolve(classFile).toUri().toURL();
        URL elsewhere = URI.create("http://plugins.example/" + classFile).toURL();
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader loader = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                List<URL> listed = Collections.list(super.getResources(name));
                if (name.endsWith(".class")) {
                    if (afterAMissingFile) {
                        // Taken for the library's own, and the next location is still looked at.
                        listed.add(missing);
                    }
                    listed.add(elsewhere);
                }
                return Collections.enumeration(listed);
            }
        }) {
            String logs = buildsWithMvel(loader);

            assertTrue(logs.contains("Skipped an error finding expression languages with " + loader + ": that class"
                    + " loader sees another copy of this library, so the error may belong to that copy: "
                    + ExpressionLanguage.class.getName() + ": " + NotALanguage.class.getName() + " not a subtype"),
                    logs);
        }
    }

    /** A services file in the directory {@code dir} that lists the classes {@code names}. */
    private URL servicesFile(String dir, String... names) throws IOException {
        Path file = pluginRoot.resolve(dir).resolve(SERVICES_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", names));
        return file.toUri().toURL();
    }

    /** A services file in the directory {@code dir} that doesn't exist, so it can't be read. */
    private URL missingServicesFile(String dir) throws IOException {
        return pluginRoot.resolve(dir).resolve(SERVICES_FILE).toUri().toURL();
    }

    /**
     * A class loader that sees the library and lists the services files {@code servicesFiles} alone, in order. It also
     * lists {@link ExpressionLanguage}'s class file at a URL that is neither a jar nor a file, so it sees another copy
     * of the library.
     */
    private static URLClassLoader seesAnotherCopy(URL... servicesFiles) throws IOException {
        String classFile = ExpressionLanguage.class.getName().replace('.', '/') + ".class";
        URL elsewhere = URI.create("http://plugins.example/" + classFile).toURL();
        return new URLClassLoader(new URL[0], LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals(SERVICES_FILE)) {
                    return Collections.enumeration(List.of(servicesFiles));
                }
                List<URL> listed = Collections.list(super.getResources(name));
                if (name.equals(classFile)) {
                    listed.add(elsewhere);
                }
                return Collections.enumeration(listed);
            }
        };
    }

    /** Finds the languages {@code loader} lists, after this library's, and returns them with the logs. */
    private static Map.Entry<LanguageRegistry, String> discover(ClassLoader loader) {
        AtomicReference<LanguageRegistry> registry = new AtomicReference<>();
        String logs = logsOf(() -> registry.set(LanguageRegistry.resolve(Map.of(), "mvel",
                List.of(ImportResolver.LIBRARY_CLASS_LOADER, loader))));
        return Map.entry(registry.get(), logs);
    }

    @ParameterizedTest(name = "errors past the most skipped: {0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("a context class loader that sees another copy of the library has at most MAX_ERRORS_SKIPPED errors"
            + " skipped, so a language listed after that many is found, and one listed after more isn't")
    void errorsSkippedAtMost(int pastTheMost) throws IOException {
        // Each missing class is one error.
        int skipped = LanguageRegistry.MAX_ERRORS_SKIPPED;
        int errors = skipped + pastTheMost;
        String[] names = new String[errors + 1];
        for (int i = 0; i < errors; i++) {
            names[i] = "plugin.Missing" + i;
        }
        names[errors] = Found.class.getName();
        try (URLClassLoader loader = seesAnotherCopy(servicesFile("listing", names))) {
            Map.Entry<LanguageRegistry, String> found = discover(loader);
            String logs = found.getValue();

            assertEquals(errors <= skipped, found.getKey().languages().containsKey("found"), logs);
            assertEquals(Math.min(errors, skipped),
                    logs.split("Skipped an error finding expression languages", -1).length - 1);
            assertEquals(errors > skipped, logs.contains("Stopped finding expression languages with " + loader
                    + ": ServiceLoader reported more than " + skipped + " errors: " + ExpressionLanguage.class.getName()
                    + ": Provider plugin.Missing" + skipped + " not found"), logs);
        }
    }

    @Test
    @DisplayName("two services files in a row that can't be read are both skipped, each named, so a language listed"
            + " after them is found")
    void unreadableServicesFilesInARowSkipped() throws IOException {
        // ServiceLoader's message names no file for either, but the IOException it wraps does, and so does the log.
        try (URLClassLoader loader = seesAnotherCopy(missingServicesFile("first"), missingServicesFile("second"),
                servicesFile("listing", Found.class.getName()))) {
            Map.Entry<LanguageRegistry, String> found = discover(loader);
            String logs = found.getValue();

            assertTrue(found.getKey().languages().containsKey("found"), logs);
            assertEquals(2, logs.split("Skipped an error finding expression languages", -1).length - 1, logs);
            assertFalse(logs.contains("Stopped finding expression languages"), logs);
            for (String dir : List.of("first", "second")) {
                assertTrue(logs.contains(Path.of(dir, SERVICES_FILE).toString()), logs);
            }
            assertTrue(logs.contains(ExpressionLanguage.class.getName() + ": Error accessing configuration file: "
                    + FileNotFoundException.class.getName() + ": "), logs);
        }
    }

    @Test
    @DisplayName("a broken services file that a class loader and its parent both list is skipped twice, so a language"
            + " listed after it is found")
    void sameServicesFileTwiceSkipped() throws IOException {
        // Each loader lists the same directory, so the same error comes twice in a row.
        servicesFile("broken", "plugin.Bad Name");
        URL broken = pluginRoot.resolve("broken").toUri().toURL();
        String classFile = ExpressionLanguage.class.getName().replace('.', '/') + ".class";
        URL elsewhere = URI.create("http://plugins.example/" + classFile).toURL();
        try (URLClassLoader parent = new URLClassLoader(new URL[]{broken},
                LanguageDiscoveryTest.class.getClassLoader());
             URLClassLoader loader = new URLClassLoader(new URL[]{broken, servicesListing(Found.class.getName())},
                     parent) {
                 @Override
                 public Enumeration<URL> getResources(String name) throws IOException {
                     List<URL> listed = Collections.list(super.getResources(name));
                     if (name.equals(classFile)) {
                         listed.add(elsewhere);
                     }
                     return Collections.enumeration(listed);
                 }
             }) {
            Map.Entry<LanguageRegistry, String> found = discover(loader);
            String logs = found.getValue();

            assertTrue(found.getKey().languages().containsKey("found"), logs);
            assertEquals(2, logs.split("Illegal configuration-file syntax", -1).length - 1, logs);
            assertFalse(logs.contains("Stopped finding expression languages"), logs);
        }
    }

    @Test
    @DisplayName("the same error again after a language was found is skipped too, so a language listed after it is"
            + " found")
    void sameErrorAfterALanguageSkipped() throws IOException {
        URL missing = missingServicesFile("missing");
        try (URLClassLoader loader = seesAnotherCopy(missing, servicesFile("first", Found.class.getName()), missing,
                servicesFile("second", Listed.class.getName()))) {
            Map.Entry<LanguageRegistry, String> found = discover(loader);
            String logs = found.getValue();

            assertEquals(Set.of("mvel", "found", "listed"), found.getKey().languages().keySet(), logs);
            assertEquals(2, logs.split("Skipped an error finding expression languages", -1).length - 1, logs);
        }
    }

    @Test
    @DisplayName("a language that can't be created fails build() with ServiceLoader's error, unchanged, after an error"
            + " skipped on a context class loader that sees another copy of the library")
    void languageCantBeCreatedAfterASkippedError() throws IOException {
        try (URLClassLoader loader = seesAnotherCopy(servicesFile("listing", "plugin.Missing",
                CantBeCreated.class.getName()))) {
            ServiceConfigurationError error = withContextClassLoader(loader,
                    () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals("no licence for this language", error.getCause().getMessage());
        }
    }

    @Test
    @DisplayName("a listed class that isn't a language fails build() unchanged when its loader can't list class files")
    void notALanguageWhereClassFilesCantBeListed() throws IOException {
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader loader = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.endsWith(".class")) {
                    throw new IOException("the plug-in's jar index is broken");
                }
                return super.getResources(name);
            }
        }) {
            ServiceConfigurationError error = withContextClassLoader(loader,
                    () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

            assertEquals(ExpressionLanguage.class.getName() + ": " + NotALanguage.class.getName() + " not a subtype",
                    error.getMessage());
        }
    }

    @Test
    @DisplayName("a listed class that isn't a language fails build() unchanged when its loader lists the library's API"
            + " in a jar that can't be found, which is taken for the library's own")
    void notALanguageWhereTheApiIsListedInAMissingJar() throws IOException {
        String classFile = ExpressionLanguage.class.getName().replace('.', '/') + ".class";
        URL missing = URI.create("jar:" + pluginRoot.resolve("missing.jar").toUri() + "!/" + classFile).toURL();
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader loader = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                List<URL> listed = Collections.list(super.getResources(name));
                if (name.equals(classFile)) {
                    listed.add(missing);
                }
                return Collections.enumeration(listed);
            }
        }) {
            ServiceConfigurationError error = withContextClassLoader(loader,
                    () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

            assertEquals(ExpressionLanguage.class.getName() + ": " + NotALanguage.class.getName() + " not a subtype",
                    error.getMessage());
        }
    }

    @Test
    @DisplayName("a listed class that isn't a language fails build() unchanged when listing its loader's class files"
            + " throws a RuntimeException")
    void notALanguageWhereListingClassFilesThrows() throws IOException {
        URL[] urls = {servicesListing(NotALanguage.class.getName()), codeOf(NotALanguage.class)};
        try (URLClassLoader loader = new URLClassLoader(urls, LanguageDiscoveryTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.endsWith(".class")) {
                    throw new IllegalStateException("the plug-in's loader is closed");
                }
                return super.getResources(name);
            }
        }) {
            ServiceConfigurationError error = withContextClassLoader(loader,
                    () -> assertThrows(ServiceConfigurationError.class, () -> builder().build()));

            assertEquals(ExpressionLanguage.class.getName() + ": " + NotALanguage.class.getName() + " not a subtype",
                    error.getMessage());
        }
    }
}
