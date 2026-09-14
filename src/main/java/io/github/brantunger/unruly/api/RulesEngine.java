package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;

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
 * @param <O> The output object type to instantiate
 */
public interface RulesEngine<O> {

    /**
     * Set the rule list for use in processing rules through the rules engine
     * @param ruleList The list of {@link Rule} objects
     * @throws io.github.brantunger.unruly.api.exception.RuleCompilationException if a rule fails to compile, has a
     *         null or blank condition or action, has a condition that contains an assignment or
     *         {@code import_static}, shares its name with
     *         another rule, names an expression language that isn't registered, or if the list contains a
     *         {@code null} rule. Also if an expression language throws while creating its compiler, or returns
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
     *         or a class from an imported package. A rule list without rules is checked against MVEL.
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    O run(FactStore<Object> facts);

    /**
     * Adds imports that rules are compiled with, so rule expressions can refer to classes by their simple names.
     * Each string is a fully qualified package name ({@code "java.util"}) or class name
     * ({@code "java.time.LocalDate"}, or {@code "java.util.Map.Entry"} for a nested class). Takes effect at the next
     * {@link #setRuleList(List)}.
     *
     * <p>
     * Whether a string names a class is decided when this method is called, by the calling thread's context class
     * loader, or this library's class loader if the thread has none. A string that loader can't load as a class, but
     * that is a valid package name, is imported as a package. Classes in imported packages are looked up when
     * {@link #setRuleList(List)} is called, with that thread's context class loader.
     * </p>
     *
     * @param packages A set of packages or classes to import
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     * @throws IllegalArgumentException if a string is neither a loadable class nor a valid package name; nothing is
     *         imported
     * @throws NullPointerException if {@code packages} or any element is {@code null}
     */
    RulesEngine<O> addImports(Set<String> packages);

    /**
     * Adds a single import that rules are compiled with: a fully qualified package name ({@code "java.util"}) or
     * class name ({@code "java.time.LocalDate"}, or {@code "java.util.Map.Entry"} for a nested class). Takes effect at
     * the next {@link #setRuleList(List)}. A class name is resolved as {@link #addImports(Set)} describes.
     *
     * @param packageString The package or class to import. Example: "java.util"
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     * @throws IllegalArgumentException if the string is neither a loadable class nor a valid package name
     * @throws NullPointerException if {@code packageString} is {@code null}
     */
    RulesEngine<O> addImport(String packageString);

    /**
     * Registers an expression language that rules can be written in, chosen by each rule's {@code language}. MVEL is
     * registered from the start; a language with the same name as a registered one replaces it, so registering a
     * language named {@code "mvel"} changes the language of every rule whose {@code language} is {@code null}. Takes
     * effect at the next {@link #setRuleList(List)}.
     *
     * <p>
     * An implementation of this interface that doesn't support other languages keeps this default, which throws.
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
     * @param listener The listener to register.
     * @return A reference to this rules engine.
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    RulesEngine<O> registerListener(RuleListener listener);

    /**
     * Registers a list of {@link RuleListener} to monitor rule evaluation and execution.
     *
     * @param listeners The list of listeners to register.
     * @return A reference to this rules engine.
     * @throws NullPointerException if {@code listeners} or any element is {@code null}; nothing is registered
     */
    RulesEngine<O> registerListeners(List<RuleListener> listeners);
}
