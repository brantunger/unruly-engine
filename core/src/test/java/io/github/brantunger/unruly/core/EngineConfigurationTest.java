package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an engine's configuration names the argument or collection that holds a null")
class EngineConfigurationTest {

    private static final List<ExpressionLanguage> LANGUAGES = List.of(new ToyExpressionLanguage());
    private static final CopyLimit LIMIT = CopyLimit.none();
    private static final Clock CLOCK = Clock.systemUTC();
    private static final OutputWriter<Object> WRITER = OutputWriter.beansAndMaps();

    private static EngineConfiguration<Object> configuration(List<ExpressionLanguage> languages, List<String> imports,
                                                             List<RuleListener> listeners, CopyLimit copyLimit,
                                                             Clock clock, Class<Object> outputType,
                                                             OutputWriter<Object> outputWriter,
                                                             Map<String, Map<String, String>> options,
                                                             Map<String, Class<?>> declaredFacts) {
        return new EngineConfiguration<>(languages, null, imports, listeners, copyLimit, 0, null, clock, outputType,
                outputWriter, options, declaredFacts, false);
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

    static Stream<Arguments> nulls() {
        return Stream.of(
                Arguments.of("a null languages", "languages must not be null", (Executable) () -> configuration(
                        null, List.of(), List.of(), LIMIT, CLOCK, Object.class, WRITER, Map.of(), Map.of())),
                Arguments.of("a null imports", "imports must not be null", (Executable) () -> configuration(
                        LANGUAGES, null, List.of(), LIMIT, CLOCK, Object.class, WRITER, Map.of(), Map.of())),
                Arguments.of("a null listeners", "listeners must not be null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), null, LIMIT, CLOCK, Object.class, WRITER, Map.of(), Map.of())),
                Arguments.of("a null copyLimit", "copyLimit must not be null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), List.of(), null, CLOCK, Object.class, WRITER, Map.of(), Map.of())),
                Arguments.of("a null clock", "clock must not be null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), List.of(), LIMIT, null, Object.class, WRITER, Map.of(), Map.of())),
                Arguments.of("a null outputType", "outputType must not be null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, null, WRITER, Map.of(), Map.of())),
                Arguments.of("a null outputWriter", "outputWriter must not be null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class, null, Map.of(), Map.of())),
                Arguments.of("a null options", "options must not be null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class, WRITER, null, Map.of())),
                Arguments.of("a null declaredFacts", "declaredFacts must not be null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class, WRITER, Map.of(), null)),
                Arguments.of("a null language", "languages must not contain null", (Executable) () -> configuration(
                        Collections.singletonList(null), List.of(), List.of(), LIMIT, CLOCK, Object.class, WRITER,
                        Map.of(), Map.of())),
                Arguments.of("a null import", "imports must not contain null", (Executable) () -> configuration(
                        LANGUAGES, Collections.singletonList(null), List.of(), LIMIT, CLOCK, Object.class, WRITER,
                        Map.of(), Map.of())),
                Arguments.of("a null listener", "listeners must not contain null", (Executable) () -> configuration(
                        LANGUAGES, List.of(), Collections.singletonList(null), LIMIT, CLOCK, Object.class, WRITER,
                        Map.of(), Map.of())),
                Arguments.of("a null language name in the options", "options must not contain null",
                        (Executable) () -> configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class,
                                WRITER, options(null, Map.of("strict", "true")), Map.of())),
                Arguments.of("a null language's options", "options must not contain null",
                        (Executable) () -> configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class,
                                WRITER, options("toy", null), Map.of())),
                Arguments.of("a null option name", "options must not contain null",
                        (Executable) () -> configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class,
                                WRITER, options("toy", option(null, "true")), Map.of())),
                Arguments.of("a null option value", "options must not contain null",
                        (Executable) () -> configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class,
                                WRITER, options("toy", option("strict", null)), Map.of())),
                // As in EngineCompileContext, the declared type's own check names a null fact name or type.
                Arguments.of("a null declared fact name", "name must not be null",
                        (Executable) () -> configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class,
                                WRITER, Map.of(), declared(null, String.class))),
                Arguments.of("a null declared fact type", "type must not be null",
                        (Executable) () -> configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class,
                                WRITER, Map.of(), declared("name", null))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nulls")
    @DisplayName("a null argument or element is named in the message")
    void namesTheNull(String description, String message, Executable creation) {
        assertEquals(message, assertThrows(NullPointerException.class, creation).getMessage());
    }

    @Test
    @DisplayName("the arguments are checked before the elements, each in parameter order")
    void firstNullWins() {
        assertAll(
                () -> assertEquals("languages must not be null", assertThrows(NullPointerException.class,
                        () -> configuration(null, List.of(), List.of(), LIMIT, CLOCK, Object.class, WRITER,
                                Map.of(), null)).getMessage()),
                () -> assertEquals("copyLimit must not be null", assertThrows(NullPointerException.class,
                        () -> configuration(Collections.singletonList(null), List.of(), List.of(), null, CLOCK,
                                Object.class, WRITER, Map.of(), Map.of())).getMessage()),
                () -> assertEquals("languages must not contain null", assertThrows(NullPointerException.class,
                        () -> configuration(Collections.singletonList(null), Collections.singletonList(null),
                                List.of(), LIMIT, CLOCK, Object.class, WRITER, Map.of(), Map.of())).getMessage()),
                () -> assertEquals("imports must not contain null", assertThrows(NullPointerException.class,
                        () -> configuration(LANGUAGES, Collections.singletonList(null),
                                Collections.singletonList(null), LIMIT, CLOCK, Object.class, WRITER, Map.of(),
                                Map.of())).getMessage()),
                () -> assertEquals("listeners must not contain null", assertThrows(NullPointerException.class,
                        () -> configuration(LANGUAGES, List.of(), Collections.singletonList(null), LIMIT, CLOCK,
                                Object.class, WRITER, options("toy", option("strict", null)), Map.of())).getMessage()),
                () -> assertEquals("options must not contain null", assertThrows(NullPointerException.class,
                        () -> configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK, Object.class, WRITER,
                                options("toy", option("strict", null)), declared(null, String.class)))
                        .getMessage()));
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
    @DisplayName("a declared fact is kept as it was declared, a primitive type too, and can't be named output")
    void declaredFactsChecked() {
        EngineConfiguration<Object> configuration = configuration(LANGUAGES, List.of(), List.of(), LIMIT, CLOCK,
                Object.class, WRITER, Map.of(), Map.of("count", int.class));

        assertEquals(Map.of("count", int.class), configuration.declaredFacts());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> configuration(LANGUAGES,
                List.of(), List.of(), LIMIT, CLOCK, Object.class, WRITER, Map.of(), Map.of("output", String.class)));
        assertEquals("'output' is reserved for the output object and cannot be declared as a fact", ex.getMessage());
    }

    @Test
    @DisplayName("the lists, options and declarations are unmodifiable copies, detached from the caller's")
    void keepsDetachedCopies() {
        List<ExpressionLanguage> languages = new ArrayList<>(LANGUAGES);
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
}
