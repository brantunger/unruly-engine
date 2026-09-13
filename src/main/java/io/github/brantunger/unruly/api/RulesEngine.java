package io.github.brantunger.unruly.api;



import java.util.List;
import java.util.Set;

/**
 * The RulesEngine fires the action expression from a list of {@link Rule} objects when their conditions evaluate to
 * <strong>true</strong>.
 *
 * @param <O> The output object type to instantiate
 */
public interface RulesEngine<O> {

    /**
     * Set the rule list for use in processing rules through the rules engine
     * @param ruleList The list of {@link Rule} objects
     * @throws io.github.brantunger.unruly.api.exception.RuleCompilationException if a rule fails to compile, has a
     *         null or blank condition or action, has a condition that contains an assignment, shares its name with
     *         another rule, or if the list contains a {@code null} rule
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
     *         an action fails, a condition doesn't evaluate to a boolean, or the output factory throws or returns
     *         {@code null}
     * @throws IllegalArgumentException if a fact is named {@code output}, or has a name rules can't refer to (not
     *         a Java identifier, a reserved MVEL word such as {@code empty} or {@code in}, or a class name MVEL
     *         resolves, such as {@code Math} or a class from an imported package)
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    O run(FactStore<Object> facts);

    /**
     * Adds package imports that rules are compiled with, so rule expressions can refer to classes in those packages
     * by their simple names. Takes effect at the next {@link #setRuleList(List)}.
     *
     * @param packages A set of packages to import
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     * @throws NullPointerException if {@code packages} or any element is {@code null}
     */
    RulesEngine<O> addImports(Set<String> packages);

    /**
     * Adds a single package import that rules are compiled with, given its fully qualified name. Takes effect at
     * the next {@link #setRuleList(List)}.
     *
     * @param packageString The package to import. Example: "java.util"
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     * @throws NullPointerException if {@code packageString} is {@code null}
     */
    RulesEngine<O> addImport(String packageString);

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
