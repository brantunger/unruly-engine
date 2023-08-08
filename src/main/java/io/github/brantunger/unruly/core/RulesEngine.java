package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;
import java.util.Set;

/**
 * The RulesEngine fires the action expression from a list of {@link Rule} objects when their conditions evaluate to
 * <strong>true</strong>.
 *
 * @param <O> The output object type to instantiate
 */
public interface RulesEngine<O> {

    void setRuleList(List<Rule> ruleList);

    /**
     * Fire rules engine against the rules supplied by the rules list.
     *
     * @param facts The key/value fact store to run the rule engine against.
     * @return The output of firing the actions of each {@link Rule} object
     */
    O run(FactStore<Object> facts);

    /**
     * This method adds package imports to the #{@link org.mvel2.ParserContext} in order to speed up the execution of
     * rules and simplify the rule expression. You may want to use this if many of your rules require the same packages.
     *
     * @param packages A set of packages to import
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     */
    RulesEngine<O> addImports(Set<String> packages);

    /**
     * This adds a single package to the #{@link org.mvel2.ParserContext} in order to speed up the execution of
     * rules and simplify the rule expression. Add the fully qualified name of the package as a string. You may want to
     * use this if many of your rules require the same packages.
     *
     * @param packageString The package to import. Example: "java.util.Set"
     * @return A reference to this rules engine. This enables the use of the builder design pattern
     */
    RulesEngine<O> addImport(String packageString);
}
