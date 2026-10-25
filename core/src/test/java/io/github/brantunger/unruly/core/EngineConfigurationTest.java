package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an engine's configuration keeps unmodifiable copies of the builder's settings")
class EngineConfigurationTest {

    private static final Map<String, ExpressionLanguage> LANGUAGES = Map.of(ToyExpressionLanguage.LANGUAGE_NAME,
            new ToyExpressionLanguage());
    private static final CopyLimit LIMIT = CopyLimit.none();
    private static final Clock CLOCK = Clock.systemUTC();
    private static final OutputWriter<Object> WRITER = OutputWriter.beansAndMaps();

    private static EngineConfiguration<Object> configuration(Map<String, ExpressionLanguage> languages,
                                                             List<String> imports,
                                                             List<RuleListener> listeners, CopyLimit copyLimit,
                                                             Clock clock, Class<Object> outputType,
                                                             OutputWriter<Object> outputWriter,
                                                             Map<String, Map<String, String>> options,
                                                             Map<String, Class<?>> declaredFacts) {
        return new EngineConfiguration<>(languages, null, imports, listeners, copyLimit, 0, null, clock, outputType,
                outputWriter, options, declaredFacts, false, Map.of());
    }

    private static Map<String, Map<String, String>> options(String language, Map<String, String> values) {
        Map<String, Map<String, String>> options = new HashMap<>();
        options.put(language, values);
        return options;
    }

    private static Map<String, String> option(String name, String value) {
        Map<String, String> values = new HashMap<>();
        values.put(name, value);
        return values;
    }

    private static Map<String, Class<?>> declared(String name, Class<?> type) {
        Map<String, Class<?>> facts = new HashMap<>();
        facts.put(name, type);
        return facts;
    }

    @Test
    @DisplayName("the default language and the run timeout may be null")
    void optionalArguments() {
        EngineConfiguration<Object> configuration = configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK,
                Object.class, WRITER, Map.of(), Map.of());

        assertNull(configuration.defaultLanguage());
        assertNull(configuration.runTimeout());
    }

    @Test
    @DisplayName("a declared fact is kept as it was declared, a primitive type too, and one named output too: the"
            + " engine rejects the names its languages reserve, which may be found only once it's created")
    void declaredFactsChecked() {
        EngineConfiguration<Object> configuration = configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK,
                Object.class, WRITER, Map.of(), Map.of("count", int.class));

        assertEquals(Map.of("count", int.class), configuration.declaredFacts());
        assertEquals(Map.of("output", String.class), configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK,
                Object.class, WRITER, Map.of(), Map.of("output", String.class)).declaredFacts());
    }

    @Test
    @DisplayName("the languages, lists, options and declarations are unmodifiable copies, detached from the caller's")
    void keepsDetachedCopies() {
        Map<String, ExpressionLanguage> languages = new HashMap<>(LANGUAGES);
        List<String> imports = new ArrayList<>(List.of("java.util"));
        RuleListener listener = new RuleListener() {
        };
        List<RuleListener> listeners = new ArrayList<>(List.of(listener));
        Map<String, String> values = option("strict", "true");
        Map<String, Map<String, String>> options = options("toy", values);
        Map<String, Class<?>> declaredFacts = declared("name", String.class);

        EngineConfiguration<Object> configuration = configuration(languages, imports, listeners, LIMIT, CLOCK,
                Object.class, WRITER, options, declaredFacts);
        languages.clear();
        imports.add("java.io");
        listeners.clear();
        values.put("strict", "false");
        options.put("other", Map.of());
        declaredFacts.put("age", Integer.class);

        assertEquals(LANGUAGES, configuration.languages());
        assertEquals(List.of("java.util"), configuration.imports());
        assertEquals(List.of(listener), configuration.listeners());
        assertEquals(Map.of("toy", Map.of("strict", "true")), configuration.options());
        assertEquals(Map.of("name", String.class), configuration.declaredFacts());
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> configuration.languages().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> configuration.imports().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> configuration.listeners().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> configuration.options().clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> configuration.options().get("toy").clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> configuration.declaredFacts().clear()));
    }

    private static EngineConfiguration<Object> withLanguageImports(Map<String, List<String>> languageImports) {
        return new EngineConfiguration<>(LANGUAGES, null, List.of(), List.of(), LIMIT, 0, null, CLOCK, Object.class,
                WRITER, Map.of(), Map.of(), false, languageImports);
    }

    private static Map<String, List<String>> languageImports(String language, List<String> names) {
        Map<String, List<String>> languageImports = new HashMap<>();
        languageImports.put(language, names);
        return languageImports;
    }

    @Test
    @DisplayName("the language imports are unmodifiable copies, kept as written")
    void languageImportsCopied() {
        List<String> names = new ArrayList<>(List.of("lodash/fp", "lodash/fp"));
        Map<String, List<String>> languageImports = languageImports("toy", names);

        EngineConfiguration<Object> configuration = withLanguageImports(languageImports);
        names.add("@acme/pricing");
        languageImports.put("other", List.of());

        assertEquals(Map.of("toy", List.of("lodash/fp", "lodash/fp")), configuration.languageImports());
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> configuration.languageImports().clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> configuration.languageImports().get("toy").clear()));
    }
}
