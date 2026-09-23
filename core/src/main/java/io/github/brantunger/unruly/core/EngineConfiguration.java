package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The settings an engine is built with, as {@link io.github.brantunger.unruly.api.RulesEngineBuilder} collected them.
 * The engine resolves them when it's created. <b>Internal:</b> this record may change in any release.
 *
 * @param languages       The languages given to the builder, or none to find them with {@link java.util.ServiceLoader}
 * @param defaultLanguage The name of the default language, or {@code null} to use the only language
 * @param imports         The package and class names to import, not yet resolved
 * @param listeners       The listeners, in the order they're called
 * @param copyLimit       How many compiled copies of the rules runs may hold at once, and which runs that
 *                        applies to
 * @param copiesAtLoad    How many copies of the rules {@code load()} makes, zero or more
 * @param runTimeout      How long a run may take, or {@code null} if runs have no deadline
 * @param clock           The clock a run reads when it starts, to decide which rules are within their validity window
 * @param outputType      The output type languages are told about
 * @param outputWriter    Sets the properties actions return on the output object
 * @param declaredFacts   The declared type of each fact, by name, empty if none were declared. A primitive type is
 *                        kept as its wrapper, and no fact may be named {@code output}
 * @param allFactsDeclared Whether a run may supply only the declared facts
 * @param options         Each language's options, by language name
 * @param <O>             The type of the output object
 */
public record EngineConfiguration<O>(List<ExpressionLanguage> languages, String defaultLanguage, List<String> imports,
                                     List<RuleListener> listeners, CopyLimit copyLimit, int copiesAtLoad,
                                     Duration runTimeout, Clock clock,
                                     Class<? super O> outputType, OutputWriter<? super O> outputWriter,
                                     Map<String, Map<String, String>> options,
                                     Map<String, Class<?>> declaredFacts, boolean allFactsDeclared) {

    // The message for a null language name, language's options, option name or option value.
    private static final String NULL_OPTION = "options must not contain null";

    /**
     * Keeps unmodifiable copies of the lists, options and declarations, so later changes to the builder don't change
     * an engine. A declared type is checked and kept as {@link EngineCompileContext#declaredType(String, Class)} keeps
     * it.
     *
     * @throws NullPointerException     if an argument other than {@code defaultLanguage} and {@code runTimeout}, or an
     *                                  element, is {@code null}
     * @throws IllegalArgumentException if a fact is declared with the name {@code output}
     */
    public EngineConfiguration {
        Objects.requireNonNull(languages, "languages must not be null");
        Objects.requireNonNull(imports, "imports must not be null");
        Objects.requireNonNull(listeners, "listeners must not be null");
        Objects.requireNonNull(copyLimit, "copyLimit must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(outputType, "outputType must not be null");
        Objects.requireNonNull(outputWriter, "outputWriter must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(declaredFacts, "declaredFacts must not be null");
        for (ExpressionLanguage language : languages) {
            Objects.requireNonNull(language, "languages must not contain null");
        }
        for (String name : imports) {
            Objects.requireNonNull(name, "imports must not contain null");
        }
        for (RuleListener listener : listeners) {
            Objects.requireNonNull(listener, "listeners must not contain null");
        }
        options.forEach((language, values) -> {
            Objects.requireNonNull(language, NULL_OPTION);
            Objects.requireNonNull(values, NULL_OPTION);
            values.forEach((name, value) -> {
                Objects.requireNonNull(name, NULL_OPTION);
                Objects.requireNonNull(value, NULL_OPTION);
            });
        });
        languages = List.copyOf(languages);
        imports = List.copyOf(imports);
        listeners = List.copyOf(listeners);
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        options.forEach((language, values) -> copied.put(language, Map.copyOf(values)));
        options = Collections.unmodifiableMap(copied);
        // declaredType names a null fact name or type itself.
        Map<String, Class<?>> declared = new LinkedHashMap<>();
        declaredFacts.forEach((name, type) -> declared.put(name, EngineCompileContext.declaredType(name, type)));
        declaredFacts = Map.copyOf(declared);
    }
}
