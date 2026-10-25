package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of one loaded rule list as {@code load()} compiled them, and what their runs check their facts against: the
 * compilers of the languages they use, the facts those languages' rules read and the names they reserve. Also whether
 * those languages have finished a run, their checksum and when they were loaded. None of its fields changes once it's
 * made, though the flags of whether a language has run are set as runs finish, and every run of the rule list shares
 * it. The engine makes it with {@link RuleListCompiler.Compilation#loadedRules()}, and its {@link RuleSet} holds it.
 * <b>Internal:</b> this class may change in any release.
 */
final class LoadedRules {

    private final List<CompiledRule> compiledRules;
    private final Map<String, ExpressionCompiler> compilers;
    // What a run checks its facts' names against besides the compilers: the facts the rules of each language that can
    // tell read, and the names the languages the rules use reserve only for the rule lists that use them.
    private final Map<String, Set<String>> namesRead;
    private final Map<String, String> reservedNames;
    // Whether a run has finished with a language of each class these rules use.
    private final LanguagesRan ran;
    // Identify these rules, and when they were loaded, for RulesEngine.rules() and every run's result.
    private final String ruleChecksum;
    private final Instant loadTime;

    /**
     * Creates the loaded rules, with their checksum, loaded now.
     *
     * @param compiledRules     The compiled rules, in the order they run
     * @param compilers         The compilers of the languages the rules use, by language name, in the order they
     *                          check fact names
     * @param namesRead         The names of the facts each compiler's rules read, by language name, for the compilers
     *                          that can tell; empty when none can
     * @param reservedFactNames The language that reserves each name, by name, of the languages the rules use that
     *                          reserve their names only for the rule lists that use them; empty when none does
     * @param languages         The languages the rules use, by name, whose first runs {@link #languagesRan()} tells of:
     *                          not the default language a rule list without rules is checked against, as no action of
     *                          it can run
     */
    LoadedRules(List<CompiledRule> compiledRules, Map<String, ExpressionCompiler> compilers,
                Map<String, Set<String>> namesRead, Map<String, String> reservedFactNames,
                Map<String, ExpressionLanguage> languages) {
        this.compiledRules = List.copyOf(compiledRules);
        this.compilers = Collections.unmodifiableMap(new LinkedHashMap<>(compilers));
        this.namesRead = Map.copyOf(namesRead);
        this.reservedNames = Map.copyOf(reservedFactNames);
        this.ran = new LanguagesRan(languages);
        this.ruleChecksum = Checksums.ofRules(this.compiledRules);
        this.loadTime = Instant.now();
    }

    /**
     * Returns the rules as {@code load()} compiled them, which every run shares.
     *
     * @return An unmodifiable list of the compiled rules
     */
    List<CompiledRule> rules() {
        return compiledRules;
    }

    /**
     * Returns the checksum that identifies these rules, as
     * {@link io.github.brantunger.unruly.api.RuleSetInfo#checksum()} describes it.
     *
     * @return The checksum
     */
    String checksum() {
        return ruleChecksum;
    }

    /**
     * Returns when these rules were compiled.
     *
     * @return The time the loaded rules were created
     */
    Instant loadedAt() {
        return loadTime;
    }

    /**
     * Returns the compilers that check the fact names of a run of these rules, and create its sessions.
     *
     * @return An unmodifiable map of the compilers by language name, in the order they check
     */
    Map<String, ExpressionCompiler> factChecks() {
        return compilers;
    }

    /**
     * Returns the names of the facts the rules of each language read, for the languages whose compilers can tell (see
     * {@link ExpressionCompiler#factNamesRead()}): a run asks such a language to check only those names.
     *
     * @return An unmodifiable map of the names by language name; empty when no compiler can tell
     */
    Map<String, Set<String>> factNamesRead() {
        return namesRead;
    }

    /**
     * Returns the names that the languages these rules use reserve only for the rule lists that use them (see
     * {@link io.github.brantunger.unruly.api.language.ExpressionLanguage#reservesForEveryRuleList()}), which no fact
     * of a run of these rules may have.
     *
     * @return An unmodifiable map of the language that reserves each name, by name; empty when no language does
     */
    Map<String, String> reservedFactNames() {
        return reservedNames;
    }

    /**
     * Returns whether a run has finished with each language these rules use, which tells a run of them whether it
     * needs the room for a language's first run.
     *
     * @return The languages' first-run flags
     */
    LanguagesRan languagesRan() {
        return ran;
    }
}
