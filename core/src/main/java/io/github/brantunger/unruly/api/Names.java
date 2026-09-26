package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.core.Failures;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Shows names as the engine's error messages show them, shortened to 200 characters, then escaped, so a name from
 * request data can't start a log line of its own: fact names in the messages of {@link FactMap}, tags in those of
 * {@link Rule} and {@link RunOptions}, an expression language's name in those of {@link RulesEngineBuilder}, and rule
 * names and tags in the {@code toString()} of {@link RunResult}, {@link RuleSetInfo}, {@link RuleEvaluation} and
 * {@link RunOptions}. It delegates to the engine's own escaping rather than keeping a copy of it.
 */
final class Names {

    private Names() {
    }

    /**
     * Shortens one name to 200 characters, then escapes it, as {@link Failures#quote} does.
     *
     * @param name The name, or {@code null}
     * @return The name, shortened to 200 characters, then escaped, or {@code null} as string concatenation shows it
     */
    static String quote(@Nullable String name) {
        return name == null ? "null" : Failures.quote(name);
    }

    /**
     * Shortens each name to 200 characters, then escapes it, for a {@code toString()} that shows them as a list.
     *
     * @param names The names
     * @return Each name, shortened to 200 characters, then escaped, in the same order
     */
    static List<String> quoteEach(Collection<? extends @Nullable String> names) {
        return names.stream().map(Names::quote).toList();
    }

    /**
     * Shows a list of names in a message, as {@link Failures#quoteAll} does: each name shortened, the list shortened
     * to 1,000 characters, and the whole escaped.
     *
     * @param names The names; a {@code null} one is shown as {@code null}
     * @return The list, such as {@code [eu, retail]}
     */
    static String quoteAll(Collection<? extends @Nullable String> names) {
        return Failures.quoteAll(names);
    }
}
