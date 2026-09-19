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
 * @param declaredFacts   The declared type of each fact, by name, empty if none were declared
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

    /**
     * Keeps unmodifiable copies of the lists and options, so later changes to the builder don't change an engine.
     *
     * @throws NullPointerException if an argument other than {@code defaultLanguage} and {@code runTimeout}, or an
     *                              element, is {@code null}
     */
    public EngineConfiguration {
        languages = List.copyOf(languages);
        imports = List.copyOf(imports);
        listeners = List.copyOf(listeners);
        Objects.requireNonNull(copyLimit, "copyLimit");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(outputWriter, "outputWriter");
        declaredFacts = Map.copyOf(declaredFacts);
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        options.forEach((language, values) -> copied.put(language, Map.copyOf(values)));
        options = Collections.unmodifiableMap(copied);
    }
}
