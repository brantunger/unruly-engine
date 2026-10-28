package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import io.github.brantunger.unruly.internal.Text;
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
     * Shortens one name to 200 characters, then escapes it, as {@link Text#quote} does.
     *
     * @param name The name, or {@code null}
     * @return The name, shortened to 200 characters, then escaped, or {@code null} as string concatenation shows it
     */
    static String quote(@Nullable String name) {
        return name == null ? "null" : Text.quote(name);
    }

    /**
     * Shortens each name to 200 characters, then escapes it, for a {@code toString()} that shows them as a list.
     *
     * @param names The names
     * @return Each name, shortened to 200 characters, then escaped, in the same order
     */
    static List<String> quoteEach(Collection<? extends @Nullable String> names) {
        // A loop, as Text.quoteAll: RunOptions.toString() runs with no engine built, and the JVM's first stream
        // pipeline deep in a stack could overflow inside java.util.stream.StreamOpFlag's static initializer, which
        // leaves every later stream, and every engine build, failing (#1302).
        List<String> quoted = new ArrayList<>(names.size());
        for (String name : names) {
            quoted.add(quote(name));
        }
        return Collections.unmodifiableList(quoted);
    }

    /**
     * The names of rules, in the same order, for a {@code toString()} that shows them through {@link #quoteEach}. A
     * loop, as {@link #quoteEach}: a result or a rule set of the application's own is made with no engine built.
     *
     * @param rules The rules
     * @return Each rule's name, in the same order
     */
    static List<String> ruleNames(Collection<Rule> rules) {
        List<String> names = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            names.add(rule.getRuleName());
        }
        return names;
    }

    /**
     * Shows a list of names in a message, as {@link Text#quoteAll} does: each name shortened, the list shortened
     * to 1,000 characters, and the whole escaped.
     *
     * @param names The names; a {@code null} one is shown as {@code null}
     * @return The list, such as {@code [eu, retail]}
     */
    static String quoteAll(Collection<? extends @Nullable String> names) {
        return Text.quoteAll(names);
    }
}
