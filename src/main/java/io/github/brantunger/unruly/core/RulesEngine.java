package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;

/**
 * The RulesEngine fires the action expression from a list of {@link Rule} objects when their conditions evaluate to
 * <strong>true</strong>.
 *
 * @param <O> The output object type to instantiate
 */
public interface RulesEngine<O> {

    /**
     * Fire rules engine against the rules supplied by the rules list.
     *
     * @param ruleList This is a list of {@link Rule} objects to run through the rules engine
     * @param facts    The key/value fact store to run the rule engine against.
     * @return The output of firing the actions of each {@link Rule} object
     */
    O run(List<Rule> ruleList, FactStore<Object> facts);
}
