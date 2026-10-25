package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.internal.ImportSize;
import io.github.brantunger.unruly.internal.Text;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An engine's settings, resolved from the builder's when the engine is created: its languages and default language,
 * its imports looked up, the names its languages reserve, and the settings it keeps as the builder gave them, checked
 * against each other. Its compiler of rule lists, its fact intake and its rule-set lifecycle are built from them.
 * Resolved before the classes engines use that have a static initializer are initialized, by an engine created
 * without {@link Engines}, so it has none itself (see {@link RunClasses}). A plain class, not a record: nothing
 * compares or prints one, and a record's own methods would link through {@code ObjectMethods}, which can fail for good
 * when first called deep in the stack (#996).
 */
// Each accessor is named after the field it returns, as a record's are.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class EngineSettings {

    // The engine's languages and its default language.
    private final LanguageRegistry languages;
    // The languages the engine is built to use, which it prepares when it's built, the default first if it's named.
    private final List<ExpressionLanguage> languagesInUse;
    // The imported packages and classes, resolved.
    private final Set<String> packageImports;
    private final Set<Class<?>> classImports;
    // The fact names the engine's languages reserve.
    private final ReservedNames reservedNames;
    // The settings kept as the builder gave them.
    private final Class<?> outputType;
    private final Map<String, Map<String, String>> options;
    private final Map<String, Class<?>> declaredFacts;
    private final boolean allFactsDeclared;
    private final Map<String, List<String>> languageImports;
    private final CopyLimit copyLimit;
    private final int copiesAtLoad;
    private final Clock clock;

    private EngineSettings(LanguageRegistry languages, List<ExpressionLanguage> languagesInUse,
                           Set<String> packageImports, Set<Class<?>> classImports, ReservedNames reservedNames,
                           EngineConfiguration<?> configuration) {
        this.languages = languages;
        this.languagesInUse = languagesInUse;
        this.packageImports = packageImports;
        this.classImports = classImports;
        this.reservedNames = reservedNames;
        this.outputType = configuration.outputType();
        this.options = configuration.options();
        this.declaredFacts = configuration.declaredFacts();
        this.allFactsDeclared = configuration.allFactsDeclared();
        this.languageImports = configuration.languageImports();
        this.copyLimit = configuration.copyLimit();
        this.copiesAtLoad = configuration.copiesAtLoad();
        this.clock = configuration.clock();
    }

    /**
     * Resolves the builder's settings: checks each declared fact as
     * {@link EngineCompileContext#checkDeclaration(String, Class)} does, takes or finds the languages and picks the
     * default one, reads the names they reserve, checks the declared names, options and language imports against
     * them, and resolves the imports with the building thread's context class loader, in that order.
     *
     * @param configuration The builder's settings
     * @return The settings, resolved
     * @throws IllegalArgumentException if a fact is declared with a blank name, or one of the engine's languages
     *                                  reserves for every rule list; if a language's own import has more than 1,000
     *                                  characters; or if an import can't be resolved, as
     *                                  {@link ImportResolver#resolve(String)} describes
     * @throws IllegalStateException    if the languages or the default language can't be resolved, a language's
     *                                  {@link ExpressionLanguage#reservedFactNames()} returns {@code null} or a set
     *                                  holding {@code null}, or options or imports are given for a language the
     *                                  engine doesn't have
     */
    static EngineSettings resolve(EngineConfiguration<?> configuration) {
        // checkDeclaration names a null fact name or type itself.
        for (Map.Entry<String, Class<?>> fact : configuration.declaredFacts().entrySet()) {
            EngineCompileContext.checkDeclaration(fact.getKey(), fact.getValue());
        }
        LanguageRegistry languages = LanguageRegistry.resolve(configuration.languages(),
                configuration.defaultLanguage(), ImportResolver.contextClassLoader());
        // A language that reserves its names only for the rule lists that use them has them checked when such a rule
        // list is loaded, and run.
        ReservedNames reservedNames = new ReservedNames(languages.languages());
        for (String name : configuration.declaredFacts().keySet()) {
            String language = reservedNames.languageReserving(name);
            if (language != null) {
                throw new IllegalArgumentException(FactNames.reservedMessage(name, language, true));
            }
        }
        // Checked once the languages are known, so an option for a language that isn't found isn't silently ignored.
        for (String language : configuration.options().keySet()) {
            checkLanguageKnown(languages, language, "Options");
        }
        // A language's own imports aren't looked up, so only their length is checked.
        for (Map.Entry<String, List<String>> imports : configuration.languageImports().entrySet()) {
            checkLanguageKnown(languages, imports.getKey(), "Imports");
            for (String name : imports.getValue()) {
                ImportSize.checkLength(name);
            }
        }
        Set<String> packages = new LinkedHashSet<>();
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (String name : configuration.imports()) {
            Class<?> type = ImportResolver.resolve(name);
            if (type != null) {
                classes.add(type);
            } else {
                packages.add(name);
            }
        }
        return new EngineSettings(languages, languagesInUse(languages, configuration),
                Collections.unmodifiableSet(packages), Collections.unmodifiableSet(classes), reservedNames,
                configuration);
    }

    /**
     * Returns the languages an engine is built to use, which it prepares when it's built: every language the builder
     * named, by naming it with {@code defaultLanguage()}, giving it with {@code language()}, or giving it options with
     * {@code option()} or imports with {@code languageImports()}. A language found with
     * {@link java.util.ServiceLoader} that none of those names, even the only one found, which is then the default, is
     * prepared when a rule list first uses it, so building an engine loads none of its classes.
     *
     * @param languages     The engine's languages, already checked against the settings
     * @param configuration The builder's settings
     * @return The languages, the default first if the builder named it; unmodifiable
     */
    private static List<ExpressionLanguage> languagesInUse(LanguageRegistry languages,
                                                           EngineConfiguration<?> configuration) {
        Set<String> names = new LinkedHashSet<>();
        if (configuration.defaultLanguage() != null) {
            names.add(configuration.defaultLanguage());
        }
        names.addAll(configuration.languages().keySet());
        names.addAll(configuration.options().keySet());
        names.addAll(configuration.languageImports().keySet());
        List<ExpressionLanguage> inUse = new ArrayList<>();
        for (String name : names) {
            inUse.add(languages.languages().get(name));
        }
        return List.copyOf(inUse);
    }

    /**
     * Fails unless a language that options or imports are given for is one of the engine's languages.
     *
     * @param languages The engine's languages
     * @param language  The name the setting was given for
     * @param setting   What was given for it, {@code Options} or {@code Imports}, which starts the message
     * @throws IllegalStateException if the engine has no language named {@code language}
     */
    private static void checkLanguageKnown(LanguageRegistry languages, String language, String setting) {
        if (!languages.languages().containsKey(language)) {
            throw new IllegalStateException(setting + " are given for the expression language '"
                    + Text.quote(language) + "', which isn't one of the engine's expression languages: "
                    + Text.quoteAll(new TreeSet<>(languages.languages().keySet())));
        }
    }

    /**
     * Returns the engine's languages and its default language.
     *
     * @return The languages
     */
    LanguageRegistry languages() {
        return languages;
    }

    /**
     * Returns the languages the engine is built to use, which it prepares when it's built (see
     * {@link #resolve(EngineConfiguration)}).
     *
     * @return The languages, the default first if the builder named it; unmodifiable
     */
    List<ExpressionLanguage> languagesInUse() {
        return languagesInUse;
    }

    /**
     * Returns the imported packages.
     *
     * @return The packages, in the order imported; unmodifiable
     */
    Set<String> packageImports() {
        return packageImports;
    }

    /**
     * Returns the imported classes.
     *
     * @return The classes, in the order imported; unmodifiable
     */
    Set<Class<?>> classImports() {
        return classImports;
    }

    /**
     * Returns the fact names the engine's languages reserve.
     *
     * @return The names
     */
    ReservedNames reservedNames() {
        return reservedNames;
    }

    /**
     * Returns the output type languages are told about.
     *
     * @return The type
     */
    Class<?> outputType() {
        return outputType;
    }

    /**
     * Returns each language's options.
     *
     * @return The options, by language name
     */
    Map<String, Map<String, String>> options() {
        return options;
    }

    /**
     * Returns the declared type of each fact, a primitive type as it was declared.
     *
     * @return The types, by fact name
     */
    Map<String, Class<?>> declaredFacts() {
        return declaredFacts;
    }

    /**
     * Returns whether a run may supply only the declared facts.
     *
     * @return {@code true} if it may
     */
    boolean allFactsDeclared() {
        return allFactsDeclared;
    }

    /**
     * Returns each language's own imports, as written.
     *
     * @return The imports, by language name
     */
    Map<String, List<String>> languageImports() {
        return languageImports;
    }

    /**
     * Returns how many compiled copies of the rules runs may hold at once, and which runs that applies to.
     *
     * @return The limit
     */
    CopyLimit copyLimit() {
        return copyLimit;
    }

    /**
     * Returns how many copies of the rules {@code load()} makes, before runs can see them.
     *
     * @return The count, zero or more
     */
    int copiesAtLoad() {
        return copiesAtLoad;
    }

    /**
     * Returns the clock a run reads when it starts, to decide which rules are within their validity window.
     *
     * @return The clock
     */
    Clock clock() {
        return clock;
    }
}
