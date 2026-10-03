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
 * @param languages       The languages given to the builder, by the name each had when it was added, or none to find
 *                        them with {@link java.util.ServiceLoader}
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
 *                        kept as it was declared, not as its wrapper, so a run widens a boxed primitive to it, and no
 *                        fact may have a blank name. The engine rejects a name one of its languages reserves, once it
 *                        has found them
 * @param allFactsDeclared Whether a run may supply only the declared facts
 * @param options         Each language's options, by language name
 * @param languageImports Each language's own imports, by language name, as written and in order, not resolved
 * @param <O>             The type of the output object
 */
public record EngineConfiguration<O>(Map<String, ExpressionLanguage> languages, String defaultLanguage,
                                     List<String> imports, List<RuleListener> listeners, CopyLimit copyLimit,
                                     int copiesAtLoad, Duration runTimeout, Clock clock,
                                     Class<? super O> outputType, OutputWriter<? super O> outputWriter,
                                     Map<String, Map<String, String>> options,
                                     Map<String, Class<?>> declaredFacts, boolean allFactsDeclared,
                                     Map<String, List<String>> languageImports) {

    // The message for a null language name, language's options, option name or option value.
    private static final String NULL_OPTION = "options must not contain null";
    // The message for a null language name, language's imports or import.
    private static final String NULL_LANGUAGE_IMPORT = "languageImports must not contain null";

    /**
     * Keeps unmodifiable copies of the languages, lists, options, declarations and language imports, so later changes
     * to the builder don't change an engine. A declared type is checked as
     * {@link EngineCompileContext#checkDeclaration(String, Class)} checks it, and kept as it was declared. A declared
     * name a language reserves isn't checked here: the languages may be found only when the engine is created.
     *
     * @throws NullPointerException     if an argument other than {@code defaultLanguage} and {@code runTimeout}, or an
     *                                  element, a language's name or a language, is {@code null}
     * @throws IllegalArgumentException if a fact is declared with a blank name
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
        Objects.requireNonNull(languageImports, "languageImports must not be null");
        languages.forEach((name, language) -> {
            Objects.requireNonNull(name, "languages must not contain null");
            Objects.requireNonNull(language, "languages must not contain null");
        });
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
        languageImports.forEach((language, names) -> {
            Objects.requireNonNull(language, NULL_LANGUAGE_IMPORT);
            Objects.requireNonNull(names, NULL_LANGUAGE_IMPORT);
            for (String name : names) {
                Objects.requireNonNull(name, NULL_LANGUAGE_IMPORT);
            }
        });
        languages = Map.copyOf(languages);
        imports = List.copyOf(imports);
        listeners = List.copyOf(listeners);
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        options.forEach((language, values) -> copied.put(language, Map.copyOf(values)));
        options = Collections.unmodifiableMap(copied);
        // checkDeclaration names a null fact name or type itself.
        declaredFacts.forEach(EngineCompileContext::checkDeclaration);
        declaredFacts = Map.copyOf(declaredFacts);
        Map<String, List<String>> copiedImports = new LinkedHashMap<>();
        languageImports.forEach((language, names) -> copiedImports.put(language, List.copyOf(names)));
        languageImports = Collections.unmodifiableMap(copiedImports);
    }

    // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods, which
    // can fail for good when first called deep in the stack (#996). equals compares the components last first, as
    // ObjectMethods does.
    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof EngineConfiguration<?> that
                && Objects.equals(languageImports, that.languageImports) && allFactsDeclared == that.allFactsDeclared
                && Objects.equals(declaredFacts, that.declaredFacts) && Objects.equals(options, that.options)
                && Objects.equals(outputWriter, that.outputWriter) && Objects.equals(outputType, that.outputType)
                && Objects.equals(clock, that.clock) && Objects.equals(runTimeout, that.runTimeout)
                && copiesAtLoad == that.copiesAtLoad && Objects.equals(copyLimit, that.copyLimit)
                && Objects.equals(listeners, that.listeners) && Objects.equals(imports, that.imports)
                && Objects.equals(defaultLanguage, that.defaultLanguage) && Objects.equals(languages, that.languages);
    }

    @Override
    public final int hashCode() {
        int hash = Objects.hashCode(languages);
        hash = hash * 31 + Objects.hashCode(defaultLanguage);
        hash = hash * 31 + Objects.hashCode(imports);
        hash = hash * 31 + Objects.hashCode(listeners);
        hash = hash * 31 + Objects.hashCode(copyLimit);
        hash = hash * 31 + Integer.hashCode(copiesAtLoad);
        hash = hash * 31 + Objects.hashCode(runTimeout);
        hash = hash * 31 + Objects.hashCode(clock);
        hash = hash * 31 + Objects.hashCode(outputType);
        hash = hash * 31 + Objects.hashCode(outputWriter);
        hash = hash * 31 + Objects.hashCode(options);
        hash = hash * 31 + Objects.hashCode(declaredFacts);
        hash = hash * 31 + Boolean.hashCode(allFactsDeclared);
        return hash * 31 + Objects.hashCode(languageImports);
    }

    @Override
    public final String toString() {
        return "EngineConfiguration[languages=" + languages + ", defaultLanguage=" + defaultLanguage
                + ", imports=" + imports + ", listeners=" + listeners + ", copyLimit=" + copyLimit
                + ", copiesAtLoad=" + copiesAtLoad + ", runTimeout=" + runTimeout + ", clock=" + clock
                + ", outputType=" + outputType + ", outputWriter=" + outputWriter + ", options=" + options
                + ", declaredFacts=" + declaredFacts + ", allFactsDeclared=" + allFactsDeclared
                + ", languageImports=" + languageImports + "]";
    }
}
