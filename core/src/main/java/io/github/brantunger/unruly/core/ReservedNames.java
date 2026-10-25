package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The fact names an engine's languages reserve, as {@link FactNames} reads them when the engine is built, asking each
 * language once: the names each language reserves, which of the languages reserve theirs only for the rule lists that
 * use them, and the names the others reserve for every rule list, each with the language that reserves it. Every
 * language the engine has counts, not only those it prepares when it's built: a run's facts reach every rule, so a name
 * any of them reserves is rejected whatever language the rules are written in. Fixed when the engine is built, and
 * read by its compiler of rule lists and its fact intake.
 */
final class ReservedNames {

    // The names each language reserves, by language name, sorted by it.
    private final Map<String, Set<String>> byLanguage;
    // The names of the languages that reserve their names only for the rule lists that use them.
    private final Set<String> perRuleListLanguages;
    // The language that reserves each name the others reserve for every rule list, by name, and the names alone.
    private final Map<String, String> languageByName;
    private final Set<String> everyRuleListNames;

    /**
     * Reads the names an engine's languages reserve, asking each language once.
     *
     * @param languages The engine's languages, by name
     * @throws IllegalStateException if a language's {@link ExpressionLanguage#reservedFactNames()} returns
     *                               {@code null}, or a set holding {@code null}
     */
    ReservedNames(Map<String, ExpressionLanguage> languages) {
        this.byLanguage = FactNames.reservedByLanguage(languages);
        this.perRuleListLanguages = FactNames.reservedPerRuleList(languages);
        Set<String> reservedForEveryRuleList = new HashSet<>(byLanguage.keySet());
        reservedForEveryRuleList.removeAll(perRuleListLanguages);
        this.languageByName = FactNames.reserved(byLanguage, reservedForEveryRuleList);
        this.everyRuleListNames = Set.copyOf(languageByName.keySet());
    }

    /**
     * Returns the fact names the engine's languages reserve for every rule list, which no declared fact or run's fact
     * has.
     *
     * @return The names; unmodifiable, and the same set each call
     */
    Set<String> forEveryRuleList() {
        return everyRuleListNames;
    }

    /**
     * Returns the language that reserves a fact name for every rule list.
     *
     * @param name The fact name, not {@code null}
     * @return The language's name, the first by name where several reserve it, or {@code null} if none reserves it so
     */
    String languageReserving(String name) {
        return languageByName.get(name);
    }

    /**
     * Returns whether any of the engine's languages reserves its names only for the rule lists that use it.
     *
     * @return {@code true} if one does
     */
    boolean anyPerRuleList() {
        return !perRuleListLanguages.isEmpty();
    }

    /**
     * Returns whether a language reserves its names only for the rule lists that use it.
     *
     * @param language A language name, which may be one the engine doesn't have
     * @return {@code true} if it is one of the engine's languages and does
     */
    boolean perRuleList(String language) {
        return perRuleListLanguages.contains(language);
    }

    /**
     * Returns the names one of the engine's languages reserves.
     *
     * @param language The name of one of the engine's languages
     * @return The names; unmodifiable
     */
    Set<String> of(String language) {
        return byLanguage.get(language);
    }

    /**
     * Returns the fact names some of the engine's languages reserve, each with the language that reserves it, as
     * {@link FactNames#reserved(Map, Set)} does.
     *
     * @param languages The names of the languages whose names to return
     * @return The language that reserves each name, by name, the first by name where several do; unmodifiable
     */
    Map<String, String> reservedBy(Set<String> languages) {
        return FactNames.reserved(byLanguage, languages);
    }
}
