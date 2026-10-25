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
 *                        kept as it was declared, not as its wrapper, so a run widens a boxed primitive to it. The
 *                        engine rejects a blank name, and, once it has found its languages, a name one of them reserves
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

    /**
     * Keeps unmodifiable copies of the languages, lists, options, declarations and language imports, so later changes
     * to the builder don't change an engine. Nothing is checked here: the builder checks each setting as it's given,
     * and the engine checks them against each other when it's created (see EngineSettings). A declared type is kept as
     * it was declared.
     *
     * @throws NullPointerException if a map or list argument is {@code null}, or holds {@code null} where
     *                              {@link Map#copyOf(Map)} or {@link List#copyOf(java.util.Collection)} rejects it
     */
    public EngineConfiguration {
        languages = Map.copyOf(languages);
        imports = List.copyOf(imports);
        listeners = List.copyOf(listeners);
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        options.forEach((language, values) -> copied.put(language, Map.copyOf(values)));
        options = Collections.unmodifiableMap(copied);
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
