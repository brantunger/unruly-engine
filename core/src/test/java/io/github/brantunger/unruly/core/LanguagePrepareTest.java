package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #945: building an engine calls {@link ExpressionLanguage#prepare()} on each language the builder names, its default
 * language included if the builder names it, on every build, once the settings are checked, so a language can
 * initialize its classes there rather than in a load or run that may be nested deep in another run's stack. Another
 * language the engine finds with {@link java.util.ServiceLoader}, even the only one, which is then the default, is
 * prepared when a rule list first uses it, once for its class. {@code DeepFirstBuildTest} checks that the room for it
 * is checked first at build, and {@code DeepFirstUseTest}, in the MVEL module, at a first use.
 */
@DisplayName("building an engine prepares the languages it is built to use, and a rule list the others (#945)")
class LanguagePrepareTest {

    private static final String SERVICES_FILE = "META-INF/services/" + ExpressionLanguage.class.getName();

    @TempDir
    Path servicesRoot;

    /**
     * A language a services file can list: the toy language under its name, which counts, for its class, how many
     * times it is prepared, as {@link java.util.ServiceLoader} creates a new one for each build.
     */
    public abstract static class Found implements ExpressionLanguage {
        private final String languageName;

        Found(String languageName) {
            this.languageName = languageName;
        }

        abstract AtomicInteger prepared();

        @Override
        public String name() {
            return languageName;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ToyExpressionLanguage(languageName).newCompiler(context);
        }

        @Override
        public void prepare() {
            prepared().incrementAndGet();
        }
    }

    /** The language found that the engine is told is the default. */
    public static final class FoundDefault extends Found {
        static final AtomicInteger PREPARED = new AtomicInteger();

        /** Creates it. */
        public FoundDefault() {
            super("found-default");
        }

        @Override
        AtomicInteger prepared() {
            return PREPARED;
        }
    }

    /** A language found that the builder doesn't name. */
    public static final class FoundUnnamed extends Found {
        static final AtomicInteger PREPARED = new AtomicInteger();

        /** Creates it. */
        public FoundUnnamed() {
            super("found-unnamed");
        }

        @Override
        AtomicInteger prepared() {
            return PREPARED;
        }
    }

    /** The only language found, which is the default without the builder naming it. */
    public static final class FoundOnly extends Found {
        static final AtomicInteger PREPARED = new AtomicInteger();

        /** Creates it. */
        public FoundOnly() {
            super("found-only");
        }

        @Override
        AtomicInteger prepared() {
            return PREPARED;
        }
    }

    /** The only language found, whose prepare() throws while {@link #THROWING} is set. */
    public static final class FoundThrowing extends Found {
        static final AtomicInteger PREPARED = new AtomicInteger();
        static final AtomicReference<RuntimeException> THROWING = new AtomicReference<>();

        /** Creates it. */
        public FoundThrowing() {
            super("found-throwing");
        }

        @Override
        AtomicInteger prepared() {
            return PREPARED;
        }

        @Override
        public void prepare() {
            super.prepare();
            RuntimeException e = THROWING.get();
            if (e != null) {
                throw e;
            }
        }
    }

    /** A language found that the builder gives an option. */
    public static final class FoundWithOption extends Found {
        static final AtomicInteger PREPARED = new AtomicInteger();

        /** Creates it. */
        public FoundWithOption() {
            super("found-option");
        }

        @Override
        AtomicInteger prepared() {
            return PREPARED;
        }
    }

    /** A language found that the builder gives imports of its own. */
    public static final class FoundWithImports extends Found {
        static final AtomicInteger PREPARED = new AtomicInteger();

        /** Creates it. */
        public FoundWithImports() {
            super("found-imports");
        }

        @Override
        AtomicInteger prepared() {
            return PREPARED;
        }
    }

    /** A class loader that sees the tests, and a services file listing {@code languages}. */
    private URLClassLoader listing(Class<?>... languages) throws IOException {
        Path file = servicesRoot.resolve(SERVICES_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", Arrays.stream(languages).map(Class::getName).toList()));
        return new URLClassLoader(new URL[]{servicesRoot.toUri().toURL()}, LanguagePrepareTest.class.getClassLoader());
    }

    private static Rule rule(String name, String language) {
        return Rule.builder().ruleName(name).language(language).condition("true").action("put k 1").build();
    }

    @Test
    @DisplayName("a language found that the builder doesn't name isn't prepared at build, but at its first use, once")
    void unnamedPreparedAtFirstUse() throws IOException {
        try (URLClassLoader loader = listing(FoundDefault.class, FoundUnnamed.class)) {
            RulesEngine<Map<String, Object>> engine = withContextClassLoader(loader,
                    () -> RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                            .defaultLanguage("found-default").build());
            int defaultPrepared = FoundDefault.PREPARED.get();

            assertEquals(0, FoundUnnamed.PREPARED.get(), "the unnamed language prepared at build");
            engine.load(List.of(rule("default", null)));
            assertEquals(0, FoundUnnamed.PREPARED.get(), "the unnamed language prepared by a list that doesn't use it");
            engine.load(List.of(rule("unnamed", "found-unnamed")));
            assertEquals(1, FoundUnnamed.PREPARED.get(), "the unnamed language prepared at its first use");
            engine.load(List.of(rule("again", "found-unnamed")));
            engine.validate(List.of(rule("validated", "found-unnamed")));
            withContextClassLoader(loader, () -> RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .defaultLanguage("found-default").build().load(List.of(rule("other", "found-unnamed"))));

            assertEquals(1, FoundUnnamed.PREPARED.get(), "the unnamed language prepared again");
            assertTrue(defaultPrepared >= 1, "the default language prepared at build");
            assertEquals(defaultPrepared + 1, FoundDefault.PREPARED.get(), "the default prepared by the next build");
            engine.close();
        }
    }

    @Test
    @DisplayName("the only language found, the default unnamed, isn't prepared at build, but at its first use, once")
    void onlyFoundPreparedAtFirstUse() throws IOException {
        try (URLClassLoader loader = listing(FoundOnly.class)) {
            RulesEngine<Map<String, Object>> engine = withContextClassLoader(loader,
                    () -> RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build());

            assertEquals(0, FoundOnly.PREPARED.get(), "the only language prepared at build");
            engine.load(List.of(rule("default", null)));
            assertEquals(1, FoundOnly.PREPARED.get(), "the only language prepared at its first use");
            engine.load(List.of(rule("again", null)));
            withContextClassLoader(loader, () -> RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .build().load(List.of(rule("other", null))));

            assertEquals(1, FoundOnly.PREPARED.get(), "the only language prepared again");
            engine.close();
        }
    }

    @Test
    @DisplayName("what prepare() throws at a first use fails the rule list as the language failing to prepare, and the "
            + "next use prepares it again")
    void throwAtFirstUseFailsLoad() throws IOException {
        try (URLClassLoader loader = listing(FoundThrowing.class)) {
            RulesEngine<Map<String, Object>> engine = withContextClassLoader(loader,
                    () -> RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build());
            IllegalStateException thrown = new IllegalStateException("thrown by prepare()");
            FoundThrowing.THROWING.set(thrown);

            RuleCompilationException failure = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule("first", null))));
            FoundThrowing.THROWING.set(null);
            engine.load(List.of(rule("again", null)));

            assertEquals("The 'found-throwing' expression language failed to prepare: thrown by prepare()",
                    failure.getMessage());
            assertSame(thrown, failure.getCause(), "cause");
            assertEquals(2, FoundThrowing.PREPARED.get(), "prepared");
            engine.close();
        }
    }

    @Test
    @DisplayName("a language found that the builder gives options or imports is prepared at build")
    void namedBySettingsPreparedAtBuild() throws IOException {
        try (URLClassLoader loader = listing(FoundDefault.class, FoundWithOption.class, FoundWithImports.class)) {
            withContextClassLoader(loader, () -> RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .defaultLanguage("found-default").option("found-option", "some", "value")
                    .languageImports("found-imports", "anything").build().close());

            assertEquals(1, FoundWithOption.PREPARED.get(), "the language given an option prepared");
            assertEquals(1, FoundWithImports.PREPARED.get(), "the language given imports prepared");
        }
    }

    /** A language that counts its prepare() calls, and throws from it what it is given, if anything. */
    static class Preparing implements ExpressionLanguage {
        private final String name;
        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicReference<RuntimeException> throwing = new AtomicReference<>();

        Preparing(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new StubExpressionLanguage().newCompiler(context);
        }

        @Override
        public void prepare() {
            prepared.incrementAndGet();
            RuntimeException e = throwing.get();
            if (e != null) {
                throw e;
            }
        }

        /** How many times it has been prepared. */
        int prepared() {
            return prepared.get();
        }
    }

    @Test
    @DisplayName("each language is prepared on every build")
    void preparedOnEveryBuild() {
        Preparing first = new Preparing("first");
        Preparing second = new Preparing("second");

        for (int build = 0; build < 2; build++) {
            RulesEngineBuilder.firstMatch(Object::new).language(first).language(second).defaultLanguage("first")
                    .build().close();
        }

        assertEquals(2, first.prepared(), "first prepared");
        assertEquals(2, second.prepared(), "second prepared");
    }

    @Test
    @DisplayName("a build whose settings are wrong prepares no language")
    void notPreparedWhenSettingsFail() {
        Preparing language = new Preparing("only");

        assertThrows(IllegalStateException.class, () -> RulesEngineBuilder.firstMatch(Object::new).language(language)
                .defaultLanguage("missing").build());

        assertEquals(0, language.prepared(), "prepared");
    }

    @Test
    @DisplayName("what prepare() throws fails the build unchanged, and the next build prepares the language again")
    void throwFailsBuild() {
        Preparing language = new Preparing("throwing");
        IllegalStateException failure = new IllegalStateException("thrown by prepare()");
        language.throwing.set(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> RulesEngineBuilder.firstMatch(Object::new).language(language).build()));
        language.throwing.set(null);
        RulesEngineBuilder.firstMatch(Object::new).language(language).build().close();

        assertEquals(2, language.prepared(), "prepared");
    }

    @Test
    @DisplayName("by default, prepare() does nothing, and an engine with such a language builds")
    void defaultDoesNothing() {
        ExpressionLanguage language = new ExpressionLanguage() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throw new UnsupportedOperationException("never compiles");
            }
        };

        assertDoesNotThrow(language::prepare);
        assertDoesNotThrow(() -> RulesEngineBuilder.firstMatch(Object::new).language(language).build().close());
    }
}
