package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The RulesEngine fires the action expression from a list of {@link Rule} objects when their conditions evaluate to
 * <strong>true</strong>. Create one with {@link RulesEngineBuilder}.
 *
 * <p>
 * <b>Lifecycle:</b> register any imports with {@link #addImport(String)} and any expression languages besides MVEL
 * with {@link #registerLanguage(ExpressionLanguage)}, compile the rules once with
 * {@link #setRuleList(List)}, then call {@link #run(FactStore)} as often as needed, from any number of threads.
 * Rules are evaluated in descending priority order; equal priorities keep their list order, and a {@code null}
 * priority sorts last. {@code setRuleList} may be called again at any time to swap in new rules atomically.
 * </p>
 *
 * <p>
 * <b>2.0:</b> imports, expression languages and listeners are expected to be set when the engine is built, rather than
 * on an engine that may already be running, so {@link #addImports(Set)}, {@link #addImport(String)},
 * {@link #registerLanguage(ExpressionLanguage)}, {@link #registerListener(RuleListener)} and
 * {@link #registerListeners(List)} are expected to move to the engine builder. They aren't deprecated yet, because
 * 1.x has no replacement.
 * </p>
 *
 * <p>
 * <b>Implementing:</b> you may implement this interface, for example to decorate an engine or as a test double. A
 * method added in a 1.x release is a {@code default} method, so an existing implementation keeps compiling. A default
 * that can't be implemented generically, such as {@link #registerLanguage(ExpressionLanguage)}, throws
 * {@link UnsupportedOperationException}; override it to support the feature.
 * </p>
 *
 * @param <O> The output object type to instantiate
 */
public interface RulesEngine<O> extends AutoCloseable {

    /**
     * Set the rule list for use in processing rules through the rules engine
     * @param ruleList The list of {@link Rule} objects
     * @throws io.github.brantunger.unruly.api.exception.RuleCompilationException if a rule fails to compile, has a
     *         null or blank condition or action, has a condition that contains an assignment or
     *         {@code import_static}, shares its name with
     *         another rule, names an expression language that isn't registered, or if the list contains a
     *         {@code null} rule. Also if finding the expression languages fails, for example because two found
     *         languages have the same name, or if an expression language throws while creating its compiler, or returns
     *         {@code null} instead of a compiler or a compiled expression. An {@link Error} other than
     *         {@link StackOverflowError} or {@link AssertionError} thrown while compiling, also as the cause of another
     *         exception, is logged and then rethrown unchanged.
     * @throws NullPointerException if {@code ruleList} itself is {@code null}
     */
    void setRuleList(List<Rule> ruleList);

    /**
     * Fire rules engine against the rules supplied by the rules list.
     *
     * @param facts The key/value fact store to run the rule engine against.
     * @return The output of firing the actions of the matching {@link Rule} objects, or {@code null} if the rule
     *         list is empty or no rule matched
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException if evaluating a condition or executing
     *         an action fails, a condition doesn't evaluate to a boolean, the output factory throws or returns
     *         {@code null}, or a compiled condition or action throws or returns {@code null} when it is copied for the
     *         run
     * @throws IllegalArgumentException if a fact is named {@code output} or {@code null}, or has a name that the
     *         language of a loaded rule can't refer to. In MVEL, that is a name that isn't a Java identifier, a
     *         reserved word such as {@code empty} or {@code in}, or a class name MVEL resolves, such as {@code Math}
     *         or a class from an imported package. A rule list without rules is checked against MVEL. Also if a
     *         language's check of a fact name fails with any other exception, which becomes the cause.
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    @Nullable O run(FactStore<@Nullable Object> facts);

    /**
     * Adds imports that rules are compiled with, so rule expressions can refer to classes by their simple names.
     * Each string is a fully qualified package name ({@code "java.util"}) or class name
     * ({@code "java.time.LocalDate"}, or {@code "java.util.Map.Entry"} for a nested class). Takes effect at the next
     * {@link #setRuleList(List)}.
     *
     * <p>
     * Whether a string names a class is decided when this method is called, by the calling thread's context class
     * loader, or this library's class loader if the thread has none. A string that loader doesn't find as a class, but
     * that is a valid package name, is imported as a package. Classes in imported packages are looked up when
     * {@link #setRuleList(List)} is called, with that thread's context class loader.
     * </p>
     *
     * <p>
     * <b>2.0:</b> expected to move to the engine builder; see the class description.
     * </p>
     *
     * @param packages A set of packages or classes to import
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     * @throws IllegalArgumentException if a string is neither a loadable class nor a valid package name, or names a
     *         class that exists but can't be loaded, for example because a class it depends on is missing; nothing is
     *         imported
     * @throws NullPointerException if {@code packages} or any element is {@code null}
     */
    RulesEngine<O> addImports(Set<String> packages);

    /**
     * Adds a single import that rules are compiled with: a fully qualified package name ({@code "java.util"}) or
     * class name ({@code "java.time.LocalDate"}, or {@code "java.util.Map.Entry"} for a nested class). Takes effect at
     * the next {@link #setRuleList(List)}. A class name is resolved as {@link #addImports(Set)} describes.
     *
     * <p>
     * <b>2.0:</b> expected to move to the engine builder; see the class description.
     * </p>
     *
     * @param packageString The package or class to import. Example: "java.util"
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     * @throws IllegalArgumentException if the string is neither a loadable class nor a valid package name, or names a
     *         class that exists but can't be loaded
     * @throws NullPointerException if {@code packageString} is {@code null}
     */
    RulesEngine<O> addImport(String packageString);

    /**
     * Registers an expression language that rules can be written in, chosen by each rule's {@code language}. Languages
     * listed in a {@code META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage} file, MVEL
     * among them, are found with {@link java.util.ServiceLoader} whenever {@link #setRuleList(List)} is called. A
     * registered language replaces a registered or found language with the same name, so registering a language named
     * {@code "mvel"} changes the language of every rule whose {@code language} is {@code null}. Takes effect at the next
     * {@link #setRuleList(List)}.
     *
     * <p>
     * An implementation of this interface that doesn't support other languages keeps this default, which throws.
     * </p>
     *
     * <p>
     * <b>2.0:</b> expected to move to the engine builder; see the class description.
     * </p>
     *
     * @param language The language to register
     * @return A reference to this rules engine
     * @throws IllegalArgumentException if the language's name is {@code null} or blank
     * @throws NullPointerException if {@code language} is {@code null}
     * @throws UnsupportedOperationException if this engine doesn't support other expression languages
     */
    default RulesEngine<O> registerLanguage(ExpressionLanguage language) {
        throw new UnsupportedOperationException(getClass().getName() + " only supports MVEL rules");
    }

    /**
     * Registers a single {@link RuleListener} to monitor rule evaluation and execution.
     *
     * <p>
     * <b>2.0:</b> expected to move to the engine builder; see the class description.
     * </p>
     *
     * @param listener The listener to register.
     * @return A reference to this rules engine.
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    RulesEngine<O> registerListener(RuleListener listener);

    /**
     * Registers a list of {@link RuleListener} to monitor rule evaluation and execution.
     *
     * <p>
     * <b>2.0:</b> expected to move to the engine builder; see the class description.
     * </p>
     *
     * @param listeners The list of listeners to register.
     * @return A reference to this rules engine.
     * @throws NullPointerException if {@code listeners} or any element is {@code null}; nothing is registered
     */
    RulesEngine<O> registerListeners(List<RuleListener> listeners);

    /**
     * Closes the engine, and releases what its expression languages hold for the rules, such as interpreter contexts.
     * Runs in progress finish first: the languages' sessions are closed as each run returns, and their compilers after
     * the last one. Afterwards, {@link #run(FactStore)} and {@link #setRuleList(List)} throw
     * {@link IllegalStateException}. Closing an engine that is already closed does nothing.
     *
     * <p>
     * A failure to close a session or a compiler is logged at WARN and not thrown, except a fatal {@link Error}, which
     * is rethrown unchanged. By default, this method does nothing.
     * </p>
     */
    @Override
    default void close() {
        // Nothing to release.
    }
}
