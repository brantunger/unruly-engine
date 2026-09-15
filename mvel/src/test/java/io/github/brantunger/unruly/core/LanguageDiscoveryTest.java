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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("expression languages are found with ServiceLoader each time a rule list is loaded")
class LanguageDiscoveryTest {

    private static final String SERVICES_FILE = "META-INF/services/" + ExpressionLanguage.class.getName();

    private final RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);

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

    private static Rule rule(String name, String language, String condition, String action) {
        return Rule.builder().ruleName(name).language(language).condition(condition).action(action).build();
    }

    /** A class loader that sees the library, and a services file listing {@code languages}. */
    private URLClassLoader listing(Class<?>... languages) throws IOException {
        Path file = servicesRoot.resolve(SERVICES_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Arrays.stream(languages).map(Class::getName).collect(Collectors.joining("\n")));
        return new URLClassLoader(new URL[]{servicesRoot.toUri().toURL()}, LanguageDiscoveryTest.class.getClassLoader());
    }

    private static <T> T withContextClassLoader(ClassLoader loader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private void load(ClassLoader contextClassLoader, List<Rule> rules) {
        withContextClassLoader(contextClassLoader, () -> {
            engine.setRuleList(rules);
            return null;
        });
    }

    @Test
    @DisplayName("a language listed in a services file the context class loader sees is used without registering it")
    void foundWithContextClassLoader() throws IOException {
        List<Rule> rules = List.of(rule("toy", "found", "true", "put k 1"),
                rule("mvel", null, "true", "output.put('m', 2)"));

        try (URLClassLoader loader = listing(Found.class)) {
            load(loader, rules);
        }

        assertEquals(Map.of("k", 1, "m", 2), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("languages are found again for each rule list")
    void foundForEachRuleList() throws IOException {
        List<Rule> rules = List.of(rule("toy", "found", "true", "put k 1"));
        assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        try (URLClassLoader loader = listing(Found.class)) {
            load(loader, rules);
        }

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("MVEL is found with this library's class loader when the thread has no context class loader")
    void mvelFoundWithoutContextClassLoader() {
        load(null, List.of(rule("mvel", null, "true", "output.put('m', 1)")));

        assertEquals(Map.of("m", 1), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a registered language replaces a found language with the same name")
    void registeredReplacesFound() throws IOException {
        engine.registerLanguage(new NamedLanguage("found") {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throw new IllegalStateException("the registered one");
            }
        });
        List<Rule> rules = List.of(rule("toy", "found", "true", "put k 1"));

        try (URLClassLoader loader = listing(Found.class)) {
            RuleCompilationException ex = withContextClassLoader(loader,
                    () -> assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules)));

            assertEquals("The 'found' expression language failed to create a compiler: the registered one",
                    ex.getMessage());
        }
    }

    @Test
    @DisplayName("two found languages with the same name fail setRuleList, naming both")
    void sameNameTwice() throws IOException {
        List<Rule> rules = List.of(rule("mvel", null, "true", "output.put('m', 1)"));

        try (URLClassLoader loader = listing(Found.class, AlsoFound.class)) {
            RuleCompilationException ex = withContextClassLoader(loader,
                    () -> assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules)));

            assertEquals("Failed to find the expression languages: The expression languages " + Found.class.getName()
                    + " and " + AlsoFound.class.getName() + " found with ServiceLoader are both named 'found'",
                    ex.getMessage());
            assertInstanceOf(IllegalStateException.class, ex.getCause());
            assertNull(ex.getRuleName());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {BlankName.class, NoName.class})
    @DisplayName("a found language with a null or blank name fails setRuleList")
    void nullOrBlankName(Class<?> language) throws IOException {
        List<Rule> rules = List.of(rule("mvel", null, "true", "output.put('m', 1)"));

        try (URLClassLoader loader = listing(language)) {
            RuleCompilationException ex = withContextClassLoader(loader,
                    () -> assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules)));

            assertEquals("Failed to find the expression languages: The expression language " + language.getName()
                    + " found with ServiceLoader has a null or blank name", ex.getMessage());
        }
    }

    @Test
    @DisplayName("a found language that can't be created fails setRuleList with ServiceLoader's error, unchanged")
    void languageCantBeCreated() throws IOException {
        List<Rule> rules = List.of(rule("mvel", null, "true", "output.put('m', 1)"));

        try (URLClassLoader loader = listing(CantBeCreated.class)) {
            ServiceConfigurationError error = withContextClassLoader(loader,
                    () -> assertThrows(ServiceConfigurationError.class, () -> engine.setRuleList(rules)));

            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals("no licence for this language", error.getCause().getMessage());
        }
    }
}
